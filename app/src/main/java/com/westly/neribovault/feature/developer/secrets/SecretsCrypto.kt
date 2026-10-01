package com.westly.neribovault.feature.developer.secrets

import android.util.Base64
import com.westly.neribovault.core.lock.PinHasher
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption for secret values, using only `javax.crypto`.
 * The key is derived from PIN A with PBKDF2 through [PinHasher]; every value gets a fresh
 * random 12-byte IV, so the same value never produces the same ciphertext twice.
 */
object SecretsCrypto {
    /** PBKDF2 iterations for both the key derivation and the PIN verification hashes. */
    const val KDF_ITERATIONS = 210_000

    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

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
}

/**
 * Believable fake values for decoy mode. The same secret always gets the same fake, so the
 * decoy view stays consistent from one visit to the next. These are not security values.
 */
object SecretsDecoys {
    private const val ALPHANUMERIC = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private const val PASSWORD_CHARS = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789!#$%*"
    private const val HEX = "0123456789abcdef"
    private const val BASE64_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** A fake value that looks right for [category], derived from [seed] (the secret's id). */
    fun fake(category: String, seed: String): String {
        val rng = Random(seed.hashCode().toLong() * 31L + category.hashCode().toLong())
        return when (category) {
            "api_key" -> "sk_live_" + pick(rng, ALPHANUMERIC, 24)
            "token" -> "ghp_" + pick(rng, ALPHANUMERIC, 36)
            "password" -> pick(rng, PASSWORD_CHARS, 14)
            "database" -> "postgresql://app_user:" + pick(rng, ALPHANUMERIC, 16) +
                "@db.internal.example.com:5432/app"
            "ssh_key" -> "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI" + pick(rng, BASE64_CHARS, 43)
            "env" -> pick(rng, HEX, 32)
            else -> pick(rng, ALPHANUMERIC, 24)
        }
    }

    private fun pick(rng: Random, chars: String, length: Int): String {
        val out = StringBuilder(length)
        repeat(length) { out.append(chars[rng.nextInt(chars.length)]) }
        return out.toString()
    }
}
