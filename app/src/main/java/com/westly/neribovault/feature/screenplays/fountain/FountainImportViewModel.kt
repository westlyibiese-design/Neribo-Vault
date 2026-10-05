package com.westly.neribovault.feature.screenplays.fountain

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.entity.ScreenplayEntity
import com.westly.neribovault.data.repository.ScreenplaysRepository
import com.westly.neribovault.feature.screenplays.engine.Fountain
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_IMPORT_BYTES = 2 * 1024 * 1024
private const val READ_BUFFER_BYTES = 16 * 1024
private const val IMPORTED_FALLBACK_TITLE = "Imported screenplay"

/** What happened to one import. */
sealed interface FountainImportEvent {
    /** The new screenplay [id] is saved and ready to open. */
    data class Imported(val id: String, val title: String) : FountainImportEvent

    /** The file was too big, not text, or could not be read. */
    object Failed : FountainImportEvent
}

/** Turns a `.fountain` file the owner picked into a brand-new screenplay. Never overwrites anything. */
class FountainImportViewModel(private val repository: ScreenplaysRepository) : ViewModel() {

    private val eventChannel = Channel<FountainImportEvent>(Channel.BUFFERED)

    /** One event per import, for the list screen's snackbar and navigation. */
    val events: Flow<FountainImportEvent> = eventChannel.receiveAsFlow()

    /** Reads the file at [uri] and saves it as a new screenplay. */
    fun importFrom(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            val event = try {
                withContext(Dispatchers.IO) { importFile(resolver, uri) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FountainImportEvent.Failed
            }
            eventChannel.trySend(event)
        }
    }

    private suspend fun importFile(resolver: ContentResolver, uri: Uri): FountainImportEvent {
        val text = readText(resolver, uri) ?: return FountainImportEvent.Failed
        val document = FountainFile.read(text)
        val body = Fountain.serialize(Fountain.parse(document.body))
        val title = document.title.trim()
            .ifEmpty { fileBaseName(resolver, uri) }
            .ifEmpty { IMPORTED_FALLBACK_TITLE }
        val now = System.currentTimeMillis()
        val screenplay = ScreenplayEntity(
            id = newId(),
            createdAt = now,
            updatedAt = now,
            title = title,
            author = document.author.trim(),
            contact = document.contact.trim(),
            noticeEnabled = true,
            noticeText = if (document.copyright.isBlank()) "" else document.copyright,
            content = body,
        )
        repository.upsert(screenplay)
        return FountainImportEvent.Imported(screenplay.id, title)
    }

    /** The file as UTF-8 text, or null when it is over 2 MB, contains a NUL byte or cannot be read. */
    private fun readText(resolver: ContentResolver, uri: Uri): String? {
        try {
            val stream = resolver.openInputStream(uri) ?: return null
            val bytes = stream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(READ_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > MAX_IMPORT_BYTES) return null
                }
                out.toByteArray()
            }
            if (bytes.any { it.toInt() == 0 }) return null
            return String(bytes, Charsets.UTF_8)
        } catch (e: IOException) {
            return null
        } catch (e: SecurityException) {
            return null
        }
    }

    /** The picked file's name without its extension (and without a second `.fountain`), or "". */
    private fun fileBaseName(resolver: ContentResolver, uri: Uri): String {
        val name = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        } catch (e: Exception) {
            null
        }
        val trimmed = name.orEmpty().trim()
        val withoutExtension = trimmed.substringBeforeLast('.', trimmed)
        val withoutFountain = if (withoutExtension.endsWith(".fountain", ignoreCase = true)) {
            withoutExtension.dropLast(".fountain".length)
        } else {
            withoutExtension
        }
        return withoutFountain.trim()
    }
}
