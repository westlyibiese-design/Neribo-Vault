package com.westly.neribovault.feature.authenticator.engine

import java.io.ByteArrayOutputStream

/** Base32 as written in RFC 4648, with a tolerant decoder for keys that people type or copy. */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /**
     * RFC 4648, case-insensitive; spaces, hyphens and "=" padding are ignored. Returns null for
     * any other character or when nothing is left.
     */
    fun decode(text: String): ByteArray? {
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bitsLeft = 0
        for (ch in text) {
            if (ch == ' ' || ch == '-' || ch == '=') continue
            val upper = if (ch in 'a'..'z') ch - 32 else ch
            val index = ALPHABET.indexOf(upper)
            if (index < 0) return null
            buffer = (buffer shl 5) or index
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.write((buffer shr bitsLeft) and 0xFF)
                buffer = buffer and ((1 shl bitsLeft) - 1)
            }
        }
        val bytes = out.toByteArray()
        return if (bytes.isEmpty()) null else bytes
    }

    /** RFC 4648 upper-case with "=" padding. */
    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(ALPHABET[(buffer shr bits) and 0x1F])
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) {
            sb.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        }
        while (sb.length % 8 != 0) sb.append('=')
        return sb.toString()
    }
}
