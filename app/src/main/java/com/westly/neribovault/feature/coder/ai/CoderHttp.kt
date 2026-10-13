package com.westly.neribovault.feature.coder.ai

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The one HTTP client for AI and GitHub calls. Built once, with no logging and no cache, so request
 * headers (which carry the key) are never written anywhere. The long read timeout is for slow chat replies.
 */
object CoderHttp {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}

/**
 * Sends the call and waits for the answer without blocking a thread. Cancelling the coroutine cancels the
 * call. An answer that arrives after the coroutine was cancelled is closed so no connection is left open.
 * The caller must close the returned [Response].
 */
suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation {
        try {
            cancel()
        } catch (e: Exception) {
            // The call is already finished; there is nothing left to cancel.
        }
    }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) {
                continuation.resume(response)
            } else {
                response.close()
            }
        }
    })
}

/**
 * A plain-English reason for a failed request. It never includes the exception's own message,
 * the address or the headers, because those can contain the key.
 */
internal fun describeFailure(e: Throwable): String = when (e) {
    is UnknownHostException, is ConnectException -> "No internet connection."
    is SocketTimeoutException, is InterruptedIOException -> "The request took too long. Try again."
    is SSLException -> "A secure connection couldn't be made."
    else -> "Something went wrong with the connection."
}
