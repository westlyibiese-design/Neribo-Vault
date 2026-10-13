package com.westly.neribovault.feature.coder

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
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
import com.westly.neribovault.feature.coder.preview.CoderConsole
import com.westly.neribovault.feature.coder.preview.CoderMarkdown
import com.westly.neribovault.feature.coder.preview.CoderMime
import com.westly.neribovault.feature.coder.preview.CoderPreviewHost
import com.westly.neribovault.feature.coder.preview.CoderPreviewResponder
import com.westly.neribovault.feature.coder.preview.CoderPreviewRoot
import com.westly.neribovault.feature.coder.preview.ConsoleLevel
import com.westly.neribovault.feature.coder.templates.CoderTemplates
import com.westly.neribovault.feature.coder.ui.CoderSyntaxColors
import com.westly.neribovault.feature.coder.zip.CoderExportResult
import com.westly.neribovault.feature.coder.zip.CoderImportReport
import com.westly.neribovault.feature.coder.zip.CoderImportResult
import com.westly.neribovault.feature.coder.zip.CoderZipExporter
import com.westly.neribovault.feature.coder.zip.CoderZipRules
import com.westly.neribovault.feature.coder.zip.importStream
import com.westly.neribovault.feature.coder.zip.writeProjectZip
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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
        results.addAll(previewChecks(context, store))
        results.addAll(markdownChecks())
        results.addAll(consoleChecks())
        results.addAll(templateChecks())
        results.addAll(zipChecks(context, store, root))
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

private fun previewChecks(context: Context, store: CoderFileStore): List<CoderCheckResult> {
    val out = ArrayList<CoderCheckResult>()
    val id = newId()
    val host = CoderPreviewHost.hostFor(id)
    store.write(id, "index.html", "<p>home</p>")
    store.write(id, "a/index.html", "<p>a</p>")
    store.write(id, "a/b.css", "body{}")
    store.write(id, "a b.js", "1")
    store.write(id, "__coder/secret.txt", "secret")
    store.write(id, "README.md", "# Title\n\n**bold**")
    store.write(id, "data.json", "{\"a\":1,\"u\":\"x/y\"}")
    store.write(id, "bad.json", "{\"a\":")
    val responder = CoderPreviewResponder(context, store, id, "")
    fun get(path: String, method: String = "GET"): WebResourceResponse? =
        responder.respondTo(Uri.parse("https://$host$path"), method)
    fun body(response: WebResourceResponse?): String =
        response?.data?.readBytes()?.toString(Charsets.UTF_8) ?: ""

    val mimes = listOf(
        "a.html" to "text/html", "a.css" to "text/css", "a.js" to "text/javascript", "a.json" to "application/json",
        "a.svg" to "image/svg+xml", "a.PNG" to "image/png", "a.jpg" to "image/jpeg", "a.woff2" to "font/woff2",
        "a.mp4" to "video/mp4", "a.md" to "text/plain", "a.wasm" to "application/wasm", "a.xyz" to "application/octet-stream",
    )
    for ((path, mime) in mimes) {
        out.add(check("Content type of $path", mime) { CoderMime.forPath(path) == mime })
    }
    out.add(check("Text types are recognised", "text and json yes, png no") {
        CoderMime.isTextMime("text/css") && CoderMime.isTextMime("application/json") && !CoderMime.isTextMime("image/png")
    })
    out.add(check("Preview host is lower case, dash free and short", "valid host") {
        val upper = CoderPreviewHost.hostFor("ABCDEF12-3456-7890-ABCD-EF1234567890")
        upper == upper.lowercase() && !upper.contains("-") && upper.endsWith(".coder.invalid") &&
            upper.substringBefore('.').length < 63
    })
    out.add(check("Preview address encodes each part", "spaces become %20, slashes stay") {
        CoderPreviewHost.urlFor(id, "a b/c d.js") == "https://$host/a%20b/c%20d.js"
    })
    out.add(check("Preview root needs an index.html", "empty string, then null") {
        CoderPreviewRoot.find(store, id) == "" && CoderPreviewRoot.find(store, newId()) == null
    })
    val expectedStatus = listOf(
        "/" to 200, "/a/" to 200, "/a" to 200, "/a/b.css" to 200, "/a%20b.js" to 200,
        "/%20x.js" to 404, "/../x" to 404, "/%2e%2e/x" to 404, "/nothing.txt" to 404,
        "/__coder/secret.txt" to 404, "/__coder/x" to 404, "/__coder" to 404,
        "/__coder/md/README.md" to 200, "/__coder/json/data.json" to 200, "/__coder/md/missing.md" to 404,
    )
    for ((path, status) in expectedStatus) {
        out.add(check("Preview answers $path with $status", status.toString()) { get(path)?.statusCode == status })
    }
    out.add(check("Preview sends the right type for a stylesheet", "text/css") { get("/a/b.css")?.mimeType == "text/css" })
    out.add(check("Preview refuses POST", "405") { get("/", "POST")?.statusCode == 405 })
    out.add(check("Preview answers HEAD without a body", "200 and empty") {
        val r = get("/", "HEAD")
        r?.statusCode == 200 && body(r).isEmpty()
    })
    out.add(check("Preview blocks http", "403") {
        responder.respondTo(Uri.parse("http://$host/"), "GET")?.statusCode == 403
    })
    out.add(check("Preview blocks other schemes", "403") {
        responder.respondTo(Uri.parse("ftp://$host/"), "GET")?.statusCode == 403
    })
    out.add(check("Preview lets other https hosts through", "null") {
        responder.respondTo(Uri.parse("https://example.com/"), "GET") == null
    })
    out.add(check("Markdown page is built from the file", "contains a heading and bold text") {
        val html = body(get("/__coder/md/README.md"))
        html.contains("<h1>Title</h1>") && html.contains("<strong>bold</strong>")
    })
    out.add(check("JSON page is pretty printed and escaped", "quoted keys, readable slash") {
        val html = body(get("/__coder/json/data.json"))
        html.contains("&quot;a&quot;: 1") && html.contains("x/y") && !html.contains("\\/")
    })
    out.add(check("Broken JSON shows the raw text and a message", "message plus raw text") {
        val html = body(get("/__coder/json/bad.json"))
        html.contains("valid JSON") && html.contains("&quot;a&quot;")
    })
    return out
}

private fun markdownChecks(): List<CoderCheckResult> {
    fun html(md: String): String = CoderMarkdown.toHtml(md)
    return listOf(
        check("Markdown escapes raw HTML", "text, not a tag") {
            val h = html("<script>alert(1)</script>")
            h.contains("&lt;script&gt;") && !h.contains("<script")
        },
        check("Markdown refuses javascript: links", "no link") {
            !html("[x](javascript:alert(1))").contains("href")
        },
        check("Markdown refuses hidden-space javascript: links", "no link") {
            !html("[x](java\tscript:alert(1))").contains("href") && !html("[x](JaVaScRiPt:alert(1))").contains("href")
        },
        check("Markdown refuses data: images", "no image") { !html("![x](data:text/html,hi)").contains("<img") },
        check("Markdown keeps web links", "a link") {
            html("[x](https://example.com/a?b=1&c=2)").contains("<a href=\"https://example.com/a?b=1&amp;c=2\">x</a>")
        },
        check("Markdown bold, italic and code", "strong, em, code") {
            val h = html("**b** *i* _j_ `c`")
            h.contains("<strong>b</strong>") && h.contains("<em>i</em>") && h.contains("<em>j</em>") && h.contains("<code>c</code>")
        },
        check("Markdown lists and quotes", "ul, ol, blockquote") {
            val h = html("- a\n- b\n\n1. c\n2. d\n\n> q")
            h.contains("<ul>") && h.contains("<ol>") && h.contains("<blockquote>")
        },
        check("Markdown fenced code is escaped", "pre with escaped text") {
            html("```\n<b>x</b>\n```").contains("<pre><code>&lt;b&gt;x&lt;/b&gt;</code></pre>")
        },
        check("Markdown shows unfinished markup literally", "no crash, text kept") {
            val h = html("```\ncode\n**bold *x _y `z [a](b")
            h.contains("code") && h.contains("**bold")
        },
        check("Markdown handles 200,000 characters of odd input", "finishes in time") {
            val start = System.nanoTime()
            val inputs = listOf(
                "a *b* **c** _d_ `e` [f](https://x.y) ".repeat(5_500),
                "[".repeat(200_000),
                "* ".repeat(100_000),
                "_ ".repeat(100_000),
                "**a ".repeat(50_000),
                "`".repeat(200_000),
            )
            for (input in inputs) html(input)
            (System.nanoTime() - start) / 1_000_000 < 10_000
        },
    )
}

private fun consoleChecks(): List<CoderCheckResult> {
    val host = CoderPreviewHost.hostFor(newId())
    return listOf(
        check("Console keeps the last 200 messages", "200 kept, errors counted, clear resets") {
            val console = CoderConsole(host, "")
            repeat(250) { console.add(ConsoleLevel.Log, "m$it", null, 0) }
            console.add(ConsoleLevel.Error, "boom", null, 0)
            val kept = console.messages.size == 200 && console.messages.last().text == "boom" && console.errorCount == 1
            console.clear()
            kept && console.messages.isEmpty() && console.errorCount == 0
        },
        check("Console turns page addresses into project files", "relative file or null") {
            val console = CoderConsole(host, "dist")
            console.relativeSource("https://$host/dist/app.js?x=1#y") == "app.js" &&
                console.relativeSource("https://$host/") == "index.html" &&
                console.relativeSource("https://example.com/app.js") == null &&
                console.relativeSource(null) == null
        },
    )
}

private fun templateChecks(): List<CoderCheckResult> {
    val all = CoderTemplates.all
    return listOf(
        check("There are five templates with the agreed ids", "blank, hello-web, counter, todo, notes-md") {
            all.map { it.id } == listOf("blank", "hello-web", "counter", "todo", "notes-md")
        },
        check("Every template file has a valid path, fits the limit and works offline", "valid, under 3 KB, no remote scripts") {
            all.all { template ->
                template.files.isNotEmpty() && template.files.all { file ->
                    CoderPaths.isValidPath(file.path) &&
                        file.content.toByteArray(Charsets.UTF_8).size <= 3_072 &&
                        !file.content.contains("src=\"http") &&
                        !file.content.contains("stylesheet\" href=\"http")
                }
            }
        },
        check("Every template can be found by id", "found") { all.all { CoderTemplates.find(it.id) == it } },
    )
}

private fun zipChecks(context: Context, store: CoderFileStore, root: File): List<CoderCheckResult> {
    val out = ArrayList<CoderCheckResult>()
    out.addAll(zipNameChecks())
    out.addAll(zipRuleChecks())
    out.addAll(zipRoundTripChecks(context, store, root))
    out.addAll(previewRootChecks(store))
    return out
}

private fun zipNameChecks(): List<CoderCheckResult> {
    val out = ArrayList<CoderCheckResult>()
    val names = listOf(
        "a/b.txt" to "a/b.txt", "a\\b.txt" to "a/b.txt", "./a.txt" to "a.txt", "dir/" to "dir",
        "../evil" to null, "a/../../evil" to null, "/abs" to null, "C:\\x" to null, "" to null,
    )
    for ((raw, expected) in names) {
        out.add(check("ZIP name \"$raw\" becomes ${expected ?: "refused"}", expected ?: "null") {
            CoderZipRules.normalizeEntryName(raw) == expected
        })
    }
    val skipped = listOf(
        "node_modules/x/y.js", "src/node_modules/a", ".git/config", "__MACOSX/._a", "a/.DS_Store", "docs/Thumbs.db",
    )
    for (path in skipped) {
        out.add(check("ZIP skips $path", "a skip reason") { CoderZipRules.shouldSkip(path) != null })
    }
    out.add(check("ZIP keeps src/app.js and .gitignore", "no skip reason") {
        CoderZipRules.shouldSkip("src/app.js") == null && CoderZipRules.shouldSkip(".gitignore") == null
    })
    return out
}

private fun zipRuleChecks(): List<CoderCheckResult> = listOf(
    check("Outer folder is found", "repo-main") {
        CoderZipRules.stripWrapperFolder(listOf("repo-main/a", "repo-main/b/c")) == "repo-main"
    },
    check("Mixed top folders have no outer folder", "null") {
        CoderZipRules.stripWrapperFolder(listOf("a/x", "b/y")) == null &&
            CoderZipRules.stripWrapperFolder(listOf("repo/a.txt", "b.txt")) == null
    },
    check("A lone top-level file has no outer folder", "null") {
        CoderZipRules.stripWrapperFolder(listOf("a.txt")) == null
    },
    check("Project name comes from the file name", "My App, then Imported project") {
        CoderZipRules.projectNameFromFile("My App.ZIP") == "My App" &&
            CoderZipRules.projectNameFromFile(null) == "Imported project" &&
            CoderZipRules.projectNameFromFile(".zip") == "Imported project" &&
            CoderZipRules.projectNameFromFile("x".repeat(80) + ".zip").length == 60
    },
    check("Same name gets (2), then (3)", "Name (2), Name (3)") {
        CoderZipRules.uniqueProjectName("Name", listOf("Other")) == "Name" &&
            CoderZipRules.uniqueProjectName("Name", listOf("Name")) == "Name (2)" &&
            CoderZipRules.uniqueProjectName("Name", listOf("Name", "name (2)")) == "Name (3)" &&
            CoderZipRules.uniqueProjectName("y".repeat(60), listOf("y".repeat(60))).length <= 60
    },
    check("Zip bomb ratio rule", "above 200 times is refused; unknown sizes are not") {
        CoderZipRules.isSuspiciousRatio(201_000L, 1_000L) &&
            !CoderZipRules.isSuspiciousRatio(200_000L, 1_000L) &&
            !CoderZipRules.isSuspiciousRatio(500_000L, -1L) &&
            !CoderZipRules.isSuspiciousRatio(500_000L, 0L)
    },
)

private fun buildTestZip(): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        fun add(name: String, data: ByteArray) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(data)
            zip.closeEntry()
        }
        zip.putNextEntry(ZipEntry("repo-main/"))
        zip.closeEntry()
        add("repo-main/index.html", "<h1>Hi \u00E9</h1>".toByteArray(Charsets.UTF_8))
        add("repo-main/css/style.css", "body{}".toByteArray(Charsets.UTF_8))
        add("repo-main/img/logo.png", byteArrayOf(-119, 80, 78, 71, 0, 1, 2, 3))
        add("repo-main/node_modules/x/y.js", "1".toByteArray(Charsets.UTF_8))
        add("../evil.txt", "evil".toByteArray(Charsets.UTF_8))
        add("repo-main/A.txt", "first".toByteArray(Charsets.UTF_8))
        add("repo-main/a.txt", "second".toByteArray(Charsets.UTF_8))
    }
    return bytes.toByteArray()
}

private fun zipEntryNames(bytes: ByteArray): Set<String> {
    val names = HashSet<String>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            names.add(entry.name)
        }
    }
    return names
}

private fun zipRoundTripChecks(context: Context, store: CoderFileStore, root: File): List<CoderCheckResult> {
    val out = ArrayList<CoderCheckResult>()
    val zipBytes = buildTestZip()
    val keepAll: suspend (String, String) -> Boolean = { _, _ -> true }
    val first = try {
        runBlocking { importStream(store, { ByteArrayInputStream(zipBytes) }, "Test Site.zip", keepAll) }
    } catch (e: Exception) {
        CoderImportResult.Failure("threw " + e.javaClass.simpleName)
    }
    val success = first as? CoderImportResult.Success
    val id = success?.projectId ?: ""
    val report = success?.report
    out.add(check("ZIP import of the test site succeeds", "Success") { success != null })
    out.add(check("ZIP import keeps the right files and folders", "css, img and three files") {
        store.listAll(id).map { it.path } == listOf("css", "css/style.css", "img", "img/logo.png", "A.txt", "index.html")
    })
    out.add(check("ZIP import removes the outer folder", "repo-main") { report?.strippedWrapperFolder == "repo-main" })
    out.add(check("ZIP import counts files", "4 added, 3 skipped") { report != null && report.importedFiles == 4 && report.skippedFiles == 3 })
    out.add(check("ZIP import reports what it skipped", "node_modules and unsafe or duplicate names") {
        report != null && report.skippedReasons.any { it.startsWith("node_modules") } &&
            report.skippedReasons.any { it.startsWith("unsafe or duplicate") }
    })
    out.add(check("ZIP import never writes outside the project", "no evil.txt anywhere") {
        val above = store.projectDir(id).parentFile
        above != null && !File(above, "evil.txt").exists() && store.listAll(id).none { it.path.contains("evil") }
    })
    out.add(check("ZIP import keeps the first of two names that differ only by case", "A.txt holds first, no second file") {
        store.read(id, "A.txt").let { it is CoderReadResult.Text && it.text == "first" } &&
            store.listAll(id).count { it.path.equals("a.txt", ignoreCase = true) } == 1
    })
    out.add(check("ZIP import keeps binary bytes exactly", "same 8 bytes") {
        fileBytes(store, id, "img/logo.png")?.toList() == listOf<Byte>(-119, 80, 78, 71, 0, 1, 2, 3)
    })

    val exportFile = File(context.cacheDir, "coder-selftest-export-" + newId() + ".zip")
    try {
        val exported = try {
            runBlocking {
                FileOutputStream(exportFile).use { stream -> writeProjectZip(store, id, store.listAll(id), stream) }
            }
        } catch (e: Exception) {
            -1
        }
        out.add(check("ZIP export counts files", "4") { exported == 4 })
        out.add(check("ZIP export keeps folders and names", "folder entries end in a slash") {
            zipEntryNames(exportFile.readBytes()) ==
                setOf("css/", "css/style.css", "img/", "img/logo.png", "A.txt", "index.html")
        })
        val again = try {
            runBlocking { importStream(store, { exportFile.inputStream() }, "Test Site.zip", keepAll) }
        } catch (e: Exception) {
            CoderImportResult.Failure("threw " + e.javaClass.simpleName)
        }
        val againSuccess = again as? CoderImportResult.Success
        out.add(check("An exported ZIP imports again with the same files", "same list, no outer folder") {
            againSuccess != null && againSuccess.report.strippedWrapperFolder == null &&
                store.listAll(againSuccess.projectId).map { it.path } == store.listAll(id).map { it.path }
        })
    } finally {
        exportFile.delete()
    }

    val emptyTarget = File(context.cacheDir, "coder-selftest-empty-" + newId() + ".zip")
    out.add(check("An empty project is not exported", "Failure that says the project is empty") {
        val result = runBlocking { CoderZipExporter(context, store).export(newId(), Uri.fromFile(emptyTarget)) }
        result is CoderExportResult.Failure && result.message.contains("empty") && !emptyTarget.exists()
    })

    val before = root.list()?.size ?: 0
    val notZip = try {
        runBlocking { importStream(store, { ByteArrayInputStream("just some text".toByteArray(Charsets.UTF_8)) }, "x.zip", keepAll) }
    } catch (e: Exception) {
        CoderImportResult.Failure("threw " + e.javaClass.simpleName)
    }
    out.add(check("A text file is refused as not a ZIP", "Failure about a valid ZIP") {
        notZip is CoderImportResult.Failure && notZip.message.contains("valid ZIP")
    })
    val onlySkipped = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("node_modules/a.js"))
            zip.write(1)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("empty-folder/"))
            zip.closeEntry()
        }
    }.toByteArray()
    val noFiles = try {
        runBlocking { importStream(store, { ByteArrayInputStream(onlySkipped) }, "x.zip", keepAll) }
    } catch (e: Exception) {
        CoderImportResult.Failure("threw " + e.javaClass.simpleName)
    }
    out.add(check("A ZIP with nothing importable is refused", "Failure about no files") {
        noFiles is CoderImportResult.Failure && noFiles.message.contains("no files")
    })
    val noRow = try {
        runBlocking { importStream(store, { ByteArrayInputStream(zipBytes) }, "x.zip", { _, _ -> false }) }
    } catch (e: Exception) {
        CoderImportResult.Success("", CoderImportReport(0, 0, emptyList(), null))
    }
    out.add(check("An import whose project row fails removes its files", "Failure") { noRow is CoderImportResult.Failure })
    out.add(check("A refused ZIP leaves no project folder behind", "same number of folders") {
        (root.list()?.size ?: 0) == before
    })
    return out
}

private fun previewRootChecks(store: CoderFileStore): List<CoderCheckResult> {
    fun rootOf(vararg files: String): String? {
        val id = newId()
        for (path in files) store.write(id, path, "<p>x</p>")
        return CoderPreviewRoot.find(store, id)
    }
    return listOf(
        check("Preview root: index.html at the top wins", "empty string") { rootOf("index.html", "dist/index.html") == "" },
        check("Preview root: dist", "dist") { rootOf("dist/index.html") == "dist" },
        check("Preview root: dist comes before build, out and docs", "dist") {
            rootOf("docs/index.html", "out/index.html", "build/index.html", "dist/index.html") == "dist"
        },
        check("Preview root: build, then out, then docs", "build, out, docs") {
            rootOf("docs/index.html", "out/index.html", "build/index.html") == "build" &&
                rootOf("docs/index.html", "out/index.html") == "out" &&
                rootOf("docs/index.html") == "docs"
        },
        check("Preview root: no index.html is null", "null") { rootOf("src/index.html", "dist/main.js") == null },
    )
}
