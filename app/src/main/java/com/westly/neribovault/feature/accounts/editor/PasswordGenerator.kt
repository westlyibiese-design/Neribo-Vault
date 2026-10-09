package com.westly.neribovault.feature.accounts.editor

import java.security.SecureRandom

/** Makes random passwords with [SecureRandom]. Nothing is logged or stored here. */
object PasswordGenerator {
    private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val DIGITS = "0123456789"
    private const val SYMBOLS = "!@#\$%^&*-_+=?"
    private const val AMBIGUOUS = "0Oo1lI|"
    private const val MIN_LENGTH = 8
    private const val MAX_LENGTH = 64

    private val random = SecureRandom()

    /**
     * A password of [length] (clamped to 8..64) with at least one character from every selected
     * group. When no group is selected, lower case is used. With [avoidAmbiguous] the characters
     * `0 O o 1 l I |` are never used.
     */
    fun generate(
        length: Int,
        lower: Boolean,
        upper: Boolean,
        digits: Boolean,
        symbols: Boolean,
        avoidAmbiguous: Boolean,
    ): String {
        val size = length.coerceIn(MIN_LENGTH, MAX_LENGTH)
        val groups = ArrayList<String>()
        if (lower) groups.add(LOWER)
        if (upper) groups.add(UPPER)
        if (digits) groups.add(DIGITS)
        if (symbols) groups.add(SYMBOLS)
        if (groups.isEmpty()) groups.add(LOWER)
        val usable = groups.map { group ->
            if (avoidAmbiguous) group.filter { it !in AMBIGUOUS } else group
        }
        val pool = usable.joinToString("")

        val chars = CharArray(size)
        var filled = 0
        for (group in usable) {
            chars[filled] = group[random.nextInt(group.length)]
            filled += 1
        }
        while (filled < size) {
            chars[filled] = pool[random.nextInt(pool.length)]
            filled += 1
        }
        // Fisher-Yates, so the guaranteed characters are not always at the front.
        for (i in size - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val swap = chars[i]
            chars[i] = chars[j]
            chars[j] = swap
        }
        return String(chars)
    }
}
