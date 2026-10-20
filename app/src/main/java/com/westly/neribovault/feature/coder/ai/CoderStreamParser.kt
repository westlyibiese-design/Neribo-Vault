package com.westly.neribovault.feature.coder.ai

import com.westly.neribovault.feature.coder.files.CoderPaths

private const val STREAM_PATH_TOKEN = "path="

/** The code block that is being written right now: its language, its file path (null when none or not valid) and the complete lines so far. */
data class OpenBlock(val language: String, val path: String?, val codeSoFar: String)

/**
 * Builds the same pieces as [parseReply] while the text is still arriving. [feed] takes the next piece of the
 * answer, cut at any position; an incomplete last line is held back until its line break arrives, because only a
 * whole line shows whether it is a fence. [finish] ends the text: after it, [segments] equals what [parseReply]
 * gives for the whole text, except that a block whose closing fence never came carries `unfinished = true`.
 * The fence rules are copied from `CoderCodeBlocks.kt` on purpose (they are private there); the self-test compares
 * both at every possible cut. Pure, never throws.
 */
class CoderStreamParser {
    private val raw = StringBuilder()
    private val tail = StringBuilder()
    private val closed = ArrayList<CoderReplySegment>()
    private val prose = ArrayList<String>()
    private val code = ArrayList<String>()
    private var fence: StreamFence? = null
    private var done = false

    /** Every piece fed so far, joined. */
    val rawText: String get() = raw.toString()

    /** True once [finish] was called; later pieces are ignored. */
    val isFinished: Boolean get() = done

    /** True while a code block is open: its opening fence was read and its closing fence was not. */
    val isInsideCode: Boolean get() = fence != null

    /** The pieces that are complete: finished text runs and closed code blocks. After [finish] this is the whole reply. */
    val segments: List<CoderReplySegment> get() = ArrayList(closed)

    /** The code block being written, with its complete lines only, or null when no block is open. */
    val openBlock: OpenBlock?
        get() {
            val open = fence ?: return null
            val block = streamBlockOf(open.info, code.joinToString("\n"), false)
            return OpenBlock(block.language, block.path, block.code)
        }

    /** Adds the next piece of the answer. Does nothing after [finish]. */
    fun feed(delta: String) {
        if (done || delta.isEmpty()) return
        raw.append(delta)
        var pos = 0
        while (pos < delta.length) {
            val lineBreak = delta.indexOf('\n', pos)
            if (lineBreak < 0) {
                tail.append(delta, pos, delta.length)
                break
            }
            tail.append(delta, pos, lineBreak)
            val line = tail.toString().removeSuffix("\r")
            tail.setLength(0)
            processLine(line)
            pos = lineBreak + 1
        }
    }

    /** Ends the text: the held-back last line counts as a whole line, an open block is closed as unfinished and the last text run is added. */
    fun finish() {
        if (done) return
        done = true
        val last = tail.toString().removeSuffix("\r")
        tail.setLength(0)
        processLine(last)
        val open = fence
        if (open != null) {
            closed.add(CoderReplySegment.Code(streamBlockOf(open.info, code.joinToString("\n"), true)))
            fence = null
            code.clear()
        } else {
            flushProse()
        }
    }

    /**
     * What the screen shows while the answer arrives: the closed pieces plus the text run in progress, including the
     * held-back last line when it is safe to show (see [safeTail]). Equals [segments] after [finish].
     */
    fun liveSegments(): List<CoderReplySegment> {
        val out = ArrayList<CoderReplySegment>(closed)
        if (fence == null) {
            val lines = ArrayList<String>(prose)
            val shown = safeTail()
            if (shown.isNotEmpty()) lines.add(shown)
            val text = streamProseText(lines)
            if (text != null) out.add(CoderReplySegment.Text(text))
        }
        return out
    }

    /** Like [openBlock], with the held-back last line added when it is safe to show. */
    fun liveOpenBlock(): OpenBlock? {
        val open = openBlock ?: return null
        val shown = safeTail()
        if (shown.isEmpty()) return open
        val joined = if (code.isEmpty()) shown else open.codeSoFar + "\n" + shown
        return open.copy(codeSoFar = joined)
    }

    /** The held-back last line, or an empty text when it could still turn out to be a fence (it starts with a backtick). */
    private fun safeTail(): String {
        if (tail.isEmpty()) return ""
        val text = tail.toString().removeSuffix("\r")
        val first = text.indexOfFirst { it != ' ' && it != '\t' }
        if (first >= 0 && text[first] == '`') return ""
        return text
    }

    private fun processLine(line: String) {
        val open = fence
        if (open == null) {
            val opening = streamOpeningFence(line)
            if (opening == null) {
                prose.add(line)
                return
            }
            flushProse()
            fence = opening
            code.clear()
            return
        }
        if (streamClosesFence(line, open.length)) {
            closed.add(CoderReplySegment.Code(streamBlockOf(open.info, code.joinToString("\n"), false)))
            fence = null
            code.clear()
            return
        }
        code.add(line)
    }

    private fun flushProse() {
        val text = streamProseText(prose)
        if (text != null) closed.add(CoderReplySegment.Text(text))
        prose.clear()
    }
}

/** The length of the backtick run and the text after it, for a line that opens a fence. */
private class StreamFence(val length: Int, val info: String)

private fun streamOpeningFence(line: String): StreamFence? {
    val start = line.indexOfFirst { it != ' ' && it != '\t' }
    if (start < 0) return null
    val run = streamBacktickRun(line, start)
    if (run < 3) return null
    val info = line.substring(start + run).trim()
    // Backticks in the rest of the line mean inline code, not a fence.
    if (info.indexOf('`') >= 0) return null
    return StreamFence(run, info)
}

private fun streamClosesFence(line: String, openLength: Int): Boolean {
    val start = line.indexOfFirst { it != ' ' && it != '\t' }
    if (start < 0) return false
    return streamBacktickRun(line, start) >= openLength
}

private fun streamBacktickRun(line: String, start: Int): Int {
    var end = start
    while (end < line.length && line[end] == '`') end++
    return end - start
}

private fun streamBlockOf(info: String, code: String, unfinished: Boolean): CoderCodeBlock {
    var language = ""
    var path: String? = null
    for (token in info.split(' ', '\t')) {
        if (token.isEmpty()) continue
        if (token.startsWith(STREAM_PATH_TOKEN)) {
            val candidate = token.removePrefix(STREAM_PATH_TOKEN).trim('"', '\'')
            if (path == null && CoderPaths.isValidPath(candidate)) path = candidate
        } else if (language.isEmpty()) {
            language = token.lowercase()
        }
    }
    return CoderCodeBlock(language, path, code, unfinished)
}

/** The collected text lines as one text without blank lines at either end, or null when only blank lines are left. */
private fun streamProseText(lines: List<String>): String? {
    var first = 0
    var last = lines.size - 1
    while (first <= last && lines[first].isBlank()) first++
    while (last >= first && lines[last].isBlank()) last--
    if (first > last) return null
    return lines.subList(first, last + 1).joinToString("\n")
}
