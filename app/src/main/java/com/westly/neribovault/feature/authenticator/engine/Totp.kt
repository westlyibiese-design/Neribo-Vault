package com.westly.neribovault.feature.authenticator.engine

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Time-based one-time codes (RFC 6238, using the RFC 4226 truncation). */
object Totp {
    /** The time-step number for [timeMillis]. */
    fun counter(timeMillis: Long, periodSeconds: Int): Long =
        timeMillis / 1000 / periodSeconds

    /** Seconds until the next code, from 1 up to [periodSeconds]. */
    fun secondsRemaining(timeMillis: Long, periodSeconds: Int): Int =
        (periodSeconds - ((timeMillis / 1000) % periodSeconds)).toInt()

    /**
     * HMAC of the 8-byte big-endian counter, dynamic truncation, modulo 10^digits, zero-padded
     * on the left. [secret] must not be empty.
     */
    fun code(
        secret: ByteArray,
        timeMillis: Long,
        digits: Int,
        periodSeconds: Int,
        algorithm: OtpAlgorithm,
    ): String {
        val counter = counter(timeMillis, periodSeconds)
        val message = ByteArray(8)
        for (i in 7 downTo 0) {
            message[i] = ((counter shr ((7 - i) * 8)) and 0xFF).toByte()
        }
        val mac = Mac.getInstance(algorithm.macName)
        mac.init(SecretKeySpec(secret, algorithm.macName))
        val hash = mac.doFinal(message)
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        var modulus = 1L
        repeat(digits) { modulus *= 10L }
        return (binary.toLong() % modulus).toString().padStart(digits, '0')
    }
}

/** How a code is shown on screen. */
object CodeFormat {
    /** "287082" becomes "287 082" and "94287082" becomes "9428 7082" (split in half). */
    fun group(code: String): String {
        if (code.length < 2) return code
        val half = code.length / 2
        return code.substring(0, half) + " " + code.substring(half)
    }
}
