package com.westly.neribovault.feature.coder.ai

import com.westly.neribovault.data.local.entity.ProjectEntity
import com.westly.neribovault.feature.developer.projectStatusLabel

private const val INFO_MAX_NAME_CHARS = 100
private const val INFO_MAX_DESCRIPTION_CHARS = 1_000
private const val INFO_MAX_TECH_ITEMS = 20
private const val INFO_MAX_TECH_CHARS = 40
private const val INFO_MAX_NOTES = 5
private const val INFO_MAX_NOTE_TITLE_CHARS = 100
private const val INFO_MAX_NOTE_BODY_CHARS = 1_500
private const val INFO_UNTITLED = "Untitled"

/**
 * The text that tells the AI what the linked Developer project is: its name, status, description and tech
 * stack, in a `<project_info>` block. When [notes] is not null and not empty, a `<notes>` block follows with
 * up to 5 notes (title and text) in the given order, so the caller sends them newest first. Blank fields are
 * left out. The description is cut to 1,000 characters, at most 20 tech items are used (each cut to 40), and each
 * note text is cut to 1,500. The repository and live addresses are never part of it. The text is included as is,
 * without escaping: the AI is told to treat it as data. Pure.
 */
fun buildProjectInfo(project: ProjectEntity, notes: List<Pair<String, String>>?): String {
    val out = StringBuilder("<project_info>")
    appendLine(out, "Name", cutInfo(project.name.trim(), INFO_MAX_NAME_CHARS))
    val status = project.status.trim()
    if (status.isNotEmpty()) appendLine(out, "Status", projectStatusLabel(status))
    appendLine(out, "Description", cutInfo(project.description.trim(), INFO_MAX_DESCRIPTION_CHARS))
    val tech = project.techStack
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .take(INFO_MAX_TECH_ITEMS)
        .map { cutInfo(it, INFO_MAX_TECH_CHARS) }
    if (tech.isNotEmpty()) appendLine(out, "Tech stack", tech.joinToString(", "))
    out.append("\n</project_info>")

    val blocks = noteBlocks(notes)
    if (blocks.isNotEmpty()) {
        out.append("\n<notes>\n").append(blocks.joinToString("\n\n")).append("\n</notes>")
    }
    return out.toString()
}

private fun appendLine(out: StringBuilder, label: String, value: String) {
    if (value.isNotEmpty()) out.append('\n').append(label).append(": ").append(value)
}

/** One "## Title" block per usable note, at most 5. A note with neither a title nor a text is skipped. */
private fun noteBlocks(notes: List<Pair<String, String>>?): List<String> {
    if (notes == null) return emptyList()
    val blocks = ArrayList<String>()
    for ((title, body) in notes) {
        if (blocks.size >= INFO_MAX_NOTES) break
        val cleanTitle = cutInfo(title.trim(), INFO_MAX_NOTE_TITLE_CHARS)
        val cleanBody = cutInfo(body.trim(), INFO_MAX_NOTE_BODY_CHARS)
        if (cleanTitle.isEmpty() && cleanBody.isEmpty()) continue
        val heading = "## " + cleanTitle.ifEmpty { INFO_UNTITLED }
        blocks.add(if (cleanBody.isEmpty()) heading else heading + "\n" + cleanBody)
    }
    return blocks
}

/** [text] cut to at most [max] characters, never in the middle of a character made of two parts. */
private fun cutInfo(text: String, max: Int): String {
    if (text.length <= max) return text
    var end = max
    if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
    return text.substring(0, end)
}
