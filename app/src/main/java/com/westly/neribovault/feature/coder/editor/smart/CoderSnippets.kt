package com.westly.neribovault.feature.coder.editor.smart

/**
 * The snippets Coder offers, as plain Kotlin constants. Typing a snippet's trigger offers its body. The caret
 * goes to the first place the person would type. Lines of a body after the first are written for an indent
 * of zero; the suggestion engine adds the indent of the line the snippet is typed on.
 */
object CoderSnippets {
    /** One snippet: [trigger] offers [body]; [caretOffset] is where the caret goes inside [body]. */
    class Snippet(val trigger: String, val detail: String, val body: String, val caretOffset: Int)

    private fun snippet(trigger: String, detail: String, before: String, after: String = ""): Snippet =
        Snippet(trigger, detail, before + after, before.length)

    /** Snippets for HTML files. */
    val HTML: List<Snippet> = listOf(
        snippet(
            "html5",
            "A complete page to start from",
            "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n  <meta charset=\"UTF-8\">\n" +
                "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n  <title>",
            "</title>\n</head>\n<body>\n  \n</body>\n</html>\n",
        ),
        snippet("link:css", "Links a stylesheet", "<link rel=\"stylesheet\" href=\"", "\">"),
        snippet("script:src", "Links a JavaScript file", "<script src=\"", "\"></script>"),
    )

    /** Snippets for CSS files and style blocks. */
    val CSS: List<Snippet> = listOf(
        snippet(
            "flexcenter",
            "Centers things with flex",
            "display: flex;\njustify-content: center;\nalign-items: center;",
        ),
        snippet(
            "reset",
            "Removes the default spacing",
            "* {\n  margin: 0;\n  padding: 0;\n  box-sizing: border-box;\n}\n",
        ),
    )

    /** Snippets for JavaScript and TypeScript files and script blocks. */
    val JAVASCRIPT: List<Snippet> = listOf(
        snippet("log", "Prints a message", "console.log(", ")"),
        snippet("fn", "A function", "function name() {\n  ", "\n}"),
        snippet("af", "An arrow function", "const name = () => {\n  ", "\n};"),
        snippet("for", "A counting loop", "for (let i = 0; i < 10; i++) {\n  ", "\n}"),
        snippet("click", "Reacts to a click", "element.addEventListener(\"click\", () => {\n  ", "\n});"),
        snippet(
            "fetchjson",
            "Gets data from the internet",
            "fetch(\"",
            "\")\n  .then(r => r.json())\n  .then(data => {\n    console.log(data);\n  });",
        ),
    )
}
