package com.westly.neribovault.feature.developer.secrets

import android.content.Context
import org.json.JSONException
import org.json.JSONObject

/**
 * Hooks for the encrypted whole-app backup (Phase 16). A backup of the database is only useful
 * on a new phone if the secrets setup (vault salt and PIN checks) travels with it; the secrets
 * themselves stay encrypted in the database and are never exported in readable form here.
 */
object SecretsBackup {
    private const val VERSION = 1
    private const val KEY_VERSION = "version"
    private const val KEY_VAULT_SALT = "vaultSalt"
    private const val KEY_PIN_A = "pinA"
    private const val KEY_PIN_B = "pinB"
    private const val KEY_HASH = "hash"
    private const val KEY_SALT = "salt"
    private const val KEY_ITERATIONS = "iterations"

    /**
     * JSON text containing the vault KDF salt, PIN A and PIN B verification hashes (with their
     * salts and iteration counts), or null when the secrets were never set up. Never contains any
     * PIN or secret value.
     */
    fun exportState(context: Context): String? {
        val prefs = SecretsPrefs.get(context) ?: return null
        val vaultSalt = prefs.vaultSalt() ?: return null
        val pinA = prefs.pinA() ?: return null
        val root = JSONObject()
        root.put(KEY_VERSION, VERSION)
        root.put(KEY_VAULT_SALT, SecretsPrefs.encode(vaultSalt))
        root.put(KEY_PIN_A, pinToJson(pinA))
        val pinB = prefs.pinB()
        if (pinB != null) root.put(KEY_PIN_B, pinToJson(pinB))
        return root.toString()
    }

    /**
     * Replaces the stored secrets setup with the JSON produced by [exportState]. Ends any active
     * session. Throws [IllegalArgumentException] if [json] is not a valid export, in which case
     * nothing is changed.
     */
    fun importState(context: Context, json: String) {
        val parsed = parse(json)
        val prefs = SecretsPrefs.get(context)
            ?: throw IllegalStateException("The secrets store is not available on this phone")
        val written = prefs.writeSetup(parsed.vaultSalt, parsed.pinA, parsed.pinB)
        if (!written) throw IllegalStateException("The secrets setup could not be stored")
        SecretsVault.onStorageReplaced(context)
    }

    private class Parsed(val vaultSalt: ByteArray, val pinA: PinRecord, val pinB: PinRecord?)

    private fun parse(json: String): Parsed {
        try {
            val root = JSONObject(json)
            require(root.optInt(KEY_VERSION, -1) == VERSION) { "Unsupported secrets backup version" }
            val vaultSalt = SecretsPrefs.decode(root.getString(KEY_VAULT_SALT))
            require(vaultSalt.isNotEmpty()) { "Missing vault salt" }
            val pinA = pinFromJson(root.getJSONObject(KEY_PIN_A))
            val pinBJson = root.optJSONObject(KEY_PIN_B)
            val pinB = if (pinBJson != null) pinFromJson(pinBJson) else null
            return Parsed(vaultSalt = vaultSalt, pinA = pinA, pinB = pinB)
        } catch (e: JSONException) {
            throw IllegalArgumentException("Not a valid secrets setup", e)
        }
    }

    private fun pinToJson(record: PinRecord): JSONObject {
        val json = JSONObject()
        json.put(KEY_HASH, SecretsPrefs.encode(record.hash))
        json.put(KEY_SALT, SecretsPrefs.encode(record.salt))
        json.put(KEY_ITERATIONS, record.iterations)
        return json
    }

    private fun pinFromJson(json: JSONObject): PinRecord {
        val hash = SecretsPrefs.decode(json.getString(KEY_HASH))
        val salt = SecretsPrefs.decode(json.getString(KEY_SALT))
        val iterations = json.getInt(KEY_ITERATIONS)
        require(hash.isNotEmpty() && salt.isNotEmpty() && iterations > 0) { "Invalid PIN check" }
        return PinRecord(hash = hash, salt = salt, iterations = iterations)
    }
}
