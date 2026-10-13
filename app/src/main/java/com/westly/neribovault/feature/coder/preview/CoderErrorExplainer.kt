package com.westly.neribovault.feature.coder.preview

/**
 * Turns common JavaScript and React error messages into one short, calm sentence for beginners.
 * It only reads the message text; it never runs anything.
 */
object CoderErrorExplainer {
    private const val MAX_INPUT = 500
    private const val MAX_NAME = 60

    private val REACT_NOT_DEFINED = Regex("\\b(?:ReactDOM|React) is not defined", RegexOption.IGNORE_CASE)
    private val NO_IMPORTS = Regex(
        "require is not defined|cannot use import statement|unexpected token '?(?:import|export)\\b|failed to resolve module specifier",
        RegexOption.IGNORE_CASE,
    )
    private val READ_NULL = Regex(
        "cannot read propert(?:y|ies) of (?:null|undefined)(?: \\(reading '([^']*)'\\))?",
        RegexOption.IGNORE_CASE,
    )
    private val READ_NULL_OLD = Regex("cannot read property '([^']*)' of (?:null|undefined)", RegexOption.IGNORE_CASE)
    private val SET_NULL = Regex(
        "cannot set propert(?:y|ies) of (?:null|undefined)(?: \\(setting '([^']*)'\\))?",
        RegexOption.IGNORE_CASE,
    )
    private val NOT_FUNCTION = Regex("([\\w$.]+) is not a function", RegexOption.IGNORE_CASE)
    private val NOT_DEFINED = Regex("([\\w$]+) is not defined", RegexOption.IGNORE_CASE)
    private val TYPO = Regex(
        "unexpected token|unexpected end of input|missing \\) after argument list|invalid or unexpected token|" +
            "unexpected identifier|unterminated jsx|adjacent jsx elements|unterminated string",
        RegexOption.IGNORE_CASE,
    )
    private val NETWORK = Regex("failed to fetch|networkerror|net::err_", RegexOption.IGNORE_CASE)
    private val MISSING_FILE = Regex("failed to load resource|status of 404|\\(404\\)|404 \\(not found\\)", RegexOption.IGNORE_CASE)

    /** One friendly sentence for [message], or null when no rule matches. Never throws. */
    fun explain(message: String): String? {
        return try {
            explainShort(message.take(MAX_INPUT))
        } catch (e: Exception) {
            null
        }
    }

    private fun explainShort(text: String): String? {
        val lower = text.lowercase()
        if (REACT_NOT_DEFINED.containsMatchIn(text)) {
            return "React isn't loaded. Check the script tags at the top of index.html."
        }
        if (NO_IMPORTS.containsMatchIn(text)) {
            return "This preview can't use import/export between files. Put the code in one file, or load files with script tags."
        }
        if (lower.contains("objects are not valid as a react child")) {
            return "React was asked to show an object directly. Show one of its values instead, for example {item.name}."
        }
        if (lower.contains("invalid hook call")) {
            return "A React hook was used in the wrong place. Hooks like useState must be used inside a component function."
        }
        if (lower.contains("each child in a list should have a unique") && lower.contains("key")) {
            return "Items in a list need a unique key. Add key={...} to each item."
        }
        READ_NULL.find(text)?.let { return readMessage(it.groupValues[1]) }
        READ_NULL_OLD.find(text)?.let { return readMessage(it.groupValues[1]) }
        SET_NULL.find(text)?.let { match ->
            val name = match.groupValues[1]
            return "The code tried to set something that isn't there" + quoted(name) +
                ". Often an element id doesn't match, or the script runs before the page has loaded."
        }
        NOT_FUNCTION.find(text)?.let { match ->
            return "'" + match.groupValues[1].take(MAX_NAME) + "' was used like a function but it isn't one. Check its spelling and what it holds."
        }
        NOT_DEFINED.find(text)?.let { match ->
            return "The code uses a name ('" + match.groupValues[1].take(MAX_NAME) +
                "') that doesn't exist yet. Check the spelling, or that the file that creates it is loaded first."
        }
        if (TYPO.containsMatchIn(text)) {
            return "There is a typo in the code, such as a missing bracket, quote or comma, near the line shown."
        }
        if (NETWORK.containsMatchIn(text)) {
            return "The page tried to load something from the internet and couldn't. Check the address and your connection."
        }
        if (MISSING_FILE.containsMatchIn(text)) {
            return "A file the page asked for wasn't found. Check the file name and folder."
        }
        return null
    }

    private fun readMessage(name: String): String =
        "The code asked something that isn't there" + (if (name.isEmpty()) "" else " for") + quoted(name) +
            ". Often an element id doesn't match, or the script runs before the page has loaded."

    /** " ('name')" for a non-empty [name], otherwise an empty string. */
    private fun quoted(name: String): String =
        if (name.isEmpty()) "" else " ('" + name.take(MAX_NAME) + "')"
}
