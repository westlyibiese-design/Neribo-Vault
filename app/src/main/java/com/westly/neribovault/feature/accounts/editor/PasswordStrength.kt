package com.westly.neribovault.feature.accounts.editor

import kotlin.math.max

/** Scores a password from 0 (very weak) to 4 (strong). Pure Kotlin, no randomness. */
object PasswordStrength {
    private val WEAK_WORDS = listOf("password", "qwerty", "12345", "admin")

    /**
     * Under 8 characters is 0. Otherwise: start from the number of character groups used minus
     * one (never below 0), add 1 at 12 or more characters and 1 more at 16 or more, subtract 1
     * for a run of 3 or more identical characters or a common word, and clamp to 0..4.
     */
    fun score(password: String): Int {
        if (password.length < 8) return 0
        var groups = 0
        if (password.any { it.isLowerCase() }) groups += 1
        if (password.any { it.isUpperCase() }) groups += 1
        if (password.any { it.isDigit() }) groups += 1
        if (password.any { !it.isLetterOrDigit() }) groups += 1
        var result = max(groups - 1, 0)
        if (password.length >= 12) result += 1
        if (password.length >= 16) result += 1
        if (hasRepeatedRun(password) || containsWeakWord(password)) result -= 1
        return result.coerceIn(0, 4)
    }

    /** The words shown beside the strength bar. */
    fun label(score: Int): String = when (score) {
        0 -> "Very weak"
        1 -> "Weak"
        2 -> "Fair"
        3 -> "Good"
        else -> "Strong"
    }

    private fun hasRepeatedRun(password: String): Boolean {
        var run = 1
        for (i in 1 until password.length) {
            run = if (password[i] == password[i - 1]) run + 1 else 1
            if (run >= 3) return true
        }
        return false
    }

    private fun containsWeakWord(password: String): Boolean {
        val lower = password.lowercase()
        return WEAK_WORDS.any { it in lower }
    }
}
