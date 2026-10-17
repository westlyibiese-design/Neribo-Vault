package com.westly.neribovault.feature.coder

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

/** The one place this DataStore name is declared. It is not the app's settings file and it is not backed up. */
private val Context.coderPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "neribo_coder_prefs")

/**
 * Small Coder settings kept on the phone: which first-time tips were already seen, for which Coder
 * projects the person allowed the AI to use the notes of the linked Developer project, and the three editor
 * switches (suggestions, auto-close, mistake underlines; all on by default). Holds no secrets
 * and no file text. A failed read counts as "nothing saved yet"; a failed write is ignored, so the
 * worst case is a tip shown again or a switch that stays off.
 */
class CoderPrefs(context: Context) {
    private val store: DataStore<Preferences> = context.applicationContext.coderPrefsDataStore
    private val hintsKey = stringSetPreferencesKey("hints_seen")
    private val notesKey = stringSetPreferencesKey("notes_opt_in")
    private val suggestionsKey = booleanPreferencesKey("editor_suggestions")
    private val autoCloseKey = booleanPreferencesKey("editor_auto_close")
    private val lintKey = booleanPreferencesKey("editor_lint")

    private val data: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    /** True once the tip [id] was dismissed. */
    fun hintSeen(id: String): Flow<Boolean> =
        data.map { prefs -> id in (prefs[hintsKey] ?: emptySet()) }.distinctUntilChanged()

    /** Remembers that the tip [id] was seen, so it is not shown again. */
    suspend fun markHintSeen(id: String) {
        try {
            store.edit { prefs -> prefs[hintsKey] = (prefs[hintsKey] ?: emptySet()) + id }
        } catch (e: IOException) {
            return
        }
    }

    /** Forgets every seen tip, so all of them are shown again. */
    suspend fun resetHints() {
        try {
            store.edit { prefs -> prefs.remove(hintsKey) }
        } catch (e: IOException) {
            return
        }
    }

    /** True when the AI may use the notes of the Developer project linked to the Coder project [coderProjectId]. Off by default. */
    fun notesOptIn(coderProjectId: String): Flow<Boolean> =
        data.map { prefs -> coderProjectId in (prefs[notesKey] ?: emptySet()) }.distinctUntilChanged()

    /** Turns the notes switch of the Coder project [coderProjectId] on or off. */
    suspend fun setNotesOptIn(coderProjectId: String, enabled: Boolean) {
        try {
            store.edit { prefs ->
                val current = prefs[notesKey] ?: emptySet()
                prefs[notesKey] = if (enabled) current + coderProjectId else current - coderProjectId
            }
        } catch (e: IOException) {
            return
        }
    }

    /** True when suggestions while typing are on. On by default. */
    val suggestionsEnabled: Flow<Boolean> = data.map { prefs -> prefs[suggestionsKey] ?: true }.distinctUntilChanged()

    /** True when brackets, quotes and tags close themselves. On by default. */
    val autoCloseEnabled: Flow<Boolean> = data.map { prefs -> prefs[autoCloseKey] ?: true }.distinctUntilChanged()

    /** True when mistake underlines are on. On by default. */
    val lintEnabled: Flow<Boolean> = data.map { prefs -> prefs[lintKey] ?: true }.distinctUntilChanged()

    /** Turns suggestions while typing on or off. */
    suspend fun setSuggestionsEnabled(enabled: Boolean) {
        setFlag(suggestionsKey, enabled)
    }

    /** Turns auto-close on or off. */
    suspend fun setAutoCloseEnabled(enabled: Boolean) {
        setFlag(autoCloseKey, enabled)
    }

    /** Turns mistake underlines on or off. */
    suspend fun setLintEnabled(enabled: Boolean) {
        setFlag(lintKey, enabled)
    }

    private suspend fun setFlag(key: Preferences.Key<Boolean>, enabled: Boolean) {
        try {
            store.edit { prefs -> prefs[key] = enabled }
        } catch (e: IOException) {
            return
        }
    }
}
