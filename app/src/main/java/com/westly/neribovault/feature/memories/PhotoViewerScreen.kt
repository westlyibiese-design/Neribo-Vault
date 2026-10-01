package com.westly.neribovault.feature.memories

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.feature.memories.components.MemoryPhoto

/** The most a photo can be zoomed in. */
private const val MAX_ZOOM = 4f

/** The zoom a double-tap jumps to. */
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * Full-screen photo viewer on the theme's background: swipe between the memory's photos,
 * pinch or double-tap to zoom, "2 of 5" in the top bar and a close button.
 */
@Composable
fun PhotoViewerScreen(
    memoryId: String,
    startIndex: Int,
    onBack: () -> Unit,
) {
    val vm = neriboViewModel(key = "viewer-$memoryId") { c ->
        MemoryDetailViewModel(memoryId, c.memoriesRepository)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val photos = state.memory?.photoUris.orEmpty()

    // Leave when the memory is gone or has no photos (for example it was just deleted).
    LaunchedEffect(state.isLoading, photos.isEmpty()) {
        if (!state.isLoading && photos.isEmpty()) onBack()
    }

    if (photos.isEmpty()) {
        NeriboScaffold(topBar = { NeriboTopBar(title = "Photo") }) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding))
        }
    } else {
        ViewerContent(photos = photos, startIndex = startIndex, onClose = onBack)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ViewerContent(photos: List<String>, startIndex: Int, onClose: () -> Unit) {
    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, photos.lastIndex),
        pageCount = { photos.size },
    )
    // Whether the photo on screen is zoomed in. While it is, swiping pans the photo instead of
    // moving to the next one.
    var zoomed by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState.currentPage) {
        zoomed = false
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = "${pagerState.currentPage + 1} of ${photos.size}",
                actions = {
                    NeriboIconButton(
                        icon = Icons.Outlined.Close,
                        contentDescription = "Close photo viewer",
                        onClick = onClose,
                    )
                },
            )
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding),
            userScrollEnabled = !zoomed,
        ) { page ->
            ZoomablePhoto(
                path = photos[page],
                description = "Photo ${page + 1} of ${photos.size}",
                onZoomChange = { isZoomed ->
                    if (page == pagerState.currentPage) zoomed = isZoomed
                },
            )
        }
    }
}

/**
 * One photo with pinch-to-zoom, panning while zoomed and double-tap to zoom in and out. A single
 * finger on an unzoomed photo is left alone so the pager can swipe.
 */
@Composable
private fun ZoomablePhoto(
    path: String,
    description: String,
    onZoomChange: (Boolean) -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    /** Keeps the zoomed photo from being dragged completely off the screen. */
    fun clampOffset(raw: Offset, forScale: Float): Offset {
        if (forScale <= 1f) return Offset.Zero
        val maxX = boxSize.width * (forScale - 1f) / 2f
        val maxY = boxSize.height * (forScale - 1f) / 2f
        return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            val center = Offset(boxSize.width / 2f, boxSize.height / 2f)
                            scale = DOUBLE_TAP_ZOOM
                            // Zoom in around the tapped point.
                            offset = clampOffset((tap - center) * (1f - DOUBLE_TAP_ZOOM), DOUBLE_TAP_ZOOM)
                        }
                        onZoomChange(scale > 1f)
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event: PointerEvent = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        // Only take over the gesture for a pinch or while already zoomed.
                        if (fingers >= 2 || scale > 1f) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val newScale = (scale * zoomChange).coerceIn(1f, MAX_ZOOM)
                            val centroid = event.changes
                                .filter { it.pressed }
                                .fold(Offset.Zero) { sum, change -> sum + change.position }
                                .let { if (fingers > 0) it / fingers.toFloat() else it }
                            val center = Offset(boxSize.width / 2f, boxSize.height / 2f)
                            // Keep the point between the fingers where it is while zooming.
                            val zoomedOffset = (centroid - center) -
                                ((centroid - center) - offset) * (newScale / scale)
                            offset = clampOffset(zoomedOffset + panChange, newScale)
                            scale = newScale
                            onZoomChange(scale > 1f)
                            event.changes.forEach { change ->
                                if (change.positionChanged()) change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        MemoryPhoto(
            path = path,
            contentDescription = description,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            contentScale = ContentScale.Fit,
            fullSize = true,
        )
    }
}
