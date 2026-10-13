package com.westly.neribovault.feature.coder.preview

import com.westly.neribovault.feature.coder.files.CoderFileStore

/** Finds the folder of a project that the preview serves as the top of the site. */
object CoderPreviewRoot {
    /** The folders checked for an `index.html`, in this order: the project itself first, then the usual names of a built site. */
    private val CANDIDATE_FOLDERS = listOf("", "dist", "build", "out", "docs")

    /**
     * The first of "" (the project folder), `dist`, `build`, `out` and `docs` that holds an `index.html`,
     * or null because there is nothing to show. A built app in `dist` or `build` is shown like a deployed
     * site, so addresses such as `/assets/app.js` work. An app built for a sub-path (for example
     * `/my-repo/`) will not find its files, which is a known limit. Does blocking disk work, so call it
     * from `Dispatchers.IO`.
     */
    fun find(store: CoderFileStore, projectId: String): String? {
        for (folder in CANDIDATE_FOLDERS) {
            val index = store.resolve(projectId, join(folder, "index.html")) ?: continue
            if (index.isFile) return folder
        }
        return null
    }

    /** [path] inside the preview root [rootPrefix] ("" or a folder name without a trailing slash), as a project path. */
    internal fun join(rootPrefix: String, path: String): String =
        if (rootPrefix.isEmpty()) path else "$rootPrefix/$path"
}
