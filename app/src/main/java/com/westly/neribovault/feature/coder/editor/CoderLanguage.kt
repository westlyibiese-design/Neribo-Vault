package com.westly.neribovault.feature.coder.editor

/** The languages the editor knows how to color. Anything else is [Plain]. */
enum class CoderLanguage {
    Html, Css, JavaScript, TypeScript, Json, Markdown, Python, Kotlin, Java, CLike, Shell, Sql, Yaml, Plain;

    companion object {
        /** The language of a file, chosen by its extension (ignoring upper and lower case). */
        fun fromPath(path: String): CoderLanguage {
            val name = path.substringAfterLast('/')
            val dot = name.lastIndexOf('.')
            if (dot < 0) return Plain
            return when (name.substring(dot + 1).lowercase()) {
                "html", "htm", "xml", "svg" -> Html
                "css", "scss", "less" -> Css
                "js", "mjs", "cjs", "jsx" -> JavaScript
                "ts", "tsx" -> TypeScript
                "json" -> Json
                "md", "markdown" -> Markdown
                "py" -> Python
                "kt", "kts" -> Kotlin
                "java" -> Java
                "c", "h", "cpp", "hpp", "cc", "cs", "go", "rs", "swift", "dart", "php" -> CLike
                "sh", "bash", "zsh" -> Shell
                "sql" -> Sql
                "yml", "yaml" -> Yaml
                else -> Plain
            }
        }
    }
}
