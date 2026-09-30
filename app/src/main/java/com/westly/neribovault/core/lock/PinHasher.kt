package com.westly.neribovault.core.lock

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * PBKDF2 with HMAC-SHA256 (210,000 iterations, 256-bit output, random 16-byte salt).
 * Uses the platform implementation and falls back to a manual one on old Android versions
 * (API 24 and 25 do not offer PBKDF2WithHmacSHA256).
 */
object PinHasher {
    const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16

    /** A fresh random salt. */
    fun newSalt(): ByteArray {
        val salt = ByteArray(SALT_BYTES)
        SecureRandom().nextBytes(salt)
        return salt
    }

    /** Hashes [pin] with [salt]. This is slow on purpose; call it off the main thread. */
    fun hash(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val chars = pin.toCharArray()
        return try {
            hashWithPlatform(chars, salt, iterations)
        } catch (e: Exception) {
            hashManually(pin, salt, iterations)
        } finally {
            chars.fill('\u0000')
        }
    }

    /** Constant-time check of [pin] against a stored [expected] hash. */
    fun verify(pin: String, salt: ByteArray, iterations: Int, expected: ByteArray): Boolean =
        MessageDigest.isEqual(hash(pin, salt, iterations), expected)

    private fun hashWithPlatform(chars: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(chars, salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** PBKDF2 for a single 32-byte output block, which is all a 256-bit key needs. */
    private fun hashManually(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pin.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        var u = mac.doFinal()
        val result = u.copyOf()
        for (round in 1 until iterations) {
            u = mac.doFinal(u)
            for (i in result.indices) {
                result[i] = (result[i].toInt() xor u[i].toInt()).toByte()
            }
        }
        return result
    }
}
