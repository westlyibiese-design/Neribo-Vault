package com.westly.neribovault.feature.coder.guidance

/** One short guide: its [id], its [title] and 4 to 6 numbered [steps], each one short sentence. */
class Guide(val id: String, val title: String, val steps: List<String>)

/**
 * The guides on the Help screen, as plain data. They name only buttons and menus that exist in Coder today;
 * a later phase that adds a feature adds its guide or step here.
 */
object CoderGuides {
    /** Every guide, in the order the Help screen lists them. */
    val all: List<Guide> = listOf(
        Guide(
            "first-page",
            "Make your first page",
            listOf(
                "On the Coder home screen, tap the round plus button.",
                "Pick My first web page and type a name for your project.",
                "Tap Create project.",
                "Tap index.html in the file list to open it.",
                "Change some of the words between the tags.",
                "Tap the Run button, the triangle at the top, to see your page.",
            ),
        ),
        Guide(
            "suggestions",
            "Use suggestions while you type",
            listOf(
                "Open an HTML, CSS or JavaScript file.",
                "Tap in the text and start typing a word, for example but.",
                "Look at the strip just above the row of symbols. Suggestions appear there.",
                "Tap a suggestion to add it to your code.",
                "To switch suggestions off, tap the three dots at the top and choose Editor settings.",
            ),
        ),
        Guide(
            "several-files",
            "Open several files",
            listOf(
                "Tap a file in the file list to open it.",
                "Tap the Files button at the left of the row of tabs.",
                "Tap another file. It opens as a new tab.",
                "Tap a tab to switch to that file.",
                "Tap the x on a tab to close it. Your text is saved first.",
            ),
        ),
        Guide(
            "run",
            "See your page (Run)",
            listOf(
                "Open your project.",
                "Tap the Run button, the triangle at the top. Coder saves your file and shows your page.",
                "Tap the page to try its buttons and links.",
                "If something does not work, tap the console button at the top to read the messages.",
                "Tap Back to return to your code.",
            ),
        ),
        Guide(
            "ai-help",
            "Ask the AI for help",
            listOf(
                "On the Coder home screen, tap the three dots and choose AI settings.",
                "Pick a company, add your own key and tap Save and test.",
                "Open a project and tap the light bulb button at the top.",
                "Type your question in plain words and tap Send.",
                "Nothing changes in your files until you tap a button such as Insert at cursor. You can always undo.",
            ),
        ),
        Guide(
            "github",
            "Save to GitHub",
            listOf(
                "Open a project. On the file list, tap the three dots and choose GitHub.",
                "Under Account, follow the steps to make a token, paste it and tap Connect.",
                "Under Repository, link an existing repository or create a new one.",
                "Under Push, write a short note about your changes.",
                "Tap Commit and push and check the summary.",
            ),
        ),
        Guide(
            "zip",
            "Import or export a ZIP",
            listOf(
                "To bring in a ZIP, tap the three dots on the Coder home screen and choose Import ZIP.",
                "Pick the ZIP file on your phone. It becomes a new project.",
                "To save a project as a ZIP, open it. On the file list, tap the three dots and choose Export as ZIP.",
                "Choose where to save the file.",
            ),
        ),
        Guide(
            "fix-mistake",
            "Fix a mistake",
            listOf(
                "Tap Undo, the curved arrow at the top, to take back your last change. Redo brings it back.",
                "An underline marks something that looks wrong. Tap that spot to read what it means.",
                "Tap Run and look at the console button if your page does not work.",
                "In the console, tap Fix with AI next to an error to ask for help.",
                "Deleted a project by mistake? On the Coder home screen, tap the three dots, choose Recently deleted and tap Restore.",
            ),
        ),
    )
}
