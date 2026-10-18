package com.westly.neribovault.feature.coder.guidance

/** One word explained in plain language: its [id] (used by "What is this?"), the [title] shown and the [explanation]. */
class GlossaryTerm(val id: String, val title: String, val explanation: String)

/** The small glossary behind the Help screen and the "What is this?" links. The texts are plain data. */
object CoderGlossary {
    /** Every term, in the order the Help screen lists them. */
    val terms: List<GlossaryTerm> = listOf(
        GlossaryTerm(
            "html",
            "HTML",
            "The language that gives a web page its content: headings, text, buttons and pictures. Its files end in .html.",
        ),
        GlossaryTerm(
            "css",
            "CSS",
            "The language that makes a page look good: colors, sizes, spacing and fonts. Its files end in .css.",
        ),
        GlossaryTerm(
            "javascript",
            "JavaScript",
            "The language that makes a page do things, such as reacting when someone taps a button. Its files end in .js.",
        ),
        GlossaryTerm(
            "react",
            "React",
            "A popular tool for building web pages out of small pieces that you can use again. Its files often end in .jsx.",
        ),
        GlossaryTerm(
            "zip",
            "ZIP",
            "One file that holds many files together. Coder uses it to bring a whole project in and to save one out.",
        ),
        GlossaryTerm(
            "repository",
            "Repository",
            "A project folder kept on GitHub, together with its history. People often say repo for short.",
        ),
        GlossaryTerm(
            "commit",
            "Commit",
            "A saved snapshot of your files with a short note about what changed. GitHub keeps every commit, so you can look back.",
        ),
        GlossaryTerm(
            "push",
            "Push",
            "Sending your latest commit from your phone to GitHub, so your files are kept there too.",
        ),
        GlossaryTerm(
            "token",
            "Token",
            "A long secret code that works like a password just for one app. It lets Coder save files to your GitHub account. " +
                "Coder keeps it locked on this phone and never shows it again.",
        ),
        GlossaryTerm(
            "api-key",
            "API key",
            "A secret code from an AI company that tells it the requests are yours. " +
                "Coder keeps it locked on this phone and only sends it to that company.",
        ),
        GlossaryTerm(
            "console",
            "Console",
            "A list of messages from your page, including errors. When something does not work, look here first.",
        ),
        GlossaryTerm(
            "preview",
            "Preview",
            "Your page shown inside Coder, so you can try it without leaving the app.",
        ),
        GlossaryTerm(
            "build",
            "Build",
            "A step that turns a bigger project into plain files that a browser can open. Simple pages do not need one.",
        ),
        GlossaryTerm(
            "environment-variable",
            "Environment variable",
            "A named value, such as a setting, that a project can read instead of having it written in its code. " +
                "Anything placed in a web page can be seen by the people who visit it, so never put real secrets there.",
        ),
        GlossaryTerm(
            "cors",
            "CORS",
            "A browser rule that decides whether your page may ask another website for data. " +
                "If that website does not allow your page, the request fails and the console shows an error.",
        ),
        GlossaryTerm(
            "dist-folder",
            "Dist folder",
            "The folder, often named dist or build, where a build puts the finished files. Coder can show this folder in the preview.",
        ),
    )

    /** The term with [id], or null when there is none. */
    fun find(id: String): GlossaryTerm? = terms.firstOrNull { it.id == id }
}
