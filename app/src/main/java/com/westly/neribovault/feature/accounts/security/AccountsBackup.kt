package com.westly.neribovault.feature.accounts.security

import android.content.Context
import org.json.JSONException
import org.json.JSONObject

/**
 * Hooks for the encrypted whole-app backup. A backup of the database is only useful on a new
 * phone if the Accounts setup (vault salt and PIN checks) travels with it; the passwords
 * themselves stay encrypted in the database and are never exported in readable form here.
 * Because the key comes only from the PIN, the same Accounts PIN unlocks a restored backup.
 */
object AccountsBackup {
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
     * salts and iteration counts), or null when the Accounts PIN was never set up. Never
     * contains any PIN, key, password or value.
     */
    fun exportState(context: Context): String? {
        val prefs = AccountsPrefs.get(context) ?: return null
        val vaultSalt = prefs.vaultSalt() ?: return null
        val pinA = prefs.pinA() ?: return null
        val root = JSONObject()
        root.put(KEY_VERSION, VERSION)
        root.put(KEY_VAULT_SALT, AccountsPrefs.encode(vaultSalt))
        root.put(KEY_PIN_A, pinToJson(pinA))
        val pinB = prefs.pinB()
        if (pinB != null) root.put(KEY_PIN_B, pinToJson(pinB))
        return root.toString()
    }

    /**
     * Replaces the stored Accounts setup with the JSON produced by [exportState]. Ends any active
     * session. Throws [IllegalArgumentException] if [json] is not a valid export, in which case
     * nothing is changed.
     */
    fun importState(context: Context, json: String) {
        val parsed = parse(json)
        val prefs = AccountsPrefs.get(context)
            ?: throw IllegalStateException("The Accounts store is not available on this phone")
        val written = prefs.writeSetup(parsed.vaultSalt, parsed.pinA, parsed.pinB)
        if (!written) throw IllegalStateException("The Accounts setup could not be stored")
        AccountsVault.onStorageReplaced(context)
    }

    private class Parsed(val vaultSalt: ByteArray, val pinA: PinRecord, val pinB: PinRecord?)

    private fun parse(json: String): Parsed {
        try {
            val root = JSONObject(json)
            require(root.optInt(KEY_VERSION, -1) == VERSION) { "Unsupported Accounts backup version" }
            val vaultSalt = AccountsPrefs.decode(root.getString(KEY_VAULT_SALT))
            require(vaultSalt.isNotEmpty()) { "Missing vault salt" }
            val pinA = pinFromJson(root.getJSONObject(KEY_PIN_A))
            val pinBJson = root.optJSONObject(KEY_PIN_B)
            val pinB = if (pinBJson != null) pinFromJson(pinBJson) else null
            return Parsed(vaultSalt = vaultSalt, pinA = pinA, pinB = pinB)
        } catch (e: JSONException) {
            throw IllegalArgumentException("Not a valid Accounts setup", e)
        }
    }

    private fun pinToJson(record: PinRecord): JSONObject {
        val json = JSONObject()
        json.put(KEY_HASH, AccountsPrefs.encode(record.hash))
        json.put(KEY_SALT, AccountsPrefs.encode(record.salt))
        json.put(KEY_ITERATIONS, record.iterations)
        return json
    }

    private fun pinFromJson(json: JSONObject): PinRecord {
        val hash = AccountsPrefs.decode(json.getString(KEY_HASH))
        val salt = AccountsPrefs.decode(json.getString(KEY_SALT))
        val iterations = json.getInt(KEY_ITERATIONS)
        require(hash.isNotEmpty() && salt.isNotEmpty() && iterations > 0) { "Invalid PIN check" }
        return PinRecord(hash = hash, salt = salt, iterations = iterations)
    }
}
