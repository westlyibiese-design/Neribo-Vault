package com.westly.neribovault.feature.accounts.tools

import com.westly.neribovault.feature.accounts.editor.PasswordStrength

/** One stored password to check. [label] is the account name. */
data class HealthEntry(val accountId: String, val label: String, val password: String)

/** A weak password: strength [score] is 0 or 1. Holds no password. */
data class HealthWeak(val accountId: String, val label: String, val score: Int)

/** Two or more accounts that share one password. Holds names only. */
data class HealthReusedGroup(val labels: List<String>)

/** The result of a check. It never contains a password. */
data class HealthReport(
    val checked: Int,
    val strongOrGood: Int,
    val weak: List<HealthWeak>,
    val reused: List<HealthReusedGroup>,
)

/** Finds weak and shared passwords. Pure Kotlin, no storage and no logging. */
object PasswordHealthEngine {
    fun analyze(entries: List<HealthEntry>): HealthReport {
        val scored = entries.map { it to PasswordStrength.score(it.password) }
        val weak = scored
            .filter { it.second <= 1 }
            .sortedWith(
                compareBy<Pair<HealthEntry, Int>> { it.second }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.first.label },
            )
            .map { HealthWeak(it.first.accountId, it.first.label, it.second) }
        val strongOrGood = scored.count { it.second >= 3 }
        val reused = entries
            .groupBy { it.password }
            .values
            .filter { it.size >= 2 }
            .map { group ->
                HealthReusedGroup(group.map { it.label }.sortedWith(String.CASE_INSENSITIVE_ORDER))
            }
            .sortedWith(
                compareByDescending<HealthReusedGroup> { it.labels.size }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.labels.first() },
            )
        return HealthReport(
            checked = entries.size,
            strongOrGood = strongOrGood,
            weak = weak,
            reused = reused,
        )
    }
}
