package com.westly.neribovault.feature.authenticator.backup

import com.westly.neribovault.feature.authenticator.engine.Base32
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

/** One account inside a backup file. [secret] is the plain key; callers zero-fill it after use. */
class BackupAccount(
    val issuer: String,
    val accountName: String,
    val secret: ByteArray,
    val algorithm: String,
    val digits: Int,
    val periodSeconds: Int,
    val notes: String,
    val isPinned: Boolean,
)

/** The outcome of reading a backup file. */
sealed interface BackupReadResult {
    class Success(
        val accounts: List<BackupAccount>,
        val createdAtMillis: Long,
        val skipped: Int,
    ) : BackupReadResult

    /** The password is wrong or the file was changed. The two cases are never told apart. */
    object WrongPasswordOrDamaged : BackupReadResult

    object NotABackup : BackupReadResult
}

/**
 * The Authenticator's own password-protected backup file: the 5 bytes `NVAU1`, a 16-byte salt,
 * a 12-byte IV, then the AES-256-GCM ciphertext of the JSON text. The key comes from the password
 * with PBKDF2-HMAC-SHA256 (210,000 rounds).
 */
object BackupFile {
    const val MAGIC = "NVAU1"

    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = 16
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val MIN_SECRET_BYTES = 10
    private const val VERSION = 1
    private val MAGIC_BYTES = MAGIC.toByteArray(Charsets.US_ASCII)
    private val ALGORITHMS = setOf("SHA1", "SHA256", "SHA512")

    private val random = SecureRandom()

    /** Builds the encrypted file bytes. Temporary key and plain-text arrays are zero-filled. */
    fun write(accounts: List<BackupAccount>, password: String, createdAtMillis: Long): ByteArray {
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val keyBytes = deriveKey(password, salt)
        val plain = buildJson(accounts, createdAtMillis).toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_BITS, iv))
            val encrypted = cipher.doFinal(plain)
            return MAGIC_BYTES + salt + iv + encrypted
        } finally {
            keyBytes.fill(0)
            plain.fill(0)
        }
    }

    /** Opens a backup file. Never throws. */
    fun read(bytes: ByteArray, password: String): BackupReadResult {
        val headerSize = MAGIC_BYTES.size + SALT_BYTES + IV_BYTES
        if (bytes.size < headerSize + TAG_BYTES) return BackupReadResult.NotABackup
        for (i in MAGIC_BYTES.indices) {
            if (bytes[i] != MAGIC_BYTES[i]) return BackupReadResult.NotABackup
        }
        return try {
            val salt = bytes.copyOfRange(MAGIC_BYTES.size, MAGIC_BYTES.size + SALT_BYTES)
            val iv = bytes.copyOfRange(MAGIC_BYTES.size + SALT_BYTES, headerSize)
            val encrypted = bytes.copyOfRange(headerSize, bytes.size)
            val keyBytes = deriveKey(password, salt)
            val plain = try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_BITS, iv))
                cipher.doFinal(encrypted)
            } catch (e: GeneralSecurityException) {
                return BackupReadResult.WrongPasswordOrDamaged
            } finally {
                keyBytes.fill(0)
            }
            try {
                parse(String(plain, Charsets.UTF_8))
            } finally {
                plain.fill(0)
            }
        } catch (e: Exception) {
            BackupReadResult.WrongPasswordOrDamaged
        }
    }

    private fun buildJson(accounts: List<BackupAccount>, createdAtMillis: Long): String {
        val list = JSONArray()
        for (account in accounts) {
            val item = JSONObject()
            item.put("issuer", account.issuer)
            item.put("accountName", account.accountName)
            item.put("secret", Base32.encode(account.secret))
            item.put("algorithm", account.algorithm)
            item.put("digits", account.digits)
            item.put("period", account.periodSeconds)
            item.put("notes", account.notes)
            item.put("pinned", account.isPinned)
            list.put(item)
        }
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("createdAt", createdAtMillis)
        root.put("accounts", list)
        return root.toString()
    }

    private fun parse(text: String): BackupReadResult {
        val root = JSONObject(text)
        if (root.optInt("version", -1) != VERSION) return BackupReadResult.WrongPasswordOrDamaged
        val list = root.optJSONArray("accounts") ?: return BackupReadResult.WrongPasswordOrDamaged
        val accounts = ArrayList<BackupAccount>()
        var skipped = 0
        for (i in 0 until list.length()) {
            val account = parseAccount(list.optJSONObject(i))
            if (account == null) skipped++ else accounts.add(account)
        }
        return BackupReadResult.Success(accounts, root.optLong("createdAt", 0L), skipped)
    }

    /** One validated account, or null when anything about it is invalid. */
    private fun parseAccount(item: JSONObject?): BackupAccount? {
        if (item == null) return null
        val algorithm = item.optString("algorithm", "")
        val digits = item.optInt("digits", -1)
        val period = item.optInt("period", -1)
        if (algorithm !in ALGORITHMS || (digits != 6 && digits != 8) || period !in 15..120) return null
        val secret = Base32.decode(item.optString("secret", "")) ?: return null
        if (secret.size < MIN_SECRET_BYTES) {
            secret.fill(0)
            return null
        }
        return BackupAccount(
            issuer = item.optString("issuer", ""),
            accountName = item.optString("accountName", ""),
            secret = secret,
            algorithm = algorithm,
            digits = digits,
            periodSeconds = period,
            notes = item.optString("notes", ""),
            isPinned = item.optBoolean("pinned", false),
        )
    }

    /** 32 key bytes from [password] and [salt]. The caller zero-fills the result. */
    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
        try {
            return try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } catch (e: java.security.NoSuchAlgorithmException) {
                // Phones older than Android 8 do not offer this name; the result is identical.
                pbkdf2Fallback(password, salt)
            }
        } finally {
            spec.clearPassword()
        }
    }

    /** PBKDF2-HMAC-SHA256 for a single 32-byte block (RFC 8018), for Android 7. */
    private fun pbkdf2Fallback(password: String, salt: ByteArray): ByteArray {
        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(passwordBytes, "HmacSHA256"))
            mac.update(salt)
            mac.update(byteArrayOf(0, 0, 0, 1))
            var u = mac.doFinal()
            val result = u.copyOf()
            for (round in 2..ITERATIONS) {
                u = mac.doFinal(u)
                for (k in result.indices) result[k] = (result[k].toInt() xor u[k].toInt()).toByte()
            }
            return result
        } finally {
            passwordBytes.fill(0)
        }
    }
}
