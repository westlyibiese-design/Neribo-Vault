package com.westly.neribovault.feature.lyrics.sheet

/**
 * How the lyric sheet is printed. [large] switches the lyric text from 13 pt to 16 pt;
 * [showLabels] prints a label such as VERSE 1 above every section.
 */
data class LyricSheetOptions(
    val large: Boolean = false,
    val showLabels: Boolean = true,
)
