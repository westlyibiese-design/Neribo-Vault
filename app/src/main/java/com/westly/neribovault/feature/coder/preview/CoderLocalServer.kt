package com.westly.neribovault.feature.coder.preview

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.westly.neribovault.NeriboApp
import com.westly.neribovault.feature.coder.files.CoderFileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** What a browser needs to open the served project: the [port], the secret [token] and the address [url] that carries it. */
data class CoderServerInfo(val port: Int, val token: String, val url: String)

/** The project that is being served right now, with the details of its server. */
data class CoderServing(val projectId: String, val info: CoderServerInfo)

private const val MAX_CONNECTIONS = 32
private const val READ_TIMEOUT_MS = 15_000
private const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L
private const val IDLE_CHECK_MS = 15_000L
private const val TOKEN_BYTES = 16
private const val MSG_FORBIDDEN = "Forbidden"
private const val MSG_NOT_ALLOWED = "Only GET and HEAD requests are answered here."
private const val MSG_GONE = "File not found"

/**
 * A tiny web server on this phone, so a project's page can be tried in the phone's own browser. It listens on
 * 127.0.0.1 only (nothing else on the Wi-Fi can reach it), on a random free port, and one project is served at a
 * time. Every request needs a secret: the first one carries it in the address and gets it back as a cookie.
 * Files are answered by [CoderPreviewResponder], the same code the preview uses, so the same rules apply.
 * It stops by itself after 10 minutes without a request, and when it is told to.
 */
class CoderLocalServer(private val context: Context, private val store: CoderFileStore) {
    private val lock = Any()
    private var current: Run? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _serving = MutableStateFlow<CoderServing?>(null)

    /** The project being served, or null while the server is off. */
    val serving: StateFlow<CoderServing?> = _serving.asStateFlow()

    /** Everything that belongs to one start of the server. Closing it ends the accept loop and every connection. */
    private inner class Run(
        val projectId: String,
        val rootPrefix: String,
        val socket: ServerSocket,
        val token: String,
        val job: Job,
    ) {
        val port: Int = socket.localPort
        val connections: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
        val openCount = AtomicInteger(0)
        val lastRequestAt = AtomicLong(SystemClock.elapsedRealtime())
        val responder = CoderPreviewResponder(context.applicationContext, store, projectId, rootPrefix)
        val host: String = CoderPreviewHost.hostFor(projectId)

        fun close() {
            job.cancel()
            closeQuietly(socket)
            for (connection in connections.toList()) closeQuietly(connection)
            connections.clear()
        }
    }

    /**
     * Starts serving [projectId] (with [rootPrefix] as the top of the site, as the preview uses it) and returns what a
     * browser needs, or null when no port could be opened. Serving another project first stops the current one.
     * Opens a socket, so call it from `Dispatchers.IO`.
     */
    fun start(projectId: String, rootPrefix: String): CoderServerInfo? {
        return synchronized(lock) {
            stop()
            val socket = try {
                ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            } catch (e: IOException) {
                return@synchronized null
            }
            val token = generateToken()
            val info = CoderServerInfo(socket.localPort, token, "http://127.0.0.1:${socket.localPort}/?k=$token")
            val job = SupervisorJob(scope.coroutineContext[Job])
            val run = Run(projectId, rootPrefix, socket, token, job)
            current = run
            _serving.value = CoderServing(projectId, info)
            val runScope = CoroutineScope(job + Dispatchers.IO)
            runScope.launch { acceptLoop(run, runScope) }
            runScope.launch { watchIdle(run) }
            info
        }
    }

    /** Stops the server and closes every connection. Does nothing when it is not running. */
    fun stop() {
        val run = synchronized(lock) { current } ?: return
        stopRun(run)
    }

    private fun stopRun(run: Run) {
        synchronized(lock) {
            if (current === run) {
                current = null
                _serving.value = null
            }
        }
        run.close()
    }

    /** Stops [run] after ten minutes without a request. Ends by itself when the run is cancelled, because delay() then throws. */
    private suspend fun watchIdle(run: Run) {
        while (true) {
            delay(IDLE_CHECK_MS)
            if (SystemClock.elapsedRealtime() - run.lastRequestAt.get() >= IDLE_TIMEOUT_MS) {
                stopRun(run)
                return
            }
        }
    }

    private fun acceptLoop(run: Run, runScope: CoroutineScope) {
        while (runScope.isActive && !run.socket.isClosed) {
            val client = try {
                run.socket.accept()
            } catch (e: IOException) {
                return
            }
            if (run.openCount.incrementAndGet() > MAX_CONNECTIONS) {
                run.openCount.decrementAndGet()
                closeQuietly(client)
                continue
            }
            run.connections.add(client)
            runScope.launch {
                try {
                    handle(run, client)
                } catch (e: Exception) {
                    // A broken connection must never stop the server; the client just gets no answer.
                } finally {
                    run.connections.remove(client)
                    run.openCount.decrementAndGet()
                    closeQuietly(client)
                }
            }
        }
    }

    private fun handle(run: Run, client: Socket) {
        client.soTimeout = READ_TIMEOUT_MS
        val head = readHead(BufferedInputStream(client.getInputStream())) ?: return
        val output = client.getOutputStream()
        val response = when (head) {
            is HeadResult.TooLarge -> CoderHttpRequest.textResponse(431, "Request Header Fields Too Large", "Request too large")
            is HeadResult.Bytes -> answer(run, head.bytes)
        }
        try {
            response.writeTo(output)
        } catch (e: IOException) {
            // The browser went away while the answer was being sent.
        }
    }

    private fun answer(run: Run, bytes: ByteArray): CoderHttpResponse {
        val parsed = CoderHttpRequest.parseRequest(bytes)
        if (parsed is ParseResult.Bad) return CoderHttpRequest.textResponse(parsed.status, parsed.reason, parsed.reason)
        val request = (parsed as ParseResult.Ok).request
        val head = request.method == "HEAD"
        return when (CoderHttpRequest.decide(request, run.port, run.token)) {
            CoderAccess.Forbidden -> CoderHttpRequest.textResponse(403, "Forbidden", MSG_FORBIDDEN, head = head)
            CoderAccess.MethodNotAllowed ->
                CoderHttpRequest.textResponse(405, "Method Not Allowed", MSG_NOT_ALLOWED, mapOf("Allow" to "GET, HEAD"))
            CoderAccess.SetCookieAndRedirect -> {
                // Not a bare redirect: a small page sets the cookie, makes sure the phone answers for the real page
                // (trying again if it is slow) and only then opens it, so the browser never sits on a blank page.
                run.lastRequestAt.set(SystemClock.elapsedRealtime())
                CoderHttpRequest.openingResponse(request.rawPath, run.token, head = head)
            }
            CoderAccess.Serve -> {
                run.lastRequestAt.set(SystemClock.elapsedRealtime())
                serve(run, request)
            }
        }
    }

    /** Answers a file request through the preview's own responder. A project whose folder is gone is no longer served. */
    private fun serve(run: Run, request: CoderParsedRequest): CoderHttpResponse {
        if (!store.projectDir(run.projectId).isDirectory) {
            stopRun(run)
            return CoderHttpRequest.textResponse(404, "Not Found", MSG_GONE)
        }
        val address = Uri.parse("https://" + run.host + request.rawPath)
        val result = run.responder.respondTo(address, request.method)
            ?: return CoderHttpRequest.textResponse(404, "Not Found", MSG_GONE)
        val headers = LinkedHashMap<String, String>()
        for ((name, value) in result.responseHeaders.orEmpty()) {
            // The preview allows any page to read its files; a page on the open internet must not read these.
            if (!name.equals("Access-Control-Allow-Origin", ignoreCase = true)) headers[name] = value
        }
        val charset = result.encoding
        headers["Content-Type"] = if (charset.isNullOrEmpty()) result.mimeType else result.mimeType + "; charset=" + charset
        val reason = result.reasonPhrase?.takeIf { it.isNotBlank() } ?: "OK"
        return CoderHttpRequest.buildResponse(result.statusCode, reason, headers, result.data)
    }

    /** What reading the start of a request found. */
    private sealed interface HeadResult {
        class Bytes(val bytes: ByteArray) : HeadResult
        object TooLarge : HeadResult
    }

    /**
     * Reads up to the empty line that ends the request head. Null when the browser closed the connection, was too
     * slow (the whole head must arrive within the read time) or sent nothing. A head that never ends within the
     * allowed size is [HeadResult.TooLarge].
     */
    private fun readHead(input: BufferedInputStream): HeadResult? {
        val buffer = ByteArrayOutputStream()
        val started = SystemClock.elapsedRealtime()
        var matched = 0
        while (true) {
            if (SystemClock.elapsedRealtime() - started > READ_TIMEOUT_MS) return null
            val next = try {
                input.read()
            } catch (e: SocketTimeoutException) {
                return null
            } catch (e: IOException) {
                return null
            }
            if (next < 0) return null
            // A request starts with a method such as GET. Anything else (for example a browser trying a secure
            // connection first) is closed at once, so the browser falls back to plain http without waiting.
            if (buffer.size() == 0 && next !in 'A'.code..'Z'.code) return null
            buffer.write(next)
            matched = when {
                next == 13 && (matched == 0 || matched == 2) -> matched + 1
                next == 10 && (matched == 1 || matched == 3) -> matched + 1
                next == 13 -> 1
                else -> 0
            }
            if (matched == 4) return HeadResult.Bytes(buffer.toByteArray())
            if (buffer.size() > CoderHttpRequest.MAX_HEAD_BYTES) return HeadResult.TooLarge
        }
    }

    private fun closeQuietly(closeable: Closeable) {
        try {
            closeable.close()
        } catch (e: IOException) {
            // Already closed or broken; nothing to clean up.
        }
    }

    companion object {
        @Volatile
        private var shared: CoderLocalServer? = null

        /**
         * The one server of the app process. It uses the app's own project files and is created the first time it
         * is asked for. Never keeps an Activity: only the application context is held.
         */
        fun shared(context: Context): CoderLocalServer {
            val existing = shared
            if (existing != null) return existing
            return synchronized(this) {
                val again = shared
                if (again != null) {
                    again
                } else {
                    val app = context.applicationContext
                    val created = CoderLocalServer(app, (app as NeriboApp).container.coderFileStore)
                    shared = created
                    created
                }
            }
        }

        /** A new 128-bit secret as 32 lower-case hex characters, from the secure random generator. */
        fun generateToken(): String {
            val bytes = ByteArray(TOKEN_BYTES)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
    }
}
