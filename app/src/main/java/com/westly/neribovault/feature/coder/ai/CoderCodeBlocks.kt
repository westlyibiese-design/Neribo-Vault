package com.westly.neribovault.feature.coder.ai

import com.westly.neribovault.feature.coder.files.CoderPaths

private const val PATH_TOKEN = "path="

/**
 * Splits an AI reply into text and fenced code blocks. A fence is a line that starts with three or
 * more backticks; the first word after it is the language and an optional `path=...` word names the
 * file, kept only when it is a valid project path. A block ends at a line that starts with a fence at
 * least as long as the opening one. A block that is never closed (the answer may have been cut off)
 * takes the rest of the reply. Never throws and reads the text once.
 */
fun parseReply(text: String): List<CoderReplySegment> {
    if (text.isEmpty()) return emptyList()
    val lines = text.split('\n').map { it.removeSuffix("\r") }
    val out = ArrayList<CoderReplySegment>()
    val prose = ArrayList<String>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val open = openingFence(line)
        if (open == null) {
            prose.add(line)
            index++
            continue
        }
        flushProse(prose, out)
        val code = ArrayList<String>()
        index++
        while (index < lines.size && !closesFence(lines[index], open.length)) {
            code.add(lines[index])
            index++
        }
        index++ // the closing fence; past the end when the block was never closed
        out.add(CoderReplySegment.Code(blockOf(open.info, code.joinToString("\n"))))
    }
    flushProse(prose, out)
    return out
}

/** The length of the backtick run and the text after it, for a line that opens a fence. */
private class Fence(val length: Int, val info: String)

private fun openingFence(line: String): Fence? {
    val start = line.indexOfFirst { it != ' ' && it != '\t' }
    if (start < 0) return null
    val run = backtickRun(line, start)
    if (run < 3) return null
    val info = line.substring(start + run).trim()
    // Backticks in the rest of the line mean inline code, not a fence.
    if (info.indexOf('`') >= 0) return null
    return Fence(run, info)
}

private fun closesFence(line: String, openLength: Int): Boolean {
    val start = line.indexOfFirst { it != ' ' && it != '\t' }
    if (start < 0) return false
    return backtickRun(line, start) >= openLength
}

private fun backtickRun(line: String, start: Int): Int {
    var end = start
    while (end < line.length && line[end] == '`') end++
    return end - start
}

private fun blockOf(info: String, code: String): CoderCodeBlock {
    var language = ""
    var path: String? = null
    for (token in info.split(' ', '\t')) {
        if (token.isEmpty()) continue
        if (token.startsWith(PATH_TOKEN)) {
            val candidate = token.removePrefix(PATH_TOKEN).trim('"', '\'')
            if (path == null && CoderPaths.isValidPath(candidate)) path = candidate
        } else if (language.isEmpty()) {
            language = token.lowercase()
        }
    }
    return CoderCodeBlock(language, path, code)
}

/** Adds the collected text lines as one segment, without blank lines at either end, and clears them. */
private fun flushProse(prose: MutableList<String>, out: MutableList<CoderReplySegment>) {
    var first = 0
    var last = prose.size - 1
    while (first <= last && prose[first].isBlank()) first++
    while (last >= first && prose[last].isBlank()) last--
    if (first <= last) {
        out.add(CoderReplySegment.Text(prose.subList(first, last + 1).joinToString("\n")))
    }
    prose.clear()
}
