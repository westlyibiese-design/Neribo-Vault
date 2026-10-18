package com.westly.neribovault.feature.coder.workspace

import com.westly.neribovault.feature.coder.files.CoderPaths

/** The most files that can be open at once. Each open file holds its text and undo history in memory. */
const val CODER_MAX_TABS = 12

/** Shown when every tab has unsaved changes and none can be closed to make room. */
const val CODER_TABS_FULL_REASON = "Close a tab first"

/**
 * The open tabs of one project: [paths] in the order they were opened, the [active] one (null when none),
 * the [maxTabs] limit and [recent], the paths from least to most recently used. A path missing from [recent]
 * counts as older than every path in it, so a list built without [recent] still works.
 */
data class CoderTabs(
    val paths: List<String>,
    val active: String?,
    val maxTabs: Int = CODER_MAX_TABS,
    val recent: List<String> = emptyList(),
)

/** What happened when a path was opened. */
sealed class OpenOutcome {
    /** A new tab was added; [closedPath] is the clean tab that was closed to make room, if any. */
    data class Opened(val tabs: CoderTabs, val closedPath: String?) : OpenOutcome()

    /** The path was already open and is now the active tab. */
    data class Activated(val tabs: CoderTabs) : OpenOutcome()

    /** Nothing changed; [reason] says why in plain words. */
    data class Refused(val reason: String) : OpenOutcome()
}

/**
 * The rules of the tab strip as pure functions: no Android types, no disk, so every rule is checked by the
 * debug self-test. Paths are project paths such as "src/app.js".
 */
object CoderTabLogic {

    /** Makes [path] the active tab and the most recently used one. Unchanged when [path] is not open. */
    fun activate(tabs: CoderTabs, path: String): CoderTabs {
        if (path !in tabs.paths) return tabs
        return tabs.copy(active = path, recent = usageOrder(tabs).filter { it != path } + path)
    }

    /**
     * Opens [path]. An open path is only activated. When [CoderTabs.maxTabs] tabs are open, the least recently
     * used tab that [isDirty] says is clean is closed to make room; when every tab is dirty the result is
     * [OpenOutcome.Refused].
     */
    fun open(tabs: CoderTabs, path: String, isDirty: (String) -> Boolean): OpenOutcome {
        if (path in tabs.paths) return OpenOutcome.Activated(activate(tabs, path))
        var base = tabs
        var closed: String? = null
        if (tabs.paths.size >= tabs.maxTabs.coerceAtLeast(1)) {
            val victim = usageOrder(tabs).firstOrNull { !isDirty(it) } ?: return OpenOutcome.Refused(CODER_TABS_FULL_REASON)
            base = withoutPath(tabs, victim)
            closed = victim
        }
        val added = base.copy(paths = base.paths + path)
        return OpenOutcome.Opened(activate(added, path), closed)
    }

    /** Closes [path]. Closing the active tab activates its right neighbour, else its left neighbour, else nothing. */
    fun close(tabs: CoderTabs, path: String): CoderTabs {
        val index = tabs.paths.indexOf(path)
        if (index < 0) return tabs
        val rest = tabs.paths.filterIndexed { i, _ -> i != index }
        val order = usageOrder(tabs).filter { it != path }
        if (tabs.active != path) return tabs.copy(paths = rest, recent = order)
        val next = rest.getOrNull(index) ?: rest.getOrNull(index - 1)
        return if (next == null) {
            tabs.copy(paths = emptyList(), active = null, recent = emptyList())
        } else {
            tabs.copy(paths = rest, active = next, recent = order.filter { it != next } + next)
        }
    }

    /** Closes every tab except [keep], which becomes the active one. Unchanged when [keep] is not open. */
    fun closeOthers(tabs: CoderTabs, keep: String): CoderTabs {
        if (keep !in tabs.paths) return tabs
        return tabs.copy(paths = listOf(keep), active = keep, recent = listOf(keep))
    }

    /** Closes every tab. */
    fun closeAll(tabs: CoderTabs): CoderTabs = tabs.copy(paths = emptyList(), active = null, recent = emptyList())

    /** The new path of [path] after [from] was renamed to [to]; [from] may be a file or a folder. Unchanged when not affected. */
    fun mapPath(path: String, from: String, to: String): String = when {
        path == from -> to
        path.startsWith("$from/") -> to + path.substring(from.length)
        else -> path
    }

    /** True when [path] is [target] itself or lies inside the folder [target]. */
    fun isInside(path: String, target: String): Boolean = path == target || path.startsWith("$target/")

    /** Renames the tab [from] to [to], and every tab inside the folder [from] (their paths start with from + "/"). */
    fun rename(tabs: CoderTabs, from: String, to: String): CoderTabs {
        val paths = tabs.paths.map { mapPath(it, from, to) }.distinct()
        val active = tabs.active?.let { mapPath(it, from, to) }?.takeIf { it in paths }
        val recent = usageOrder(tabs).map { mapPath(it, from, to) }.distinct().filter { it in paths }
        return tabs.copy(paths = paths, active = active, recent = recent)
    }

    /** A deleted file or folder closes every tab under it. */
    fun removed(tabs: CoderTabs, path: String): CoderTabs {
        var result = tabs
        for (open in tabs.paths) {
            if (isInside(open, path)) result = close(result, open)
        }
        return result
    }

    /**
     * The text shown on each tab: the file name, or "name (folder)" with the parent folder's name when two tabs
     * share a name ("root" for a top-level file). When that is still not unique the full path is used.
     */
    fun labels(paths: List<String>): Map<String, String> {
        val nameCounts = HashMap<String, Int>()
        for (path in paths) {
            val name = CoderPaths.nameOf(path)
            nameCounts[name] = (nameCounts[name] ?: 0) + 1
        }
        val first = LinkedHashMap<String, String>()
        for (path in paths) {
            val name = CoderPaths.nameOf(path)
            first[path] = if ((nameCounts[name] ?: 0) > 1) "$name (${folderName(path)})" else name
        }
        val labelCounts = HashMap<String, Int>()
        for (label in first.values) labelCounts[label] = (labelCounts[label] ?: 0) + 1
        val result = LinkedHashMap<String, String>()
        for ((path, label) in first) result[path] = if ((labelCounts[label] ?: 0) > 1) path else label
        return result
    }

    private fun folderName(path: String): String {
        val parent = CoderPaths.parentOf(path)
        return if (parent.isEmpty()) "root" else CoderPaths.nameOf(parent)
    }

    /** All open paths, least recently used first. */
    private fun usageOrder(tabs: CoderTabs): List<String> {
        val known = tabs.recent.filter { it in tabs.paths }.distinct()
        return tabs.paths.filter { it !in known } + known
    }

    private fun withoutPath(tabs: CoderTabs, path: String): CoderTabs {
        val paths = tabs.paths.filter { it != path }
        return tabs.copy(
            paths = paths,
            active = tabs.active?.takeIf { it != path },
            recent = usageOrder(tabs).filter { it != path },
        )
    }
}

/**
 * The one save status shown for the whole editor. A write in progress in any tab shows Saving; otherwise a
 * failed save of the active tab shows Failed; otherwise unsaved text in the active tab (waiting for autosave)
 * shows Saving; otherwise Saved. The other tabs show their own dots.
 */
fun aggregateSaveStatus(anyWriting: Boolean, activeFailed: Boolean, activePending: Boolean): CoderSaveStatus = when {
    anyWriting -> CoderSaveStatus.Saving
    activeFailed -> CoderSaveStatus.Failed
    activePending -> CoderSaveStatus.Saving
    else -> CoderSaveStatus.Saved
}
