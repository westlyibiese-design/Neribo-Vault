package com.westly.neribovault.feature.lyrics.engine

/** The kind of a song section. Verses are numbered by order of appearance; [OTHER] carries a custom label. */
enum class SectionType { INTRO, VERSE, PRE_CHORUS, CHORUS, BRIDGE, HOOK, INTERLUDE, OUTRO, OTHER }

/**
 * One section of a song.
 * - [label] is used only when [type] == [SectionType.OTHER] (the custom label, trimmed, never
 *   containing "]" or a line break); for every other type it is "".
 * - [text] holds the lines of the section separated by "\n"; it never contains a blank line and
 *   no line ends with a space.
 */
data class SongSection(val type: SectionType, val label: String, val text: String)
