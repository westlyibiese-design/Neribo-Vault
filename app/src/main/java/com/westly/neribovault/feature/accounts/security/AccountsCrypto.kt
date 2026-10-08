package com.westly.neribovault.feature.accounts.security

import android.util.Base64
import com.westly.neribovault.core.lock.PinHasher
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption for passwords and secret fields, using only `javax.crypto`.
 * The key is derived from the main PIN with PBKDF2 through [PinHasher]; every value gets a fresh
 * random 12-byte IV, so the same value never produces the same ciphertext twice.
 */
object AccountsCrypto {
    /** PBKDF2 iterations for both the key derivation and the PIN verification hashes. */
    const val KDF_ITERATIONS = 210_000

    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private const val ALPHANUMERIC = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private const val PASSWORD_CHARS = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789!#$%*"
    private const val LOWER_DIGITS = "abcdefghijklmnopqrstuvwxyz0123456789"

    private val random = SecureRandom()

    /** Base64 (no wrap) ciphertext and IV, exactly as they are stored in the database. */
    class Sealed(val ciphertext: String, val iv: String)

    /** The 256-bit key for [pin] and [vaultSalt]. Slow on purpose; call it off the main thread. */
    fun deriveKey(pin: String, vaultSalt: ByteArray): ByteArray =
        PinHasher.hash(pin, vaultSalt, KDF_ITERATIONS)

    /** A new PIN verification record with its own random salt. */
    fun newPinRecord(pin: String): PinRecord {
        val salt = PinHasher.newSalt()
        return PinRecord(
            hash = PinHasher.hash(pin, salt, KDF_ITERATIONS),
            salt = salt,
            iterations = KDF_ITERATIONS,
        )
    }

    /** Whether [pin] matches [record]. Constant time. */
    fun matches(pin: String, record: PinRecord): Boolean =
        PinHasher.verify(pin, record.salt, record.iterations, record.hash)

    /** Encrypts [plaintext] with [key] and a fresh IV. */
    fun encrypt(key: ByteArray, plaintext: String): Sealed {
        val iv = ByteArray(IV_BYTES)
        random.nextBytes(iv)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val plainBytes = plaintext.toByteArray(Charsets.UTF_8)
        try {
            val encrypted = cipher.doFinal(plainBytes)
            return Sealed(
                ciphertext = Base64.encodeToString(encrypted, Base64.NO_WRAP),
                iv = Base64.encodeToString(iv, Base64.NO_WRAP),
            )
        } finally {
            plainBytes.fill(0)
        }
    }

    /** Decrypts a stored value, or returns null if the key is wrong or the data was altered. */
    fun decrypt(key: ByteArray, ciphertext: String, iv: String): String? {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val ivBytes = Base64.decode(iv, Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, ivBytes))
            val plainBytes = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
            try {
                String(plainBytes, Charsets.UTF_8)
            } finally {
                plainBytes.fill(0)
            }
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * A believable fake value for the second-PIN session. The output depends only on [category]
     * and [seed] (the row's id), so the same password always shows the same fake. These are not
     * security values. Categories: "password" (12 to 16 characters), "api_key" (`sk_live_` plus
     * 24 characters) and "text" (a short code such as `k4m2-9xq7`); anything else is treated as "text".
     */
    fun fake(category: String, seed: String): String {
        val rng = Random(mix(category, seed))
        return when (category) {
            "password" -> pick(rng, PASSWORD_CHARS, 12 + rng.nextInt(5))
            "api_key" -> "sk_live_" + pick(rng, ALPHANUMERIC, 24)
            else -> pick(rng, LOWER_DIGITS, 4) + "-" + pick(rng, LOWER_DIGITS, 4)
        }
    }

    /** FNV-1a over the category and seed, so nearby seeds give very different random streams. */
    private fun mix(category: String, seed: String): Long {
        var hash = -3750763034362895579L
        for (ch in "$category|$seed") {
            hash = hash xor ch.code.toLong()
            hash *= 1099511628211L
        }
        return hash
    }

    private fun pick(rng: Random, chars: String, length: Int): String {
        val out = StringBuilder(length)
        repeat(length) { out.append(chars[rng.nextInt(chars.length)]) }
        return out.toString()
    }
}
