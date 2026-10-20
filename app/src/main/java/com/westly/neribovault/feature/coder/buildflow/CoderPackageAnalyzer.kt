package com.westly.neribovault.feature.coder.buildflow

import org.json.JSONException
import org.json.JSONObject

/**
 * What the GitHub build will run for a project. [tool] is the name shown to the person; [installCommand] and
 * [buildCommand] come from a fixed set; [outputDir] is the folder the build writes the finished site to; [nodeVersion]
 * is 18, 20 or 22. When [supported] is false the build cannot run and [unsupportedReason] says why in plain English.
 */
data class CoderBuildPlan(
    val tool: String,
    val installCommand: String,
    val buildCommand: String,
    val outputDir: String,
    val nodeVersion: Int,
    val warnings: List<String>,
    val supported: Boolean,
    val unsupportedReason: String? = null,
)

/** The names of the build tools the analyzer can tell apart. */
object CoderBuildTools {
    const val VITE = "Vite"
    const val CREATE_REACT_APP = "Create React App"
    const val PARCEL = "Parcel"
    const val WEBPACK = "webpack"
    const val UNKNOWN = "Unknown build tool"
}

/**
 * Reads a project's `package.json` and decides how it can be built on GitHub: which tool it uses, how to install and
 * build it, where the finished site ends up and which Node version to ask for. Pure, so the self-test checks every
 * rule on its own. Nothing here reads a file: the caller passes the text and the names of the files at the top.
 */
object CoderPackageAnalyzer {
    /** The Node version used when `engines.node` says nothing usable. */
    const val DEFAULT_NODE_VERSION = 20

    const val MSG_NO_PACKAGE = "This project has no package.json, so there is nothing to build."
    const val MSG_UNREADABLE = "Coder couldn't read package.json. Check that it is valid JSON."
    const val MSG_NO_BUILD = "This project has no build script, so there is nothing to build."
    const val MSG_SERVER = "This project needs a server, so it can't be built into a static site here."
    const val MSG_CHECK_OUTPUT = "Check the output folder name."
    const val MSG_BASE = "This project sets a base path; the preview may not find its files."
    const val MSG_HOMEPAGE = "This project has a homepage field, so its files may be looked for under that address."

    private const val MAX_OUTPUT_DIR_LENGTH = 100

    private val TOKEN_SPLIT = Regex("[^A-Za-z0-9@/._-]+")
    private val BASE_FLAG = Regex("--base(?:=|\\s+)(\\S+)")
    private val CONFIG_BASE = Regex("\\bbase\\s*:\\s*['\"`]([^'\"`]*)['\"`]")
    private val OUTPUT_DIR = Regex("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*")

    /**
     * The plan for the project whose root `package.json` reads [packageJson] (null when the file is missing) and whose
     * top-level files are named [rootFileNames]. [viteConfigText] is the text of `vite.config.*` when there is one; it
     * is only used to look for a base path.
     */
    fun analyze(packageJson: String?, rootFileNames: Set<String>, viteConfigText: String? = null): CoderBuildPlan {
        if (packageJson == null) return unsupported(MSG_NO_PACKAGE)
        val json = try {
            JSONObject(packageJson)
        } catch (e: JSONException) {
            return unsupported(MSG_UNREADABLE)
        }
        val scripts = json.optJSONObject("scripts")
        val buildScript = if (scripts == null || scripts.isNull("build")) "" else scripts.optString("build", "").trim()
        if (buildScript.isEmpty()) return unsupported(MSG_NO_BUILD)

        val words = wordsOf(json, buildScript)
        if ("next" in words) return unsupported(MSG_SERVER, "Next.js")
        if ("nuxt" in words || "nuxt3" in words) return unsupported(MSG_SERVER, "Nuxt")
        val blocked = listOf("@angular/cli" to "Angular", "astro" to "Astro", "@sveltejs/kit" to "SvelteKit")
            .firstOrNull { it.first in words }
        if (blocked != null) return unsupported("${blocked.second} projects can't be built here yet.", blocked.second)

        val tool: String
        val outputDir: String
        when {
            "vite" in words -> {
                tool = CoderBuildTools.VITE
                outputDir = "dist"
            }
            "react-scripts" in words -> {
                tool = CoderBuildTools.CREATE_REACT_APP
                outputDir = "build"
            }
            "parcel" in words -> {
                tool = CoderBuildTools.PARCEL
                outputDir = "dist"
            }
            "webpack" in words || "webpack-cli" in words -> {
                tool = CoderBuildTools.WEBPACK
                outputDir = "dist"
            }
            else -> {
                tool = CoderBuildTools.UNKNOWN
                outputDir = "dist"
            }
        }

        val install: String
        val build: String
        when {
            "package-lock.json" in rootFileNames -> {
                install = "npm ci"
                build = "npm run build"
            }
            "yarn.lock" in rootFileNames -> {
                install = "corepack enable && yarn install --frozen-lockfile"
                build = "yarn build"
            }
            "pnpm-lock.yaml" in rootFileNames -> {
                install = "corepack enable && pnpm install --frozen-lockfile"
                build = "pnpm build"
            }
            else -> {
                install = "npm install"
                build = "npm run build"
            }
        }

        val engines = json.optJSONObject("engines")
        val nodeSpec = if (engines == null || engines.isNull("node")) null else engines.optString("node", "")
        val warnings = ArrayList<String>()
        if (tool == CoderBuildTools.UNKNOWN) warnings.add(MSG_CHECK_OUTPUT)
        if (tool == CoderBuildTools.VITE && setsBasePath(buildScript, viteConfigText)) warnings.add(MSG_BASE)
        if (tool == CoderBuildTools.CREATE_REACT_APP && hasHomepage(json)) warnings.add(MSG_HOMEPAGE)
        return CoderBuildPlan(tool, install, build, outputDir, nodeMajorFor(nodeSpec), warnings, supported = true)
    }

    /**
     * The Node version to ask for from an `engines.node` text. A text that starts (after any of `>=<~^v` and spaces)
     * with a number gives that major version, bounded to 18, 20 or 22 (16 and 17 become 18, 19 becomes 20, 21 and
     * higher become 22). Anything else, and null, gives 20.
     */
    fun nodeMajorFor(spec: String?): Int {
        if (spec == null) return DEFAULT_NODE_VERSION
        val trimmed = spec.trim().trimStart('>', '=', '<', '^', '~', 'v', 'V', ' ')
        val major = trimmed.takeWhile { it in '0'..'9' }.toIntOrNull() ?: return DEFAULT_NODE_VERSION
        return when {
            major <= 18 -> 18
            major <= 20 -> 20
            else -> 22
        }
    }

    /** True when [dir] is a plain relative folder name (letters, digits, ".", "_" and "-", with "/" between parts) that is safe to write into the workflow. */
    fun isValidOutputDir(dir: String): Boolean {
        if (dir.isEmpty() || dir.length > MAX_OUTPUT_DIR_LENGTH) return false
        if (!OUTPUT_DIR.matches(dir)) return false
        return dir.split('/').none { it == "." || it == ".." }
    }

    /** The prefix a tool needs on a variable name for the build to read it: `VITE_` for Vite, `REACT_APP_` for Create React App, else null. */
    fun envPrefixFor(tool: String): String? = when (tool) {
        CoderBuildTools.VITE -> "VITE_"
        CoderBuildTools.CREATE_REACT_APP -> "REACT_APP_"
        else -> null
    }

    /** The plain-English hint when some of [names] lack the prefix [tool] needs, or null when all have it or the tool needs none. */
    fun prefixHint(tool: String, names: List<String>): String? {
        val prefix = envPrefixFor(tool) ?: return null
        if (names.all { it.startsWith(prefix) }) return null
        return if (tool == CoderBuildTools.VITE) {
            "Vite only reads names that start with VITE_."
        } else {
            "Create React App only reads names that start with REACT_APP_."
        }
    }

    private fun unsupported(reason: String, tool: String = CoderBuildTools.UNKNOWN): CoderBuildPlan =
        CoderBuildPlan(tool, "", "", "dist", DEFAULT_NODE_VERSION, emptyList(), supported = false, unsupportedReason = reason)

    /** Every dependency name and every word of the build script. */
    private fun wordsOf(json: JSONObject, buildScript: String): Set<String> {
        val words = HashSet<String>()
        for (section in listOf("dependencies", "devDependencies")) {
            val keys = json.optJSONObject(section)?.keys() ?: continue
            while (keys.hasNext()) words.add(keys.next())
        }
        for (token in buildScript.split(TOKEN_SPLIT)) {
            if (token.isNotEmpty()) words.add(token)
        }
        return words
    }

    /** True when the build script or the Vite config sets a base path other than "/". */
    private fun setsBasePath(buildScript: String, viteConfigText: String?): Boolean {
        val fromFlag = BASE_FLAG.find(buildScript)?.groupValues?.get(1)?.trim('"', '\'')
        val fromConfig = if (viteConfigText == null) null else CONFIG_BASE.find(viteConfigText)?.groupValues?.get(1)
        val base = fromFlag ?: fromConfig ?: return false
        return base != "/"
    }

    private fun hasHomepage(json: JSONObject): Boolean =
        json.has("homepage") && !json.isNull("homepage") && json.optString("homepage", "").isNotBlank()
}
