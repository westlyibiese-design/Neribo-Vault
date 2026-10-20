package com.westly.neribovault.feature.coder.ai

/**
 * The real steps of one AI answer, in order: [Reading] the project files into the question, [Asking] the provider
 * (until the first text), [Writing] (while text arrives), and one of four endings: [Finished], [Stopped] (the person
 * tapped Stop), [CutShort] (the provider or the 200,000-character limit ended it early) and [Failed].
 */
enum class CoderAiStage { Reading, Asking, Writing, Finished, Stopped, CutShort, Failed }

/** Things that happen to an answer and move it from one stage to the next. */
enum class CoderStageEvent { ContextRead, TextArrived, StreamFinished, StreamCutShort, StopTapped, ErrorHappened }

/** The stage of an answer plus the facts its labels need: how many files were read, and which provider and model were asked. */
data class CoderAiProgress(
    val stage: CoderAiStage,
    val fileCount: Int = 0,
    val providerName: String = "",
    val model: String = "",
)

/** One line of the progress card. [done] steps show a check mark; [detail] is a muted extra such as the seconds counter. */
data class CoderStageStep(val label: String, val done: Boolean, val detail: String? = null)

/**
 * The rules of the progress card as pure functions: which stage follows which event, which steps to show and what
 * the one-line summary says. Only real stages exist here; there is no "thinking" and no percentage.
 */
object CoderAiStages {
    /** True for the four endings. A stage that ended never changes again. */
    fun isTerminal(stage: CoderAiStage): Boolean =
        stage == CoderAiStage.Finished || stage == CoderAiStage.Stopped ||
            stage == CoderAiStage.CutShort || stage == CoderAiStage.Failed

    /**
     * The stage after [event]. Text means Writing; the end of the stream is Finished or CutShort only when text
     * arrived (an end with no text at all counts as Failed); Stop and errors end the answer from any stage.
     */
    fun next(stage: CoderAiStage, event: CoderStageEvent): CoderAiStage {
        if (isTerminal(stage)) return stage
        return when (event) {
            CoderStageEvent.ContextRead -> if (stage == CoderAiStage.Reading) CoderAiStage.Asking else stage
            CoderStageEvent.TextArrived -> CoderAiStage.Writing
            CoderStageEvent.StreamFinished -> if (stage == CoderAiStage.Writing) CoderAiStage.Finished else CoderAiStage.Failed
            CoderStageEvent.StreamCutShort -> if (stage == CoderAiStage.Writing) CoderAiStage.CutShort else CoderAiStage.Failed
            CoderStageEvent.StopTapped -> CoderAiStage.Stopped
            CoderStageEvent.ErrorHappened -> CoderAiStage.Failed
        }
    }

    /**
     * The steps to show while the answer is still running, in order; each step that was reached is listed, the
     * earlier ones as done. Empty once the answer ended. [writingTarget] names what is being written: a file path,
     * "code" for a block without a path, or null for plain text.
     */
    fun steps(progress: CoderAiProgress, writingTarget: String?, elapsedMillis: Long): List<CoderStageStep> {
        val stage = progress.stage
        if (isTerminal(stage)) return emptyList()
        val out = ArrayList<CoderStageStep>()
        out.add(CoderStageStep(readingLabel(progress.fileCount), done = stage != CoderAiStage.Reading))
        if (stage == CoderAiStage.Reading) return out
        val waiting = stage == CoderAiStage.Asking
        out.add(
            CoderStageStep(
                label = askingLabel(progress.providerName, progress.model),
                done = !waiting,
                detail = if (waiting) liveSecondsText(elapsedMillis) else null,
            ),
        )
        if (stage == CoderAiStage.Writing) out.add(CoderStageStep(writingLabel(writingTarget), done = false))
        return out
    }

    /**
     * The one muted line that replaces the card when the answer ended, or an empty text while it still runs.
     * [filesWritten] counts the finished code blocks that name a file, [otherBlocks] the finished blocks that do not.
     */
    fun summary(progress: CoderAiProgress, filesWritten: Int, otherBlocks: Int, elapsedMillis: Long): String {
        val seconds = secondsOf(elapsedMillis)
        return when (progress.stage) {
            CoderAiStage.Finished -> when {
                filesWritten == 1 -> "Wrote 1 file in $seconds s"
                filesWritten > 1 -> "Wrote $filesWritten files in $seconds s"
                otherBlocks > 0 -> "Wrote code in $seconds s"
                else -> "Finished in $seconds s"
            }
            CoderAiStage.Stopped -> "Stopped"
            CoderAiStage.CutShort -> "Cut short"
            CoderAiStage.Failed -> "Couldn't finish"
            CoderAiStage.Reading, CoderAiStage.Asking, CoderAiStage.Writing -> ""
        }
    }

    /** Whole seconds for a finished answer: rounded to the nearest second, never less than 1. */
    fun secondsOf(elapsedMillis: Long): Long = maxOf(1L, (elapsedMillis + 500L) / 1000L)

    /** The running counter while waiting for the first text, or null during the first second. */
    fun liveSecondsText(elapsedMillis: Long): String? {
        val seconds = elapsedMillis / 1000L
        return if (seconds >= 1L) "$seconds s" else null
    }

    private fun readingLabel(fileCount: Int): String =
        if (fileCount <= 0) "Reading your request" else "Reading your files ($fileCount)"

    private fun askingLabel(providerName: String, model: String): String = when {
        providerName.isBlank() -> "Asking the AI"
        model.isBlank() -> "Asking $providerName"
        else -> "Asking $providerName ($model)"
    }

    private fun writingLabel(target: String?): String = if (target.isNullOrBlank()) "Writing" else "Writing $target"
}

/**
 * Keeps screen updates to at most one every [intervalMs] milliseconds, whatever the speed of the stream: text that
 * arrives in between is simply part of the next update. Pure; the caller supplies the clock.
 */
class CoderPublishGate(private val intervalMs: Long = 50L) {
    private var lastPublishedAt: Long? = null

    /** True when there is something new ([dirty]) and the last update is at least [intervalMs] old (or there was none). */
    fun shouldPublish(nowMs: Long, dirty: Boolean): Boolean {
        if (!dirty) return false
        val last = lastPublishedAt ?: return true
        return nowMs - last >= intervalMs
    }

    /** Records that an update was published at [nowMs]. */
    fun markPublished(nowMs: Long) {
        lastPublishedAt = nowMs
    }
}
