package com.westly.neribovault.feature.coder.preview

import com.westly.neribovault.feature.coder.files.CoderFileStore

/** Finds the folder of a project that the preview serves as the top of the site. */
object CoderPreviewRoot {

    /**
     * The folder to serve: "" when the project has an `index.html` at its top level, otherwise null
     * because there is nothing to show. Does blocking disk work, so call it from `Dispatchers.IO`.
     */
    fun find(store: CoderFileStore, projectId: String): String? {
        val index = store.resolve(projectId, "index.html") ?: return null
        return if (index.isFile) "" else null
    }

    /** [path] inside the preview root [rootPrefix] ("" or a folder name without a trailing slash), as a project path. */
    internal fun join(rootPrefix: String, path: String): String =
        if (rootPrefix.isEmpty()) path else "$rootPrefix/$path"
}
