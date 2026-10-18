package com.westly.neribovault.feature.coder.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The smallest share of the space the code editor keeps while the preview is next to it. */
const val SPLIT_MIN_RATIO = 0.25f

/** The largest share of the space the code editor takes while the preview is next to it. */
const val SPLIT_MAX_RATIO = 0.75f

/** The share of the space the editor starts with. */
const val SPLIT_DEFAULT_RATIO = 0.5f

/** The width in dp from which the editor and the preview sit side by side instead of one above the other. */
const val SPLIT_WIDE_MIN_DP = 600

private val DIVIDER_TOUCH_SIZE = 48.dp
private val DIVIDER_LINE_SIZE = 2.dp

/** Keeps the editor's share between [SPLIT_MIN_RATIO] and [SPLIT_MAX_RATIO]. A value that is not a number gives [SPLIT_DEFAULT_RATIO]. */
fun clampRatio(ratio: Float): Float = when {
    ratio.isNaN() -> SPLIT_DEFAULT_RATIO
    ratio < SPLIT_MIN_RATIO -> SPLIT_MIN_RATIO
    ratio > SPLIT_MAX_RATIO -> SPLIT_MAX_RATIO
    else -> ratio
}

/**
 * Shows [first] (the editor) and [second] (the preview) together with a draggable divider between them: side by
 * side when the space is at least [SPLIT_WIDE_MIN_DP] dp wide, one above the other when it is narrower. [ratio] is
 * the editor's share and is always kept within 25% to 75%. With [collapseSecondWhenNarrow] set, a narrow layout
 * gives all the room to [first] and shrinks [second] to nothing without removing it, so the page is not loaded again.
 * The divider is a 48 dp touch strip with a thin line in the middle.
 */
@Composable
fun SplitLayout(
    ratio: Float,
    onRatioChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    collapseSecondWhenNarrow: Boolean = false,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier) {
        val wide = maxWidth >= SPLIT_WIDE_MIN_DP.dp
        val collapsed = !wide && collapseSecondWhenNarrow
        val totalPx = with(density) { (if (wide) maxWidth else maxHeight).toPx() }
        val availablePx = (totalPx - with(density) { DIVIDER_TOUCH_SIZE.toPx() }).coerceAtLeast(1f)
        val currentRatio by rememberUpdatedState(clampRatio(ratio))
        val currentAvailable by rememberUpdatedState(availablePx)
        val currentChange by rememberUpdatedState(onRatioChange)
        val dragState = rememberDraggableState { delta ->
            currentChange(clampRatio(currentRatio + delta / currentAvailable))
        }
        val firstSize: Dp = with(density) { (availablePx * clampRatio(ratio)).toDp() }
        if (wide) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.width(firstSize).fillMaxHeight().clipToBounds()) { first() }
                DividerStrip(vertical = true, draggableState = dragState)
                Box(modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds()) { second() }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                val firstModifier = if (collapsed) Modifier.weight(1f).fillMaxWidth() else Modifier.height(firstSize).fillMaxWidth()
                Box(modifier = firstModifier.clipToBounds()) { first() }
                if (!collapsed) DividerStrip(vertical = false, draggableState = dragState)
                val secondModifier = if (collapsed) Modifier.height(0.dp).fillMaxWidth() else Modifier.weight(1f).fillMaxWidth()
                Box(modifier = secondModifier.clipToBounds()) { second() }
            }
        }
    }
}

/** The 48 dp strip that is dragged to change the split, with a thin line in its middle. [vertical] means the strip stands upright (side by side). */
@Composable
private fun DividerStrip(vertical: Boolean, draggableState: DraggableState) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val orientation = if (vertical) Orientation.Horizontal else Orientation.Vertical
    val sizeModifier = if (vertical) {
        Modifier.width(DIVIDER_TOUCH_SIZE).fillMaxHeight()
    } else {
        Modifier.height(DIVIDER_TOUCH_SIZE).fillMaxWidth()
    }
    Box(
        modifier = sizeModifier
            .semantics { contentDescription = "Drag to change the size of the preview" }
            .draggable(state = draggableState, orientation = orientation),
        contentAlignment = Alignment.Center,
    ) {
        val lineModifier = if (vertical) {
            Modifier.width(DIVIDER_LINE_SIZE).fillMaxHeight()
        } else {
            Modifier.height(DIVIDER_LINE_SIZE).fillMaxWidth()
        }
        Box(modifier = lineModifier.background(lineColor))
    }
}
