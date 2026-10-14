package com.westly.neribovault.feature.coder.ai

/** Who wrote a chat message. */
enum class ChatRole { User, Assistant }

/**
 * One message of the chat. [text] is always what was typed (user) or what the AI answered (assistant);
 * the file context that goes with a question is never kept here. [notice] is a short muted line under
 * a user message: what was sent with it, or, when [failed] is true, why it was not sent.
 */
data class CoderChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val failed: Boolean = false,
    val truncated: Boolean = false,
    val notice: String? = null,
)

/** The answer of [CoderAiClient.chat]. A [Failure] message is plain English and never holds the key or the user's code. */
sealed interface CoderChatResult {
    data class Success(val text: String, val truncated: Boolean) : CoderChatResult
    data class Failure(val message: String, val kind: CoderChatFailure) : CoderChatResult
}

/** What went wrong, so the screen knows whether to offer the AI settings. */
enum class CoderChatFailure { InvalidKey, ModelNotFound, RateLimited, Busy, Blocked, Network, Other }

/** One fenced block of code in a reply. [path] is set only when the AI named a valid project path. */
data class CoderCodeBlock(val language: String, val path: String?, val code: String)

/** A reply is shown as a list of plain text pieces and code blocks. */
sealed interface CoderReplySegment {
    data class Text(val text: String) : CoderReplySegment
    data class Code(val block: CoderCodeBlock) : CoderReplySegment
}
