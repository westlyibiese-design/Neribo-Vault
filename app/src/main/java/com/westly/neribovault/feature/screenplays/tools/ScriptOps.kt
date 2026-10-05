package com.westly.neribovault.feature.screenplays.tools

import com.westly.neribovault.feature.screenplays.engine.BlockType
import com.westly.neribovault.feature.screenplays.engine.ScriptBlock
import com.westly.neribovault.feature.screenplays.engine.ScriptPaginator
import com.westly.neribovault.feature.screenplays.engine.speakerOf

/**
 * Pure edits on a block list: move, duplicate and delete a scene, and rename a character.
 * Every function returns a new list, or null when the edit cannot be applied cleanly.
 * A scene starts at a scene heading that still has text after notes are removed (the same
 * rule the paginator uses for its scene list) and runs up to the next such heading.
 */
object ScriptOps {

    /** Indices of the blocks that start a scene, in script order. */
    fun sceneStarts(blocks: List<ScriptBlock>): List<Int> =
        blocks.indices.filter { isSceneStart(blocks[it]) }

    /** Moves the scene that starts at [blockIndex] above the previous scene. Null for the first scene. */
    fun moveSceneUp(blocks: List<ScriptBlock>, blockIndex: Int): List<ScriptBlock>? {
        val starts = sceneStarts(blocks)
        val position = starts.indexOf(blockIndex)
        if (position <= 0) return null
        val previousStart = starts[position - 1]
        val end = endOf(blocks, starts, position)
        return blocks.subList(0, previousStart) +
            blocks.subList(blockIndex, end) +
            blocks.subList(previousStart, blockIndex) +
            blocks.subList(end, blocks.size)
    }

    /** Moves the scene that starts at [blockIndex] below the next scene. Null for the last scene. */
    fun moveSceneDown(blocks: List<ScriptBlock>, blockIndex: Int): List<ScriptBlock>? {
        val starts = sceneStarts(blocks)
        val position = starts.indexOf(blockIndex)
        if (position < 0 || position >= starts.size - 1) return null
        val end = endOf(blocks, starts, position)
        val nextEnd = endOf(blocks, starts, position + 1)
        return blocks.subList(0, blockIndex) +
            blocks.subList(end, nextEnd) +
            blocks.subList(blockIndex, end) +
            blocks.subList(nextEnd, blocks.size)
    }

    /** Inserts an exact copy of the scene directly after it. */
    fun duplicateScene(blocks: List<ScriptBlock>, blockIndex: Int): List<ScriptBlock>? {
        val starts = sceneStarts(blocks)
        val position = starts.indexOf(blockIndex)
        if (position < 0) return null
        val end = endOf(blocks, starts, position)
        return blocks.subList(0, end) +
            blocks.subList(blockIndex, end) +
            blocks.subList(end, blocks.size)
    }

    /** Removes the scene (its heading and every block up to the next scene). */
    fun deleteScene(blocks: List<ScriptBlock>, blockIndex: Int): List<ScriptBlock>? {
        val starts = sceneStarts(blocks)
        val position = starts.indexOf(blockIndex)
        if (position < 0) return null
        val end = endOf(blocks, starts, position)
        return blocks.subList(0, blockIndex) + blocks.subList(end, blocks.size)
    }

    /** How many character cues belong to [name] (compared case-insensitively on the speaker part). */
    fun countCues(blocks: List<ScriptBlock>, name: String): Int =
        blocks.count { it.type == BlockType.CHARACTER && speakerOf(it.text).equals(name, ignoreCase = true) }

    /**
     * Renames a speaker in every cue. Only the part before the first "(" changes; an extension
     * such as "(V.O.)" is kept. Null when [newName] is blank or [oldName] has no cues.
     */
    fun renameCharacter(blocks: List<ScriptBlock>, oldName: String, newName: String): List<ScriptBlock>? {
        val target = newName.trim().uppercase()
        if (target.isEmpty() || countCues(blocks, oldName) == 0) return null
        return blocks.map { block ->
            if (block.type == BlockType.CHARACTER && speakerOf(block.text).equals(oldName, ignoreCase = true)) {
                val open = block.text.indexOf('(')
                val text = if (open >= 0) target + " " + block.text.substring(open) else target
                block.copy(text = text)
            } else {
                block
            }
        }
    }

    private fun isSceneStart(block: ScriptBlock): Boolean =
        block.type == BlockType.SCENE_HEADING && ScriptPaginator.stripNotes(block.text).isNotBlank()

    /** Exclusive end index of the scene at [position] in [starts]. */
    private fun endOf(blocks: List<ScriptBlock>, starts: List<Int>, position: Int): Int =
        if (position + 1 < starts.size) starts[position + 1] else blocks.size
}
