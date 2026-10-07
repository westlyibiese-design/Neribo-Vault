package com.westly.neribovault.feature.lyrics.tools

import com.westly.neribovault.feature.lyrics.engine.SongSection

/**
 * Pure operations on a song's sections. Every function returns a new list and returns [sections]
 * unchanged when [index] is out of range (or the move is impossible). No section's text is ever
 * changed; verse numbers are renumbered by `LyricsFormat.serialize`.
 */
object SongOps {

    /** Swaps the section at [index] with the one above it. */
    fun moveUp(sections: List<SongSection>, index: Int): List<SongSection> {
        if (index < 1 || index > sections.lastIndex) return sections
        val result = sections.toMutableList()
        val moved = result[index]
        result[index] = result[index - 1]
        result[index - 1] = moved
        return result
    }

    /** Swaps the section at [index] with the one below it. */
    fun moveDown(sections: List<SongSection>, index: Int): List<SongSection> {
        if (index < 0 || index >= sections.lastIndex) return sections
        val result = sections.toMutableList()
        val moved = result[index]
        result[index] = result[index + 1]
        result[index + 1] = moved
        return result
    }

    /** Inserts an identical copy of the section at [index] directly below it. */
    fun duplicate(sections: List<SongSection>, index: Int): List<SongSection> {
        if (index !in sections.indices) return sections
        val result = sections.toMutableList()
        result.add(index + 1, sections[index])
        return result
    }

    /** Removes the section at [index]. */
    fun delete(sections: List<SongSection>, index: Int): List<SongSection> {
        if (index !in sections.indices) return sections
        val result = sections.toMutableList()
        result.removeAt(index)
        return result
    }
}
