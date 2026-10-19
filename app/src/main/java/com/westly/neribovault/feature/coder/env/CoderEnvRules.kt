package com.westly.neribovault.feature.coder.env

import org.json.JSONObject

/** One environment variable of a project: its [name] and its [value]. The value is never logged. */
data class EnvVar(val name: String, val value: String)

/** What reading a `.env` file found: the [variables] in file order, and how many lines were [skippedLines]. */
data class DotEnvResult(val variables: List<Pair<String, String>>, val skippedLines: Int)

/** The outcome of adding file variables to the ones a project has. */
data class DotEnvMerge(val merged: List<EnvVar>, val added: Int, val overwritten: Int, val notAdded: Int)

/**
 * The pure rules of environment variables: what a name and a value may look like, how a `.env` file is read,
 * and how the two small files the preview serves (`env.js` and `env.json`) are written. Nothing here touches
 * the disk or the network, so the self-test checks every rule on its own.
 */
object CoderEnvRules {
    /** The most variables one project can have. */
    const val MAX_VARIABLES = 50

    /** The longest value, in characters. An empty value is allowed. */
    const val MAX_VALUE_LENGTH = 2_000

    /** The longest name, in characters. */
    const val MAX_NAME_LENGTH = 64

    /** The message for a name GitHub or the build machine uses itself. */
    const val MSG_RESERVED = "That name is reserved."

    private const val MSG_NAME_EMPTY = "Enter a name."
    private const val MSG_NAME_FORM =
        "Use capital letters, digits and underscores, and don't start with a digit. Up to 64 characters."
    private const val MSG_VALUE_LONG = "Keep the value under 2,000 characters."
    private const val MSG_VALUE_CONTROL = "The value can't contain line breaks or other control characters."
    private const val MSG_DUPLICATE = "A variable with this name already exists."
    private const val MSG_TOO_MANY = "You can have up to 50 variables."

    private val NAME_PATTERN = Regex("[A-Z_][A-Z0-9_]{0,63}")

    /** True for a name the build machine or GitHub already uses: `GITHUB_...`, `RUNNER_...`, `CI`, `PATH` and `HOME`. */
    fun isReserved(name: String): Boolean =
        name.startsWith("GITHUB_") || name.startsWith("RUNNER_") || name == "CI" || name == "PATH" || name == "HOME"

    /** Why [name] cannot be used, in plain English, or null when it is fine. */
    fun validateName(name: String): String? = when {
        name.isEmpty() -> MSG_NAME_EMPTY
        !NAME_PATTERN.matches(name) -> MSG_NAME_FORM
        isReserved(name) -> MSG_RESERVED
        else -> null
    }

    /** True when [validateName] has nothing to say. */
    fun isValidName(name: String): Boolean = validateName(name) == null

    /** Why [value] cannot be used, in plain English, or null when it is fine. 0 to 2,000 characters, no control characters. */
    fun validateValue(value: String): String? = when {
        value.length > MAX_VALUE_LENGTH -> MSG_VALUE_LONG
        value.any { it.code < 32 || it.code == 127 } -> MSG_VALUE_CONTROL
        else -> null
    }

    /**
     * What the Name field shows while the person types: capital letters, with spaces and hyphens turned into
     * underscores and every other character that a name cannot hold dropped. At most 64 characters.
     */
    fun suggestName(typed: String): String {
        val out = StringBuilder()
        for (ch in typed.uppercase()) {
            val mapped = if (ch == ' ' || ch == '-') '_' else ch
            if (mapped in 'A'..'Z' || mapped in '0'..'9' || mapped == '_') out.append(mapped)
        }
        return out.toString().take(MAX_NAME_LENGTH)
    }

    /**
     * The first reason that saving [name] and [value] is not allowed, or null. [originalName] is the name of the
     * variable being edited (null when a new one is added), so an edit may keep its own name.
     */
    fun validateForSave(existing: List<EnvVar>, originalName: String?, name: String, value: String): String? {
        validateName(name)?.let { return it }
        validateValue(value)?.let { return it }
        if (existing.any { it.name == name && it.name != originalName }) return MSG_DUPLICATE
        if (originalName == null && existing.none { it.name == name } && existing.size >= MAX_VARIABLES) return MSG_TOO_MANY
        return null
    }

    /**
     * Reads the text of a `.env` file. Understands `KEY=VALUE`, `export KEY=VALUE`, `#` comment lines, blank lines,
     * single and double quotes (the quotes are removed) and inline comments, which only count when the `#` follows a
     * space and the value is not quoted. A line that is none of these, or whose name or value breaks the rules, is
     * counted in [DotEnvResult.skippedLines]. A name that appears twice keeps its last value.
     */
    fun parseDotEnvDetailed(text: String): DotEnvResult {
        val found = LinkedHashMap<String, String>()
        var skipped = 0
        for (rawLine in text.removePrefix("\uFEFF").lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parsed = parseLine(line)
            if (parsed == null) skipped++ else found[parsed.first] = parsed.second
        }
        return DotEnvResult(found.entries.map { it.key to it.value }, skipped)
    }

    /** The variables of [parseDotEnvDetailed], without the count of skipped lines. */
    fun parseDotEnv(text: String): List<Pair<String, String>> = parseDotEnvDetailed(text).variables

    /** The names in [incoming] that [existing] already has, in file order. */
    fun overwrittenNames(existing: List<EnvVar>, incoming: List<Pair<String, String>>): List<String> {
        val have = existing.map { it.name }.toSet()
        return incoming.map { it.first }.filter { it in have }
    }

    /**
     * Puts [incoming] into [existing]: a name that exists gets the new value, a new name is added at the end while
     * the 50-variable limit allows. [DotEnvMerge.notAdded] counts the new names that no longer fit.
     */
    fun merge(existing: List<EnvVar>, incoming: List<Pair<String, String>>): DotEnvMerge {
        val map = LinkedHashMap<String, String>()
        for (variable in existing) map[variable.name] = variable.value
        var added = 0
        var overwritten = 0
        var notAdded = 0
        for ((name, value) in incoming) {
            when {
                map.containsKey(name) -> {
                    map[name] = value
                    overwritten++
                }
                map.size < MAX_VARIABLES -> {
                    map[name] = value
                    added++
                }
                else -> notAdded++
            }
        }
        return DotEnvMerge(map.entries.map { EnvVar(it.key, it.value) }, added, overwritten, notAdded)
    }

    /**
     * The text of `env.js`: `window.__ENV__ = Object.freeze({...});` with names in alphabetical order. Every key and
     * value is written as a JavaScript string by [jsString], so quotes, backslashes, `</script>` and Unicode cannot
     * break out of the string or the page.
     */
    fun generateEnvJs(vars: Map<String, String>): String {
        val body = vars.keys.sorted().joinToString(",") { key -> jsString(key) + ":" + jsString(vars.getValue(key)) }
        return "window.__ENV__ = Object.freeze({$body});"
    }

    /** The text of `env.json`: the same variables as one JSON object, names in alphabetical order. */
    fun generateEnvJson(vars: Map<String, String>): String {
        val obj = JSONObject()
        for (key in vars.keys.sorted()) obj.put(key, vars.getValue(key))
        return obj.toString().replace("\\/", "/")
    }

    /**
     * [text] as a quoted JavaScript string. It starts from `JSONObject.quote`, then writes "/" plainly again (Android
     * escapes every slash) except after "<", where `</` becomes `<\/` so a value can never close a script tag, and
     * `<!--` becomes `<\!--`. The two line separators U+2028 and U+2029 are written as escapes.
     */
    internal fun jsString(text: String): String {
        var quoted = JSONObject.quote(text)
        quoted = quoted.replace("\\/", "/")
        quoted = quoted.replace("</", "<\\/").replace("<!--", "<\\!--")
        quoted = quoted.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        return quoted
    }

    private fun parseLine(line: String): Pair<String, String>? {
        var rest = line
        if (rest.startsWith("export") && rest.length > 6 && rest[6].isWhitespace()) rest = rest.substring(6).trimStart()
        val equals = rest.indexOf('=')
        if (equals <= 0) return null
        val name = rest.substring(0, equals).trim()
        if (validateName(name) != null) return null
        val value = parseValue(rest.substring(equals + 1)) ?: return null
        if (validateValue(value) != null) return null
        return name to value
    }

    /** The value after the "=" sign (spaces still on it), or null when a quote is not closed or something else follows it. */
    private fun parseValue(afterEquals: String): String? {
        val raw = afterEquals.trimStart()
        if (raw.isEmpty()) return ""
        if (raw.startsWith("#")) return if (afterEquals.length > raw.length) "" else raw
        return when (raw[0]) {
            '"' -> parseDoubleQuoted(raw)
            '\'' -> {
                val close = raw.indexOf('\'', 1)
                if (close < 0 || !onlyCommentAfter(raw, close + 1)) null else raw.substring(1, close)
            }
            else -> {
                val comment = raw.indexOf(" #")
                (if (comment >= 0) raw.substring(0, comment) else raw).trim()
            }
        }
    }

    /** A double-quoted value: `\"` is a quote and `\\` is a backslash; any other backslash stays as it is. */
    private fun parseDoubleQuoted(raw: String): String? {
        val out = StringBuilder()
        var i = 1
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '\\' && i + 1 < raw.length && (raw[i + 1] == '"' || raw[i + 1] == '\\')) {
                out.append(raw[i + 1])
                i += 2
                continue
            }
            if (ch == '"') return if (onlyCommentAfter(raw, i + 1)) out.toString() else null
            out.append(ch)
            i++
        }
        return null
    }

    /** True when only spaces, or spaces and then a `#` comment, follow position [from] of [raw]. */
    private fun onlyCommentAfter(raw: String, from: Int): Boolean {
        val rest = raw.substring(from).trim()
        return rest.isEmpty() || rest.startsWith("#")
    }
}
