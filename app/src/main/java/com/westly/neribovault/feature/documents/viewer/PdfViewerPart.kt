package com.westly.neribovault.feature.documents.viewer

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import com.westly.neribovault.core.design.NeriboTheme
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val CACHED_PAGES = 4
private const val MAX_PAGE_SIDE_PX = 2600
private const val PAGE_ASPECT_RATIO = 1f / 1.414f

/**
 * Draws the pages of one PDF with the framework [PdfRenderer].
 *
 * `PdfRenderer` allows only one open page at a time, so every render runs inside one [Mutex] and
 * closes its page straight away. A small cache keeps the last few page bitmaps. Bitmaps are never
 * recycled (one may still be on screen); dropping the reference is enough.
 *
 * [close] closes the renderer and the file descriptor and deletes [tempFile] (the decrypted copy
 * of an encrypted PDF), after taking the same mutex so a render in progress is never cut off.
 * Calling it twice is harmless.
 */
class PdfPageSource private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    private val tempFile: File?,
    val pageCount: Int,
) {
    private val mutex = Mutex()
    private val cache = LruCache<Int, Bitmap>(CACHED_PAGES)
    private var cachedWidth = 0
    private var closed = false

    /**
     * Page [index] drawn [widthPx] wide on a white background, or null when the source is closed or
     * the index is out of range. Runs off the main thread. May throw if the page cannot be drawn.
     */
    suspend fun renderPage(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.Default) {
        mutex.withLock { renderLocked(index, widthPx) }
    }

    private fun renderLocked(index: Int, widthPx: Int): Bitmap? {
        if (closed || index < 0 || index >= pageCount) return null
        if (widthPx != cachedWidth) {
            cache.evictAll()
            cachedWidth = widthPx
        }
        val cached = cache.get(index)
        if (cached != null) return cached

        val page = renderer.openPage(index)
        try {
            val size = scaledPageSize(page.width, page.height, widthPx, MAX_PAGE_SIDE_PX)
            val bitmap = Bitmap.createBitmap(size.first, size.second, Bitmap.Config.ARGB_8888)
            // The renderer draws on a transparent bitmap, so paint the paper first.
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            cache.put(index, bitmap)
            return bitmap
        } finally {
            page.close()
        }
    }

    /** Closes everything and deletes the temporary decrypted copy. Safe to call more than once. */
    suspend fun close() {
        mutex.withLock {
            if (closed) return@withLock
            closed = true
            cache.evictAll()
            try {
                renderer.close()
            } catch (ignored: Exception) {
                // Already closed or broken; nothing more to release.
            }
            try {
                descriptor.close()
            } catch (ignored: Exception) {
                // Same.
            }
            if (tempFile != null) {
                try {
                    tempFile.delete()
                } catch (ignored: Exception) {
                    // A leftover file is removed the next time the viewer opens.
                }
            }
        }
    }

    companion object {
        /**
         * Opens [file] (a seekable PDF). [tempFile], when given, is deleted by [close]. Throws
         * `SecurityException` (password protected), `IOException` (damaged) or
         * `IllegalArgumentException` (no pages); nothing stays open when it throws.
         */
        fun open(file: File, tempFile: File?): PdfPageSource {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            var renderer: PdfRenderer? = null
            try {
                val opened = PdfRenderer(descriptor)
                renderer = opened
                val count = opened.pageCount
                if (count <= 0) throw IllegalArgumentException("The PDF has no pages")
                return PdfPageSource(descriptor, opened, tempFile, count)
            } catch (t: Throwable) {
                runCatching { renderer?.close() }
                runCatching { descriptor.close() }
                throw t
            }
        }
    }
}

/**
 * The pages of a PDF one under another, white on the paper background, with a muted note at the
 * end. [listState] belongs to the screen, which reads it for the "Page X of N" line.
 */
@Composable
fun ViewerPdfContent(
    source: PdfPageSource,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val spacing = NeriboTheme.spacing
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val widthPx = with(density) { (maxWidth - spacing.screen * 2).roundToPx() }.coerceAtLeast(1)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = spacing.screen,
                end = spacing.screen,
                top = spacing.sm,
                bottom = spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            items(count = source.pageCount) { index ->
                ViewerPdfPage(source = source, index = index, widthPx = widthPx)
            }
            item {
                Text(
                    text = "Text copy isn't available for PDF files yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = spacing.md),
                )
            }
        }
    }
}

/** One page: a light A4-shaped box until the page is drawn, then the page itself. */
@Composable
private fun ViewerPdfPage(source: PdfPageSource, index: Int, widthPx: Int) {
    var bitmap by remember(source, index, widthPx) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(source, index, widthPx) { mutableStateOf(false) }

    LaunchedEffect(source, index, widthPx) {
        try {
            val rendered = source.renderPage(index, widthPx)
            if (rendered == null) failed = true else bitmap = rendered
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Includes OutOfMemoryError: one bad page must never crash the viewer.
            failed = true
        }
    }

    val drawn = bitmap
    if (drawn != null) {
        Image(
            bitmap = drawn.asImageBitmap(),
            contentDescription = "Page ${index + 1}",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White),
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(PAGE_ASPECT_RATIO)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (failed) {
                Text(
                    text = "Couldn't show this page.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
