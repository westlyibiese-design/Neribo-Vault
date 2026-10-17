package com.westly.neribovault.feature.coder.editor.smart

import androidx.compose.ui.text.input.TextFieldValue
import com.westly.neribovault.feature.coder.editor.CoderLanguage

/** What a suggestion is, so the strip and the accept rules can treat it correctly. */
enum class SuggestionKind { Tag, Attribute, Property, Value, Keyword, Snippet, Member }

/**
 * One idea shown in the suggestion strip. [label] is what the chip says, [insertText] replaces the typed
 * word when the chip is tapped, [caretOffset] is the caret position inside [insertText] afterwards, and
 * [detail] is a short plain-English phrase.
 */
data class Suggestion(
    val label: String,
    val insertText: String,
    val caretOffset: Int,
    val kind: SuggestionKind,
    val detail: String,
)

/** The suggestions for one caret position. [replaceStart] is where the typed word starts; the caret is where it ends. */
data class SuggestionResult(val replaceStart: Int, val items: List<Suggestion>)

private const val WINDOW = 2_000
private const val MAX_ITEMS = 5
private const val MAX_WORD = 40

private fun noneAt(at: Int): SuggestionResult = SuggestionResult(at, emptyList())

private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

private fun isCssWordChar(c: Char): Boolean = c.isLetter() || c == '-'

private fun isTagNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '-'

private fun isAttributeNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '-' || c == ':' || c == '_'

/**
 * Works out which ideas to offer while typing. Pure and fast: it reads only a small window around the caret
 * (the current line and at most 2,000 characters before it), so a 300 KB file is as quick as a small one.
 * Nothing is offered inside comments, inside strings, in JSON, Markdown or plain text, or in the middle of a word.
 */
object CoderSuggestionEngine {

    /**
     * The ideas for [cursor] in [text] written in [language]: at most five, exact matches first, then the
     * labels in [recent] (the labels accepted lately, oldest first, newest last), then the most common first.
     */
    fun suggest(text: String, cursor: Int, language: CoderLanguage, recent: List<String>): SuggestionResult {
        if (cursor < 0 || cursor > text.length) return noneAt(0)
        if (cursor < text.length && isWordChar(text[cursor])) return noneAt(cursor)
        return when (language) {
            CoderLanguage.Html -> suggestInHtml(text, cursor, recent)
            CoderLanguage.Css -> suggestInCss(text, cursor, 0, recent)
            CoderLanguage.JavaScript, CoderLanguage.TypeScript -> suggestInJs(text, cursor, 0, recent)
            else -> noneAt(cursor)
        }
    }

    /** Like [suggest] for an editor value: nothing while text is selected. */
    internal fun suggestFor(value: TextFieldValue, language: CoderLanguage, recent: List<String>): SuggestionResult {
        if (!value.selection.collapsed) return noneAt(value.selection.end)
        return suggest(value.text, value.selection.start, language, recent)
    }
}

// ---- Shared scanning helpers (also used by auto-close) -----------------------------------------

/** What kind of block of an HTML page the caret is in. */
internal enum class HtmlBlockKind { None, Script, Style }

/** The block the caret is in; [contentStart] is the index of the first character after its opening tag. */
internal class HtmlBlock(val kind: HtmlBlockKind, val contentStart: Int)

/** Where a scan of code ends up: in plain code, a comment or a string. */
internal enum class CodeZone { Code, LineComment, BlockComment, SingleQuote, DoubleQuote, Backtick }

/** Small bounded scanners shared by the suggestion engine and auto-close. Every loop is limited in length. */
internal object CoderSmartScan {
    private const val BLOCK_LOOKBACK = 30_000
    private const val TAG_LOOKBACK = 20_000
    private val NO_BLOCK = HtmlBlock(HtmlBlockKind.None, 0)

    /** Whether the caret is inside a `<script>` or `<style>` block of an HTML page. */
    fun htmlBlockAt(text: String, caret: Int): HtmlBlock {
        val floor = (caret - BLOCK_LOOKBACK).coerceAtLeast(0)
        var i = minOf(caret, text.length) - 1
        while (i >= floor) {
            if (text[i] == '<') {
                val closing = i + 1 < text.length && text[i + 1] == '/'
                val nameStart = if (closing) i + 2 else i + 1
                val kind = when {
                    tagNameAt(text, nameStart, "script") -> HtmlBlockKind.Script
                    tagNameAt(text, nameStart, "style") -> HtmlBlockKind.Style
                    else -> HtmlBlockKind.None
                }
                if (kind != HtmlBlockKind.None) {
                    if (closing) return NO_BLOCK
                    val gt = text.indexOf('>', i)
                    return if (gt in 0 until caret) HtmlBlock(kind, gt + 1) else NO_BLOCK
                }
            }
            i--
        }
        return NO_BLOCK
    }

    private fun tagNameAt(text: String, start: Int, name: String): Boolean {
        if (!text.regionMatches(start, name, 0, name.length, ignoreCase = true)) return false
        val end = start + name.length
        return end >= text.length || !text[end].isLetterOrDigit()
    }

    /** Where to start scanning for a caret: the start of the code, or the start of a line about 2,000 characters back. */
    fun scanStart(text: String, caret: Int, floor: Int): Int {
        val raw = caret - WINDOW
        if (raw <= floor) return floor
        var i = raw
        while (i < caret && text[i] != '\n') i++
        return if (i < caret) i + 1 else raw
    }

    /** Whether the code just before [caret] ends inside a comment or a string. [lineComments] turns on `//` comments. */
    fun zoneAt(text: String, from: Int, caret: Int, lineComments: Boolean): CodeZone {
        var zone = CodeZone.Code
        var i = from
        while (i < caret) {
            val c = text[i]
            when (zone) {
                CodeZone.Code -> {
                    if (c == '/' && i + 1 < caret && lineComments && text[i + 1] == '/') {
                        zone = CodeZone.LineComment
                        i++
                    } else if (c == '/' && i + 1 < caret && text[i + 1] == '*') {
                        zone = CodeZone.BlockComment
                        i++
                    } else if (c == '\'') {
                        zone = CodeZone.SingleQuote
                    } else if (c == '"') {
                        zone = CodeZone.DoubleQuote
                    } else if (c == '`') {
                        zone = CodeZone.Backtick
                    }
                }
                CodeZone.LineComment -> {
                    if (c == '\n') zone = CodeZone.Code
                }
                CodeZone.BlockComment -> {
                    if (c == '*' && i + 1 < caret && text[i + 1] == '/') {
                        zone = CodeZone.Code
                        i++
                    }
                }
                CodeZone.SingleQuote, CodeZone.DoubleQuote -> {
                    if (c == '\\') {
                        i++
                    } else if (c == '\n') {
                        zone = CodeZone.Code
                    } else if ((zone == CodeZone.SingleQuote && c == '\'') || (zone == CodeZone.DoubleQuote && c == '"')) {
                        zone = CodeZone.Code
                    }
                }
                CodeZone.Backtick -> {
                    if (c == '\\') {
                        i++
                    } else if (c == '`') {
                        zone = CodeZone.Code
                    }
                }
            }
            i++
        }
        return zone
    }

    private fun isComment(zone: CodeZone): Boolean = zone == CodeZone.LineComment || zone == CodeZone.BlockComment

    /** Whether the caret is inside an HTML comment (`<!-- ... -->`) that opened within the window. */
    fun inHtmlComment(text: String, caret: Int): Boolean {
        val floor = (caret - WINDOW).coerceAtLeast(0)
        val open = lastIndexOfString(text, "<!--", caret - 4, floor)
        if (open < 0) return false
        val close = lastIndexOfString(text, "-->", caret - 3, floor)
        return close < open + 4
    }

    /** Whether the caret is inside a comment of the kinds that matter: HTML, CSS, and `//` and block comments of code. */
    fun inComment(text: String, caret: Int, language: CoderLanguage): Boolean = when (language) {
        CoderLanguage.Html -> inHtmlComment(text, caret) || inHtmlBlockComment(text, caret)
        CoderLanguage.Css -> isComment(zoneAt(text, scanStart(text, caret, 0), caret, false))
        CoderLanguage.JavaScript, CoderLanguage.TypeScript, CoderLanguage.Java, CoderLanguage.Kotlin, CoderLanguage.CLike ->
            isComment(zoneAt(text, scanStart(text, caret, 0), caret, true))
        else -> false
    }

    private fun inHtmlBlockComment(text: String, caret: Int): Boolean {
        val block = htmlBlockAt(text, caret)
        return when (block.kind) {
            HtmlBlockKind.Script -> isComment(zoneAt(text, scanStart(text, caret, block.contentStart), caret, true))
            HtmlBlockKind.Style -> isComment(zoneAt(text, scanStart(text, caret, block.contentStart), caret, false))
            HtmlBlockKind.None -> false
        }
    }

    /** The leading spaces and tabs of the line that holds [pos], or an empty text when the line is too long to see. */
    fun lineIndent(text: String, pos: Int): String {
        val floor = (pos - WINDOW).coerceAtLeast(0)
        var lineStart = pos
        while (lineStart > floor && text[lineStart - 1] != '\n') lineStart--
        if (lineStart > 0 && text[lineStart - 1] != '\n') return ""
        var end = lineStart
        while (end < pos && (text[end] == ' ' || text[end] == '\t')) end++
        return text.substring(lineStart, end)
    }

    /** The last index at or before [from] (and not before [floor]) where [needle] starts, or -1. */
    fun lastIndexOfString(text: String, needle: String, from: Int, floor: Int): Int {
        var i = minOf(from, text.length - needle.length)
        while (i >= floor && i >= 0) {
            if (text.startsWith(needle, i)) return i
            i--
        }
        return -1
    }

    /** The last index at or before [from] (and not before [floor]) of [ch], or -1. */
    fun lastIndexOfChar(text: String, ch: Char, from: Int, floor: Int): Int {
        var i = minOf(from, text.length - 1)
        while (i >= floor && i >= 0) {
            if (text[i] == ch) return i
            i--
        }
        return -1
    }

    /**
     * The name of the tag that is still open just before [end]: the nearest `<name>` that has no `</name>`
     * yet. Void tags, self-closing tags, comments and the inside of script and style blocks are skipped.
     * Looks back at most 20,000 characters. Null when nothing is open.
     */
    fun nearestUnclosedTag(text: String, end: Int): String? {
        val limit = end.coerceIn(0, text.length)
        val stack = ArrayList<String>()
        var i = text.indexOf('<', (limit - TAG_LOOKBACK).coerceAtLeast(0))
        while (i in 0 until limit) {
            var next = i + 1
            if (text.startsWith("<!--", i)) {
                val close = text.indexOf("-->", i + 4)
                if (close < 0 || close + 3 > limit) break
                next = close + 3
            } else if (i + 1 < limit && text[i + 1] == '/') {
                val nameEnd = nameEndAt(text, i + 2, limit)
                if (nameEnd > i + 2) {
                    val at = indexOfName(stack, text.substring(i + 2, nameEnd))
                    if (at >= 0) {
                        while (stack.size > at) stack.removeAt(stack.size - 1)
                    }
                }
                val gt = text.indexOf('>', i)
                next = if (gt < 0) limit else gt + 1
            } else if (i + 1 < limit && text[i + 1].isLetter()) {
                val nameEnd = nameEndAt(text, i + 1, limit)
                val name = text.substring(i + 1, nameEnd)
                val gt = tagEndAt(text, nameEnd, limit)
                if (gt < 0) break
                val lower = name.lowercase()
                if (text[gt - 1] != '/' && lower !in SMART_HTML_VOID_TAGS) {
                    stack.add(name)
                    if (lower == "script" || lower == "style") {
                        val close = text.indexOf("</$lower", gt)
                        if (close < 0 || close >= limit) break
                        next = close
                    } else {
                        next = gt + 1
                    }
                } else {
                    next = gt + 1
                }
            }
            i = text.indexOf('<', next)
        }
        return if (stack.isEmpty()) null else stack[stack.size - 1]
    }

    private fun nameEndAt(text: String, start: Int, limit: Int): Int {
        var j = start
        while (j < limit && (text[j].isLetterOrDigit() || text[j] == '-' || text[j] == '_' || text[j] == ':' || text[j] == '.')) j++
        return j
    }

    /** The index of the `>` that ends the opening tag whose name ended at [from], or -1 when it is not finished before [limit]. */
    private fun tagEndAt(text: String, from: Int, limit: Int): Int {
        var quote = ' '
        var j = from
        while (j < limit) {
            val c = text[j]
            if (quote != ' ') {
                if (c == quote) quote = ' '
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                return j
            }
            j++
        }
        return -1
    }

    private fun indexOfName(stack: List<String>, name: String): Int {
        var k = stack.size - 1
        while (k >= 0) {
            if (stack[k].equals(name, ignoreCase = true)) return k
            k--
        }
        return -1
    }
}

// ---- Ranking -------------------------------------------------------------------------------------

private class Entry(val suggestion: Suggestion, val order: Int)

/** The entries of one dictionary where the word is also what is inserted. */
private fun plainEntries(words: List<DictWord>, kind: SuggestionKind, firstOrder: Int): List<Entry> =
    words.mapIndexed { index, word ->
        Entry(Suggestion(word.word, word.word, word.word.length, kind, word.detail), firstOrder + index)
    }

/**
 * The best five entries for [prefix]: an exact match first, then the same letter case, then the labels in
 * [recent] (newest first), then dictionary order. Ideas that would only retype what is there are left out.
 */
private fun pick(prefix: String, entries: List<Entry>, recent: List<String>): List<Suggestion> {
    val matching = ArrayList<Entry>()
    for (entry in entries) {
        if (entry.suggestion.label.startsWith(prefix, ignoreCase = true)) matching.add(entry)
    }
    if (matching.isEmpty()) return emptyList()
    matching.sortWith(
        compareBy<Entry>(
            { if (it.suggestion.label == prefix) 0 else 1 },
            { if (it.suggestion.label.startsWith(prefix)) 0 else 1 },
            { -recent.lastIndexOf(it.suggestion.label) },
            { it.order },
        ),
    )
    val out = ArrayList<Suggestion>()
    val seen = HashSet<String>()
    for (entry in matching) {
        val s = entry.suggestion
        if (s.insertText == s.label && s.label == prefix) continue
        if (!seen.add(s.label)) continue
        out.add(s)
        if (out.size == MAX_ITEMS) break
    }
    return out
}

/** A snippet as a suggestion: lines after the first get the indent of the line it is typed on. */
private fun snippetSuggestion(snippet: CoderSnippets.Snippet, indent: String): Suggestion {
    if (indent.isEmpty() || snippet.body.indexOf('\n') < 0) {
        return Suggestion(snippet.trigger, snippet.body, snippet.caretOffset, SuggestionKind.Snippet, snippet.detail)
    }
    val builder = StringBuilder()
    var caret = snippet.caretOffset
    for (index in snippet.body.indices) {
        val ch = snippet.body[index]
        builder.append(ch)
        if (ch == '\n') {
            builder.append(indent)
            if (index < snippet.caretOffset) caret += indent.length
        }
    }
    return Suggestion(snippet.trigger, builder.toString(), caret, SuggestionKind.Snippet, snippet.detail)
}

/** The snippets of [list] whose trigger is exactly [word], as entries that rank before everything else. */
private fun snippetEntries(list: List<CoderSnippets.Snippet>, word: String, indent: String): List<Entry> =
    list.filter { it.trigger == word }.map { Entry(snippetSuggestion(it, indent), -1) }

// ---- HTML ----------------------------------------------------------------------------------------

private fun suggestInHtml(text: String, cursor: Int, recent: List<String>): SuggestionResult {
    val block = CoderSmartScan.htmlBlockAt(text, cursor)
    return when (block.kind) {
        HtmlBlockKind.Script -> suggestInJs(text, cursor, block.contentStart, recent)
        HtmlBlockKind.Style -> suggestInCss(text, cursor, block.contentStart, recent)
        HtmlBlockKind.None -> suggestInMarkup(text, cursor, recent)
    }
}

private fun suggestInMarkup(text: String, cursor: Int, recent: List<String>): SuggestionResult {
    if (CoderSmartScan.inHtmlComment(text, cursor)) return noneAt(cursor)
    val floor = (cursor - WINDOW).coerceAtLeast(0)
    val lastLt = CoderSmartScan.lastIndexOfChar(text, '<', cursor - 1, floor)
    val lastGt = CoderSmartScan.lastIndexOfChar(text, '>', cursor - 1, floor)
    if (lastLt < 0 || lastLt < lastGt) return suggestHtmlSnippet(text, cursor, recent)
    val afterLt = lastLt + 1
    if (afterLt >= cursor) return noneAt(cursor)
    val nextChar = if (cursor < text.length) text[cursor] else ' '
    val first = text[afterLt]
    if (first == '/') return suggestClosingTag(text, cursor, lastLt, nextChar)
    if (!first.isLetter()) return noneAt(cursor)
    val segment = text.substring(afterLt, cursor)
    var whitespaceAt = -1
    for (index in segment.indices) {
        if (segment[index].isWhitespace()) {
            whitespaceAt = index
            break
        }
    }
    if (whitespaceAt < 0) return suggestTagName(segment, afterLt, nextChar, recent)
    return suggestAttribute(segment, whitespaceAt, cursor, nextChar, recent)
}

/** The snippets that work in the text of a page, such as `html5`. */
private fun suggestHtmlSnippet(text: String, cursor: Int, recent: List<String>): SuggestionResult {
    var start = cursor
    while (start > 0 && cursor - start <= MAX_WORD && (text[start - 1].isLetterOrDigit() || text[start - 1] == ':')) start--
    if (cursor - start > MAX_WORD || start == cursor) return noneAt(cursor)
    val word = text.substring(start, cursor)
    val entries = snippetEntries(CoderSnippets.HTML, word, CoderSmartScan.lineIndent(text, start))
    return SuggestionResult(start, pick(word, entries, recent))
}

private fun suggestClosingTag(text: String, cursor: Int, lastLt: Int, nextChar: Char): SuggestionResult {
    val prefix = text.substring(lastLt + 2, cursor)
    if (prefix.isEmpty() || !prefix.all { isTagNameChar(it) }) return noneAt(cursor)
    val name = CoderSmartScan.nearestUnclosedTag(text, lastLt) ?: return noneAt(cursor)
    if (!name.startsWith(prefix, ignoreCase = true)) return noneAt(cursor)
    val insert = if (nextChar == '>') name else "$name>"
    if (insert == prefix) return noneAt(cursor)
    val suggestion = Suggestion(name, insert, insert.length, SuggestionKind.Tag, "Closes the open $name tag")
    return SuggestionResult(lastLt + 2, listOf(suggestion))
}

private fun tagSuggestion(word: DictWord, nextIsGreaterThan: Boolean): Suggestion {
    val name = word.word
    val insert = when {
        nextIsGreaterThan -> name
        name in SMART_HTML_VOID_TAGS -> "$name "
        else -> "$name></$name>"
    }
    val caret = if (nextIsGreaterThan) name.length else name.length + 1
    return Suggestion(name, insert, caret, SuggestionKind.Tag, word.detail)
}

private fun suggestTagName(prefix: String, start: Int, nextChar: Char, recent: List<String>): SuggestionResult {
    if (!prefix.all { isTagNameChar(it) }) return noneAt(start + prefix.length)
    val entries = SMART_HTML_TAGS.mapIndexed { index, word -> Entry(tagSuggestion(word, nextChar == '>'), index) }
    return SuggestionResult(start, pick(prefix, entries, recent))
}

private fun attributeSuggestion(word: DictWord, nameOnly: Boolean): Suggestion {
    val name = word.word
    return when {
        nameOnly -> Suggestion(name, name, name.length, SuggestionKind.Attribute, word.detail)
        name in SMART_HTML_BOOLEAN_ATTRIBUTES -> Suggestion(name, "$name ", name.length + 1, SuggestionKind.Attribute, word.detail)
        else -> Suggestion(name, "$name=\"\"", name.length + 2, SuggestionKind.Attribute, word.detail)
    }
}

/** What is known about the part of an opening tag after its name. */
private class TagRest(
    val inValue: Boolean,
    val attribute: String,
    val valuePrefix: String,
    val namePrefix: String,
    val existing: Set<String>,
)

/** Reads the attributes of an opening tag from [from] to the end of [segment], following quotes. */
private fun parseTagRest(segment: String, from: Int): TagRest {
    val existing = HashSet<String>()
    // 0 between attributes, 1 in a name, 2 after "=", 3 in a quoted value, 4 in an unquoted value
    var state = 0
    var nameStart = 0
    var valueStart = 0
    var lastAttribute = ""
    var quote = ' '
    var i = from
    while (i < segment.length) {
        val ch = segment[i]
        if (state == 0) {
            if (ch == '=') {
                if (lastAttribute.isNotEmpty()) state = 2
            } else if (!ch.isWhitespace() && ch != '/') {
                nameStart = i
                state = 1
            }
        } else if (state == 1) {
            if (ch == '=' || ch.isWhitespace() || ch == '/') {
                lastAttribute = segment.substring(nameStart, i).lowercase()
                existing.add(lastAttribute)
                state = if (ch == '=') 2 else 0
            }
        } else if (state == 2) {
            if (ch == '"' || ch == '\'') {
                quote = ch
                valueStart = i + 1
                state = 3
            } else if (!ch.isWhitespace()) {
                valueStart = i
                state = 4
            }
        } else if (state == 3) {
            if (ch == quote) {
                state = 0
                lastAttribute = ""
            }
        } else if (ch.isWhitespace()) {
            state = 0
            lastAttribute = ""
        }
        i++
    }
    return when (state) {
        3 -> TagRest(true, lastAttribute, segment.substring(valueStart), "", existing)
        1 -> TagRest(false, "", "", segment.substring(nameStart), existing)
        else -> TagRest(false, "", "", "", existing)
    }
}

private fun suggestAttribute(segment: String, whitespaceAt: Int, cursor: Int, nextChar: Char, recent: List<String>): SuggestionResult {
    val tag = segment.substring(0, whitespaceAt).lowercase()
    val rest = parseTagRest(segment, whitespaceAt)
    if (rest.inValue) {
        if (tag == "input" && rest.attribute == "type" && rest.valuePrefix.length >= 2) {
            val entries = plainEntries(SMART_INPUT_TYPES, SuggestionKind.Value, 0)
            return SuggestionResult(cursor - rest.valuePrefix.length, pick(rest.valuePrefix, entries, recent))
        }
        return noneAt(cursor)
    }
    val prefix = rest.namePrefix
    if (prefix.length < 2 || !prefix.all { isAttributeNameChar(it) }) return noneAt(cursor)
    val specific = SMART_HTML_TAG_ATTRIBUTES[tag] ?: emptyList()
    val entries = ArrayList<Entry>()
    var order = 0
    for (word in specific) {
        if (word.word !in rest.existing) entries.add(Entry(attributeSuggestion(word, nextChar == '='), order++))
    }
    for (word in SMART_HTML_GLOBAL_ATTRIBUTES) {
        if (word.word in rest.existing || specific.any { it.word == word.word }) continue
        entries.add(Entry(attributeSuggestion(word, nextChar == '='), order++))
    }
    return SuggestionResult(cursor - prefix.length, pick(prefix, entries, recent))
}

// ---- JavaScript and TypeScript -------------------------------------------------------------------

private fun suggestInJs(text: String, cursor: Int, floor: Int, recent: List<String>): SuggestionResult {
    val from = CoderSmartScan.scanStart(text, cursor, floor)
    if (CoderSmartScan.zoneAt(text, from, cursor, true) != CodeZone.Code) return noneAt(cursor)
    var start = cursor
    while (start > floor && cursor - start <= MAX_WORD && isWordChar(text[start - 1])) start--
    if (cursor - start > MAX_WORD) return noneAt(cursor)
    val prefix = text.substring(start, cursor)
    if (prefix.isEmpty() || prefix[0].isDigit()) return noneAt(cursor)
    if (start > floor && text[start - 1] == '.') {
        val members = SMART_JS_MEMBERS[receiverBefore(text, start - 1, floor)] ?: return noneAt(cursor)
        return SuggestionResult(start, pick(prefix, plainEntries(members, SuggestionKind.Member, 0), recent))
    }
    if (prefix.length < 2) return noneAt(cursor)
    val entries = ArrayList<Entry>()
    entries.addAll(snippetEntries(CoderSnippets.JAVASCRIPT, prefix, CoderSmartScan.lineIndent(text, start)))
    entries.addAll(plainEntries(SMART_JS_KEYWORDS, SuggestionKind.Keyword, 0))
    entries.addAll(plainEntries(SMART_JS_GLOBALS, SuggestionKind.Keyword, SMART_JS_KEYWORDS.size))
    return SuggestionResult(start, pick(prefix, entries, recent))
}

/** The word just before the dot at [dot], or an empty text when it is part of a longer chain such as `a.b.`. */
private fun receiverBefore(text: String, dot: Int, floor: Int): String {
    var start = dot
    while (start > floor && dot - start <= MAX_WORD && isWordChar(text[start - 1])) start--
    if (start > floor && text[start - 1] == '.') return ""
    return text.substring(start, dot)
}

// ---- CSS -----------------------------------------------------------------------------------------

private const val MAX_CSS_DEPTH = 32

private val NESTING_AT_RULES = listOf(
    "@media", "@supports", "@layer", "@container", "@keyframes", "@-webkit-keyframes", "@document",
)

/** What the code before the caret says about where in a stylesheet the caret is. */
private class CssScan(val inCode: Boolean, val inDeclarations: Boolean, val segmentStart: Int)

private fun isNestingPrelude(text: String, start: Int, end: Int): Boolean {
    var s = start
    while (s < end && text[s].isWhitespace()) s++
    if (s >= end || text[s] != '@') return false
    return NESTING_AT_RULES.any { text.startsWith(it, s) }
}

/**
 * Reads the stylesheet from [from] to [caret] once. [truncated] says the start was cut off, so the caret may be
 * inside a rule whose opening brace is out of sight.
 */
private fun scanCss(text: String, from: Int, caret: Int, truncated: Boolean): CssScan {
    val nesting = BooleanArray(MAX_CSS_DEPTH)
    var depth = 0
    var paren = 0
    var lastDelimiter = ' '
    var segmentStart = from
    var preludeStart = from
    var zone = CodeZone.Code
    var i = from
    while (i < caret) {
        val c = text[i]
        if (zone == CodeZone.Code) {
            if (c == '/' && i + 1 < caret && text[i + 1] == '*') {
                zone = CodeZone.BlockComment
                i++
            } else if (c == '"') {
                zone = CodeZone.DoubleQuote
            } else if (c == '\'') {
                zone = CodeZone.SingleQuote
            } else if (c == '(') {
                paren++
            } else if (c == ')') {
                if (paren > 0) paren--
            } else if (c == '{') {
                if (depth < MAX_CSS_DEPTH) nesting[depth] = isNestingPrelude(text, preludeStart, i)
                depth++
                lastDelimiter = '{'
                segmentStart = i + 1
                preludeStart = i + 1
            } else if (c == '}') {
                if (depth > 0) depth--
                lastDelimiter = '}'
                segmentStart = i + 1
                preludeStart = i + 1
                paren = 0
            } else if (c == ';' && paren == 0) {
                lastDelimiter = ';'
                segmentStart = i + 1
                preludeStart = i + 1
            }
        } else if (zone == CodeZone.BlockComment) {
            if (c == '*' && i + 1 < caret && text[i + 1] == '/') {
                zone = CodeZone.Code
                i++
            }
        } else if (c == '\\') {
            i++
        } else if (c == '\n' || (zone == CodeZone.SingleQuote && c == '\'') || (zone == CodeZone.DoubleQuote && c == '"')) {
            zone = CodeZone.Code
        }
        i++
    }
    val inDeclarations = if (depth > 0) {
        depth > MAX_CSS_DEPTH || !nesting[depth - 1]
    } else {
        truncated && (lastDelimiter == ';' || lastDelimiter == ' ')
    }
    return CssScan(zone == CodeZone.Code, inDeclarations, segmentStart)
}

private fun propertySuggestion(word: DictWord, nameOnly: Boolean): Suggestion {
    val name = word.word
    return if (nameOnly) {
        Suggestion(name, name, name.length, SuggestionKind.Property, word.detail)
    } else {
        Suggestion(name, "$name: ;", name.length + 2, SuggestionKind.Property, word.detail)
    }
}

private fun pseudoEntries(words: List<DictWord>): List<Entry> =
    words.mapIndexed { index, word ->
        val caret = if (word.word.endsWith("()")) word.word.length - 1 else word.word.length
        Entry(Suggestion(word.word, word.word, caret, SuggestionKind.Keyword, word.detail), index)
    }

private fun suggestInCss(text: String, cursor: Int, floor: Int, recent: List<String>): SuggestionResult {
    val from = CoderSmartScan.scanStart(text, cursor, floor)
    val scan = scanCss(text, from, cursor, from > floor)
    if (!scan.inCode) return noneAt(cursor)
    var start = cursor
    while (start > scan.segmentStart && cursor - start <= MAX_WORD && isCssWordChar(text[start - 1])) start--
    if (cursor - start > MAX_WORD) return noneAt(cursor)
    val word = text.substring(start, cursor)
    if (word.isEmpty()) return noneAt(cursor)
    val segment = text.substring(scan.segmentStart, cursor)
    val beforeWord = segment.substring(0, segment.length - word.length)
    val nextChar = if (cursor < text.length) text[cursor] else ' '
    val indent = CoderSmartScan.lineIndent(text, start)
    if (scan.inDeclarations) {
        val colonAt = segment.indexOf(':')
        if (colonAt < 0) {
            if (word.length < 2 || beforeWord.isNotBlank()) return noneAt(cursor)
            val entries = ArrayList<Entry>()
            entries.addAll(snippetEntries(CoderSnippets.CSS, word, indent))
            SMART_CSS_PROPERTIES.forEachIndexed { index, w -> entries.add(Entry(propertySuggestion(w, nextChar == ':'), index)) }
            return SuggestionResult(start, pick(word, entries, recent))
        }
        return suggestCssValue(text, start, word, segment, colonAt, beforeWord, recent)
    }
    return suggestCssSelectorSide(text, start, word, segment, indent, recent)
}

private fun suggestCssValue(
    text: String,
    start: Int,
    word: String,
    segment: String,
    colonAt: Int,
    beforeWord: String,
    recent: List<String>,
): SuggestionResult {
    val property = segment.substring(0, colonAt).trim().lowercase()
    if (property.isEmpty() || !property.all { isCssWordChar(it) }) return noneAt(start + word.length)
    val valueText = segment.substring(colonAt + 1)
    if (valueText.count { it == '(' } > valueText.count { it == ')' }) return noneAt(start + word.length)
    val valueBeforeWord = beforeWord.substring(colonAt + 1)
    val minLetters = if (valueBeforeWord.isBlank()) 1 else 2
    if (word.length < minLetters) return noneAt(start + word.length)
    if (start > 0) {
        val previous = text[start - 1]
        if (previous.isDigit() || previous == '#' || previous == '.' || previous == '_') return noneAt(start + word.length)
    }
    val values = ArrayList<DictWord>()
    SMART_CSS_VALUES[property]?.let { values.addAll(it) }
    if (property in SMART_CSS_COLOR_PROPERTIES) values.addAll(SMART_CSS_COLORS)
    if (values.isEmpty()) return noneAt(start + word.length)
    return SuggestionResult(start, pick(word, plainEntries(values, SuggestionKind.Value, 0), recent))
}

/** Ideas where a selector or an at-rule is being written: at-rules after `@`, pseudo-classes after `:`, and snippets. */
private fun suggestCssSelectorSide(
    text: String,
    start: Int,
    word: String,
    segment: String,
    indent: String,
    recent: List<String>,
): SuggestionResult {
    if (segment.count { it == '(' } > segment.count { it == ')' }) return noneAt(start + word.length)
    val previous = if (start > 0) text[start - 1] else ' '
    if (previous == '@') {
        return SuggestionResult(start, pick(word, plainEntries(SMART_CSS_AT_RULES, SuggestionKind.Keyword, 0), recent))
    }
    if (previous == ':') {
        val pseudoElement = start >= 2 && text[start - 2] == ':'
        val words = if (pseudoElement) SMART_CSS_PSEUDO_CLASSES.filter { it.word == "before" || it.word == "after" } else SMART_CSS_PSEUDO_CLASSES
        return SuggestionResult(start, pick(word, pseudoEntries(words), recent))
    }
    if (word.length < 2) return noneAt(start + word.length)
    return SuggestionResult(start, pick(word, snippetEntries(CoderSnippets.CSS, word, indent), recent))
}
