package com.westly.neribovault.feature.coder.preview

import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** One request the local server understood: the method, the path (as sent and decoded), the `k` value, the headers and the cookies. */
class CoderParsedRequest(
    val method: String,
    /** The path exactly as sent, percent-encoded, without the query. Always starts with a single "/". */
    val rawPath: String,
    /** [rawPath] with the percent-encoding undone. */
    val path: String,
    /** The value of the `k` parameter in the query, decoded, or null when there is none. */
    val queryToken: String?,
    /** Header names in lower case. When a name repeats, the last value is kept. */
    val headers: Map<String, String>,
    /** The name and value of every cookie sent, in the order sent. */
    val cookies: List<Pair<String, String>>,
) {
    /** The first cookie called [name], or null. */
    fun cookie(name: String): String? = cookies.firstOrNull { it.first == name }?.second
}

/** The result of reading a request: it was understood ([Ok]) or it is refused with [Bad.status]. */
sealed interface ParseResult {
    class Ok(val request: CoderParsedRequest) : ParseResult
    class Bad(val status: Int, val reason: String) : ParseResult
}

/** What the server does with a request that was understood. */
enum class CoderAccess { Forbidden, MethodNotAllowed, SetCookieAndRedirect, Serve }

/** A response ready to be written: the head as bytes and the body to copy after it (null for no body). */
class CoderHttpResponse(val headBytes: ByteArray, private val body: InputStream?) {
    /** The head as text, for the self-test. */
    fun headText(): String = String(headBytes, Charsets.ISO_8859_1)

    /** Writes the head and then the body in small pieces, so a large file never sits in memory. Always closes the body. */
    @Throws(IOException::class)
    fun writeTo(out: OutputStream) {
        try {
            out.write(headBytes)
            if (body != null) {
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    val read = body.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
            }
            out.flush()
        } finally {
            try {
                body?.close()
            } catch (e: IOException) {
                // The body is finished or the connection is gone; there is nothing left to do with it.
            }
        }
    }

    private companion object {
        const val COPY_BUFFER = 16 * 1024
    }
}

/**
 * Reading requests and writing responses for the local server. Everything here is pure: it works on bytes and
 * streams, so the self-test checks it without a socket. Only GET and HEAD are served.
 */
object CoderHttpRequest {
    /** The name of the cookie that proves a browser opened the page with the right token. */
    const val COOKIE_NAME = "coderk"

    /** The longest request line (method, path and version) that is accepted. */
    const val MAX_REQUEST_LINE = 8 * 1024

    /** The most bytes all header lines together may use. */
    const val MAX_HEADER_BYTES = 16 * 1024

    /** The most header lines a request may have. */
    const val MAX_HEADERS = 100

    /** The most bytes of a request head the server ever reads while looking for its end. */
    const val MAX_HEAD_BYTES = MAX_REQUEST_LINE + MAX_HEADER_BYTES + 8

    /**
     * Reads the head of a request: [bytes] hold the request line and the header lines, with or without the
     * empty line that ends them. Anything that is not a plain HTTP/1.0 or 1.1 request with a path is refused:
     * 400 for a bad or too long request line, 431 for too many or too large headers.
     */
    fun parseRequest(bytes: ByteArray): ParseResult {
        val text = String(bytes, Charsets.ISO_8859_1)
        val firstBreak = text.indexOf("\r\n")
        val line = if (firstBreak >= 0) text.substring(0, firstBreak) else text
        if (line.length > MAX_REQUEST_LINE) return ParseResult.Bad(400, "Bad Request")
        val parts = line.split(' ')
        if (parts.size != 3) return ParseResult.Bad(400, "Bad Request")
        val method = parts[0]
        val target = parts[1]
        val version = parts[2]
        if (method.isEmpty() || !method.all { it in 'A'..'Z' }) return ParseResult.Bad(400, "Bad Request")
        if (version != "HTTP/1.1" && version != "HTTP/1.0") return ParseResult.Bad(400, "Bad Request")
        if (!target.startsWith("/") || target.startsWith("//")) return ParseResult.Bad(400, "Bad Request")
        if (firstBreak < 0) return ParseResult.Bad(400, "Bad Request")

        val headerText = text.substring(firstBreak + 2)
        if (headerText.length > MAX_HEADER_BYTES) return ParseResult.Bad(431, "Request Header Fields Too Large")
        val headers = LinkedHashMap<String, String>()
        var count = 0
        for (raw in headerText.split("\r\n")) {
            if (raw.isEmpty()) continue
            count += 1
            if (count > MAX_HEADERS) return ParseResult.Bad(431, "Request Header Fields Too Large")
            if (raw[0] == ' ' || raw[0] == '\t') return ParseResult.Bad(400, "Bad Request")
            val colon = raw.indexOf(':')
            if (colon <= 0) return ParseResult.Bad(400, "Bad Request")
            val name = raw.substring(0, colon)
            if (name.any { it == ' ' || it == '\t' || it.code < 33 || it.code > 126 }) return ParseResult.Bad(400, "Bad Request")
            headers[name.lowercase()] = raw.substring(colon + 1).trim()
        }

        val noFragment = target.substringBefore('#')
        val rawPath = noFragment.substringBefore('?')
        val query = if (noFragment.contains('?')) noFragment.substringAfter('?') else ""
        val path = Uri.decode(rawPath)
        if (path.any { it.code < 32 || it.code == 127 }) return ParseResult.Bad(400, "Bad Request")
        return ParseResult.Ok(
            CoderParsedRequest(
                method = method,
                rawPath = rawPath,
                path = path,
                queryToken = tokenFromQuery(query),
                headers = headers,
                cookies = parseCookies(headers["cookie"]),
            ),
        )
    }

    /** The decoded value of the first `k` parameter of [query], or null. */
    private fun tokenFromQuery(query: String): String? {
        if (query.isEmpty()) return null
        for (pair in query.split('&')) {
            val name = pair.substringBefore('=')
            if (name == "k" && pair.contains('=')) return Uri.decode(pair.substringAfter('='))
        }
        return null
    }

    /** The name and value of each cookie in a `Cookie` header, in order. Pieces without "=" are ignored. */
    private fun parseCookies(header: String?): List<Pair<String, String>> {
        if (header.isNullOrBlank()) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (piece in header.split(';')) {
            val trimmed = piece.trim()
            val equals = trimmed.indexOf('=')
            if (equals <= 0) continue
            out.add(trimmed.substring(0, equals).trim() to trimmed.substring(equals + 1).trim())
        }
        return out
    }

    /** True when the `Host` header is `127.0.0.1:[port]` or `localhost:[port]`; anything else could be a DNS-rebinding attack. */
    fun hostAllowed(request: CoderParsedRequest, port: Int): Boolean {
        val host = request.headers["host"]?.lowercase() ?: return false
        return host == "127.0.0.1:$port" || host == "localhost:$port"
    }

    /**
     * What to do with [request] on a server listening on [port] with the secret [token]: refuse a wrong host
     * (403), anything but GET and HEAD (405), and a request with neither the right cookie nor the right `k` (403).
     * The right `k` answers with a redirect that sets the cookie; the right cookie is served.
     */
    fun decide(request: CoderParsedRequest, port: Int, token: String): CoderAccess {
        if (!hostAllowed(request, port)) return CoderAccess.Forbidden
        if (request.method != "GET" && request.method != "HEAD") return CoderAccess.MethodNotAllowed
        if (request.cookies.any { it.first == COOKIE_NAME && sameSecret(it.second, token) }) return CoderAccess.Serve
        val sent = request.queryToken
        if (sent != null && sameSecret(sent, token)) return CoderAccess.SetCookieAndRedirect
        return CoderAccess.Forbidden
    }

    /** Compares two secrets in a time that does not depend on where they differ. */
    private fun sameSecret(sent: String, expected: String): Boolean =
        MessageDigest.isEqual(sent.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))

    /** The `Set-Cookie` value that remembers [token] for this browser. HttpOnly and SameSite=Strict keep pages from reading or sending it. */
    fun cookieHeader(token: String): String = "$COOKIE_NAME=$token; Path=/; HttpOnly; SameSite=Strict"

    /**
     * An HTTP/1.1 response. [headers] are copied; header names and values lose any line breaks. The connection is
     * always closed after the response, nothing may be guessed from the content type, and nothing is cached.
     * Content-Length, Transfer-Encoding and Connection from [headers] are ignored. [bodyStream] is read when the
     * response is written; pass null for no body.
     */
    fun buildResponse(status: Int, reason: String, headers: Map<String, String>, bodyStream: InputStream?): CoderHttpResponse {
        val merged = LinkedHashMap<String, Pair<String, String>>()
        for ((name, value) in headers) {
            val cleanName = name.filter { it.code in 33..126 && it != ':' }
            val key = cleanName.lowercase()
            if (cleanName.isEmpty() || key == "content-length" || key == "transfer-encoding" || key == "connection") continue
            merged[key] = cleanName to value.replace("\r", " ").replace("\n", " ")
        }
        merged["connection"] = "Connection" to "close"
        merged["x-content-type-options"] = "X-Content-Type-Options" to "nosniff"
        merged["cache-control"] = "Cache-Control" to "no-store"
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason.replace("\r", " ").replace("\n", " ")).append("\r\n")
        for ((name, value) in merged.values) head.append(name).append(": ").append(value).append("\r\n")
        head.append("\r\n")
        return CoderHttpResponse(head.toString().toByteArray(Charsets.ISO_8859_1), bodyStream)
    }

    /** A short plain-text response, such as the 403 and 405 answers. The body is left out when [head] is true (a HEAD request). */
    fun textResponse(
        status: Int,
        reason: String,
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
        head: Boolean = false,
    ): CoderHttpResponse {
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = "text/plain; charset=utf-8"
        headers.putAll(extraHeaders)
        val stream: InputStream? = if (head) null else ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
        return buildResponse(status, reason, headers, stream)
    }
}
