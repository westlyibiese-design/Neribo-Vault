package com.westly.neribovault.feature.coder.editor.smart

import com.westly.neribovault.feature.coder.editor.CoderLanguage
import org.json.JSONException
import org.json.JSONTokener

/** How serious a [CoderIssue] is: an [Error] stops the code from working, a [Warning] is only advice. */
enum class IssueSeverity { Error, Warning }

/** One underlined place: the characters from [start] up to, not including, [end], and what to tell the person. */
data class CoderIssue(val start: Int, val end: Int, val severity: IssueSeverity, val message: String)

/**
 * Finds the obvious mistakes in HTML, CSS, JavaScript/TypeScript and JSON, and explains each in one plain sentence.
 * It only follows a few clear rules and stays silent when it is unsure, so a correct file is never underlined.
 */
object CoderLinter {
    /** The most issues one file can show. */
    const val MAX_ISSUES = 50

    /** Longer files are not checked at all. */
    const val MAX_CHARS = 100_000

    /** The issues of [text], sorted by position, at most [MAX_ISSUES]. Languages without rules give an empty list. */
    fun lint(text: String, language: CoderLanguage): List<CoderIssue> {
        if (text.isEmpty() || text.length > MAX_CHARS) return emptyList()
        val found = when (language) {
            CoderLanguage.Html -> HtmlScan(text).run()
            CoderLanguage.Css -> lintCss(text)
            CoderLanguage.JavaScript, CoderLanguage.TypeScript -> ScriptScan(text).run()
            CoderLanguage.Json -> lintJson(text)
            else -> emptyList()
        }
        return found
            .filter { it.start >= 0 && it.start < it.end && it.end <= text.length }
            .sortedBy { it.start }
            .take(MAX_ISSUES)
    }
}

private const val SCAN_ISSUE_LIMIT = 500

private fun closerOf(opener: Char): Char = when (opener) {
    '(' -> ')'
    '[' -> ']'
    else -> '}'
}

private fun openerOf(closer: Char): Char = when (closer) {
    ')' -> '('
    ']' -> '['
    else -> '{'
}

// ---- HTML ---------------------------------------------------------------------------------------

private val VOID_TAGS = setOf(
    "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr",
    "command", "keygen",
)

/** Tags whose closing tag HTML lets you leave out, so a missing one is never reported. */
private val OPTIONAL_CLOSE = setOf(
    "html", "head", "body", "p", "li", "dt", "dd", "tr", "td", "th", "thead", "tbody", "tfoot", "colgroup",
    "option", "optgroup", "caption", "rt", "rp",
)

private fun isNameStart(ch: Char): Boolean = (ch in 'a'..'z') || (ch in 'A'..'Z')

private fun isNameChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == ':' || ch == '.'

private class OpenTag(val name: String, val written: String, val start: Int, val end: Int)

/** One pass over an HTML text with a stack of the tags that are still open. */
private class HtmlScan(private val text: String) {
    private val n = text.length
    private val issues = ArrayList<CoderIssue>()
    private val stack = ArrayList<OpenTag>()
    private var incomplete = false

    fun run(): List<CoderIssue> {
        var i = 0
        while (i < n) {
            val lt = text.indexOf('<', i)
            if (lt < 0) break
            i = scanAt(lt)
        }
        if (!incomplete) {
            for (open in stack) {
                if (open.name !in OPTIONAL_CLOSE) report(IssueSeverity.Error, open.start, open.end, neverClosed(open.written))
            }
        }
        return issues
    }

    private fun report(severity: IssueSeverity, start: Int, end: Int, message: String) {
        if (issues.size < SCAN_ISSUE_LIMIT) issues.add(CoderIssue(start, end, severity, message))
    }

    private fun neverClosed(written: String): String =
        "This <$written> is never closed. Add </$written> where it should end."

    private fun scanAt(i: Int): Int {
        if (text.startsWith("<!--", i)) return skipPast("-->", i + 4)
        if (text.startsWith("<![CDATA[", i)) return skipPast("]]>", i + 9)
        if (i + 1 >= n) return n
        val c = text[i + 1]
        if (c == '!' || c == '?') return skipPast(">", i + 2)
        if (c == '/') return if (i + 2 < n && isNameStart(text[i + 2])) readClosing(i) else i + 2
        if (isNameStart(c)) return readOpening(i)
        return i + 1
    }

    /** The index after the next [marker], or the end of the text (and a stop to end-of-file reports) when there is none. */
    private fun skipPast(marker: String, from: Int): Int {
        val e = text.indexOf(marker, from)
        if (e < 0) {
            incomplete = true
            return n
        }
        return e + marker.length
    }

    private fun readClosing(i: Int): Int {
        var j = i + 2
        while (j < n && isNameChar(text[j])) j++
        val written = text.substring(i + 2, j)
        val gt = text.indexOf('>', j)
        if (gt < 0) {
            incomplete = true
            return n
        }
        close(written, i, gt + 1)
        return gt + 1
    }

    private fun close(written: String, start: Int, end: Int) {
        val name = written.lowercase()
        if (name in VOID_TAGS) return
        val index = stack.indexOfLast { it.name == name }
        if (index < 0) {
            val top = stack.lastOrNull { it.name !in OPTIONAL_CLOSE }
            if (top == null) {
                report(IssueSeverity.Error, start, end, "This </$written> has no opening <$written>. Remove it or add the opening tag.")
            } else {
                report(IssueSeverity.Error, start, end, "Expected </${top.written}> but found </$written>. Rename this closing tag or close </${top.written}> first.")
            }
            return
        }
        for (k in stack.size - 1 downTo index + 1) {
            val skipped = stack[k]
            if (skipped.name !in OPTIONAL_CLOSE) report(IssueSeverity.Error, skipped.start, skipped.end, neverClosed(skipped.written))
        }
        while (stack.size > index) stack.removeAt(stack.size - 1)
    }

    private fun readOpening(i: Int): Int {
        var j = i + 1
        while (j < n && isNameChar(text[j])) j++
        val written = text.substring(i + 1, j)
        var k = j
        var hasAlt = false
        var selfClosing = false
        var end = -1
        while (k < n) {
            val ch = text[k]
            if (ch == '>') {
                end = k + 1
                selfClosing = k > j && text[k - 1] == '/'
                break
            }
            if (ch.isWhitespace() || ch == '/') {
                k++
                continue
            }
            val nameStart = k
            while (k < n && !text[k].isWhitespace() && text[k] != '=' && text[k] != '>' && text[k] != '/') k++
            if (k == nameStart) {
                k++
                continue
            }
            if (k - nameStart == 3 && text.regionMatches(nameStart, "alt", 0, 3, ignoreCase = true)) hasAlt = true
            var m = k
            while (m < n && text[m].isWhitespace()) m++
            if (m < n && text[m] == '=') {
                m++
                while (m < n && text[m].isWhitespace()) m++
                if (m < n && (text[m] == '"' || text[m] == '\'')) {
                    val closeQuote = text.indexOf(text[m], m + 1)
                    if (closeQuote < 0) {
                        incomplete = true
                        return n
                    }
                    k = closeQuote + 1
                } else {
                    while (m < n && !text[m].isWhitespace() && text[m] != '>') m++
                    k = m
                }
            }
        }
        if (end < 0) {
            incomplete = true
            return n
        }
        val name = written.lowercase()
        if (name == "img" && !hasAlt) {
            report(IssueSeverity.Warning, i, end, "Add alt text so people using screen readers know what the picture shows.")
        }
        if (selfClosing || name in VOID_TAGS) return end
        stack.add(OpenTag(name, written, i, end))
        if (name == "script" || name == "style") return skipRawText(name, end)
        return end
    }

    /** Skips the code inside <script> or <style>; the closing tag is then read as usual. */
    private fun skipRawText(name: String, from: Int): Int {
        var at = text.indexOf("</", from)
        while (at >= 0) {
            val afterName = at + 2 + name.length
            if (text.regionMatches(at + 2, name, 0, name.length, ignoreCase = true) &&
                (afterName >= n || !isNameChar(text[afterName]))
            ) {
                return at
            }
            at = text.indexOf("</", at + 2)
        }
        return n
    }
}

// ---- CSS ----------------------------------------------------------------------------------------

/** Blanks out comments, the text inside quotes and unquoted url(...) so braces and semicolons there are ignored. */
private fun maskCss(text: String): String {
    val out = text.toCharArray()
    val n = text.length
    var i = 0
    while (i < n) {
        val c = text[i]
        if (c == '/' && i + 1 < n && text[i + 1] == '*') {
            val e = text.indexOf("*/", i + 2)
            val stop = if (e < 0) n else e + 2
            blank(out, i, stop)
            i = stop
            continue
        }
        if (c == '"' || c == '\'') {
            val close = stringEndOnLine(text, i)
            if (close < 0) {
                i++
                continue
            }
            blank(out, i + 1, close)
            i = close + 1
            continue
        }
        if ((c == 'u' || c == 'U') && text.regionMatches(i, "url(", 0, 4, ignoreCase = true)) {
            var j = i + 4
            while (j < n && (text[j] == ' ' || text[j] == '\t')) j++
            if (j < n && (text[j] == '"' || text[j] == '\'')) {
                i = j
                continue
            }
            val close = text.indexOf(')', j)
            if (close < 0) {
                i += 4
                continue
            }
            blank(out, j, close)
            i = close + 1
            continue
        }
        i++
    }
    return String(out)
}

private fun blank(out: CharArray, from: Int, to: Int) {
    for (k in from until to) if (out[k] != '\n') out[k] = ' '
}

/** The index of the quote that closes the string opened at [open], or -1 when the line ends first. */
private fun stringEndOnLine(text: String, open: Int): Int {
    val quote = text[open]
    var k = open + 1
    while (k < text.length) {
        val ch = text[k]
        if (ch == '\\') {
            k += 2
            continue
        }
        if (ch == '\n') return -1
        if (ch == quote) return k
        k++
    }
    return -1
}

private class DeclarationCandidate(val start: Int, val end: Int, val depth: Int)

private fun lintCss(text: String): List<CoderIssue> {
    val masked = maskCss(text)
    val issues = ArrayList<CoderIssue>()
    val open = ArrayList<Int>()
    for (idx in masked.indices) {
        val ch = masked[idx]
        if (ch == '{') {
            open.add(idx)
        } else if (ch == '}') {
            if (open.isEmpty()) {
                if (issues.size < SCAN_ISSUE_LIMIT) {
                    issues.add(CoderIssue(idx, idx + 1, IssueSeverity.Error, "This } has no matching { before it. Remove it or add the missing {."))
                }
            } else {
                open.removeAt(open.size - 1)
            }
        }
    }
    for (idx in open) {
        if (issues.size < SCAN_ISSUE_LIMIT) {
            issues.add(CoderIssue(idx, idx + 1, IssueSeverity.Error, "This { is never closed. Add a } where the block should end."))
        }
    }
    addMissingSemicolons(masked, issues)
    return issues
}

/** Reports a declaration line without ; that is directly followed by another declaration in the same block. */
private fun addMissingSemicolons(masked: String, issues: MutableList<CoderIssue>) {
    val n = masked.length
    var depth = 0
    var pending: DeclarationCandidate? = null
    var pos = 0
    while (pos <= n) {
        var eol = masked.indexOf('\n', pos)
        if (eol < 0) eol = n
        val startDepth = depth
        var hasBrace = false
        for (k in pos until eol) {
            val ch = masked[k]
            if (ch == '{') {
                hasBrace = true
                depth++
            } else if (ch == '}') {
                hasBrace = true
                if (depth > 0) depth--
            }
        }
        var a = pos
        var b = eol
        while (a < b && masked[a].isWhitespace()) a++
        while (b > a && masked[b - 1].isWhitespace()) b--
        if (a < b) {
            val waiting = pending
            pending = null
            if (waiting != null && masked[a] != '}' && startDepth == waiting.depth && looksLikeDeclaration(masked, a, b, false)) {
                if (issues.size < SCAN_ISSUE_LIMIT) {
                    issues.add(CoderIssue(waiting.start, waiting.end, IssueSeverity.Error, "Add a ; at the end of this line."))
                }
            }
            if (!hasBrace && startDepth > 0 && endsWithoutSemicolon(masked, b) && looksLikeDeclaration(masked, a, b, true)) {
                pending = DeclarationCandidate(a, b, startDepth)
            }
        }
        pos = eol + 1
    }
}

private fun endsWithoutSemicolon(masked: String, end: Int): Boolean {
    val last = masked[end - 1]
    return last != ';' && last != '{' && last != '}' && last != ',' && last != '(' && last != ':' && last != '\\'
}

/** True for lines shaped like `property: value`. With [needValue] something must follow the colon. */
private fun looksLikeDeclaration(masked: String, from: Int, to: Int, needValue: Boolean): Boolean {
    var k = from
    var dashes = 0
    while (k < to && masked[k] == '-' && dashes < 2) {
        k++
        dashes++
    }
    if (k >= to || !(masked[k].isLetter() || masked[k] == '_')) return false
    while (k < to && (masked[k].isLetterOrDigit() || masked[k] == '-' || masked[k] == '_')) k++
    while (k < to && (masked[k] == ' ' || masked[k] == '\t')) k++
    if (k >= to || masked[k] != ':') return false
    k++
    if (k < to && masked[k] == ':') return false
    if (!needValue) return true
    while (k < to && (masked[k] == ' ' || masked[k] == '\t')) k++
    return k < to
}

// ---- JavaScript and TypeScript ------------------------------------------------------------------

private val REGEX_PREVIOUS = setOf('(', ',', '=', ':', '[', '!', '&', '|', '?', ';', '+', '-', '*', '%', '~', '^')

private val REGEX_KEYWORDS = setOf(
    "return", "typeof", "instanceof", "in", "of", "new", "delete", "void", "throw", "case", "do", "else", "yield", "await",
)

private val STRING_KEYWORDS = setOf(
    "return", "case", "from", "in", "of", "typeof", "new", "yield", "await", "throw", "import", "export", "default",
    "else", "delete", "void", "instanceof",
)

private val STRING_PREVIOUS = setOf('(', ',', '=', ':', '[', '{', '?', '&', '|', '!', '+', '-', '*', '%', '~', '^', ';')

private fun isWordChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_' || ch == '$'

private class ScriptOpener(val ch: Char, val at: Int, val template: Boolean)

/**
 * One pass over JavaScript or TypeScript that skips comments, strings, template text and regular expressions and
 * reports the first bracket problem plus quotes that are not closed on their line.
 */
private class ScriptScan(private val text: String) {
    private val n = text.length
    private val issues = ArrayList<CoderIssue>()
    private val stack = ArrayList<ScriptOpener>()
    private var bracketReported = false

    fun run(): List<CoderIssue> {
        var i = 0
        while (i < n) i = step(i)
        if (!bracketReported && stack.isNotEmpty()) {
            val first = stack[0]
            reportBracket(first.at, "This ${first.ch} is never closed. Add a ${closerOf(first.ch)} where it should end.")
        }
        return issues
    }

    private fun reportBracket(at: Int, message: String) {
        if (bracketReported) return
        bracketReported = true
        issues.add(CoderIssue(at, at + 1, IssueSeverity.Error, message))
    }

    private fun step(i: Int): Int {
        val c = text[i]
        val next = if (i + 1 < n) text[i + 1] else '\u0000'
        if (c == '/' && next == '/') {
            val e = text.indexOf('\n', i)
            return if (e < 0) n else e
        }
        if (c == '/' && next == '*') {
            val e = text.indexOf("*/", i + 2)
            return if (e < 0) n else e + 2
        }
        if (c == '"' || c == '\'') return scanString(i)
        if (c == '`') return scanTemplate(i + 1)
        if (c == '/') {
            val end = if (regexAllowed(i)) regexEnd(i) else -1
            return if (end > 0) end else i + 1
        }
        if (c == '(' || c == '[' || c == '{') {
            stack.add(ScriptOpener(c, i, false))
            return i + 1
        }
        if (c == ')' || c == ']' || c == '}') return closeBracket(c, i)
        return i + 1
    }

    private fun closeBracket(c: Char, i: Int): Int {
        val want = openerOf(c)
        if (stack.isEmpty()) {
            reportBracket(i, "This $c has no matching $want before it. Remove it or add the missing $want.")
            return i + 1
        }
        val top = stack[stack.size - 1]
        if (top.ch != want) {
            reportBracket(i, "This $c doesn't match the ${top.ch} opened earlier. Check the brackets before it.")
            return i + 1
        }
        stack.removeAt(stack.size - 1)
        return if (top.template) scanTemplate(i + 1) else i + 1
    }

    /** Text of a template literal up to the closing backtick or the next ${ (which is then an open bracket). */
    private fun scanTemplate(from: Int): Int {
        var k = from
        while (k < n) {
            val ch = text[k]
            if (ch == '\\') {
                k += 2
                continue
            }
            if (ch == '`') return k + 1
            if (ch == '$' && k + 1 < n && text[k + 1] == '{') {
                stack.add(ScriptOpener('{', k + 1, true))
                return k + 2
            }
            k++
        }
        return n
    }

    private fun scanString(i: Int): Int {
        val quote = text[i]
        val close = stringEndOnLine(text, i)
        if (close >= 0) return close + 1
        if (!stringStartPlausible(i)) return i + 1
        var lineEnd = text.indexOf('\n', i)
        if (lineEnd < 0) lineEnd = n
        var end = lineEnd
        while (end > i + 1 && text[end - 1].isWhitespace()) end--
        if (issues.size < SCAN_ISSUE_LIMIT) {
            issues.add(CoderIssue(i, end, IssueSeverity.Error, "This text in quotes isn't closed. Add a matching $quote at the end."))
        }
        return lineEnd
    }

    /** True when a quote here can only begin a string. In page text such as Don't it begins nothing, so nothing is reported. */
    private fun stringStartPlausible(i: Int): Boolean {
        var j = i - 1
        while (j >= 0 && text[j].isWhitespace()) j--
        if (j < 0) return true
        val p = text[j]
        if (isWordChar(p)) return wordEndingAt(j) in STRING_KEYWORDS
        if (p == '>') return j > 0 && text[j - 1] == '='
        return p in STRING_PREVIOUS
    }

    private fun regexAllowed(i: Int): Boolean {
        var j = i - 1
        while (j >= 0 && text[j].isWhitespace()) j--
        if (j < 0) return true
        val p = text[j]
        if (isWordChar(p)) return wordEndingAt(j) in REGEX_KEYWORDS
        if ((p == '+' || p == '-') && j > 0 && text[j - 1] == p) return false
        return p in REGEX_PREVIOUS
    }

    private fun wordEndingAt(j: Int): String {
        var k = j
        while (k >= 0 && isWordChar(text[k])) k--
        return text.substring(k + 1, j + 1)
    }

    /** The index after a regular expression that starts at [i] and ends on the same line, or -1. */
    private fun regexEnd(i: Int): Int {
        var k = i + 1
        var inClass = false
        while (k < n) {
            val ch = text[k]
            if (ch == '\n') return -1
            if (ch == '\\') {
                k += 2
                continue
            }
            if (inClass) {
                if (ch == ']') inClass = false
            } else if (ch == '[') {
                inClass = true
            } else if (ch == '/') {
                return k + 1
            }
            k++
        }
        return -1
    }
}

// ---- JSON ---------------------------------------------------------------------------------------

private fun lintJson(text: String): List<CoderIssue> {
    if (text.isBlank()) return emptyList()
    try {
        JSONTokener(text).nextValue()
        return emptyList()
    } catch (e: JSONException) {
        val raw = e.message.orEmpty()
        val tag = " at character "
        val marker = raw.indexOf(tag)
        val reason = jsonReason(if (marker >= 0) raw.substring(0, marker) else raw)
        val position = if (marker >= 0) raw.substring(marker + tag.length).takeWhile { it.isDigit() }.toIntOrNull() else null
        val index = ((position ?: text.length) - 1).coerceIn(0, text.length - 1)
        var lineEnd = text.indexOf('\n', index)
        if (lineEnd < 0) lineEnd = text.length
        val end = maxOf(index + 1, lineEnd)
        return listOf(CoderIssue(index, end, IssueSeverity.Error, "This isn't valid JSON: $reason."))
    } catch (e: StackOverflowError) {
        return emptyList()
    }
}

/** A short plain reason for the parser's message. Only the start of the message is used, never the file's text. */
private fun jsonReason(message: String): String = when {
    message.startsWith("Unterminated object") -> "a comma or a closing } is missing"
    message.startsWith("Unterminated array") -> "a comma or a closing ] is missing"
    message.startsWith("Unterminated string") -> "a closing quote is missing"
    message.startsWith("Expected literal value") -> "a value is missing"
    message.startsWith("Expected ':'") -> "a : is missing after a name"
    message.startsWith("End of input") -> "the text ends too early"
    else -> "check the commas, quotes and brackets"
}
