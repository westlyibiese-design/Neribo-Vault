package com.westly.neribovault.feature.lyrics.sheet

private const val MAX_NAME_LENGTH = 60
private val UNSAFE_CHARS = Regex("[^\\p{L}\\p{Nd} _-]")
private val SPACE_OR_HYPHEN_RUN = Regex("[ -]{2,}")

/**
 * A safe file name for the lyric sheet PDF, for example "Lagos Lights.pdf".
 * A blank title becomes "Lyrics". Anything other than a letter, digit, space, hyphen or underscore
 * becomes a hyphen; runs of spaces or hyphens collapse; the name is cut to 60 characters.
 */
fun pdfFileName(title: String): String {
    val base = title.trim().ifEmpty { "Lyrics" }
    val replaced = UNSAFE_CHARS.replace(base, "-")
    val collapsed = SPACE_OR_HYPHEN_RUN.replace(replaced) { match ->
        if (match.value.contains('-')) "-" else " "
    }
    val cut = collapsed.trim().take(MAX_NAME_LENGTH).trim()
    val name = cut.ifEmpty { "Lyrics" }
    return "$name.pdf"
}
