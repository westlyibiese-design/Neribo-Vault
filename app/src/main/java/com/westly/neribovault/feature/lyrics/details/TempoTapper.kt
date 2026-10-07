package com.westly.neribovault.feature.lyrics.details

import kotlin.math.roundToInt

/**
 * Works out a tempo from taps. It keeps the times of the last [MAX_TAPS] taps and starts over
 * when more than [RESET_AFTER_MS] pass between two taps. Pure Kotlin: the caller supplies the time.
 */
class TempoTapper {
    private val taps = ArrayDeque<Long>()

    /**
     * Records a tap at [nowMillis]. Returns the detected beats per minute (20 to 300), or null
     * for the first tap of a run.
     */
    fun tap(nowMillis: Long): Int? {
        val last = taps.lastOrNull()
        if (last != null && nowMillis - last > RESET_AFTER_MS) taps.clear()
        taps.addLast(nowMillis)
        while (taps.size > MAX_TAPS) taps.removeFirst()
        if (taps.size < 2) return null
        val averageInterval = (taps.last() - taps.first()).toDouble() / (taps.size - 1)
        if (averageInterval <= 0.0) return null
        return (60_000.0 / averageInterval).roundToInt().coerceIn(MIN_BPM, MAX_BPM)
    }

    /** Forgets all taps. */
    fun reset() {
        taps.clear()
    }

    companion object {
        const val MAX_TAPS = 6
        const val RESET_AFTER_MS = 2_500L
        const val MIN_BPM = 20
        const val MAX_BPM = 300
    }
}
