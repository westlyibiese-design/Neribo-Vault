package com.westly.neribovault.feature.coder.zip

import com.westly.neribovault.feature.coder.files.CoderLimits
import com.westly.neribovault.feature.coder.files.CoderPaths

/** Why an entry of a ZIP is left out on purpose. */
enum class SkipReason { NODE_MODULES, GIT_FOLDER, SYSTEM_FILE }

/**
 * The pure rules of ZIP import: limits, name cleaning, what to skip, the outer folder and project
 * names. Nothing here touches the disk, so the self-test can check every rule on its own.
 */
object CoderZipRules {
    /** The biggest ZIP file the picker may hand over. */
    const val MAX_ZIP_FILE_BYTES = 50L * 1024 * 1024

    /** The biggest single file inside a ZIP, once unpacked. */
    const val MAX_ENTRY_BYTES = 20L * 1024 * 1024

    /** The biggest total of unpacked files: the same limit a project always has. */
    const val MAX_TOTAL_BYTES = CoderLimits.MAX_PROJECT_BYTES

    /** An entry that unpacks to more than this many times its packed size is refused as suspicious. */
    const val MAX_COMPRESSION_RATIO = 200

    /** The most data read while looking through a ZIP, skipped parts included. It stops a ZIP built to unpack to gigabytes. */
    const val MAX_SCAN_BYTES = 1024L * 1024 * 1024

    /** The `templateId` of every project made from a ZIP. */
    const val TEMPLATE_ID = "zip"

    /** The project name used when the file has no usable name. */
    const val DEFAULT_PROJECT_NAME = "Imported project"

    /**
     * [raw] with backslashes turned into slashes, one leading "./" and every trailing slash removed.
     * Null when nothing is left. The result is not checked for safety yet.
     */
    fun cleanEntryName(raw: String): String? {
        var name = raw.replace('\\', '/')
        if (name.startsWith("./")) name = name.substring(2)
        name = name.trimEnd('/')
        return if (name.isEmpty()) null else name
    }

    /**
     * The project path for a ZIP entry name, or null when the name is empty or unsafe. Names with "..",
     * absolute paths, drive letters, control characters or over-long parts are all refused here.
     */
    fun normalizeEntryName(raw: String): String? {
        val name = cleanEntryName(raw) ?: return null
        return if (CoderPaths.isValidPath(name)) name else null
    }

    /** Why [path] is left out of an import, or null when it is kept. */
    fun shouldSkip(path: String): SkipReason? {
        val segments = path.split('/')
        if (segments.any { it == "node_modules" }) return SkipReason.NODE_MODULES
        if (segments.any { it == ".git" }) return SkipReason.GIT_FOLDER
        if (segments.any { it == "__MACOSX" }) return SkipReason.SYSTEM_FILE
        val fileName = segments.last()
        if (fileName == ".DS_Store" || fileName == "Thumbs.db" || fileName == "desktop.ini") return SkipReason.SYSTEM_FILE
        return null
    }

    /**
     * The name of the one folder that holds everything in [paths] (a GitHub download is
     * `repo-main/...`), or null when the files do not all start in the same folder.
     */
    fun stripWrapperFolder(paths: List<String>): String? {
        var top: String? = null
        for (path in paths) {
            val slash = path.indexOf('/')
            if (slash <= 0) return null
            val first = path.substring(0, slash)
            if (top == null) top = first else if (top != first) return null
        }
        return top
    }

    /** A project name from the picked file's name: no ".zip", no control characters, at most 60 characters. */
    fun projectNameFromFile(displayName: String?): String {
        var name = (displayName ?: "").filter { it.code >= 32 && it.code != 127 }.trim()
        if (name.endsWith(".zip", ignoreCase = true)) name = name.substring(0, name.length - 4).trim()
        if (name.length > CoderLimits.MAX_PROJECT_NAME_LENGTH) {
            name = name.substring(0, CoderLimits.MAX_PROJECT_NAME_LENGTH).trim()
        }
        return if (name.isEmpty()) DEFAULT_PROJECT_NAME else name
    }

    /** [base], or "[base] (2)", "[base] (3)" and so on when a project in [existing] already has that name. The result fits the name limit. */
    fun uniqueProjectName(base: String, existing: Collection<String>): String {
        val taken = existing.map { it.trim().lowercase() }.toSet()
        if (base.trim().lowercase() !in taken) return base
        var number = 2
        var candidate: String
        do {
            val suffix = " ($number)"
            candidate = base.take(CoderLimits.MAX_PROJECT_NAME_LENGTH - suffix.length).trimEnd() + suffix
            number++
        } while (candidate.lowercase() in taken)
        return candidate
    }

    /** True when [uncompressed] bytes came from [compressed] bytes at a ratio above the limit. An unknown or zero [compressed] size is never suspicious. */
    fun isSuspiciousRatio(uncompressed: Long, compressed: Long): Boolean =
        compressed > 0L && uncompressed > compressed * MAX_COMPRESSION_RATIO
}
