package com.westly.neribovault.feature.lyrics.performance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.LoadingState
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.NeriboIconButton
import kotlinx.coroutines.launch

/** Auto-scroll speeds (multiples of 30dp per second), text sizes in sp, and the defaults. */
private val SPEEDS = listOf(0.5f, 1f, 1.5f, 2f, 2.5f, 3f)
private val TEXT_SIZES = listOf(20, 28, 36, 48)
private const val DEFAULT_SPEED_INDEX = 1
private const val DEFAULT_SIZE_INDEX = 1
private const val SCROLL_DP_PER_SECOND = 30f

/**
 * A full-screen reading view for singing from the phone: large serif text that scrolls by itself,
 * with a slim control bar. The screen stays on while it is shown.
 */
@Composable
fun PerformanceScreen(songId: String, onBack: () -> Unit) {
    val vm = neriboViewModel(key = "lyrics-performance-$songId") { c ->
        PerformanceViewModel(c.songsRepository, songId)
    }
    val state by vm.state.collectAsStateWithLifecycle()

    // Keep the screen on while this screen is shown, and put the old setting back afterwards.
    val view = LocalView.current
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }

    when {
        state.isLoading -> LoadingState(modifier = Modifier.fillMaxSize())
        state.notFound -> EmptyState(
            icon = Icons.Outlined.MusicNote,
            title = "Song not found",
            message = "This song may have been deleted or moved.",
            modifier = Modifier.fillMaxSize(),
            actionLabel = "Back",
            onAction = onBack,
        )
        state.sections.isEmpty() -> EmptyState(
            icon = Icons.Outlined.MusicNote,
            title = "Nothing to perform yet",
            message = "Write a few lines first, then come back to sing them.",
            modifier = Modifier.fillMaxSize(),
            actionLabel = "Back",
            onAction = onBack,
        )
        else -> PerformanceContent(state = state, onBack = onBack)
    }
}

@Composable
private fun PerformanceContent(state: PerformanceUiState, onBack: () -> Unit) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    // Session settings: they survive rotation but are not stored anywhere.
    var playing by rememberSaveable { mutableStateOf(false) }
    var speedIndex by rememberSaveable { mutableStateOf(DEFAULT_SPEED_INDEX) }
    var sizeIndex by rememberSaveable { mutableStateOf(DEFAULT_SIZE_INDEX) }
    var labelsOn by rememberSaveable { mutableStateOf(true) }

    val fontSize = TEXT_SIZES[sizeIndex]
    val speed = SPEEDS[speedIndex]

    // Auto-scroll: 30dp per second times the speed, frame by frame; it stops at the end.
    LaunchedEffect(playing, speedIndex) {
        if (!playing) return@LaunchedEffect
        val pxPerSecond = with(density) { SCROLL_DP_PER_SECOND.dp.toPx() } * SPEEDS[speedIndex]
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val seconds = (now - last) / 1_000_000_000f
            last = now
            listState.scrollBy(pxPerSecond * seconds)
            if (!listState.canScrollForward) {
                playing = false
                break
            }
        }
    }

    // Scrolling by hand pauses the auto-scroll.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) playing = false
        }
    }

    fun togglePlay() {
        if (playing) {
            playing = false
        } else {
            scope.launch {
                // Pressing Play at the very end starts again from the top.
                if (!listState.canScrollForward) listState.scrollToItem(0)
                playing = true
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { togglePlay() },
                ),
            contentPadding = PaddingValues(
                start = spacing.screen,
                end = spacing.screen,
                top = statusTop + 64.dp,
                bottom = 200.dp,
            ),
        ) {
            itemsIndexed(state.sections) { index, section ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (labelsOn) {
                        Text(
                            text = state.labels.getOrElse(index) { "" }.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = (fontSize * 0.4f).coerceAtLeast(11f).sp,
                                letterSpacing = 0.8.sp,
                            ),
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(bottom = spacing.xs),
                        )
                    }
                    Text(
                        text = section.text,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = FontFamily.Serif,
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize * 1.4f).sp,
                        ),
                        color = colors.onBackground,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(fontSize.dp))
                }
            }
        }

        // Back arrow, always visible at the top left.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(spacing.xs)
                .background(colors.surface.copy(alpha = 0.7f), CircleShape),
        ) {
            NeriboIconButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Back",
                onClick = onBack,
            )
        }

        // The slim control bar pinned to the bottom.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(colors.surface.copy(alpha = 0.9f)),
        ) {
            NeriboDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = spacing.sm, vertical = spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NeriboIconButton(
                    icon = if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    onClick = { togglePlay() },
                    tint = colors.primary,
                )
                NeriboIconButton(
                    icon = Icons.Outlined.Remove,
                    contentDescription = "Slower",
                    onClick = { if (speedIndex > 0) speedIndex -= 1 },
                    modifier = Modifier.alpha(if (speedIndex > 0) 1f else 0.38f),
                )
                Box(
                    modifier = Modifier
                        .size(width = 44.dp, height = 48.dp)
                        .semantics { contentDescription = "Speed " + speedLabel(speed) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = speedLabel(speed),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurface,
                    )
                }
                NeriboIconButton(
                    icon = Icons.Outlined.Add,
                    contentDescription = "Faster",
                    onClick = { if (speedIndex < SPEEDS.lastIndex) speedIndex += 1 },
                    modifier = Modifier.alpha(if (speedIndex < SPEEDS.lastIndex) 1f else 0.38f),
                )
                TextSizeButton(
                    sample = 14,
                    description = "Smaller text",
                    enabled = sizeIndex > 0,
                    onClick = { if (sizeIndex > 0) sizeIndex -= 1 },
                )
                TextSizeButton(
                    sample = 24,
                    description = "Larger text",
                    enabled = sizeIndex < TEXT_SIZES.lastIndex,
                    onClick = { if (sizeIndex < TEXT_SIZES.lastIndex) sizeIndex += 1 },
                )
                NeriboChip(
                    label = "Labels",
                    selected = labelsOn,
                    onClick = { labelsOn = !labelsOn },
                )
            }
        }
    }
}

/** "0.5x", "1x", "1.5x" ... */
private fun speedLabel(speed: Float): String =
    if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"

/** A 48dp "A" button: a small A for smaller text and a big A for larger text. */
@Composable
private fun TextSizeButton(
    sample: Int,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "A",
            style = MaterialTheme.typography.titleMedium.copy(fontSize = sample.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
