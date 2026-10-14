package com.westly.neribovault.feature.coder.ai

import com.westly.neribovault.feature.coder.files.CoderPaths

/** The most extra files a person can add to a question. */
const val CODER_AI_MAX_EXTRA_FILES = 5

/** The most characters of an error text that are sent. */
const val CODER_AI_MAX_CONSOLE_CHARS = 2_000

private const val MAX_LISTED_PATHS = 200
private const val MAX_FILE_CHARS = 60_000
private const val MAX_FILES = 6
private const val MAX_TURN_CHARS = 120_000
private const val MAX_SELECTION_CHARS = 20_000
private const val MAX_REQUEST_CHARS = 8_000
private const val MAX_HISTORY_MESSAGES = 10
private const val MAX_HISTORY_CHARS = 40_000
private const val MAX_NAME_CHARS = 100
private const val SHORTENED_MARKER = "[... file shortened ...]"

/** The text of one project file that goes with a question. Order matters: the first file has the highest priority. */
data class ContextFile(val path: String, val text: String)

/** The text sent for one question. [shortened] is true when some file text had to be left out. */
data class BuiltTurn(val text: String, val shortened: Boolean)

/**
 * What the workspace knows when a question is sent: the project name, every file path, the open file
 * with its current (maybe unsaved) text, and the selected text of the open file.
 */
data class CoderAiWorkspaceContext(
    val projectName: String,
    val filePaths: List<String>,
    val openPath: String?,
    val openText: String?,
    val selection: String?,
)

/**
 * Builds the one text that is sent for a question: the project's file list, the given files, the
 * selection, the error text and the request, each in its own tag. [files] is in priority order (the
 * open file first); at most 6 are used and the last ones are dropped when the whole text would pass
 * 120,000 characters. Paths that fail [CoderPaths.isValidPath] are never listed or included. Pure.
 */
fun buildUserTurn(
    projectName: String,
    filePaths: List<String>,
    files: List<ContextFile>,
    selection: String?,
    console: String?,
    request: String,
): BuiltTurn {
    var shortened = false
    val validPaths = filePaths.filter { CoderPaths.isValidPath(it) }
    val head = StringBuilder()
    head.append("<project name=\"").append(cleanAttribute(projectName)).append("\">\nFiles:")
    for (path in validPaths.take(MAX_LISTED_PATHS)) head.append("\n- ").append(path)
    if (validPaths.size > MAX_LISTED_PATHS) {
        head.append("\n- ... and ").append(validPaths.size - MAX_LISTED_PATHS).append(" more")
    }
    head.append("\n</project>")

    val fileBlocks = ArrayList<String>()
    val seen = HashSet<String>()
    for (file in files) {
        if (fileBlocks.size >= MAX_FILES) break
        if (!CoderPaths.isValidPath(file.path) || !seen.add(file.path)) continue
        val cut = cutText(file.text, MAX_FILE_CHARS)
        val body = if (cut.length < file.text.length) {
            shortened = true
            cut + "\n" + SHORTENED_MARKER
        } else {
            file.text
        }
        fileBlocks.add("<file path=\"" + file.path + "\">\n" + body + "\n</file>")
    }

    val tail = ArrayList<String>()
    val selected = selection?.takeIf { it.isNotBlank() }
    if (selected != null) {
        val cut = cutText(selected, MAX_SELECTION_CHARS)
        if (cut.length < selected.length) shortened = true
        tail.add("<selection>\n$cut\n</selection>")
    }
    val errorText = console?.takeIf { it.isNotBlank() }
    if (errorText != null) {
        tail.add("<console>\n" + cutText(errorText, CODER_AI_MAX_CONSOLE_CHARS) + "\n</console>")
    }
    tail.add("<request>\n" + cutText(request, MAX_REQUEST_CHARS) + "\n</request>")

    var text = assembleTurn(head.toString(), fileBlocks, tail)
    while (text.length > MAX_TURN_CHARS && fileBlocks.isNotEmpty()) {
        fileBlocks.removeAt(fileBlocks.lastIndex)
        shortened = true
        text = assembleTurn(head.toString(), fileBlocks, tail)
    }
    return BuiltTurn(text, shortened)
}

/**
 * The earlier messages that go with a new question, as role and text pairs: only messages that were
 * sent, the last 10 at most, with older questions as just what was typed. [latestTurn] is the full
 * built text of the newest question and replaces the text of the last user message. The result starts
 * with a user message and alternates; neighbours of the same role are joined with a blank line. Pure.
 */
fun buildHistory(messages: List<CoderChatMessage>, latestTurn: String? = null): List<Pair<ChatRole, String>> {
    var recent = messages.filter { !it.failed }.takeLast(MAX_HISTORY_MESSAGES)
    while (recent.size > 1 && recent.dropLast(1).sumOf { it.text.length } > MAX_HISTORY_CHARS) {
        recent = recent.drop(1)
    }
    val lastUser = recent.indexOfLast { it.role == ChatRole.User }
    val pairs = recent.mapIndexed { index, message ->
        val text = if (latestTurn != null && index == lastUser) latestTurn else message.text
        message.role to text
    }
    return alternate(pairs)
}

private fun alternate(pairs: List<Pair<ChatRole, String>>): List<Pair<ChatRole, String>> {
    val out = ArrayList<Pair<ChatRole, String>>()
    for ((role, text) in pairs) {
        if (out.isEmpty() && role != ChatRole.User) continue
        val last = out.lastOrNull()
        if (last != null && last.first == role) {
            out[out.lastIndex] = role to (last.second + "\n\n" + text)
        } else {
            out.add(role to text)
        }
    }
    return out
}

private fun assembleTurn(head: String, fileBlocks: List<String>, tail: List<String>): String {
    val parts = ArrayList<String>()
    parts.add(head)
    parts.addAll(fileBlocks)
    parts.addAll(tail)
    return parts.joinToString("\n")
}

/** [text] cut to at most [max] characters, never in the middle of a character made of two parts. */
private fun cutText(text: String, max: Int): String {
    if (text.length <= max) return text
    var end = max
    if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
    return text.substring(0, end)
}

/** A project name that cannot break out of its tag. */
private fun cleanAttribute(name: String): String =
    name.filter { it.code >= 32 && it != '"' && it != '<' && it != '>' }.trim().take(MAX_NAME_CHARS)
