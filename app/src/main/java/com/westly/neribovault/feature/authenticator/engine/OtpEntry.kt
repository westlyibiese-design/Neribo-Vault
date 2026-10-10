package com.westly.neribovault.feature.authenticator.engine

/** One account read from a link: the service, the login, the secret key bytes and the code settings. */
class OtpEntry(
    val issuer: String,
    val accountName: String,
    val secret: ByteArray,
    val algorithm: OtpAlgorithm,
    val digits: Int,
    val periodSeconds: Int,
)

/** The outcome of reading a link or export code. */
sealed interface OtpParseResult {
    /** [skippedCounterBased] counts counter-based accounts, [skippedUnsupported] anything else that was left out. */
    class Success(
        val entries: List<OtpEntry>,
        val skippedCounterBased: Int,
        val skippedUnsupported: Int,
    ) : OtpParseResult

    /** [reason] is a short plain-English sentence that can be shown to the owner. */
    class Failure(val reason: String) : OtpParseResult
}
