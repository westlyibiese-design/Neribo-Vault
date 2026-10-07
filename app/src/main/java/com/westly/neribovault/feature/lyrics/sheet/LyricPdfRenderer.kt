package com.westly.neribovault.feature.lyrics.sheet

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Renders pages of a PDF file to bitmaps with [PdfRenderer]. The renderer allows one open page at a
 * time, so every call is serialized. Call [close] when done; later calls then return null.
 */
internal class LyricPdfRenderer private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) {
    private val lock = Any()
    private var closed = false

    val pageCount: Int = renderer.pageCount

    /** A white ARGB_8888 bitmap [widthPx] wide with the A4 page shape (595 : 842), or null if unavailable. */
    fun renderPage(index: Int, widthPx: Int): Bitmap? {
        synchronized(lock) {
            if (closed || index < 0 || index >= pageCount) return null
            val width = widthPx.coerceAtLeast(1)
            val height = Math.round(width * 842f / 595f).coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            val page = renderer.openPage(index)
            try {
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            } finally {
                page.close()
            }
            return bitmap
        }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            try {
                renderer.close()
            } finally {
                descriptor.close()
            }
        }
    }

    companion object {
        /** Opens [file] for rendering. Throws if the file is not a readable PDF. */
        fun open(file: File): LyricPdfRenderer {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                return LyricPdfRenderer(descriptor, PdfRenderer(descriptor))
            } catch (e: Exception) {
                descriptor.close()
                throw e
            }
        }
    }
}
