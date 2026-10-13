package com.westly.neribovault.feature.coder.preview

/**
 * A small Markdown-to-HTML converter for the preview. All text is escaped before it is written, so
 * raw HTML in a Markdown file is shown as text and never runs. Links and images only accept web
 * addresses and relative paths. Every search is bounded and nesting is limited, so no input can make
 * it loop forever or throw; anything it does not understand is written out literally.
 */
object CoderMarkdown {
    private const val MAX_SPAN = 400
    private const val MAX_CODE_SPAN = 600
    private const val MAX_URL = 1_000
    private const val MAX_DEPTH = 4

    private const val PAGE_CSS = ":root{color-scheme:light dark}" +
        "body{margin:0 auto;max-width:720px;padding:16px;font-family:system-ui,sans-serif;line-height:1.6;" +
        "background:#faf8f4;color:#2a2a28;overflow-wrap:anywhere}" +
        "a{color:#2f6b55}" +
        "h1,h2,h3,h4,h5,h6{line-height:1.25}" +
        "pre{overflow-x:auto;padding:12px;border-radius:8px;background:#efece5}" +
        "code{font-family:monospace;font-size:.92em;background:#efece5;padding:1px 4px;border-radius:4px}" +
        "pre code{padding:0;background:none}" +
        "blockquote{margin:0 0 1em;padding-left:12px;border-left:3px solid #cfcac0;color:#5c5b56}" +
        "hr{border:0;border-top:1px solid #d9d5cb}" +
        "img{max-width:100%}" +
        ".err{color:#b3261e}" +
        "@media (prefers-color-scheme:dark){" +
        "body{background:#161715;color:#ecebe6}a{color:#8fc4ac}pre,code{background:#242320}" +
        "blockquote{border-left-color:#4a4843;color:#b5b3ab}hr{border-top-color:#3a3935}.err{color:#f2b8b5}}"

    /**
     * Turns [md] into a complete HTML page. When [baseUrl] is given, relative links and images in the
     * text are resolved against it.
     */
    fun toHtml(md: String, baseUrl: String? = null): String {
        val body = try {
            renderBlocks(md)
        } catch (e: Exception) {
            "<pre>" + escape(md) + "</pre>"
        }
        return page("Preview", body, baseUrl)
    }

    /** Wraps [body] (already HTML) in a minimal page with calm light and dark colors. Used by the preview's built-in pages too. */
    internal fun page(title: String, body: String, baseUrl: String? = null): String {
        val base = if (baseUrl != null) "<base href=\"" + escape(baseUrl) + "\">" else ""
        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            base +
            "<title>" + escape(title) + "</title><style>" + PAGE_CSS + "</style></head><body>" +
            body +
            "</body></html>"
    }

    /** Escapes the five characters that matter in HTML text and attributes. */
    internal fun escape(text: String): String {
        val out = StringBuilder(text.length + 16)
        for (ch in text) appendEscaped(out, ch)
        return out.toString()
    }

    private fun appendEscaped(out: StringBuilder, ch: Char) {
        when (ch) {
            '&' -> out.append("&amp;")
            '<' -> out.append("&lt;")
            '>' -> out.append("&gt;")
            '"' -> out.append("&quot;")
            '\'' -> out.append("&#39;")
            else -> out.append(ch)
        }
    }

    // ---- Blocks ----------------------------------------------------------------------------

    private fun renderBlocks(md: String): String {
        val lines = md.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = StringBuilder(md.length + 256)
        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            i = when {
                trimmed.isEmpty() -> i + 1
                isFence(trimmed) && hasClosingFence(lines, i + 1) -> renderFence(lines, i, out)
                headingLevel(trimmed) > 0 -> {
                    renderHeading(trimmed, out)
                    i + 1
                }
                isRule(trimmed) -> {
                    out.append("<hr>\n")
                    i + 1
                }
                trimmed.startsWith(">") -> renderQuote(lines, i, out)
                bulletText(trimmed) != null -> renderList(lines, i, out, ordered = false)
                orderedText(trimmed) != null -> renderList(lines, i, out, ordered = true)
                else -> renderParagraph(lines, i, out)
            }
        }
        return out.toString()
    }

    private fun isFence(trimmed: String): Boolean = trimmed.startsWith("```")

    private fun hasClosingFence(lines: List<String>, from: Int): Boolean {
        for (j in from until lines.size) {
            if (isFence(lines[j].trim())) return true
        }
        return false
    }

    /** Writes a fenced code block and returns the index of the line after its closing fence. */
    private fun renderFence(lines: List<String>, start: Int, out: StringBuilder): Int {
        val body = ArrayList<String>()
        var j = start + 1
        while (j < lines.size && !isFence(lines[j].trim())) {
            body.add(lines[j])
            j++
        }
        out.append("<pre><code>").append(escape(body.joinToString("\n"))).append("</code></pre>\n")
        return j + 1
    }

    private fun headingLevel(trimmed: String): Int {
        var n = 0
        while (n < trimmed.length && trimmed[n] == '#') n++
        return if (n in 1..6 && n < trimmed.length && trimmed[n] == ' ') n else 0
    }

    private fun renderHeading(trimmed: String, out: StringBuilder) {
        val level = headingLevel(trimmed)
        val text = trimmed.substring(level + 1).trim().trimEnd('#').trim()
        out.append("<h").append(level).append('>')
            .append(inline(text, 0))
            .append("</h").append(level).append(">\n")
    }

    private fun isRule(trimmed: String): Boolean = trimmed.length >= 3 && trimmed.all { it == '-' }

    private fun bulletText(trimmed: String): String? {
        if (trimmed.length < 3) return null
        val first = trimmed[0]
        if ((first == '-' || first == '*' || first == '+') && trimmed[1] == ' ') {
            return trimmed.substring(2).trim()
        }
        return null
    }

    private fun orderedText(trimmed: String): String? {
        var d = 0
        while (d < trimmed.length && trimmed[d] in '0'..'9') d++
        if (d < 1 || d > 9 || d + 2 >= trimmed.length) return null
        if (trimmed[d] != '.' || trimmed[d + 1] != ' ') return null
        return trimmed.substring(d + 2).trim()
    }

    private fun renderQuote(lines: List<String>, start: Int, out: StringBuilder): Int {
        val parts = ArrayList<String>()
        var j = start
        while (j < lines.size) {
            val t = lines[j].trim()
            if (!t.startsWith(">")) break
            parts.add(t.substring(1).trimStart())
            j++
        }
        out.append("<blockquote><p>").append(inline(parts.joinToString("\n"), 0)).append("</p></blockquote>\n")
        return j
    }

    private fun renderList(lines: List<String>, start: Int, out: StringBuilder, ordered: Boolean): Int {
        val tag = if (ordered) "ol" else "ul"
        out.append('<').append(tag).append(">\n")
        var j = start
        while (j < lines.size) {
            val t = lines[j].trim()
            val item = (if (ordered) orderedText(t) else bulletText(t)) ?: break
            out.append("<li>").append(inline(item, 0)).append("</li>\n")
            j++
        }
        out.append("</").append(tag).append(">\n")
        return j
    }

    private fun startsBlock(trimmed: String): Boolean =
        trimmed.isEmpty() || isFence(trimmed) || headingLevel(trimmed) > 0 || isRule(trimmed) ||
            trimmed.startsWith(">") || bulletText(trimmed) != null || orderedText(trimmed) != null

    /** Joins lines until a blank line or the start of another block. The first line is always taken, so this always moves forward. */
    private fun renderParagraph(lines: List<String>, start: Int, out: StringBuilder): Int {
        val parts = ArrayList<String>()
        var j = start
        while (j < lines.size) {
            val t = lines[j].trim()
            if (j > start && startsBlock(t)) break
            parts.add(t)
            j++
        }
        out.append("<p>").append(inline(parts.joinToString("\n"), 0)).append("</p>\n")
        return j
    }

    // ---- Inline text -----------------------------------------------------------------------

    /** Escapes [text] and turns code spans, emphasis, links and images into HTML. Constructs that never close stay as plain text. */
    private fun inline(text: String, depth: Int): String {
        if (depth > MAX_DEPTH) return escape(text)
        val out = StringBuilder(text.length + 16)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val used = when (c) {
                '`' -> codeSpan(text, i, out)
                '!' -> image(text, i, out)
                '[' -> link(text, i, out, depth)
                '*', '_' -> emphasis(text, i, out, depth)
                else -> 0
            }
            if (used > 0) {
                i += used
            } else {
                appendEscaped(out, c)
                i++
            }
        }
        return out.toString()
    }

    /** The index of [ch] at or after [from], looking at most [maxSpan] characters ahead; -1 when not found. */
    private fun indexOfBounded(text: String, ch: Char, from: Int, maxSpan: Int): Int {
        val limit = minOf(text.length, from + maxSpan)
        var j = from
        while (j < limit) {
            if (text[j] == ch) return j
            j++
        }
        return -1
    }

    private fun codeSpan(text: String, start: Int, out: StringBuilder): Int {
        val end = indexOfBounded(text, '`', start + 1, MAX_CODE_SPAN)
        if (end <= start + 1) return 0
        out.append("<code>").append(escape(text.substring(start + 1, end))).append("</code>")
        return end - start + 1
    }

    private fun emphasis(text: String, start: Int, out: StringBuilder, depth: Int): Int {
        val mark = text[start]
        val n = text.length
        if (mark == '*' && start + 1 < n && text[start + 1] == '*') {
            if (start + 2 >= n || text[start + 2].isWhitespace()) return 0
            val close = findStrongClose(text, start + 2)
            if (close < 0) return 0
            out.append("<strong>").append(inline(text.substring(start + 2, close), depth + 1)).append("</strong>")
            return close + 2 - start
        }
        if (start + 1 >= n || text[start + 1].isWhitespace()) return 0
        if (mark == '_' && start > 0 && text[start - 1].isLetterOrDigit()) return 0
        val close = findItalicClose(text, mark, start + 1)
        if (close < 0) return 0
        out.append("<em>").append(inline(text.substring(start + 1, close), depth + 1)).append("</em>")
        return close + 1 - start
    }

    private fun findStrongClose(text: String, from: Int): Int {
        val n = text.length
        var j = from + 1
        while (j + 1 < n && j < from + MAX_SPAN) {
            if (text[j] == '*' && text[j + 1] == '*' && !text[j - 1].isWhitespace()) return j
            j++
        }
        return -1
    }

    private fun findItalicClose(text: String, mark: Char, from: Int): Int {
        val n = text.length
        val limit = minOf(n, from + MAX_SPAN)
        var j = from + 1
        while (j < limit) {
            if (text[j] == mark && !text[j - 1].isWhitespace()) {
                if (mark == '*') {
                    val touchesAnotherStar = text[j - 1] == '*' || (j + 1 < n && text[j + 1] == '*')
                    if (!touchesAnotherStar) return j
                } else if (j + 1 >= n || !text[j + 1].isLetterOrDigit()) {
                    return j
                }
            }
            j++
        }
        return -1
    }

    private class LinkParts(val label: String, val url: String, val end: Int)

    /** Reads `[label](url)` with its `[` at [open]. [LinkParts.end] is the index just after the closing parenthesis. */
    private fun parseLink(text: String, open: Int): LinkParts? {
        val close = indexOfBounded(text, ']', open + 1, MAX_SPAN)
        if (close < 0 || close + 1 >= text.length || text[close + 1] != '(') return null
        val urlStart = close + 2
        val urlEnd = findUrlEnd(text, urlStart)
        if (urlEnd < 0) return null
        return LinkParts(text.substring(open + 1, close), text.substring(urlStart, urlEnd), urlEnd + 1)
    }

    /** The index of the parenthesis that closes the address, allowing one level of parentheses inside it; -1 when there is none or a space comes first. */
    private fun findUrlEnd(text: String, from: Int): Int {
        var nesting = 0
        var j = from
        val limit = minOf(text.length, from + MAX_URL)
        while (j < limit) {
            val ch = text[j]
            if (ch == '(') {
                nesting++
            } else if (ch == ')') {
                if (nesting == 0) return j
                nesting--
            } else if (ch.isWhitespace()) {
                return -1
            }
            j++
        }
        return -1
    }

    private fun link(text: String, start: Int, out: StringBuilder, depth: Int): Int {
        val parts = parseLink(text, start) ?: return 0
        val label = inline(parts.label, depth + 1)
        if (!isSafeUrl(parts.url)) {
            out.append(label)
        } else {
            out.append("<a href=\"").append(escape(parts.url)).append("\">")
                .append(if (parts.label.isEmpty()) escape(parts.url) else label)
                .append("</a>")
        }
        return parts.end - start
    }

    private fun image(text: String, start: Int, out: StringBuilder): Int {
        if (start + 1 >= text.length || text[start + 1] != '[') return 0
        val parts = parseLink(text, start + 1) ?: return 0
        if (isSafeUrl(parts.url)) {
            out.append("<img src=\"").append(escape(parts.url)).append("\" alt=\"").append(escape(parts.label)).append("\">")
        } else {
            out.append(escape(parts.label))
        }
        return parts.end - start
    }

    /**
     * True for `https://` and `http://` addresses and for relative paths. Anything that has another
     * scheme (`javascript:`, `data:`, `vbscript:`, `mailto:` ...) is refused, also when spaces or
     * control characters are hidden inside the scheme name, which browsers ignore.
     */
    private fun isSafeUrl(url: String): Boolean {
        val cleaned = StringBuilder()
        for (ch in url) {
            if (ch.code > 32 && ch.code != 127) cleaned.append(ch.lowercaseChar())
        }
        val text = cleaned.toString()
        if (text.isEmpty() || text.startsWith("#")) return false
        val colon = text.indexOf(':')
        var firstSeparator = -1
        for (k in text.indices) {
            val ch = text[k]
            if (ch == '/' || ch == '?' || ch == '#') {
                firstSeparator = k
                break
            }
        }
        val hasScheme = colon > 0 && (firstSeparator < 0 || colon < firstSeparator)
        if (!hasScheme) return true
        return text.startsWith("https://") || text.startsWith("http://")
    }
}
