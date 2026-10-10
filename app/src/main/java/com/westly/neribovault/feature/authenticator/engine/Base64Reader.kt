package com.westly.neribovault.feature.authenticator.engine

import java.io.ByteArrayOutputStream

/**
 * A tiny Base64 reader for the export links of other authenticator apps. It accepts both the
 * standard and the URL-safe alphabet, and the padding is optional. White space is ignored.
 */
internal object Base64Reader {
    /** The decoded bytes, or null when [text] is not valid Base64. */
    fun decode(text: String): ByteArray? {
        val body = text.trimEnd('=')
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bitsLeft = 0
        var count = 0
        for (ch in body) {
            if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t') continue
            val value = valueOf(ch)
            if (value < 0) return null
            count++
            buffer = (buffer shl 6) or value
            bitsLeft += 6
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.write((buffer shr bitsLeft) and 0xFF)
                buffer = buffer and ((1 shl bitsLeft) - 1)
            }
        }
        // A single leftover character can never be part of valid Base64.
        if (count % 4 == 1) return null
        return out.toByteArray()
    }

    private fun valueOf(ch: Char): Int = when (ch) {
        in 'A'..'Z' -> ch - 'A'
        in 'a'..'z' -> ch - 'a' + 26
        in '0'..'9' -> ch - '0' + 52
        '+', '-' -> 62
        '/', '_' -> 63
        else -> -1
    }
}
