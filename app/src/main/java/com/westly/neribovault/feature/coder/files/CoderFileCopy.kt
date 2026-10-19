package com.westly.neribovault.feature.coder.files

/** What to do with every file whose name is already used in the folder. One choice is made for the whole batch. */
enum class CoderConflictChoice { Replace, KeepBoth, Skip }

/**
 * The final name of one incoming file. [name] is null when the file is left out; [replaces] is true when the
 * file that is already there is overwritten.
 */
data class CoderNamePlan(val name: String?, val replaces: Boolean)

/**
 * Pure rules for adding files from the phone and for moving files: clean names, free names, conflict choices and
 * move checks. No Android types and no disk, so the debug self-test checks every rule.
 */
object CoderFileCopy {
    /** The biggest file that can be added from the phone: 20 MB. */
    const val MAX_FILE_BYTES = 20L * 1024 * 1024

    /** The size of the chunks a file is copied in. */
    const val COPY_BUFFER_BYTES = 64 * 1024

    /** The name used when nothing usable is left of a picked file's name. */
    const val FALLBACK_NAME = "file"

    /** The longest ending that still counts as an extension when a long name is shortened. */
    private const val MAX_EXTENSION_CHARS = 20

    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

    /**
     * A name that [CoderPaths.isValidSegment] accepts, made from the name a file had on the phone. The characters
     * `\ / : * ? " < > |` and control characters become "_", spaces at both ends and dots at the end are removed,
     * and a name over 100 characters is shortened in the middle so its extension stays. Never empty. Unicode is kept.
     */
    fun sanitizeName(name: String): String {
        val replaced = StringBuilder(name.length)
        for (ch in name) replaced.append(if (isBadChar(ch)) '_' else ch)
        var clean = trimEnds(replaced.toString())
        if (clean.length > CoderLimits.MAX_SEGMENT_LENGTH) {
            val (stem, ext) = splitExtension(clean)
            val room = (CoderLimits.MAX_SEGMENT_LENGTH - ext.length).coerceAtLeast(1)
            clean = trimEnds(withoutBrokenPair(stem.take(room)) + ext)
        }
        return if (clean.isEmpty()) FALLBACK_NAME else clean
    }

    /**
     * [name] when it is not in [existing]; otherwise `name (2).ext`, `name (3).ext` and so on, the first one that is
     * free. The number goes before the last extension; a name without one (or a dot-file such as `.env`) just gets
     * the number at the end. The result stays within 100 characters.
     */
    fun uniqueName(existing: Set<String>, name: String): String {
        if (name !in existing) return name
        val (stem, ext) = splitExtension(name)
        var number = 2
        while (true) {
            val suffix = " ($number)"
            val room = (CoderLimits.MAX_SEGMENT_LENGTH - ext.length - suffix.length).coerceAtLeast(1)
            val candidate = withoutBrokenPair(stem.take(room)) + suffix + ext
            if (candidate !in existing) return candidate
            number++
        }
    }

    /**
     * The names in [cleanNames] that are already used in the folder ([onDisk]), each once, in the order given.
     * Two incoming files with the same name are not a conflict here: they are always kept apart.
     */
    fun conflictingNames(onDisk: Set<String>, cleanNames: List<String>): List<String> =
        cleanNames.filter { it in onDisk }.distinct()

    /**
     * What happens to one incoming file called [cleanName]. [onDisk] are the names already in the folder and
     * [assigned] the names given to earlier files of this batch. A name taken by an earlier file of the batch is
     * always made unique (so two files are never mixed up); a name that is on the disk follows [choice].
     */
    fun planName(onDisk: Set<String>, assigned: Set<String>, cleanName: String, choice: CoderConflictChoice): CoderNamePlan {
        if (cleanName in assigned) return CoderNamePlan(uniqueName(onDisk + assigned, cleanName), false)
        if (cleanName !in onDisk) return CoderNamePlan(cleanName, false)
        return when (choice) {
            CoderConflictChoice.Replace -> CoderNamePlan(cleanName, true)
            CoderConflictChoice.KeepBoth -> CoderNamePlan(uniqueName(onDisk + assigned, cleanName), false)
            CoderConflictChoice.Skip -> CoderNamePlan(null, false)
        }
    }

    /** True for the picture types the file sheet can show: png, jpg, jpeg, gif, webp and bmp (any letter case). */
    fun isImageName(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return false
        return name.substring(dot + 1).lowercase() in IMAGE_EXTENSIONS
    }

    /** The heading of the report after adding files, such as "Added 4 files, 1 skipped". */
    fun reportTitle(added: Int, skipped: Int): String {
        val files = if (added == 1) "Added 1 file" else "Added $added files"
        return when {
            added == 0 && skipped == 0 -> "Nothing was added"
            added == 0 -> "Nothing was added, $skipped skipped"
            skipped == 0 -> files
            else -> "$files, $skipped skipped"
        }
    }

    /** True when [fromPath] may be moved into the folder [newParent] ("" is the project root): not into itself or one of its own folders. */
    fun canMoveInto(fromPath: String, newParent: String): Boolean =
        fromPath.isNotEmpty() && newParent != fromPath && !newParent.startsWith("$fromPath/")

    /** Where [fromPath] ends up when it is moved into [newParent]. */
    fun movedPath(fromPath: String, newParent: String): String = CoderPaths.join(newParent, CoderPaths.nameOf(fromPath))

    /**
     * The folders [fromPath] can be moved into: the project root ("") first, then each of [folders] in the order given,
     * without [fromPath] itself and the folders inside it.
     */
    fun moveTargets(folders: List<String>, fromPath: String): List<String> =
        listOf("") + folders.filter { canMoveInto(fromPath, it) }

    private fun isBadChar(ch: Char): Boolean = Character.isISOControl(ch) || "\\/:*?\"<>|".indexOf(ch) >= 0

    /** Removes spaces at both ends and dots at the end, until nothing more changes. */
    private fun trimEnds(text: String): String {
        var current = text
        while (true) {
            val next = current.trim(' ').trimEnd('.')
            if (next == current) return current
            current = next
        }
    }

    /** Splits [name] into the part before the last dot and the ending with its dot; no ending for a dot-file or a very long ending. */
    private fun splitExtension(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || name.length - dot > MAX_EXTENSION_CHARS) return Pair(name, "")
        return Pair(name.substring(0, dot), name.substring(dot))
    }

    /** Drops a lone first half of a surrogate pair that cutting a name may leave at its end. */
    private fun withoutBrokenPair(text: String): String =
        if (text.isNotEmpty() && text.last().isHighSurrogate()) text.dropLast(1) else text
}
