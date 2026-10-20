package com.westly.neribovault.feature.coder.ai

import com.westly.neribovault.feature.coder.files.CoderLimits
import com.westly.neribovault.feature.coder.files.CoderPaths
import java.util.Locale

private const val MSG_BAD_NAME = "This file name can't be used."
private const val MSG_UNFINISHED = "The AI didn't finish this file, so it can't be applied."
private const val MSG_DUPLICATE = "A later file in this answer has the same name, so this one was skipped."
private const val MSG_TOO_BIG = "This file is bigger than 300 KB."
private const val MSG_TOTAL = "The files together would be bigger than 2 MB."

/** Whether applying a file adds it ([New]) or writes over one that is already in the project ([Replaces]). */
enum class CoderPlanStatus { New, Replaces }

/**
 * One file of a file plan. [reason] is why the file can't be applied (plain English, shown on its row), or null
 * when it can. [unfinished] is true for a block whose closing fence never came. [sizeBytes] is the size the file
 * will have on disk.
 */
data class CoderPlanRow(
    val path: String,
    val language: String,
    val code: String,
    val status: CoderPlanStatus,
    val sizeBytes: Long,
    val unfinished: Boolean,
    val reason: String?,
) {
    /** True when the row can be checked and applied. */
    val canApply: Boolean get() = reason == null
}

/** The limits of one apply: how many files, how many bytes in all and how many bytes in one file. */
data class CoderApplyLimits(
    val maxFiles: Int = CoderApplyPlan.MAX_FILES,
    val maxTotalBytes: Long = CoderApplyPlan.MAX_TOTAL_BYTES,
    val maxFileBytes: Long = CoderLimits.MAX_EDITABLE_BYTES,
)

/** One file to write: its project [path] and its complete new [code]. Whether it is new or replaces a file is found out on the disk. */
data class CoderApplyItem(val path: String, val code: String)

/**
 * The rules of a file plan as pure functions: which files of an answer can be applied together, why a file can't,
 * what is checked by default and what the texts say. Nothing here touches the disk or the screen.
 */
object CoderApplyPlan {
    /** A plan is only offered when the answer has at least this many finished files with different valid paths. */
    const val MIN_FILES = 2

    /** The most files one apply may write. */
    const val MAX_FILES = 20

    /** The most bytes one apply may write in all: 2 MB. */
    const val MAX_TOTAL_BYTES = 2L * 1024L * 1024L

    /**
     * Why the AI may not write [path], or null when it may. Refused: a path that is not valid, anything under a
     * `.coder` folder (the app's own), `.git` and `.github`, and files named `.env` or `.env.something` except
     * `.env.example`. Names are compared ignoring case.
     */
    fun pathRefusal(path: String): String? {
        if (!CoderPaths.isValidPath(path)) return MSG_BAD_NAME
        val first = path.substringBefore('/').lowercase()
        if (first.startsWith(".coder")) return "The AI can't change $path. It belongs to the app."
        if (first == ".git") return "The AI can't change the .git folder."
        if (first == ".github") return "The AI can't change the .github folder."
        val name = CoderPaths.nameOf(path).lowercase()
        if (name == ".env" || (name.startsWith(".env.") && name != ".env.example")) {
            return "The AI can't write $name files. They are for secrets."
        }
        return null
    }

    /**
     * One row for every block of [blocks] that names a path, in order; blocks without a path are not files and get
     * no row. [existingPaths] are the files already in the project: a row for one of them says [CoderPlanStatus.Replaces].
     * A row is refused (with its reason) for: a path [pathRefusal] does not allow, an unfinished block, a path that
     * a later block names again (the last one wins, the earlier ones are skipped), a file bigger than
     * [CoderApplyLimits.maxFileBytes], a file after the [CoderApplyLimits.maxFiles]th one that can be applied, and a
     * file that would take the total past [CoderApplyLimits.maxTotalBytes].
     */
    fun build(
        blocks: List<CoderCodeBlock>,
        existingPaths: Set<String>,
        limits: CoderApplyLimits = CoderApplyLimits(),
    ): List<CoderPlanRow> {
        val named = ArrayList<Pair<String, CoderCodeBlock>>()
        for (block in blocks) {
            val path = block.path ?: continue
            named.add(path to block)
        }
        val lastIndex = HashMap<String, Int>()
        named.forEachIndexed { index, pair -> lastIndex[pair.first] = index }
        val rows = ArrayList<CoderPlanRow>(named.size)
        var accepted = 0
        var total = 0L
        named.forEachIndexed { index, pair ->
            val path = pair.first
            val block = pair.second
            val size = block.code.toByteArray(Charsets.UTF_8).size.toLong()
            var reason: String? = pathRefusal(path)
            if (reason == null && block.unfinished) reason = MSG_UNFINISHED
            if (reason == null && lastIndex[path] != index) reason = MSG_DUPLICATE
            if (reason == null && size > limits.maxFileBytes) reason = MSG_TOO_BIG
            if (reason == null && accepted >= limits.maxFiles) reason = "Only ${limits.maxFiles} files can be applied at once."
            if (reason == null && total + size > limits.maxTotalBytes) reason = MSG_TOTAL
            if (reason == null) {
                accepted++
                total += size
            }
            val status = if (path in existingPaths) CoderPlanStatus.Replaces else CoderPlanStatus.New
            rows.add(CoderPlanRow(path, block.language, block.code, status, size, block.unfinished, reason))
        }
        return rows
    }

    /** True when [blocks] hold at least [MIN_FILES] finished blocks with different valid paths, so a plan is worth showing. */
    fun worthOffering(blocks: List<CoderCodeBlock>): Boolean {
        val distinct = HashSet<String>()
        for (block in blocks) {
            val path = block.path ?: continue
            if (!block.unfinished && CoderPaths.isValidPath(path)) distinct.add(path)
        }
        return distinct.size >= MIN_FILES
    }

    /**
     * The plan for the blocks of one answer, or null when [worthOffering] says no. Every file that can be applied
     * starts checked; refused files start unchecked. [existingFiles] are the files (not folders) already in the project.
     */
    fun offer(
        blocks: List<CoderCodeBlock>,
        existingFiles: Set<String>,
        limits: CoderApplyLimits = CoderApplyLimits(),
    ): CoderFilePlan? {
        if (!worthOffering(blocks)) return null
        val rows = build(blocks, existingFiles, limits)
        val selected = rows.filter { it.canApply }.mapTo(LinkedHashSet<String>()) { it.path }
        return CoderFilePlan(rows = rows, selected = selected)
    }

    /** [plan] with the file at [path] checked or unchecked. Unchanged when no row of that path can be applied. */
    fun toggle(plan: CoderFilePlan, path: String): CoderFilePlan {
        val row = plan.rows.firstOrNull { it.path == path && it.canApply } ?: return plan
        val next = LinkedHashSet<String>(plan.selected)
        if (!next.remove(row.path)) next.add(row.path)
        return plan.copy(selected = next)
    }

    /** The files to write: every row that can be applied and is checked, in the order of the answer. */
    fun itemsFor(plan: CoderFilePlan): List<CoderApplyItem> =
        plan.rows.filter { it.canApply && it.path in plan.selected }.map { CoderApplyItem(it.path, it.code) }

    /** How many files are checked and can be applied. */
    fun selectedCount(plan: CoderFilePlan): Int = itemsFor(plan).size

    /** The checked files as (new files, replaced files). */
    fun counts(plan: CoderFilePlan): Pair<Int, Int> {
        var created = 0
        var replaced = 0
        for (row in plan.rows) {
            if (!row.canApply || row.path !in plan.selected) continue
            if (row.status == CoderPlanStatus.Replaces) replaced++ else created++
        }
        return created to replaced
    }

    /** The title of the card, for example "The AI wrote 3 files". */
    fun headerText(fileCount: Int): String = "The AI wrote $fileCount ${fileWord(fileCount)}"

    /** The question before applying, with the number of new and replaced files. */
    fun confirmText(created: Int, replaced: Int): String =
        "Creates $created new ${fileWord(created)} and replaces $replaced ${fileWord(replaced)}. " +
            "A backup of the replaced files is saved so you can undo this."

    /** The text of "Copy all": every finished file under a line with its path. Unfinished files are left out. */
    fun copyAllText(rows: List<CoderPlanRow>): String =
        rows.filter { !it.unfinished }.joinToString("\n\n") { "===== ${it.path} =====\n${it.code}" }

    /** A size for a row, for example "812 B", "4.2 KB" or "1.3 MB". */
    fun sizeText(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    /** "New" or "Replaces", the tag on a row. */
    fun statusLabel(status: CoderPlanStatus): String = if (status == CoderPlanStatus.Replaces) "Replaces" else "New"

    /** "file" for one, "files" for any other number. */
    fun fileWord(count: Int): String = if (count == 1) "file" else "files"
}
