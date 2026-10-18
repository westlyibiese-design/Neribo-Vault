package com.westly.neribovault.feature.coder.guidance

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.NeriboApp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.feature.coder.GuidanceMode
import com.westly.neribovault.feature.coder.ui.CoderHintCard
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How often a screen with a waiting tip looks again for a quiet moment. */
private const val TIP_POLL_MS = 1_000L

/**
 * The typing facts the workspace view model keeps: the time of the last change to the open file (0 = none yet,
 * on the `SystemClock.elapsedRealtime` clock) and how many characters were typed this session.
 */
data class TipTyping(val lastTypedAtMillis: Long, val typedChars: Int) {
    companion object {
        /** No typing at all. */
        val None = TipTyping(0L, 0)
    }
}

/**
 * What this app session remembers about tips: how many appeared, when the last one appeared, which ones the
 * person dismissed (so a tip never comes back before the saved list has caught up) and the tip that is on
 * screen right now (so a rotation shows it again instead of counting a second tip). All of it lives in memory
 * and starts again from zero when the app restarts. Used from the main thread only.
 */
object CoderTipSession {
    private var shownCount = 0
    private var lastShownAtMillis: Long? = null
    private val dismissed = HashSet<String>()
    private var activeId: String? = null
    private var activeShownAt = 0L

    /** The engine's view of the session: [seen] plus the tips dismissed in this session. */
    fun stateFor(mode: GuidanceMode, tipsEnabled: Boolean, seen: Set<String>, visible: Boolean): TipState =
        TipState(mode, tipsEnabled, seen + dismissed, visible, shownCount, lastShownAtMillis)

    /** Records that the tip [id] appeared at [now]. */
    fun recordShown(id: String, now: Long) {
        shownCount += 1
        lastShownAtMillis = now
        activeId = id
        activeShownAt = now
    }

    /** When the tip [id] appeared, or null when it is not the tip on screen. */
    fun shownAt(id: String): Long? = if (activeId == id) activeShownAt else null

    /** Remembers that the tip [id] is done for good, and that it is no longer on screen. */
    fun markDismissed(id: String) {
        dismissed.add(id)
        if (activeId == id) activeId = null
    }

    /** The tip that was on [screen] before a rotation and still has time left, or null. */
    fun restorable(screen: TipScreen, now: Long): Tip? {
        val id = activeId ?: return null
        val tip = CoderTips.byId(id)
        val expired = now - activeShownAt >= CoderTipEngine.AUTO_HIDE_MS
        if (tip == null || id in dismissed || expired) {
            activeId = null
            return null
        }
        return if (tip.screen == screen) tip else null
    }
}

/**
 * The one place a tip appears. It draws nothing (zero height) when there is no tip; otherwise it draws the
 * hint card with Got it. It looks for a quiet moment with [CoderTipEngine], hides the tip after 12 seconds,
 * and remembers it as seen according to [CoderTipEngine.shouldMarkSeen]. [context] carries what the screen
 * knows; the host adds the clock, the keyboard and, through [typing], the typing facts. Place it first in the
 * content, directly under the top bar.
 */
@Composable
fun CoderTipHost(
    screen: TipScreen,
    context: TipContext,
    modifier: Modifier = Modifier,
    typing: () -> TipTyping = { TipTyping.None },
) {
    val appContext = LocalContext.current
    val prefs = remember(appContext) { (appContext.applicationContext as NeriboApp).container.coderPrefs }
    val mode by prefs.guidanceMode.collectAsStateWithLifecycle<GuidanceMode?>(initialValue = null)
    val enabled by prefs.tipsEnabled.collectAsStateWithLifecycle<Boolean?>(initialValue = null)
    val seen by prefs.hintsSeen.collectAsStateWithLifecycle<Set<String>?>(initialValue = null)
    val scope = rememberCoroutineScope()

    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val keyboardVisible by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
    var keyboardSeen by remember { mutableStateOf(false) }
    LaunchedEffect(keyboardVisible) { if (keyboardVisible) keyboardSeen = true }

    var shown by remember { mutableStateOf(CoderTipSession.restorable(screen, SystemClock.elapsedRealtime())) }
    val latestContext by rememberUpdatedState(context)
    val latestTyping by rememberUpdatedState(typing)
    val latestKeyboard by rememberUpdatedState(keyboardVisible)
    val latestKeyboardSeen by rememberUpdatedState(keyboardSeen)

    val currentMode = mode
    val currentEnabled = enabled
    val currentSeen = seen

    // Looks for a quiet moment while no tip is showing; stops as soon as one is chosen or none is left.
    LaunchedEffect(currentMode, currentEnabled, currentSeen, shown == null, screen) {
        if (currentMode == null || currentEnabled == null || currentSeen == null || shown != null) return@LaunchedEffect
        while (isActive) {
            val now = SystemClock.elapsedRealtime()
            val state = CoderTipSession.stateFor(currentMode, currentEnabled, currentSeen, visible = false)
            if (!CoderTipEngine.hasPending(screen, state)) return@LaunchedEffect
            val live = latestTyping()
            val base = latestContext
            val tip = CoderTipEngine.next(
                base.copy(
                    screen = screen,
                    nowMillis = now,
                    lastTypedAtMillis = live.lastTypedAtMillis,
                    typedChars = live.typedChars,
                    keyboardVisible = latestKeyboard,
                    keyboardSeenInEditor = base.keyboardSeenInEditor || latestKeyboardSeen,
                ),
                state,
            )
            if (tip != null) {
                CoderTipSession.recordShown(tip.id, now)
                shown = tip
                return@LaunchedEffect
            }
            delay(TIP_POLL_MS)
        }
    }

    // Keeps a shown tip for 12 seconds, then hides it. Leaving the screen early marks it seen only after 3 seconds.
    val active = shown
    LaunchedEffect(active?.id) {
        val tip = active ?: return@LaunchedEffect
        val startedAt = CoderTipSession.shownAt(tip.id) ?: SystemClock.elapsedRealtime()
        var end = TipEnd.Left
        try {
            delay((CoderTipEngine.AUTO_HIDE_MS - (SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0L))
            end = TipEnd.TimedOut
        } finally {
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (CoderTipEngine.shouldMarkSeen(end, elapsed)) {
                CoderTipSession.markDismissed(tip.id)
                withContext(NonCancellable) { prefs.markHintSeen(tip.id) }
            }
        }
        shown = null
    }

    if (active != null) {
        CoderHintCard(
            id = active.id,
            text = active.text,
            modifier = modifier,
            onDismiss = {
                CoderTipSession.markDismissed(active.id)
                shown = null
                scope.launch { withContext(NonCancellable) { prefs.markHintSeen(active.id) } }
            },
        )
    }
}

/**
 * The welcome card of the Coder home screen: one title, one sentence, New project and Skip. It is not a tour.
 * Either button is followed by the caller marking the card as seen.
 */
@Composable
fun CoderWelcomeCard(onNewProject: () -> Unit, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    NeriboCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = "Welcome to Coder",
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
            )
            Text(
                text = "Make a project from a template, change the code, and tap Run to see it.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                NeriboButton(text = "New project", onClick = onNewProject)
                NeriboButton(text = "Skip", onClick = onSkip, style = ButtonStyle.Text)
            }
        }
    }
}
