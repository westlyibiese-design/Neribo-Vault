package com.westly.neribovault.feature.developer.docs

// A variable looks like {{name}}. Names are letters, digits, "_", "-" and spaces; the spaces
// around the name are trimmed, so {{ error }} and {{error}} are the same variable.
private val VARIABLE_REGEX = Regex("\\{\\{\\s*([\\p{L}\\p{N}_\\- ]+?)\\s*\\}\\}")

/** Every `{{variable}}` in [body]: unique, in the order they first appear, names trimmed. */
fun extractVariables(body: String): List<String> =
    VARIABLE_REGEX.findAll(body)
        .map { match -> match.groupValues[1].trim() }
        .filter { name -> name.isNotEmpty() }
        .distinct()
        .toList()

/**
 * Replaces every occurrence of each variable in [body] with its value from [values]. A variable
 * with no entry is left exactly as written. The stored prompt is never changed; this only builds
 * the text to copy.
 */
fun fillVariables(body: String, values: Map<String, String>): String =
    VARIABLE_REGEX.replace(body) { match ->
        val name = match.groupValues[1].trim()
        values[name] ?: match.value
    }

/** Remembers the last value typed for each variable name until the app process ends. */
internal object PromptFillMemory {
    private val remembered = mutableMapOf<String, String>()

    fun recall(name: String): String? = remembered[name]

    fun store(name: String, value: String) {
        remembered[name] = value
    }
}
