package com.westly.neribovault.feature.lyrics.engine

import java.text.Normalizer

/** A rule-of-thumb syllable estimate. Always labeled "estimate" in the UI. */
object SyllableCounter {

    private val combiningMarks = Regex("\\p{Mn}")
    private const val VOWELS = "aeiou"

    /** Estimated syllables in one word; 0 when the word has no letters a to z (after removing accents). */
    fun countWord(word: String): Int {
        val stripped = Normalizer.normalize(word, Normalizer.Form.NFD)
            .replace(combiningMarks, "")
            .lowercase()
        val letters = stripped.filter { it in 'a'..'z' }
        if (letters.isEmpty()) return 0
        val hasVowel = letters.any { it in VOWELS }
        if (!hasVowel && 'y' !in letters) return 1
        // Without a, e, i, o or u (as in "my" or "rhythm") the letter y is the only vowel.
        val vowelLetters = if (hasVowel) VOWELS else "y"
        var groups = 0
        var previousWasVowel = false
        for (c in letters) {
            val vowel = c in vowelLetters
            if (vowel && !previousWasVowel) groups += 1
            previousWasVowel = vowel
        }
        val endsWithConsonantLe = letters.length >= 3 &&
            letters.endsWith("le") &&
            letters[letters.length - 3] !in VOWELS
        if (groups >= 2 && letters.endsWith("e") && !endsWithConsonantLe) groups -= 1
        return maxOf(groups, 1)
    }

    /** Sum of [countWord] over the whitespace-separated words of [line]. */
    fun countLine(line: String): Int =
        line.split(Regex("\\s+")).filter { it.isNotEmpty() }.sumOf { countWord(it) }
}
