package com.westly.neribovault.feature.authenticator.backup

import com.westly.neribovault.feature.authenticator.CheckResult
import com.westly.neribovault.feature.authenticator.engine.Base32

private const val PASSWORD = "harmattan-2026"
private const val CREATED = 1_790_000_000_000L

/** Debug only: the 7 checks of the backup file format. */
fun runBackupSelfTest(): List<CheckResult> {
    val results = ArrayList<CheckResult>()

    results.add(
        check("Round trip of three accounts") {
            val input = sampleAccounts()
            val result = BackupFile.read(BackupFile.write(input, PASSWORD, CREATED), PASSWORD)
            if (result !is BackupReadResult.Success) return@check "did not open"
            val out = result.accounts
            when {
                out.size != input.size -> "expected ${input.size} accounts, got ${out.size}"
                result.skipped != 0 -> "${result.skipped} accounts skipped"
                result.createdAtMillis != CREATED -> "creation time differs"
                else -> input.indices.firstNotNullOfOrNull { i -> accountDifference(i, input[i], out[i]) }
            }
        },
    )

    results.add(
        check("Wrong password") {
            val bytes = BackupFile.write(sampleAccounts(), PASSWORD, CREATED)
            val result = BackupFile.read(bytes, "not-the-password")
            if (result === BackupReadResult.WrongPasswordOrDamaged) null else "wrong password was not refused"
        },
    )

    results.add(
        check("Changed byte") {
            val bytes = BackupFile.write(sampleAccounts(), PASSWORD, CREATED)
            val index = bytes.size - 20
            bytes[index] = (bytes[index].toInt() xor 0x01).toByte()
            val result = BackupFile.read(bytes, PASSWORD)
            if (result === BackupReadResult.WrongPasswordOrDamaged) null else "changed data was accepted"
        },
    )

    results.add(
        check("Not a backup") {
            val bytes = ByteArray(80) { it.toByte() }
            val result = BackupFile.read(bytes, PASSWORD)
            if (result === BackupReadResult.NotABackup) null else "other bytes were not refused"
        },
    )

    results.add(
        check("Empty list") {
            val result = BackupFile.read(BackupFile.write(emptyList(), PASSWORD, CREATED), PASSWORD)
            when {
                result !is BackupReadResult.Success -> "did not open"
                result.accounts.isNotEmpty() -> "expected no accounts"
                else -> null
            }
        },
    )

    results.add(
        check("Fresh salt and IV") {
            val input = sampleAccounts()
            val one = BackupFile.write(input, PASSWORD, CREATED)
            val two = BackupFile.write(input, PASSWORD, CREATED)
            if (one.contentEquals(two)) "two writes were identical" else null
        },
    )

    results.add(
        check("File header and size") {
            val bytes = BackupFile.write(emptyList(), PASSWORD, CREATED)
            val magic = BackupFile.MAGIC.toByteArray(Charsets.US_ASCII)
            when {
                bytes.size < 49 -> "only ${bytes.size} bytes"
                !bytes.copyOfRange(0, magic.size).contentEquals(magic) -> "does not start with ${BackupFile.MAGIC}"
                else -> null
            }
        },
    )

    return results
}

private fun sampleAccounts(): List<BackupAccount> = listOf(
    BackupAccount(
        issuer = "Ẹ̀gbọ́n ₦",
        accountName = "adaeze@example.com",
        secret = Base32.decode("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ") ?: ByteArray(20) { 1 },
        algorithm = "SHA1",
        digits = 6,
        periodSeconds = 30,
        notes = "First line\nSecond line",
        isPinned = false,
    ),
    BackupAccount(
        issuer = "Bank of Benin",
        accountName = "tunde",
        secret = ByteArray(64) { (it + 3).toByte() },
        algorithm = "SHA512",
        digits = 8,
        periodSeconds = 60,
        notes = "",
        isPinned = false,
    ),
    BackupAccount(
        issuer = "Lagos Mail",
        accountName = "ifeoma",
        secret = ByteArray(32) { (it * 7 + 1).toByte() },
        algorithm = "SHA256",
        digits = 6,
        periodSeconds = 30,
        notes = "Work",
        isPinned = true,
    ),
)

/** Null when [a] and [b] are equal in every field, otherwise a short description of the difference. */
private fun accountDifference(index: Int, a: BackupAccount, b: BackupAccount): String? = when {
    a.issuer != b.issuer -> "account ${index + 1}: service differs"
    a.accountName != b.accountName -> "account ${index + 1}: account name differs"
    !a.secret.contentEquals(b.secret) -> "account ${index + 1}: secret differs"
    a.algorithm != b.algorithm -> "account ${index + 1}: algorithm differs"
    a.digits != b.digits -> "account ${index + 1}: digits differ"
    a.periodSeconds != b.periodSeconds -> "account ${index + 1}: period differs"
    a.notes != b.notes -> "account ${index + 1}: notes differ"
    a.isPinned != b.isPinned -> "account ${index + 1}: pinned flag differs"
    else -> null
}

/** Runs [block]; null means passed, text means failed. Any exception becomes a failed check. */
private inline fun check(name: String, block: () -> String?): CheckResult = try {
    val problem = block()
    CheckResult(name, problem == null, problem ?: "ok")
} catch (e: Exception) {
    CheckResult(name, false, "exception: ${e.javaClass.simpleName}")
}
