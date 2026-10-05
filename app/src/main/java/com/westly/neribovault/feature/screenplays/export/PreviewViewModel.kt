package com.westly.neribovault.feature.screenplays.export

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.local.entity.ScreenplayEntity
import com.westly.neribovault.data.repository.ScreenplaysRepository
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
import kotlinx.coroutines.withContext

private const val GENERATE_DEBOUNCE_MS = 500L
private const val CACHE_KILOBYTES = 24 * 1024

/** What the Preview screen is showing. */
enum class PreviewPhase { Loading, Ready, Empty, Error }

/** [pageCount] counts every PDF page; [generation] changes each time new pages are ready. */
data class PreviewUiState(
    val phase: PreviewPhase = PreviewPhase.Loading,
    val title: String = "",
    val pageCount: Int = 0,
    val generation: Int = 0,
    val isSaving: Boolean = false,
)

/**
 * Builds the preview PDF in the cache folder, renders its pages lazily to bitmaps and saves the
 * PDF to a file the owner picks. [previewDir] is `cacheDir/preview`.
 */
class PreviewViewModel(
    private val repository: ScreenplaysRepository,
    private val screenplayId: String,
    private val previewDir: File,
) : ViewModel() {

    private val _state = MutableStateFlow(PreviewUiState())
    val state: StateFlow<PreviewUiState> = _state.asStateFlow()

    private val messageChannel = Channel<String>(Channel.BUFFERED)

    /** One-off snackbar messages ("Saved: 4 pages"). */
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    private val retryTick = MutableStateFlow(0)

    @Volatile
    private var renderer: PdfPageRenderer? = null

    private val pageCache = object : LruCache<String, Bitmap>(CACHE_KILOBYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    init {
        viewModelScope.launch { watchScreenplay() }
    }

    @OptIn(FlowPreview::class)
    private suspend fun watchScreenplay() {
        val entities = repository.observeById(screenplayId).distinctUntilChanged().debounce(GENERATE_DEBOUNCE_MS)
        combine(entities, retryTick) { entity, _ -> entity }.collectLatest { entity -> generate(entity) }
    }

    fun retry() {
        retryTick.update { it + 1 }
    }

    private suspend fun generate(entity: ScreenplayEntity?) {
        releaseRenderer()
        pageCache.evictAll()
        if (entity == null) {
            _state.update { it.copy(phase = PreviewPhase.Empty, pageCount = 0) }
            return
        }
        _state.update { it.copy(phase = PreviewPhase.Loading, title = entity.title) }
        try {
            val pageCount = withContext(Dispatchers.IO) {
                previewDir.mkdirs()
                val file = File(previewDir, "$screenplayId.pdf")
                file.outputStream().buffered().use { ScriptPdfWriter.write(entity, it) }
                ensureActive()
                val opened = PdfPageRenderer.open(file)
                renderer = opened
                opened.pageCount
            }
            _state.update {
                it.copy(phase = PreviewPhase.Ready, pageCount = pageCount, generation = it.generation + 1)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            if (e.message == "Nothing to export") {
                _state.update { it.copy(phase = PreviewPhase.Empty, pageCount = 0) }
            } else {
                _state.update { it.copy(phase = PreviewPhase.Error, pageCount = 0) }
            }
        } catch (e: Exception) {
            _state.update { it.copy(phase = PreviewPhase.Error, pageCount = 0) }
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

    /** Writes the latest saved version of the screenplay to [uri], then reports the result. */
    fun savePdf(resolver: ContentResolver, uri: Uri) {
        if (_state.value.isSaving) return
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val message = try {
                val summary = withContext(Dispatchers.IO) {
                    val entity = repository.getById(screenplayId)
                        ?: throw IllegalStateException("Nothing to export")
                    val stream = resolver.openOutputStream(uri, "wt")
                        ?: throw IOException("Could not open the file")
                    stream.buffered().use { ScriptPdfWriter.write(entity, it) }
                }
                "Saved: ${summary.totalPages} pages"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Could not save the PDF. Please try again."
            }
            _state.update { it.copy(isSaving = false) }
            messageChannel.trySend(message)
        }
    }

    private fun releaseRenderer() {
        val old = renderer
        renderer = null
        if (old != null) {
            try {
                old.close()
            } catch (e: Exception) {
                // Already closed or file gone; nothing more to release.
            }
        }
    }

    override fun onCleared() {
        releaseRenderer()
        pageCache.evictAll()
        super.onCleared()
    }
}
