package com.westly.neribovault.feature.coder.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** The answer of [listModels]. */
sealed interface CoderModelsResult {
    /** The key works. [models] are the ids it may use, sorted and without duplicates. */
    data class Success(val models: List<String>) : CoderModelsResult

    /** Nothing was listed. [message] is plain English and never holds the key or the provider's reply. */
    data class Failure(val kind: Kind, val message: String) : CoderModelsResult

    /** What went wrong, so the screen knows whether the key itself was refused. */
    enum class Kind { InvalidKey, RateLimited, Network, Other }
}

private const val MESSAGE_INVALID_KEY = "That key wasn't accepted. Check that you copied all of it."
private const val MESSAGE_RATE_LIMITED = "Your provider says you've used up your limit or need to add billing."
private const val MESSAGE_NOT_UNDERSTOOD = "The provider's answer wasn't understood."

/** The biggest answer that is read; a model list is far smaller. */
private const val MAX_BODY_BYTES = 2 * 1024 * 1024

/** Words that mark a model as something other than a chat model. They are hidden from the OpenAI list. */
private val NON_CHAT_WORDS = listOf(
    "embedding", "whisper", "tts", "dall-e", "moderation", "image",
    "audio", "realtime", "transcribe", "davinci", "babbage", "search",
)

/**
 * Asks [provider] which models [apiKey] may use. This is also the test of the key. Every address is fixed,
 * everything is HTTPS, and the key travels only in a header, never in the address. Nothing from the request
 * or the reply is put into the failure text.
 */
suspend fun listModels(provider: CoderAiProvider, apiKey: String): CoderModelsResult = withContext(Dispatchers.IO) {
    val request = buildRequest(provider, apiKey)
        ?: return@withContext CoderModelsResult.Failure(CoderModelsResult.Kind.InvalidKey, MESSAGE_INVALID_KEY)
    try {
        CoderHttp.client.newCall(request).awaitResponse().use { response -> readResult(provider, response) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CoderModelsResult.Failure(CoderModelsResult.Kind.Network, describeFailure(e))
    }
}

/** The GET request for [provider], or null when the key holds a character a header cannot carry. */
private fun buildRequest(provider: CoderAiProvider, apiKey: String): Request? = try {
    val builder = Request.Builder().get()
    when (provider) {
        CoderAiProvider.CLAUDE -> builder
            .url("https://api.anthropic.com/v1/models?limit=1000")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
        CoderAiProvider.OPENAI -> builder
            .url("https://api.openai.com/v1/models")
            .header("Authorization", "Bearer $apiKey")
        CoderAiProvider.GEMINI -> builder
            .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000")
            .header("x-goog-api-key", apiKey)
    }
    builder.build()
} catch (e: IllegalArgumentException) {
    // The exception text repeats the header value, so it is dropped on purpose.
    null
}

private fun readResult(provider: CoderAiProvider, response: Response): CoderModelsResult {
    val code = response.code
    if (response.isSuccessful) {
        val body = readBody(response) ?: return notUnderstood()
        val models = parseModels(provider, body) ?: return notUnderstood()
        return CoderModelsResult.Success(models)
    }
    return when {
        code == 401 || code == 403 -> failure(CoderModelsResult.Kind.InvalidKey, MESSAGE_INVALID_KEY)
        code == 429 -> failure(CoderModelsResult.Kind.RateLimited, MESSAGE_RATE_LIMITED)
        // Google answers a wrong key with 400 and the reason API_KEY_INVALID instead of 401.
        provider == CoderAiProvider.GEMINI && code == 400 && readBody(response)?.contains("API_KEY_INVALID") == true ->
            failure(CoderModelsResult.Kind.InvalidKey, MESSAGE_INVALID_KEY)
        else -> failure(CoderModelsResult.Kind.Other, "The provider returned an error ($code).")
    }
}

private fun failure(kind: CoderModelsResult.Kind, message: String): CoderModelsResult =
    CoderModelsResult.Failure(kind, message)

private fun notUnderstood(): CoderModelsResult =
    CoderModelsResult.Failure(CoderModelsResult.Kind.Other, MESSAGE_NOT_UNDERSTOOD)

/** The reply as text, or null when there is none or it is bigger than the limit. */
private fun readBody(response: Response): String? {
    val stream = response.body?.byteStream() ?: return null
    val out = ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    while (true) {
        val count = stream.read(chunk)
        if (count < 0) break
        if (out.size() + count > MAX_BODY_BYTES) return null
        out.write(chunk, 0, count)
    }
    return out.toString("UTF-8")
}

/**
 * Turns a provider's model list into plain ids, sorted and without duplicates. Returns null when [body]
 * is not the expected JSON. OpenAI's list hides models that are not for chat; Gemini's keeps only models
 * that can generate text and drops the "models/" start of the name. Pure, so the self-test can check it.
 */
internal fun parseModels(provider: CoderAiProvider, body: String): List<String>? {
    val root = try {
        JSONObject(body)
    } catch (e: JSONException) {
        return null
    }
    val ids: List<String> = when (provider) {
        CoderAiProvider.CLAUDE -> idsOf(root.optJSONArray("data"))
        CoderAiProvider.OPENAI -> idsOf(root.optJSONArray("data"))?.filter { isChatModel(it) }
        CoderAiProvider.GEMINI -> geminiIds(root.optJSONArray("models"))
    } ?: return null
    return ids.mapNotNull { cleanModelName(it) }.distinct().sorted()
}

private fun idsOf(array: JSONArray?): List<String>? {
    if (array == null) return null
    val out = ArrayList<String>()
    for (i in 0 until array.length()) {
        val id = array.optJSONObject(i)?.let { textOf(it, "id") } ?: continue
        out.add(id)
    }
    return out
}

private fun geminiIds(array: JSONArray?): List<String>? {
    if (array == null) return null
    val out = ArrayList<String>()
    for (i in 0 until array.length()) {
        val model = array.optJSONObject(i) ?: continue
        if (!canGenerate(model.optJSONArray("supportedGenerationMethods"))) continue
        val name = textOf(model, "name") ?: continue
        val id = name.removePrefix("models/")
        if (id.isNotEmpty()) out.add(id)
    }
    return out
}

private fun canGenerate(methods: JSONArray?): Boolean {
    if (methods == null) return false
    for (i in 0 until methods.length()) {
        if (methods.optString(i, "") == "generateContent") return true
    }
    return false
}

private fun isChatModel(id: String): Boolean {
    val lower = id.lowercase()
    return NON_CHAT_WORDS.none { lower.contains(it) }
}

/** A non-blank string value, or null for a missing, null or blank one. */
private fun textOf(obj: JSONObject, key: String): String? {
    if (!obj.has(key) || obj.isNull(key)) return null
    val value = obj.optString(key, "").trim()
    return if (value.isEmpty()) null else value
}
