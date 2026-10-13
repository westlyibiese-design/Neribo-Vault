package com.westly.neribovault.feature.coder.preview

import android.net.Uri

/**
 * The fake web address each project is shown at. The host ends in `.invalid`, which can never
 * resolve on the internet, and it differs per project, so two projects never share saved page data.
 */
object CoderPreviewHost {
    /** Paths that start with this are answered by the app itself (Markdown and JSON pages), never from project files. */
    const val VIRTUAL_PREFIX = "__coder"

    /** The host name of [projectId]: "p" + the id without dashes + ".coder.invalid", in lower case. */
    fun hostFor(projectId: String): String = "p" + projectId.replace("-", "").lowercase() + ".coder.invalid"

    /** The full address of [pathUnderRoot] in [projectId]. Every part of the path is percent-encoded; the slashes stay. */
    fun urlFor(projectId: String, pathUnderRoot: String): String {
        val encoded = pathUnderRoot.split('/').joinToString("/") { Uri.encode(it) }
        return "https://" + hostFor(projectId) + "/" + encoded
    }
}
