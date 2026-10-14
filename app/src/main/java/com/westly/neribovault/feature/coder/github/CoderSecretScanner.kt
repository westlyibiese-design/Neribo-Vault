package com.westly.neribovault.feature.coder.github

import com.westly.neribovault.feature.coder.files.CoderLimits

/**
 * Looks through text files for things that look like passwords or keys, so the person can be warned
 * before a push puts them on GitHub. It only reports where (file, line) and what kind. The matched text
 * is never copied into a result, so the warning itself cannot leak the secret.
 */
object CoderSecretScanner {
    private const val MAX_FINDINGS = 50
    private const val MAX_LINE_CHARS = 2000

    private class Rule(val kind: String, val regex: Regex)

    private val rules: List<Rule> = listOf(
        Rule("AWS access key", Regex("AKIA[0-9A-Z]{16}")),
        Rule("GitHub token", Regex("gh[pousr]_[A-Za-z0-9]{36,}")),
        Rule("GitHub token", Regex("github_pat_[A-Za-z0-9_]{50,}")),
        Rule("AI or payment key", Regex("(?<![A-Za-z0-9])sk-[A-Za-z0-9_-]{20,}")),
        Rule("Google API key", Regex("AIza[0-9A-Za-z_-]{35}")),
        Rule("private key", Regex("-----BEGIN (RSA |EC |OPENSSH |DSA |PGP )?PRIVATE KEY")),
        Rule("Slack token", Regex("xox[baprs]-[A-Za-z0-9-]{10,}")),
        Rule(
            "possible secret",
            Regex("(api[_-]?key|secret|password|passwd|token)\\s*[:=]\\s*[\"'][^\"'\\s]{12,}[\"']", RegexOption.IGNORE_CASE),
        ),
    )

    /**
     * Scans [files] (path and text pairs). Text over 300,000 characters and text with a NUL character
     * (a binary file) are skipped, and so is any line over 2,000 characters. A line is reported once, for
     * the first kind that matches it. At most 50 findings are returned, in file and line order.
     */
    fun scan(files: List<Pair<String, String>>): List<SecretFinding> {
        val out = ArrayList<SecretFinding>()
        for ((path, text) in files) {
            if (text.length.toLong() > CoderLimits.MAX_EDITABLE_BYTES) continue
            if (text.indexOf('\u0000') >= 0) continue
            var lineNumber = 0
            for (line in text.lineSequence()) {
                lineNumber++
                if (line.length > MAX_LINE_CHARS) continue
                val rule = rules.firstOrNull { it.regex.containsMatchIn(line) } ?: continue
                out.add(SecretFinding(path, lineNumber, rule.kind))
                if (out.size >= MAX_FINDINGS) return out
            }
        }
        return out
    }
}
