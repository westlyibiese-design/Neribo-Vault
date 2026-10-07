package com.westly.neribovault.feature.lyrics.sheet

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import com.westly.neribovault.data.local.entity.SongEntity
import com.westly.neribovault.feature.lyrics.engine.LyricsFormat
import java.io.File
import java.io.IOException

private const val SHARE_TAG = "LyricSheetShare"
private const val SHARED_FOLDER = "shared"
private const val MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L

/**
 * The song as plain text for copying or sharing: the title, then `by <writer>` when there is a
 * writer, a blank line and the lyrics in the Lyrics text format. Private notes are never included.
 */
fun lyricSheetText(song: SongEntity): String {
    val title = song.title.trim().ifEmpty { "Untitled song" }
    val writer = song.writer.trim()
    val lyrics = LyricsFormat.serialize(LyricsFormat.parse(song.content))
    return buildString {
        append(title).append('\n')
        if (writer.isNotEmpty()) append("by ").append(writer).append('\n')
        append('\n')
        append(lyrics)
    }
}

/** Shares the lyric sheet as a PDF through the Android share sheet. */
object LyricSheetShare {

    /**
     * Writes [song] as a PDF into `cacheDir/shared/` and opens the share sheet for it. Call this from
     * a background dispatcher: the file work happens on the calling thread and only the share sheet
     * itself is started on the main thread. Files older than 24 hours are deleted first. Throws
     * [IllegalStateException] ("Nothing to export") for a song with no lines, and other exceptions
     * when the file cannot be written; the caller shows the message.
     */
    fun sharePdf(context: Context, song: SongEntity, options: LyricSheetOptions) {
        val folder = File(context.cacheDir, SHARED_FOLDER)
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Could not create the share folder")

        val cutoff = System.currentTimeMillis() - MAX_AGE_MILLIS
        folder.listFiles()?.forEach { old ->
            if (old.isFile && old.lastModified() < cutoff) old.delete()
        }

        val file = File(folder, pdfFileName(song.title))
        try {
            file.outputStream().buffered().use { LyricSheetPdfWriter.write(song, options, it) }
        } catch (e: Exception) {
            file.delete()
            throw e
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, song.title.trim().ifEmpty { "Lyrics" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share lyric sheet")
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Handler(Looper.getMainLooper()).post {
            try {
                context.startActivity(chooser)
            } catch (e: Exception) {
                Log.w(SHARE_TAG, "Could not open the share sheet", e)
            }
        }
    }
}
