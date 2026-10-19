package com.westly.neribovault.feature.coder.env

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** The one place this DataStore name is declared. It is not the app's settings file and it is not backed up. */
private val Context.coderEnvDataStore: DataStore<Preferences> by preferencesDataStore(name = "neribo_coder_env")

private const val KEY_PREFIX = "env_"
private const val MAX_PROJECT_ID_LENGTH = 64
private const val SEPARATOR = ":"

/**
 * The environment variables of every Coder project, one encrypted entry per project (`env_<projectId>`). The entry
 * is a JSON list of name and value pairs sealed with [CoderEnvKeyCrypto] (a new random IV for every write). Nothing
 * here is ever logged, synced, backed up, sent to an AI, saved in screen state or written into a project folder.
 * Every function returns "nothing" or false on a failure instead of throwing.
 */
class CoderEnvStore private constructor(context: Context) {
    private val store: DataStore<Preferences> = context.applicationContext.coderEnvDataStore

    private val data: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    /**
     * The variables of [projectId], in the order they were saved. Empty when there are none, when the id is not a
     * plain id, or when the entry cannot be decrypted. The result is also put in [CoderEnvCache].
     */
    suspend fun load(projectId: String): List<EnvVar> {
        val key = keyFor(projectId) ?: return emptyList()
        val stored = data.first()[key]
        val vars: List<EnvVar> = if (stored == null) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) { decode(stored) } ?: emptyList()
        }
        CoderEnvCache.put(projectId, vars)
        return vars
    }

    /**
     * Encrypts and saves [vars] for [projectId], and updates [CoderEnvCache]. Returns false when a name or value
     * breaks the rules, a name appears twice, there are more than 50 variables, the phone cannot encrypt, or the
     * write fails. An empty list removes the entry.
     */
    suspend fun save(projectId: String, vars: List<EnvVar>): Boolean {
        val key = keyFor(projectId) ?: return false
        if (vars.size > CoderEnvRules.MAX_VARIABLES) return false
        if (vars.any { !CoderEnvRules.isValidName(it.name) || CoderEnvRules.validateValue(it.value) != null }) return false
        if (vars.map { it.name }.toSet().size != vars.size) return false
        if (vars.isEmpty()) {
            return try {
                store.edit { prefs -> prefs.remove(key) }
                CoderEnvCache.put(projectId, vars)
                true
            } catch (e: IOException) {
                false
            }
        }
        val bytes = encode(vars).toByteArray(Charsets.UTF_8)
        val sealed = try {
            withContext(Dispatchers.IO) { CoderEnvKeyCrypto.encrypt(bytes) }
        } finally {
            bytes.fill(0)
        }
        if (sealed == null) return false
        return try {
            store.edit { prefs -> prefs[key] = sealed.iv + SEPARATOR + sealed.ciphertext }
            CoderEnvCache.put(projectId, vars)
            true
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Removes the entry of every project that is not in [validProjectIds] (live projects and projects in the trash).
     * The caller must pass the real, loaded lists: an empty set removes everything.
     */
    suspend fun purgeOrphans(validProjectIds: Set<String>) {
        try {
            store.edit { prefs ->
                val stale = prefs.asMap().keys
                    .map { it.name }
                    .filter { it.startsWith(KEY_PREFIX) && it.removePrefix(KEY_PREFIX) !in validProjectIds }
                for (name in stale) prefs.remove(stringPreferencesKey(name))
            }
        } catch (e: IOException) {
            return
        }
        CoderEnvCache.retainOnly(validProjectIds)
    }

    private fun keyFor(projectId: String): Preferences.Key<String>? {
        val plain = projectId.isNotEmpty() && projectId.length <= MAX_PROJECT_ID_LENGTH &&
            projectId.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
        return if (plain) stringPreferencesKey(KEY_PREFIX + projectId) else null
    }

    private fun encode(vars: List<EnvVar>): String {
        val list = JSONArray()
        for (variable in vars) list.put(JSONObject().put("n", variable.name).put("v", variable.value))
        return list.toString()
    }

    /** The variables inside a stored entry, or null when it is damaged or cannot be decrypted. */
    private fun decode(stored: String): List<EnvVar>? {
        val parts = stored.split(SEPARATOR)
        if (parts.size != 2) return null
        val bytes = CoderEnvKeyCrypto.decrypt(ciphertext = parts[1], iv = parts[0]) ?: return null
        return try {
            val list = JSONArray(String(bytes, Charsets.UTF_8))
            val out = ArrayList<EnvVar>(list.length())
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                val name = item.getString("n")
                val value = item.getString("v")
                if (CoderEnvRules.isValidName(name) && CoderEnvRules.validateValue(value) == null) out.add(EnvVar(name, value))
            }
            out
        } catch (e: JSONException) {
            null
        } finally {
            bytes.fill(0)
        }
    }

    companion object {
        @Volatile
        private var instance: CoderEnvStore? = null

        /** The one store of the app process. It is created the first time it is asked for and holds only the application context. */
        fun shared(context: Context): CoderEnvStore {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                val again = instance
                if (again != null) {
                    again
                } else {
                    val created = CoderEnvStore(context.applicationContext)
                    instance = created
                    created
                }
            }
        }

        /** The store when [shared] was called before, else null. */
        internal fun existing(): CoderEnvStore? = instance
    }
}

/**
 * The variables of the projects that were opened, kept in memory so the preview can answer `env.js` without waiting
 * for the disk. It is updated on every save and never written anywhere.
 */
object CoderEnvCache {
    private val cache = ConcurrentHashMap<String, Map<String, String>>()

    /** The variables of [projectId] as name to value; empty when none are loaded. Never waits. */
    fun get(projectId: String): Map<String, String> = cache[projectId] ?: emptyMap()

    /** Loads [projectId] from the store the first time it is needed. Does nothing when it is loaded or when no store was created yet. */
    suspend fun ensureLoaded(projectId: String) {
        if (cache.containsKey(projectId)) return
        CoderEnvStore.existing()?.load(projectId)
    }

    internal fun put(projectId: String, vars: List<EnvVar>) {
        val map = LinkedHashMap<String, String>()
        for (variable in vars) map[variable.name] = variable.value
        cache[projectId] = map
    }

    internal fun retainOnly(validProjectIds: Set<String>) {
        for (id in cache.keys.toList()) {
            if (id !in validProjectIds) cache.remove(id)
        }
    }

    /** Forgets everything. The self-test uses it so a check never sees what another left behind. */
    internal fun clear() {
        cache.clear()
    }
}
