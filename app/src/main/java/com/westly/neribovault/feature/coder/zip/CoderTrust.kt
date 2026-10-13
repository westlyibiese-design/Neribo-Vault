package com.westly.neribovault.feature.coder.zip

import java.util.Collections

/**
 * Remembers which ZIP projects the person agreed to run, until the app process ends. Nothing is
 * written to disk, so every new app session asks again.
 */
object CoderTrust {
    private val trusted: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())

    /** True when [projectId] was trusted during this app session. */
    fun isTrusted(projectId: String): Boolean = trusted.contains(projectId)

    /** Remembers [projectId] as trusted for this app session. */
    fun trust(projectId: String) {
        trusted.add(projectId)
    }
}
