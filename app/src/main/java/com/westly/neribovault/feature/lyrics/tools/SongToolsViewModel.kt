package com.westly.neribovault.feature.lyrics.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.util.snippet
import com.westly.neribovault.data.local.entity.SongEntity
import com.westly.neribovault.data.repository.SongsRepository
import com.westly.neribovault.feature.lyrics.engine.LyricsAnalysis
import com.westly.neribovault.feature.lyrics.engine.LyricsFormat
import com.westly.neribovault.feature.lyrics.engine.LyricsStats
import com.westly.neribovault.feature.lyrics.engine.SongSection
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val STRUCTURE_SNIPPET_CHARS = 60

/** One row of the Structure tab. [index] is the position in `LyricsFormat.parse(content)`. */
data class StructureRow(
    val index: Int,
    val label: String,
    val lineCount: Int,
    val preview: String,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
)

/** One lyric line with its estimated syllable count. */
data class SyllableLine(val text: String, val count: Int, val isOutlier: Boolean)

/** One section of the Syllables tab. Sections with no lines are never created. */
data class SyllableSection(val label: String, val average: String, val lines: List<SyllableLine>)

/** Everything the Song tools screen shows. [isEmpty] is true when the song has no sections. */
data class SongToolsUiState(
    val isLoading: Boolean = true,
    val isEmpty: Boolean = false,
    val stats: LyricsStats? = null,
    val mostRepeated: Pair<String, Int>? = null,
    val updatedAt: Long = 0L,
    val rows: List<StructureRow> = emptyList(),
    val syllableSections: List<SyllableSection> = emptyList(),
)

/** One-off events for the screen. */
sealed interface SongToolsEvent {
    /** A section was deleted; the screen shows "Section deleted" with Undo. */
    data object SectionDeleted : SongToolsEvent

    /** Something could not be saved. */
    data object SaveFailed : SongToolsEvent
}

/**
 * Measures a song and applies the structure operations (move, duplicate, delete). The numbers are
 * computed off the main thread whenever the song changes. Changes to the lyrics always go through
 * parse, a pure [SongOps] function, serialize and a save of the latest entity.
 */
class SongToolsViewModel(
    private val repository: SongsRepository,
    private val songId: String,
) : ViewModel() {

    val state: StateFlow<SongToolsUiState> = repository.observeById(songId)
        .map { song -> buildState(song) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SongToolsUiState())

    private val eventChannel = Channel<SongToolsEvent>(Channel.BUFFERED)

    /** One-off events (snackbars). */
    val events: Flow<SongToolsEvent> = eventChannel.receiveAsFlow()

    private val saveLock = Mutex()

    /** The exact content before the last delete, kept in memory only for this screen visit. */
    private var contentBeforeDelete: String? = null

    fun moveUp(index: Int) = change(index) { sections -> SongOps.moveUp(sections, index) }

    fun moveDown(index: Int) = change(index) { sections -> SongOps.moveDown(sections, index) }

    fun duplicate(index: Int) = change(index) { sections -> SongOps.duplicate(sections, index) }

    fun delete(index: Int) {
        viewModelScope.launch {
            saveLock.withLock {
                val entity = repository.getById(songId) ?: return@launch
                val sections = LyricsFormat.parse(entity.content)
                if (index !in sections.indices) return@launch
                val updated = SongOps.delete(sections, index)
                if (updated == sections) return@launch
                if (save(entity, LyricsFormat.serialize(updated))) {
                    contentBeforeDelete = entity.content
                    eventChannel.trySend(SongToolsEvent.SectionDeleted)
                }
            }
        }
    }

    /** Puts back the exact content from before the last delete. */
    fun undoDelete() {
        val previous = contentBeforeDelete ?: return
        viewModelScope.launch {
            saveLock.withLock {
                val entity = repository.getById(songId) ?: return@launch
                if (save(entity, previous)) contentBeforeDelete = null
            }
        }
    }

    private fun change(index: Int, operation: (List<SongSection>) -> List<SongSection>) {
        viewModelScope.launch {
            saveLock.withLock {
                val entity = repository.getById(songId) ?: return@launch
                val sections = LyricsFormat.parse(entity.content)
                if (index !in sections.indices) return@launch
                val updated = operation(sections)
                if (updated == sections) return@launch
                save(entity, LyricsFormat.serialize(updated))
            }
        }
    }

    private suspend fun save(entity: SongEntity, content: String): Boolean {
        return try {
            repository.upsert(entity.copy(content = content))
            true
        } catch (e: Exception) {
            eventChannel.trySend(SongToolsEvent.SaveFailed)
            false
        }
    }

    private fun buildState(song: SongEntity?): SongToolsUiState {
        if (song == null) return SongToolsUiState(isLoading = false, isEmpty = true)
        val sections = LyricsFormat.parse(song.content)
        if (sections.isEmpty()) {
            return SongToolsUiState(isLoading = false, isEmpty = true, updatedAt = song.updatedAt)
        }
        val counts = LyricsAnalysis.lineSyllables(sections)
        val rows = ArrayList<StructureRow>()
        val syllableSections = ArrayList<SyllableSection>()
        sections.forEachIndexed { index, section ->
            val label = LyricsFormat.labelAt(sections, index)
            val lines = section.text.split('\n').filter { it.isNotBlank() }
            rows.add(
                StructureRow(
                    index = index,
                    label = label,
                    lineCount = lines.size,
                    preview = lines.firstOrNull()?.let { snippet(it, STRUCTURE_SNIPPET_CHARS) }.orEmpty(),
                    canMoveUp = index > 0,
                    canMoveDown = index < sections.lastIndex,
                ),
            )
            val lineCounts = counts[index]
            if (lines.isNotEmpty() && lineCounts.size == lines.size) {
                val outliers = LyricsAnalysis.outlierLines(lineCounts)
                val average = lineCounts.sum().toDouble() / lineCounts.size
                syllableSections.add(
                    SyllableSection(
                        label = label,
                        average = String.format(Locale.US, "%.1f", average),
                        lines = lines.mapIndexed { lineIndex, text ->
                            SyllableLine(text.trim(), lineCounts[lineIndex], lineIndex in outliers)
                        },
                    ),
                )
            }
        }
        return SongToolsUiState(
            isLoading = false,
            isEmpty = false,
            stats = LyricsAnalysis.stats(sections),
            mostRepeated = LyricsAnalysis.mostRepeatedLine(sections),
            updatedAt = song.updatedAt,
            rows = rows,
            syllableSections = syllableSections,
        )
    }
}
