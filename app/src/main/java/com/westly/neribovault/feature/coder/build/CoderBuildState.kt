package com.westly.neribovault.feature.coder.build

import com.westly.neribovault.feature.coder.files.CoderFileStore
import com.westly.neribovault.feature.coder.files.CoderReadResult
import com.westly.neribovault.feature.coder.files.CoderWriteResult
import com.westly.neribovault.feature.coder.preview.CoderPreviewRoot
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** One run of the build workflow on GitHub, as far as the wizard needs it. [status] is `queued`, `in_progress` or `completed`. */
data class ActionRun(
    val id: Long,
    val status: String,
    val conclusion: String?,
    val createdAtMillis: Long,
    val headSha: String,
)

/** One step of a job on GitHub, with the name GitHub shows for it. */
data class ActionStep(val name: String, val status: String, val conclusion: String?, val number: Int)

/** One job of a run. [steps] are in the order GitHub ran them. */
data class ActionJob(val id: Long, val name: String, val status: String, val conclusion: String?, val steps: List<ActionStep>)

/** One file a run made available for download. */
data class ActionArtifact(val id: Long, val name: String, val sizeBytes: Long, val expired: Boolean)

/** Where one stage of a build is. */
enum class BuildStageState { Waiting, Running, Done, Failed }

/** One line of the live list in the wizard. */
data class BuildStage(val label: String, val state: BuildStageState)

/** What the wizard remembers about the build that finished last: the run, when it finished downloading, the commit and the output folder. */
data class CoderBuildMeta(val runId: Long, val builtAtMillis: Long, val headSha: String, val outputDirName: String)

/** What the wizard remembers about a run in progress, so a new screen can pick it up again. [status] is `running`, `done`, `failed` or `cancelled`. */
data class CoderBuildStateFile(
    val runId: Long,
    val dispatchedAtMillis: Long,
    val status: String,
    val owner: String,
    val repo: String,
    val branch: String,
)

/**
 * The step state shown for a GitHub step: `completed` with `success`, `neutral` or `skipped` is done; `completed`
 * with anything else is failed; `in_progress` is running; everything else (queued, pending, waiting) is waiting.
 */
fun mapStepState(status: String, conclusion: String?): BuildStageState = when (status) {
    "completed" -> when (conclusion) {
        "success", "neutral", "skipped" -> BuildStageState.Done
        else -> BuildStageState.Failed
    }
    "in_progress" -> BuildStageState.Running
    else -> BuildStageState.Waiting
}

/**
 * Reads and writes the two small files the build keeps inside a project: `.coder-build/.meta` (the build that is
 * shown) and `.coder/build-state.json` (a run in progress). Both folders start with `.coder`, so they are hidden
 * from the file tree, ZIPs, pushes and AI. Every function does blocking disk work; call it from `Dispatchers.IO`.
 */
object CoderBuildFiles {
    /** The folder the finished site is kept in. */
    const val BUILD_DIR = CoderPreviewRoot.BUILT_FOLDER

    /** The file with the details of the finished site. */
    const val META_PATH = "$BUILD_DIR/.meta"

    /** The file with the run in progress. */
    const val STATE_PATH = ".coder/build-state.json"

    /** The text of a meta file. */
    fun metaJson(meta: CoderBuildMeta): String = JSONObject()
        .put("runId", meta.runId)
        .put("builtAtMillis", meta.builtAtMillis)
        .put("headSha", meta.headSha)
        .put("outputDirName", meta.outputDirName)
        .toString()

    /** The meta in [text], or null when it is not a meta file. */
    fun parseMeta(text: String): CoderBuildMeta? = try {
        val obj = JSONObject(text)
        CoderBuildMeta(obj.getLong("runId"), obj.getLong("builtAtMillis"), obj.getString("headSha"), obj.getString("outputDirName"))
    } catch (e: JSONException) {
        null
    }

    /** The text of a state file. */
    fun stateJson(state: CoderBuildStateFile): String = JSONObject()
        .put("runId", state.runId)
        .put("dispatchedAtMillis", state.dispatchedAtMillis)
        .put("status", state.status)
        .put("owner", state.owner)
        .put("repo", state.repo)
        .put("branch", state.branch)
        .toString()

    /** The state in [text], or null when it is not a state file. */
    fun parseState(text: String): CoderBuildStateFile? = try {
        val obj = JSONObject(text)
        CoderBuildStateFile(
            obj.getLong("runId"),
            obj.getLong("dispatchedAtMillis"),
            obj.getString("status"),
            obj.getString("owner"),
            obj.getString("repo"),
            obj.getString("branch"),
        )
    } catch (e: JSONException) {
        null
    }

    /** The meta of the build that is shown, or null when there is none or it is unreadable. */
    fun readMeta(store: CoderFileStore, projectId: String): CoderBuildMeta? =
        (store.read(projectId, META_PATH) as? CoderReadResult.Text)?.let { parseMeta(it.text) }

    /** The run in progress, or null when there is none or the file is unreadable. */
    fun readState(store: CoderFileStore, projectId: String): CoderBuildStateFile? =
        (store.read(projectId, STATE_PATH) as? CoderReadResult.Text)?.let { parseState(it.text) }

    /** Saves [state]. False when the file could not be written. */
    fun writeState(store: CoderFileStore, projectId: String, state: CoderBuildStateFile): Boolean =
        store.write(projectId, STATE_PATH, stateJson(state)) == CoderWriteResult.Saved

    /** Saves [meta]. False when the file could not be written. */
    fun writeMeta(store: CoderFileStore, projectId: String, meta: CoderBuildMeta): Boolean =
        store.write(projectId, META_PATH, metaJson(meta)) == CoderWriteResult.Saved
}

/**
 * Carries one prepared (never sent) AI message from the build wizard to the project screen, which is a different
 * destination with its own view models. It lives in memory only and holds a log tail that was cleaned of anything
 * that looks like a secret; [take] empties it.
 */
object CoderBuildAiHandoff {
    private val pending = ConcurrentHashMap<String, String>()

    /** Keeps [text] for [projectId], replacing an earlier one. */
    fun put(projectId: String, text: String) {
        pending[projectId] = text
    }

    /** The kept text for [projectId], removed, or null when there is none. */
    fun take(projectId: String): String? = pending.remove(projectId)
}
