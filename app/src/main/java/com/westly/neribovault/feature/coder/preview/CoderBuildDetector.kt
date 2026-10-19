package com.westly.neribovault.feature.coder.preview

import com.westly.neribovault.feature.coder.files.CoderFileStore
import com.westly.neribovault.feature.coder.files.CoderReadResult
import org.json.JSONObject

/** Whether a project can be previewed as it is, or has to be built on a computer first. */
sealed interface CoderBuildCheck {
    /** The project can be shown. */
    object Ready : CoderBuildCheck

    /** The project must be built first. [reason] is plain English; [builtFolderHint] is "dist" or "build" when that folder exists but has no index.html, else null. */
    data class NeedsBuild(val reason: String, val builtFolderHint: String?) : CoderBuildCheck
}

/**
 * Spots projects written for build tools such as Vite, which a phone cannot run. Looks only at the
 * top-level `index.html` and `package.json`. Does blocking disk work, so call it from `Dispatchers.IO`.
 */
object CoderBuildDetector {
    private val BUILT_FOLDERS = setOf("dist", "build", "out", "docs")
    private val SCRIPT_TAG = Regex("<script\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val SRC_ATTRIBUTE = Regex("\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val MODULE_ATTRIBUTE = Regex("\\btype\\s*=\\s*[\"']?module[\"']?", RegexOption.IGNORE_CASE)
    private val TOKEN_SPLIT = Regex("[^A-Za-z0-9@/._-]+")

    /** Display names of the build tools, in the order they are looked for. */
    private val TOOLS: List<Pair<String, String>> = listOf(
        "vite" to "Vite",
        "react-scripts" to "Create React App",
        "next" to "Next.js",
        "webpack" to "webpack",
        "parcel" to "Parcel",
        "@vue/cli" to "Vue CLI",
        "nuxt" to "Nuxt",
        "svelte" to "Svelte",
        "astro" to "Astro",
    )

    /** Checks [projectId]. Never throws; any read problem counts as [CoderBuildCheck.Ready]. */
    fun check(store: CoderFileStore, projectId: String): CoderBuildCheck {
        return try {
            checkProject(store, projectId)
        } catch (e: Exception) {
            CoderBuildCheck.Ready
        }
    }

    /** True when the project's top-level `index.html` loads source files (the Vite / React layout) and so cannot run as it is. */
    internal fun sourceIndexNeedsBuild(store: CoderFileStore, projectId: String): Boolean {
        val index = readText(store, projectId, "index.html") ?: return false
        return loadsSourceFiles(index)
    }

    private fun checkProject(store: CoderFileStore, projectId: String): CoderBuildCheck {
        // Rule 0: a site built on GitHub is ready to show.
        val built = store.resolve(projectId, CoderPreviewRoot.join(CoderPreviewRoot.BUILT_FOLDER, "index.html"))
        if (built != null && built.isFile) return CoderBuildCheck.Ready
        val root = CoderPreviewRoot.find(store, projectId)
        // Rule 1: a finished site in dist, build, out or docs, with no index.html at the top.
        if (root != null && root in BUILT_FOLDERS) return CoderBuildCheck.Ready

        val hasRootIndex = root == ""
        val tool = findTool(readText(store, projectId, "package.json"))
        val hint = builtFolderHint(store, projectId)

        // Rule 2: the page loads its source files (the Vite / React layout).
        if (hasRootIndex) {
            val index = readText(store, projectId, "index.html")
            if (index != null && loadsSourceFiles(index)) {
                val reason = if (tool != null) {
                    "This project uses a build tool ($tool), so its code must be built before it can run."
                } else {
                    "This project's page loads source files (such as main.jsx), so its code must be built before it can run."
                }
                return CoderBuildCheck.NeedsBuild(reason, hint)
            }
        }
        // Rule 3: a package.json for a build tool and no page to show.
        if (root == null && tool != null) {
            return CoderBuildCheck.NeedsBuild(
                "This project uses a build tool ($tool), so its code must be built before it can run.",
                hint,
            )
        }
        return CoderBuildCheck.Ready
    }

    /** The file's text, or null when it is missing, too large, binary or unreadable. */
    private fun readText(store: CoderFileStore, projectId: String, path: String): String? =
        (store.read(projectId, path) as? CoderReadResult.Text)?.text

    /** True when a script tag is a module whose source ends in .jsx, .tsx or .ts, or its source starts with /src/. */
    private fun loadsSourceFiles(html: String): Boolean {
        for (tag in SCRIPT_TAG.findAll(html)) {
            val text = tag.value
            val src = SRC_ATTRIBUTE.find(text)?.groupValues?.get(1) ?: continue
            val clean = src.substringBefore('?').substringBefore('#').lowercase()
            val isSourceExtension = clean.endsWith(".jsx") || clean.endsWith(".tsx") || clean.endsWith(".ts")
            if (isSourceExtension && MODULE_ATTRIBUTE.containsMatchIn(text)) return true
            if (src.startsWith("/src/")) return true
        }
        return false
    }

    /** The display name of the first build tool named in package.json's dependencies, devDependencies or scripts, or null. */
    private fun findTool(packageJson: String?): String? {
        if (packageJson == null) return null
        val words = ArrayList<String>()
        try {
            val json = JSONObject(packageJson)
            for (section in listOf("dependencies", "devDependencies")) {
                val keys = json.optJSONObject(section)?.keys() ?: continue
                while (keys.hasNext()) words.add(keys.next())
            }
            val scripts = json.optJSONObject("scripts")
            if (scripts != null) {
                val keys = scripts.keys()
                while (keys.hasNext()) words.addAll(scripts.optString(keys.next(), "").split(TOKEN_SPLIT))
            }
        } catch (e: Exception) {
            return null
        }
        for ((name, label) in TOOLS) {
            val found = words.any { word -> word == name || (name == "@vue/cli" && word.startsWith("@vue/cli")) }
            if (found) return label
        }
        return null
    }

    /** "dist" or "build" when that folder exists and has no index.html directly inside, else null. */
    private fun builtFolderHint(store: CoderFileStore, projectId: String): String? {
        for (folder in listOf("dist", "build")) {
            val dir = store.resolve(projectId, folder) ?: continue
            if (!dir.isDirectory) continue
            val index = store.resolve(projectId, "$folder/index.html")
            if (index == null || !index.isFile) return folder
        }
        return null
    }
}
