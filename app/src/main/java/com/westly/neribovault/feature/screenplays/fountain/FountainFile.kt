package com.westly.neribovault.feature.screenplays.fountain

import com.westly.neribovault.data.local.entity.ScreenplayEntity
import com.westly.neribovault.feature.screenplays.engine.defaultNoticeText
import java.time.Year

/** The title page fields and the script body of a `.fountain` file. */
data class FountainDocument(
    val title: String,
    val author: String,
    val contact: String,
    val copyright: String,
    val body: String,
)

/** Reads and writes the `.fountain` text format (title page keys, then the script body). Pure Kotlin. */
object FountainFile {

    private val KEY_LINE = Regex("^([A-Za-z][A-Za-z ]*):(.*)$")
    private const val BOM = '\uFEFF'
    private const val CONTINUATION_SPACES = "   "

    /** Splits [text] into the title page fields and the body. Never throws. */
    fun read(text: String): FountainDocument {
        val normalized = text.removePrefix(BOM.toString()).replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split("\n")
        if (!looksLikeKeyLine(lines.firstOrNull().orEmpty())) {
            return FountainDocument("", "", "", "", normalized)
        }

        val keys = ArrayList<String>()
        val values = ArrayList<MutableList<String>>()
        var index = 0
        while (index < lines.size && lines[index].isNotBlank()) {
            val line = lines[index]
            val match = KEY_LINE.matchEntire(line.trimEnd())
            val indented = line.startsWith("   ") || line.startsWith("\t")
            if (!indented && match != null) {
                keys.add(match.groupValues[1].trim().lowercase())
                val inline = match.groupValues[2].trim()
                values.add(if (inline.isEmpty()) mutableListOf() else mutableListOf(inline))
            } else if (values.isNotEmpty()) {
                // An indented line belongs to the key above it. A stray unindented line is kept
                // with that key too, so no text is lost.
                values.last().add(line.trim())
            }
            index += 1
        }
        // Skip the single blank line that ends the title page.
        val bodyStart = if (index < lines.size) index + 1 else index
        val body = lines.drop(bodyStart).joinToString("\n")

        fun valueOf(vararg names: String): List<String> {
            for ((i, key) in keys.withIndex()) {
                if (key in names && values[i].any { it.isNotBlank() }) return values[i]
            }
            return emptyList()
        }

        val titleLines = valueOf("title").filter { it.isNotBlank() }
        val title = titleLines.joinToString(" ").replace("*", "").replace("_", "").trim()
        return FountainDocument(
            title = title,
            author = valueOf("author", "authors").joinToString("\n").trim(),
            contact = valueOf("contact").joinToString("\n").trim(),
            copyright = valueOf("copyright").joinToString("\n").trim(),
            body = body,
        )
    }

    /** The `.fountain` text of [screenplay]: title page keys, a blank line, then the body. */
    fun write(screenplay: ScreenplayEntity): String {
        val header = StringBuilder()
        val title = screenplay.title.trim().replace(Regex("\\s*\\n\\s*"), " ")
        if (title.isNotEmpty()) header.append("Title: ").append(title).append('\n')

        val authorLines = screenplay.author.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (authorLines.isNotEmpty()) {
            header.append("Credit: written by\n")
            header.append("Author: ").append(authorLines.first()).append('\n')
            for (extra in authorLines.drop(1)) header.append(CONTINUATION_SPACES).append(extra).append('\n')
        }

        val contactLines = screenplay.contact.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (contactLines.isNotEmpty()) {
            header.append("Contact:\n")
            for (line in contactLines) header.append(CONTINUATION_SPACES).append(line).append('\n')
        }

        val copyright = printedNotice(screenplay).lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (copyright.isNotEmpty()) header.append("Copyright: ").append(copyright).append('\n')

        if (header.isEmpty()) return screenplay.content
        if (screenplay.content.isBlank()) return header.toString()
        return header.toString() + "\n" + screenplay.content
    }

    /** The notice that would print on the notice page, or "" when the notice is off. */
    private fun printedNotice(screenplay: ScreenplayEntity): String {
        if (!screenplay.noticeEnabled) return ""
        return if (screenplay.noticeText.isBlank()) {
            defaultNoticeText(screenplay.author, Year.now().value)
        } else {
            screenplay.noticeText
        }
    }

    /**
     * True when [line] is a title page key such as `Title: The Last Danfo`. A script that opens
     * with a transition like `FADE IN:` or `CUT TO:` is not mistaken for a title page.
     */
    private fun looksLikeKeyLine(line: String): Boolean {
        val trimmed = line.trimEnd()
        if (line.startsWith(" ") || line.startsWith("\t")) return false
        val match = KEY_LINE.matchEntire(trimmed) ?: return false
        val key = match.groupValues[1].trim()
        if (key.equals("FADE IN", ignoreCase = true)) return false
        if (key == key.uppercase() && key.endsWith(" TO")) return false
        return true
    }
}
