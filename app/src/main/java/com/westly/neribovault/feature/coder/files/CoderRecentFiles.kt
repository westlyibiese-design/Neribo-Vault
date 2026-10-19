package com.westly.neribovault.feature.coder.files

import org.json.JSONArray

/**
 * The last files opened in one project, kept in the hidden file `.coder/recent.json` (a JSON array of paths) so
 * the file drawer can offer them again. The list rules are pure; [load] and [save] touch the disk and ignore every
 * failure, because a missing recent list must never get in the way of the person's work.
 */
object CoderRecentFiles {
    /** The most files the list keeps. */
    const val MAX_RECENT = 5

    /** Where the list lives inside the project. It starts with `.coder`, so it is hidden, never exported and never pushed. */
    const val FILE_PATH = ".coder/recent.json"

    /** [current] with [opened] moved (or added) to the front, no duplicates, at most [MAX_RECENT] long. */
    fun record(current: List<String>, opened: String): List<String> =
        (listOf(opened) + current.filter { it != opened }).distinct().take(MAX_RECENT)

    /** [current] without the files that are no longer in [existing]. The order stays. */
    fun dropMissing(current: List<String>, existing: Set<String>): List<String> =
        current.filter { it in existing }

    /** [current] followed by the files of [older] that it does not have yet, at most [MAX_RECENT] in all. */
    fun merge(current: List<String>, older: List<String>): List<String> =
        (current + older).distinct().take(MAX_RECENT)

    /** The list after [from] was renamed or moved to [to]; [from] may be a folder, so files inside it follow. */
    fun renamed(current: List<String>, from: String, to: String): List<String> =
        current.map { path ->
            when {
                path == from -> to
                path.startsWith("$from/") -> to + path.substring(from.length)
                else -> path
            }
        }.distinct().take(MAX_RECENT)

    /** The paths in [json]: only valid, visible project paths, no duplicates, at most [MAX_RECENT]. Anything unreadable gives an empty list. */
    fun parse(json: String?): List<String> {
        if (json == null) return emptyList()
        return try {
            val array = JSONArray(json)
            val out = ArrayList<String>()
            for (i in 0 until array.length()) {
                val path = array.opt(i) as? String ?: continue
                if (CoderPaths.isValidPath(path) && !isCoderHiddenPath(path) && path !in out) out.add(path)
                if (out.size >= MAX_RECENT) break
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** The JSON array for [paths]. */
    fun toJson(paths: List<String>): String {
        val array = JSONArray()
        for (path in paths.take(MAX_RECENT)) array.put(path)
        return array.toString()
    }

    /** Reads the list of [projectId]. An empty list when the file is missing or damaged. Does disk work. */
    fun load(store: CoderFileStore, projectId: String): List<String> {
        val result = try {
            store.read(projectId, FILE_PATH)
        } catch (e: Exception) {
            return emptyList()
        }
        return if (result is CoderReadResult.Text) parse(result.text) else emptyList()
    }

    /** Writes the list of [projectId]. A failure is ignored. Does disk work. */
    fun save(store: CoderFileStore, projectId: String, paths: List<String>) {
        try {
            store.write(projectId, FILE_PATH, toJson(paths))
        } catch (e: Exception) {
            // The recent list is a convenience; losing it is harmless.
        }
    }
}
