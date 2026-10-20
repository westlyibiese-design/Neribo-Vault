package com.westly.neribovault.feature.coder.buildflow

/**
 * Turns the last lines of a failed build log into one calm sentence a beginner can act on. Pure. The most specific
 * patterns come first, because a failed npm build always ends with a general "script failed" line.
 */
object CoderBuildErrorExplainer {
    private class Rule(val matches: (String) -> Boolean, val sentence: String)

    private val SH_NOT_FOUND = Regex("sh: \\d+: [\\w.@/-]+: not found", RegexOption.IGNORE_CASE)
    private val ENOENT_PACKAGE = Regex("ENOENT[^\\n]*package\\.json", RegexOption.IGNORE_CASE)

    private val rules: List<Rule> = listOf(
        Rule(
            { it.contains("missing script: build", ignoreCase = true) },
            "Your package.json has no \"build\" script. Add one under \"scripts\", for example \"build\": \"vite build\".",
        ),
        Rule(
            { ENOENT_PACKAGE.containsMatchIn(it) },
            "GitHub couldn't find package.json. It must be at the top of your project.",
        ),
        Rule(
            { it.contains("Module not found: Error: Can't resolve", ignoreCase = true) },
            "The build can't find a file or package that your code imports. Check the spelling and that the package is in package.json.",
        ),
        Rule(
            { it.contains("Cannot find module", ignoreCase = true) },
            "A file or package your code asks for can't be found. Check its name and that it is listed in package.json.",
        ),
        Rule(
            { it.contains("ERESOLVE", ignoreCase = true) || it.contains("peer dep", ignoreCase = true) },
            "Two packages need different versions of each other. Ask the AI to help you fix the versions in package.json.",
        ),
        Rule(
            { it.contains("EBADENGINE", ignoreCase = true) },
            "A package needs a different Node version than the build uses. You can set it in package.json under \"engines\".",
        ),
        Rule(
            { it.contains("JavaScript heap out of memory", ignoreCase = true) },
            "The build ran out of memory. The project may be too big for GitHub's free build machine.",
        ),
        Rule(
            { it.contains("Treating warnings as errors", ignoreCase = true) },
            "The build treats warnings as errors. Fix the warnings above, or turn that setting off.",
        ),
        Rule(
            { it.contains("error TS") },
            "TypeScript found a mistake in your code. The lines above say which file and line.",
        ),
        Rule(
            { it.contains("SyntaxError", ignoreCase = true) },
            "There is a typing mistake in one of your code files, such as a missing bracket or quote.",
        ),
        Rule(
            { it.contains("vite: not found", ignoreCase = true) || it.contains("command not found", ignoreCase = true) || SH_NOT_FOUND.containsMatchIn(it) },
            "The build tool isn't installed. Check that it is listed in package.json under dependencies or devDependencies.",
        ),
        Rule(
            { it.contains("ELIFECYCLE", ignoreCase = true) },
            "A script in your project stopped with an error. The lines above it say why.",
        ),
    )

    /** One plain sentence for [logTail], or null when nothing in it is recognised. */
    fun explain(logTail: String): String? = rules.firstOrNull { it.matches(logTail) }?.sentence
}
