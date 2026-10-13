package com.westly.neribovault.feature.coder

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.feature.coder.editor.CoderHighlighter
import com.westly.neribovault.feature.coder.editor.CoderLanguage
import com.westly.neribovault.feature.coder.editor.CoderUndoStack
import com.westly.neribovault.feature.coder.files.CoderFileStore
import com.westly.neribovault.feature.coder.files.CoderPaths
import com.westly.neribovault.feature.coder.files.CoderReadResult
import com.westly.neribovault.feature.coder.files.CoderWriteResult
import com.westly.neribovault.feature.coder.ui.CoderSyntaxColors
import java.io.File

/** The outcome of one Coder check. [detail] says what was expected when [passed] is false. */
data class CoderCheckResult(val name: String, val passed: Boolean, val detail: String)

private fun check(name: String, expectation: String, body: () -> Boolean): CoderCheckResult =
    try {
        val ok = body()
        CoderCheckResult(name, ok, if (ok) "ok" else "expected: $expectation")
    } catch (e: Exception) {
        CoderCheckResult(name, false, "threw " + e.javaClass.simpleName + ": " + (e.message ?: "no message"))
    }

private fun fileBytes(store: CoderFileStore, id: String, path: String): ByteArray? =
    store.resolve(id, path)?.readBytes()

/**
 * Checks the Coder engine that nobody can unit-test on this project: path rules, the file store,
 * the highlighter, the language map and the undo stack. Works in a temporary folder inside the app
 * cache and removes it at the end. Needs no PIN and no database.
 */
fun runCoderSelfTest(context: Context): List<CoderCheckResult> {
    val root = File(context.cacheDir, "coder-selftest-" + newId())
    val results = ArrayList<CoderCheckResult>()
    try {
        val store = CoderFileStore(root)
        results.addAll(pathChecks())
        results.addAll(resolveChecks(store))
        results.addAll(fileChecks(store))
        results.addAll(languageChecks())
        results.addAll(highlighterChecks())
        results.addAll(undoChecks())
    } finally {
        root.deleteRecursively()
    }
    return results
}

private fun pathChecks(): List<CoderCheckResult> {
    val out = ArrayList<CoderCheckResult>()
    for (path in listOf("a.txt", "src/app.js", ".gitignore")) {
        out.add(check("Path accepted: $path", "valid") { CoderPaths.isValidPath(path) })
    }
    val deep = (1..13).joinToString("/") { "d$it" }
    val rejected = listOf(
        "" to "empty", "/a" to "/a", "a/" to "a/", "../a" to "../a", "a/../b" to "a/../b",
        "a//b" to "a//b", "a\\b" to "a backslash b", "a:b" to "a:b", "a".repeat(201) to "201 characters",
        deep to "13 levels deep",
    )
    for ((path, label) in rejected) {
        out.add(check("Path refused: $label", "invalid") { !CoderPaths.isValidPath(path) })
    }
    return out
}

private fun resolveChecks(store: CoderFileStore): List<CoderCheckResult> {
    val id = newId()
    return listOf(
        check("resolve refuses ../x", "null") { store.resolve(id, "../x") == null },
        check("resolve refuses a/../../x", "null") { store.resolve(id, "a/../../x") == null },
        check("resolve refuses an absolute path", "null") { store.resolve(id, "/etc/passwd") == null },
        check("resolve refuses a bad project id", "null") {
            store.resolve("../other", "a.txt") == null && store.resolve("not an id!", "a.txt") == null
        },
        check("resolve of empty path is the project folder", "project folder") {
            store.resolve(id, "") == store.projectDir(id)
        },
    )
}

private fun fileChecks(store: CoderFileStore): List<CoderCheckResult> {
    val out = ArrayList<CoderCheckResult>()
    out.add(check("Unicode text round trip", "same text back") {
        val id = newId()
        val text = "h\u00E9llo \u2713"
        val read = store.write(id, "u.txt", text) == CoderWriteResult.Saved && store.read(id, "u.txt").let {
            it is CoderReadResult.Text && it.text == text && !it.usesCrlf
        }
        read
    })
    out.add(check("CRLF file reads as LF with usesCrlf", "LF text and usesCrlf") {
        val id = newId()
        store.resolve(id, "")?.mkdirs()
        store.resolve(id, "c.txt")?.writeBytes("a\r\nb\r\n".toByteArray(Charsets.UTF_8))
        store.read(id, "c.txt").let { it is CoderReadResult.Text && it.text == "a\nb\n" && it.usesCrlf }
    })
    out.add(check("Text with crlf writes back as CRLF", "CRLF bytes") {
        val id = newId()
        store.write(id, "c.txt", "a\nb\n", crlf = true) == CoderWriteResult.Saved &&
            fileBytes(store, id, "c.txt")?.toString(Charsets.UTF_8) == "a\r\nb\r\n"
    })
    out.add(check("BOM is stripped on read", "text without BOM") {
        val id = newId()
        store.resolve(id, "")?.mkdirs()
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        store.resolve(id, "b.txt")?.writeBytes(bom + "hi".toByteArray(Charsets.UTF_8))
        store.read(id, "b.txt").let { it is CoderReadResult.Text && it.text == "hi" }
    })
    out.add(check("NUL byte file is Binary", "Binary") {
        val id = newId()
        store.resolve(id, "")?.mkdirs()
        store.resolve(id, "n.bin")?.writeBytes(byteArrayOf(65, 0, 66))
        store.read(id, "n.bin") == CoderReadResult.Binary
    })
    out.add(check("Invalid UTF-8 file is Binary", "Binary") {
        val id = newId()
        store.resolve(id, "")?.mkdirs()
        store.resolve(id, "bad.txt")?.writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        store.read(id, "bad.txt") == CoderReadResult.Binary
    })
    out.add(check("300,001 bytes is TooLarge", "TooLarge on read and write") {
        val id = newId()
        store.resolve(id, "")?.mkdirs()
        store.resolve(id, "big.txt")?.writeBytes(ByteArray(300_001) { 97 })
        store.read(id, "big.txt") == CoderReadResult.TooLarge &&
            store.write(id, "big2.txt", "a".repeat(300_001)) == CoderWriteResult.TooLarge &&
            store.resolve(id, "big2.txt")?.exists() == false
    })
    out.add(check("createFile twice gives AlreadyExists", "second call AlreadyExists") {
        val id = newId()
        store.createFile(id, "x.txt") == CoderWriteResult.Saved &&
            store.createFile(id, "x.txt") == CoderWriteResult.AlreadyExists
    })
    out.add(check("Folder cannot be renamed into itself", "not Saved, folder still there") {
        val id = newId()
        store.createFolder(id, "a")
        store.createFile(id, "a/f.txt", "x")
        store.rename(id, "a", "a/b") != CoderWriteResult.Saved && store.resolve(id, "a/f.txt")?.exists() == true
    })
    out.add(check("rename to an existing name gives AlreadyExists", "AlreadyExists") {
        val id = newId()
        store.createFile(id, "x.txt")
        store.createFile(id, "y.txt")
        store.rename(id, "x.txt", "y.txt") == CoderWriteResult.AlreadyExists
    })
    out.add(check("delete of the project root is refused", "false and folder kept") {
        val id = newId()
        store.createFile(id, "x.txt")
        !store.delete(id, "") && store.projectDir(id).exists()
    })
    out.add(check("listAll puts folders first, then files by name", "folders first, case-insensitive") {
        val id = newId()
        store.createFile(id, "b.txt")
        store.createFile(id, "A.txt")
        store.createFile(id, "z/inner.txt")
        store.createFile(id, "Y/inner.txt")
        store.createFolder(id, "m")
        val paths = store.listAll(id).map { it.path }
        paths == listOf("m", "Y", "Y/inner.txt", "z", "z/inner.txt", "A.txt", "b.txt")
    })
    out.add(check("Atomic write leaves no temp file", "no .tmp- files") {
        val id = newId()
        for (i in 1..5) store.write(id, "sub/t.txt", "version $i")
        store.projectDir(id).walkTopDown().none { it.name.contains(".tmp-") } &&
            store.read(id, "sub/t.txt").let { it is CoderReadResult.Text && it.text == "version 5" }
    })
    return out
}

private fun languageChecks(): List<CoderCheckResult> = listOf(
    check("Language by extension (10 files)", "all ten match") {
        CoderLanguage.fromPath("index.html") == CoderLanguage.Html &&
            CoderLanguage.fromPath("src/style.css") == CoderLanguage.Css &&
            CoderLanguage.fromPath("app.js") == CoderLanguage.JavaScript &&
            CoderLanguage.fromPath("app.ts") == CoderLanguage.TypeScript &&
            CoderLanguage.fromPath("data.json") == CoderLanguage.Json &&
            CoderLanguage.fromPath("README.md") == CoderLanguage.Markdown &&
            CoderLanguage.fromPath("main.py") == CoderLanguage.Python &&
            CoderLanguage.fromPath("Main.kt") == CoderLanguage.Kotlin &&
            CoderLanguage.fromPath("run.sh") == CoderLanguage.Shell &&
            CoderLanguage.fromPath("notes.xyz") == CoderLanguage.Plain &&
            CoderLanguage.fromPath("INDEX.HTML") == CoderLanguage.Html
    },
)

private fun highlighterChecks(): List<CoderCheckResult> {
    val colors = CoderSyntaxColors(
        keyword = Color.Red, string = Color.Green, number = Color.Blue, comment = Color.Gray,
        tag = Color.Cyan, attribute = Color.Magenta, plain = Color.Black,
    )
    fun same(text: String, language: CoderLanguage): Boolean =
        CoderHighlighter.highlight(text, language, colors).text == text
    return listOf(
        check("Highlighter keeps text with an unterminated string", "same text") {
            same("var s = \"abc\nnext line", CoderLanguage.JavaScript) && same("<a href=\"x", CoderLanguage.Html)
        },
        check("Highlighter keeps text with an unterminated comment", "same text") {
            same("a /* b", CoderLanguage.Css) && same("<!-- open", CoderLanguage.Html)
        },
        check("Highlighter keeps empty text", "same text") { same("", CoderLanguage.Kotlin) },
        check("Highlighter skips 150,000 characters", "same text and no spans") {
            val big = "x".repeat(150_000)
            val result = CoderHighlighter.highlight(big, CoderLanguage.JavaScript, colors)
            result.text == big && result.spanStyles.isEmpty()
        },
    )
}

private fun undoChecks(): List<CoderCheckResult> = listOf(
    check("Undo stack order", "undo and redo in order") {
        val stack = CoderUndoStack()
        stack.push(TextFieldValue("A"))
        stack.push(TextFieldValue("B"))
        val b = stack.undo(TextFieldValue("C"))
        val a = stack.undo(TextFieldValue("B"))
        val none = stack.undo(TextFieldValue("A"))
        val redoB = stack.redo(TextFieldValue("A"))
        b?.text == "B" && a?.text == "A" && none == null && redoB?.text == "B" && stack.canUndo && stack.canRedo
    },
    check("Undo stack keeps 200 steps", "200 steps") {
        val stack = CoderUndoStack()
        for (i in 1..250) stack.push(TextFieldValue("v$i"))
        var steps = 0
        var current = TextFieldValue("now")
        while (true) {
            val previous = stack.undo(current) ?: break
            current = previous
            steps++
        }
        steps == 200
    },
)
