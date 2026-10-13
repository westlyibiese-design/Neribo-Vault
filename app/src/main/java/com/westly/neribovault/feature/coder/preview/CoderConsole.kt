package com.westly.neribovault.feature.coder.preview

import android.net.Uri
import android.webkit.ConsoleMessage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** How serious a console message is. */
enum class ConsoleLevel { Log, Info, Warn, Error, Debug }

/**
 * One line of the preview console. [sourceFile] is a project file such as `script.js` and [line] its
 * line number, when the page said where the message came from. [friendly] is reserved for a plain-English
 * explanation of an error and is not filled in yet.
 */
data class CoderConsoleMessage(
    val id: Long,
    val level: ConsoleLevel,
    val text: String,
    val sourceFile: String?,
    val line: Int?,
    val friendly: String? = null,
)

/**
 * The messages a previewed page writes to its console. It keeps the last 200 and counts the errors
 * since it was last cleared. [host] and [rootPrefix] are used to turn page addresses into project files.
 * All calls come from the main thread.
 */
class CoderConsole(private val host: String, private val rootPrefix: String) {

    /** The most recent messages, oldest first. */
    var messages: List<CoderConsoleMessage> by mutableStateOf(emptyList())
        private set

    /** How many error messages arrived since the console was last cleared. */
    var errorCount: Int by mutableIntStateOf(0)
        private set

    private var nextId = 1L

    /** Records a message from the page's own console. Always returns true, which tells the browser it was handled. */
    fun onConsoleMessage(message: ConsoleMessage): Boolean {
        val level = when (message.messageLevel()) {
            ConsoleMessage.MessageLevel.LOG -> ConsoleLevel.Log
            ConsoleMessage.MessageLevel.TIP -> ConsoleLevel.Info
            ConsoleMessage.MessageLevel.WARNING -> ConsoleLevel.Warn
            ConsoleMessage.MessageLevel.ERROR -> ConsoleLevel.Error
            ConsoleMessage.MessageLevel.DEBUG -> ConsoleLevel.Debug
            else -> ConsoleLevel.Log
        }
        add(level, message.message().orEmpty(), message.sourceId(), message.lineNumber())
        return true
    }

    /** Adds a message. [sourceId] is the address the browser reported; a [line] of 0 or less means unknown. */
    fun add(level: ConsoleLevel, text: String, sourceId: String?, line: Int) {
        val entry = CoderConsoleMessage(
            id = nextId++,
            level = level,
            text = text.take(MAX_TEXT_LENGTH),
            sourceFile = relativeSource(sourceId),
            line = if (line > 0) line else null,
        )
        messages = (messages + entry).takeLast(MAX_MESSAGES)
        if (level == ConsoleLevel.Error) errorCount += 1
    }

    /** Adds an error that did not come from the page's code, such as a page that could not be loaded. */
    fun addError(text: String) {
        add(ConsoleLevel.Error, text, null, 0)
    }

    /** Removes every message and resets the error count. */
    fun clear() {
        messages = emptyList()
        errorCount = 0
    }

    /** The project file behind a page address, or null when the address is on another host or there is none. */
    internal fun relativeSource(sourceId: String?): String? {
        if (sourceId.isNullOrBlank()) return null
        val prefix = "https://$host/"
        if (!sourceId.startsWith(prefix, ignoreCase = true)) return null
        var path = Uri.decode(sourceId.substring(prefix.length).substringBefore('#').substringBefore('?'))
        val virtualMd = CoderPreviewHost.VIRTUAL_PREFIX + "/md/"
        val virtualJson = CoderPreviewHost.VIRTUAL_PREFIX + "/json/"
        path = when {
            path.startsWith(virtualMd) -> path.removePrefix(virtualMd)
            path.startsWith(virtualJson) -> path.removePrefix(virtualJson)
            rootPrefix.isNotEmpty() && path.startsWith("$rootPrefix/") -> path.removePrefix("$rootPrefix/")
            else -> path
        }
        return if (path.isEmpty()) "index.html" else path
    }

    private companion object {
        const val MAX_MESSAGES = 200
        const val MAX_TEXT_LENGTH = 2_000
    }
}
