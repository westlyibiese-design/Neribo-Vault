package com.westly.neribovault.feature.coder.ai

/** Who wrote a chat message. */
enum class ChatRole { User, Assistant }

/**
 * One message of the chat. [text] is always what was typed (user) or what the AI answered (assistant, the raw text
 * so far); the file context that goes with a question is never kept here. [notice] is a short muted line: under a
 * user message, what was sent with it or, when [failed] is true, why it was not sent; under an assistant message,
 * why the answer ended early (stopped, cut short, interrupted).
 *
 * The fields from [segments] on belong to an assistant message that arrives as a stream. [segments] are its text
 * and code pieces so far (empty for a question), [openBlock] the code block being written right now, [stage] the
 * real step it is in, [stopped] true when the person tapped Stop, [interrupted] true when the connection or the
 * provider failed after some text had arrived, and [elapsedMillis] the time since the question was sent (frozen
 * when the answer ended). [plan] is the file plan of an answer that ended normally with two or more files in it.
 */
data class CoderChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val failed: Boolean = false,
    val truncated: Boolean = false,
    val notice: String? = null,
    val segments: List<CoderReplySegment> = emptyList(),
    val openBlock: OpenBlock? = null,
    val stage: CoderAiProgress? = null,
    val stopped: Boolean = false,
    val interrupted: Boolean = false,
    val elapsedMillis: Long = 0L,
    val plan: CoderFilePlan? = null,
)

/** Where a file plan is: waiting for a choice, busy applying or undoing, or applied (and undoable with the backup [backupId]). */
sealed interface CoderPlanPhase {
    object Idle : CoderPlanPhase
    object Working : CoderPlanPhase
    data class Applied(val backupId: String) : CoderPlanPhase
}

/**
 * The files an answer wrote, offered together. [rows] are the files in the order of the answer, [selected] the
 * paths that are checked, [phase] what the card is doing and [note] the result line of the last apply or undo.
 */
data class CoderFilePlan(
    val rows: List<CoderPlanRow>,
    val selected: Set<String>,
    val phase: CoderPlanPhase = CoderPlanPhase.Idle,
    val note: String? = null,
)

/** The answer of [CoderAiClient.chat]. A [Failure] message is plain English and never holds the key or the user's code. */
sealed interface CoderChatResult {
    data class Success(val text: String, val truncated: Boolean) : CoderChatResult
    data class Failure(val message: String, val kind: CoderChatFailure) : CoderChatResult
}

/** What went wrong, so the screen knows whether to offer the AI settings. */
enum class CoderChatFailure { InvalidKey, ModelNotFound, RateLimited, Busy, Blocked, Network, Other }

/**
 * One fenced block of code in a reply. [path] is set only when the AI named a valid project path. [unfinished] is
 * true only for a block of a streamed answer whose closing fence never arrived (the answer was stopped, cut short
 * or interrupted); such a block must not be applied, only copied. [parseReply] never sets it.
 */
data class CoderCodeBlock(val language: String, val path: String?, val code: String, val unfinished: Boolean = false)

/** A reply is shown as a list of plain text pieces and code blocks. */
sealed interface CoderReplySegment {
    data class Text(val text: String) : CoderReplySegment
    data class Code(val block: CoderCodeBlock) : CoderReplySegment
}
