package com.westly.neribovault.feature.developer.plans

/** One file or folder in a parsed outline. A node with children is always a folder. */
data class TreeNode(
    val name: String,
    val isFolder: Boolean,
    val children: List<TreeNode> = emptyList(),
)

private const val MAX_OUTLINE_LINES = 5_000
private const val MAX_TREE_DEPTH = 32
private const val TAB_COLUMNS = 2

private const val BRANCH = "\u251C\u2500\u2500 "
private const val LAST_BRANCH = "\u2514\u2500\u2500 "
private const val PIPE_PAD = "\u2502   "
private const val BLANK_PAD = "    "

private val SAFE_PATH = Regex("^[A-Za-z0-9._/@%+=:,-]+\$")

private class MutableNode(val name: String, val hasTrailingSlash: Boolean) {
    val children = mutableListOf<MutableNode>()
}

/**
 * Turns an indented outline into a tree. Two spaces or one tab make one level, but any
 * consistent-enough indentation works: a line is the child of the nearest line above it that is
 * indented less. A trailing `/` marks a folder; so does having children. Blank lines and trailing
 * spaces are ignored. This function never throws.
 */
fun parseOutline(text: String): List<TreeNode> =
    try {
        parseOutlineUnsafe(text)
    } catch (error: RuntimeException) {
        emptyList()
    }

private fun parseOutlineUnsafe(text: String): List<TreeNode> {
    val roots = mutableListOf<MutableNode>()
    val stackNodes = ArrayList<MutableNode>()
    val stackIndents = ArrayList<Int>()
    var used = 0
    for (rawLine in text.lineSequence()) {
        if (used >= MAX_OUTLINE_LINES) break
        val line = rawLine.trimEnd()
        if (line.isBlank()) continue
        used++
        val trimmed = line.trim()
        val name = trimmed.trimEnd('/').trim()
        if (name.isEmpty()) continue
        val indent = indentColumns(line)
        while (stackIndents.isNotEmpty() && stackIndents[stackIndents.lastIndex] >= indent) {
            stackIndents.removeAt(stackIndents.lastIndex)
            stackNodes.removeAt(stackNodes.lastIndex)
        }
        while (stackNodes.size >= MAX_TREE_DEPTH) {
            stackIndents.removeAt(stackIndents.lastIndex)
            stackNodes.removeAt(stackNodes.lastIndex)
        }
        val node = MutableNode(name = name, hasTrailingSlash = trimmed.endsWith("/"))
        if (stackNodes.isEmpty()) {
            roots += node
        } else {
            stackNodes[stackNodes.lastIndex].children += node
        }
        stackNodes += node
        stackIndents += indent
    }
    return roots.map { it.toTreeNode() }
}

private fun MutableNode.toTreeNode(): TreeNode = TreeNode(
    name = name,
    isFolder = hasTrailingSlash || children.isNotEmpty(),
    children = children.map { it.toTreeNode() },
)

/** The width of the leading whitespace: a space is one column, a tab is two. */
private fun indentColumns(line: String): Int {
    var columns = 0
    for (char in line) {
        if (!char.isWhitespace()) break
        columns += if (char == '\t') TAB_COLUMNS else 1
    }
    return columns
}

private fun labelOf(node: TreeNode): String = if (node.isFolder) node.name + "/" else node.name

/**
 * Draws [nodes] as a tree with `├──`, `└──` and `│` connectors. Top-level entries have no
 * connector; folders end with `/`.
 */
fun renderTree(nodes: List<TreeNode>): String {
    val out = StringBuilder()
    nodes.forEach { node ->
        if (out.isNotEmpty()) out.append('\n')
        out.append(labelOf(node))
        appendChildren(out, node.children, "")
    }
    return out.toString()
}

private fun appendChildren(out: StringBuilder, children: List<TreeNode>, prefix: String) {
    children.forEachIndexed { index, child ->
        val isLast = index == children.lastIndex
        out.append('\n')
        out.append(prefix)
        out.append(if (isLast) LAST_BRANCH else BRANCH)
        out.append(labelOf(child))
        val childPrefix = prefix + (if (isLast) BLANK_PAD else PIPE_PAD)
        appendChildren(out, child.children, childPrefix)
    }
}

/**
 * `mkdir -p` for every folder and `touch` for every file, as POSIX paths, one per line. Paths
 * are single-quoted when they contain spaces or anything else a shell could misread.
 */
fun buildMkdirCommands(nodes: List<TreeNode>): String {
    val lines = mutableListOf<String>()
    collectCommands(nodes, "", lines)
    return lines.joinToString("\n")
}

private fun collectCommands(nodes: List<TreeNode>, parentPath: String, lines: MutableList<String>) {
    nodes.forEach { node ->
        val path = if (parentPath.isEmpty()) node.name else parentPath + "/" + node.name
        // A leading dash would be read as an option, so anchor such paths with "./".
        val safe = if (path.startsWith("-")) "./" + path else path
        if (node.isFolder) {
            lines += "mkdir -p " + shellQuote(safe)
            collectCommands(node.children, path, lines)
        } else {
            lines += "touch " + shellQuote(safe)
        }
    }
}

private fun shellQuote(path: String): String =
    if (SAFE_PATH.matches(path)) path else "'" + path.replace("'", "'\\''") + "'"

/** How many folders and files a tree holds, as a pair (folders, files). */
fun countTree(nodes: List<TreeNode>): Pair<Int, Int> {
    var folders = 0
    var files = 0
    nodes.forEach { node ->
        if (node.isFolder) folders++ else files++
        val inner = countTree(node.children)
        folders += inner.first
        files += inner.second
    }
    return Pair(folders, files)
}

/** A starter outline offered in the folder planner's template sheet. */
internal data class FolderTemplate(val label: String, val outline: String)

internal val FOLDER_TEMPLATES: List<FolderTemplate> = listOf(
    FolderTemplate(
        label = "React + Vite app",
        outline = """
            public/
              favicon.svg
            src/
              components/
              hooks/
              pages/
                Home.tsx
              App.tsx
              main.tsx
              index.css
            .gitignore
            index.html
            package.json
            tsconfig.json
            vite.config.ts
            README.md
        """.trimIndent(),
    ),
    FolderTemplate(
        label = "Kotlin Android app",
        outline = """
            app/
              build.gradle.kts
              src/
                main/
                  AndroidManifest.xml
                  java/
                    com/
                      westly/
                        myapp/
                          MainActivity.kt
                          data/
                          ui/
                  res/
                    values/
                      strings.xml
            .github/
              workflows/
                build.yml
            build.gradle.kts
            settings.gradle.kts
            gradle.properties
            README.md
        """.trimIndent(),
    ),
    FolderTemplate(
        label = "Node API",
        outline = """
            src/
              config/
                env.ts
              controllers/
              middleware/
              routes/
                index.ts
              services/
              server.ts
            tests/
            .env.example
            .gitignore
            package.json
            tsconfig.json
            README.md
        """.trimIndent(),
    ),
    FolderTemplate(
        label = "Static website",
        outline = """
            css/
              style.css
            js/
              main.js
            images/
              logo.png
            index.html
            about.html
            contact.html
            README.md
        """.trimIndent(),
    ),
)
