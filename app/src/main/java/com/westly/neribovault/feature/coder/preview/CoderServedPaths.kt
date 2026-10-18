package com.westly.neribovault.feature.coder.preview

/**
 * The paths of a project that are never shown, neither in the preview nor in the browser on this phone.
 * They hold secrets or tools' files, not the website. Pure, so the self-test can check every rule.
 */
object CoderServedPaths {
    private val BLOCKED_SUFFIXES = listOf(".pem", ".key", ".keystore")

    /**
     * True when the project-relative [path] must never be served: a part of it is `.git`, `node_modules` or
     * starts with `.coder`, or its file name is `.env`, starts with `.env.`, or ends with `.pem`, `.key` or
     * `.keystore`. Case is ignored, and a blocked folder blocks everything inside it. The preview answers such
     * a path exactly like a missing file, so nobody learns that it exists.
     */
    fun isBlocked(path: String): Boolean {
        val parts = path.lowercase().split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false
        if (parts.any { it == ".git" || it == "node_modules" || it.startsWith(".coder") }) return true
        val name = parts.last()
        if (name == ".env" || name.startsWith(".env.")) return true
        return BLOCKED_SUFFIXES.any { name.endsWith(it) }
    }
}
