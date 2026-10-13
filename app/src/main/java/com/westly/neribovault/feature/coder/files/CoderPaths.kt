package com.westly.neribovault.feature.coder.files

/**
 * Rules for paths inside a Coder project. A path is relative, uses "/" and never leaves the project.
 * Every path that comes from a user, a template or later a ZIP must pass [isValidPath] first.
 */
object CoderPaths {
    private const val FORBIDDEN_CHARS = "\\/:*?\"<>|"

    /** True for a safe relative path: no empty, "." or ".." parts, no backslash, within the size limits. */
    fun isValidPath(path: String): Boolean {
        if (path.isEmpty() || path.length > CoderLimits.MAX_PATH_LENGTH) return false
        if (path.startsWith('/') || path.endsWith('/')) return false
        val segments = path.split('/')
        if (segments.size > CoderLimits.MAX_FOLDER_DEPTH) return false
        return segments.all { isValidSegment(it) }
    }

    /** True for one file or folder name. Dot-files such as `.gitignore` are fine. */
    fun isValidSegment(name: String): Boolean {
        if (name.isBlank() || name.length > CoderLimits.MAX_SEGMENT_LENGTH) return false
        if (name == "." || name == "..") return false
        if (name.first() == ' ' || name.last() == ' ' || name.last() == '.') return false
        for (ch in name) {
            if (ch.code < 32 || ch.code == 127) return false
            if (FORBIDDEN_CHARS.indexOf(ch) >= 0) return false
        }
        return true
    }

    /** The folder part of [path]; an empty string for a top-level entry. */
    fun parentOf(path: String): String {
        val slash = path.lastIndexOf('/')
        return if (slash < 0) "" else path.substring(0, slash)
    }

    /** The last part of [path]. */
    fun nameOf(path: String): String = path.substring(path.lastIndexOf('/') + 1)

    /** [name] inside [parent]; just [name] when [parent] is empty. */
    fun join(parent: String, name: String): String = if (parent.isEmpty()) name else "$parent/$name"
}
