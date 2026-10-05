package com.westly.neribovault.feature.screenplays.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.util.snippet
import com.westly.neribovault.data.repository.ScreenplaysRepository
import com.westly.neribovault.feature.screenplays.engine.BlockType
import com.westly.neribovault.feature.screenplays.engine.Fountain
import com.westly.neribovault.feature.screenplays.engine.SceneInfo
import com.westly.neribovault.feature.screenplays.engine.ScriptBlock
import com.westly.neribovault.feature.screenplays.engine.ScriptPaginator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val SNIPPET_CHARS = 80

/** One scene row: the paginator's scene, a short look at its first action line, and move limits. */
data class SceneRowItem(
    val info: SceneInfo,
    val snippet: String,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
)

/** Text that sits before the first scene heading. [blockIndex] is the first such block. */
data class PreambleItem(val blockIndex: Int, val snippet: String)

/** Everything the Scenes and characters screen draws. [content] is the script the lists were built from. */
data class ScenesUiState(
    val isLoading: Boolean = true,
    val content: String = "",
    val preamble: PreambleItem? = null,
    val scenes: List<SceneRowItem> = emptyList(),
    val characters: List<SpeakerStat> = emptyList(),
) {
    val isEmpty: Boolean get() = scenes.isEmpty() && characters.isEmpty()
}

/** One-off messages for the snackbar. */
sealed interface ScenesEvent {
    /** A scene was removed; the screen offers Undo. */
    data class SceneDeleted(val number: Int) : ScenesEvent

    /** A plain message, such as an edit that could not be applied. */
    data class Message(val text: String) : ScenesEvent
}

/**
 * Lists the scenes and characters of a script and applies the four scene and character edits.
 * Every write loads the latest entity, edits the parsed blocks with [ScriptOps] and saves only
 * `content`. The screen's lists reload by themselves because they observe the entity.
 */
class ScenesViewModel(
    private val repository: ScreenplaysRepository,
    private val screenplayId: String,
) : ViewModel() {

    val state: StateFlow<ScenesUiState> = repository.observeById(screenplayId)
        .map { entity -> entity?.content }
        .distinctUntilChanged()
        .map { content -> if (content == null) ScenesUiState(isLoading = false) else buildState(content) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScenesUiState())

    private val eventChannel = Channel<ScenesEvent>(Channel.BUFFERED)
    val events: Flow<ScenesEvent> = eventChannel.receiveAsFlow()

    private val writeLock = Mutex()

    /** In memory only: the content before the last scene delete, and the content right after it. */
    private var undoContent: String? = null
    private var undoAfter: String? = null

    fun moveUp(blockIndex: Int) = edit { ScriptOps.moveSceneUp(it, blockIndex) }

    fun moveDown(blockIndex: Int) = edit { ScriptOps.moveSceneDown(it, blockIndex) }

    fun duplicate(blockIndex: Int) = edit { ScriptOps.duplicateScene(it, blockIndex) }

    /** Removes the scene and offers Undo through [events]. */
    fun delete(blockIndex: Int, sceneNumber: Int) {
        edit(
            onSaved = { before, after ->
                undoContent = before
                undoAfter = after
                eventChannel.trySend(ScenesEvent.SceneDeleted(sceneNumber))
            },
        ) { ScriptOps.deleteScene(it, blockIndex) }
    }

    /** Saves the exact content from before the last delete, if the script has not changed since. */
    fun undoDelete() {
        val before = undoContent ?: return
        val after = undoAfter ?: return
        viewModelScope.launch {
            writeLock.withLock {
                val entity = repository.getById(screenplayId)
                if (entity != null && entity.content == after) {
                    repository.upsert(entity.copy(content = before))
                }
                undoContent = null
                undoAfter = null
            }
        }
    }

    fun rename(oldName: String, newName: String) = edit { ScriptOps.renameCharacter(it, oldName, newName) }

    private fun edit(
        onSaved: ((before: String, after: String) -> Unit)? = null,
        operation: (List<ScriptBlock>) -> List<ScriptBlock>?,
    ) {
        viewModelScope.launch {
            writeLock.withLock {
                val entity = repository.getById(screenplayId)
                // The lists on screen must match the saved script, or an index could hit the wrong scene.
                if (entity != null && entity.content == state.value.content) {
                    val result = withContext(Dispatchers.Default) { applyOperation(entity.content, operation) }
                    if (result == null) {
                        eventChannel.trySend(ScenesEvent.Message("That change could not be made, so the script was left as it was."))
                    } else if (result != entity.content) {
                        repository.upsert(entity.copy(content = result))
                        undoContent = null
                        undoAfter = null
                        onSaved?.invoke(entity.content, result)
                    }
                }
            }
        }
    }

    /** Parses, edits and serializes. Null unless the edit applied and the result reads back identically. */
    private fun applyOperation(content: String, operation: (List<ScriptBlock>) -> List<ScriptBlock>?): String? {
        val changed = operation(Fountain.parse(content)) ?: return null
        val text = Fountain.serialize(changed)
        return if (Fountain.parse(text) == changed) text else null
    }
}

private fun buildState(content: String): ScenesUiState {
    val blocks = Fountain.parse(content)
    val paginated = ScriptPaginator.paginate(blocks)
    val scenes = paginated.scenes
    val rows = scenes.mapIndexed { position, info ->
        val end = if (position + 1 < scenes.size) scenes[position + 1].blockIndex else blocks.size
        SceneRowItem(
            info = info,
            snippet = firstActionSnippet(blocks, info.blockIndex + 1, end),
            canMoveUp = position > 0,
            canMoveDown = position < scenes.size - 1,
        )
    }
    val firstScene = scenes.firstOrNull()?.blockIndex ?: blocks.size
    return ScenesUiState(
        isLoading = false,
        content = content,
        preamble = preambleOf(blocks, firstScene),
        scenes = rows,
        characters = ScriptAnalysis.speakerStats(blocks),
    )
}

private fun firstActionSnippet(blocks: List<ScriptBlock>, from: Int, end: Int): String {
    for (index in from until end) {
        val block = blocks[index]
        if (block.type == BlockType.ACTION) {
            val text = ScriptPaginator.stripNotes(block.text)
            if (text.isNotBlank()) return snippet(text, SNIPPET_CHARS)
        }
    }
    return ""
}

private fun preambleOf(blocks: List<ScriptBlock>, firstScene: Int): PreambleItem? {
    for (index in 0 until firstScene) {
        val block = blocks[index]
        if (block.type != BlockType.PAGE_BREAK) {
            val text = ScriptPaginator.stripNotes(block.text)
            if (text.isNotBlank()) return PreambleItem(index, snippet(text, SNIPPET_CHARS))
        }
    }
    return null
}
