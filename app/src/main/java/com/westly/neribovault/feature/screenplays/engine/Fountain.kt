package com.westly.neribovault.feature.screenplays.engine

/** Reads and writes Fountain, the open plain-text screenplay format. Pure Kotlin. */
object Fountain {
    private val SCENE_PREFIX = Regex("^(INT|EXT|EST|INT\\.?/EXT|I/E)[. ]", RegexOption.IGNORE_CASE)
    private val PAGE_BREAK_LINE = Regex("^={3,}$")
    private val CONTD_SUFFIX = Regex(
        "\\s*\\((CONT'D|CONT\u2019D|CONT'D\\.|CONT\\.|CONTD)\\)\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private val FIXED_TRANSITIONS = setOf("FADE IN:", "FADE OUT.", "FADE TO BLACK.")

    /** Parses the script body (no title page) into blocks. Never throws; never drops text. */
    fun parse(content: String): List<ScriptBlock> {
        val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
        val blocks = ArrayList<ScriptBlock>()
        val paragraph = ArrayList<String>()
        for (raw in normalized.split('\n')) {
            val line = raw.trimEnd()
            if (line.isEmpty()) {
                if (paragraph.isNotEmpty()) {
                    classify(paragraph, blocks)
                    paragraph.clear()
                }
            } else {
                paragraph.add(line)
            }
        }
        if (paragraph.isNotEmpty()) classify(paragraph, blocks)
        return blocks
    }

    /**
     * Turns blocks into Fountain text that parses back into the same blocks. Ends with a single
     * "\n" (or is "" for no blocks).
     */
    fun serialize(blocks: List<ScriptBlock>): String {
        val paragraphs = ArrayList<String>()
        var inGroup = false
        for (block in blocks) {
            val isBreak = block.type == BlockType.PAGE_BREAK
            if (!isBreak && block.text.isBlank()) continue
            val attaches = inGroup &&
                (block.type == BlockType.PARENTHETICAL || block.type == BlockType.DIALOGUE)
            if (attaches) {
                val line = if (block.type == BlockType.PARENTHETICAL) {
                    "(" + oneLine(block.text) + ")"
                } else {
                    nonBlankLines(block.text).joinToString("\n")
                }
                paragraphs[paragraphs.size - 1] = paragraphs[paragraphs.size - 1] + "\n" + line
            } else if (block.type == BlockType.CHARACTER) {
                paragraphs.add(cueParagraph(oneLine(block.text)))
                inGroup = true
            } else if (isBreak) {
                paragraphs.add("===")
                inGroup = false
            } else {
                inGroup = false
                // An orphan parenthetical or dialogue is written as an action paragraph.
                val type = when (block.type) {
                    BlockType.PARENTHETICAL, BlockType.DIALOGUE -> BlockType.ACTION
                    else -> block.type
                }
                val text = if (block.type == BlockType.PARENTHETICAL) "(" + block.text + ")" else block.text
                for (part in splitOnBlankLines(text)) {
                    paragraphs.add(plainOrForced(type, part))
                }
            }
        }
        if (paragraphs.isEmpty()) return ""
        return paragraphs.joinToString("\n\n") + "\n"
    }

    // ---- parsing ----

    private fun classify(lines: List<String>, out: MutableList<ScriptBlock>) {
        val first = lines[0]
        val single = lines.size == 1
        val trimmed = first.trim()

        // 1. Page break.
        if (single && PAGE_BREAK_LINE.matches(trimmed)) {
            out.add(ScriptBlock(BlockType.PAGE_BREAK, ""))
            return
        }
        // 2. Forced action.
        if (first.startsWith("!")) {
            val head = first.substring(1)
            val rest = if (head.isBlank()) lines.drop(1) else listOf(head) + lines.drop(1)
            if (rest.isNotEmpty()) out.add(ScriptBlock(BlockType.ACTION, rest.joinToString("\n")))
            return
        }
        // 3. Forced scene heading.
        if (single && first.startsWith(".") && !first.startsWith("..")) {
            val heading = first.substring(1).trim().uppercase()
            if (heading.isNotEmpty()) {
                out.add(ScriptBlock(BlockType.SCENE_HEADING, heading))
                return
            }
        }
        // 4. Scene heading.
        if (single && SCENE_PREFIX.containsMatchIn(trimmed)) {
            out.add(ScriptBlock(BlockType.SCENE_HEADING, trimmed.uppercase()))
            return
        }
        // 5. Forced transition.
        if (single && first.startsWith(">") && !first.endsWith("<")) {
            val transition = first.substring(1).trim().uppercase()
            if (transition.isNotEmpty()) {
                out.add(ScriptBlock(BlockType.TRANSITION, transition))
                return
            }
        }
        // 6. Transition.
        if (single && isUpperCaseLine(trimmed) &&
            (trimmed.endsWith("TO:") || trimmed in FIXED_TRANSITIONS)
        ) {
            out.add(ScriptBlock(BlockType.TRANSITION, trimmed))
            return
        }
        // 7. Character group.
        if (lines.size >= 2) {
            val forced = first.startsWith("@")
            if (forced || (isUpperCaseLine(first) && !trimmed.endsWith("TO:"))) {
                val cue = cueText(first, forced)
                if (cue.isNotEmpty()) {
                    out.add(ScriptBlock(BlockType.CHARACTER, cue))
                    addGroupBody(lines.drop(1), out)
                    return
                }
            }
        }
        // 8. Action.
        out.add(ScriptBlock(BlockType.ACTION, lines.joinToString("\n")))
    }

    private fun cueText(firstLine: String, forced: Boolean): String {
        var cue = if (forced) firstLine.substring(1) else firstLine
        cue = cue.trimEnd()
        if (cue.endsWith("^")) cue = cue.dropLast(1)
        cue = CONTD_SUFFIX.replace(cue.trim(), "").trim()
        return if (forced) cue else cue.uppercase()
    }

    private fun addGroupBody(body: List<String>, out: MutableList<ScriptBlock>) {
        val dialogue = ArrayList<String>()
        for (raw in body) {
            val line = raw.trim()
            val inner = if (line.length >= 2 && line.startsWith("(") && line.endsWith(")")) {
                line.substring(1, line.length - 1).trim()
            } else {
                ""
            }
            if (inner.isNotEmpty()) {
                if (dialogue.isNotEmpty()) {
                    out.add(ScriptBlock(BlockType.DIALOGUE, dialogue.joinToString("\n")))
                    dialogue.clear()
                }
                out.add(ScriptBlock(BlockType.PARENTHETICAL, inner))
            } else {
                dialogue.add(line)
            }
        }
        if (dialogue.isNotEmpty()) {
            out.add(ScriptBlock(BlockType.DIALOGUE, dialogue.joinToString("\n")))
        }
    }

    // ---- serializing ----

    private fun oneLine(text: String): String = text.replace('\n', ' ').trim()

    private fun nonBlankLines(text: String): List<String> = text.split('\n').filter { it.isNotBlank() }

    /** Splits [text] into runs of non-blank lines; each run becomes its own paragraph. */
    private fun splitOnBlankLines(text: String): List<String> {
        val parts = ArrayList<String>()
        val run = ArrayList<String>()
        for (line in text.split('\n')) {
            if (line.isBlank()) {
                if (run.isNotEmpty()) {
                    parts.add(run.joinToString("\n"))
                    run.clear()
                }
            } else {
                run.add(line)
            }
        }
        if (run.isNotEmpty()) parts.add(run.joinToString("\n"))
        return parts
    }

    /** The cue line of a character group, with "@" when it would not be read as a cue on its own. */
    private fun cueParagraph(cue: String): String {
        val probe = parse(cue + "\nx")
        return if (probe.firstOrNull() == ScriptBlock(BlockType.CHARACTER, cue)) cue else "@$cue"
    }

    /** Writes [text] plainly; if that would be read back as another block, adds the forced marker. */
    private fun plainOrForced(type: BlockType, text: String): String {
        if (parse(text) == listOf(ScriptBlock(type, text))) return text
        return when (type) {
            BlockType.SCENE_HEADING -> ".$text"
            BlockType.ACTION -> "!$text"
            BlockType.TRANSITION -> ">$text"
            else -> text
        }
    }
}
