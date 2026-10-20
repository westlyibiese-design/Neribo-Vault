package com.westly.neribovault.feature.coder.ai

import org.json.JSONException
import org.json.JSONObject

private const val MAX_DETAIL_CHARS = 300
private const val MESSAGE_PROVIDER_ERROR = "The provider reported an error."
private const val MESSAGE_CONNECTION_CLOSED = "The connection closed before the answer was finished."
private val GEMINI_BLOCKED_FINISH = setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION")

/**
 * What a streamed answer tells the screen, in order: [Started] once the provider accepted the request, any number
 * of [Text] pieces, then exactly one end: [Finished] (with [Finished.truncated] true when the provider ran out of
 * room) or [Failed]. A [Failed] that comes after some [Text] pieces leaves those pieces valid.
 */
sealed interface CoderStreamEvent {
    /** The provider accepted the request and the answer is about to arrive. */
    object Started : CoderStreamEvent

    /** The next piece of the answer. */
    data class Text(val delta: String) : CoderStreamEvent

    /** The answer is complete. [truncated] is true when it was cut off at the provider's length limit. */
    data class Finished(val truncated: Boolean) : CoderStreamEvent

    /** The answer ended with a problem. [message] is plain English and never holds the key or the user's code. */
    data class Failed(val kind: CoderChatFailure, val message: String) : CoderStreamEvent
}

/**
 * Reads one server-sent event of a provider's stream (its name and its JSON text) into zero or more
 * [CoderStreamEvent]s. Pure and tolerant: anything unknown or unreadable gives an empty list. A cut-off flag
 * arrives together with the end of the answer, so the first [CoderStreamEvent.Finished] of a stream is the one that counts.
 */
object CoderStreamEvents {
    /** The events of one stream event of [provider]. [eventName] is the `event:` name, or null when there was none. */
    fun parse(provider: CoderAiProvider, eventName: String?, data: String): List<CoderStreamEvent> {
        val text = data.trim()
        if (text.isEmpty()) return emptyList()
        if (provider == CoderAiProvider.OPENAI && text == "[DONE]") return listOf(CoderStreamEvent.Finished(false))
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return emptyList()
        }
        return when (provider) {
            CoderAiProvider.CLAUDE -> parseClaude(root, eventName)
            CoderAiProvider.OPENAI -> parseOpenAi(root)
            CoderAiProvider.GEMINI -> parseGemini(root)
        }
    }

    private fun parseClaude(root: JSONObject, eventName: String?): List<CoderStreamEvent> {
        val type = stringOf(root, "type") ?: eventName
        return when (type) {
            "content_block_delta" -> {
                val delta = root.optJSONObject("delta")
                val piece = if (delta != null && stringOf(delta, "type") == "text_delta") stringOf(delta, "text") else null
                if (piece.isNullOrEmpty()) emptyList() else listOf(CoderStreamEvent.Text(piece))
            }
            "message_delta" -> {
                val reason = root.optJSONObject("delta")?.let { stringOf(it, "stop_reason") }
                if (reason == "max_tokens") listOf(CoderStreamEvent.Finished(true)) else emptyList()
            }
            "message_stop" -> listOf(CoderStreamEvent.Finished(false))
            "error" -> listOf(claudeFailure(root.optJSONObject("error")))
            else -> emptyList()
        }
    }

    /** Claude's own error types give the same plain texts as the matching HTTP codes; anything else shows its message. */
    private fun claudeFailure(error: JSONObject?): CoderStreamEvent.Failed {
        val code = when (error?.let { stringOf(it, "type") }) {
            "overloaded_error" -> 529
            "rate_limit_error" -> 429
            "authentication_error" -> 401
            else -> 0
        }
        if (code == 0) return otherFailure(error)
        val failure = CoderAiClient.mapError(CoderAiProvider.CLAUDE, code, "")
        return CoderStreamEvent.Failed(failure.kind, failure.message)
    }

    private fun parseOpenAi(root: JSONObject): List<CoderStreamEvent> {
        val error = root.optJSONObject("error")
        if (error != null) return listOf(otherFailure(error))
        val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: return emptyList()
        val out = ArrayList<CoderStreamEvent>()
        val delta = choice.optJSONObject("delta")
        if (delta != null) {
            val content = stringOf(delta, "content")
            if (!content.isNullOrEmpty()) out.add(CoderStreamEvent.Text(content))
            val refusal = stringOf(delta, "refusal")
            if (!refusal.isNullOrEmpty()) out.add(CoderStreamEvent.Text(refusal))
        }
        if (stringOf(choice, "finish_reason") == "length") out.add(CoderStreamEvent.Finished(true))
        return out
    }

    private fun parseGemini(root: JSONObject): List<CoderStreamEvent> {
        val error = root.optJSONObject("error")
        if (error != null) return listOf(otherFailure(error))
        val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
        if (candidate == null) {
            val reason = root.optJSONObject("promptFeedback")?.let { stringOf(it, "blockReason") }
            return if (reason != null) listOf(blocked()) else emptyList()
        }
        val out = ArrayList<CoderStreamEvent>()
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.optBoolean("thought", false)) continue
                val piece = stringOf(part, "text")
                if (!piece.isNullOrEmpty()) out.add(CoderStreamEvent.Text(piece))
            }
        }
        val finishReason = stringOf(candidate, "finishReason")
        if (finishReason != null && finishReason in GEMINI_BLOCKED_FINISH) {
            out.add(blocked())
        } else if (finishReason == "MAX_TOKENS") {
            out.add(CoderStreamEvent.Finished(true))
        }
        return out
    }

    private fun blocked(): CoderStreamEvent.Failed =
        CoderStreamEvent.Failed(CoderChatFailure.Blocked, CoderAiClient.MESSAGE_BLOCKED)

    /** A failure the provider described itself: its one-line message, at most 300 characters, after a fixed first sentence. */
    private fun otherFailure(error: JSONObject?): CoderStreamEvent.Failed {
        val raw = error?.let { stringOf(it, "message") }
        val line = raw?.replace(Regex("\\s+"), " ")?.trim()?.take(MAX_DETAIL_CHARS)
        val message = if (line.isNullOrEmpty()) MESSAGE_PROVIDER_ERROR else "$MESSAGE_PROVIDER_ERROR $line"
        return CoderStreamEvent.Failed(CoderChatFailure.Other, message)
    }

    /** A string value, or null for a missing, null or non-string one. JSON null is not the text "null". */
    private fun stringOf(obj: JSONObject, key: String): String? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return obj.opt(key) as? String
    }
}

/**
 * Reads a whole stream, one line at a time, into the events the screen needs, and makes sure it ends exactly
 * once. It remembers whether any text arrived: an end without text is a failure ("empty answer"), and a safety
 * block after some text only cuts the answer short. When the stream simply stops without its end marker the
 * result depends on the provider: Gemini has no marker, so that is a normal end; Claude and OpenAI always send
 * one, so a missing marker means the connection was lost. Pure, so the self-test can run it on recorded streams.
 */
class CoderStreamDecoder(private val provider: CoderAiProvider) {
    private val sse = CoderSseParser()
    private var sawText = false

    /** True once the end of the answer was returned; every later line and [end] give nothing. */
    var isDone: Boolean = false
        private set

    /** Reads one line of the stream (without its line break) and returns the events it completed. */
    fun feedLine(line: String): List<CoderStreamEvent> = handle(sse.feed(line + "\n"))

    /** Call when the stream has no more lines. Returns the end of the answer if it has not been returned yet. */
    fun end(): List<CoderStreamEvent> {
        if (isDone) return emptyList()
        val out = ArrayList<CoderStreamEvent>(handle(sse.finish()))
        if (isDone) return out
        isDone = true
        if (provider == CoderAiProvider.GEMINI) {
            out.add(finishedOrEmpty(false))
        } else {
            out.add(CoderStreamEvent.Failed(CoderChatFailure.Network, MESSAGE_CONNECTION_CLOSED))
        }
        return out
    }

    private fun handle(events: List<SseEvent>): List<CoderStreamEvent> {
        val out = ArrayList<CoderStreamEvent>()
        for (event in events) {
            if (isDone) break
            for (item in CoderStreamEvents.parse(provider, event.event, event.data)) {
                if (isDone) break
                when (item) {
                    is CoderStreamEvent.Text -> {
                        if (item.delta.isNotBlank()) sawText = true
                        out.add(item)
                    }
                    is CoderStreamEvent.Finished -> {
                        out.add(finishedOrEmpty(item.truncated))
                        isDone = true
                    }
                    is CoderStreamEvent.Failed -> {
                        val cutByFilter = item.kind == CoderChatFailure.Blocked && sawText
                        out.add(if (cutByFilter) CoderStreamEvent.Finished(true) else item)
                        isDone = true
                    }
                    CoderStreamEvent.Started -> out.add(item)
                }
            }
        }
        return out
    }

    private fun finishedOrEmpty(truncated: Boolean): CoderStreamEvent =
        if (sawText) {
            CoderStreamEvent.Finished(truncated)
        } else {
            CoderStreamEvent.Failed(CoderChatFailure.Other, CoderAiClient.MESSAGE_EMPTY)
        }
}
