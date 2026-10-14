package com.westly.neribovault.feature.coder

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.WebResourceResponse
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.entity.ProjectEntity
import com.westly.neribovault.feature.coder.ai.CODER_SYSTEM_PROMPT
import com.westly.neribovault.feature.coder.ai.ChatRole
import com.westly.neribovault.feature.coder.ai.CoderAiClient
import com.westly.neribovault.feature.coder.ai.CoderAiConfig
import com.westly.neribovault.feature.coder.ai.CoderAiProvider
import com.westly.neribovault.feature.coder.ai.CoderChatFailure
import com.westly.neribovault.feature.coder.ai.CoderChatMessage
import com.westly.neribovault.feature.coder.ai.CoderChatResult
import com.westly.neribovault.feature.coder.ai.CoderCodeBlock
import com.westly.neribovault.feature.coder.ai.CoderReplySegment
import com.westly.neribovault.feature.coder.ai.ContextFile
import com.westly.neribovault.feature.coder.ai.buildHistory
import com.westly.neribovault.feature.coder.ai.buildProjectInfo
import com.westly.neribovault.feature.coder.ai.buildUserTurn
import com.westly.neribovault.feature.coder.ai.parseReply
import com.westly.neribovault.feature.coder.ai.CoderKeyCrypto
import com.westly.neribovault.feature.coder.ai.cleanKey
import com.westly.neribovault.feature.coder.ai.cleanModelName
import com.westly.neribovault.feature.coder.ai.describeFailure
import com.westly.neribovault.feature.coder.ai.parseModels
import com.westly.neribovault.feature.coder.editor.CoderHighlighter
import com.westly.neribovault.feature.coder.editor.CoderLanguage
import com.westly.neribovault.feature.coder.editor.CoderUndoStack
import com.westly.neribovault.feature.coder.files.CoderFileStore
import com.westly.neribovault.feature.coder.files.CoderPaths
import com.westly.neribovault.feature.coder.files.CoderReadResult
import com.westly.neribovault.feature.coder.files.CoderWriteResult
import com.westly.neribovault.feature.coder.github.CoderGitHubErrorKind
import com.westly.neribovault.feature.coder.github.CoderGitHubErrors
import com.westly.neribovault.feature.coder.github.CoderGitHubKeyCrypto
import com.westly.neribovault.feature.coder.github.CoderGitHubRequests
import com.westly.neribovault.feature.coder.github.CoderGitSha
import com.westly.neribovault.feature.coder.github.CoderPushPlanner
import com.westly.neribovault.feature.coder.github.CoderSecretScanner
import com.westly.neribovault.feature.coder.github.LocalFile
import com.westly.neribovault.feature.coder.github.RemoteEntry
import com.westly.neribovault.feature.coder.github.TreeEntryInput
import com.westly.neribovault.feature.coder.github.cleanGitHubToken
import com.westly.neribovault.feature.coder.github.isValidRepoName
import com.westly.neribovault.feature.coder.github.parseRepoRef
import com.westly.neribovault.feature.coder.preview.CoderBuildCheck
import com.westly.neribovault.feature.coder.preview.CoderBuildDetector
import com.westly.neribovault.feature.coder.preview.CoderCdnShim
import com.westly.neribovault.feature.coder.preview.CoderConsole
import com.westly.neribovault.feature.coder.preview.CoderErrorExplainer
import com.westly.neribovault.feature.coder.preview.CoderMarkdown
import com.westly.neribovault.feature.coder.preview.CoderMime
import com.westly.neribovault.feature.coder.preview.CoderPreviewHost
import com.westly.neribovault.feature.coder.preview.CoderPreviewResponder
import com.westly.neribovault.feature.coder.preview.CoderPreviewRoot
import com.westly.neribovault.feature.coder.preview.CoderVendorAsset
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
import okhttp3.Request
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.net.ssl.SSLException

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
        results.addAll(shimChecks())
        results.addAll(buildDetectorChecks(store))
        results.addAll(explainerChecks())
        results.addAll(reactTemplateChecks())
        results.addAll(aiProviderChecks())
        results.addAll(aiKeyChecks())
        results.addAll(aiModelParsingChecks())
        results.addAll(aiFailureTextChecks())
        results.addAll(aiCryptoChecks())
        results.addAll(aiChatChecks())
        results.addAll(githubChecks(store))
        results.addAll(developerLinkChecks())
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
        check("There are seven templates with the agreed ids", "blank, hello-web, counter, todo, notes-md, react-hello, react-counter") {
            all.map { it.id } == listOf("blank", "hello-web", "counter", "todo", "notes-md", "react-hello", "react-counter")
        },
        check("Every template file has a valid path, fits the limit and works offline", "valid, under 3 KB, only the bundled React scripts are remote") {
            all.all { template ->
                template.files.isNotEmpty() && template.files.all { file ->
                    var rest = file.content
                    for (url in REACT_SCRIPT_URLS) rest = rest.replace("src=\"" + url + "\"", "")
                    CoderPaths.isValidPath(file.path) &&
                        file.content.toByteArray(Charsets.UTF_8).size <= 3_072 &&
                        !rest.contains("src=\"http") &&
                        !rest.contains("stylesheet\" href=\"http")
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

/** The three addresses the React templates load; the app answers them from its own files. */
private val REACT_SCRIPT_URLS = listOf(
    "https://unpkg.com/react@18/umd/react.development.js",
    "https://unpkg.com/react-dom@18/umd/react-dom.development.js",
    "https://unpkg.com/@babel/standalone/babel.min.js",
)

private fun shimChecks(): List<CoderCheckResult> {
    fun asset(url: String): CoderVendorAsset? = CoderCdnShim.match(Uri.parse(url))
    val out = ArrayList<CoderCheckResult>()
    val matches = listOf(
        REACT_SCRIPT_URLS[0] to CoderVendorAsset.React,
        REACT_SCRIPT_URLS[1] to CoderVendorAsset.ReactDom,
        REACT_SCRIPT_URLS[2] to CoderVendorAsset.Babel,
        "https://cdn.jsdelivr.net/npm/react@18.3.1/umd/react.development.js" to CoderVendorAsset.React,
        "https://cdn.jsdelivr.net/npm/react-dom@18.3.1/umd/react-dom.development.js" to CoderVendorAsset.ReactDom,
        "https://cdn.jsdelivr.net/npm/@babel/standalone/babel.min.js" to CoderVendorAsset.Babel,
        "https://cdnjs.cloudflare.com/ajax/libs/react/18.3.1/umd/react.development.js" to CoderVendorAsset.React,
        "https://cdnjs.cloudflare.com/ajax/libs/react-dom/18.3.1/umd/react-dom.development.js" to CoderVendorAsset.ReactDom,
        "https://cdnjs.cloudflare.com/ajax/libs/babel-standalone/7.24.0/babel.min.js" to CoderVendorAsset.Babel,
        "https://unpkg.com/react@18.2.0/umd/react.production.min.js" to CoderVendorAsset.React,
        "https://unpkg.com/react-dom@18.x/umd/react-dom.production.min.js" to CoderVendorAsset.ReactDom,
        "https://unpkg.com/@babel/standalone@7.24.0/babel.js" to CoderVendorAsset.Babel,
        "https://unpkg.com/react@18/umd/react.development.js?v=1" to CoderVendorAsset.React,
    )
    for ((url, expected) in matches) {
        out.add(check("Shim serves $url", expected.name) { asset(url) == expected })
    }
    val refused = listOf(
        "https://unpkg.com/react@19/umd/react.development.js" to "react 19",
        "https://unpkg.com/react/umd/react.development.js" to "no version",
        "http://unpkg.com/react@18/umd/react.development.js" to "http",
        "https://evil.com/react@18/umd/react.development.js" to "another host",
        "https://unpkg.com/react@18/umd/react.development.js/extra" to "extra segment",
        "https://unpkg.com/react@180/umd/react.development.js" to "version 180",
        "https://unpkg.com/@babel/standalone@8.0.0/babel.min.js" to "babel 8",
        "https://cdn.jsdelivr.net/react@18/umd/react.development.js" to "jsdelivr without /npm",
    )
    for ((url, label) in refused) {
        out.add(check("Shim refuses $label", "null") { asset(url) == null })
    }
    return out
}

private fun buildDetectorChecks(store: CoderFileStore): List<CoderCheckResult> {
    fun project(vararg files: Pair<String, String>): String {
        val id = newId()
        for ((path, text) in files) store.write(id, path, text)
        return id
    }
    val viteId = project(
        "index.html" to "<div id=\"root\"></div><script type=\"module\" src=\"/src/main.jsx\"></script>",
        "package.json" to "{\"devDependencies\":{\"vite\":\"^5.0.0\"},\"scripts\":{\"build\":\"vite build\"}}",
        "src/main.jsx" to "console.log(1)",
    )
    val distId = project("dist/index.html" to "<p>built</p>", "dist/app.js" to "1")
    val classicId = project(
        "index.html" to "<script src=\"" + REACT_SCRIPT_URLS[0] + "\"></script><script type=\"text/babel\" src=\"app.jsx\"></script>",
        "app.jsx" to "function App() { return <p>hi</p>; }",
    )
    val packageOnlyId = project("package.json" to "{\"dependencies\":{\"react-scripts\":\"5.0.1\"}}", "src/index.js" to "1")
    return listOf(
        check("Build detector: Vite source layout needs a build", "NeedsBuild that names Vite") {
            val result = CoderBuildDetector.check(store, viteId)
            result is CoderBuildCheck.NeedsBuild && result.reason.contains("Vite")
        },
        check("Build detector: a built dist folder is ready", "Ready") {
            CoderBuildDetector.check(store, distId) == CoderBuildCheck.Ready
        },
        check("Build detector: script-tag React is ready", "Ready") {
            CoderBuildDetector.check(store, classicId) == CoderBuildCheck.Ready
        },
        check("Build detector: an empty project is ready", "Ready") {
            CoderBuildDetector.check(store, newId()) == CoderBuildCheck.Ready
        },
        check("Build detector: package.json with a build tool and no page needs a build", "NeedsBuild") {
            CoderBuildDetector.check(store, packageOnlyId) is CoderBuildCheck.NeedsBuild
        },
    )
}

private fun explainerChecks(): List<CoderCheckResult> {
    val cases = listOf(
        "ReferenceError: foo is not defined" to "'foo'",
        "TypeError: Cannot read properties of null (reading 'style')" to "'style'",
        "TypeError: Cannot read properties of undefined (reading 'map')" to "'map'",
        "TypeError: Cannot set properties of null (setting 'innerHTML')" to "set something",
        "TypeError: foo.bar is not a function" to "'foo.bar'",
        "SyntaxError: Unexpected token '}'" to "typo",
        "SyntaxError: Unexpected end of input" to "typo",
        "SyntaxError: missing ) after argument list" to "typo",
        "SyntaxError: Invalid or unexpected token" to "typo",
        "SyntaxError: Unexpected identifier" to "typo",
        "TypeError: Failed to fetch" to "internet",
        "net::ERR_INTERNET_DISCONNECTED" to "internet",
        "Failed to load resource: the server responded with a status of 404 ()" to "wasn't found",
        "Error: Objects are not valid as a React child (found: object with keys {a})" to "{item.name}",
        "Error: Invalid hook call. Hooks can only be called inside of the body of a function component." to "useState",
        "Warning: Each child in a list should have a unique \"key\" prop." to "key={...}",
        "ReferenceError: require is not defined" to "import/export",
        "SyntaxError: Cannot use import statement outside a module" to "import/export",
        "ReferenceError: React is not defined" to "React isn't loaded",
        "ReferenceError: ReactDOM is not defined" to "React isn't loaded",
    )
    val out = ArrayList<CoderCheckResult>()
    for ((message, expected) in cases) {
        out.add(check("Explainer: $message", "a sentence containing $expected") {
            CoderErrorExplainer.explain(message)?.contains(expected) == true
        })
    }
    out.add(check("Explainer: an unknown message gives null", "null") {
        CoderErrorExplainer.explain("Something unusual happened") == null
    })
    out.add(check("Explainer: a 10,000 character message does not throw", "no exception") {
        CoderErrorExplainer.explain("x".repeat(10_000))
        CoderErrorExplainer.explain("Cannot read properties of null (reading '" + "y".repeat(10_000) + "')")
        true
    })
    return out
}

private fun reactTemplateChecks(): List<CoderCheckResult> {
    val hello = CoderTemplates.find("react-hello")
    val counter = CoderTemplates.find("react-counter")
    return listOf(
        check("React templates exist", "react-hello and react-counter") { hello != null && counter != null },
        check("React templates use classic scripts only", "no import or export text") {
            listOfNotNull(hello, counter).flatMap { it.files }.none {
                it.content.contains("import ") || it.content.contains("export ")
            }
        },
        check("React templates load the three bundled addresses", "all three unpkg URLs in index.html") {
            listOfNotNull(hello, counter).size == 2 && listOfNotNull(hello, counter).all { template ->
                val index = template.files.firstOrNull { it.path == "index.html" }?.content.orEmpty()
                REACT_SCRIPT_URLS.all { index.contains("src=\"" + it + "\"") }
            }
        },
        check("React template addresses are served by the shim", "all three match") {
            REACT_SCRIPT_URLS.all { CoderCdnShim.match(Uri.parse(it)) != null }
        },
    )
}

private fun aiProviderChecks(): List<CoderCheckResult> = listOf(
    check("AI provider ids map to the three providers", "claude, gemini, openai") {
        CoderAiProvider.fromId("claude") == CoderAiProvider.CLAUDE &&
            CoderAiProvider.fromId("gemini") == CoderAiProvider.GEMINI &&
            CoderAiProvider.fromId("openai") == CoderAiProvider.OPENAI
    },
    check("AI provider id null or unknown gives null", "null") {
        CoderAiProvider.fromId(null) == null && CoderAiProvider.fromId("") == null &&
            CoderAiProvider.fromId("mistral") == null && CoderAiProvider.fromId("Claude") == null
    },
    check("AI key pages are fixed https addresses", "three https pages") {
        CoderAiProvider.values().all { it.keysPageUrl.startsWith("https://") }
    },
    check("AI config text hides the key", "no key text, says hidden") {
        val text = CoderAiConfig(CoderAiProvider.CLAUDE, "sk-secret-key-1234", "some-model").toString()
        !text.contains("sk-secret-key-1234") && !text.contains("1234") && text.contains("apiKey=hidden") &&
            text.contains("some-model")
    },
)

private fun aiKeyChecks(): List<CoderCheckResult> = listOf(
    check("Key with spaces around it is trimmed", "sk-abc123") { cleanKey("  sk-abc123 ") == "sk-abc123" },
    check("Key with a newline inside loses the newline", "sk-abc123") {
        cleanKey("sk-ab\nc123\r\n") == "sk-abc123" && cleanKey("sk-ab c1\t23") == "sk-abc123"
    },
    check("Empty key is refused", "null") { cleanKey("") == null && cleanKey("  \n ") == null },
    check("Key of 512 characters is kept and 513 is refused", "512 ok, 513 null") {
        cleanKey("a".repeat(512)) != null && cleanKey("a".repeat(513)) == null
    },
    check("Key with a character outside printable ASCII is refused", "null") {
        cleanKey("sk-\u00E9123") == null && cleanKey("sk-\u4E2D123") == null
    },
    check("Model names with odd characters are refused", "letters, digits and . - _ : only") {
        cleanModelName(" gpt-4o ") == "gpt-4o" && cleanModelName("ft:gpt-4o:org::abc") == "ft:gpt-4o:org::abc" &&
            cleanModelName("") == null && cleanModelName("a/b") == null && cleanModelName("a b") == null &&
            cleanModelName("m?x=1") == null && cleanModelName("a".repeat(121)) == null
    },
)

private fun aiModelParsingChecks(): List<CoderCheckResult> {
    val claude = "{\"data\":[{\"type\":\"model\",\"id\":\"claude-b\"},{\"type\":\"model\",\"id\":\"claude-a\"}]," +
        "\"has_more\":false}"
    val openAi = "{\"object\":\"list\",\"data\":[{\"id\":\"gpt-4o\"},{\"id\":\"text-embedding-x\"}," +
        "{\"id\":\"whisper-1\"},{\"id\":\"gpt-4o\"},{\"id\":\"gpt-4o-mini\"},{\"id\":\"gpt-image-1\"}]}"
    val gemini = "{\"models\":[{\"name\":\"models/gemini-pro\",\"supportedGenerationMethods\":[\"countTokens\",\"generateContent\"]}," +
        "{\"name\":\"models/embedding-001\",\"supportedGenerationMethods\":[\"embedContent\"]}," +
        "{\"name\":\"models/gemini-flash\",\"supportedGenerationMethods\":[\"generateContent\"]}]}"
    return listOf(
        check("Claude model list is read and sorted", "claude-a, claude-b") {
            parseModels(CoderAiProvider.CLAUDE, claude) == listOf("claude-a", "claude-b")
        },
        check("OpenAI list drops non-chat models and duplicates", "gpt-4o, gpt-4o-mini") {
            parseModels(CoderAiProvider.OPENAI, openAi) == listOf("gpt-4o", "gpt-4o-mini")
        },
        check("Gemini list keeps only generateContent and drops models/", "gemini-flash, gemini-pro") {
            parseModels(CoderAiProvider.GEMINI, gemini) == listOf("gemini-flash", "gemini-pro")
        },
        check("Garbage model lists give null", "null for every provider") {
            CoderAiProvider.values().all { provider ->
                parseModels(provider, "not json at all") == null && parseModels(provider, "") == null &&
                    parseModels(provider, "[1,2]") == null && parseModels(provider, "{\"other\":1}") == null
            }
        },
        check("An empty list is a list, not garbage", "empty list") {
            parseModels(CoderAiProvider.CLAUDE, "{\"data\":[]}") == emptyList<String>()
        },
    )
}

private fun aiFailureTextChecks(): List<CoderCheckResult> = listOf(
    check("No internet gives the no-internet text", "No internet connection.") {
        describeFailure(UnknownHostException("api.example.com")) == "No internet connection." &&
            describeFailure(ConnectException("refused")) == "No internet connection."
    },
    check("A timeout gives the too-long text", "The request took too long. Try again.") {
        describeFailure(SocketTimeoutException("timeout")) == "The request took too long. Try again." &&
            describeFailure(InterruptedIOException("timeout")) == "The request took too long. Try again."
    },
    check("A secure-connection error gives its text", "A secure connection couldn't be made.") {
        describeFailure(SSLException("handshake")) == "A secure connection couldn't be made."
    },
    check("Any other error gives the general text and no details", "general text, no message inside") {
        val text = describeFailure(IOException("key=sk-secret-9999 https://x.example/?k=1"))
        text == "Something went wrong with the connection." && !text.contains("secret") && !text.contains("http")
    },
)

private fun aiCryptoChecks(): List<CoderCheckResult> {
    if (!CoderKeyCrypto.isAvailable()) {
        return listOf(CoderCheckResult("Key crypto: Keystore unavailable on this phone, checks skipped", true, "ok"))
    }
    return listOf(
        check("Key crypto round trip", "same bytes back, ciphertext without the text") {
            val secret = "sk-test-key-1234".toByteArray(Charsets.UTF_8)
            val sealed = CoderKeyCrypto.encrypt(secret)
            val back = if (sealed == null) null else CoderKeyCrypto.decrypt(sealed.ciphertext, sealed.iv)
            sealed != null && back != null && back.contentEquals(secret) && !sealed.ciphertext.contains("sk-test")
        },
        check("Key crypto uses a new IV every time", "two different IVs") {
            val secret = "sk-test-key-1234".toByteArray(Charsets.UTF_8)
            val first = CoderKeyCrypto.encrypt(secret)
            val second = CoderKeyCrypto.encrypt(secret)
            first != null && second != null && first.iv != second.iv
        },
        check("Key crypto refuses a wrong IV", "null") {
            val sealed = CoderKeyCrypto.encrypt("sk-test-key-1234".toByteArray(Charsets.UTF_8))
            val wrongIv = Base64.encodeToString(ByteArray(12), Base64.NO_WRAP)
            sealed != null && CoderKeyCrypto.decrypt(sealed.ciphertext, wrongIv) == null
        },
    )
}

// ---- Phase C6: AI chat ---------------------------------------------------------------------

private const val CHAT_TEST_KEY = "SECRET-KEY-1234"

private fun chatMessage(id: Long, role: ChatRole, text: String, failed: Boolean = false) =
    CoderChatMessage(id = id, role = role, text = text, failed = failed)

private fun aiChatChecks(): List<CoderCheckResult> =
    replyParserChecks() + userTurnChecks() + historyChecks() + requestBuildChecks() +
        responseParseChecks() + errorMapChecks()

private fun replyParserChecks(): List<CoderCheckResult> = listOf(
    check("Reply parser keeps plain text", "one text segment") {
        parseReply("Hello\nworld") == listOf(CoderReplySegment.Text("Hello\nworld"))
    },
    check("Reply parser reads a block with a path", "text, code, text") {
        parseReply("Here:\n```html path=index.html\n<p>Hi</p>\n```\nDone") == listOf(
            CoderReplySegment.Text("Here:"),
            CoderReplySegment.Code(CoderCodeBlock("html", "index.html", "<p>Hi</p>")),
            CoderReplySegment.Text("Done"),
        )
    },
    check("Reply parser reads two blocks", "two code segments with a text between") {
        parseReply("```css path=style.css\na{}\n```\nand\n```js\nlet x = 1\n```") == listOf(
            CoderReplySegment.Code(CoderCodeBlock("css", "style.css", "a{}")),
            CoderReplySegment.Text("and"),
            CoderReplySegment.Code(CoderCodeBlock("js", null, "let x = 1")),
        )
    },
    check("Reply parser keeps an unterminated block", "the rest becomes code") {
        parseReply("Look:\n```js\nlet a = 1;") == listOf(
            CoderReplySegment.Text("Look:"),
            CoderReplySegment.Code(CoderCodeBlock("js", null, "let a = 1;")),
        )
    },
    check("Reply parser drops an invalid path", "path is null") {
        parseReply("```html path=../x\n<p></p>\n```") ==
            listOf(CoderReplySegment.Code(CoderCodeBlock("html", null, "<p></p>")))
    },
    check("Reply parser keeps a short fence inside a longer one", "one block holding the inner fence") {
        parseReply("````md\n```js\nx\n```\n````") ==
            listOf(CoderReplySegment.Code(CoderCodeBlock("md", null, "```js\nx\n```")))
    },
    check("Reply parser gives nothing for empty text", "empty list") { parseReply("").isEmpty() },
    check("Reply parser handles very long text", "finishes quickly, never throws") {
        val start = System.nanoTime()
        val plain = parseReply("a".repeat(100_000))
        val fences = parseReply("```\nx\n".repeat(20_000))
        val lines = parseReply("line\n".repeat(50_000))
        plain.size == 1 && fences.isNotEmpty() && lines.size == 1 &&
            (System.nanoTime() - start) < 5_000_000_000L
    },
)

private fun userTurnChecks(): List<CoderCheckResult> {
    val marker = "[... file shortened ...]"
    val big = buildUserTurn("P", listOf("big.txt"), listOf(ContextFile("big.txt", "a".repeat(100_000))), null, null, "REQ")
    val manyPaths = buildUserTurn("P", (1..250).map { "f$it.txt" }, emptyList(), null, null, "REQ")
    val order = buildUserTurn(
        "P", emptyList(), listOf(ContextFile("open.js", "1"), ContextFile("b.js", "2")), null, null, "REQ",
    )
    val sixFiles = buildUserTurn("P", emptyList(), (1..8).map { ContextFile("f$it.txt", "x") }, null, null, "REQ")
    val huge = buildUserTurn(
        "P", emptyList(), (1..6).map { ContextFile("f$it.txt", "y".repeat(25_000)) }, null, null, "REQ",
    )
    val plain = buildUserTurn("P", listOf("a.txt"), listOf(ContextFile("a.txt", "AAA")), null, null, "REQ")
    val full = buildUserTurn("P", listOf("a.txt"), listOf(ContextFile("a.txt", "AAA")), "SEL", "ERR", "REQ")
    val unsafe = buildUserTurn(
        "P", listOf("a.txt", "../x"),
        listOf(ContextFile("../x", "HIDDEN-TEXT"), ContextFile("a.txt", "ok")), null, null, "REQ",
    )
    return listOf(
        check("Turn cuts a 100,000-character file to 60,000 plus the marker", "cut text, marker, shortened") {
            val content = big.text.substringAfter("<file path=\"big.txt\">\n").substringBefore("\n</file>")
            big.shortened && content == "a".repeat(60_000) + "\n" + marker
        },
        check("Turn lists at most 200 paths", "200 paths and a 50 more line") {
            manyPaths.text.lines().count { it.startsWith("- f") } == 200 && manyPaths.text.contains("- ... and 50 more")
        },
        check("Turn keeps the open file first", "open.js before b.js") {
            val open = order.text.indexOf("<file path=\"open.js\">")
            open >= 0 && open < order.text.indexOf("<file path=\"b.js\">")
        },
        check("Turn uses at most 6 files", "6 file tags") {
            Regex("<file path=").findAll(sixFiles.text).count() == 6
        },
        check("Turn stays within 120,000 characters", "short enough, shortened, first file kept") {
            huge.text.length <= 120_000 && huge.shortened && huge.text.contains("<file path=\"f1.txt\">") &&
                !huge.text.contains("<file path=\"f6.txt\">")
        },
        check("Turn leaves out selection and console when null", "no such tags") {
            !plain.text.contains("<selection>") && !plain.text.contains("<console>") && !plain.shortened
        },
        check("Turn holds only what was supplied", "exactly the expected text") {
            full.text == "<project name=\"P\">\nFiles:\n- a.txt\n</project>\n<file path=\"a.txt\">\nAAA\n</file>\n" +
                "<selection>\nSEL\n</selection>\n<console>\nERR\n</console>\n<request>\nREQ\n</request>"
        },
        check("Turn refuses invalid paths", "no text from ../x") {
            !unsafe.text.contains("HIDDEN-TEXT") && !unsafe.text.contains("../x") && unsafe.text.contains("ok")
        },
    )
}

private fun historyChecks(): List<CoderCheckResult> {
    val u = ChatRole.User
    val a = ChatRole.Assistant
    val twelve = (0..12).map { chatMessage(it.toLong(), if (it % 2 == 0) u else a, if (it % 2 == 0) "u$it" else "a$it") }
    return listOf(
        check("History drops a leading assistant message", "starts with the user message") {
            buildHistory(listOf(chatMessage(1, a, "a1"), chatMessage(2, u, "u1"))) == listOf(u to "u1")
        },
        check("History joins two user messages in a row", "one merged user message") {
            buildHistory(listOf(chatMessage(1, u, "u1"), chatMessage(2, u, "u2"))) == listOf(u to "u1\n\nu2")
        },
        check("History keeps at most 10 messages and starts with the user", "10 or fewer, user first, newest last") {
            val result = buildHistory(twelve)
            result.size <= 10 && result.first().first == u && result.last().second == "u12"
        },
        check("History leaves out messages that were not sent", "failed message missing") {
            buildHistory(
                listOf(
                    chatMessage(1, u, "u1"), chatMessage(2, a, "a1"),
                    chatMessage(3, u, "u2", failed = true), chatMessage(4, u, "u3"),
                ),
            ) == listOf(u to "u1", a to "a1", u to "u3")
        },
        check("History puts the full turn on the newest question only", "older text plain, newest full") {
            buildHistory(listOf(chatMessage(1, u, "u1"), chatMessage(2, a, "a1"), chatMessage(3, u, "u2")), "FULL") ==
                listOf(u to "u1", a to "a1", u to "FULL")
        },
    )
}

private fun chatBody(request: Request): String {
    val buffer = Buffer()
    request.body?.writeTo(buffer)
    return buffer.readUtf8()
}

private fun requestBuildChecks(): List<CoderCheckResult> {
    val turns = listOf(ChatRole.User to "hi", ChatRole.Assistant to "yo", ChatRole.User to "go")
    val claude = CoderAiClient.buildRequest(CoderAiConfig(CoderAiProvider.CLAUDE, CHAT_TEST_KEY, "claude-x"), "SYS", turns)
    val openAi = CoderAiClient.buildRequest(CoderAiConfig(CoderAiProvider.OPENAI, CHAT_TEST_KEY, "gpt-x"), "SYS", turns)
    val gemini = CoderAiClient.buildRequest(
        CoderAiConfig(CoderAiProvider.GEMINI, CHAT_TEST_KEY, "models/gemini-1.5-pro"), "SYS", turns,
    )
    val claudeBody = chatBody(claude)
    val openAiBody = chatBody(openAi)
    val geminiBody = chatBody(gemini)
    return listOf(
        check("Claude request address, header and body", "fixed address, key only in the header") {
            val json = JSONObject(claudeBody)
            claude.url.toString() == "https://api.anthropic.com/v1/messages" &&
                claude.header("x-api-key") == CHAT_TEST_KEY && claude.header("anthropic-version") == "2023-06-01" &&
                !claude.url.toString().contains(CHAT_TEST_KEY) && !claudeBody.contains(CHAT_TEST_KEY) &&
                json.getInt("max_tokens") == 4096 && json.getString("system") == "SYS" &&
                json.getJSONArray("messages").length() == 3 &&
                claude.body?.contentType()?.toString() == "application/json; charset=utf-8"
        },
        check("OpenAI request has no limits and a leading system message", "no temperature or token limit") {
            val json = JSONObject(openAiBody)
            val first = json.getJSONArray("messages").getJSONObject(0)
            openAi.url.toString() == "https://api.openai.com/v1/chat/completions" &&
                openAi.header("Authorization") == "Bearer $CHAT_TEST_KEY" &&
                !openAi.url.toString().contains(CHAT_TEST_KEY) && !openAiBody.contains(CHAT_TEST_KEY) &&
                !json.has("temperature") && !json.has("max_tokens") && !json.has("max_completion_tokens") &&
                first.getString("role") == "system" && first.getString("content") == "SYS" &&
                json.getJSONArray("messages").length() == 4
        },
        check("Gemini request strips models/ and uses model for answers", "address, header and roles") {
            val json = JSONObject(geminiBody)
            val contents = json.getJSONArray("contents")
            gemini.url.toString() == "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-pro:generateContent" &&
                gemini.header("x-goog-api-key") == CHAT_TEST_KEY &&
                !gemini.url.toString().contains(CHAT_TEST_KEY) && !geminiBody.contains(CHAT_TEST_KEY) &&
                contents.getJSONObject(1).getString("role") == "model" &&
                contents.getJSONObject(0).getString("role") == "user" &&
                json.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text") == "SYS"
        },
    )
}

private fun responseParseChecks(): List<CoderCheckResult> {
    fun parsed(provider: CoderAiProvider, body: String) = CoderAiClient.parseResponse(provider, body)
    return listOf(
        check("Claude answer joins text parts and reads the cut-short flag", "joined text, truncated") {
            val cut = "{\"content\":[{\"type\":\"text\",\"text\":\"Hello \"},{\"type\":\"tool_use\"}," +
                "{\"type\":\"text\",\"text\":\"world\"}],\"stop_reason\":\"max_tokens\"}"
            val done = "{\"content\":[{\"type\":\"text\",\"text\":\"Hi\"}],\"stop_reason\":\"end_turn\"}"
            parsed(CoderAiProvider.CLAUDE, cut) == CoderChatResult.Success("Hello world", true) &&
                parsed(CoderAiProvider.CLAUDE, done) == CoderChatResult.Success("Hi", false)
        },
        check("OpenAI answer, cut-short flag and refusal", "text, truncated, refusal as text") {
            val cut = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hi\"},\"finish_reason\":\"length\"}]}"
            val refusal = "{\"choices\":[{\"message\":{\"content\":null,\"refusal\":\"I can't help with that.\"}," +
                "\"finish_reason\":\"stop\"}]}"
            parsed(CoderAiProvider.OPENAI, cut) == CoderChatResult.Success("Hi", true) &&
                parsed(CoderAiProvider.OPENAI, refusal) == CoderChatResult.Success("I can't help with that.", false)
        },
        check("Gemini answer, cut-short flag and block reason", "joined text, truncated, blocked") {
            val cut = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"A\"},{\"text\":\"B\"}]},\"finishReason\":\"MAX_TOKENS\"}]}"
            val blocked = parsed(CoderAiProvider.GEMINI, "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}")
            parsed(CoderAiProvider.GEMINI, cut) == CoderChatResult.Success("AB", true) &&
                blocked is CoderChatResult.Failure && blocked.kind == CoderChatFailure.Blocked
        },
        check("Unreadable answers are failures, not crashes", "failure for every provider") {
            CoderAiProvider.values().all { provider ->
                parsed(provider, "not json") is CoderChatResult.Failure &&
                    parsed(provider, "{}") is CoderChatResult.Failure && parsed(provider, "") is CoderChatResult.Failure
            }
        },
    )
}

private fun errorMapChecks(): List<CoderCheckResult> {
    fun kindOf(code: Int, body: String = "", provider: CoderAiProvider = CoderAiProvider.OPENAI) =
        CoderAiClient.mapError(provider, code, body).kind
    return listOf(
        check("Error 401 and 403 mean the key was refused", "InvalidKey") {
            kindOf(401) == CoderChatFailure.InvalidKey && kindOf(403) == CoderChatFailure.InvalidKey
        },
        check("Error 404 means the model was not found", "ModelNotFound") { kindOf(404) == CoderChatFailure.ModelNotFound },
        check("Error 429 means a limit was reached", "RateLimited") { kindOf(429) == CoderChatFailure.RateLimited },
        check("Error 503 and 529 mean the provider is busy", "Busy") {
            kindOf(503) == CoderChatFailure.Busy && kindOf(529) == CoderChatFailure.Busy
        },
        check("Error 400 adds the provider's one-line message", "rejected text plus the message") {
            val result = CoderAiClient.mapError(
                CoderAiProvider.OPENAI, 400, "{\"error\":{\"message\":\"Bad\\nthing   here\"}}",
            )
            result.kind == CoderChatFailure.Other &&
                result.message == "The provider rejected the request. Bad thing here"
        },
        check("Error 400 with an unreadable body has no detail", "only the rejected text") {
            CoderAiClient.mapError(CoderAiProvider.CLAUDE, 400, "<html>oops</html>").message ==
                "The provider rejected the request."
        },
        check("A long provider message is cut to 300 characters", "300 characters of detail") {
            val body = "{\"error\":{\"message\":\"" + "x".repeat(500) + "\"}}"
            CoderAiClient.mapError(CoderAiProvider.GEMINI, 400, body).message.length ==
                "The provider rejected the request. ".length + 300
        },
        check("Gemini's wrong-key answer counts as an invalid key", "InvalidKey") {
            kindOf(400, "{\"error\":{\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}", CoderAiProvider.GEMINI) ==
                CoderChatFailure.InvalidKey
        },
        check("Other codes give the code in plain words", "error (500)") {
            CoderAiClient.mapError(CoderAiProvider.OPENAI, 500, "").message == "The provider returned an error (500)."
        },
    )
}

// ---- Phase C7: GitHub foundation -----------------------------------------------------------
// Every fake secret below is joined from pieces at run time: GitHub's push protection refuses a
// commit that contains a token-looking text, so no such text may stand in this file.

private const val GH_TEST_TOKEN = "TESTTOKEN-ABCDEFGH"
private const val GH_OWNER = "octocat"
private const val GH_REPO = "hello-world"
private const val GH_SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
private const val GH_SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
private const val GH_SHA_C = "cccccccccccccccccccccccccccccccccccccccc"

private fun githubChecks(store: CoderFileStore): List<CoderCheckResult> =
    gitShaChecks(store) + repoRefChecks() + pushPlanChecks() + ignoreChecks() + snapshotChecks(store) +
        secretScannerChecks() + githubRequestChecks() + githubErrorChecks() + githubTokenChecks() + githubCryptoChecks()

private fun gitShaChecks(store: CoderFileStore): List<CoderCheckResult> = listOf(
    check("Git hash of an empty file", "e69de29b...") {
        CoderGitSha.blobSha(ByteArray(0)) == "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391"
    },
    check("Git hash of hello and a new line", "ce013625...") {
        CoderGitSha.blobSha("hello\n".toByteArray(Charsets.UTF_8)) == "ce013625030ba8dba906f756967f9e9ca394464a"
    },
    check("Git hash of a file equals the hash of its bytes", "same hash, text and binary") {
        val id = "ghsha-" + newId()
        store.write(id, "h.txt", "hello\n")
        val dir = store.resolve(id, "")!!
        val binary = byteArrayOf(0, -1, 10, 13, 127, -128, 65)
        java.io.File(dir, "img.bin").writeBytes(binary)
        val text = store.resolve(id, "h.txt")!!
        val image = store.resolve(id, "img.bin")!!
        CoderGitSha.blobSha(text) == "ce013625030ba8dba906f756967f9e9ca394464a" &&
            CoderGitSha.blobSha(image) == CoderGitSha.blobSha(binary)
    },
)

private fun repoRefChecks(): List<CoderCheckResult> {
    val good = listOf(
        "octocat/hello-world" to "hello-world",
        "https://github.com/octocat/hello-world" to "hello-world",
        "https://github.com/octocat/hello-world.git" to "hello-world",
        "https://github.com/octocat/hello-world/" to "hello-world",
        "github.com/octocat/hello-world" to "hello-world",
        "  octocat/my_repo.v2  " to "my_repo.v2",
    )
    val bad = listOf("", "octocat", "a/b/c", "octocat/", "oct ocat/x", "octocat/..", "https://gitlab.com/o/n")
    val out = ArrayList<CoderCheckResult>()
    for ((raw, expectedName) in good) {
        out.add(check("Repository reference accepted: " + raw.trim(), "octocat and $expectedName") {
            parseRepoRef(raw) == Pair("octocat", expectedName)
        })
    }
    for (raw in bad) {
        out.add(check("Repository reference refused: " + raw.ifEmpty { "empty" }, "null") { parseRepoRef(raw) == null })
    }
    out.add(check("Repository owner longer than 39 characters is refused", "null") {
        parseRepoRef("a".repeat(40) + "/x") == null && parseRepoRef("a".repeat(39) + "/x") != null
    })
    out.add(check("Repository names", "valid and invalid names") {
        isValidRepoName("my-app_1.0") && !isValidRepoName("") && !isValidRepoName(".") && !isValidRepoName("..") &&
            !isValidRepoName("a b") && !isValidRepoName("a/b") && isValidRepoName("a".repeat(100)) &&
            !isValidRepoName("a".repeat(101))
    })
    return out
}

private fun ghLocal(path: String, sha: String) = LocalFile(path, 10L, sha)

private fun ghRemote(path: String, sha: String, type: String = "blob", mode: String = "100644") =
    RemoteEntry(path, type, sha, mode)

private fun pushPlanChecks(): List<CoderCheckResult> {
    val local = listOf(
        ghLocal("new.txt", GH_SHA_A), ghLocal("changed.txt", GH_SHA_B), ghLocal("same.txt", GH_SHA_A),
        ghLocal("run.sh", GH_SHA_B), ghLocal("docs", GH_SHA_C),
    )
    val remote = listOf(
        ghRemote("changed.txt", GH_SHA_A), ghRemote("same.txt", GH_SHA_A), ghRemote("run.sh", GH_SHA_A, mode = "100755"),
        ghRemote("docs", GH_SHA_B, type = "tree", mode = "040000"), ghRemote("only-on-github.txt", GH_SHA_C),
    )
    val plan = CoderPushPlanner.computePlan(local, remote)
    return listOf(
        check("Push plan: a file GitHub does not have is new", "new.txt only") {
            plan.newFiles.map { it.path } == listOf("new.txt")
        },
        check("Push plan: a different hash is changed", "changed.txt and run.sh") {
            plan.changedFiles.map { it.path } == listOf("changed.txt", "run.sh")
        },
        check("Push plan: the same hash is unchanged", "1 unchanged") { plan.unchangedCount == 1 },
        check("Push plan: files only on GitHub are left alone", "not in any list") {
            val all = plan.newFiles + plan.changedFiles
            all.none { it.path == "only-on-github.txt" } && plan.conflicts.none { it == "only-on-github.txt" }
        },
        check("Push plan: an executable file keeps its mode", "100755 for run.sh") {
            plan.remoteModes["run.sh"] == "100755" && plan.remoteModes["changed.txt"] == "100644" &&
                plan.remoteModes.keys == setOf("changed.txt", "run.sh")
        },
        check("Push plan: a folder on GitHub is a conflict", "docs left out and listed") {
            plan.conflicts == listOf("docs") && (plan.newFiles + plan.changedFiles).none { it.path == "docs" }
        },
        check("Push plan: nothing local and nothing remote", "empty plan") {
            val empty = CoderPushPlanner.computePlan(emptyList(), emptyList())
            empty.newFiles.isEmpty() && empty.changedFiles.isEmpty() && empty.unchangedCount == 0 && empty.conflicts.isEmpty()
        },
    )
}

private fun ignoreChecks(): List<CoderCheckResult> {
    val out = ArrayList<CoderCheckResult>()
    for (path in listOf("node_modules/a.js", ".git/x", "a/.DS_Store", ".x.tmp-1")) {
        out.add(check("Push ignores $path", "ignored") { CoderPushPlanner.isIgnored(path) })
    }
    out.add(check("Push keeps src/a.js and .gitignore", "not ignored") {
        !CoderPushPlanner.isIgnored("src/a.js") && !CoderPushPlanner.isIgnored(".gitignore")
    })
    return out
}

private fun snapshotChecks(store: CoderFileStore): List<CoderCheckResult> = listOf(
    check("Push snapshot lists real files with their git hashes", "a.txt and dir/b.txt only") {
        val id = "ghsnap-" + newId()
        store.write(id, "a.txt", "hello\n")
        store.write(id, "dir/b.txt", "")
        store.write(id, "node_modules/x.js", "x")
        store.write(id, ".git/config", "x")
        val files = runBlocking { CoderPushPlanner.snapshot(store, id) }
        files.map { it.path }.sorted() == listOf("a.txt", "dir/b.txt") &&
            files.first { it.path == "a.txt" }.sha == "ce013625030ba8dba906f756967f9e9ca394464a" &&
            files.first { it.path == "dir/b.txt" }.sha == "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391" &&
            files.first { it.path == "a.txt" }.sizeBytes == 6L
    },
)

private fun secretScannerChecks(): List<CoderCheckResult> {
    val aws = "AK" + "IA" + "B".repeat(16)
    val ghToken = "gh" + "p_" + "a".repeat(36)
    val ghFine = "github" + "_pat_" + "a".repeat(50)
    val aiKey = "s" + "k-" + "a".repeat(24)
    val google = "AI" + "za" + "a".repeat(35)
    val pem = "-----BEGIN " + "RSA PRIV" + "ATE KEY-----"
    val slack = "xo" + "xb-" + "1234567890" + "-abcdefghij"
    val generic = "const password = \"" + "correct-horse-battery" + "\""
    val kinds = listOf(
        Triple("AWS access key", "key = $aws", aws),
        Triple("GitHub token", "token $ghToken", ghToken),
        Triple("GitHub token", "x $ghFine", ghFine),
        Triple("AI or payment key", "KEY=$aiKey", aiKey),
        Triple("Google API key", "k: $google", google),
        Triple("private key", pem, pem),
        Triple("Slack token", "hook $slack", slack),
        Triple("possible secret", generic, "correct-horse-battery"),
    )
    val out = ArrayList<CoderCheckResult>()
    for ((kind, line, secret) in kinds) {
        out.add(check("Secret scanner finds: $kind (${secret.length} characters)", "one finding of that kind, without the text") {
            val found = CoderSecretScanner.scan(listOf("src/config.js" to "ok\nok\n$line\n"))
            found.size == 1 && found[0].kind == kind && found[0].line == 3 && found[0].path == "src/config.js" &&
                !found[0].toString().contains(secret)
        })
    }
    out.add(check("Secret scanner skips a line of 2001 characters, not one of 2000", "only the short line is found") {
        val long = aws + "x".repeat(2001 - aws.length)
        val short = aws + "x".repeat(2000 - aws.length)
        val found = CoderSecretScanner.scan(listOf("a.txt" to "$long\n$short"))
        long.length == 2001 && found.size == 1 && found[0].line == 2
    })
    out.add(check("Secret scanner skips a binary-looking file", "nothing found") {
        CoderSecretScanner.scan(listOf("a.bin" to ("a\u0000" + aws))).isEmpty()
    })
    out.add(check("Secret scanner finds nothing in ordinary code", "nothing found") {
        val code = "fun main() {\n    val total = 1 + 2\n    println(\"hello\")\n}\n" +
            "<div class=\"ta" + "sk-management-dashboard-widget\"></div>\nconst tokenCount = 12\n"
        CoderSecretScanner.scan(listOf("Main.kt" to code)).isEmpty()
    })
    out.add(check("Secret scanner returns at most 50 findings", "50") {
        CoderSecretScanner.scan(listOf("a.txt" to List(60) { aws }.joinToString("\n"))).size == 50
    })
    return out
}

private fun ghBodyText(request: Request): String {
    val body = request.body ?: return ""
    val buffer = Buffer()
    body.writeTo(buffer)
    return buffer.readUtf8()
}

private fun githubRequestChecks(): List<CoderCheckResult> {
    val blob = byteArrayOf(0, 1, 2, -1, -2, 65, 66)
    val spaced = "my folder/na\u00efve file.txt"
    val requests: List<Pair<String, Request>> = listOf(
        "user" to CoderGitHubRequests.user(GH_TEST_TOKEN),
        "repo" to CoderGitHubRequests.repo(GH_TEST_TOKEN, GH_OWNER, GH_REPO),
        "create repo" to CoderGitHubRequests.createRepo(GH_TEST_TOKEN, GH_REPO, true),
        "branch head" to CoderGitHubRequests.branchHead(GH_TEST_TOKEN, GH_OWNER, GH_REPO, "feature/x"),
        "latest commit" to CoderGitHubRequests.latestCommit(GH_TEST_TOKEN, GH_OWNER, GH_REPO),
        "commit" to CoderGitHubRequests.commit(GH_TEST_TOKEN, GH_OWNER, GH_REPO, GH_SHA_A),
        "tree" to CoderGitHubRequests.tree(GH_TEST_TOKEN, GH_OWNER, GH_REPO, GH_SHA_A),
        "blob" to CoderGitHubRequests.createBlob(GH_TEST_TOKEN, GH_OWNER, GH_REPO, blob),
        "new tree" to CoderGitHubRequests.createTree(
            GH_TEST_TOKEN, GH_OWNER, GH_REPO, GH_SHA_A, listOf(TreeEntryInput(spaced, "100644", GH_SHA_B)),
        ),
        "commit create" to CoderGitHubRequests.createCommit(GH_TEST_TOKEN, GH_OWNER, GH_REPO, "Save", GH_SHA_A, GH_SHA_B),
        "ref update" to CoderGitHubRequests.updateRef(GH_TEST_TOKEN, GH_OWNER, GH_REPO, "main", GH_SHA_A),
        "first file" to CoderGitHubRequests.putFile(GH_TEST_TOKEN, GH_OWNER, GH_REPO, spaced, blob, "First"),
    )
    val out = ArrayList<CoderCheckResult>()
    for ((label, request) in requests) {
        out.add(check("GitHub request ($label): safe address, headers, no token in address or body", "https api.github.com, 4 headers") {
            request.url.scheme == "https" && request.url.host == "api.github.com" &&
                !request.url.toString().contains(GH_TEST_TOKEN) && !ghBodyText(request).contains(GH_TEST_TOKEN) &&
                request.header("Accept") == "application/vnd.github+json" &&
                request.header("Authorization") == "Bearer $GH_TEST_TOKEN" &&
                request.header("X-GitHub-Api-Version") == "2022-11-28" &&
                request.header("User-Agent") == "NeriboVault-Coder"
        })
    }
    out.add(check("GitHub request: the blob body is valid base64 of the file", "same bytes back") {
        val request = CoderGitHubRequests.createBlob(GH_TEST_TOKEN, GH_OWNER, GH_REPO, blob)
        val json = JSONObject(ghBodyText(request))
        request.method == "POST" && json.getString("encoding") == "base64" &&
            Base64.decode(json.getString("content"), Base64.NO_WRAP).contentEquals(blob) &&
            request.body?.contentLength() == ghBodyText(request).toByteArray(Charsets.UTF_8).size.toLong()
    })
    out.add(check("GitHub request: the ref update is a PATCH without force", "PATCH, force false") {
        val request = CoderGitHubRequests.updateRef(GH_TEST_TOKEN, GH_OWNER, GH_REPO, "main", GH_SHA_A)
        val json = JSONObject(ghBodyText(request))
        request.method == "PATCH" && !json.getBoolean("force") && json.getString("sha") == GH_SHA_A &&
            request.url.encodedPath == "/repos/$GH_OWNER/$GH_REPO/git/refs/heads/main"
    })
    out.add(check("GitHub request: a branch name keeps its slash in the address", "heads/feature/x") {
        CoderGitHubRequests.branchHead(GH_TEST_TOKEN, GH_OWNER, GH_REPO, "feature/x").url.encodedPath ==
            "/repos/$GH_OWNER/$GH_REPO/git/ref/heads/feature/x"
    })
    out.add(check("GitHub request: the tree call asks for everything and the empty-repo call for one commit", "query parameters") {
        CoderGitHubRequests.tree(GH_TEST_TOKEN, GH_OWNER, GH_REPO, GH_SHA_A).url.queryParameter("recursive") == "1" &&
            CoderGitHubRequests.latestCommit(GH_TEST_TOKEN, GH_OWNER, GH_REPO).url.queryParameter("per_page") == "1"
    })
    out.add(check("GitHub request: a new tree keeps file names exactly and is built from blobs", "exact path, blob, mode") {
        val request = CoderGitHubRequests.createTree(
            GH_TEST_TOKEN, GH_OWNER, GH_REPO, GH_SHA_A,
            listOf(TreeEntryInput(spaced, "100755", GH_SHA_B), TreeEntryInput("b.txt", "120000", GH_SHA_C)),
        )
        val json = JSONObject(ghBodyText(request))
        val tree: JSONArray = json.getJSONArray("tree")
        json.getString("base_tree") == GH_SHA_A && tree.length() == 2 &&
            tree.getJSONObject(0).getString("path") == spaced && tree.getJSONObject(0).getString("mode") == "100755" &&
            tree.getJSONObject(1).getString("mode") == "100644" && tree.getJSONObject(1).getString("type") == "blob"
    })
    out.add(check("GitHub request: a tree without a base has no base_tree", "no base_tree key") {
        !JSONObject(ghBodyText(CoderGitHubRequests.createTree(GH_TEST_TOKEN, GH_OWNER, GH_REPO, null, emptyList()))).has("base_tree")
    })
    out.add(check("GitHub request: the commit has one parent", "message, tree and parents") {
        val json = JSONObject(ghBodyText(CoderGitHubRequests.createCommit(GH_TEST_TOKEN, GH_OWNER, GH_REPO, "Save", GH_SHA_A, GH_SHA_B)))
        json.getString("message") == "Save" && json.getString("tree") == GH_SHA_A &&
            json.getJSONArray("parents").length() == 1 && json.getJSONArray("parents").getString(0) == GH_SHA_B
    })
    out.add(check("GitHub request: a new repository is empty and private on request", "private true, auto_init false") {
        val json = JSONObject(ghBodyText(CoderGitHubRequests.createRepo(GH_TEST_TOKEN, "my-app", true)))
        json.getString("name") == "my-app" && json.getBoolean("private") && !json.getBoolean("auto_init")
    })
    out.add(check("GitHub request: the first-file upload encodes the path and has no branch", "encoded segments, PUT") {
        val request = CoderGitHubRequests.putFile(GH_TEST_TOKEN, GH_OWNER, GH_REPO, spaced, blob, "First \"commit\"")
        val json = JSONObject(ghBodyText(request))
        request.method == "PUT" && request.url.encodedPath.contains("/contents/my%20folder/na") &&
            request.url.encodedPath.endsWith("%20file.txt") && !json.has("branch") &&
            json.getString("message") == "First \"commit\"" &&
            Base64.decode(json.getString("content"), Base64.NO_WRAP).contentEquals(blob)
    })
    return out
}

private fun githubErrorChecks(): List<CoderCheckResult> {
    fun kindOf(code: Int, remaining: String? = null) = CoderGitHubErrors.map(code, remaining, null).kind
    return listOf(
        check("GitHub error 401 means the token was refused", "InvalidToken and the plain text") {
            val error = CoderGitHubErrors.map(401, null, null)
            error.kind == CoderGitHubErrorKind.InvalidToken &&
                error.message == "GitHub didn't accept this token. It may be wrong or expired."
        },
        check("GitHub error 403 with no requests left is a rate limit", "RateLimited") {
            kindOf(403, "0") == CoderGitHubErrorKind.RateLimited
        },
        check("GitHub error 403 otherwise means no permission", "NoAccess") {
            kindOf(403) == CoderGitHubErrorKind.NoAccess && kindOf(403, "42") == CoderGitHubErrorKind.NoAccess &&
                CoderGitHubErrors.map(403, null, null).message.contains("'repo'")
        },
        check("GitHub error 404 means not found", "NotFound") { kindOf(404) == CoderGitHubErrorKind.NotFound },
        check("GitHub error 409 is a conflict", "Conflict") { kindOf(409) == CoderGitHubErrorKind.Conflict },
        check("GitHub error 422 adds GitHub's one-line message, at most 200 characters", "Invalid, one line") {
            val error = CoderGitHubErrors.map(422, null, "Bad\nthing   here " + "x".repeat(400))
            error.kind == CoderGitHubErrorKind.Invalid && !error.message.contains('\n') &&
                error.message.startsWith("GitHub rejected the request. Bad thing here x") &&
                error.message.length == "GitHub rejected the request. ".length + 200
        },
        check("GitHub error 422 on a ref update means the branch moved", "Conflict and the newer-changes text") {
            val error = CoderGitHubErrors.map(422, null, "Reference update failed", forRefUpdate = true)
            error.kind == CoderGitHubErrorKind.Conflict &&
                error.message == "GitHub has newer changes than this phone. Try again."
        },
        check("GitHub error 500 and 503 mean GitHub is busy", "Busy") {
            kindOf(500) == CoderGitHubErrorKind.Busy && kindOf(503) == CoderGitHubErrorKind.Busy
        },
        check("GitHub error with another code gives the code in plain words", "error (418)") {
            val error = CoderGitHubErrors.map(418, null, null)
            error.kind == CoderGitHubErrorKind.Other && error.message == "GitHub returned an error (418)."
        },
        check("A thrown error is a network error with no details from the error", "Network, no secret in the text") {
            val error = CoderGitHubErrors.fromException(IOException("token=$GH_TEST_TOKEN https://api.github.com/x"))
            error.kind == CoderGitHubErrorKind.Network && error.message == describeFailure(IOException()) &&
                !error.message.contains(GH_TEST_TOKEN) && !error.message.contains("http")
        },
        check("A missing connection is described in plain words", "No internet connection.") {
            CoderGitHubErrors.fromException(UnknownHostException("api.github.com")).message == "No internet connection."
        },
    )
}

private fun githubTokenChecks(): List<CoderCheckResult> = listOf(
    check("GitHub token cleaning removes spaces and line breaks", "abc123") {
        cleanGitHubToken("  abc 123\n") == "abc123"
    },
    check("GitHub token cleaning refuses empty text and non-ASCII characters", "null") {
        cleanGitHubToken("") == null && cleanGitHubToken("   \n") == null && cleanGitHubToken("abc\u00e9") == null
    },
    check("GitHub token cleaning accepts 512 characters and refuses 513", "512 yes, 513 null") {
        cleanGitHubToken("a".repeat(512)) != null && cleanGitHubToken("a".repeat(513)) == null
    },
)

private fun githubCryptoChecks(): List<CoderCheckResult> {
    if (!CoderGitHubKeyCrypto.isAvailable()) {
        return listOf(CoderCheckResult("GitHub token crypto: Keystore unavailable on this phone, checks skipped", true, "ok"))
    }
    return listOf(
        check("GitHub token crypto round trip", "same bytes back, ciphertext without the text") {
            val secret = GH_TEST_TOKEN.toByteArray(Charsets.UTF_8)
            val sealed = CoderGitHubKeyCrypto.encrypt(secret)
            val back = if (sealed == null) null else CoderGitHubKeyCrypto.decrypt(sealed.ciphertext, sealed.iv)
            sealed != null && back != null && back.contentEquals(secret) && !sealed.ciphertext.contains("TESTTOKEN")
        },
        check("GitHub token crypto has its own key alias", "different from the AI key alias") {
            CoderGitHubKeyCrypto.KEY_ALIAS == "neribo_coder_github_key" && CoderGitHubKeyCrypto.KEY_ALIAS != CoderKeyCrypto.KEY_ALIAS
        },
        check("GitHub token crypto refuses a wrong IV", "null") {
            val sealed = CoderGitHubKeyCrypto.encrypt(GH_TEST_TOKEN.toByteArray(Charsets.UTF_8))
            val wrongIv = Base64.encodeToString(ByteArray(12), Base64.NO_WRAP)
            sealed != null && CoderGitHubKeyCrypto.decrypt(sealed.ciphertext, wrongIv) == null
        },
    )
}

// ---- Developer link and project info (phase C8, part A) -------------------------------------

private fun infoProject(
    name: String = "Shop App",
    status: String = "active",
    description: String = "A small shop.",
    tech: List<String> = listOf("Kotlin", "Room"),
) = ProjectEntity(
    id = "dev-1",
    createdAt = 1L,
    updatedAt = 2L,
    name = name,
    description = description,
    status = status,
    techStack = tech,
    repoUrl = "https://example.invalid/repo",
    liveUrl = "https://example.invalid/live",
)

private fun developerLinkChecks(): List<CoderCheckResult> = projectInfoChecks() + infoInTurnChecks()

private fun projectInfoChecks(): List<CoderCheckResult> {
    val base = "<project_info>\nName: Shop App\nStatus: Active\nDescription: A small shop.\nTech stack: Kotlin, Room\n</project_info>"
    val longDescription = buildProjectInfo(infoProject(description = "d".repeat(5_000)), null)
    val manyTech = buildProjectInfo(infoProject(tech = (1..30).map { "tech$it" }), null)
    val longTech = buildProjectInfo(infoProject(tech = listOf("x".repeat(60))), null)
    val eightNotes = buildProjectInfo(infoProject(), (1..8).map { "T$it" to "B$it" })
    val longNote = buildProjectInfo(infoProject(), listOf("Big" to "z".repeat(3_000)))
    return listOf(
        check("Project info holds the fields in order", "exact tags and lines") {
            buildProjectInfo(infoProject(), null) == base
        },
        check("Project info never holds the repository or live address", "no address text") {
            !base.contains("example.invalid") && !buildProjectInfo(infoProject(), listOf("N" to "b")).contains("example.invalid")
        },
        check("Project info leaves out blank fields", "no empty lines") {
            buildProjectInfo(infoProject(name = "  ", status = "", description = "", tech = emptyList()), null) ==
                "<project_info>\n</project_info>" &&
                buildProjectInfo(infoProject(status = " ", description = " ", tech = listOf(" ", "")), null) ==
                "<project_info>\nName: Shop App\n</project_info>"
        },
        check("Project info cuts a 5,000-character description to 1,000", "1,000 characters") {
            val line = longDescription.lines().first { it.startsWith("Description: ") }
            line.removePrefix("Description: ") == "d".repeat(1_000)
        },
        check("Project info uses at most 20 tech items", "20 items") {
            val line = manyTech.lines().first { it.startsWith("Tech stack: ") }
            val items = line.removePrefix("Tech stack: ").split(", ")
            items.size == 20 && items.first() == "tech1" && items.last() == "tech20"
        },
        check("Project info cuts a long tech item to 40 characters", "40 characters") {
            longTech.lines().first { it.startsWith("Tech stack: ") }.removePrefix("Tech stack: ") == "x".repeat(40)
        },
        check("Project info has no notes block for null or empty notes", "no notes tag") {
            !buildProjectInfo(infoProject(), null).contains("<notes>") &&
                !buildProjectInfo(infoProject(), emptyList()).contains("<notes>") &&
                !buildProjectInfo(infoProject(), listOf("" to " ")).contains("<notes>")
        },
        check("Project info shows a note under its title", "exact notes block") {
            buildProjectInfo(infoProject(), listOf("Plan" to "Step one")) == base + "\n<notes>\n## Plan\nStep one\n</notes>"
        },
        check("Project info uses at most 5 notes, in the given order", "T1 to T5 only") {
            Regex("(?m)^## ").findAll(eightNotes).count() == 5 &&
                eightNotes.contains("## T5\nB5") && !eightNotes.contains("## T6")
        },
        check("Project info cuts a note text to 1,500 characters", "1,500 characters") {
            longNote.contains("## Big\n" + "z".repeat(1_500) + "\n</notes>")
        },
    )
}

private fun infoInTurnChecks(): List<CoderCheckResult> {
    val info = buildProjectInfo(infoProject(), listOf("Plan" to "Step one"))
    val files = listOf(ContextFile("a.txt", "AAA"))
    val without = buildUserTurn("P", listOf("a.txt"), files, null, null, "REQ")
    val withInfo = buildUserTurn("P", listOf("a.txt"), files, null, null, "REQ", info)
    val blank = buildUserTurn("P", listOf("a.txt"), files, null, null, "REQ", "   ")
    val crowded = buildUserTurn(
        "P", emptyList(), (1..6).map { ContextFile("f$it.txt", "y".repeat(25_000)) }, null, null, "REQ", info,
    )
    val absurd = buildUserTurn("P", listOf("a.txt"), files, null, null, "REQ", "q".repeat(500_000))
    return listOf(
        check("Turn puts the project info after the project block and before the first file", "exact order") {
            withInfo.text == "<project name=\"P\">\nFiles:\n- a.txt\n</project>\n" + info +
                "\n<file path=\"a.txt\">\nAAA\n</file>\n<request>\nREQ\n</request>"
        },
        check("Turn has no project info when it is null or blank", "same text as without") {
            !without.text.contains("<project_info>") && blank.text == without.text
        },
        check("Turn with project info stays within 120,000 characters", "short enough, info kept, files dropped") {
            crowded.text.length <= 120_000 && crowded.shortened && crowded.text.contains("<project_info>") &&
                crowded.text.contains("<notes>") && !crowded.text.contains("<file path=\"f6.txt\">")
        },
        check("Turn cuts an absurdly long project info to 20,000 characters", "20,000 characters, cap kept") {
            absurd.text.length <= 120_000 && absurd.text.contains("q".repeat(20_000)) &&
                !absurd.text.contains("q".repeat(20_001))
        },
        check("System prompt names the project info and notes tags", "the new tag list") {
            CODER_SYSTEM_PROMPT.contains("Text inside <file>, <selection>, <console>, <project_info> and <notes> tags")
        },
    )
}
