package com.westly.neribovault.feature.screenplays.share

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import com.westly.neribovault.data.local.entity.ScreenplayEntity
import com.westly.neribovault.feature.screenplays.export.ScriptPdfWriter
import com.westly.neribovault.feature.screenplays.export.pdfFileName
import java.io.File
import java.io.IOException

private const val SHARE_TAG = "PdfShare"
private const val SHARED_FOLDER = "shared"
private const val MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L

/**
 * Writes [screenplay] as a PDF into `cacheDir/shared/` and opens the Android share sheet for it.
 * Call this from a background dispatcher: the file work happens on the calling thread and only the
 * share sheet itself is started on the main thread. Files older than 24 hours are deleted first.
 * Throws [IllegalStateException] ("Nothing to export") for an empty script, and other exceptions
 * when the file cannot be written; the caller shows the message.
 */
fun sharePdf(context: Context, screenplay: ScreenplayEntity) {
    val folder = File(context.cacheDir, SHARED_FOLDER)
    if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Could not create the share folder")

    val cutoff = System.currentTimeMillis() - MAX_AGE_MILLIS
    folder.listFiles()?.forEach { old ->
        if (old.isFile && old.lastModified() < cutoff) old.delete()
    }

    val file = File(folder, pdfFileName(screenplay.title))
    try {
        file.outputStream().buffered().use { ScriptPdfWriter.write(screenplay, it) }
    } catch (e: Exception) {
        file.delete()
        throw e
    }

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, screenplay.title.trim().ifEmpty { "Screenplay" })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, "Share screenplay")
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    Handler(Looper.getMainLooper()).post {
        try {
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.w(SHARE_TAG, "Could not open the share sheet", e)
        }
    }
}
