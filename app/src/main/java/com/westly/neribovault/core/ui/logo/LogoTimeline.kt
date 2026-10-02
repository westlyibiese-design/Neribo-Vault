package com.westly.neribovault.core.ui.logo

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * Pure timing data and helpers for the animated Neribo Vault logo.
 *
 * All times are milliseconds from the start of the animation. The whole animation is driven
 * by one clock that runs linearly from 0 to [TOTAL_MS].
 */
object LogoTimeline {
    /** Length of the single driving clock. */
    const val TOTAL_MS: Float = 4950f

    /** Pause after the clock ends, before the logo reports that it is finished. */
    const val HOLD_AFTER_MS: Long = 250L

    /** Pause on the final frame when system animations are turned off. */
    const val REDUCED_MOTION_HOLD_MS: Long = 800L

    // Ring: the open "C" arc draws itself.
    const val RING_START: Float = 250f
    const val RING_DURATION: Float = 1500f
    val RING_EASING: Easing = CubicBezierEasing(0.65f, 0.05f, 0.36f, 1.0f)

    // Middle arrow: grows to the right from its fixed left edge.
    const val MIDDLE_START: Float = 1500f
    const val MIDDLE_DURATION: Float = 700f
    val MIDDLE_EASING: Easing = CubicBezierEasing(0.22f, 0.70f, 0.32f, 1.0f)

    /** The middle arrow's opacity reaches 1 after this share of its duration. */
    const val MIDDLE_FADE_SHARE: Float = 0.4f

    // Side arrows: fade in while sliding left into place.
    const val TOP_START: Float = 2050f
    const val BOTTOM_START: Float = 2180f
    const val SIDE_DURATION: Float = 550f
    val SIDE_EASING: Easing = CubicBezierEasing(0.20f, 0.90f, 0.30f, 1.05f)

    /** Reference units the side arrows start to the right of their final place. */
    const val SIDE_SLIDE_UNITS: Float = 26f

    // Wordmark "Neribo Vault": fades in while moving up.
    const val WORDMARK_START: Float = 2750f
    const val WORDMARK_DURATION: Float = 600f
    val WORDMARK_EASING: Easing = CubicBezierEasing(0.16f, 0.80f, 0.24f, 1.0f)

    /** Reference dp the wordmark starts below its final place. */
    const val WORDMARK_RISE_DP: Float = 16f

    // Tagline "© NERIBO GROUP": simple fade.
    const val TAGLINE_START: Float = 3350f
    const val TAGLINE_DURATION: Float = 600f
    val TAGLINE_EASING: Easing = CubicBezierEasing(0.0f, 0.0f, 0.58f, 1.0f)

    // Icon shine: a soft white band sweeps across the icon shapes.
    const val ICON_SHINE_START: Float = 4050f
    const val ICON_SHINE_DURATION: Float = 900f
    val ICON_SHINE_EASING: Easing = CubicBezierEasing(0.33f, 0.0f, 0.20f, 1.0f)
    const val SHINE_BAND_WIDTH: Float = 220f
    const val SHINE_PEAK_ALPHA: Float = 0.95f
    const val SHINE_FROM_X: Float = 0f
    const val SHINE_TO_X: Float = 970f

    // Wordmark shine: a highlight band sweeps once across the text.
    const val WORDMARK_SHINE_START: Float = 4050f
    const val WORDMARK_SHINE_DURATION: Float = 900f
    val WORDMARK_SHINE_EASING: Easing = CubicBezierEasing(0.42f, 0.0f, 0.58f, 1.0f)

    /** Linear 0..1 share of the duration that has passed. Never divides by zero. */
    fun fraction(nowMs: Float, startMs: Float, durationMs: Float): Float {
        val safeDuration = if (durationMs > 0f) durationMs else 1f
        return ((nowMs - startMs) / safeDuration).coerceIn(0f, 1f)
    }

    /** Eased progress, clamped to 0..1 on both input and output. */
    fun progress(nowMs: Float, startMs: Float, durationMs: Float, easing: Easing): Float {
        return easing.transform(fraction(nowMs, startMs, durationMs)).coerceIn(0f, 1f)
    }

    /**
     * Eased progress where only the input is clamped. The result may overshoot 1.0 slightly
     * for curves whose second control value is above 1 (the side arrows).
     */
    fun progressOvershoot(nowMs: Float, startMs: Float, durationMs: Float, easing: Easing): Float {
        return easing.transform(fraction(nowMs, startMs, durationMs))
    }
}
