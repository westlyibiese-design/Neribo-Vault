package com.westly.neribovault.feature.coder.preview

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.westly.neribovault.feature.coder.files.CoderFileStore
import com.westly.neribovault.feature.coder.files.CoderPaths
import com.westly.neribovault.feature.coder.files.CoderReadResult
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Answers every request a previewed page makes to its own fake address, straight from the project
 * folder. Files are only ever found through [CoderFileStore.resolve], so a request can never reach
 * anything outside the project. Secure (https) requests to other hosts are left alone so the page can
 * use the internet; every other kind of address is refused. [rootPrefix] is "" or a folder name
 * (without a trailing slash) that acts as the top of the site. [context] is kept for the registry
 * contract and is not used yet.
 */
class CoderPreviewResponder(
    @Suppress("unused") private val context: Context,
    private val store: CoderFileStore,
    private val projectId: String,
    private val rootPrefix: String,
) {
    private val previewHost: String = CoderPreviewHost.hostFor(projectId)

    /** Called by the browser for every request. Returns null when the browser should handle it itself. */
    fun respond(request: WebResourceRequest): WebResourceResponse? =
        respondTo(request.url, request.method ?: "GET")

    /**
     * The answer to a request for [url] with HTTP [method], or null for an https address on another
     * host. A pure function of the files on disk, so the self-test calls it without a browser.
     */
    fun respondTo(url: Uri, method: String): WebResourceResponse? {
        if (url.scheme?.lowercase() != "https") {
            return response("text/plain", 403, "Forbidden", BLOCKED_TEXT.toByteArray(Charsets.UTF_8), head = false)
        }
        if (url.host?.lowercase() != previewHost) return null
        return try {
            serve(url, method.uppercase())
        } catch (e: Exception) {
            messagePage(500, "Something went wrong", "This file could not be shown.", head = false)
        }
    }

    private fun serve(url: Uri, method: String): WebResourceResponse {
        if (method != "GET" && method != "HEAD") {
            return messagePage(405, "Method not allowed", "Only GET and HEAD requests are answered here.", head = false)
        }
        val head = method == "HEAD"
        val path = Uri.decode(url.encodedPath.orEmpty()).removePrefix("/")
        val virtual = CoderPreviewHost.VIRTUAL_PREFIX
        if (path == virtual || path.startsWith("$virtual/")) return serveVirtual(path, head)
        return serveFile(path, head)
    }

    private fun serveFile(requested: String, head: Boolean): WebResourceResponse {
        val path = if (requested.isEmpty() || requested.endsWith("/")) requested + "index.html" else requested
        var target = store.resolve(projectId, CoderPreviewRoot.join(rootPrefix, path))
        if (target != null && target.isDirectory) {
            target = store.resolve(projectId, CoderPreviewRoot.join(rootPrefix, "$path/index.html"))
        }
        if (target == null || !target.isFile) return notFound(requested, head)
        return fileResponse(target, head) ?: notFound(requested, head)
    }

    private fun fileResponse(file: File, head: Boolean): WebResourceResponse? {
        val mime = CoderMime.forPath(file.name)
        val encoding = if (CoderMime.isTextMime(mime)) "utf-8" else null
        val stream: InputStream = if (head) {
            ByteArrayInputStream(ByteArray(0))
        } else {
            try {
                FileInputStream(file)
            } catch (e: IOException) {
                return null
            }
        }
        return WebResourceResponse(mime, encoding, 200, "OK", HEADERS, stream)
    }

    // ---- Markdown and JSON pages ------------------------------------------------------------

    private fun serveVirtual(path: String, head: Boolean): WebResourceResponse {
        val md = CoderPreviewHost.VIRTUAL_PREFIX + "/md/"
        val json = CoderPreviewHost.VIRTUAL_PREFIX + "/json/"
        return when {
            path.startsWith(md) -> textPage(path.removePrefix(md), markdown = true, head = head)
            path.startsWith(json) -> textPage(path.removePrefix(json), markdown = false, head = head)
            else -> notFound(path, head)
        }
    }

    /** Reads the project file at [filePath] and shows it as a formatted page. The path is checked by the file store. */
    private fun textPage(filePath: String, markdown: Boolean, head: Boolean): WebResourceResponse {
        return when (val read = store.read(projectId, filePath)) {
            is CoderReadResult.Text -> {
                val html = if (markdown) CoderMarkdown.toHtml(read.text, baseUrlFor(filePath)) else jsonPage(read.text)
                response("text/html", 200, "OK", html.toByteArray(Charsets.UTF_8), head)
            }
            CoderReadResult.TooLarge -> messagePage(200, "This file is too large to preview", "Files over 300 KB are not shown here.", head)
            CoderReadResult.Binary -> messagePage(200, "This file isn't plain text", "It can't be shown as a page.", head)
            CoderReadResult.Missing -> notFound(filePath, head)
            CoderReadResult.Failed -> messagePage(500, "Couldn't open this file", "Please try again.", head)
        }
    }

    /** Relative links and images in a Markdown file start from the folder the file is in. */
    private fun baseUrlFor(filePath: String): String {
        val folder = CoderPaths.parentOf(filePath)
        return if (folder.isEmpty()) {
            CoderPreviewHost.urlFor(projectId, "")
        } else {
            CoderPreviewHost.urlFor(projectId, folder) + "/"
        }
    }

    private fun jsonPage(text: String): String {
        var problem: String? = null
        val shown = try {
            prettyJson(text)
        } catch (e: JSONException) {
            problem = "This isn't valid JSON: " + shortParserMessage(e.message)
            text
        } catch (e: StackOverflowError) {
            problem = "This isn't valid JSON: it is nested too deeply to show."
            text
        }
        val notice = if (problem != null) "<p class=\"err\">" + CoderMarkdown.escape(problem) + "</p>" else ""
        return CoderMarkdown.page("Preview", notice + "<pre>" + CoderMarkdown.escape(shown) + "</pre>")
    }

    /** Pretty-prints objects and lists. Android writes "/" as "\/", which is undone so addresses stay readable. */
    private fun prettyJson(text: String): String {
        return when (val value = JSONTokener(text).nextValue()) {
            is JSONObject -> value.toString(2).replace("\\/", "/")
            is JSONArray -> value.toString(2).replace("\\/", "/")
            else -> text.trim()
        }
    }

    /** The parser puts the whole input after "of" in its message; keep only the part that says what and where. */
    private fun shortParserMessage(message: String?): String {
        val text = message ?: "unknown problem"
        val at = text.indexOf(" at character ")
        val cut = if (at >= 0) text.indexOf(" of ", at) else -1
        val short = if (cut >= 0) text.substring(0, cut) else text
        return short.take(200)
    }

    // ---- Built-in pages ---------------------------------------------------------------------

    private fun notFound(path: String, head: Boolean): WebResourceResponse {
        val body = "<h1>File not found</h1><p><code>" + CoderMarkdown.escape("/$path") + "</code></p>"
        val html = CoderMarkdown.page("File not found", body)
        return response("text/html", 404, "Not Found", html.toByteArray(Charsets.UTF_8), head)
    }

    private fun messagePage(status: Int, title: String, message: String, head: Boolean): WebResourceResponse {
        val body = "<h1>" + CoderMarkdown.escape(title) + "</h1><p>" + CoderMarkdown.escape(message) + "</p>"
        val html = CoderMarkdown.page(title, body)
        return response("text/html", status, reasonFor(status), html.toByteArray(Charsets.UTF_8), head)
    }

    private fun reasonFor(status: Int): String = when (status) {
        200 -> "OK"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        else -> "Error"
    }

    private fun response(mime: String, status: Int, reason: String, bytes: ByteArray, head: Boolean): WebResourceResponse {
        val body = if (head) ByteArray(0) else bytes
        return WebResourceResponse(mime, "utf-8", status, reason, HEADERS, ByteArrayInputStream(body))
    }

    private companion object {
        const val BLOCKED_TEXT = "Blocked: only secure (https) addresses are allowed."

        val HEADERS: Map<String, String> = mapOf(
            "Cache-Control" to "no-store",
            "Access-Control-Allow-Origin" to "*",
        )
    }
}
