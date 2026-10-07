package com.westly.neribovault.feature.lyrics.sheet

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.SongEntity
import com.westly.neribovault.data.repository.SongsRepository
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val GENERATE_DEBOUNCE_MS = 500L
private const val CACHE_KILOBYTES = 24 * 1024
private const val NOTHING_TO_EXPORT = "Nothing to export"

/** What the Lyric sheet screen is showing. */
enum class SheetPhase { Loading, Ready, Empty, Error }

/** [pageCount] counts every PDF page; [generation] changes each time new pages are ready. */
data class LyricSheetUiState(
    val phase: SheetPhase = SheetPhase.Loading,
    val title: String = "",
    val pageCount: Int = 0,
    val generation: Int = 0,
    val isSaving: Boolean = false,
)

private data class SheetRequest(val song: SongEntity?, val options: LyricSheetOptions, val retry: Int)

/**
 * Builds the preview PDF in the cache folder, renders its pages lazily to bitmaps, saves the PDF to
 * a file the owner picks and shares it. [previewDir] is `cacheDir/preview`.
 */
class LyricSheetViewModel(
    private val repository: SongsRepository,
    private val songId: String,
    private val previewDir: File,
) : ViewModel() {

    private val _state = MutableStateFlow(LyricSheetUiState())
    val state: StateFlow<LyricSheetUiState> = _state.asStateFlow()

    private val messageChannel = Channel<String>(Channel.BUFFERED)

    /** One-off snackbar messages ("Saved: 4 pages"). */
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    private val options = MutableStateFlow(LyricSheetOptions())
    private val retryTick = MutableStateFlow(0)
    private val fileLock = Mutex()

    @Volatile
    private var renderer: LyricPdfRenderer? = null

    private val pageCache = object : LruCache<String, Bitmap>(CACHE_KILOBYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    init {
        viewModelScope.launch { watchSong() }
    }

    /** Sets the print options; a new preview is built after a short pause. */
    fun setOptions(newOptions: LyricSheetOptions) {
        options.value = newOptions
    }

    fun retry() {
        retryTick.update { it + 1 }
    }

    @OptIn(FlowPreview::class)
    private suspend fun watchSong() {
        val songs = repository.observeById(songId).distinctUntilChanged()
        combine(songs, options, retryTick) { song, opts, tick -> SheetRequest(song, opts, tick) }
            .debounce(GENERATE_DEBOUNCE_MS)
            .collectLatest { request -> generate(request.song, request.options) }
    }

    private suspend fun generate(song: SongEntity?, sheetOptions: LyricSheetOptions) {
        pageCache.evictAll()
        if (song == null || song.isDeleted) {
            releaseRenderer()
            _state.update { it.copy(phase = SheetPhase.Empty, pageCount = 0) }
            return
        }
        _state.update { it.copy(phase = SheetPhase.Loading, title = song.title) }
        try {
            val pageCount = withContext(Dispatchers.IO) {
                fileLock.withLock {
                    releaseRenderer()
                    previewDir.mkdirs()
                    val file = File(previewDir, "song-$songId.pdf")
                    file.outputStream().buffered().use { LyricSheetPdfWriter.write(song, sheetOptions, it) }
                    ensureActive()
                    val opened = LyricPdfRenderer.open(file)
                    renderer = opened
                    opened.pageCount
                }
            }
            _state.update {
                it.copy(phase = SheetPhase.Ready, pageCount = pageCount, generation = it.generation + 1)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            if (e.message == NOTHING_TO_EXPORT) {
                _state.update { it.copy(phase = SheetPhase.Empty, pageCount = 0) }
            } else {
                _state.update { it.copy(phase = SheetPhase.Error, pageCount = 0) }
            }
        } catch (e: Exception) {
            _state.update { it.copy(phase = SheetPhase.Error, pageCount = 0) }
        }
    }

    /** The bitmap for page [index] (0-based) at [widthPx], from the cache or freshly rendered. */
    suspend fun loadPage(index: Int, widthPx: Int): Bitmap? {
        val key = "${_state.value.generation}-$widthPx-$index"
        pageCache.get(key)?.let { return it }
        val current = renderer ?: return null
        val rendered: Bitmap? = try {
            withContext(Dispatchers.IO) { current.renderPage(index, widthPx) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            pageCache.evictAll()
            null
        }
        val bitmap = rendered ?: return null
        pageCache.put(key, bitmap)
        return bitmap
    }

    /** Writes the latest saved version of the song to [uri], then reports the result. */
    fun savePdf(resolver: ContentResolver, uri: Uri) {
        if (_state.value.isSaving) return
        _state.update { it.copy(isSaving = true) }
        val sheetOptions = options.value
        viewModelScope.launch {
            val message = try {
                val pages = withContext(Dispatchers.IO) {
                    val song = repository.getById(songId) ?: throw IllegalStateException(NOTHING_TO_EXPORT)
                    val stream = resolver.openOutputStream(uri, "wt") ?: throw IOException("Could not open the file")
                    stream.buffered().use { LyricSheetPdfWriter.write(song, sheetOptions, it) }
                }
                if (pages == 1) "Saved: 1 page" else "Saved: $pages pages"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Could not save the PDF. Please try again."
            }
            _state.update { it.copy(isSaving = false) }
            messageChannel.trySend(message)
        }
    }

    /** Builds the PDF in the share folder and opens the Android share sheet. */
    fun sharePdf(context: Context) {
        val sheetOptions = options.value
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val song = repository.getById(songId) ?: throw IllegalStateException(NOTHING_TO_EXPORT)
                    LyricSheetShare.sharePdf(context, song, sheetOptions)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messageChannel.trySend("Could not share the PDF.")
            }
        }
    }

    /** The latest saved version of the song as plain text, or null when the song is gone. */
    suspend fun plainText(): String? = repository.getById(songId)?.let { lyricSheetText(it) }

    private fun releaseRenderer() {
        val old = renderer
        renderer = null
        if (old != null) {
            try {
                old.close()
            } catch (e: Exception) {
                // Already closed or the file is gone; nothing more to release.
            }
        }
    }

    override fun onCleared() {
        releaseRenderer()
        pageCache.evictAll()
        super.onCleared()
    }
}
