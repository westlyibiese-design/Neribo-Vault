package com.westly.neribovault.feature.screenplays.share

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.repository.ScreenplaysRepository
import com.westly.neribovault.feature.screenplays.fountain.FountainFile
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val NOTHING_TO_EXPORT = "Nothing to export"

/**
 * The two extra Preview actions: share the PDF through the Android share sheet and save the
 * script as a `.fountain` file. Both use the latest saved version of the screenplay.
 */
class ScriptShareViewModel(
    private val repository: ScreenplaysRepository,
    private val screenplayId: String,
) : ViewModel() {

    private val messageChannel = Channel<String>(Channel.BUFFERED)

    /** One-off snackbar messages. */
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    /** Builds the PDF in the cache folder and opens the share sheet. */
    fun startShare(context: Context) {
        viewModelScope.launch {
            val failure = try {
                withContext(Dispatchers.IO) {
                    val entity = repository.getById(screenplayId) ?: throw IllegalStateException(NOTHING_TO_EXPORT)
                    sharePdf(context, entity)
                }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                if (e.message == NOTHING_TO_EXPORT) {
                    "Nothing to share yet. Write a scene first, then come back."
                } else {
                    "Could not share the PDF."
                }
            } catch (e: Exception) {
                "Could not share the PDF."
            }
            if (failure != null) messageChannel.trySend(failure)
        }
    }

    /** Writes the screenplay as `.fountain` text (UTF-8) to the file the owner picked at [uri]. */
    fun exportFountain(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            val message = try {
                withContext(Dispatchers.IO) {
                    val entity = repository.getById(screenplayId) ?: throw IllegalStateException(NOTHING_TO_EXPORT)
                    val stream = resolver.openOutputStream(uri, "wt") ?: throw IOException("Could not open the file")
                    stream.use { it.write(FountainFile.write(entity).toByteArray(Charsets.UTF_8)) }
                }
                "Saved .fountain file. If your phone adds .txt to the name, rename it to .fountain."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Could not save the .fountain file. Please try again."
            }
            messageChannel.trySend(message)
        }
    }
}
