package com.westly.neribovault.feature.documents.viewer

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.feature.documents.DocumentDetailViewModel
import com.westly.neribovault.feature.documents.components.DocumentImage
import com.westly.neribovault.feature.documents.components.rememberFileExists
import com.westly.neribovault.feature.documents.isPdfPath
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The most a photo can be zoomed in. */
private const val MAX_ZOOM = 4f

/** The zoom a double-tap jumps to. */
private const val DOUBLE_TAP_ZOOM = 2.5f

/** Widest a rendered PDF page gets, in pixels, however wide the screen is. */
private const val MAX_PAGE_WIDTH_PX = 1600

/**
 * Full-screen viewer for a document's attachment, on the theme's background: a photo you can
 * pinch and double-tap to zoom, or a PDF you scroll through page by page.
 */
@Composable
fun AttachmentViewerScreen(
    documentId: String,
    onBack: () -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val vm = neriboViewModel(key = "viewer-$documentId") { c ->
        DocumentDetailViewModel(appContext, documentId, c.personalDocumentsRepository)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val document = state.document
    val path = document?.fileUri

    // Leave when the document is gone or has no file (for example it was just deleted).
    LaunchedEffect(state.isLoading, path) {
        if (!state.isLoading && path == null) onBack()
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = document?.title?.trim().orEmpty().ifEmpty { "Attachment" },
                actions = {
                    NeriboIconButton(
                        icon = Icons.Outlined.Close,
                        contentDescription = "Close viewer",
                        onClick = onBack,
                    )
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (path != null) {
                AttachmentViewer(path = path, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** Shows the file at [path]: a PDF in the page viewer, anything else as a zoomable photo. */
@Composable
fun AttachmentViewer(path: String, modifier: Modifier = Modifier) {
    val exists by rememberFileExists(path)
    when {
        exists == null -> Box(modifier = modifier)
        exists == false -> ViewerMessage(
            title = "File not found",
            message = "This attachment is no longer on the phone. Edit the document to attach it again.",
            modifier = modifier,
        )
        isPdfPath(path) -> PdfViewer(path = path, modifier = modifier)
        else -> ZoomableImage(path = path, modifier = modifier)
    }
}

@Composable
private fun ViewerMessage(title: String, message: String, modifier: Modifier = Modifier) {
    EmptyState(
        icon = Icons.Outlined.Description,
        title = title,
        message = message,
        modifier = modifier,
    )
}

/**
 * A photo with pinch-to-zoom, panning while zoomed and double-tap to zoom in and out.
 */
@Composable
private fun ZoomableImage(path: String, modifier: Modifier = Modifier) {
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
        modifier = modifier
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
                            event.changes.forEach { change ->
                                if (change.positionChanged()) change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        DocumentImage(
            path = path,
            contentDescription = "Attached photo. Pinch or double-tap to zoom.",
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

// ---------------------------------------------------------------------------------------------
// PDF
// ---------------------------------------------------------------------------------------------

private sealed interface PdfLoad {
    object Loading : PdfLoad
    class Ready(val session: PdfSession) : PdfLoad
    class Failed(val message: String) : PdfLoad
}

/**
 * An open PDF: the [PdfRenderer], its file descriptor, each page's shape and a small bitmap
 * cache. The renderer allows one open page at a time and is not thread-safe, so every render
 * takes [lock]. Closing waits for any render in progress, then frees the renderer, the
 * descriptor and the cached bitmaps.
 */
private class PdfSession(
    private val renderer: PdfRenderer,
    private val descriptor: ParcelFileDescriptor,
    /** Width divided by height for each page, so a page can be sized before it is drawn. */
    val aspectRatios: List<Float>,
) {
    private val lock = Mutex()
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var closed = false

    private val cache = object : LruCache<Long, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }

    val pageCount: Int get() = aspectRatios.size

    private fun keyOf(index: Int, widthPx: Int): Long = (index.toLong() shl 32) or widthPx.toLong()

    /** The page as a bitmap [widthPx] wide, or null if it could not be drawn. Safe to call from any thread. */
    suspend fun render(index: Int, widthPx: Int): Bitmap? {
        val key = keyOf(index, widthPx)
        synchronized(cache) { cache.get(key) }?.let { if (!it.isRecycled) return it }
        return lock.withLock {
            if (closed) return@withLock null
            synchronized(cache) { cache.get(key) }?.let { if (!it.isRecycled) return@withLock it }
            withContext(Dispatchers.Default) {
                try {
                    val page = renderer.openPage(index)
                    try {
                        val height = (widthPx * page.height / page.width.toFloat()).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(AndroidColor.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        synchronized(cache) { cache.put(key, bitmap) }
                        bitmap
                    } finally {
                        page.close()
                    }
                } catch (e: Exception) {
                    null
                } catch (e: OutOfMemoryError) {
                    null
                }
            }
        }
    }

    /** Frees everything once any render in progress has finished. Safe to call more than once. */
    fun close() {
        if (closed) return
        closed = true
        closeScope.launch {
            lock.withLock {
                runCatching { renderer.close() }
                runCatching { descriptor.close() }
                synchronized(cache) {
                    // The page list is already off the screen, so nothing is still drawing these.
                    cache.snapshot().values.forEach { if (!it.isRecycled) it.recycle() }
                    cache.evictAll()
                }
            }
        }
    }

    private companion object {
        /** Room for roughly a handful of full-width pages; older pages are redrawn on demand. */
        const val CACHE_BYTES = 40 * 1024 * 1024
    }
}

private const val MSG_PDF_UNREADABLE =
    "This PDF can't be opened. It may be damaged or protected with a password."

/** Opens [path] as a [PdfSession]; never throws. Must run off the main thread. */
private fun openPdf(path: String): PdfLoad {
    var descriptor: ParcelFileDescriptor? = null
    var renderer: PdfRenderer? = null
    return try {
        val opened = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        descriptor = opened
        val created = PdfRenderer(opened)
        renderer = created
        if (created.pageCount <= 0) {
            runCatching { created.close() }
            runCatching { opened.close() }
            PdfLoad.Failed(MSG_PDF_UNREADABLE)
        } else {
            val ratios = ArrayList<Float>(created.pageCount)
            for (index in 0 until created.pageCount) {
                val page = created.openPage(index)
                try {
                    ratios.add(if (page.height > 0) page.width / page.height.toFloat() else 0.7f)
                } finally {
                    page.close()
                }
            }
            PdfLoad.Ready(PdfSession(created, opened, ratios))
        }
    } catch (e: Exception) {
        // Encrypted PDFs raise SecurityException; damaged ones raise IOException or similar.
        runCatching { renderer?.close() }
        runCatching { descriptor?.close() }
        PdfLoad.Failed(MSG_PDF_UNREADABLE)
    } catch (e: OutOfMemoryError) {
        runCatching { renderer?.close() }
        runCatching { descriptor?.close() }
        PdfLoad.Failed(MSG_PDF_UNREADABLE)
    }
}

/** Opens the PDF at [path] and closes it again when the composable leaves the screen. */
@Composable
private fun rememberPdfLoad(path: String): State<PdfLoad> =
    produceState<PdfLoad>(initialValue = PdfLoad.Loading, path) {
        // Not cancellable: an opened renderer must always reach the close below, never be dropped.
        val loaded = withContext(Dispatchers.IO + NonCancellable) { openPdf(path) }
        value = loaded
        try {
            awaitCancellation()
        } finally {
            (loaded as? PdfLoad.Ready)?.session?.close()
        }
    }

/**
 * A vertically scrolling list of pages, each rendered to a bitmap on a background dispatcher at
 * screen width, with a small "2 of 5" counter. Encrypted or damaged files show a friendly
 * message instead of crashing.
 */
@Composable
private fun PdfViewer(path: String, modifier: Modifier = Modifier) {
    val load by rememberPdfLoad(path)
    when (val current = load) {
        PdfLoad.Loading -> Box(modifier = modifier)
        is PdfLoad.Failed -> ViewerMessage(
            title = "Can't open this PDF",
            message = current.message,
            modifier = modifier,
        )
        is PdfLoad.Ready -> PdfPages(session = current.session, modifier = modifier)
    }
}

@Composable
private fun PdfPages(session: PdfSession, modifier: Modifier = Modifier) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { (maxWidth - spacing.lg * 2).roundToPx() }
            .coerceIn(1, MAX_PAGE_WIDTH_PX)

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = spacing.lg,
                end = spacing.lg,
                top = spacing.sm,
                bottom = 72.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            items(count = session.pageCount, key = { it }) { index ->
                PdfPage(
                    session = session,
                    index = index,
                    widthPx = widthPx,
                    aspectRatio = session.aspectRatios[index],
                )
            }
        }

        val pill = MaterialTheme.shapes.small
        Text(
            text = "$currentPage of ${session.pageCount}",
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurface,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = spacing.lg)
                .clip(pill)
                .background(colors.surface.copy(alpha = 0.92f), pill)
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun PdfPage(session: PdfSession, index: Int, widthPx: Int, aspectRatio: Float) {
    val colors = MaterialTheme.colorScheme
    val bitmap by produceState<Bitmap?>(initialValue = null, session, index, widthPx) {
        value = session.render(index, widthPx)
    }
    val shape = MaterialTheme.shapes.small
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio.coerceIn(0.2f, 5f))
            .clip(shape)
            .background(colors.surfaceVariant, shape),
    ) {
        val ready = bitmap
        if (ready != null && !ready.isRecycled) {
            Image(
                bitmap = ready.asImageBitmap(),
                contentDescription = "Page ${index + 1} of ${session.pageCount}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillWidth,
            )
        }
    }
}
