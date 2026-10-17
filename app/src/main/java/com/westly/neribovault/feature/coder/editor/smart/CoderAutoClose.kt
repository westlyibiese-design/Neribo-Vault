package com.westly.neribovault.feature.coder.editor.smart

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.westly.neribovault.feature.coder.editor.CoderLanguage

private const val INDENT = "  "
private const val LOOK_BACK = 2_000

/**
 * The well-known auto-close behavior: brackets and quotes close themselves, a closing character that is
 * already there is stepped over, Backspace between an empty pair removes both, Enter between brackets makes
 * an indented block, and HTML tags close themselves. Pure logic with no state.
 */
object CoderAutoClose {

    /**
     * Returns [after] adjusted by the auto-close rules, or null when nothing special applies. [after] must be
     * [before] plus exactly one typed character at the caret (or minus exactly one character before the caret
     * for Backspace), with nothing selected and no IME composition; anything else, such as paste, returns null.
     * Nothing happens inside comments.
     */
    fun apply(before: TextFieldValue, after: TextFieldValue, language: CoderLanguage): TextFieldValue? {
        if (!before.selection.collapsed || !after.selection.collapsed || after.composition != null) return null
        val old = before.text
        val updated = after.text
        val caret = before.selection.start
        if (caret < 0 || caret > old.length) return null
        val result: TextFieldValue? = if (updated.length == old.length + 1) {
            typed(old, updated, caret, after.selection.start, language)
        } else if (updated.length == old.length - 1) {
            deleted(old, updated, caret, after.selection.start, language)
        } else {
            null
        }
        if (result == null || !isSafeCaret(result)) return null
        return result
    }

    private fun isSafeCaret(value: TextFieldValue): Boolean {
        val text = value.text
        val caret = value.selection.start
        if (caret < 0 || caret > text.length) return false
        return !(caret > 0 && caret < text.length && text[caret - 1].isHighSurrogate() && text[caret].isLowSurrogate())
    }

    /** Only round and square brackets are closed in Markdown and plain text, and never quotes. */
    private fun isTextLike(language: CoderLanguage): Boolean =
        language == CoderLanguage.Markdown || language == CoderLanguage.Plain

    private fun isTagLanguage(language: CoderLanguage): Boolean =
        language == CoderLanguage.Html || language == CoderLanguage.JavaScript || language == CoderLanguage.TypeScript

    private fun typed(old: String, updated: String, caret: Int, caretAfter: Int, language: CoderLanguage): TextFieldValue? {
        if (caretAfter != caret + 1) return null
        if (!updated.regionMatches(0, old, 0, caret)) return null
        if (!updated.regionMatches(caret + 1, old, caret, old.length - caret)) return null
        if (CoderSmartScan.inComment(old, caret, language)) return null
        val c = updated[caret]
        val previous: Char? = if (caret > 0) old[caret - 1] else null
        val next: Char? = if (caret < old.length) old[caret] else null
        val textLike = isTextLike(language)
        return when (c) {
            '(', '[', '{' -> openBracket(updated, caret, c, next, textLike)
            ')', ']', '}' -> if (textLike && c == '}') null else stepOver(old, caret, c, next)
            '"', '\'', '`' -> if (textLike) null else quote(old, updated, caret, c, previous, next)
            '>' -> closeOpenedTag(old, updated, caret, language)
            '/' -> closeAfterSlash(old, updated, caret, previous, next, language)
            '\n' -> enterBetweenPair(old, caret, textLike)
            else -> null
        }
    }

    private fun insertAfter(updated: String, at: Int, extra: String): TextFieldValue =
        TextFieldValue(updated.substring(0, at) + extra + updated.substring(at), TextRange(at))

    private fun closerOf(open: Char): String = when (open) {
        '(' -> ")"
        '[' -> "]"
        else -> "}"
    }

    private fun openBracket(updated: String, caret: Int, c: Char, next: Char?, textLike: Boolean): TextFieldValue? {
        if (textLike && c == '{') return null
        val roomForCloser = next == null || next.isWhitespace() || next == ')' || next == ']' || next == '}'
        if (!roomForCloser) return null
        return insertAfter(updated, caret + 1, closerOf(c))
    }

    /** Typing a closing character that is already next just moves the caret over it. */
    private fun stepOver(old: String, caret: Int, c: Char, next: Char?): TextFieldValue? {
        if (next != c) return null
        return TextFieldValue(old, TextRange(caret + 1))
    }

    private fun quote(old: String, updated: String, caret: Int, c: Char, previous: Char?, next: Char?): TextFieldValue? {
        if (next == c) return stepOver(old, caret, c, next)
        if (previous != null && (previous.isLetterOrDigit() || previous == c || previous == '\\')) return null
        return insertAfter(updated, caret + 1, c.toString())
    }

    /** Whether the `<` at [lt] can start a tag in code: not right after a name, a bracket (a comparison or a type). */
    private fun ltStartsTag(old: String, lt: Int, language: CoderLanguage): Boolean {
        if (language == CoderLanguage.Html) return true
        if (lt == 0) return true
        val before = old[lt - 1]
        return !(before.isLetterOrDigit() || before == '_' || before == '$' || before == ')' || before == ']')
    }

    private fun closeOpenedTag(old: String, updated: String, caret: Int, language: CoderLanguage): TextFieldValue? {
        if (!isTagLanguage(language)) return null
        val code = language != CoderLanguage.Html
        val floor = (caret - LOOK_BACK).coerceAtLeast(0)
        var lt = caret - 1
        while (lt >= floor && old[lt] != '<') {
            if (old[lt] == '>') return null
            lt--
        }
        if (lt < floor || lt + 1 >= caret || !old[lt + 1].isLetter()) return null
        var nameEnd = lt + 1
        while (nameEnd < caret && (old[nameEnd].isLetterOrDigit() || old[nameEnd] == '-' || old[nameEnd] == '_' || old[nameEnd] == ':' || old[nameEnd] == '.')) nameEnd++
        val name = old.substring(lt + 1, nameEnd)
        var quote = ' '
        var braces = 0
        var i = nameEnd
        while (i < caret) {
            val ch = old[i]
            if (quote != ' ') {
                if (ch == quote) quote = ' '
            } else if (ch == '"' || ch == '\'') {
                quote = ch
            } else if (code && ch == '{') {
                braces++
            } else if (code && ch == '}' && braces > 0) {
                braces--
            }
            i++
        }
        if (quote != ' ' || braces > 0) return null
        if (old[caret - 1] == '/') return null
        if (name.lowercase() in SMART_HTML_VOID_TAGS) return null
        if (code) {
            if (!ltStartsTag(old, lt, language)) return null
            if (name.lowercase() !in SMART_HTML_TAG_NAMES && !name[0].isUpperCase()) return null
        }
        if (updated.startsWith("</$name", caret + 1)) return null
        return insertAfter(updated, caret + 1, "</$name>")
    }

    /** Typing `/` right after `<` writes the name of the nearest open tag and `>`. */
    private fun closeAfterSlash(
        old: String,
        updated: String,
        caret: Int,
        previous: Char?,
        next: Char?,
        language: CoderLanguage,
    ): TextFieldValue? {
        if (!isTagLanguage(language) || previous != '<') return null
        if (language != CoderLanguage.Html && !ltStartsTag(old, caret - 1, language)) return null
        val name = CoderSmartScan.nearestUnclosedTag(old, caret - 1) ?: return null
        val extra = if (next == '>') name else "$name>"
        val text = updated.substring(0, caret + 1) + extra + updated.substring(caret + 1)
        return TextFieldValue(text, TextRange(caret + 1 + extra.length))
    }

    /** Enter between `{}`, `[]` or `()`: an indented line for the caret and the closer on its own line. */
    private fun enterBetweenPair(old: String, caret: Int, textLike: Boolean): TextFieldValue? {
        if (caret == 0 || caret >= old.length) return null
        val open = old[caret - 1]
        val close = old[caret]
        val isPair = (open == '(' && close == ')') || (open == '[' && close == ']') || (open == '{' && close == '}' && !textLike)
        if (!isPair) return null
        val indent = CoderSmartScan.lineIndent(old, caret - 1)
        val inner = indent + INDENT
        val text = old.substring(0, caret) + "\n" + inner + "\n" + indent + old.substring(caret)
        return TextFieldValue(text, TextRange(caret + 1 + inner.length))
    }

    private fun deleted(old: String, updated: String, caret: Int, caretAfter: Int, language: CoderLanguage): TextFieldValue? {
        if (caret < 1 || caret >= old.length || caretAfter != caret - 1) return null
        if (!updated.regionMatches(0, old, 0, caretAfter)) return null
        if (!updated.regionMatches(caretAfter, old, caret, old.length - caret)) return null
        val open = old[caret - 1]
        val close = old[caret]
        val brackets = (open == '(' && close == ')') || (open == '[' && close == ']')
        val curly = open == '{' && close == '}'
        val quotes = (open == '"' && close == '"') || (open == '\'' && close == '\'') || (open == '`' && close == '`')
        val textLike = isTextLike(language)
        if (!(brackets || (!textLike && (curly || quotes)))) return null
        if (CoderSmartScan.inComment(old, caret - 1, language)) return null
        return TextFieldValue(old.substring(0, caret - 1) + old.substring(caret + 1), TextRange(caret - 1))
    }
}
