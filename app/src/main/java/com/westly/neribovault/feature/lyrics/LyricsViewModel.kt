package com.westly.neribovault.feature.lyrics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.entity.SongEntity
import com.westly.neribovault.data.repository.SongsRepository
import com.westly.neribovault.feature.lyrics.engine.CheckResult
import com.westly.neribovault.feature.lyrics.engine.LyricsAnalysis
import com.westly.neribovault.feature.lyrics.engine.LyricsFormat
import com.westly.neribovault.feature.lyrics.engine.LyricsSamples
import com.westly.neribovault.feature.lyrics.engine.runLyricsSelfTest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The title shown (and stored for a new song) when the owner leaves the title blank. */
const val UNTITLED_SONG = "Untitled song"

/** Stored status values. */
const val STATUS_IDEA = "idea"
const val STATUS_DRAFT = "draft"
const val STATUS_FINISHED = "finished"

/** Section and line totals of a song. */
data class SongCounts(val sections: Int, val lines: Int)

/** "1 section", "2 sections", "0 lines". */
fun countLabel(count: Int, singular: String): String =
    if (count == 1) "1 $singular" else "$count ${singular}s"

/** The quiet line on a card: "8 sections \u00B7 23 lines", or "Empty" when the song has no lines. */
fun sectionsAndLinesLabel(counts: SongCounts): String =
    if (counts.lines == 0) {
        "Empty"
    } else {
        countLabel(counts.sections, "section") + " \u00B7 " + countLabel(counts.lines, "line")
    }

/** The status as shown on a badge. */
fun statusLabel(status: String): String = when (status) {
    STATUS_FINISHED -> "Finished"
    STATUS_DRAFT -> "Draft"
    else -> "Idea"
}

/** One row of the list: the song and its (cached) counts. */
data class SongListItem(val song: SongEntity, val counts: SongCounts)

/** Which sample the debug menu adds. */
enum class DebugSample(val title: String) {
    A("Sample A: Lagos Lights"),
    B("Sample B: Long sample"),
    C("Sample C: Outlier test"),
}

/** Everything the Lyrics list screen draws. [statusFilter] is "" for All, otherwise a stored status. */
data class LyricsUiState(
    val query: String = "",
    val isSearchOpen: Boolean = false,
    val statusFilter: String = "",
    val items: List<SongListItem> = emptyList(),
    val hasAny: Boolean = false,
    val defaultWriter: String = "",
    val selfTest: List<CheckResult>? = null,
    val isLoading: Boolean = true,
)

/** State and actions for the Lyrics list. */
class LyricsViewModel(private val repository: SongsRepository) : ViewModel() {

    private val queryFlow = MutableStateFlow("")
    private val searchOpenFlow = MutableStateFlow(false)
    private val statusFlow = MutableStateFlow("")
    private val selfTestFlow = MutableStateFlow<List<CheckResult>?>(null)

    /** Counts by `id:updatedAt`, so scrolling and re-filtering never parse a song again. */
    private val countsCache = ConcurrentHashMap<String, SongCounts>()

    private val allItems: Flow<List<SongListItem>> = repository.observeAll()
        .map { list -> list.map { song -> SongListItem(song, countsFor(song)) } }
        .flowOn(Dispatchers.Default)

    private val filters: Flow<Pair<String, String>> = combine(queryFlow, statusFlow) { query, status ->
        query to status
    }

    val state: StateFlow<LyricsUiState> = combine(
        allItems,
        filters,
        searchOpenFlow,
        selfTestFlow,
    ) { items, filter, searchOpen, selfTest ->
        val (query, status) = filter
        val needle = query.trim()
        val shown = items.filter { item ->
            val matchesStatus = status.isEmpty() || item.song.status == status
            val matchesQuery = needle.isEmpty() ||
                item.song.title.contains(needle, ignoreCase = true) ||
                item.song.writer.contains(needle, ignoreCase = true)
            matchesStatus && matchesQuery
        }
        LyricsUiState(
            query = query,
            isSearchOpen = searchOpen,
            statusFilter = status,
            items = shown,
            hasAny = items.isNotEmpty(),
            defaultWriter = items.firstOrNull()?.song?.writer.orEmpty(),
            selfTest = selfTest,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LyricsUiState())

    private fun countsFor(song: SongEntity): SongCounts {
        if (countsCache.size > 300) countsCache.clear()
        val key = song.id + ":" + song.updatedAt
        return countsCache.getOrPut(key) {
            val stats = LyricsAnalysis.stats(LyricsFormat.parse(song.content))
            SongCounts(stats.sections, stats.lines)
        }
    }

    fun onQueryChange(value: String) {
        queryFlow.value = value
    }

    fun openSearch() {
        searchOpenFlow.value = true
    }

    fun closeSearch() {
        searchOpenFlow.value = false
        queryFlow.value = ""
    }

    fun onStatusFilterChange(status: String) {
        statusFlow.value = status
    }

    /** Creates an empty song and calls [onCreated] with its id once it is saved. */
    fun create(title: String, writer: String, onCreated: (String) -> Unit) {
        val now = System.currentTimeMillis()
        val song = SongEntity(
            id = newId(),
            createdAt = now,
            updatedAt = now,
            title = title.trim().ifEmpty { UNTITLED_SONG },
            writer = writer.trim(),
            songKey = "",
            tempoBpm = null,
            mood = "",
            status = STATUS_IDEA,
            notes = "",
            content = "",
        )
        viewModelScope.launch {
            repository.upsert(song)
            onCreated(song.id)
        }
    }

    fun updateDetails(id: String, title: String, writer: String) {
        viewModelScope.launch {
            val existing = repository.getById(id) ?: return@launch
            repository.upsert(existing.copy(title = title.trim(), writer = writer.trim()))
        }
    }

    fun duplicate(id: String) {
        viewModelScope.launch {
            val source = repository.getById(id) ?: return@launch
            val now = System.currentTimeMillis()
            repository.upsert(
                source.copy(
                    id = newId(),
                    createdAt = now,
                    updatedAt = now,
                    isDeleted = false,
                    deletedAt = null,
                    title = "Copy of " + source.title.ifBlank { UNTITLED_SONG },
                ),
            )
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.softDelete(id) }
    }

    fun restore(id: String) {
        viewModelScope.launch { repository.restore(id) }
    }

    /** Debug menu: adds one of the three sample songs as a new song. */
    fun addSample(sample: DebugSample) {
        val now = System.currentTimeMillis()
        val content = when (sample) {
            DebugSample.A -> LyricsSamples.sampleA
            DebugSample.B -> LyricsSamples.sampleB
            DebugSample.C -> LyricsSamples.sampleC
        }
        viewModelScope.launch {
            repository.upsert(
                SongEntity(
                    id = newId(),
                    createdAt = now,
                    updatedAt = now,
                    title = sample.title,
                    writer = "Neribo",
                    songKey = "",
                    tempoBpm = null,
                    mood = "",
                    status = STATUS_DRAFT,
                    notes = "",
                    content = content,
                ),
            )
        }
    }

    /** Debug menu: runs the engine checks off the main thread and shows the result. */
    fun runSelfTest() {
        viewModelScope.launch(Dispatchers.Default) {
            selfTestFlow.value = runLyricsSelfTest()
        }
    }

    fun dismissSelfTest() {
        selfTestFlow.value = null
    }
}
