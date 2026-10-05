package com.westly.neribovault.feature.screenplays.export

private const val MAX_NAME_LENGTH = 60
private val UNSAFE_CHARS = Regex("[^\\p{L}\\p{Nd} _-]")
private val SPACE_OR_HYPHEN_RUN = Regex("[ -]{2,}")

/**
 * A safe file name for the exported PDF, for example "The Last Danfo.pdf".
 * A blank title becomes "Screenplay". Anything other than a letter, digit, space, hyphen or
 * underscore becomes a hyphen; runs of spaces or hyphens collapse; the name is cut to 60 characters.
 */
fun pdfFileName(title: String): String {
    val base = title.trim().ifEmpty { "Screenplay" }
    val replaced = UNSAFE_CHARS.replace(base, "-")
    val collapsed = SPACE_OR_HYPHEN_RUN.replace(replaced) { match ->
        if (match.value.contains('-')) "-" else " "
    }
    val cut = collapsed.trim().take(MAX_NAME_LENGTH).trim()
    val name = cut.ifEmpty { "Screenplay" }
    return "$name.pdf"
}
