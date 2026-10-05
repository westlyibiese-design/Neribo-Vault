package com.westly.neribovault.feature.screenplays.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.repository.ScreenplaysRepository
import com.westly.neribovault.data.local.entity.ScreenplayEntity
import com.westly.neribovault.feature.screenplays.engine.BlockType
import com.westly.neribovault.feature.screenplays.engine.Fountain
import com.westly.neribovault.feature.screenplays.engine.ScriptBlock
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
 * Everything the editor screen draws.
 * [syncRevision] goes up whenever the ViewModel (not the user's typing) changes block text, so
 * the rows know to refresh their text fields.
 */
data class EditorUiState(
    val isLoading: Boolean = true,
    val notFound: Boolean = false,
    val title: String = "",
    val blocks: List<EditorBlock> = emptyList(),
    val focusedId: String? = null,
    val focusTarget: FocusTarget? = null,
    val flash: FlashTarget? = null,
    val syncRevision: Long = 0L,
    val isSaving: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
)

/** State and actions of the screenplay writing screen. */
class ScreenplayEditorViewModel(
    private val repository: ScreenplaysRepository,
    private val screenplayId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val history = ScreenplayUndoStack(HISTORY_LIMIT)
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
            repository.observeById(screenplayId).collect { entity -> handleEmission(entity) }
        }
    }

    // ---- loading and external changes ----

    private suspend fun handleEmission(entity: ScreenplayEntity?) {
        if (entity == null) {
            if (!deleted) _state.update { it.copy(isLoading = false, notFound = true) }
            return
        }
        if (!loaded) {
            val parsed = withContext(Dispatchers.Default) { Fountain.parse(entity.content) }
            if (loaded) return
            loaded = true
            lastSavedContent = entity.content
            val blocks = toEditorBlocks(parsed)
            val startsEmpty = blocks.isEmpty()
            val shown = if (startsEmpty) listOf(EditorBlock(newId(), BlockType.SCENE_HEADING, "")) else blocks
            val focusBlock = if (startsEmpty) shown.first() else null
            baseline = EditorSnapshot(shown, focusBlock?.id)
            _state.update {
                it.copy(
                    isLoading = false,
                    notFound = false,
                    title = entity.title,
                    blocks = shown,
                    focusedId = focusBlock?.id,
                    focusTarget = focusBlock?.let { b -> FocusTarget(b.id, 0, nextToken()) },
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
        _state.update { it.copy(title = entity.title, notFound = false) }
        if (entity.content != lastSavedContent && entity.content !in ownContents) {
            applyExternal(entity.content)
        }
    }

    /** Another screen changed the script: replace the blocks and forget the history. */
    private suspend fun applyExternal(content: String) {
        val parsed = withContext(Dispatchers.Default) { Fountain.parse(content) }
        if (content == lastSavedContent) return
        saveJob?.cancel()
        typingJob?.cancel()
        typingDirty = false
        history.clear()
        ownContents.clear()
        lastSavedContent = content
        val blocks = toEditorBlocks(parsed)
        val shown = if (blocks.isEmpty()) listOf(EditorBlock(newId(), BlockType.SCENE_HEADING, "")) else blocks
        baseline = EditorSnapshot(shown, null)
        _state.update {
            it.copy(
                blocks = shown,
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

    private fun toEditorBlocks(parsed: List<ScriptBlock>): List<EditorBlock> =
        parsed.map { EditorBlock(newId(), it.type, it.text) }

    private fun toScriptBlocks(blocks: List<EditorBlock>): List<ScriptBlock> =
        blocks
            .filter { it.type == BlockType.PAGE_BREAK || it.text.isNotBlank() }
            .map { ScriptBlock(it.type, it.text) }

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
        if (deleted || !loaded) return
        withContext(NonCancellable) {
            saveMutex.withLock {
                val version = editVersion
                val blocksNow = _state.value.blocks
                val content = withContext(Dispatchers.Default) {
                    Fountain.serialize(toScriptBlocks(blocksNow))
                }
                var failed = false
                if (content != lastSavedContent) {
                    val entity = repository.getById(screenplayId)
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

    /** The whole script as Fountain text, for "Copy as Fountain text". */
    fun currentFountain(): String = Fountain.serialize(toScriptBlocks(_state.value.blocks))

    /** Saves, then moves the screenplay to Recently deleted. */
    suspend fun deleteScreenplay() {
        saveJob?.cancel()
        typingJob?.cancel()
        persist()
        deleted = true
        withContext(NonCancellable) { repository.softDelete(screenplayId) }
    }

    // ---- history ----

    private fun snapshot(): EditorSnapshot = EditorSnapshot(_state.value.blocks, _state.value.focusedId)

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
        val focusBlock = snapshot.focusedId?.let { id -> snapshot.blocks.firstOrNull { it.id == id } }
        val token = nextToken()
        _state.update {
            it.copy(
                blocks = snapshot.blocks,
                focusedId = focusBlock?.id,
                focusTarget = focusBlock?.let { b ->
                    FocusTarget(b.id, b.text.length, token, textFocus = b.type != BlockType.PAGE_BREAK)
                },
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
    fun onFocused(blockId: String) {
        _state.update { if (it.focusedId == blockId) it else it.copy(focusedId = blockId) }
    }

    /** Tapping a page break selects it (it has no text field). */
    fun focusPageBreak(blockId: String) {
        val token = nextToken()
        _state.update {
            it.copy(focusedId = blockId, focusTarget = FocusTarget(blockId, 0, token, textFocus = false))
        }
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

    /** Handles a change in the text field of [blockId]. */
    fun onFieldEdit(blockId: String, edit: FieldEdit) {
        if (!loaded) return
        when (edit) {
            is FieldEdit.Typed -> onTyped(blockId, edit.text)
            is FieldEdit.Enter -> onEnter(blockId, edit.before, edit.after)
            FieldEdit.BackspaceAtStart -> onBackspaceAtStart(blockId)
        }
    }

    private fun indexOfBlock(blockId: String): Int = _state.value.blocks.indexOfFirst { it.id == blockId }

    private fun onTyped(blockId: String, text: String) {
        val s = _state.value
        val index = indexOfBlock(blockId)
        if (index < 0) return
        val block = s.blocks[index]
        val newText = EditorRules.transformText(block.type, text)
        if (block.type == BlockType.ACTION && EditorRules.startsSceneHeading(newText)) {
            convertToSceneHeading(index, newText)
            return
        }
        if (newText == block.text) return
        markTyping()
        val blocks = s.blocks.toMutableList()
        blocks[index] = block.copy(text = newText)
        _state.update { it.copy(blocks = blocks) }
        scheduleSave()
        refreshHistoryFlags()
    }

    /** "INT. " typed at the start of an Action turns it into a Scene heading. */
    private fun convertToSceneHeading(index: Int, text: String) {
        beginStructural()
        val s = _state.value
        val block = s.blocks[index]
        val upper = EditorRules.transformText(BlockType.SCENE_HEADING, text)
        val blocks = s.blocks.toMutableList()
        blocks[index] = block.copy(type = BlockType.SCENE_HEADING, text = upper)
        val token = nextToken()
        _state.update {
            it.copy(
                blocks = blocks,
                focusedId = block.id,
                focusTarget = FocusTarget(block.id, upper.length, token),
                syncRevision = it.syncRevision + 1,
            )
        }
        finishStructural()
    }

    private fun onEnter(blockId: String, before: String, after: String) {
        val index = indexOfBlock(blockId)
        if (index < 0) return
        val block = _state.value.blocks[index]
        if (before.isBlank() && after.isBlank()) {
            if (block.type == BlockType.ACTION) return
            beginStructural()
            val blocks = _state.value.blocks.toMutableList()
            blocks[index] = block.copy(type = BlockType.ACTION, text = "")
            val repaired = EditorRules.repairOrphans(blocks, index + 1)
            val token = nextToken()
            _state.update {
                it.copy(
                    blocks = repaired,
                    focusedId = block.id,
                    focusTarget = FocusTarget(block.id, 0, token),
                    syncRevision = it.syncRevision + 1,
                )
            }
            finishStructural()
            return
        }
        beginStructural()
        val result = EditorRules.splitBlock(_state.value.blocks, index, before, after, newId())
        val token = nextToken()
        _state.update {
            it.copy(
                blocks = result.blocks,
                focusedId = result.focusId,
                focusTarget = FocusTarget(result.focusId, result.cursor, token),
                syncRevision = it.syncRevision + 1,
            )
        }
        finishStructural()
    }

    private fun onBackspaceAtStart(blockId: String) {
        val index = indexOfBlock(blockId)
        if (index < 0) return
        val block = _state.value.blocks[index]
        // A block with text keeps it: the field restores its sentinel and nothing else happens.
        if (block.text.isNotEmpty()) return
        removeBlock(index)
    }

    /** Removes the block at [index] and moves focus to the end of the previous block (or the next one at the top). */
    private fun removeBlock(index: Int) {
        val current = _state.value.blocks
        if (current.size <= 1 || index !in current.indices) return
        beginStructural()
        val remaining = current.toMutableList()
        remaining.removeAt(index)
        val repaired = EditorRules.repairOrphans(remaining, index)
        val focusIndex = if (index > 0) index - 1 else 0
        val focusBlock = repaired[focusIndex]
        val atEnd = index > 0
        val cursor = if (atEnd) focusBlock.text.length else 0
        val token = nextToken()
        _state.update {
            it.copy(
                blocks = repaired,
                focusedId = focusBlock.id,
                focusTarget = FocusTarget(
                    focusBlock.id,
                    cursor,
                    token,
                    textFocus = focusBlock.type != BlockType.PAGE_BREAK,
                ),
                syncRevision = it.syncRevision + 1,
            )
        }
        finishStructural()
    }

    /** Replaces the text of the focused block with a tapped suggestion. */
    fun applySuggestion(newText: String) {
        val s = _state.value
        val index = indexOfBlock(s.focusedId ?: return)
        if (index < 0) return
        val block = s.blocks[index]
        if (block.type == BlockType.PAGE_BREAK) return
        val text = EditorRules.transformText(block.type, newText.replace('\n', ' '))
        if (text == block.text) return
        beginStructural()
        val blocks = s.blocks.toMutableList()
        blocks[index] = block.copy(text = text)
        val token = nextToken()
        _state.update {
            it.copy(
                blocks = blocks,
                focusTarget = FocusTarget(block.id, text.length, token),
                syncRevision = it.syncRevision + 1,
            )
        }
        finishStructural()
    }

    /** Changes the type of the focused block (the element bar). */
    fun changeType(newType: BlockType) {
        val s = _state.value
        val index = indexOfBlock(s.focusedId ?: return)
        if (index < 0) return
        val block = s.blocks[index]
        if (block.type == BlockType.PAGE_BREAK || block.type == newType) return
        val previousType = s.blocks.getOrNull(index - 1)?.type
        if (!EditorRules.isChipEnabled(newType, previousType)) return
        beginStructural()
        val text = EditorRules.convertText(block.text, block.type, newType)
        val blocks = s.blocks.toMutableList()
        blocks[index] = block.copy(type = newType, text = text)
        val repaired = EditorRules.repairOrphans(blocks, index + 1)
        val token = nextToken()
        _state.update {
            it.copy(
                blocks = repaired,
                focusTarget = FocusTarget(block.id, text.length, token),
                syncRevision = it.syncRevision + 1,
            )
        }
        finishStructural()
    }

    /** Adds a page break after the focused block, or at the end when nothing is focused. */
    fun insertPageBreak() {
        if (!loaded) return
        beginStructural()
        val s = _state.value
        val focusIndex = indexOfBlock(s.focusedId ?: "")
        val insertAt = if (focusIndex >= 0) focusIndex + 1 else s.blocks.size
        val pageBreak = EditorBlock(newId(), BlockType.PAGE_BREAK, "")
        val blocks = s.blocks.toMutableList()
        blocks.add(insertAt, pageBreak)
        val repaired = EditorRules.repairOrphans(blocks, insertAt + 1)
        val token = nextToken()
        _state.update {
            it.copy(
                blocks = repaired,
                focusedId = pageBreak.id,
                focusTarget = FocusTarget(pageBreak.id, 0, token, textFocus = false),
                syncRevision = it.syncRevision + 1,
            )
        }
        finishStructural()
    }

    /** Deletes the focused page break. */
    fun deletePageBreak() {
        val s = _state.value
        val index = indexOfBlock(s.focusedId ?: return)
        if (index < 0 || s.blocks[index].type != BlockType.PAGE_BREAK) return
        if (s.blocks.size <= 1) return
        removeBlock(index)
    }

    /** Tapping the empty space under the last block. */
    fun onTapBelow() {
        if (!loaded) return
        val blocks = _state.value.blocks
        val last = blocks.lastOrNull() ?: return
        if (last.isBlank) {
            val token = nextToken()
            _state.update {
                it.copy(focusedId = last.id, focusTarget = FocusTarget(last.id, last.text.length, token))
            }
            return
        }
        beginStructural()
        val added = EditorBlock(newId(), BlockType.ACTION, "")
        val token = nextToken()
        _state.update {
            it.copy(
                blocks = it.blocks + added,
                focusedId = added.id,
                focusTarget = FocusTarget(added.id, 0, token),
                syncRevision = it.syncRevision + 1,
            )
        }
        finishStructural()
    }

    // ---- jumping from the Scenes screen ----

    /** Scrolls to and focuses the block with [savedIndex] in the saved script. */
    fun jumpToBlock(savedIndex: Int) {
        if (!loaded) {
            pendingJump = savedIndex
            return
        }
        viewModelScope.launch {
            if (saveJob?.isActive != true) {
                val entity = repository.getById(screenplayId)
                if (entity != null && entity.content != lastSavedContent && entity.content !in ownContents) {
                    applyExternal(entity.content)
                }
            }
            applyJump(savedIndex)
        }
    }

    private fun applyJump(savedIndex: Int) {
        val blocks = _state.value.blocks
        if (blocks.isEmpty()) return
        val block = blocks[EditorRules.jumpTargetIndex(blocks, savedIndex)]
        val token = nextToken()
        _state.update {
            it.copy(
                focusedId = block.id,
                focusTarget = FocusTarget(
                    block.id,
                    block.text.length,
                    token,
                    textFocus = block.type != BlockType.PAGE_BREAK,
                ),
                flash = FlashTarget(block.id, token),
            )
        }
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
