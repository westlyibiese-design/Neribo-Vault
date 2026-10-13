package com.westly.neribovault.feature.coder.editor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.westly.neribovault.feature.coder.ui.CoderSyntaxColors

/** The kinds of token the editor colors. The order matches the style table in [CoderHighlighter.highlight]. */
private enum class TokenKind { Keyword, StringLiteral, Number, Comment, Tag, Attribute }

/** Receives one colored range: start (inclusive), end (exclusive) and its kind. */
private typealias Sink = (Int, Int, TokenKind) -> Unit

/**
 * Colors code with a hand-written scanner that reads the text once, from start to end. It never
 * uses regular expressions that could backtrack, so a large or broken file cannot freeze typing.
 * The result has exactly the characters of the input: only styles are added, which is why the
 * editor can use an identity offset mapping.
 */
object CoderHighlighter {
    /** Longer text is shown without colors, so a huge file never slows typing down. */
    const val MAX_HIGHLIGHT_CHARS = 100_000

    /** The text of [text] with colors for [language]. Unfinished strings and comments never throw. */
    fun highlight(text: String, language: CoderLanguage, colors: CoderSyntaxColors): AnnotatedString {
        if (text.isEmpty() || text.length > MAX_HIGHLIGHT_CHARS || language == CoderLanguage.Plain) {
            return AnnotatedString(text)
        }
        val styles = arrayOf(
            SpanStyle(color = colors.keyword),
            SpanStyle(color = colors.string),
            SpanStyle(color = colors.number),
            SpanStyle(color = colors.comment),
            SpanStyle(color = colors.tag),
            SpanStyle(color = colors.attribute),
        )
        val builder = AnnotatedString.Builder(text)
        val length = text.length
        scan(text, language) { start, end, kind ->
            val from = if (start < 0) 0 else start
            val to = if (end > length) length else end
            if (to > from) builder.addStyle(styles[kind.ordinal], from, to)
        }
        return builder.toAnnotatedString()
    }
}

private fun scan(text: String, language: CoderLanguage, emit: Sink) {
    when (language) {
        CoderLanguage.Html -> scanHtml(text, emit)
        CoderLanguage.Css -> scanCss(text, emit)
        CoderLanguage.Json -> scanJson(text, emit)
        CoderLanguage.Markdown -> scanMarkdown(text, emit)
        CoderLanguage.Yaml -> scanYaml(text, emit)
        CoderLanguage.Plain -> Unit
        else -> scanCode(text, specFor(language), emit)
    }
}

// ---- Shared helpers ------------------------------------------------------------------------

/** The end of a string that starts with [quote] at [start]; an unfinished one ends at the line end unless [multiLine]. */
private fun quotedEnd(text: String, start: Int, quote: Char, multiLine: Boolean): Int {
    val n = text.length
    var j = start + 1
    while (j < n) {
        val ch = text[j]
        if (ch == '\\') {
            if (!multiLine && j + 1 < n && text[j + 1] == '\n') return j + 1
            j += 2
            continue
        }
        if (ch == quote) return j + 1
        if (ch == '\n' && !multiLine) return j
        j++
    }
    return n
}

/** The index of the next line break at or after [from], or the text length. */
private fun lineEnd(text: String, from: Int): Int {
    var j = from
    while (j < text.length && text[j] != '\n') j++
    return j
}

private fun isIdentStart(c: Char): Boolean = c.isLetter() || c == '_' || c == '$'

private fun isIdentPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

private fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

private const val MAX_KEYWORD_LENGTH = 20

// ---- Programming languages -----------------------------------------------------------------

private class CodeSpec(
    val keywords: Set<String>,
    val lineComments: Array<String>,
    val blockComments: Boolean,
    val quotes: String,
    val tripleQuotes: Boolean = false,
    val typesAreTags: Boolean = false,
    val ignoreCase: Boolean = false,
    val commentNeedsSpace: Boolean = false,
    val lifetimeAwareApostrophe: Boolean = false,
)

private val JS_KEYWORDS = setOf(
    "var", "let", "const", "function", "return", "if", "else", "for", "while", "do", "switch", "case",
    "break", "continue", "default", "new", "delete", "typeof", "instanceof", "in", "of", "this", "class",
    "extends", "super", "import", "export", "from", "as", "async", "await", "yield", "try", "catch",
    "finally", "throw", "null", "undefined", "true", "false", "void", "static", "get", "set", "with",
    "debugger",
)

private val TS_KEYWORDS = JS_KEYWORDS + setOf(
    "interface", "type", "enum", "implements", "public", "private", "protected", "readonly", "abstract",
    "declare", "namespace", "module", "keyof", "is", "any", "unknown", "never", "string", "number",
    "boolean", "symbol", "object", "satisfies", "override",
)

private val PYTHON_KEYWORDS = setOf(
    "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif", "else",
    "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "None", "nonlocal",
    "not", "or", "pass", "raise", "return", "try", "while", "with", "yield", "True", "False",
)

private val KOTLIN_KEYWORDS = setOf(
    "package", "import", "class", "interface", "object", "fun", "val", "var", "if", "else", "when", "for",
    "while", "do", "return", "break", "continue", "in", "is", "as", "try", "catch", "finally", "throw",
    "this", "super", "null", "true", "false", "override", "open", "abstract", "final", "sealed", "data",
    "enum", "companion", "private", "public", "protected", "internal", "lateinit", "const", "suspend",
    "inline", "typealias", "by", "init", "constructor", "where", "out", "vararg", "operator", "infix",
    "tailrec", "external", "annotation", "crossinline", "noinline", "reified",
)

private val JAVA_KEYWORDS = setOf(
    "package", "import", "class", "interface", "enum", "extends", "implements", "new", "return", "if",
    "else", "for", "while", "do", "switch", "case", "break", "continue", "default", "try", "catch",
    "finally", "throw", "throws", "this", "super", "null", "true", "false", "public", "private",
    "protected", "static", "final", "abstract", "synchronized", "volatile", "transient", "native", "void",
    "int", "long", "short", "byte", "char", "float", "double", "boolean", "instanceof", "assert", "var",
    "record", "sealed", "permits",
)

private val CLIKE_KEYWORDS = setOf(
    "if", "else", "for", "while", "do", "switch", "case", "break", "continue", "default", "return",
    "struct", "class", "enum", "union", "typedef", "static", "const", "void", "int", "long", "short",
    "char", "float", "double", "bool", "unsigned", "signed", "new", "delete", "this", "null", "nullptr",
    "nil", "true", "false", "func", "package", "import", "var", "let", "fn", "mut", "pub", "use", "impl",
    "trait", "match", "self", "mod", "crate", "where", "async", "await", "interface", "namespace",
    "using", "public", "private", "protected", "virtual", "override", "abstract", "final", "try", "catch",
    "finally", "throw", "throws", "function", "echo", "foreach", "as", "in", "is", "extends",
    "implements", "defer", "go", "chan", "select", "range", "type", "guard", "protocol", "extension",
)

private val SHELL_KEYWORDS = setOf(
    "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case", "esac", "in",
    "function", "select", "time", "return", "exit", "break", "continue", "export", "local", "readonly",
    "unset", "echo", "cd", "source", "alias", "set", "shift", "test", "true", "false",
)

private val SQL_KEYWORDS = setOf(
    "select", "from", "where", "and", "or", "not", "null", "insert", "into", "values", "update", "set",
    "delete", "create", "table", "drop", "alter", "add", "column", "primary", "key", "foreign",
    "references", "index", "unique", "join", "inner", "left", "right", "outer", "full", "cross", "on",
    "as", "group", "by", "order", "having", "limit", "offset", "distinct", "union", "all", "case", "when",
    "then", "else", "end", "in", "is", "like", "between", "exists", "count", "sum", "avg", "min", "max",
    "asc", "desc", "default", "check", "constraint", "view", "begin", "commit", "rollback", "transaction",
    "true", "false",
)

private val SLASH_COMMENT = arrayOf("//")

private val JS_SPEC = CodeSpec(JS_KEYWORDS, SLASH_COMMENT, true, "\"'`")
private val TS_SPEC = CodeSpec(TS_KEYWORDS, SLASH_COMMENT, true, "\"'`", typesAreTags = true)
private val PYTHON_SPEC = CodeSpec(PYTHON_KEYWORDS, arrayOf("#"), false, "\"'", tripleQuotes = true)
private val KOTLIN_SPEC = CodeSpec(KOTLIN_KEYWORDS, SLASH_COMMENT, true, "\"'", tripleQuotes = true, typesAreTags = true)
private val JAVA_SPEC = CodeSpec(JAVA_KEYWORDS, SLASH_COMMENT, true, "\"'", tripleQuotes = true, typesAreTags = true)
private val CLIKE_SPEC = CodeSpec(
    CLIKE_KEYWORDS, SLASH_COMMENT, true, "\"'`", typesAreTags = true, lifetimeAwareApostrophe = true,
)
private val SHELL_SPEC = CodeSpec(SHELL_KEYWORDS, arrayOf("#"), false, "\"'", commentNeedsSpace = true)
private val SQL_SPEC = CodeSpec(SQL_KEYWORDS, arrayOf("--"), true, "'\"", ignoreCase = true)

private fun specFor(language: CoderLanguage): CodeSpec = when (language) {
    CoderLanguage.TypeScript -> TS_SPEC
    CoderLanguage.Python -> PYTHON_SPEC
    CoderLanguage.Kotlin -> KOTLIN_SPEC
    CoderLanguage.Java -> JAVA_SPEC
    CoderLanguage.CLike -> CLIKE_SPEC
    CoderLanguage.Shell -> SHELL_SPEC
    CoderLanguage.Sql -> SQL_SPEC
    else -> JS_SPEC
}

private fun scanCode(text: String, spec: CodeSpec, emit: Sink) {
    val n = text.length
    var i = 0
    while (i < n) {
        val c = text[i]
        if (isLineComment(text, i, spec)) {
            val end = lineEnd(text, i)
            emit(i, end, TokenKind.Comment)
            i = end
        } else if (spec.blockComments && c == '/' && i + 1 < n && text[i + 1] == '*') {
            val close = text.indexOf("*/", i + 2)
            val end = if (close < 0) n else close + 2
            emit(i, end, TokenKind.Comment)
            i = end
        } else if (spec.quotes.indexOf(c) >= 0 && opensString(text, i, c, spec)) {
            val end = codeStringEnd(text, i, c, spec)
            emit(i, end, TokenKind.StringLiteral)
            i = end
        } else if (c.isDigit()) {
            var j = i + 1
            while (j < n && isNumberPart(text, j)) j++
            emit(i, j, TokenKind.Number)
            i = j
        } else if (isIdentStart(c)) {
            var j = i + 1
            while (j < n && isIdentPart(text[j])) j++
            val kind = identifierKind(text, i, j, spec)
            if (kind != null) emit(i, j, kind)
            i = j
        } else {
            i++
        }
    }
}

private fun isLineComment(text: String, i: Int, spec: CodeSpec): Boolean {
    for (marker in spec.lineComments) {
        if (text.startsWith(marker, i)) {
            if (spec.commentNeedsSpace && i > 0 && !text[i - 1].isWhitespace()) return false
            return true
        }
    }
    return false
}

private fun isNumberPart(text: String, j: Int): Boolean {
    val ch = text[j]
    if (ch.isLetterOrDigit() || ch == '_') return true
    return ch == '.' && j + 1 < text.length && text[j + 1].isDigit()
}

/** A quote opens a string, except a lifetime such as `'a` in Rust-like languages. */
private fun opensString(text: String, i: Int, quote: Char, spec: CodeSpec): Boolean {
    if (quote == '\'' && spec.lifetimeAwareApostrophe) {
        return i + 2 < text.length && (text[i + 2] == '\'' || text[i + 1] == '\\')
    }
    return true
}

private fun codeStringEnd(text: String, start: Int, quote: Char, spec: CodeSpec): Int {
    if (spec.tripleQuotes && (quote == '"' || quote == '\'')) {
        val triple = if (quote == '"') "\"\"\"" else "'''"
        if (text.startsWith(triple, start)) {
            val close = text.indexOf(triple, start + 3)
            return if (close < 0) text.length else close + 3
        }
    }
    return quotedEnd(text, start, quote, quote == '`')
}

private fun identifierKind(text: String, start: Int, end: Int, spec: CodeSpec): TokenKind? {
    val length = end - start
    if (length <= MAX_KEYWORD_LENGTH) {
        val word = text.substring(start, end)
        val key = if (spec.ignoreCase) word.lowercase() else word
        if (key in spec.keywords) return TokenKind.Keyword
    }
    if (spec.typesAreTags && length > 1 && text[start].isUpperCase()) return TokenKind.Tag
    return null
}

// ---- JSON ----------------------------------------------------------------------------------

private fun scanJson(text: String, emit: Sink) {
    val n = text.length
    var i = 0
    while (i < n) {
        val c = text[i]
        if (c == '"') {
            val end = quotedEnd(text, i, '"', false)
            emit(i, end, if (isFollowedByColon(text, end)) TokenKind.Attribute else TokenKind.StringLiteral)
            i = end
        } else if (c.isDigit() || (c == '-' && i + 1 < n && text[i + 1].isDigit())) {
            var j = i + 1
            while (j < n && (text[j].isDigit() || text[j] == '.' || text[j] == 'e' || text[j] == 'E' || text[j] == '+' || text[j] == '-')) j++
            emit(i, j, TokenKind.Number)
            i = j
        } else if (c.isLetter()) {
            var j = i + 1
            while (j < n && text[j].isLetter()) j++
            if (j - i <= 5) {
                val word = text.substring(i, j)
                if (word == "true" || word == "false" || word == "null") emit(i, j, TokenKind.Keyword)
            }
            i = j
        } else {
            i++
        }
    }
}

private fun isFollowedByColon(text: String, from: Int): Boolean {
    var k = from
    while (k < text.length && (text[k] == ' ' || text[k] == '\t' || text[k] == '\n' || text[k] == '\r')) k++
    return k < text.length && text[k] == ':'
}

// ---- HTML ----------------------------------------------------------------------------------

private fun scanHtml(text: String, emit: Sink) {
    val n = text.length
    var i = 0
    while (i < n) {
        if (text[i] != '<') {
            i++
            continue
        }
        if (text.startsWith("<!--", i)) {
            val close = text.indexOf("-->", i + 4)
            val end = if (close < 0) n else close + 3
            emit(i, end, TokenKind.Comment)
            i = end
            continue
        }
        val rawTag = rawTextTagName(text, i)
        val end = scanHtmlTag(text, i, emit)
        i = end
        if (rawTag != null && end >= 2 && text[end - 1] == '>' && text[end - 2] != '/') {
            // The content of <script> and <style> is left uncolored on purpose.
            val close = text.indexOf("</$rawTag", end, ignoreCase = true)
            i = if (close < 0) n else close
        }
    }
}

/** "script" or "style" when an opening tag of that name starts at [i]; otherwise null. */
private fun rawTextTagName(text: String, i: Int): String? {
    for (name in arrayOf("script", "style")) {
        if (text.startsWith("<$name", i, ignoreCase = true)) {
            val after = i + 1 + name.length
            if (after >= text.length) return name
            val next = text[after]
            if (next == '>' || next == '/' || next.isWhitespace()) return name
        }
    }
    return null
}

/** Colors one tag that starts at [start] and returns the index after it. A lone "<" is left alone. */
private fun scanHtmlTag(text: String, start: Int, emit: Sink): Int {
    val n = text.length
    var k = start + 1
    if (k >= n) return n
    val first = text[k]
    if (first == '!' || first == '?') {
        while (k < n && text[k] != '>' && text[k] != '\n') k++
        val end = if (k < n && text[k] == '>') k + 1 else k
        emit(start, end, TokenKind.Tag)
        return end
    }
    if (first == '/') k++
    if (k >= n || !text[k].isLetter()) return start + 1
    while (k < n && (text[k].isLetterOrDigit() || text[k] == '-' || text[k] == ':' || text[k] == '.' || text[k] == '_')) k++
    emit(start, k, TokenKind.Tag)
    while (k < n) {
        val ch = text[k]
        if (ch == '>') {
            emit(k, k + 1, TokenKind.Tag)
            return k + 1
        }
        if (ch == '/' && k + 1 < n && text[k + 1] == '>') {
            emit(k, k + 2, TokenKind.Tag)
            return k + 2
        }
        if (ch == '<' && k + 1 < n && (text[k + 1].isLetter() || text[k + 1] == '/' || text[k + 1] == '!')) {
            return k
        }
        if (ch == '"' || ch == '\'') {
            val end = quotedEnd(text, k, ch, false)
            emit(k, end, TokenKind.StringLiteral)
            k = end
        } else if (ch.isLetter() || ch == '_' || ch == '@' || ch == ':') {
            var m = k + 1
            while (m < n && (text[m].isLetterOrDigit() || text[m] == '-' || text[m] == '_' || text[m] == ':' || text[m] == '.' || text[m] == '@')) m++
            emit(k, m, TokenKind.Attribute)
            k = m
        } else {
            k++
        }
    }
    return n
}

// ---- CSS -----------------------------------------------------------------------------------

private val CSS_CONTAINER_RULES = setOf(
    "media", "supports", "layer", "container", "document", "keyframes", "-webkit-keyframes",
    "-moz-keyframes", "scope", "starting-style",
)

private fun scanCss(text: String, emit: Sink) {
    val n = text.length
    var i = 0
    val declarationBlocks = ArrayList<Boolean>()
    var inPrelude = false
    var preludeIsContainer = false
    var inValue = false
    while (i < n) {
        val c = text[i]
        if (c == '/' && i + 1 < n && text[i + 1] == '*') {
            val close = text.indexOf("*/", i + 2)
            val end = if (close < 0) n else close + 2
            emit(i, end, TokenKind.Comment)
            i = end
        } else if (c == '"' || c == '\'') {
            val end = quotedEnd(text, i, c, false)
            emit(i, end, TokenKind.StringLiteral)
            i = end
        } else if (c == '{') {
            declarationBlocks.add(!(inPrelude && preludeIsContainer))
            inPrelude = false
            preludeIsContainer = false
            inValue = false
            i++
        } else if (c == '}') {
            if (declarationBlocks.isNotEmpty()) declarationBlocks.removeAt(declarationBlocks.size - 1)
            inPrelude = false
            preludeIsContainer = false
            inValue = false
            i++
        } else if (c == ';') {
            inPrelude = false
            preludeIsContainer = false
            inValue = false
            i++
        } else if (c == '@') {
            var j = i + 1
            while (j < n && (text[j].isLetter() || text[j] == '-')) j++
            emit(i, j, TokenKind.Keyword)
            inPrelude = true
            preludeIsContainer = text.substring(i + 1, j).lowercase() in CSS_CONTAINER_RULES
            i = j
        } else {
            val inDeclarations = declarationBlocks.isNotEmpty() && declarationBlocks[declarationBlocks.size - 1]
            if (inPrelude) {
                i = if (c.isDigit()) scanCssNumber(text, i, emit) else i + 1
            } else if (inDeclarations) {
                if (inValue) {
                    i = scanCssValueChar(text, i, emit)
                } else if (c.isLetter() || c == '-' || c == '_') {
                    var j = i + 1
                    while (j < n && (text[j].isLetterOrDigit() || text[j] == '-' || text[j] == '_')) j++
                    var k = j
                    while (k < n && (text[k] == ' ' || text[k] == '\t')) k++
                    if (k < n && text[k] == ':') {
                        emit(i, j, TokenKind.Attribute)
                        inValue = true
                    }
                    i = j
                } else {
                    i++
                }
            } else if (c.isWhitespace() || c == ',') {
                i++
            } else {
                var j = i + 1
                while (j < n && !isCssSelectorStop(text, j)) j++
                emit(i, j, TokenKind.Tag)
                i = j
            }
        }
    }
}

private fun isCssSelectorStop(text: String, j: Int): Boolean {
    val ch = text[j]
    return ch.isWhitespace() || ch == ',' || ch == '{' || ch == '}' || ch == ';' || ch == '"' || ch == '\'' ||
        (ch == '/' && j + 1 < text.length && text[j + 1] == '*')
}

private fun scanCssNumber(text: String, i: Int, emit: Sink): Int {
    val n = text.length
    var j = i + 1
    while (j < n && (text[j].isDigit() || text[j] == '.')) j++
    while (j < n && (text[j].isLetter() || text[j] == '%')) j++
    emit(i, j, TokenKind.Number)
    return j
}

/** Colors what starts at [i] inside a CSS value and returns the next index to look at. */
private fun scanCssValueChar(text: String, i: Int, emit: Sink): Int {
    val n = text.length
    val c = text[i]
    if (c == '#') {
        var j = i + 1
        while (j < n && isHexDigit(text[j])) j++
        if (j - i in 4..9) {
            emit(i, j, TokenKind.Number)
            return j
        }
        return i + 1
    }
    if (c == '!' && text.startsWith("!important", i)) {
        emit(i, i + 10, TokenKind.Keyword)
        return i + 10
    }
    val previousIsWord = i > 0 && (text[i - 1].isLetterOrDigit() || text[i - 1] == '-' || text[i - 1] == '_')
    val startsNumber = c.isDigit() ||
        (c == '.' && i + 1 < n && text[i + 1].isDigit()) ||
        ((c == '-' || c == '+') && !previousIsWord && i + 1 < n &&
            (text[i + 1].isDigit() || (text[i + 1] == '.' && i + 2 < n && text[i + 2].isDigit())))
    if (startsNumber) return scanCssNumber(text, i, emit)
    if (c.isLetter() || c == '-' || c == '_') {
        var j = i + 1
        while (j < n && (text[j].isLetterOrDigit() || text[j] == '-' || text[j] == '_')) j++
        return j
    }
    return i + 1
}

// ---- Markdown ------------------------------------------------------------------------------

private fun scanMarkdown(text: String, emit: Sink) {
    val n = text.length
    var i = 0
    var fence = ' '
    while (i < n) {
        val eol = lineEnd(text, i)
        var t = i
        while (t < eol && (text[t] == ' ' || text[t] == '\t')) t++
        if (fence != ' ') {
            if (t + 3 <= eol && text[t] == fence && text[t + 1] == fence && text[t + 2] == fence) {
                emit(t, eol, TokenKind.Comment)
                fence = ' '
            } else if (eol > i) {
                emit(i, eol, TokenKind.StringLiteral)
            }
        } else if (t < eol) {
            val c = text[t]
            if (c == '`' && text.startsWith("```", t)) {
                fence = '`'
                emit(t, eol, TokenKind.Comment)
            } else if (c == '~' && text.startsWith("~~~", t)) {
                fence = '~'
                emit(t, eol, TokenKind.Comment)
            } else if (c == '#' && isMarkdownHeading(text, t, eol)) {
                emit(t, eol, TokenKind.Keyword)
            } else {
                val markerEnd = listMarkerEnd(text, t, eol)
                if (markerEnd > t) emit(t, markerEnd, TokenKind.Keyword)
                scanInlineCode(text, if (markerEnd > t) markerEnd else t, eol, emit)
            }
        }
        i = eol + 1
    }
}

private fun isMarkdownHeading(text: String, t: Int, eol: Int): Boolean {
    var hashes = 0
    while (t + hashes < eol && text[t + hashes] == '#' && hashes < 7) hashes++
    return hashes in 1..6 && (t + hashes == eol || text[t + hashes] == ' ')
}

/** The end of a list marker ("-", "*", "+" or "1.") that starts at [t], or [t] when there is none. */
private fun listMarkerEnd(text: String, t: Int, eol: Int): Int {
    val c = text[t]
    if ((c == '-' || c == '*' || c == '+') && t + 1 < eol && text[t + 1] == ' ') return t + 1
    var j = t
    while (j < eol && text[j].isDigit()) j++
    if (j > t && j + 1 < eol && (text[j] == '.' || text[j] == ')') && text[j + 1] == ' ') return j + 1
    return t
}

private fun scanInlineCode(text: String, from: Int, eol: Int, emit: Sink) {
    var p = from
    while (p < eol) {
        if (text[p] == '`') {
            var q = p + 1
            while (q < eol && text[q] != '`') q++
            if (q < eol) {
                emit(p, q + 1, TokenKind.StringLiteral)
                p = q + 1
            } else {
                p++
            }
        } else {
            p++
        }
    }
}

// ---- YAML ----------------------------------------------------------------------------------

private fun scanYaml(text: String, emit: Sink) {
    val n = text.length
    var i = 0
    while (i < n) {
        val eol = lineEnd(text, i)
        scanYamlLine(text, i, eol, emit)
        i = eol + 1
    }
}

private fun scanYamlLine(text: String, start: Int, end: Int, emit: Sink) {
    var p = start
    while (p < end && (text[p] == ' ' || text[p] == '\t')) p++
    if (p >= end) return
    if (text[p] == '#') {
        emit(p, end, TokenKind.Comment)
        return
    }
    while (p < end && text[p] == '-' && (p + 1 >= end || text[p + 1] == ' ')) {
        p++
        while (p < end && text[p] == ' ') p++
    }
    if (p >= end) return
    val keyEnd = yamlKeyEnd(text, p, end)
    if (keyEnd > p) {
        emit(p, keyEnd, TokenKind.Attribute)
        p = keyEnd + 1
    }
    scanYamlValue(text, p, end, emit)
}

/** The end of a plain "key:" that starts at [p], or [p] when this is not a key. */
private fun yamlKeyEnd(text: String, p: Int, end: Int): Int {
    var k = p
    while (k < end) {
        val ch = text[k]
        if (ch == ':' && (k + 1 >= end || text[k + 1] == ' ')) return k
        if (ch == '"' || ch == '\'') return p
        if (ch == '#' && k > p && text[k - 1] == ' ') return p
        k++
    }
    return p
}

private fun scanYamlValue(text: String, from: Int, end: Int, emit: Sink) {
    var q = from
    while (q < end) {
        val ch = text[q]
        if (ch == ' ' || ch == '\t' || ch == ',' || ch == '[' || ch == ']' || ch == '{' || ch == '}' || ch == ':') {
            q++
        } else if (ch == '#' && (q == from || text[q - 1] == ' ')) {
            emit(q, end, TokenKind.Comment)
            return
        } else if (ch == '"' || ch == '\'') {
            val stringEnd = quotedEnd(text, q, ch, false)
            emit(q, stringEnd, TokenKind.StringLiteral)
            q = stringEnd
        } else {
            var e = q + 1
            while (e < end && text[e] != ' ' && text[e] != '\t' && text[e] != ',' && text[e] != ']' && text[e] != '}') e++
            val kind = yamlTokenKind(text, q, e)
            if (kind != null) emit(q, e, kind)
            q = e
        }
    }
}

private fun yamlTokenKind(text: String, start: Int, end: Int): TokenKind? {
    if (end - start > MAX_KEYWORD_LENGTH) return null
    val token = text.substring(start, end)
    if (token == "true" || token == "false" || token == "null" || token == "yes" || token == "no" || token == "~") {
        return TokenKind.Keyword
    }
    val numeric = token.all { it.isDigit() || it == '.' || it == '_' || it == '-' || it == '+' }
    return if (numeric && token.any { it.isDigit() }) TokenKind.Number else null
}
