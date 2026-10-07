package com.westly.neribovault.feature.lyrics.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.entity.SongEntity
import com.westly.neribovault.data.repository.SongsRepository
import com.westly.neribovault.feature.lyrics.engine.LyricsFormat
import com.westly.neribovault.feature.lyrics.engine.SectionType
import com.westly.neribovault.feature.lyrics.engine.SongSection
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
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

/**
 * Everything the song editor draws.
 * [syncRevision] goes up whenever the ViewModel (not the user's typing) changes section text, so
 * the rows know to refresh their text fields.
 */
data class SongEditorUiState(
    val isLoading: Boolean = true,
    val notFound: Boolean = false,
    val title: String = "",
    val writer: String = "",
    val sections: List<EditorSection> = emptyList(),
    val focusedId: String? = null,
    val focusTarget: FocusTarget? = null,
    val flash: FlashTarget? = null,
    val syncRevision: Long = 0L,
    val isSaving: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
)

/** State and actions of the song writing screen. */
class SongEditorViewModel(
    private val repository: SongsRepository,
    private val songId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(SongEditorUiState())
    val state: StateFlow<SongEditorUiState> = _state.asStateFlow()

    private val history = SongUndoStack(HISTORY_LIMIT)
    private var baseline = EditorSnapshot(emptyList(), null)
    private var typingDirty = false
    private var typingJob: Job? = null

    private var saveJob: Job? = null
    private val saveMutex = Mutex()
    private var editVersion = 0L
    private var lastSavedContent = ""
    private val ownContents = ArrayDeque<String>()

    private var loaded = false
    private var deleted = false
    private var pendingJump: Int? = null

    private var tokenCounter = 0L
    private var appliedToken = 0L
    private var liveCursor = 0

    init {
        viewModelScope.launch {
            repository.observeById(songId).collect { entity -> handleEmission(entity) }
        }
    }

    // ---- loading and external changes ----

    private suspend fun handleEmission(entity: SongEntity?) {
        if (entity == null) {
            if (!deleted) _state.update { it.copy(isLoading = false, notFound = true) }
            return
        }
        if (!loaded) {
            val parsed = withContext(Dispatchers.Default) { LyricsFormat.parse(entity.content) }
            if (loaded) return
            loaded = true
            lastSavedContent = entity.content
            val sections = toEditorSections(parsed)
            val startsEmpty = sections.isEmpty()
            val shown = if (startsEmpty) listOf(emptyVerse()) else sections
            val focusSection = if (startsEmpty) shown.first() else null
            baseline = EditorSnapshot(shown, focusSection?.id)
            _state.update {
                it.copy(
                    isLoading = false,
                    notFound = false,
                    title = entity.title,
                    writer = entity.writer,
                    sections = shown,
                    focusedId = focusSection?.id,
                    focusTarget = focusSection?.let { s -> FocusTarget(s.id, 0, nextToken()) },
                    syncRevision = it.syncRevision + 1,
                )
            }
            val jump = pendingJump
            if (jump != null) {
                pendingJump = null
                applyJump(jump)
            }
            return
        }
        _state.update { it.copy(title = entity.title, writer = entity.writer, notFound = false) }
        if (entity.content != lastSavedContent && entity.content !in ownContents) {
            applyExternal(entity.content)
        }
    }

    /** Another screen changed the lyrics: replace the sections and forget the history. */
    private suspend fun applyExternal(content: String) {
        val parsed = withContext(Dispatchers.Default) { LyricsFormat.parse(content) }
        if (content == lastSavedContent) return
        saveJob?.cancel()
        typingJob?.cancel()
        typingDirty = false
        history.clear()
        ownContents.clear()
        lastSavedContent = content
        val sections = toEditorSections(parsed)
        val shown = if (sections.isEmpty()) listOf(emptyVerse()) else sections
        baseline = EditorSnapshot(shown, null)
        _state.update {
            it.copy(
                sections = shown,
                focusedId = null,
                focusTarget = null,
                flash = null,
                isSaving = false,
                canUndo = false,
                canRedo = false,
                syncRevision = it.syncRevision + 1,
            )
        }
    }

    private fun emptyVerse(): EditorSection = EditorSection(newId(), SectionType.VERSE, "", "")

    private fun toEditorSections(parsed: List<SongSection>): List<EditorSection> =
        parsed.map { EditorSection(newId(), it.type, it.label, it.text) }

    private fun toSongSections(sections: List<EditorSection>): List<SongSection> =
        sections.map { SongSection(it.type, it.label, it.text) }

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
        // A song nobody has edited in this session is never written (it would only change its date).
        if (deleted || !loaded || editVersion == 0L) return
        withContext(NonCancellable) {
            saveMutex.withLock {
                val version = editVersion
                val sectionsNow = _state.value.sections
                val content = withContext(Dispatchers.Default) {
                    LyricsFormat.serialize(toSongSections(sectionsNow))
                }
                var failed = false
                if (content != lastSavedContent) {
                    // Always start from the latest stored song so details written elsewhere survive.
                    val entity = repository.getById(songId)
                    if (entity != null && !deleted) {
                        val previous = lastSavedContent
                        lastSavedContent = content
                        ownContents.addLast(content)
                        while (ownContents.size > OWN_CONTENT_LIMIT) ownContents.removeFirst()
                        try {
                            repository.upsert(entity.copy(content = content))
                        } catch (e: Exception) {
                            lastSavedContent = previous
                            failed = true
                        }
                    }
                }
                if (!failed && version == editVersion) {
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

    /** Saves right now and returns once the write is done. */
    suspend fun flushNow() {
        saveJob?.cancel()
        persist()
    }

    /** The lyrics as Lyrics text (labels in square brackets), for "Copy lyrics". */
    fun currentLyrics(): String = LyricsFormat.serialize(toSongSections(_state.value.sections))

    /** Saves, then moves the song to Recently deleted. */
    suspend fun deleteSong() {
        saveJob?.cancel()
        typingJob?.cancel()
        persist()
        deleted = true
        withContext(NonCancellable) { repository.softDelete(songId) }
    }

    // ---- history ----

    private fun snapshot(): EditorSnapshot = EditorSnapshot(_state.value.sections, _state.value.focusedId)

    private fun markTyping() {
        typingDirty = true
        typingJob?.cancel()
        typingJob = viewModelScope.launch {
            delay(TYPING_STEP_MS)
            pushTypingStep()
            refreshHistoryFlags()
        }
    }

    private fun pushTypingStep() {
        if (!typingDirty) return
        history.push(baseline)
        typingDirty = false
        baseline = snapshot()
    }

    private fun commitTyping() {
        typingJob?.cancel()
        typingJob = null
        pushTypingStep()
    }

    private fun beginStructural() {
        commitTyping()
        history.push(snapshot())
    }

    private fun finishStructural() {
        baseline = snapshot()
        scheduleSave()
        refreshHistoryFlags()
    }

    private fun refreshHistoryFlags() {
        val undo = history.canUndo || typingDirty
        val redo = history.canRedo
        _state.update { if (it.canUndo == undo && it.canRedo == redo) it else it.copy(canUndo = undo, canRedo = redo) }
    }

    fun undo() {
        if (!loaded) return
        commitTyping()
        val previous = history.undo(snapshot()) ?: return
        restore(previous)
    }

    fun redo() {
        if (!loaded) return
        commitTyping()
        val next = history.redo(snapshot()) ?: return
        restore(next)
    }

    private fun restore(snapshot: EditorSnapshot) {
        val focusSection = snapshot.focusedId?.let { id -> snapshot.sections.firstOrNull { it.id == id } }
        val token = nextToken()
        _state.update {
            it.copy(
                sections = snapshot.sections,
                focusedId = focusSection?.id,
                focusTarget = focusSection?.let { s -> FocusTarget(s.id, s.text.length, token) },
                syncRevision = it.syncRevision + 1,
            )
        }
        baseline = snapshot
        scheduleSave()
        refreshHistoryFlags()
    }

    // ---- focus ----

    private fun nextToken(): Long {
        tokenCounter++
        return tokenCounter
    }

    /** A text field gained focus. */
    fun onFocused(sectionId: String) {
        _state.update { if (it.focusedId == sectionId) it else it.copy(focusedId = sectionId) }
    }

    /** The row reports its cursor so a rotation can put it back. */
    fun reportCursor(offset: Int) {
        liveCursor = offset
    }

    /** The cursor offset a row should use for [target]: the requested one the first time, the live one after a rotation. */
    fun consumeCursor(target: FocusTarget): Int {
        val cursor = if (appliedToken == target.token) liveCursor else target.cursor
        appliedToken = target.token
        liveCursor = cursor
        return cursor
    }

    // ---- editing ----

    /** Handles a change in the text field of [sectionId]. */
    fun onFieldEdit(sectionId: String, edit: FieldEdit) {
        if (!loaded) return
        when (edit) {
            is FieldEdit.Typed -> onTyped(sectionId, edit.text)
            FieldEdit.BackspaceAtStart -> onBackspaceAtStart(sectionId)
        }
    }

    private fun indexOfSection(sectionId: String): Int = _state.value.sections.indexOfFirst { it.id == sectionId }

    private fun onTyped(sectionId: String, text: String) {
        val s = _state.value
        val index = indexOfSection(sectionId)
        if (index < 0) return
        val section = s.sections[index]
        if (EditorRules.hasBlankLine(text)) {
            splitSection(index, text)
            return
        }
        if (text == section.text) return
        markTyping()
        val sections = s.sections.toMutableList()
        sections[index] = section.copy(text = text)
        _state.update { it.copy(sections = sections) }
        scheduleSave()
        refreshHistoryFlags()
    }

    /** The "Enter twice" rule: a blank line starts a new section of the next type. */
    private fun splitSection(index: Int, text: String) {
        beginStructural()
        val outcome = EditorRules.splitAtBlankLine(_state.value.sections, index, text) { newId() } ?: return
        val token = nextToken()
        _state.update {
            it.copy(
                sections = outcome.sections,
                focusedId = outcome.focusId,
                focusTarget = FocusTarget(outcome.focusId, 0, token),
                syncRevision = it.syncRevision + 1,
            )
        }
        finishStructural()
    }

    private fun onBackspaceAtStart(sectionId: String) {
        val index = indexOfSection(sectionId)
        if (index < 0) return
        val section = _state.value.sections[index]
        // A section with text keeps it: the field restores its sentinel and nothing else happens.
        if (section.text.isNotEmpty()) return
        removeSection(index, keepOne = false)
    }

    /**
     * Removes the section at [index] and moves focus to the end of the previous section (or the
     * start of the next one at the top). When it is the only section: with [keepOne] it is
     * replaced by an empty verse, otherwise nothing happens.
     */
    private fun removeSection(index: Int, keepOne: Boolean) {
        val current = _state.value.sections
        if (index !in current.indices) return
        if (current.size <= 1) {
            if (!keepOne) return
            beginStructural()
            val fresh = emptyVerse()
            val token = nextToken()
            _state.update {
                it.copy(
                    sections = listOf(fresh),
                    focusedId = fresh.id,
                    focusTarget = FocusTarget(fresh.id, 0, token),
                    syncRevision = it.syncRevision + 1,
                )
            }
            finishStructural()
            return
        }
        beginStructural()
        val remaining = current.toMutableList()
        remaining.removeAt(index)
        val focusIndex = if (index > 0) index - 1 else 0
        val focusSection = remaining[focusIndex]
        val cursor = if (index > 0) focusSection.text.length else 0
        val token = nextToken()
        _state.update {
            it.copy(
                sections = remaining,
                focusedId = focusSection.id,
                focusTarget = FocusTarget(focusSection.id, cursor, token),
                syncRevision = it.syncRevision + 1,
            )
        }
        finishStructural()
    }

    // ---- section menu, type sheet, add bar ----

    /** Removes a section from its menu. Undo brings it back. */
    fun deleteSection(sectionId: String) {
        if (!loaded) return
        val index = indexOfSection(sectionId)
        if (index < 0) return
        removeSection(index, keepOne = true)
    }

    /** Inserts an identical section directly below. */
    fun duplicateSection(sectionId: String) {
        if (!loaded) return
        val s = _state.value
        val index = indexOfSection(sectionId)
        if (index < 0) return
        beginStructural()
        val copy = s.sections[index].copy(id = newId())
        val sections = s.sections.toMutableList()
        sections.add(index + 1, copy)
        val token = nextToken()
        _state.update {
            it.copy(
                sections = sections,
                focusedId = copy.id,
                focusTarget = FocusTarget(copy.id, copy.text.length, token),
                syncRevision = it.syncRevision + 1,
            )
        }
        finishStructural()
    }

    fun moveSectionUp(sectionId: String) = moveSection(sectionId, -1)

    fun moveSectionDown(sectionId: String) = moveSection(sectionId, 1)

    private fun moveSection(sectionId: String, delta: Int) {
        if (!loaded) return
        val s = _state.value
        val index = indexOfSection(sectionId)
        if (index < 0) return
        val moved = EditorRules.moved(s.sections, index, index + delta) ?: return
        beginStructural()
        val section = moved[index + delta]
        val token = nextToken()
        _state.update {
            it.copy(
                sections = moved,
                focusedId = section.id,
                focusTarget = FocusTarget(section.id, section.text.length, token),
                flash = FlashTarget(section.id, token),
                syncRevision = it.syncRevision + 1,
            )
        }
        clearFlashLater(token)
        finishStructural()
    }

    /** Changes the type (and, for Other, the label) of a section. An empty new chorus is pre-filled. */
    fun changeType(sectionId: String, type: SectionType, label: String) {
        if (!loaded) return
        val s = _state.value
        val index = indexOfSection(sectionId)
        if (index < 0) return
        val section = s.sections[index]
        val newLabel = if (type == SectionType.OTHER) {
            EditorRules.cleanLabel(label).ifEmpty { EditorRules.DEFAULT_OTHER_LABEL }
        } else {
            ""
        }
        if (section.type == type && section.label == newLabel) return
        beginStructural()
        var text = section.text
        if (type == SectionType.CHORUS && text.isBlank()) {
            val copy = EditorRules.prefilledChorusText(s.sections, index)
            if (copy.isNotEmpty()) text = copy
        }
        val sections = s.sections.toMutableList()
        sections[index] = section.copy(type = type, label = newLabel, text = text)
        val token = nextToken()
        _state.update {
            it.copy(
                sections = sections,
                focusedId = section.id,
                focusTarget = FocusTarget(section.id, text.length, token),
                syncRevision = it.syncRevision + 1,
            )
        }
        finishStructural()
    }

    /** Adds a section of [type] directly after [afterId], or at the end when [afterId] is null. */
    fun addSection(type: SectionType, label: String, afterId: String?) {
        if (!loaded) return
        beginStructural()
        val s = _state.value
        val afterIndex = if (afterId == null) -1 else indexOfSection(afterId)
        val insertAt = if (afterIndex >= 0) afterIndex + 1 else s.sections.size
        val newLabel = if (type == SectionType.OTHER) {
            EditorRules.cleanLabel(label).ifEmpty { EditorRules.DEFAULT_OTHER_LABEL }
        } else {
            ""
        }
        val created = EditorRules.createSection(newId(), type, newLabel, "", s.sections, insertAt)
        val sections = s.sections.toMutableList()
        sections.add(insertAt, created)
        val token = nextToken()
        _state.update {
            it.copy(
                sections = sections,
                focusedId = created.id,
                focusTarget = FocusTarget(created.id, 0, token),
                syncRevision = it.syncRevision + 1,
            )
        }
        finishStructural()
    }

    // ---- jumping from the Tools screen ----

    /** Scrolls to and focuses the section with [savedIndex] in the saved song. */
    fun jumpToSection(savedIndex: Int) {
        if (!loaded) {
            pendingJump = savedIndex
            return
        }
        viewModelScope.launch {
            if (saveJob?.isActive != true) {
                val entity = repository.getById(songId)
                if (entity != null && entity.content != lastSavedContent && entity.content !in ownContents) {
                    applyExternal(entity.content)
                }
            }
            applyJump(savedIndex)
        }
    }

    private fun applyJump(savedIndex: Int) {
        val sections = _state.value.sections
        if (sections.isEmpty()) return
        val section = sections[EditorRules.jumpTargetIndex(sections, savedIndex)]
        val token = nextToken()
        _state.update {
            it.copy(
                focusedId = section.id,
                focusTarget = FocusTarget(section.id, section.text.length, token),
                flash = FlashTarget(section.id, token),
            )
        }
        clearFlashLater(token)
    }

    private fun clearFlashLater(token: Long) {
        viewModelScope.launch {
            delay(FLASH_CLEAR_MS)
            _state.update { if (it.flash?.token == token) it.copy(flash = null) else it }
        }
    }

    private companion object {
        const val HISTORY_LIMIT = 100
        const val SAVE_DEBOUNCE_MS = 600L
        const val TYPING_STEP_MS = 800L
        const val FLASH_CLEAR_MS = 900L
        const val OWN_CONTENT_LIMIT = 8
    }
}
