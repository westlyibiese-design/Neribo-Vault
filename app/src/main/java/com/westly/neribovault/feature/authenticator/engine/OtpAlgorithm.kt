package com.westly.neribovault.feature.authenticator.engine

/** The hash algorithms a one-time code can use. [macName] is the matching `javax.crypto.Mac` name. */
enum class OtpAlgorithm(val macName: String) {
    SHA1("HmacSHA1"),
    SHA256("HmacSHA256"),
    SHA512("HmacSHA512"),
}
