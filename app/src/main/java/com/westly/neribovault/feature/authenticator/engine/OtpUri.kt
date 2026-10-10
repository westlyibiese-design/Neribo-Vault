package com.westly.neribovault.feature.authenticator.engine

import java.io.ByteArrayOutputStream
import java.net.URLDecoder

/** Reads `otpauth://` links and the `otpauth-migration://` export links of other authenticator apps. */
object OtpUri {
    private const val TOTP_PREFIX = "otpauth://totp/"
    private const val HOTP_PREFIX = "otpauth://hotp/"
    private const val MIGRATION_PREFIX = "otpauth-migration://offline?"

    private const val MIN_SECRET_BYTES = 10
    private const val DEFAULT_PERIOD = 30

    private const val MSG_NOTHING = "There is nothing to read"
    private const val MSG_SECRET = "The secret key is not valid"
    private const val MSG_ALGORITHM = "This code uses an algorithm that is not supported"
    private const val MSG_DIGITS = "This code uses a number of digits that is not supported"
    private const val MSG_PERIOD = "This code uses a time step that is not supported"
    private const val MSG_HOTP = "Counter-based (HOTP) codes are not supported"
    private const val MSG_UNREADABLE = "That code could not be read"
    private const val MSG_NO_ACCOUNTS = "No supported accounts were found in that code"
    private const val MSG_NOT_CODE = "That is not an authenticator code"

    /** Reads [text]. Never throws. */
    fun parse(text: String): OtpParseResult = try {
        parseTrimmed(text.trim())
    } catch (e: Exception) {
        OtpParseResult.Failure(MSG_UNREADABLE)
    }

    private fun parseTrimmed(text: String): OtpParseResult = when {
        text.isEmpty() -> OtpParseResult.Failure(MSG_NOTHING)
        text.startsWith(TOTP_PREFIX, ignoreCase = true) ->
            parseTotp(text.substring(TOTP_PREFIX.length))
        text.startsWith(HOTP_PREFIX, ignoreCase = true) -> OtpParseResult.Failure(MSG_HOTP)
        text.startsWith(MIGRATION_PREFIX, ignoreCase = true) ->
            parseMigration(text.substring(MIGRATION_PREFIX.length))
        else -> OtpParseResult.Failure(MSG_NOT_CODE)
    }

    // ---- otpauth://totp/<label>?<query> ----------------------------------------------------

    private fun parseTotp(rest: String): OtpParseResult {
        val questionMark = rest.indexOf('?')
        val rawLabel = if (questionMark >= 0) rest.substring(0, questionMark) else rest
        val query = if (questionMark >= 0) rest.substring(questionMark + 1) else ""

        val label = percentDecode(rawLabel)
        val colon = label.indexOf(':')
        var issuer = if (colon >= 0) label.substring(0, colon).trim() else ""
        val accountName = if (colon >= 0) label.substring(colon + 1).trim() else label.trim()

        val params = parseQuery(query)

        val secret = params["secret"]?.let { Base32.decode(it) }
        if (secret == null || secret.size < MIN_SECRET_BYTES) {
            secret?.fill(0)
            return OtpParseResult.Failure(MSG_SECRET)
        }

        val issuerParam = params["issuer"]
        if (issuerParam != null && issuerParam.isNotBlank()) issuer = issuerParam.trim()

        val algorithm = parseAlgorithm(params["algorithm"])
        if (algorithm == null) {
            secret.fill(0)
            return OtpParseResult.Failure(MSG_ALGORITHM)
        }
        val digits = parseDigits(params["digits"])
        if (digits == null) {
            secret.fill(0)
            return OtpParseResult.Failure(MSG_DIGITS)
        }
        val period = parsePeriod(params["period"])
        if (period == null) {
            secret.fill(0)
            return OtpParseResult.Failure(MSG_PERIOD)
        }

        val entry = OtpEntry(issuer, accountName, secret, algorithm, digits, period)
        return OtpParseResult.Success(listOf(entry), skippedCounterBased = 0, skippedUnsupported = 0)
    }

    /** A missing or blank value means the default; any other unknown value is not supported (null). */
    private fun parseAlgorithm(value: String?): OtpAlgorithm? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return OtpAlgorithm.SHA1
        return when (text.uppercase()) {
            "SHA1" -> OtpAlgorithm.SHA1
            "SHA256" -> OtpAlgorithm.SHA256
            "SHA512" -> OtpAlgorithm.SHA512
            else -> null
        }
    }

    private fun parseDigits(value: String?): Int? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return 6
        return when (text) {
            "6" -> 6
            "8" -> 8
            else -> null
        }
    }

    private fun parsePeriod(value: String?): Int? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return DEFAULT_PERIOD
        val number = text.toIntOrNull() ?: return null
        return if (number in 15..120) number else null
    }

    /** Splits a query string into decoded name/value pairs. The first value of a repeated name wins. */
    private fun parseQuery(query: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        if (query.isEmpty()) return result
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val equals = pair.indexOf('=')
            val name = (if (equals >= 0) pair.substring(0, equals) else pair).lowercase()
            val raw = if (equals >= 0) pair.substring(equals + 1) else ""
            val value = try {
                URLDecoder.decode(raw, "UTF-8")
            } catch (e: IllegalArgumentException) {
                raw
            }
            if (!result.containsKey(name)) result[name] = value
        }
        return result
    }

    /** Percent-decodes by hand so that a "+" stays a "+". A "%" not followed by two hex digits is kept. */
    private fun percentDecode(text: String): String {
        if (text.indexOf('%') < 0) return text
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '%' && i + 2 < text.length && hexValue(text[i + 1]) >= 0 && hexValue(text[i + 2]) >= 0) {
                out.write(hexValue(text[i + 1]) * 16 + hexValue(text[i + 2]))
                i += 3
            } else {
                val width = if (Character.isHighSurrogate(ch) && i + 1 < text.length &&
                    Character.isLowSurrogate(text[i + 1])
                ) 2 else 1
                val bytes = text.substring(i, i + width).toByteArray(Charsets.UTF_8)
                out.write(bytes, 0, bytes.size)
                i += width
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun hexValue(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'a'..'f' -> ch - 'a' + 10
        in 'A'..'F' -> ch - 'A' + 10
        else -> -1
    }

    // ---- otpauth-migration://offline?data=<Base64 of a protobuf message> --------------------

    private fun parseMigration(query: String): OtpParseResult {
        var data: String? = null
        for (pair in query.split('&')) {
            val equals = pair.indexOf('=')
            if (equals < 0) continue
            if (pair.substring(0, equals).equals("data", ignoreCase = true)) {
                data = percentDecode(pair.substring(equals + 1))
                break
            }
        }
        if (data == null || data.isBlank()) return OtpParseResult.Failure(MSG_UNREADABLE)
        val bytes = Base64Reader.decode(data) ?: return OtpParseResult.Failure(MSG_UNREADABLE)

        val entries = ArrayList<OtpEntry>()
        var skippedCounterBased = 0
        var skippedUnsupported = 0
        try {
            ProtoReader.readFields(bytes) { field, _, payload ->
                if (field == 1 && payload != null) {
                    val account = readAccount(payload)
                    when {
                        account.type == TYPE_HOTP -> skippedCounterBased++
                        account.isUnsupported() -> skippedUnsupported++
                        else -> entries.add(account.toEntry())
                    }
                }
            }
        } catch (e: MalformedProtoException) {
            entries.forEach { it.secret.fill(0) }
            return OtpParseResult.Failure(MSG_UNREADABLE)
        }
        if (entries.isEmpty()) return OtpParseResult.Failure(MSG_NO_ACCOUNTS)
        return OtpParseResult.Success(entries, skippedCounterBased, skippedUnsupported)
    }

    private const val TYPE_HOTP = 1L

    /** The fields of one `OtpParameters` message. Missing numbers keep the protobuf default of 0. */
    private class ParsedAccount {
        var secret: ByteArray = ByteArray(0)
        var name: String = ""
        var issuer: String = ""
        var algorithm: Long = 0
        var digits: Long = 0
        var type: Long = 0

        fun isUnsupported(): Boolean {
            val algorithmOk = algorithm == 0L || algorithm == 1L || algorithm == 2L || algorithm == 3L
            val digitsOk = digits == 0L || digits == 1L || digits == 2L
            val typeOk = type == 0L || type == 2L
            return !algorithmOk || !digitsOk || !typeOk || secret.size < MIN_SECRET_BYTES
        }

        fun toEntry(): OtpEntry {
            var shownIssuer = issuer.trim()
            var shownName = name.trim()
            if (shownIssuer.isEmpty()) {
                val colon = shownName.indexOf(':')
                if (colon >= 0) {
                    shownIssuer = shownName.substring(0, colon).trim()
                    shownName = shownName.substring(colon + 1).trim()
                }
            }
            val algorithmValue = when (algorithm) {
                2L -> OtpAlgorithm.SHA256
                3L -> OtpAlgorithm.SHA512
                else -> OtpAlgorithm.SHA1
            }
            val digitCount = if (digits == 2L) 8 else 6
            return OtpEntry(shownIssuer, shownName, secret, algorithmValue, digitCount, DEFAULT_PERIOD)
        }
    }

    private fun readAccount(bytes: ByteArray): ParsedAccount {
        val account = ParsedAccount()
        ProtoReader.readFields(bytes) { field, number, payload ->
            when (field) {
                1 -> { if (payload != null) account.secret = payload }
                2 -> { if (payload != null) account.name = String(payload, Charsets.UTF_8) }
                3 -> { if (payload != null) account.issuer = String(payload, Charsets.UTF_8) }
                4 -> { if (payload == null) account.algorithm = number }
                5 -> { if (payload == null) account.digits = number }
                6 -> { if (payload == null) account.type = number }
                else -> Unit // field 7 (counter) and anything unknown is skipped
            }
        }
        return account
    }

    /** Thrown inside [OtpUri] when the protobuf bytes are cut short or damaged. */
    private class MalformedProtoException : Exception()

    /** A minimal reader for the Protocol Buffers wire format. */
    private object ProtoReader {
        /**
         * Walks every field of the message in [data]. [visit] gets the field number, the value of a
         * varint field (0 for other kinds) and the bytes of a length-delimited field (null for other kinds).
         * Unknown wire types and truncated data throw [MalformedProtoException].
         */
        fun readFields(data: ByteArray, visit: (field: Int, number: Long, payload: ByteArray?) -> Unit) {
            var pos = 0
            while (pos < data.size) {
                val keyRead = readVarint(data, pos)
                pos = keyRead.next
                val field = (keyRead.value shr 3).toInt()
                val wireType = (keyRead.value and 7L).toInt()
                if (field <= 0) throw MalformedProtoException()
                when (wireType) {
                    0 -> {
                        val valueRead = readVarint(data, pos)
                        pos = valueRead.next
                        visit(field, valueRead.value, null)
                    }
                    1 -> {
                        if (data.size - pos < 8) throw MalformedProtoException()
                        pos += 8
                    }
                    2 -> {
                        val lengthRead = readVarint(data, pos)
                        pos = lengthRead.next
                        val length = lengthRead.value
                        if (length < 0 || length > (data.size - pos).toLong()) throw MalformedProtoException()
                        val end = pos + length.toInt()
                        visit(field, 0L, data.copyOfRange(pos, end))
                        pos = end
                    }
                    5 -> {
                        if (data.size - pos < 4) throw MalformedProtoException()
                        pos += 4
                    }
                    else -> throw MalformedProtoException()
                }
            }
        }

        private class VarintRead(val value: Long, val next: Int)

        private fun readVarint(data: ByteArray, start: Int): VarintRead {
            var result = 0L
            var shift = 0
            var pos = start
            while (true) {
                if (pos >= data.size || shift > 63) throw MalformedProtoException()
                val b = data[pos].toInt() and 0xFF
                pos++
                result = result or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) return VarintRead(result, pos)
                shift += 7
            }
        }
    }
}
