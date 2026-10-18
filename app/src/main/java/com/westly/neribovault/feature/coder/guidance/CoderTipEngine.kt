package com.westly.neribovault.feature.coder.guidance

import com.westly.neribovault.feature.coder.GuidanceMode

/**
 * What the engine needs to know besides the screen: the Beginner or Pro choice, the tips switch, the tips
 * already [seen], whether a tip is on screen right now ([visible]) and the session counters. The counters live
 * in memory only, so restarting the app starts them from zero again.
 */
data class TipState(
    val mode: GuidanceMode,
    val tipsEnabled: Boolean,
    val seen: Set<String>,
    val visible: Boolean,
    val shownThisSession: Int,
    /** When the last tip appeared this session (`SystemClock.elapsedRealtime`), or null when none did yet. */
    val lastShownAtMillis: Long?,
)

/** How a tip ended: the person tapped Got it, the 12 seconds ran out, or the person left the screen. */
enum class TipEnd { GotIt, TimedOut, Left }

/**
 * The quiet rules of decision N1 as pure functions: no Android types and no clock of its own, so every rule
 * is checked by the debug self-test. A tip is shown only when guidance is on, nothing else is showing, the
 * person is not typing, the screen is idle and the session limits allow it.
 */
object CoderTipEngine {
    /** No change to the file and no keyboard for this long counts as "not typing". */
    const val TYPING_QUIET_MS = 3_000L

    /** A visible tip hides itself after this long. */
    const val AUTO_HIDE_MS = 12_000L

    /** A tip that was on screen for less than this when the person left is not marked as seen. */
    const val MIN_SEEN_MS = 3_000L

    /** Most tips in one app session. */
    const val MAX_PER_SESSION = 3

    /** Least time between the start of one tip and the start of the next. */
    const val COOLDOWN_MS = 90_000L

    /** The one tip to show now, or null. The first fitting tip in [CoderTips.all] wins. */
    fun next(context: TipContext, state: TipState): Tip? {
        if (!allowedNow(context, state)) return null
        return CoderTips.all.firstOrNull { tip ->
            tip.screen == context.screen && tip.id !in state.seen && tip.trigger(context)
        }
    }

    /** True when rules 1 and 4 to 7 allow a tip at this moment, whatever the tip is. */
    fun allowedNow(context: TipContext, state: TipState): Boolean {
        if (state.mode != GuidanceMode.Beginner || !state.tipsEnabled) return false
        if (state.visible || context.welcomeVisible) return false
        if (isTyping(context)) return false
        if (context.busy) return false
        if (state.shownThisSession >= MAX_PER_SESSION) return false
        val last = state.lastShownAtMillis
        if (last != null && context.nowMillis - last < COOLDOWN_MS) return false
        return true
    }

    /** True when the person is typing: the file changed in the last 3 seconds or the keyboard is on screen. */
    fun isTyping(context: TipContext): Boolean {
        if (context.keyboardVisible) return true
        val typedAt = context.lastTypedAtMillis
        return typedAt > 0L && context.nowMillis - typedAt < TYPING_QUIET_MS
    }

    /** True when [screen] still has a tip that could appear, so the host knows whether it is worth looking again. */
    fun hasPending(screen: TipScreen, state: TipState): Boolean {
        if (state.mode != GuidanceMode.Beginner || !state.tipsEnabled) return false
        return CoderTips.all.any { it.screen == screen && it.id !in state.seen }
    }

    /**
     * Whether a tip that ended is remembered as seen. Got it and the 12-second timeout always count; leaving
     * the screen counts only when the tip was there for at least [MIN_SEEN_MS].
     */
    fun shouldMarkSeen(end: TipEnd, shownForMillis: Long): Boolean = when (end) {
        TipEnd.GotIt, TipEnd.TimedOut -> true
        TipEnd.Left -> shownForMillis >= MIN_SEEN_MS
    }

    /** True when the welcome card shows: Beginner mode, not seen before and no project yet. */
    fun showWelcome(mode: GuidanceMode, welcomeSeen: Boolean, projectCount: Int): Boolean =
        mode == GuidanceMode.Beginner && !welcomeSeen && projectCount == 0
}
