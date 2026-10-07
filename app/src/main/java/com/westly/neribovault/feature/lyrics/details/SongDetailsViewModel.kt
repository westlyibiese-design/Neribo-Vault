package com.westly.neribovault.feature.lyrics.details

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.data.repository.SongsRepository
import com.westly.neribovault.feature.lyrics.STATUS_IDEA
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Everything the Song details screen draws. [tapBpm] is the tempo detected by the tap button. */
data class SongDetailsUiState(
    val isLoading: Boolean = true,
    val notFound: Boolean = false,
    val title: String = "",
    val writer: String = "",
    val songKey: String = "",
    val tempoText: String = "",
    val tempoError: Boolean = false,
    val tapBpm: Int? = null,
    val mood: String = "",
    val status: String = STATUS_IDEA,
    val notes: String = "",
    val isSaving: Boolean = false,
)

private val MODE_SUFFIX = Regex("(?i)(?:^|\\s)(major|minor)\\s*$")

/** "major" or "minor" when [key] ends with that word, else null. */
internal fun keyMode(key: String): String? = MODE_SUFFIX.find(key)?.groupValues?.get(1)?.lowercase()

/** [key] without a trailing "major" or "minor" word. */
internal fun keyWithoutMode(key: String): String = key.replace(MODE_SUFFIX, "").trim()

/** Sets the note of [key] to [note], keeping a trailing "major" or "minor". */
internal fun keyWithNote(key: String, note: String): String {
    val mode = keyMode(key)
    return if (mode != null) "$note $mode" else note
}

/** Appends [mode] ("major" or "minor") to [key], replacing a mode already there. */
internal fun keyWithMode(key: String, mode: String): String {
    val base = keyWithoutMode(key)
    return if (base.isEmpty()) mode else "$base $mode"
}

/** State and actions of the Song details screen. Editing here never touches the lyrics. */
class SongDetailsViewModel(
    private val repository: SongsRepository,
    private val songId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(SongDetailsUiState())
    val state: StateFlow<SongDetailsUiState> = _state.asStateFlow()

    private val tapper = TempoTapper()
    private var validTempo: Int? = null
    private var loaded = false
    private var saveJob: Job? = null
    private val saveMutex = Mutex()
    private var editVersion = 0L

    init {
        viewModelScope.launch {
            repository.observeById(songId).collect { entity ->
                if (entity == null) {
                    _state.update { it.copy(isLoading = false, notFound = true) }
                } else if (!loaded) {
                    // Typing is never overwritten by the repository echoing our own saves:
                    // the fields are read from the database only once.
                    loaded = true
                    validTempo = entity.tempoBpm
                    _state.update {
                        it.copy(
                            isLoading = false,
                            notFound = false,
                            title = entity.title,
                            writer = entity.writer,
                            songKey = entity.songKey,
                            tempoText = entity.tempoBpm?.toString().orEmpty(),
                            mood = entity.mood,
                            status = entity.status,
                            notes = entity.notes,
                        )
                    }
                } else {
                    _state.update { it.copy(notFound = false) }
                }
            }
        }
    }

    // ---- field changes ----

    private fun edit(change: (SongDetailsUiState) -> SongDetailsUiState) {
        if (!loaded) return
        _state.update(change)
        scheduleSave()
    }

    fun onTitleChange(value: String) = edit { it.copy(title = value.replace("\n", " ")) }

    fun onWriterChange(value: String) = edit { it.copy(writer = value.replace("\n", " ")) }

    fun onKeyChange(value: String) = edit { it.copy(songKey = value.replace("\n", " ")) }

    fun onKeyNote(note: String) = edit { it.copy(songKey = keyWithNote(it.songKey, note)) }

    fun onKeyMode(mode: String) = edit { it.copy(songKey = keyWithMode(it.songKey, mode)) }

    fun onMoodChange(value: String) = edit { it.copy(mood = value.replace("\n", " ")) }

    /** Tapping a mood chip sets the mood; tapping the selected one clears it. */
    fun onMoodChip(mood: String) = edit {
        it.copy(mood = if (it.mood.trim().equals(mood, ignoreCase = true)) "" else mood)
    }

    fun onStatusChange(status: String) = edit { it.copy(status = status) }

    fun onNotesChange(value: String) = edit { it.copy(notes = value) }

    /** A tempo outside 20 to 300 shows an error and is not saved; the previous valid value stays. */
    fun onTempoChange(raw: String) {
        if (!loaded) return
        val digits = raw.filter { it.isDigit() }.take(3)
        val parsed = digits.toIntOrNull()
        when {
            digits.isEmpty() -> {
                validTempo = null
                edit { it.copy(tempoText = "", tempoError = false) }
            }
            parsed != null && parsed in TempoTapper.MIN_BPM..TempoTapper.MAX_BPM -> {
                validTempo = parsed
                edit { it.copy(tempoText = digits, tempoError = false) }
            }
            else -> _state.update { it.copy(tempoText = digits, tempoError = true) }
        }
    }

    /** One tap of the Tap tempo button. */
    fun onTapTempo() {
        val bpm = tapper.tap(SystemClock.elapsedRealtime())
        _state.update { it.copy(tapBpm = bpm) }
    }

    /** Writes the detected tempo into the tempo field. */
    fun useTapTempo() {
        val bpm = _state.value.tapBpm ?: return
        validTempo = bpm
        tapper.reset()
        edit { it.copy(tempoText = bpm.toString(), tempoError = false, tapBpm = null) }
    }

    // ---- saving ----

    private fun scheduleSave() {
        editVersion++
        if (!_state.value.isSaving) _state.update { it.copy(isSaving = true) }
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            persist()
        }
    }

    private suspend fun persist() {
        if (!loaded || editVersion == 0L) return
        withContext(NonCancellable) {
            saveMutex.withLock {
                val version = editVersion
                val s = _state.value
                // Start from the latest stored song: the lyrics (content) are never touched here.
                val entity = repository.getById(songId)
                if (entity != null && !entity.isDeleted) {
                    val updated = entity.copy(
                        title = s.title.trim(),
                        writer = s.writer.trim(),
                        songKey = s.songKey.trim(),
                        tempoBpm = validTempo,
                        mood = s.mood.trim(),
                        status = s.status,
                        notes = s.notes,
                    )
                    if (updated != entity) repository.upsert(updated)
                }
                if (version == editVersion) {
                    _state.update { it.copy(isSaving = false) }
                }
            }
        }
    }

    /** Saves right now without waiting for the debounce. Safe to call from lifecycle callbacks. */
    fun flush() {
        saveJob?.cancel()
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { persist() }
    }

    private companion object {
        const val SAVE_DEBOUNCE_MS = 600L
    }
}
