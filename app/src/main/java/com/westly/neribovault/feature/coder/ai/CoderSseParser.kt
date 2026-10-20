package com.westly.neribovault.feature.coder.ai

/** The longest single line the parser keeps. A longer line is dropped whole, so a broken stream cannot fill the memory. */
private const val MAX_LINE_CHARS = 1_000_000

/** One server-sent event: its optional name and its `data:` lines joined with a newline. */
data class SseEvent(val event: String?, val data: String)

/**
 * Turns the text of a server-sent event stream into events. Text may arrive in pieces cut at any position:
 * a line is only used once its line break has arrived. An event is a group of lines ended by a blank line;
 * `event:` names it, one or more `data:` lines hold its content (joined with a newline), lines starting with a
 * colon are comments and every other field is ignored. Both `\n` and `\r\n` end a line, and one space after
 * the colon is dropped. An event with no `data:` line is not an event. Pure, never throws.
 */
class CoderSseParser {
    private val pending = StringBuilder()
    private var skipping = false
    private var eventName: String? = null
    private val dataLines = ArrayList<String>()

    /** Adds the next piece of the stream and returns the events that became complete with it. */
    fun feed(text: String): List<SseEvent> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<SseEvent>()
        var pos = 0
        while (pos < text.length) {
            val lineBreak = text.indexOf('\n', pos)
            if (lineBreak < 0) {
                collect(text, pos, text.length)
                break
            }
            collect(text, pos, lineBreak)
            endLine(out)
            pos = lineBreak + 1
        }
        return out
    }

    /**
     * Call once when the stream has ended. A last line without its line break is used, and an event that was
     * not closed by a blank line is still delivered, because a provider may end right after its last data line.
     */
    fun finish(): List<SseEvent> {
        val out = ArrayList<SseEvent>()
        if (skipping) {
            skipping = false
            pending.setLength(0)
        } else if (pending.isNotEmpty()) {
            endLine(out)
        }
        dispatch(out)
        return out
    }

    private fun collect(text: String, from: Int, to: Int) {
        if (skipping || from >= to) return
        if (pending.length + (to - from) > MAX_LINE_CHARS) {
            pending.setLength(0)
            skipping = true
            return
        }
        pending.append(text, from, to)
    }

    private fun endLine(out: MutableList<SseEvent>) {
        if (skipping) {
            skipping = false
            pending.setLength(0)
            return
        }
        var line = pending.toString()
        pending.setLength(0)
        if (line.endsWith("\r")) line = line.substring(0, line.length - 1)
        handleLine(line, out)
    }

    private fun handleLine(line: String, out: MutableList<SseEvent>) {
        if (line.isEmpty()) {
            dispatch(out)
            return
        }
        if (line[0] == ':') return
        val colon = line.indexOf(':')
        val field: String
        var value: String
        if (colon < 0) {
            field = line
            value = ""
        } else {
            field = line.substring(0, colon)
            value = line.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)
        }
        when (field) {
            "event" -> eventName = value
            "data" -> dataLines.add(value)
        }
    }

    private fun dispatch(out: MutableList<SseEvent>) {
        if (dataLines.isNotEmpty()) out.add(SseEvent(eventName, dataLines.joinToString("\n")))
        eventName = null
        dataLines.clear()
    }
}
