package com.westly.neribovault.feature.coder.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Sends a chat to Claude, OpenAI or Gemini and reads the answer. Every address is fixed and HTTPS, the
 * key travels only in a header, and nothing from the request (key, headers, address, the user's code)
 * is ever put into a result, a log or an error text. The request builder, the answer reader and the
 * error mapper are pure, so the self-test can check them without a network.
 */
object CoderAiClient {
    private const val CLAUDE_URL = "https://api.anthropic.com/v1/messages"
    private const val OPENAI_URL = "https://api.openai.com/v1/chat/completions"
    private const val GEMINI_HOST = "generativelanguage.googleapis.com"
    private const val ANTHROPIC_VERSION = "2023-06-01"
    private const val CLAUDE_MAX_TOKENS = 4096
    private const val MAX_REPLY_BYTES = 2 * 1024 * 1024
    private const val MAX_DETAIL_CHARS = 300

    private const val MESSAGE_INVALID_KEY = "Your key was not accepted. Check it in AI settings."
    private const val MESSAGE_MODEL_NOT_FOUND = "That model wasn't found. Choose another one in AI settings."
    private const val MESSAGE_RATE_LIMITED = "Your provider says you've used up your limit or need to add billing."
    private const val MESSAGE_BUSY = "The provider is busy. Try again in a moment."
    private const val MESSAGE_REJECTED = "The provider rejected the request."
    private const val MESSAGE_BLOCKED = "The provider's safety filter blocked this request. Try rewording it."
    private const val MESSAGE_NOT_UNDERSTOOD = "The provider's answer wasn't understood."
    private const val MESSAGE_EMPTY = "The provider sent an empty answer. Try again."
    private const val MESSAGE_TOO_LARGE = "The provider's answer was too large to read."

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val GEMINI_BLOCKED_FINISH = setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION")

    /**
     * Sends [messages] (which must start with and end with a user message) with [system] and waits for
     * the whole answer. Runs on the IO dispatcher. Cancelling the coroutine cancels the call and gives
     * no result. A key the phone cannot put into a header gives [CoderChatFailure.InvalidKey].
     */
    suspend fun chat(
        config: CoderAiConfig,
        system: String,
        messages: List<Pair<ChatRole, String>>,
    ): CoderChatResult = withContext(Dispatchers.IO) {
        val request = try {
            buildRequest(config, system, messages)
        } catch (e: IllegalArgumentException) {
            // The exception text repeats the header value (the key), so it is dropped on purpose.
            return@withContext CoderChatResult.Failure(MESSAGE_INVALID_KEY, CoderChatFailure.InvalidKey)
        }
        val result: CoderChatResult = try {
            CoderHttp.client.newCall(request).awaitResponse().use { response ->
                val body = readBody(response)
                if (response.isSuccessful) {
                    if (body == null) {
                        CoderChatResult.Failure(MESSAGE_TOO_LARGE, CoderChatFailure.Other)
                    } else {
                        parseResponse(config.provider, body)
                    }
                } else {
                    mapError(config.provider, response.code, body.orEmpty())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A cancelled call fails with a plain IOException; that must not look like a network error.
            ensureActive()
            CoderChatResult.Failure(describeFailure(e), CoderChatFailure.Network)
        }
        withoutKey(result, config.apiKey)
    }

    /**
     * The POST request for [config]'s provider. The key is only in a header; the address and the body
     * never hold it. Throws [IllegalArgumentException] when the key holds a character a header cannot carry.
     */
    fun buildRequest(config: CoderAiConfig, system: String, messages: List<Pair<ChatRole, String>>): Request {
        val builder = Request.Builder()
        val body: JSONObject = when (config.provider) {
            CoderAiProvider.CLAUDE -> {
                builder.url(CLAUDE_URL)
                    .header("x-api-key", config.apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                JSONObject()
                    .put("model", config.model)
                    .put("max_tokens", CLAUDE_MAX_TOKENS)
                    .put("system", system)
                    .put("messages", turnArray(messages, "assistant"))
            }
            CoderAiProvider.OPENAI -> {
                builder.url(OPENAI_URL)
                    .header("Authorization", "Bearer " + config.apiKey)
                val all = JSONArray()
                all.put(JSONObject().put("role", "system").put("content", system))
                val turns = turnArray(messages, "assistant")
                for (i in 0 until turns.length()) all.put(turns.get(i))
                JSONObject().put("model", config.model).put("messages", all)
            }
            CoderAiProvider.GEMINI -> {
                val model = config.model.removePrefix("models/")
                val url = HttpUrl.Builder()
                    .scheme("https")
                    .host(GEMINI_HOST)
                    .addPathSegment("v1beta")
                    .addPathSegment("models")
                    .addPathSegment("$model:generateContent")
                    .build()
                builder.url(url).header("x-goog-api-key", config.apiKey)
                JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    .put("contents", geminiContents(messages))
            }
        }
        return builder.post(body.toString().toRequestBody(JSON_TYPE)).build()
    }

    /** Reads a successful answer of [provider]. Failures it finds (a blocked request, an empty or unknown answer) are returned, not thrown. */
    fun parseResponse(provider: CoderAiProvider, body: String): CoderChatResult {
        val root = try {
            JSONObject(body)
        } catch (e: JSONException) {
            return notUnderstood()
        }
        return when (provider) {
            CoderAiProvider.CLAUDE -> parseClaude(root)
            CoderAiProvider.OPENAI -> parseOpenAi(root)
            CoderAiProvider.GEMINI -> parseGemini(root)
        }
    }

    /**
     * Turns a failed HTTP answer into a plain-English failure. For a 400 the provider's own short
     * message is added (one line, at most 300 characters). Gemini's wrong-key answer, a 400 with
     * API_KEY_INVALID, counts as an invalid key.
     */
    fun mapError(provider: CoderAiProvider, httpCode: Int, bodyText: String): CoderChatResult.Failure = when {
        httpCode == 401 || httpCode == 403 ->
            CoderChatResult.Failure(MESSAGE_INVALID_KEY, CoderChatFailure.InvalidKey)
        provider == CoderAiProvider.GEMINI && httpCode == 400 && bodyText.contains("API_KEY_INVALID") ->
            CoderChatResult.Failure(MESSAGE_INVALID_KEY, CoderChatFailure.InvalidKey)
        httpCode == 404 -> CoderChatResult.Failure(MESSAGE_MODEL_NOT_FOUND, CoderChatFailure.ModelNotFound)
        httpCode == 429 -> CoderChatResult.Failure(MESSAGE_RATE_LIMITED, CoderChatFailure.RateLimited)
        httpCode == 529 || httpCode == 503 -> CoderChatResult.Failure(MESSAGE_BUSY, CoderChatFailure.Busy)
        httpCode == 400 -> CoderChatResult.Failure(rejectedText(bodyText), CoderChatFailure.Other)
        else -> CoderChatResult.Failure("The provider returned an error ($httpCode).", CoderChatFailure.Other)
    }

    // ---- Request bodies --------------------------------------------------------------------

    private fun turnArray(messages: List<Pair<ChatRole, String>>, assistantName: String): JSONArray {
        val array = JSONArray()
        for ((role, text) in messages) {
            val name = if (role == ChatRole.User) "user" else assistantName
            array.put(JSONObject().put("role", name).put("content", text))
        }
        return array
    }

    private fun geminiContents(messages: List<Pair<ChatRole, String>>): JSONArray {
        val array = JSONArray()
        for ((role, text) in messages) {
            val name = if (role == ChatRole.User) "user" else "model"
            val parts = JSONArray().put(JSONObject().put("text", text))
            array.put(JSONObject().put("role", name).put("parts", parts))
        }
        return array
    }

    // ---- Answers ---------------------------------------------------------------------------

    private fun parseClaude(root: JSONObject): CoderChatResult {
        val content = root.optJSONArray("content") ?: return notUnderstood()
        val text = StringBuilder()
        for (i in 0 until content.length()) {
            val item = content.optJSONObject(i) ?: continue
            if (stringOf(item, "type") == "text") stringOf(item, "text")?.let { text.append(it) }
        }
        return finished(text.toString(), stringOf(root, "stop_reason") == "max_tokens")
    }

    private fun parseOpenAi(root: JSONObject): CoderChatResult {
        val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: return notUnderstood()
        val message = choice.optJSONObject("message") ?: return notUnderstood()
        var text = stringOf(message, "content").orEmpty()
        if (text.isBlank()) text = stringOf(message, "refusal").orEmpty()
        return finished(text, stringOf(choice, "finish_reason") == "length")
    }

    private fun parseGemini(root: JSONObject): CoderChatResult {
        val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
        if (candidate == null) {
            val reason = root.optJSONObject("promptFeedback")?.let { stringOf(it, "blockReason") }
            return if (reason != null) blocked() else notUnderstood()
        }
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
        val text = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.optBoolean("thought", false)) continue
                stringOf(part, "text")?.let { text.append(it) }
            }
        }
        val finishReason = stringOf(candidate, "finishReason")
        if (text.isBlank() && finishReason != null && finishReason in GEMINI_BLOCKED_FINISH) return blocked()
        return finished(text.toString(), finishReason == "MAX_TOKENS")
    }

    private fun finished(text: String, truncated: Boolean): CoderChatResult =
        if (text.isBlank()) {
            CoderChatResult.Failure(MESSAGE_EMPTY, CoderChatFailure.Other)
        } else {
            CoderChatResult.Success(text, truncated)
        }

    private fun notUnderstood(): CoderChatResult =
        CoderChatResult.Failure(MESSAGE_NOT_UNDERSTOOD, CoderChatFailure.Other)

    private fun blocked(): CoderChatResult = CoderChatResult.Failure(MESSAGE_BLOCKED, CoderChatFailure.Blocked)

    /** A string value, or null for a missing, null or non-string one. JSON null is not the text "null". */
    private fun stringOf(obj: JSONObject, key: String): String? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return obj.opt(key) as? String
    }

    // ---- Errors and body -------------------------------------------------------------------

    private fun rejectedText(bodyText: String): String {
        val detail = providerMessage(bodyText)
        return if (detail == null) MESSAGE_REJECTED else "$MESSAGE_REJECTED $detail"
    }

    /** The provider's own error message: one line, at most 300 characters, or null when there is none. */
    private fun providerMessage(bodyText: String): String? {
        val root = try {
            JSONObject(bodyText)
        } catch (e: JSONException) {
            return null
        }
        val raw = root.optJSONObject("error")?.let { stringOf(it, "message") } ?: return null
        val line = raw.replace(Regex("\\s+"), " ").trim().take(MAX_DETAIL_CHARS)
        return if (line.isEmpty()) null else line
    }

    /** Replaces the key with a placeholder if a provider's message happened to repeat it. */
    private fun withoutKey(result: CoderChatResult, apiKey: String): CoderChatResult {
        if (result !is CoderChatResult.Failure || apiKey.isEmpty() || !result.message.contains(apiKey)) return result
        return result.copy(message = result.message.replace(apiKey, "[hidden]"))
    }

    /** The answer as text, or null when it is bigger than 2 MB. */
    private fun readBody(response: Response): String? {
        val stream = response.body?.byteStream() ?: return ""
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val count = stream.read(chunk)
            if (count < 0) break
            if (out.size() + count > MAX_REPLY_BYTES) return null
            out.write(chunk, 0, count)
        }
        return out.toString("UTF-8")
    }
}
