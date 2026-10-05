package com.westly.neribovault.feature.screenplays.engine

/** The kinds of paragraph a script is made of. [PAGE_BREAK] is a divider, not a writing element. */
enum class BlockType { SCENE_HEADING, ACTION, CHARACTER, PARENTHETICAL, DIALOGUE, TRANSITION, PAGE_BREAK }

/**
 * One paragraph of the script.
 * - SCENE_HEADING, CHARACTER and TRANSITION text is stored in UPPER CASE.
 * - PARENTHETICAL text is stored WITHOUT the surrounding parentheses.
 * - CHARACTER text may contain an extension such as "ADAEZE (V.O.)"; it never contains "(CONT'D)".
 * - ACTION and DIALOGUE text may contain "\n" (line breaks inside the paragraph); other types never do.
 * - PAGE_BREAK text is always "".
 */
data class ScriptBlock(val type: BlockType, val text: String)
