package com.westly.neribovault.feature.accounts.security

import android.util.Base64
import com.westly.neribovault.core.lock.PinHasher
import java.security.SecureRandom

/** One line of the self-test report. [detail] explains a failure. */
data class CheckResult(val name: String, val passed: Boolean, val detail: String)

private const val EXPECTED_1 = "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b"
private const val EXPECTED_2 = "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43"
private const val EXPECTED_4096 = "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a"

/**
 * Proves the cryptography on the phone itself: PBKDF2 against published test vectors, an AES-GCM
 * round trip, tamper and wrong-key detection, fresh IVs, stable fakes and a locked vault.
 * It needs no PIN, no session, no database and no context. Every check catches its own
 * exceptions and reports them as a failure.
 */
fun runAccountsSelfTest(): List<CheckResult> = listOf(
    pbkdfCheck(1, EXPECTED_1),
    pbkdfCheck(2, EXPECTED_2),
    pbkdfCheck(4096, EXPECTED_4096),
    check("AES-GCM round trip") {
        val key = randomKey()
        val text = "P\u00E1ssw0rd \u20A6\u1ECD 123!"
        val sealed = AccountsCrypto.encrypt(key, text)
        val back = AccountsCrypto.decrypt(key, sealed.ciphertext, sealed.iv)
        if (back == text) null else "The decrypted text did not match."
    },
    check("Wrong key returns nothing") {
        val sealed = AccountsCrypto.encrypt(randomKey(), "secret")
        val back = AccountsCrypto.decrypt(randomKey(), sealed.ciphertext, sealed.iv)
        if (back == null) null else "A different key still decrypted the value."
    },
    check("Changed ciphertext is rejected") {
        val key = randomKey()
        val sealed = AccountsCrypto.encrypt(key, "secret")
        val first = sealed.ciphertext.first()
        val changed = (if (first == 'A') 'B' else 'A') + sealed.ciphertext.substring(1)
        val back = AccountsCrypto.decrypt(key, changed, sealed.iv)
        if (back == null) null else "Altered data was still accepted."
    },
    check("Same text, different ciphertext") {
        val key = randomKey()
        val one = AccountsCrypto.encrypt(key, "same text")
        val two = AccountsCrypto.encrypt(key, "same text")
        when {
            one.ciphertext == two.ciphertext -> "Two encryptions gave the same ciphertext."
            one.iv == two.iv -> "Two encryptions reused the same IV."
            else -> null
        }
    },
    check("IV is 12 bytes") {
        val sealed = AccountsCrypto.encrypt(randomKey(), "x")
        val size = Base64.decode(sealed.iv, Base64.NO_WRAP).size
        if (size == 12) null else "The IV was $size bytes."
    },
    check("Fake values are stable") {
        val one = AccountsCrypto.fake("password", "seed1")
        val again = AccountsCrypto.fake("password", "seed1")
        val other = AccountsCrypto.fake("password", "seed2")
        when {
            one != again -> "The same seed gave two different fakes."
            one == other -> "Different seeds gave the same fake."
            else -> null
        }
    },
    check("Locked vault returns nothing") {
        if (AccountsVault.mode.value != AccountsSessionMode.Locked) {
            "A session is open. Tap Lock now in Accounts PIN, then run the test again."
        } else if (AccountsVault.encrypt("x") != null) {
            "Encrypt worked without a session."
        } else if (AccountsVault.decrypt("a", "b") != null) {
            "Decrypt worked without a session."
        } else {
            null
        }
    },
)

private fun pbkdfCheck(iterations: Int, expectedHex: String): CheckResult {
    val name = "PBKDF2 test vector ($iterations ${if (iterations == 1) "iteration" else "iterations"})"
    return check(name) {
        val out = PinHasher.hash("password", "salt".toByteArray(), iterations)
        val hex = out.joinToString("") { "%02x".format(it) }
        if (hex == expectedHex) null else "Got $hex"
    }
}

/** Runs [block]; a null result means passed, a text means failed with that detail. */
private fun check(name: String, block: () -> String?): CheckResult {
    return try {
        val problem = block()
        if (problem == null) CheckResult(name, true, "") else CheckResult(name, false, problem)
    } catch (e: Exception) {
        CheckResult(name, false, "Threw ${e.javaClass.simpleName}")
    }
}

private fun randomKey(): ByteArray {
    val key = ByteArray(32)
    SecureRandom().nextBytes(key)
    return key
}
