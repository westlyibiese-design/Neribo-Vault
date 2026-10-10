package com.westly.neribovault.feature.authenticator

import android.util.Base64
import com.westly.neribovault.feature.authenticator.engine.Base32
import com.westly.neribovault.feature.authenticator.engine.CodeFormat
import com.westly.neribovault.feature.authenticator.engine.OtpAlgorithm
import com.westly.neribovault.feature.authenticator.engine.OtpEntry
import com.westly.neribovault.feature.authenticator.engine.OtpParseResult
import com.westly.neribovault.feature.authenticator.engine.OtpUri
import com.westly.neribovault.feature.authenticator.engine.Totp
import com.westly.neribovault.feature.authenticator.security.AuthenticatorCrypto

/** The outcome of one self-test check. [detail] says what went wrong when [passed] is false. */
data class CheckResult(val name: String, val passed: Boolean, val detail: String)

private val RFC_TIMES = longArrayOf(59L, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L)

private val RFC_SECRETS = mapOf(
    OtpAlgorithm.SHA1 to "12345678901234567890",
    OtpAlgorithm.SHA256 to "12345678901234567890123456789012",
    OtpAlgorithm.SHA512 to "1234567890123456789012345678901234567890123456789012345678901234",
)

private val RFC_EIGHT_DIGIT = mapOf(
    OtpAlgorithm.SHA1 to listOf("94287082", "07081804", "14050471", "89005924", "69279037", "65353130"),
    OtpAlgorithm.SHA256 to listOf("46119246", "68084774", "67062674", "91819424", "90698825", "77737706"),
    OtpAlgorithm.SHA512 to listOf("90693936", "25091201", "99943326", "93441116", "38618901", "47863826"),
)

private const val LINK_A =
    "otpauth://totp/ACME%20Co:john.doe@email.com?secret=HXDMVJECJJWSRB3HWIZR4IFUGFTMXBOZ" +
        "&issuer=ACME%20Co&algorithm=SHA1&digits=6&period=30"
private const val LINK_B =
    "otpauth://totp/Old%20Label:westly?secret=JBSWY3DPEHPK3PXP&issuer=GitHub&algorithm=sha256&digits=8&period=60"
private const val LINK_NO_COLON = "otpauth://totp/westly?secret=JBSWY3DPEHPK3PXP"
private const val LINK_MIGRATION =
    "otpauth-migration://offline?data=CjgKFDEyMzQ1Njc4OTAxMjM0NTY3ODkwEhFhbGljZUBleGFtcGxlLmNvbRoHQUNNRSBDbyABKAEwAgo6CiAxMjM0NTY3" +
        "ODkwMTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMhIGV2VzdGx5GghTdXBhYmFzZSACKAIwAgowChFob3RwLXNlY3JldC1ieXRlcxIMY291bnRlci11c2VyGgdPbGRTaXRlIAEo" +
        "ATABEAEYASAAKMDEBw%3D%3D"

private val FAILURE_LINKS = listOf(
    "otpauth://hotp/x?secret=JBSWY3DPEHPK3PXP&counter=1",
    "otpauth://totp/x",
    "otpauth://totp/x?secret=1111",
    "otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&digits=7",
    "otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&period=5",
    "otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&algorithm=MD5",
    "https://example.com",
    "",
)

/**
 * Proves the one-time-code maths against the official RFC 6238 test vectors and the link parser
 * against known links. Needs no PIN and no database. Exactly 32 checks.
 */
fun runAuthenticatorSelfTest(): List<CheckResult> {
    val results = ArrayList<CheckResult>()

    // 1-18: every algorithm, every time, 8 digits.
    for (algorithm in OtpAlgorithm.values()) {
        val secret = RFC_SECRETS.getValue(algorithm).toByteArray(Charsets.US_ASCII)
        val expected = RFC_EIGHT_DIGIT.getValue(algorithm)
        for (i in RFC_TIMES.indices) {
            val time = RFC_TIMES[i]
            results.add(
                check("${algorithm.name} 8-digit at $time") {
                    val got = Totp.code(secret, time * 1000, 8, 30, algorithm)
                    if (got == expected[i]) null else "expected ${expected[i]}, got $got"
                },
            )
        }
    }

    // 19-21: the six-digit codes are the last six digits.
    for (algorithm in OtpAlgorithm.values()) {
        val secret = RFC_SECRETS.getValue(algorithm).toByteArray(Charsets.US_ASCII)
        val expected = RFC_EIGHT_DIGIT.getValue(algorithm)
        results.add(
            check("${algorithm.name} 6-digit codes") {
                val problems = ArrayList<String>()
                for (i in RFC_TIMES.indices) {
                    val got = Totp.code(secret, RFC_TIMES[i] * 1000, 6, 30, algorithm)
                    val want = expected[i].takeLast(6)
                    if (got != want) problems.add("at ${RFC_TIMES[i]} expected $want, got $got")
                }
                if (problems.isEmpty()) null else problems.joinToString("; ")
            },
        )
    }

    // 22: counters and seconds remaining.
    results.add(
        check("Counter and seconds remaining") {
            val cases = listOf(
                Triple(59_000L, 1L, 1),
                Triple(60_000L, 2L, 30),
                Triple(1_700_000_009_000L, 56666666L, 1),
                Triple(1_700_000_010_000L, 56666667L, 30),
            )
            val problems = ArrayList<String>()
            for ((time, counter, remaining) in cases) {
                val gotCounter = Totp.counter(time, 30)
                val gotRemaining = Totp.secondsRemaining(time, 30)
                if (gotCounter != counter || gotRemaining != remaining) {
                    problems.add("$time: expected $counter/$remaining, got $gotCounter/$gotRemaining")
                }
            }
            if (problems.isEmpty()) null else problems.joinToString("; ")
        },
    )

    // 23: grouping.
    results.add(
        check("Code grouping") {
            val a = CodeFormat.group("287082")
            val b = CodeFormat.group("94287082")
            if (a == "287 082" && b == "9428 7082") null else "got '$a' and '$b'"
        },
    )

    // 24: Base32 encode.
    results.add(
        check("Base32 encode") {
            val cases = listOf(
                "" to "", "f" to "MY======", "fo" to "MZXQ====", "foo" to "MZXW6===",
                "foob" to "MZXW6YQ=", "fooba" to "MZXW6YTB", "foobar" to "MZXW6YTBOI======",
            )
            val problems = cases.mapNotNull { (plain, encoded) ->
                val got = Base32.encode(plain.toByteArray(Charsets.US_ASCII))
                if (got == encoded) null else "'$plain' gave '$got'"
            }
            if (problems.isEmpty()) null else problems.joinToString("; ")
        },
    )

    // 25: Base32 decode.
    results.add(
        check("Base32 decode") {
            val problems = ArrayList<String>()
            for (text in listOf("mzxw 6ytb-oi", "MZXW6YTBOI======", "MZXW6YTBOI")) {
                val got = Base32.decode(text)
                if (got == null || String(got, Charsets.US_ASCII) != "foobar") problems.add("'$text' did not give foobar")
            }
            if (Base32.decode("1") != null) problems.add("'1' should be null")
            if (Base32.decode("") != null) problems.add("empty should be null")
            if (problems.isEmpty()) null else problems.joinToString("; ")
        },
    )

    // 26: example link A.
    results.add(
        check("Link A") {
            val entry = singleEntry(OtpUri.parse(LINK_A)) ?: return@check "did not parse to one entry"
            val times = longArrayOf(59L, 1700000009L, 1700000010L, 1700000039L, 1700000040L)
            val want = listOf("320382", "825131", "990572", "990572", "969495")
            entryProblems(entry, "ACME Co", "john.doe@email.com", OtpAlgorithm.SHA1, 6, 30, times, want)
        },
    )

    // 27: example link B and the label without a colon.
    results.add(
        check("Link B and label without colon") {
            val b = singleEntry(OtpUri.parse(LINK_B)) ?: return@check "link B did not parse to one entry"
            val problemB = entryProblems(
                b, "GitHub", "westly", OtpAlgorithm.SHA256, 8, 60,
                longArrayOf(0L, 59L, 60L, 1700000000L), listOf("96023015", "96023015", "36344551", "71205722"),
            )
            val c = singleEntry(OtpUri.parse(LINK_NO_COLON)) ?: return@check "no-colon link did not parse"
            val problemC = entryProblems(c, "", "westly", OtpAlgorithm.SHA1, 6, 30, longArrayOf(0L), listOf("282760"))
            listOfNotNull(problemB, problemC).joinToString("; ").ifEmpty { null }
        },
    )

    // 28: failures.
    results.add(
        check("Eight failure links") {
            val bad = FAILURE_LINKS.filter { OtpUri.parse(it) !is OtpParseResult.Failure }
            if (bad.isEmpty()) null else "not rejected: " + bad.joinToString(", ") { "'$it'" }
        },
    )

    // 29: migration link.
    results.add(
        check("Migration link") {
            val result = OtpUri.parse(LINK_MIGRATION)
            if (result !is OtpParseResult.Success) return@check "not a success"
            if (result.entries.size != 2) return@check "expected 2 entries, got ${result.entries.size}"
            if (result.skippedCounterBased != 1 || result.skippedUnsupported != 0) {
                return@check "skipped ${result.skippedCounterBased}/${result.skippedUnsupported}"
            }
            val first = entryProblems(
                result.entries[0], "ACME Co", "alice@example.com", OtpAlgorithm.SHA1, 6, 30,
                longArrayOf(59L), listOf("287082"),
            )
            val second = entryProblems(
                result.entries[1], "Supabase", "Westly", OtpAlgorithm.SHA256, 8, 30,
                longArrayOf(59L), listOf("46119246"),
            )
            listOfNotNull(first, second).joinToString("; ").ifEmpty { null }
        },
    )

    // 30: truncated migration link.
    results.add(
        check("Truncated migration link") {
            val cut = LINK_MIGRATION.dropLast(10)
            if (OtpUri.parse(cut) is OtpParseResult.Failure) null else "a cut link was accepted"
        },
    )

    // 31: Keystore round trip.
    results.add(
        check("Keystore round trip") {
            val plain = "12345678901234567890".toByteArray(Charsets.US_ASCII)
            val sealed = AuthenticatorCrypto.encrypt(plain) ?: return@check "encrypt returned null"
            val back = AuthenticatorCrypto.decrypt(sealed.ciphertext, sealed.iv) ?: return@check "decrypt returned null"
            val ivLength = Base64.decode(sealed.iv, Base64.NO_WRAP).size
            when {
                !back.contentEquals(plain) -> "decrypted bytes differ"
                ivLength != 12 -> "IV is $ivLength bytes"
                else -> null
            }
        },
    )

    // 32: Keystore safety.
    results.add(
        check("Keystore safety") {
            val plain = "12345678901234567890".toByteArray(Charsets.US_ASCII)
            val one = AuthenticatorCrypto.encrypt(plain) ?: return@check "encrypt returned null"
            val two = AuthenticatorCrypto.encrypt(plain) ?: return@check "second encrypt returned null"
            val raw = Base64.decode(one.ciphertext, Base64.NO_WRAP)
            raw[0] = (raw[0].toInt() xor 0x01).toByte()
            val tampered = Base64.encodeToString(raw, Base64.NO_WRAP)
            when {
                AuthenticatorCrypto.decrypt(tampered, one.iv) != null -> "changed data was accepted"
                one.ciphertext == two.ciphertext -> "ciphertexts are equal"
                one.iv == two.iv -> "IVs are equal"
                else -> null
            }
        },
    )

    return results
}

/** Runs [block]; null means passed, text means failed. Any exception becomes a failed check. */
private inline fun check(name: String, block: () -> String?): CheckResult = try {
    val problem = block()
    CheckResult(name, problem == null, problem ?: "ok")
} catch (e: Exception) {
    CheckResult(name, false, "exception: ${e.javaClass.simpleName}")
}

private fun singleEntry(result: OtpParseResult): OtpEntry? =
    if (result is OtpParseResult.Success && result.entries.size == 1) result.entries[0] else null

/** Null when [entry] has the expected fields and codes, otherwise a description of what differs. */
private fun entryProblems(
    entry: OtpEntry,
    issuer: String,
    account: String,
    algorithm: OtpAlgorithm,
    digits: Int,
    period: Int,
    seconds: LongArray,
    codes: List<String>,
): String? {
    val problems = ArrayList<String>()
    if (entry.issuer != issuer) problems.add("issuer '${entry.issuer}'")
    if (entry.accountName != account) problems.add("account '${entry.accountName}'")
    if (entry.algorithm != algorithm) problems.add("algorithm ${entry.algorithm}")
    if (entry.digits != digits) problems.add("digits ${entry.digits}")
    if (entry.periodSeconds != period) problems.add("period ${entry.periodSeconds}")
    for (i in seconds.indices) {
        val got = Totp.code(entry.secret, seconds[i] * 1000, entry.digits, entry.periodSeconds, entry.algorithm)
        if (got != codes[i]) problems.add("code at ${seconds[i]} was $got, expected ${codes[i]}")
    }
    return if (problems.isEmpty()) null else problems.joinToString(", ")
}
