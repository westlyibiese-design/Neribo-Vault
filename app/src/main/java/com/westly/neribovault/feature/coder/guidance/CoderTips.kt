package com.westly.neribovault.feature.coder.guidance

/** The screens that can show a tip. A tip belongs to exactly one of them. */
enum class TipScreen { Home, Files, Editor, Preview, Ai, GitHub }

/**
 * What is going on right now, as far as a tip cares. Screens fill in the facts they know; [CoderTipHost] adds
 * the clock, the keyboard and the typing signals. A field a screen does not know keeps its quiet default.
 *
 * [lastTypedAtMillis] is the time (on the `SystemClock.elapsedRealtime` clock) of the last change to the
 * open file; 0 means no change yet. [busy] is true while the AI is answering, a dialog or sheet is open, or the
 * preview is loading.
 */
data class TipContext(
    val screen: TipScreen,
    val nowMillis: Long = 0L,
    val lastTypedAtMillis: Long = 0L,
    val keyboardVisible: Boolean = false,
    val busy: Boolean = false,
    val welcomeVisible: Boolean = false,
    val projectListShown: Boolean = false,
    val fileListShown: Boolean = false,
    val fileOpen: Boolean = false,
    val keyboardSeenInEditor: Boolean = false,
    val suggestionShownOnce: Boolean = false,
    val tabCount: Int = 0,
    val htmlFileOpen: Boolean = false,
    val typedChars: Int = 0,
    val previewLoaded: Boolean = false,
    val aiPanelIdle: Boolean = false,
)

/** One tip: its [id] (saved when it was seen), the [screen] it belongs to, its [text] and the [trigger] that says when it fits. */
class Tip(
    val id: String,
    val screen: TipScreen,
    val text: String,
    val trigger: (TipContext) -> Boolean,
)

/**
 * Every tip Coder can show, in the order they win when two fit at once. The ids `home`, `workspace`, `preview`
 * and `ai` are the ids of the older hint cards, so a tip somebody already dismissed stays dismissed. Later
 * phases add their own tips here, in the same shape.
 */
object CoderTips {
    /** How many typed characters make the Find tip fit. */
    const val FIND_TIP_CHARS = 200

    /** The tips in priority order. */
    val all: List<Tip> = listOf(
        Tip("home", TipScreen.Home, "Start with a template. Tap New project, pick one, then tap Run to see it.") { it.projectListShown },
        Tip("workspace", TipScreen.Files, "Tap a file to edit it. Tap Run to see your page.") { it.fileListShown },
        Tip("editor-keys", TipScreen.Editor, "The row above the keyboard has the symbols you need most.") {
            it.fileOpen && it.keyboardSeenInEditor
        },
        Tip("editor-suggestions", TipScreen.Editor, "Tap a suggestion to add it. You can switch suggestions off in Editor settings.") {
            it.fileOpen && it.suggestionShownOnce
        },
        Tip("editor-tabs", TipScreen.Editor, "Open files appear as tabs. The menu button shows all your files.") {
            it.fileOpen && it.tabCount >= 2
        },
        Tip("editor-run", TipScreen.Editor, "Tap Run to see this page.") { it.fileOpen && it.htmlFileOpen },
        Tip("preview", TipScreen.Preview, "Something not working? Tap the console icon to see messages and errors.") { it.previewLoaded },
        Tip(
            "ai",
            TipScreen.Ai,
            "Ask in plain words. Nothing changes in your files until you tap Insert or Replace, and you can always undo.",
        ) { it.aiPanelIdle },
        Tip("find", TipScreen.Editor, "Need to change a word everywhere in this file? Tap the search icon.") {
            it.fileOpen && it.typedChars >= FIND_TIP_CHARS
        },
        // Reserved: the GitHub screen explains itself, so this tip never fits. It keeps the shape for later phases.
        Tip("github", TipScreen.GitHub, "") { false },
    )

    /** The tip with [id], or null. */
    fun byId(id: String): Tip? = all.firstOrNull { it.id == id }
}
