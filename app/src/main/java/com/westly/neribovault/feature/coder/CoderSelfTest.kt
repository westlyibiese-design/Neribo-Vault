package com.westly.neribovault.feature.coder

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.WebResourceResponse
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.westly.neribovault.core.util.newId
import com.westly.neribovault.data.local.dao.CoderProjectDao
import com.westly.neribovault.data.local.entity.CoderProjectEntity
import com.westly.neribovault.data.repository.CoderProjectsRepository
import com.westly.neribovault.feature.coder.files.CoderProjectCopyResult
import com.westly.neribovault.feature.coder.files.CoderProjectSearch
import com.westly.neribovault.feature.coder.github.CoderGitHubRepos
import com.westly.neribovault.feature.coder.github.CoderLimitedCopyResult
import com.westly.neribovault.feature.coder.home.duplicateCoderProject
import com.westly.neribovault.feature.coder.home.duplicateProjectName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import com.westly.neribovault.data.local.entity.ProjectEntity
import com.westly.neribovault.feature.coder.ai.AiSelectionSnapshot
import com.westly.neribovault.feature.coder.ai.CODER_SYSTEM_PROMPT
import com.westly.neribovault.feature.coder.ai.ChatRole
import com.westly.neribovault.feature.coder.ai.CODER_AI_IDEAS
import com.westly.neribovault.feature.coder.ai.CoderAiApply
import com.westly.neribovault.feature.coder.ai.CoderAiClient
import com.westly.neribovault.feature.coder.ai.CoderApplyItem
import com.westly.neribovault.feature.coder.ai.CoderApplyLimits
import com.westly.neribovault.feature.coder.ai.CoderApplyPlan
import com.westly.neribovault.feature.coder.ai.CoderApplyResult
import com.westly.neribovault.feature.coder.ai.CoderPlanRow
import com.westly.neribovault.feature.coder.ai.CoderUndoResult
import com.westly.neribovault.feature.coder.ai.CoderAiProgress
import com.westly.neribovault.feature.coder.ai.CoderAiStage
import com.westly.neribovault.feature.coder.ai.CoderAiStages
import com.westly.neribovault.feature.coder.ai.CoderPublishGate
import com.westly.neribovault.feature.coder.ai.CoderSseParser
import com.westly.neribovault.feature.coder.ai.CoderStageEvent
import com.westly.neribovault.feature.coder.ai.CoderStreamDecoder
import com.westly.neribovault.feature.coder.ai.CoderStreamEvent
import com.westly.neribovault.feature.coder.ai.CoderStreamEvents
import com.westly.neribovault.feature.coder.ai.CoderStreamParser
import com.westly.neribovault.feature.coder.ai.SseEvent
import com.westly.neribovault.feature.coder.ai.CoderAiConfig
import com.westly.neribovault.feature.coder.ai.CoderAiProvider
import com.westly.neribovault.feature.coder.ai.CoderChatFailure
import com.westly.neribovault.feature.coder.ai.CoderChatMessage
import com.westly.neribovault.feature.coder.ai.CoderChatResult
import com.westly.neribovault.feature.coder.ai.CoderCodeBlock
import com.westly.neribovault.feature.coder.ai.CoderReplySegment
import com.westly.neribovault.feature.coder.ai.ContextFile
import com.westly.neribovault.feature.coder.ai.aiApplyButtonsFor
import com.westly.neribovault.feature.coder.ai.aiSelectionSnapshotOf
import com.westly.neribovault.feature.coder.ai.aiSnapshotApplies
import com.westly.neribovault.feature.coder.ai.buildHistory
import com.westly.neribovault.feature.coder.ai.buildProjectInfo
import com.westly.neribovault.feature.coder.ai.buildUserTurn
import com.westly.neribovault.feature.coder.ai.parseReply
import com.westly.neribovault.feature.coder.ai.CoderKeyCrypto
import com.westly.neribovault.feature.coder.ai.cleanKey
import com.westly.neribovault.feature.coder.ai.cleanModelName
import com.westly.neribovault.feature.coder.ai.describeFailure
import com.westly.neribovault.feature.coder.ai.parseModels
import com.westly.neribovault.feature.coder.editor.CoderHighlighter
import com.westly.neribovault.feature.coder.editor.CoderLanguage
import com.westly.neribovault.feature.coder.editor.CoderUndoStack
import com.westly.neribovault.feature.coder.editor.smart.CoderAutoClose
import com.westly.neribovault.feature.coder.editor.smart.CoderFind
import com.westly.neribovault.feature.coder.editor.smart.CoderIssue
import com.westly.neribovault.feature.coder.editor.smart.CoderLinter
import com.westly.neribovault.feature.coder.editor.smart.CoderSnippets
import com.westly.neribovault.feature.coder.editor.smart.CoderSuggestionEngine
import com.westly.neribovault.feature.coder.editor.smart.FindState
import com.westly.neribovault.feature.coder.editor.smart.IssueSeverity
import com.westly.neribovault.feature.coder.editor.smart.SMART_CSS_PROPERTIES
import com.westly.neribovault.feature.coder.editor.smart.SMART_HTML_TAGS
import com.westly.neribovault.feature.coder.editor.smart.Suggestion
import com.westly.neribovault.feature.coder.editor.smart.SuggestionKind
import com.westly.neribovault.feature.coder.editor.smart.SuggestionResult
import com.westly.neribovault.feature.coder.buildflow.ActionArtifact
import com.westly.neribovault.feature.coder.buildflow.ActionRun
import com.westly.neribovault.feature.coder.buildflow.BuildStageState
import com.westly.neribovault.feature.coder.buildflow.CoderActionsErrors
import com.westly.neribovault.feature.coder.buildflow.CoderActionsLogic
import com.westly.neribovault.feature.coder.buildflow.CoderActionsRequests
import com.westly.neribovault.feature.coder.buildflow.CoderBuildAiHandoff
import com.westly.neribovault.feature.coder.buildflow.CoderBuildErrorExplainer
import com.westly.neribovault.feature.coder.buildflow.CoderBuildFiles
import com.westly.neribovault.feature.coder.buildflow.CoderBuildMeta
import com.westly.neribovault.feature.coder.buildflow.CoderBuildRunner
import com.westly.neribovault.feature.coder.buildflow.CoderBuildStateFile
import com.westly.neribovault.feature.coder.buildflow.CoderBuildTools
import com.westly.neribovault.feature.coder.buildflow.CoderBuildWorkflow
import com.westly.neribovault.feature.coder.buildflow.CoderPackageAnalyzer
import com.westly.neribovault.feature.coder.buildflow.ExtractResult
import com.westly.neribovault.feature.coder.buildflow.mapStepState
import com.westly.neribovault.feature.coder.buildflow.scopesAllowWorkflow
import com.westly.neribovault.feature.coder.env.CoderEnvCache
import com.westly.neribovault.feature.coder.env.CoderEnvKeyCrypto
import com.westly.neribovault.feature.coder.env.CoderEnvRules
import com.westly.neribovault.feature.coder.env.CoderEnvStore
import com.westly.neribovault.feature.coder.env.EnvVar
import com.westly.neribovault.feature.coder.files.isCoderHiddenPath
import com.westly.neribovault.feature.coder.github.CoderGitHubResult
import com.westly.neribovault.feature.coder.files.CoderConflictChoice
import com.westly.neribovault.feature.coder.files.CoderCopyResult
import com.westly.neribovault.feature.coder.files.CoderFileCopy
import com.westly.neribovault.feature.coder.files.CoderFileStore
import com.westly.neribovault.feature.coder.files.CoderLimits
import com.westly.neribovault.feature.coder.files.CoderRecentFiles
import com.westly.neribovault.feature.coder.workspace.CoderFileKind
import com.westly.neribovault.feature.coder.workspace.coderFileKindOf
import com.westly.neribovault.feature.coder.workspace.coderProjectStats
import com.westly.neribovault.feature.coder.workspace.coderTemplateLabel
import com.westly.neribovault.feature.coder.files.CoderPaths
import com.westly.neribovault.feature.coder.files.CoderReadResult
import com.westly.neribovault.feature.coder.files.CoderWriteResult
import com.westly.neribovault.feature.coder.github.CoderGitHubErrorKind
import com.westly.neribovault.feature.coder.github.CoderGitHubErrors
import com.westly.neribovault.feature.coder.github.CoderGitHubKeyCrypto
import com.westly.neribovault.feature.coder.github.CoderGitHubRequests
import com.westly.neribovault.feature.coder.github.CoderGitSha
import com.westly.neribovault.feature.coder.github.CoderPushPlanner
import com.westly.neribovault.feature.coder.github.CoderSecretScanner
import com.westly.neribovault.feature.coder.github.LocalFile
import com.westly.neribovault.feature.coder.github.RemoteEntry
import com.westly.neribovault.feature.coder.guidance.CoderGlossary
import com.westly.neribovault.feature.coder.guidance.CoderGuides
import com.westly.neribovault.feature.coder.guidance.CoderTipEngine
import com.westly.neribovault.feature.coder.guidance.CoderTips
import com.westly.neribovault.feature.coder.guidance.TipContext
import com.westly.neribovault.feature.coder.guidance.TipEnd
import com.westly.neribovault.feature.coder.guidance.TipScreen
import com.westly.neribovault.feature.coder.guidance.TipState
import com.westly.neribovault.feature.coder.github.TreeEntryInput
import com.westly.neribovault.feature.coder.github.cleanGitHubToken
import com.westly.neribovault.feature.coder.github.isValidRepoName
import com.westly.neribovault.feature.coder.github.parseRepoRef
import com.westly.neribovault.feature.coder.link.CoderLinkState
import com.westly.neribovault.feature.coder.link.linkStateOf
import com.westly.neribovault.feature.coder.preview.CoderBuildCheck
import com.westly.neribovault.feature.coder.preview.CoderBuildDetector
import com.westly.neribovault.feature.coder.preview.CoderCdnShim
import com.westly.neribovault.feature.coder.preview.CoderConsole
import com.westly.neribovault.feature.coder.preview.CoderErrorExplainer
import com.westly.neribovault.feature.coder.preview.CoderMarkdown
import com.westly.neribovault.feature.coder.preview.CoderMime
import com.westly.neribovault.feature.coder.preview.CoderPreviewHost
import com.westly.neribovault.feature.coder.preview.CoderPreviewResponder
import com.westly.neribovault.feature.coder.preview.CoderPreviewRoot
import com.westly.neribovault.feature.coder.preview.CoderVendorAsset
import com.westly.neribovault.feature.coder.preview.ConsoleLevel
import com.westly.neribovault.feature.coder.templates.CoderTemplates
import com.westly.neribovault.feature.coder.ui.CoderSyntaxColors
import com.westly.neribovault.feature.coder.workspace.CODER_MAX_TABS
import com.westly.neribovault.feature.coder.workspace.CODER_TABS_FULL_REASON
import com.westly.neribovault.feature.coder.workspace.CoderSaveStatus
import com.westly.neribovault.feature.coder.workspace.CoderTabLogic
import com.westly.neribovault.feature.coder.workspace.CoderTabs
import com.westly.neribovault.feature.coder.workspace.OpenOutcome
import com.westly.neribovault.feature.coder.workspace.aggregateSaveStatus
import com.westly.neribovault.feature.coder.zip.CoderExportResult
import com.westly.neribovault.feature.coder.zip.CoderImportReport
import com.westly.neribovault.feature.coder.zip.CoderImportResult
import com.westly.neribovault.feature.coder.zip.CoderZipExporter
import com.westly.neribovault.feature.coder.zip.CoderZipRules
import com.westly.neribovault.feature.coder.zip.SkipReason
import com.westly.neribovault.feature.coder.zip.ZIP_UNSAFE_NAMES_MESSAGE
import com.westly.neribovault.feature.coder.zip.importStream
import com.westly.neribovault.feature.coder.zip.writeProjectZip
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import com.westly.neribovault.feature.coder.files.CoderEntry
import com.westly.neribovault.feature.coder.preview.CoderAccess
import com.westly.neribovault.feature.coder.preview.CoderHttpRequest
import com.westly.neribovault.feature.coder.preview.CoderHttpResponse
import com.westly.neribovault.feature.coder.preview.CoderLocalServer
import com.westly.neribovault.feature.coder.preview.CoderParsedRequest
import com.westly.neribovault.feature.coder.preview.CoderServedPaths
import com.westly.neribovault.feature.coder.preview.CoderServerInfo
import com.westly.neribovault.feature.coder.preview.CoderConsoleMessage
import com.westly.neribovault.feature.coder.preview.ConsoleFilter
import com.westly.neribovault.feature.coder.preview.MAX_PICKER_PAGES
import com.westly.neribovault.feature.coder.preview.ParseResult
import com.westly.neribovault.feature.coder.preview.browserUrlFor
import com.westly.neribovault.feature.coder.preview.clampRatio
import com.westly.neribovault.feature.coder.preview.consoleCopyText
import com.westly.neribovault.feature.coder.preview.filterConsoleMessages
import com.westly.neribovault.feature.coder.preview.htmlPagesOf
import com.westly.neribovault.feature.coder.preview.shouldAutoRefresh
import java.net.Socket
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.net.ssl.SSLException

/** The outcome of one Coder check. [detail] says what was expected when [passed] is false. */
data class CoderCheckResult(val name: String, val passed: Boolean, val detail: String)

private fun check(name: String, expectation: String, actual: (() -> String)? = null, body: () -> Boolean): CoderCheckResult =
    try {
        val ok = body()
        CoderCheckResult(name, ok, if (ok) "ok" else failureDetail(expectation, actual))
    } catch (e: Exception) {
        CoderCheckResult(name, false, "threw " + e.javaClass.simpleName + ": " + (e.message ?: "no message"))
    }

/** The detail of a failed check: what was expected and, when the check can say, what really happened. */
private fun failureDetail(expectation: String, actual: (() -> String)?): String {
    if (actual == null) return "expected: $expectation"
    val got = try {
        actual()
    } catch (e: Exception) {
        "could not be read (" + e.javaClass.simpleName + ")"
    }
    return "expected: $expectation; got: $got"
}

private fun fileBytes(store: CoderFileStore, id: String, path: String): ByteArray? =
    store.resolve(id, path)?.readBytes()

/**
 * Checks the Coder engine that nobody can unit-test on this project: path rules, the file store,
 * the highlighter, the language map and the undo stack. Works in a temporary folder inside the app
 * cache and removes it at the end. Needs no PIN and no database.
 */
fun runCoderSelfTest(context: Context): List<CoderCheckResult> {
    val root = File(context.cacheDir, "coder-selftest-" + newId())
    val results = ArrayList<CoderCheckResult>()
    try {
        val store = CoderFileStore(root)
        results.addAll(pathChecks())
        results.addAll(resolveChecks(store))
        results.addAll(fileChecks(store))
        results.addAll(languageChecks())
        results.addAll(highlighterChecks())
        results.addAll(undoChecks())
        results.addAll(previewChecks(context, store))
        results.addAll(markdownChecks())
        results.addAll(consoleChecks())
        results.addAll(templateChecks())
        results.addAll(zipChecks(context, store, root))
        results.addAll(shimChecks())
        results.addAll(vendorBundleChecks(context))
        results.addAll(buildDetectorChecks(store))
        results.addAll(explainerChecks())
        results.addAll(reactTemplateChecks())
        results.addAll(aiProviderChecks())
        results.addAll(aiKeyChecks())
        results.addAll(aiModelParsingChecks())
        results.addAll(aiFailureTextChecks())
        results.addAll(aiCryptoChecks())
        results.addAll(aiChatChecks())
        results.addAll(githubChecks(store))
        results.addAll(developerLinkChecks())
        results.addAll(selectionSnapshotChecks())
        results.addAll(applyButtonChecks())
        results.addAll(linkStateChecks())
        results.addAll(smartSuggestionChecks())
        results.addAll(smartAutoCloseChecks())
        results.addAll(smartDictionaryChecks())
        results.addAll(smartLintChecks())
        results.addAll(smartFindChecks())
        results.addAll(tabLogicChecks())
        results.addAll(tipEngineChecks())
        results.addAll(guidanceDataChecks())
        results.addAll(previewUpgradeChecks(context, store))
        results.addAll(buildAndEnvChecks(context, store))
        results.addAll(c15aChecks(store))
        results.addAll(c15bChecks(store))
        results.addAll(c16aChecks())
        results.addAll(c16bChecks(store))
    } finally {
        root.deleteRecursively()
    }
    return results
}

private fun pathChecks(): List<CoderCheckResult> {
    val out = ArrayList<CoderCheckResult>()
    for (path in listOf("a.txt", "src/app.js", ".gitignore")) {
        out.add(check("Path accepted: $path", "valid") { CoderPaths.isValidPath(path) })
    }
    val deep = (1..13).joinToString("/") { "d$it" }
    val rejected = listOf(
        "" to "empty", "/a" to "/a", "a/" to "a/", "../a" to "../a", "a/../b" to "a/../b",
        "a//b" to "a//b", "a\\b" to "a backslash b", "a:b" to "a:b", "a".repeat(201) to "201 characters",
        deep to "13 levels deep",
    )
    for ((path, label) in rejected) {
        out.add(check("Path refused: $label", "invalid") { !CoderPaths.isValidPath(path) })
    }
    return out
}

private fun resolveChecks(store: CoderFileStore): List<CoderCheckResult> {
    val id = newId()
    return listOf(
        check("resolve refuses ../x", "null") { store.resolve(id, "../x") == null },
        check("resolve refuses a/../../x", "null") { store.resolve(id, "a/../../x") == null },
        check("resolve refuses an absolute path", "null") { store.resolve(id, "/etc/passwd") == null },
        check("resolve refuses a bad project id", "null") {
            store.resolve("../other", "a.txt") == null && store.resolve("not an id!", "a.txt") == null
        },
        check("resolve of empty path is the project folder", "project folder") {
            store.resolve(id, "") == store.projectDir(id)
        },
    )
}

private fun fileChecks(store: CoderFileStore): List<CoderCheckResult> {
    val out = ArrayList<CoderCheckResult>()
    out.add(check("Unicode text round trip", "same text back") {
        val id = newId()
        val text = "h\u00E9llo \u2713"
        val read = store.write(id, "u.txt", text) == CoderWriteResult.Saved && store.read(id, "u.txt").let {
            it is CoderReadResult.Text && it.text == text && !it.usesCrlf
        }
        read
    })
    out.add(check("CRLF file reads as LF with usesCrlf", "LF text and usesCrlf") {
        val id = newId()
        store.resolve(id, "")?.mkdirs()
        store.resolve(id, "c.txt")?.writeBytes("a\r\nb\r\n".toByteArray(Charsets.UTF_8))
        store.read(id, "c.txt").let { it is CoderReadResult.Text && it.text == "a\nb\n" && it.usesCrlf }
    })
    out.add(check("Text with crlf writes back as CRLF", "CRLF bytes") {
        val id = newId()
        store.write(id, "c.txt", "a\nb\n", crlf = true) == CoderWriteResult.Saved &&
            fileBytes(store, id, "c.txt")?.toString(Charsets.UTF_8) == "a\r\nb\r\n"
    })
    out.add(check("BOM is stripped on read", "text without BOM") {
        val id = newId()
        store.resolve(id, "")?.mkdirs()
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        store.resolve(id, "b.txt")?.writeBytes(bom + "hi".toByteArray(Charsets.UTF_8))
        store.read(id, "b.txt").let { it is CoderReadResult.Text && it.text == "hi" }
    })
    out.add(check("NUL byte file is Binary", "Binary") {
        val id = newId()
        store.resolve(id, "")?.mkdirs()
        store.resolve(id, "n.bin")?.writeBytes(byteArrayOf(65, 0, 66))
        store.read(id, "n.bin") == CoderReadResult.Binary
    })
    out.add(check("Invalid UTF-8 file is Binary", "Binary") {
        val id = newId()
        store.resolve(id, "")?.mkdirs()
        store.resolve(id, "bad.txt")?.writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        store.read(id, "bad.txt") == CoderReadResult.Binary
    })
    out.add(check("300,001 bytes is TooLarge", "TooLarge on read and write") {
        val id = newId()
        store.resolve(id, "")?.mkdirs()
        store.resolve(id, "big.txt")?.writeBytes(ByteArray(300_001) { 97 })
        store.read(id, "big.txt") == CoderReadResult.TooLarge &&
            store.write(id, "big2.txt", "a".repeat(300_001)) == CoderWriteResult.TooLarge &&
            store.resolve(id, "big2.txt")?.exists() == false
    })
    out.add(check("createFile twice gives AlreadyExists", "second call AlreadyExists") {
        val id = newId()
        store.createFile(id, "x.txt") == CoderWriteResult.Saved &&
            store.createFile(id, "x.txt") == CoderWriteResult.AlreadyExists
    })
    out.add(check("Folder cannot be renamed into itself", "not Saved, folder still there") {
        val id = newId()
        store.createFolder(id, "a")
        store.createFile(id, "a/f.txt", "x")
        store.rename(id, "a", "a/b") != CoderWriteResult.Saved && store.resolve(id, "a/f.txt")?.exists() == true
    })
    out.add(check("rename to an existing name gives AlreadyExists", "AlreadyExists") {
        val id = newId()
        store.createFile(id, "x.txt")
        store.createFile(id, "y.txt")
        store.rename(id, "x.txt", "y.txt") == CoderWriteResult.AlreadyExists
    })
    out.add(check("delete of the project root is refused", "false and folder kept") {
        val id = newId()
        store.createFile(id, "x.txt")
        !store.delete(id, "") && store.projectDir(id).exists()
    })
    out.add(check("listAll puts folders first, then files by name", "folders first, case-insensitive") {
        val id = newId()
        store.createFile(id, "b.txt")
        store.createFile(id, "A.txt")
        store.createFile(id, "z/inner.txt")
        store.createFile(id, "Y/inner.txt")
        store.createFolder(id, "m")
        val paths = store.listAll(id).map { it.path }
        paths == listOf("m", "Y", "Y/inner.txt", "z", "z/inner.txt", "A.txt", "b.txt")
    })
    out.add(check("Atomic write leaves no temp file", "no .tmp- files") {
        val id = newId()
        for (i in 1..5) store.write(id, "sub/t.txt", "version $i")
        store.projectDir(id).walkTopDown().none { it.name.contains(".tmp-") } &&
            store.read(id, "sub/t.txt").let { it is CoderReadResult.Text && it.text == "version 5" }
    })
    return out
}

private fun languageChecks(): List<CoderCheckResult> = listOf(
    check("Language by extension (10 files)", "all ten match") {
        CoderLanguage.fromPath("index.html") == CoderLanguage.Html &&
            CoderLanguage.fromPath("src/style.css") == CoderLanguage.Css &&
            CoderLanguage.fromPath("app.js") == CoderLanguage.JavaScript &&
            CoderLanguage.fromPath("app.ts") == CoderLanguage.TypeScript &&
            CoderLanguage.fromPath("data.json") == CoderLanguage.Json &&
            CoderLanguage.fromPath("README.md") == CoderLanguage.Markdown &&
            CoderLanguage.fromPath("main.py") == CoderLanguage.Python &&
            CoderLanguage.fromPath("Main.kt") == CoderLanguage.Kotlin &&
            CoderLanguage.fromPath("run.sh") == CoderLanguage.Shell &&
            CoderLanguage.fromPath("notes.xyz") == CoderLanguage.Plain &&
            CoderLanguage.fromPath("INDEX.HTML") == CoderLanguage.Html
    },
)

private fun highlighterChecks(): List<CoderCheckResult> {
    val colors = CoderSyntaxColors(
        keyword = Color.Red, string = Color.Green, number = Color.Blue, comment = Color.Gray,
        tag = Color.Cyan, attribute = Color.Magenta, plain = Color.Black,
    )
    fun same(text: String, language: CoderLanguage): Boolean =
        CoderHighlighter.highlight(text, language, colors).text == text
    return listOf(
        check("Highlighter keeps text with an unterminated string", "same text") {
            same("var s = \"abc\nnext line", CoderLanguage.JavaScript) && same("<a href=\"x", CoderLanguage.Html)
        },
        check("Highlighter keeps text with an unterminated comment", "same text") {
            same("a /* b", CoderLanguage.Css) && same("<!-- open", CoderLanguage.Html)
        },
        check("Highlighter keeps empty text", "same text") { same("", CoderLanguage.Kotlin) },
        check("Highlighter skips 150,000 characters", "same text and no spans") {
            val big = "x".repeat(150_000)
            val result = CoderHighlighter.highlight(big, CoderLanguage.JavaScript, colors)
            result.text == big && result.spanStyles.isEmpty()
        },
    )
}

private fun undoChecks(): List<CoderCheckResult> = listOf(
    check("Undo stack order", "undo and redo in order") {
        val stack = CoderUndoStack()
        stack.push(TextFieldValue("A"))
        stack.push(TextFieldValue("B"))
        val b = stack.undo(TextFieldValue("C"))
        val a = stack.undo(TextFieldValue("B"))
        val none = stack.undo(TextFieldValue("A"))
        val redoB = stack.redo(TextFieldValue("A"))
        b?.text == "B" && a?.text == "A" && none == null && redoB?.text == "B" && stack.canUndo && stack.canRedo
    },
    check("Undo stack keeps 200 steps", "200 steps") {
        val stack = CoderUndoStack()
        for (i in 1..250) stack.push(TextFieldValue("v$i"))
        var steps = 0
        var current = TextFieldValue("now")
        while (true) {
            val previous = stack.undo(current) ?: break
            current = previous
            steps++
        }
        steps == 200
    },
)

private fun previewChecks(context: Context, store: CoderFileStore): List<CoderCheckResult> {
    val out = ArrayList<CoderCheckResult>()
    val id = newId()
    val host = CoderPreviewHost.hostFor(id)
    store.write(id, "index.html", "<p>home</p>")
    store.write(id, "a/index.html", "<p>a</p>")
    store.write(id, "a/b.css", "body{}")
    store.write(id, "a b.js", "1")
    store.write(id, "__coder/secret.txt", "secret")
    store.write(id, "README.md", "# Title\n\n**bold**")
    store.write(id, "data.json", "{\"a\":1,\"u\":\"x/y\"}")
    store.write(id, "bad.json", "{\"a\":")
    val responder = CoderPreviewResponder(context, store, id, "")
    fun get(path: String, method: String = "GET"): WebResourceResponse? =
        responder.respondTo(Uri.parse("https://$host$path"), method)
    fun body(response: WebResourceResponse?): String =
        response?.data?.readBytes()?.toString(Charsets.UTF_8) ?: ""

    val mimes = listOf(
        "a.html" to "text/html", "a.css" to "text/css", "a.js" to "text/javascript", "a.json" to "application/json",
        "a.svg" to "image/svg+xml", "a.PNG" to "image/png", "a.jpg" to "image/jpeg", "a.woff2" to "font/woff2",
        "a.mp4" to "video/mp4", "a.md" to "text/plain", "a.wasm" to "application/wasm", "a.xyz" to "application/octet-stream",
    )
    for ((path, mime) in mimes) {
        out.add(check("Content type of $path", mime) { CoderMime.forPath(path) == mime })
    }
    out.add(check("Text types are recognised", "text and json yes, png no") {
        CoderMime.isTextMime("text/css") && CoderMime.isTextMime("application/json") && !CoderMime.isTextMime("image/png")
    })
    out.add(check("Preview host is lower case, dash free and short", "valid host") {
        val upper = CoderPreviewHost.hostFor("ABCDEF12-3456-7890-ABCD-EF1234567890")
        upper == upper.lowercase() && !upper.contains("-") && upper.endsWith(".coder.invalid") &&
            upper.substringBefore('.').length < 63
    })
    out.add(check("Preview address encodes each part", "spaces become %20, slashes stay") {
        CoderPreviewHost.urlFor(id, "a b/c d.js") == "https://$host/a%20b/c%20d.js"
    })
    out.add(check("Preview root needs an index.html", "empty string, then null") {
        CoderPreviewRoot.find(store, id) == "" && CoderPreviewRoot.find(store, newId()) == null
    })
    val expectedStatus = listOf(
        "/" to 200, "/a/" to 200, "/a" to 200, "/a/b.css" to 200, "/a%20b.js" to 200,
        "/%20x.js" to 404, "/../x" to 404, "/%2e%2e/x" to 404, "/nothing.txt" to 404,
        "/__coder/secret.txt" to 404, "/__coder/x" to 404, "/__coder" to 404,
        "/__coder/md/README.md" to 200, "/__coder/json/data.json" to 200, "/__coder/md/missing.md" to 404,
    )
    for ((path, status) in expectedStatus) {
        out.add(check("Preview answers $path with $status", status.toString()) { get(path)?.statusCode == status })
    }
    out.add(check("Preview sends the right type for a stylesheet", "text/css") { get("/a/b.css")?.mimeType == "text/css" })
    out.add(check("Preview refuses POST", "405") { get("/", "POST")?.statusCode == 405 })
    out.add(check("Preview answers HEAD without a body", "200 and empty") {
        val r = get("/", "HEAD")
        r?.statusCode == 200 && body(r).isEmpty()
    })
    out.add(check("Preview blocks http", "403") {
        responder.respondTo(Uri.parse("http://$host/"), "GET")?.statusCode == 403
    })
    out.add(check("Preview blocks other schemes", "403") {
        responder.respondTo(Uri.parse("ftp://$host/"), "GET")?.statusCode == 403
    })
    out.add(check("Preview lets other https hosts through", "null") {
        responder.respondTo(Uri.parse("https://example.com/"), "GET") == null
    })
    out.add(check("Markdown page is built from the file", "contains a heading and bold text") {
        val html = body(get("/__coder/md/README.md"))
        html.contains("<h1>Title</h1>") && html.contains("<strong>bold</strong>")
    })
    out.add(check("JSON page is pretty printed and escaped", "quoted keys, readable slash") {
        val html = body(get("/__coder/json/data.json"))
        html.contains("&quot;a&quot;: 1") && html.contains("x/y") && !html.contains("\\/")
    })
    out.add(check("Broken JSON shows the raw text and a message", "message plus raw text") {
        val html = body(get("/__coder/json/bad.json"))
        html.contains("valid JSON") && html.contains("&quot;a&quot;")
    })
    return out
}

private fun markdownChecks(): List<CoderCheckResult> {
    fun html(md: String): String = CoderMarkdown.toHtml(md)
    return listOf(
        check("Markdown escapes raw HTML", "text, not a tag") {
            val h = html("<script>alert(1)</script>")
            h.contains("&lt;script&gt;") && !h.contains("<script")
        },
        check("Markdown refuses javascript: links", "no link") {
            !html("[x](javascript:alert(1))").contains("href")
        },
        check("Markdown refuses hidden-space javascript: links", "no link") {
            !html("[x](java\tscript:alert(1))").contains("href") && !html("[x](JaVaScRiPt:alert(1))").contains("href")
        },
        check("Markdown refuses data: images", "no image") { !html("![x](data:text/html,hi)").contains("<img") },
        check("Markdown keeps web links", "a link") {
            html("[x](https://example.com/a?b=1&c=2)").contains("<a href=\"https://example.com/a?b=1&amp;c=2\">x</a>")
        },
        check("Markdown bold, italic and code", "strong, em, code") {
            val h = html("**b** *i* _j_ `c`")
            h.contains("<strong>b</strong>") && h.contains("<em>i</em>") && h.contains("<em>j</em>") && h.contains("<code>c</code>")
        },
        check("Markdown lists and quotes", "ul, ol, blockquote") {
            val h = html("- a\n- b\n\n1. c\n2. d\n\n> q")
            h.contains("<ul>") && h.contains("<ol>") && h.contains("<blockquote>")
        },
        check("Markdown fenced code is escaped", "pre with escaped text") {
            html("```\n<b>x</b>\n```").contains("<pre><code>&lt;b&gt;x&lt;/b&gt;</code></pre>")
        },
        check("Markdown shows unfinished markup literally", "no crash, text kept") {
            val h = html("```\ncode\n**bold *x _y `z [a](b")
            h.contains("code") && h.contains("**bold")
        },
        check("Markdown handles 200,000 characters of odd input", "finishes in time") {
            val start = System.nanoTime()
            val inputs = listOf(
                "a *b* **c** _d_ `e` [f](https://x.y) ".repeat(5_500),
                "[".repeat(200_000),
                "* ".repeat(100_000),
                "_ ".repeat(100_000),
                "**a ".repeat(50_000),
                "`".repeat(200_000),
            )
            for (input in inputs) html(input)
            (System.nanoTime() - start) / 1_000_000 < 10_000
        },
    )
}

private fun consoleChecks(): List<CoderCheckResult> {
    val host = CoderPreviewHost.hostFor(newId())
    return listOf(
        check("Console keeps the last 200 messages", "200 kept, errors counted, clear resets") {
            val console = CoderConsole(host, "")
            repeat(250) { console.add(ConsoleLevel.Log, "m$it", null, 0) }
            console.add(ConsoleLevel.Error, "boom", null, 0)
            val kept = console.messages.size == 200 && console.messages.last().text == "boom" && console.errorCount == 1
            console.clear()
            kept && console.messages.isEmpty() && console.errorCount == 0
        },
        check("Console turns page addresses into project files", "relative file or null") {
            val console = CoderConsole(host, "dist")
            console.relativeSource("https://$host/dist/app.js?x=1#y") == "app.js" &&
                console.relativeSource("https://$host/") == "index.html" &&
                console.relativeSource("https://example.com/app.js") == null &&
                console.relativeSource(null) == null
        },
    )
}

private fun templateChecks(): List<CoderCheckResult> {
    val all = CoderTemplates.all
    return listOf(
        check("There are seven templates with the agreed ids", "blank, hello-web, counter, todo, notes-md, react-hello, react-counter") {
            all.map { it.id } == listOf("blank", "hello-web", "counter", "todo", "notes-md", "react-hello", "react-counter")
        },
        check("Every template file has a valid path, fits the limit and works offline", "valid, under 3 KB, only the bundled React scripts are remote") {
            all.all { template ->
                template.files.isNotEmpty() && template.files.all { file ->
                    var rest = file.content
                    for (url in REACT_SCRIPT_URLS) rest = rest.replace("src=\"" + url + "\"", "")
                    CoderPaths.isValidPath(file.path) &&
                        file.content.toByteArray(Charsets.UTF_8).size <= 3_072 &&
                        !rest.contains("src=\"http") &&
                        !rest.contains("stylesheet\" href=\"http")
                }
            }
        },
        check("Every template can be found by id", "found") { all.all { CoderTemplates.find(it.id) == it } },
    )
}

private fun zipChecks(context: Context, store: CoderFileStore, root: File): List<CoderCheckResult> {
    val out = ArrayList<CoderCheckResult>()
    out.addAll(zipNameChecks())
    out.addAll(zipRuleChecks())
    out.addAll(zipRoundTripChecks(context, store, root))
    out.add(zipTraversalCheck(store, root))
    out.addAll(zipFolderChecks(store))
    out.addAll(previewRootChecks(store))
    return out
}

private fun zipNameChecks(): List<CoderCheckResult> {
    val out = ArrayList<CoderCheckResult>()
    val names = listOf(
        "a/b.txt" to "a/b.txt", "a\\b.txt" to "a/b.txt", "./a.txt" to "a.txt", "dir/" to "dir",
        "../evil" to null, "../evil.txt" to null, "a/../../evil" to null, "/abs" to null, "C:\\x" to null, "" to null,
    )
    for ((raw, expected) in names) {
        out.add(
            check(
                "ZIP name \"$raw\" becomes ${expected ?: "refused"}",
                expected ?: "null",
                actual = { CoderZipRules.normalizeEntryName(raw).toString() },
            ) { CoderZipRules.normalizeEntryName(raw) == expected },
        )
    }
    val skipped = listOf(
        "node_modules/x/y.js", "src/node_modules/a", ".git/config", "__MACOSX/._a", "a/.DS_Store", "docs/Thumbs.db",
    )
    for (path in skipped) {
        out.add(check("ZIP skips $path", "a skip reason", actual = { CoderZipRules.shouldSkip(path).toString() }) {
            CoderZipRules.shouldSkip(path) != null
        })
    }
    out.add(
        check(
            "ZIP keeps src/app.js and .gitignore",
            "no skip reason",
            actual = { CoderZipRules.shouldSkip("src/app.js").toString() + ", " + CoderZipRules.shouldSkip(".gitignore").toString() },
        ) { CoderZipRules.shouldSkip("src/app.js") == null && CoderZipRules.shouldSkip(".gitignore") == null },
    )
    return out
}

private fun zipRuleChecks(): List<CoderCheckResult> = listOf(
    check(
        "Outer folder is found",
        "repo-main",
        actual = { CoderZipRules.stripWrapperFolder(listOf("repo-main/a", "repo-main/b/c")).toString() },
    ) {
        CoderZipRules.stripWrapperFolder(listOf("repo-main/a", "repo-main/b/c")) == "repo-main"
    },
    check(
        "Mixed top folders have no outer folder",
        "null",
        actual = {
            CoderZipRules.stripWrapperFolder(listOf("a/x", "b/y")).toString() + ", " +
                CoderZipRules.stripWrapperFolder(listOf("repo/a.txt", "b.txt")).toString()
        },
    ) {
        CoderZipRules.stripWrapperFolder(listOf("a/x", "b/y")) == null &&
            CoderZipRules.stripWrapperFolder(listOf("repo/a.txt", "b.txt")) == null
    },
    check(
        "A lone top-level file has no outer folder",
        "null",
        actual = { CoderZipRules.stripWrapperFolder(listOf("a.txt")).toString() },
    ) {
        CoderZipRules.stripWrapperFolder(listOf("a.txt")) == null
    },
    check(
        "Project name comes from the file name",
        "My App, then Imported project",
        actual = {
            listOf(
                CoderZipRules.projectNameFromFile("My App.ZIP"),
                CoderZipRules.projectNameFromFile(null),
                CoderZipRules.projectNameFromFile(".zip"),
                CoderZipRules.projectNameFromFile("x".repeat(80) + ".zip").length.toString(),
            ).joinToString(" | ")
        },
    ) {
        CoderZipRules.projectNameFromFile("My App.ZIP") == "My App" &&
            CoderZipRules.projectNameFromFile(null) == "Imported project" &&
            CoderZipRules.projectNameFromFile(".zip") == "Imported project" &&
            CoderZipRules.projectNameFromFile("x".repeat(80) + ".zip").length == 60
    },
    check(
        "Same name gets (2), then (3)",
        "Name (2), Name (3)",
        actual = {
            listOf(
                CoderZipRules.uniqueProjectName("Name", listOf("Other")),
                CoderZipRules.uniqueProjectName("Name", listOf("Name")),
                CoderZipRules.uniqueProjectName("Name", listOf("Name", "name (2)")),
                CoderZipRules.uniqueProjectName("y".repeat(60), listOf("y".repeat(60))).length.toString(),
            ).joinToString(" | ")
        },
    ) {
        CoderZipRules.uniqueProjectName("Name", listOf("Other")) == "Name" &&
            CoderZipRules.uniqueProjectName("Name", listOf("Name")) == "Name (2)" &&
            CoderZipRules.uniqueProjectName("Name", listOf("Name", "name (2)")) == "Name (3)" &&
            CoderZipRules.uniqueProjectName("y".repeat(60), listOf("y".repeat(60))).length <= 60
    },
    check(
        "Zip bomb ratio rule",
        "above 200 times is refused; unknown sizes are not",
        actual = {
            listOf(
                CoderZipRules.isSuspiciousRatio(201_000L, 1_000L),
                CoderZipRules.isSuspiciousRatio(200_000L, 1_000L),
                CoderZipRules.isSuspiciousRatio(500_000L, -1L),
                CoderZipRules.isSuspiciousRatio(500_000L, 0L),
            ).toString()
        },
    ) {
        CoderZipRules.isSuspiciousRatio(201_000L, 1_000L) &&
            !CoderZipRules.isSuspiciousRatio(200_000L, 1_000L) &&
            !CoderZipRules.isSuspiciousRatio(500_000L, -1L) &&
            !CoderZipRules.isSuspiciousRatio(500_000L, 0L)
    },
)

/**
 * The test site: an outer folder, three good files, a node_modules file, a name Coder refuses (`bad:name.txt`, which
 * Android's ZIP reader accepts) and two names that differ only by case. Names with ".." are tested separately,
 * because Android 14 and later refuse them while reading.
 */
private fun buildTestZip(): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        fun add(name: String, data: ByteArray) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(data)
            zip.closeEntry()
        }
        zip.putNextEntry(ZipEntry("repo-main/"))
        zip.closeEntry()
        add("repo-main/index.html", "<h1>Hi \u00E9</h1>".toByteArray(Charsets.UTF_8))
        add("repo-main/css/style.css", "body{}".toByteArray(Charsets.UTF_8))
        add("repo-main/img/logo.png", byteArrayOf(-119, 80, 78, 71, 0, 1, 2, 3))
        add("repo-main/node_modules/x/y.js", "1".toByteArray(Charsets.UTF_8))
        add("bad:name.txt", "bad".toByteArray(Charsets.UTF_8))
        add("repo-main/A.txt", "first".toByteArray(Charsets.UTF_8))
        add("repo-main/a.txt", "second".toByteArray(Charsets.UTF_8))
    }
    return bytes.toByteArray()
}

/** A ZIP made in memory. A name that ends in "/" becomes a folder entry; any other name becomes a file holding the given text (an empty text gives a zero-byte entry). */
private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        for ((name, text) in entries) {
            zip.putNextEntry(ZipEntry(name))
            if (!name.endsWith("/")) zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
    return bytes.toByteArray()
}

private fun zipEntryNames(bytes: ByteArray): Set<String> {
    val names = HashSet<String>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            names.add(entry.name)
        }
    }
    return names
}

/** Runs [bytes] through the importer without a database. An exception becomes a Failure that names its type. */
private fun importBytes(store: CoderFileStore, bytes: ByteArray, suggestedName: String = "Test Site.zip"): CoderImportResult {
    val keepAll: suspend (String, String) -> Boolean = { _, _ -> true }
    return try {
        runBlocking { importStream(store, { ByteArrayInputStream(bytes) }, suggestedName, keepAll) }
    } catch (e: Exception) {
        CoderImportResult.Failure("threw " + e.javaClass.simpleName)
    }
}

/** What an import did, in one line, for a check's detail. */
private fun describeImport(result: CoderImportResult): String = when (result) {
    is CoderImportResult.Success ->
        "Success (" + result.report.importedFiles + " added, " + result.report.skippedFiles + " skipped, outer folder " +
            result.report.strippedWrapperFolder + ", notes " + result.report.skippedReasons + ")"
    is CoderImportResult.Failure -> "Failure: " + result.message
}

/** The paths of the project made by [result], or "no project" when the import failed. */
private fun importedPaths(store: CoderFileStore, result: CoderImportResult): String =
    if (result is CoderImportResult.Success) store.listAll(result.projectId).joinToString(", ") { it.path } else "no project"

private fun zipRoundTripChecks(context: Context, store: CoderFileStore, root: File): List<CoderCheckResult> {
    val out = ArrayList<CoderCheckResult>()
    val zipBytes = buildTestZip()
    val keepAll: suspend (String, String) -> Boolean = { _, _ -> true }
    val first = importBytes(store, zipBytes)
    val success = first as? CoderImportResult.Success
    val id = success?.projectId ?: ""
    val report = success?.report
    out.add(check("ZIP import of the test site succeeds", "Success", actual = { "got " + describeImport(first) }) { success != null })
    out.add(
        check(
            "ZIP import keeps the right files and folders",
            "css, img and three files",
            actual = { "files: " + importedPaths(store, first) },
        ) {
            store.listAll(id).map { it.path } == listOf("css", "css/style.css", "img", "img/logo.png", "A.txt", "index.html")
        },
    )
    out.add(
        check("ZIP import removes the outer folder", "repo-main", actual = { "outer folder: " + report?.strippedWrapperFolder }) {
            report?.strippedWrapperFolder == "repo-main"
        },
    )
    out.add(
        check(
            "ZIP import counts files",
            "4 added, 3 skipped",
            actual = { "imported " + report?.importedFiles + ", skipped " + report?.skippedFiles },
        ) { report != null && report.importedFiles == 4 && report.skippedFiles == 3 },
    )
    out.add(
        check(
            "ZIP import reports what it skipped",
            "node_modules and unsafe or duplicate names",
            actual = { "notes: " + report?.skippedReasons },
        ) {
            report != null && report.skippedReasons.any { it.startsWith("node_modules") } &&
                report.skippedReasons.any { it.startsWith("unsafe or duplicate") }
        },
    )
    out.add(
        check(
            "ZIP import never writes outside the project",
            "no evil.txt anywhere",
            actual = { "files: " + importedPaths(store, first) + "; evil.txt above the project: " + File(store.projectDir(id).parentFile, "evil.txt").exists() },
        ) {
            val above = store.projectDir(id).parentFile
            above != null && !File(above, "evil.txt").exists() && store.listAll(id).none { it.path.contains("evil") }
        },
    )
    out.add(
        check(
            "ZIP import keeps the first of two names that differ only by case",
            "A.txt holds first, no second file",
            actual = { "files: " + importedPaths(store, first) },
        ) {
            store.read(id, "A.txt").let { it is CoderReadResult.Text && it.text == "first" } &&
                store.listAll(id).count { it.path.equals("a.txt", ignoreCase = true) } == 1
        },
    )
    out.add(
        check(
            "ZIP import keeps binary bytes exactly",
            "same 8 bytes",
            actual = { "bytes: " + fileBytes(store, id, "img/logo.png")?.toList() },
        ) { fileBytes(store, id, "img/logo.png")?.toList() == listOf<Byte>(-119, 80, 78, 71, 0, 1, 2, 3) },
    )

    val exportFile = File(context.cacheDir, "coder-selftest-export-" + newId() + ".zip")
    try {
        val exported = try {
            runBlocking {
                FileOutputStream(exportFile).use { stream -> writeProjectZip(store, id, store.listAll(id), stream) }
            }
        } catch (e: Exception) {
            -1
        }
        out.add(check("ZIP export counts files", "4", actual = { "exported $exported" }) { exported == 4 })
        out.add(
            check(
                "ZIP export keeps folders and names",
                "folder entries end in a slash",
                actual = { "entries: " + zipEntryNames(exportFile.readBytes()).sorted() },
            ) {
                zipEntryNames(exportFile.readBytes()) ==
                    setOf("css/", "css/style.css", "img/", "img/logo.png", "A.txt", "index.html")
            },
        )
        val again = try {
            runBlocking { importStream(store, { exportFile.inputStream() }, "Test Site.zip", keepAll) }
        } catch (e: Exception) {
            CoderImportResult.Failure("threw " + e.javaClass.simpleName)
        }
        val againSuccess = again as? CoderImportResult.Success
        out.add(
            check(
                "An exported ZIP imports again with the same files",
                "same list, no outer folder",
                actual = { describeImport(again) + "; files: " + importedPaths(store, again) },
            ) {
                againSuccess != null && againSuccess.report.strippedWrapperFolder == null &&
                    store.listAll(againSuccess.projectId).map { it.path } == store.listAll(id).map { it.path }
            },
        )
    } finally {
        exportFile.delete()
    }

    val emptyTarget = File(context.cacheDir, "coder-selftest-empty-" + newId() + ".zip")
    val emptyResult = try {
        runBlocking { CoderZipExporter(context, store).export(newId(), Uri.fromFile(emptyTarget)) }
    } catch (e: Exception) {
        null
    }
    out.add(
        check(
            "An empty project is not exported",
            "Failure that says the project is empty",
            actual = {
                val text = if (emptyResult is CoderExportResult.Failure) "Failure: " + emptyResult.message else emptyResult.toString()
                text + "; file created: " + emptyTarget.exists()
            },
        ) { emptyResult is CoderExportResult.Failure && emptyResult.message.contains("empty") && !emptyTarget.exists() },
    )

    val before = root.list()?.size ?: 0
    val notZip = importBytes(store, "just some text".toByteArray(Charsets.UTF_8), "x.zip")
    out.add(
        check("A text file is refused as not a ZIP", "Failure about a valid ZIP", actual = { "got " + describeImport(notZip) }) {
            notZip is CoderImportResult.Failure && notZip.message.contains("valid ZIP")
        },
    )
    val onlySkipped = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("node_modules/a.js"))
            zip.write(1)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("empty-folder/"))
            zip.closeEntry()
        }
    }.toByteArray()
    val noFiles = importBytes(store, onlySkipped, "x.zip")
    out.add(
        check("A ZIP with nothing importable is refused", "Failure about no files", actual = { "got " + describeImport(noFiles) }) {
            noFiles is CoderImportResult.Failure && noFiles.message.contains("no files")
        },
    )
    val noRow = try {
        runBlocking { importStream(store, { ByteArrayInputStream(zipBytes) }, "x.zip", { _, _ -> false }) }
    } catch (e: Exception) {
        CoderImportResult.Success("", CoderImportReport(0, 0, emptyList(), null))
    }
    out.add(check("An import whose project row fails removes its files", "Failure", actual = { "got " + describeImport(noRow) }) {
        noRow is CoderImportResult.Failure
    })
    out.add(
        check(
            "A refused ZIP leaves no project folder behind",
            "same number of folders",
            actual = { "folders before $before, now " + (root.list()?.size ?: 0) },
        ) { (root.list()?.size ?: 0) == before },
    )
    return out
}

/**
 * A ZIP with a "../" name must never write outside the project. From Android 14 the reader refuses such a name, so the
 * import fails with the unsafe-names message; on older Android the entry is skipped. The detail says which happened.
 */
private fun zipTraversalCheck(store: CoderFileStore, root: File): CoderCheckResult {
    val name = "A ZIP with a ../ name never writes outside the project"
    val result = importBytes(store, zipOf("ok.txt" to "fine", "../evil.txt" to "evil"), "Traversal.zip")
    val stray = root.walkTopDown().any { it.name == "evil.txt" }
    val outcome: Pair<Boolean, String> = when (result) {
        is CoderImportResult.Failure -> {
            val expectedMessage = result.message == ZIP_UNSAFE_NAMES_MESSAGE
            val passed = expectedMessage && !stray
            Pair(passed, if (passed) "Failure with the unsafe-names message (Android 14 and later)" else "got Failure: " + result.message + "; evil.txt on disk: " + stray)
        }
        is CoderImportResult.Success -> {
            val inProject = store.listAll(result.projectId).any { it.path == "evil.txt" }
            val parent = store.projectDir(result.projectId).parentFile
            val inParent = parent != null && File(parent, "evil.txt").exists()
            val passed = !stray && !inProject && !inParent
            Pair(
                passed,
                if (passed) "Success, evil.txt was not written (older Android)" else "got Success; evil.txt in the project: $inProject, above it: $inParent, anywhere: $stray",
            )
        }
    }
    return CoderCheckResult(name, outcome.first, outcome.second)
}

private fun zipFolderChecks(store: CoderFileStore): List<CoderCheckResult> {
    val page = "<h1>Hi</h1>"
    val withSlash = importBytes(store, zipOf("dist/" to "", "dist/index.html" to page), "Built.zip")
    val withoutSlash = importBytes(store, zipOf("dist" to "", "dist/index.html" to page), "Built.zip")
    val clash = importBytes(store, zipOf("dist" to "not a folder", "dist/index.html" to page), "Built.zip")
    val emptyFile = importBytes(store, zipOf("empty.txt" to "", "a.txt" to "a"), "Empty.zip")
    fun isDistImport(result: CoderImportResult): Boolean {
        val success = result as? CoderImportResult.Success ?: return false
        return success.report.importedFiles == 1 && success.report.strippedWrapperFolder == "dist" &&
            store.listAll(success.projectId).map { it.path } == listOf("index.html")
    }
    return listOf(
        check(
            "A ZIP with dist/ and dist/index.html imports one file and removes the folder",
            "1 file, outer folder dist, no file named dist",
            actual = { describeImport(withSlash) + "; files: " + importedPaths(store, withSlash) },
        ) { isDistImport(withSlash) },
        check(
            "A folder written as a zero-byte dist entry gives the same result",
            "1 file, outer folder dist, no file named dist",
            actual = { describeImport(withoutSlash) + "; files: " + importedPaths(store, withoutSlash) },
        ) { isDistImport(withoutSlash) },
        check(
            "A file with content named like a folder is skipped and reported",
            "index.html only, 1 file skipped because a folder has the same name",
            actual = { describeImport(clash) + "; files: " + importedPaths(store, clash) },
        ) {
            val success = clash as? CoderImportResult.Success
            success != null && success.report.importedFiles == 1 && success.report.skippedFiles == 1 &&
                success.report.skippedReasons.contains("1 file skipped because a folder has the same name") &&
                store.listAll(success.projectId).map { it.path } == listOf("index.html")
        },
        check(
            "A zero-byte file that is not a folder stays a file",
            "a.txt and empty.txt, 2 added",
            actual = { describeImport(emptyFile) + "; files: " + importedPaths(store, emptyFile) },
        ) {
            val success = emptyFile as? CoderImportResult.Success
            success != null && success.report.importedFiles == 2 && success.report.strippedWrapperFolder == null &&
                store.listAll(success.projectId).map { it.path } == listOf("a.txt", "empty.txt")
        },
    )
}

private fun previewRootChecks(store: CoderFileStore): List<CoderCheckResult> {
    fun rootOf(vararg files: String): String? {
        val id = newId()
        for (path in files) store.write(id, path, "<p>x</p>")
        return CoderPreviewRoot.find(store, id)
    }
    return listOf(
        check("Preview root: index.html at the top wins", "empty string") { rootOf("index.html", "dist/index.html") == "" },
        check("Preview root: dist", "dist") { rootOf("dist/index.html") == "dist" },
        check("Preview root: dist comes before build, out and docs", "dist") {
            rootOf("docs/index.html", "out/index.html", "build/index.html", "dist/index.html") == "dist"
        },
        check("Preview root: build, then out, then docs", "build, out, docs") {
            rootOf("docs/index.html", "out/index.html", "build/index.html") == "build" &&
                rootOf("docs/index.html", "out/index.html") == "out" &&
                rootOf("docs/index.html") == "docs"
        },
        check("Preview root: no index.html is null", "null") { rootOf("src/index.html", "dist/main.js") == null },
    )
}

/** The three addresses the React templates load; the app answers them from its own files. */
private val REACT_SCRIPT_URLS = listOf(
    "https://unpkg.com/react@18/umd/react.development.js",
    "https://unpkg.com/react-dom@18/umd/react-dom.development.js",
    "https://unpkg.com/@babel/standalone/babel.min.js",
)

private fun shimChecks(): List<CoderCheckResult> {
    fun asset(url: String): CoderVendorAsset? = CoderCdnShim.match(Uri.parse(url))
    val out = ArrayList<CoderCheckResult>()
    val matches = listOf(
        REACT_SCRIPT_URLS[0] to CoderVendorAsset.React,
        REACT_SCRIPT_URLS[1] to CoderVendorAsset.ReactDom,
        REACT_SCRIPT_URLS[2] to CoderVendorAsset.Babel,
        "https://cdn.jsdelivr.net/npm/react@18.3.1/umd/react.development.js" to CoderVendorAsset.React,
        "https://cdn.jsdelivr.net/npm/react-dom@18.3.1/umd/react-dom.development.js" to CoderVendorAsset.ReactDom,
        "https://cdn.jsdelivr.net/npm/@babel/standalone/babel.min.js" to CoderVendorAsset.Babel,
        "https://cdnjs.cloudflare.com/ajax/libs/react/18.3.1/umd/react.development.js" to CoderVendorAsset.React,
        "https://cdnjs.cloudflare.com/ajax/libs/react-dom/18.3.1/umd/react-dom.development.js" to CoderVendorAsset.ReactDom,
        "https://cdnjs.cloudflare.com/ajax/libs/babel-standalone/7.24.0/babel.min.js" to CoderVendorAsset.Babel,
        "https://unpkg.com/react@18.2.0/umd/react.production.min.js" to CoderVendorAsset.React,
        "https://unpkg.com/react-dom@18.x/umd/react-dom.production.min.js" to CoderVendorAsset.ReactDom,
        "https://unpkg.com/@babel/standalone@7.24.0/babel.js" to CoderVendorAsset.Babel,
        "https://unpkg.com/react@18/umd/react.development.js?v=1" to CoderVendorAsset.React,
    )
    for ((url, expected) in matches) {
        out.add(check("Shim serves $url", expected.name) { asset(url) == expected })
    }
    val refused = listOf(
        "https://unpkg.com/react@19/umd/react.development.js" to "react 19",
        "https://unpkg.com/react/umd/react.development.js" to "no version",
        "http://unpkg.com/react@18/umd/react.development.js" to "http",
        "https://evil.com/react@18/umd/react.development.js" to "another host",
        "https://unpkg.com/react@18/umd/react.development.js/extra" to "extra segment",
        "https://unpkg.com/react@180/umd/react.development.js" to "version 180",
        "https://unpkg.com/@babel/standalone@8.0.0/babel.min.js" to "babel 8",
        "https://cdn.jsdelivr.net/react@18/umd/react.development.js" to "jsdelivr without /npm",
    )
    for ((url, label) in refused) {
        out.add(check("Shim refuses $label", "null") { asset(url) == null })
    }
    return out
}

private fun buildDetectorChecks(store: CoderFileStore): List<CoderCheckResult> {
    fun project(vararg files: Pair<String, String>): String {
        val id = newId()
        for ((path, text) in files) store.write(id, path, text)
        return id
    }
    val viteId = project(
        "index.html" to "<div id=\"root\"></div><script type=\"module\" src=\"/src/main.jsx\"></script>",
        "package.json" to "{\"devDependencies\":{\"vite\":\"^5.0.0\"},\"scripts\":{\"build\":\"vite build\"}}",
        "src/main.jsx" to "console.log(1)",
    )
    val distId = project("dist/index.html" to "<p>built</p>", "dist/app.js" to "1")
    val classicId = project(
        "index.html" to "<script src=\"" + REACT_SCRIPT_URLS[0] + "\"></script><script type=\"text/babel\" src=\"app.jsx\"></script>",
        "app.jsx" to "function App() { return <p>hi</p>; }",
    )
    val packageOnlyId = project("package.json" to "{\"dependencies\":{\"react-scripts\":\"5.0.1\"}}", "src/index.js" to "1")
    return listOf(
        check("Build detector: Vite source layout needs a build", "NeedsBuild that names Vite") {
            val result = CoderBuildDetector.check(store, viteId)
            result is CoderBuildCheck.NeedsBuild && result.reason.contains("Vite")
        },
        check("Build detector: a built dist folder is ready", "Ready") {
            CoderBuildDetector.check(store, distId) == CoderBuildCheck.Ready
        },
        check("Build detector: script-tag React is ready", "Ready") {
            CoderBuildDetector.check(store, classicId) == CoderBuildCheck.Ready
        },
        check("Build detector: an empty project is ready", "Ready") {
            CoderBuildDetector.check(store, newId()) == CoderBuildCheck.Ready
        },
        check("Build detector: package.json with a build tool and no page needs a build", "NeedsBuild") {
            CoderBuildDetector.check(store, packageOnlyId) is CoderBuildCheck.NeedsBuild
        },
    )
}

private fun explainerChecks(): List<CoderCheckResult> {
    val cases = listOf(
        "ReferenceError: foo is not defined" to "'foo'",
        "TypeError: Cannot read properties of null (reading 'style')" to "'style'",
        "TypeError: Cannot read properties of undefined (reading 'map')" to "'map'",
        "TypeError: Cannot set properties of null (setting 'innerHTML')" to "set something",
        "TypeError: foo.bar is not a function" to "'foo.bar'",
        "SyntaxError: Unexpected token '}'" to "typo",
        "SyntaxError: Unexpected end of input" to "typo",
        "SyntaxError: missing ) after argument list" to "typo",
        "SyntaxError: Invalid or unexpected token" to "typo",
        "SyntaxError: Unexpected identifier" to "typo",
        "TypeError: Failed to fetch" to "internet",
        "net::ERR_INTERNET_DISCONNECTED" to "internet",
        "Failed to load resource: the server responded with a status of 404 ()" to "wasn't found",
        "Error: Objects are not valid as a React child (found: object with keys {a})" to "{item.name}",
        "Error: Invalid hook call. Hooks can only be called inside of the body of a function component." to "useState",
        "Warning: Each child in a list should have a unique \"key\" prop." to "key={...}",
        "ReferenceError: require is not defined" to "import/export",
        "SyntaxError: Cannot use import statement outside a module" to "import/export",
        "ReferenceError: React is not defined" to "React isn't loaded",
        "ReferenceError: ReactDOM is not defined" to "React isn't loaded",
    )
    val out = ArrayList<CoderCheckResult>()
    for ((message, expected) in cases) {
        out.add(check("Explainer: $message", "a sentence containing $expected") {
            CoderErrorExplainer.explain(message)?.contains(expected) == true
        })
    }
    out.add(check("Explainer: an unknown message gives null", "null") {
        CoderErrorExplainer.explain("Something unusual happened") == null
    })
    out.add(check("Explainer: a 10,000 character message does not throw", "no exception") {
        CoderErrorExplainer.explain("x".repeat(10_000))
        CoderErrorExplainer.explain("Cannot read properties of null (reading '" + "y".repeat(10_000) + "')")
        true
    })
    return out
}

private fun reactTemplateChecks(): List<CoderCheckResult> {
    val hello = CoderTemplates.find("react-hello")
    val counter = CoderTemplates.find("react-counter")
    return listOf(
        check("React templates exist", "react-hello and react-counter") { hello != null && counter != null },
        check("React templates use classic scripts only", "no import or export text") {
            listOfNotNull(hello, counter).flatMap { it.files }.none {
                it.content.contains("import ") || it.content.contains("export ")
            }
        },
        check("React templates load the three bundled addresses", "all three unpkg URLs in index.html") {
            listOfNotNull(hello, counter).size == 2 && listOfNotNull(hello, counter).all { template ->
                val index = template.files.firstOrNull { it.path == "index.html" }?.content.orEmpty()
                REACT_SCRIPT_URLS.all { index.contains("src=\"" + it + "\"") }
            }
        },
        check("React template addresses are served by the shim", "all three match") {
            REACT_SCRIPT_URLS.all { CoderCdnShim.match(Uri.parse(it)) != null }
        },
    )
}

private fun aiProviderChecks(): List<CoderCheckResult> = listOf(
    check("AI provider ids map to the three providers", "claude, gemini, openai") {
        CoderAiProvider.fromId("claude") == CoderAiProvider.CLAUDE &&
            CoderAiProvider.fromId("gemini") == CoderAiProvider.GEMINI &&
            CoderAiProvider.fromId("openai") == CoderAiProvider.OPENAI
    },
    check("AI provider id null or unknown gives null", "null") {
        CoderAiProvider.fromId(null) == null && CoderAiProvider.fromId("") == null &&
            CoderAiProvider.fromId("mistral") == null && CoderAiProvider.fromId("Claude") == null
    },
    check("AI key pages are fixed https addresses", "three https pages") {
        CoderAiProvider.values().all { it.keysPageUrl.startsWith("https://") }
    },
    check("AI config text hides the key", "no key text, says hidden") {
        val text = CoderAiConfig(CoderAiProvider.CLAUDE, "sk-secret-key-1234", "some-model").toString()
        !text.contains("sk-secret-key-1234") && !text.contains("1234") && text.contains("apiKey=hidden") &&
            text.contains("some-model")
    },
)

private fun aiKeyChecks(): List<CoderCheckResult> = listOf(
    check("Key with spaces around it is trimmed", "sk-abc123") { cleanKey("  sk-abc123 ") == "sk-abc123" },
    check("Key with a newline inside loses the newline", "sk-abc123") {
        cleanKey("sk-ab\nc123\r\n") == "sk-abc123" && cleanKey("sk-ab c1\t23") == "sk-abc123"
    },
    check("Empty key is refused", "null") { cleanKey("") == null && cleanKey("  \n ") == null },
    check("Key of 512 characters is kept and 513 is refused", "512 ok, 513 null") {
        cleanKey("a".repeat(512)) != null && cleanKey("a".repeat(513)) == null
    },
    check("Key with a character outside printable ASCII is refused", "null") {
        cleanKey("sk-\u00E9123") == null && cleanKey("sk-\u4E2D123") == null
    },
    check("Model names with odd characters are refused", "letters, digits and . - _ : only") {
        cleanModelName(" gpt-4o ") == "gpt-4o" && cleanModelName("ft:gpt-4o:org::abc") == "ft:gpt-4o:org::abc" &&
            cleanModelName("") == null && cleanModelName("a/b") == null && cleanModelName("a b") == null &&
            cleanModelName("m?x=1") == null && cleanModelName("a".repeat(121)) == null
    },
)

private fun aiModelParsingChecks(): List<CoderCheckResult> {
    val claude = "{\"data\":[{\"type\":\"model\",\"id\":\"claude-b\"},{\"type\":\"model\",\"id\":\"claude-a\"}]," +
        "\"has_more\":false}"
    val openAi = "{\"object\":\"list\",\"data\":[{\"id\":\"gpt-4o\"},{\"id\":\"text-embedding-x\"}," +
        "{\"id\":\"whisper-1\"},{\"id\":\"gpt-4o\"},{\"id\":\"gpt-4o-mini\"},{\"id\":\"gpt-image-1\"}]}"
    val gemini = "{\"models\":[{\"name\":\"models/gemini-pro\",\"supportedGenerationMethods\":[\"countTokens\",\"generateContent\"]}," +
        "{\"name\":\"models/embedding-001\",\"supportedGenerationMethods\":[\"embedContent\"]}," +
        "{\"name\":\"models/gemini-flash\",\"supportedGenerationMethods\":[\"generateContent\"]}]}"
    return listOf(
        check("Claude model list is read and sorted", "claude-a, claude-b") {
            parseModels(CoderAiProvider.CLAUDE, claude) == listOf("claude-a", "claude-b")
        },
        check("OpenAI list drops non-chat models and duplicates", "gpt-4o, gpt-4o-mini") {
            parseModels(CoderAiProvider.OPENAI, openAi) == listOf("gpt-4o", "gpt-4o-mini")
        },
        check("Gemini list keeps only generateContent and drops models/", "gemini-flash, gemini-pro") {
            parseModels(CoderAiProvider.GEMINI, gemini) == listOf("gemini-flash", "gemini-pro")
        },
        check("Garbage model lists give null", "null for every provider") {
            CoderAiProvider.values().all { provider ->
                parseModels(provider, "not json at all") == null && parseModels(provider, "") == null &&
                    parseModels(provider, "[1,2]") == null && parseModels(provider, "{\"other\":1}") == null
            }
        },
        check("An empty list is a list, not garbage", "empty list") {
            parseModels(CoderAiProvider.CLAUDE, "{\"data\":[]}") == emptyList<String>()
        },
    )
}

private fun aiFailureTextChecks(): List<CoderCheckResult> = listOf(
    check("No internet gives the no-internet text", "No internet connection.") {
        describeFailure(UnknownHostException("api.example.com")) == "No internet connection." &&
            describeFailure(ConnectException("refused")) == "No internet connection."
    },
    check("A timeout gives the too-long text", "The request took too long. Try again.") {
        describeFailure(SocketTimeoutException("timeout")) == "The request took too long. Try again." &&
            describeFailure(InterruptedIOException("timeout")) == "The request took too long. Try again."
    },
    check("A secure-connection error gives its text", "A secure connection couldn't be made.") {
        describeFailure(SSLException("handshake")) == "A secure connection couldn't be made."
    },
    check("Any other error gives the general text and no details", "general text, no message inside") {
        val text = describeFailure(IOException("key=sk-secret-9999 https://x.example/?k=1"))
        text == "Something went wrong with the connection." && !text.contains("secret") && !text.contains("http")
    },
)

private fun aiCryptoChecks(): List<CoderCheckResult> {
    if (!CoderKeyCrypto.isAvailable()) {
        return listOf(CoderCheckResult("Key crypto: Keystore unavailable on this phone, checks skipped", true, "ok"))
    }
    return listOf(
        check("Key crypto round trip", "same bytes back, ciphertext without the text") {
            val secret = "sk-test-key-1234".toByteArray(Charsets.UTF_8)
            val sealed = CoderKeyCrypto.encrypt(secret)
            val back = if (sealed == null) null else CoderKeyCrypto.decrypt(sealed.ciphertext, sealed.iv)
            sealed != null && back != null && back.contentEquals(secret) && !sealed.ciphertext.contains("sk-test")
        },
        check("Key crypto uses a new IV every time", "two different IVs") {
            val secret = "sk-test-key-1234".toByteArray(Charsets.UTF_8)
            val first = CoderKeyCrypto.encrypt(secret)
            val second = CoderKeyCrypto.encrypt(secret)
            first != null && second != null && first.iv != second.iv
        },
        check("Key crypto refuses a wrong IV", "null") {
            val sealed = CoderKeyCrypto.encrypt("sk-test-key-1234".toByteArray(Charsets.UTF_8))
            val wrongIv = Base64.encodeToString(ByteArray(12), Base64.NO_WRAP)
            sealed != null && CoderKeyCrypto.decrypt(sealed.ciphertext, wrongIv) == null
        },
    )
}

// ---- Phase C6: AI chat ---------------------------------------------------------------------

private const val CHAT_TEST_KEY = "SECRET-KEY-1234"

private fun chatMessage(id: Long, role: ChatRole, text: String, failed: Boolean = false) =
    CoderChatMessage(id = id, role = role, text = text, failed = failed)

private fun aiChatChecks(): List<CoderCheckResult> =
    replyParserChecks() + userTurnChecks() + historyChecks() + requestBuildChecks() +
        responseParseChecks() + errorMapChecks()

private fun replyParserChecks(): List<CoderCheckResult> = listOf(
    check("Reply parser keeps plain text", "one text segment") {
        parseReply("Hello\nworld") == listOf(CoderReplySegment.Text("Hello\nworld"))
    },
    check("Reply parser reads a block with a path", "text, code, text") {
        parseReply("Here:\n```html path=index.html\n<p>Hi</p>\n```\nDone") == listOf(
            CoderReplySegment.Text("Here:"),
            CoderReplySegment.Code(CoderCodeBlock("html", "index.html", "<p>Hi</p>")),
            CoderReplySegment.Text("Done"),
        )
    },
    check("Reply parser reads two blocks", "two code segments with a text between") {
        parseReply("```css path=style.css\na{}\n```\nand\n```js\nlet x = 1\n```") == listOf(
            CoderReplySegment.Code(CoderCodeBlock("css", "style.css", "a{}")),
            CoderReplySegment.Text("and"),
            CoderReplySegment.Code(CoderCodeBlock("js", null, "let x = 1")),
        )
    },
    check("Reply parser keeps an unterminated block", "the rest becomes code") {
        parseReply("Look:\n```js\nlet a = 1;") == listOf(
            CoderReplySegment.Text("Look:"),
            CoderReplySegment.Code(CoderCodeBlock("js", null, "let a = 1;")),
        )
    },
    check("Reply parser drops an invalid path", "path is null") {
        parseReply("```html path=../x\n<p></p>\n```") ==
            listOf(CoderReplySegment.Code(CoderCodeBlock("html", null, "<p></p>")))
    },
    check("Reply parser keeps a short fence inside a longer one", "one block holding the inner fence") {
        parseReply("````md\n```js\nx\n```\n````") ==
            listOf(CoderReplySegment.Code(CoderCodeBlock("md", null, "```js\nx\n```")))
    },
    check("Reply parser gives nothing for empty text", "empty list") { parseReply("").isEmpty() },
    check("Reply parser handles very long text", "finishes quickly, never throws") {
        val start = System.nanoTime()
        val plain = parseReply("a".repeat(100_000))
        val fences = parseReply("```\nx\n".repeat(20_000))
        val lines = parseReply("line\n".repeat(50_000))
        plain.size == 1 && fences.isNotEmpty() && lines.size == 1 &&
            (System.nanoTime() - start) < 5_000_000_000L
    },
)

private fun userTurnChecks(): List<CoderCheckResult> {
    val marker = "[... file shortened ...]"
    val big = buildUserTurn("P", listOf("big.txt"), listOf(ContextFile("big.txt", "a".repeat(100_000))), null, null, "REQ")
    val manyPaths = buildUserTurn("P", (1..250).map { "f$it.txt" }, emptyList(), null, null, "REQ")
    val order = buildUserTurn(
        "P", emptyList(), listOf(ContextFile("open.js", "1"), ContextFile("b.js", "2")), null, null, "REQ",
    )
    val sixFiles = buildUserTurn("P", emptyList(), (1..8).map { ContextFile("f$it.txt", "x") }, null, null, "REQ")
    val huge = buildUserTurn(
        "P", emptyList(), (1..6).map { ContextFile("f$it.txt", "y".repeat(25_000)) }, null, null, "REQ",
    )
    val plain = buildUserTurn("P", listOf("a.txt"), listOf(ContextFile("a.txt", "AAA")), null, null, "REQ")
    val full = buildUserTurn("P", listOf("a.txt"), listOf(ContextFile("a.txt", "AAA")), "SEL", "ERR", "REQ")
    val unsafe = buildUserTurn(
        "P", listOf("a.txt", "../x"),
        listOf(ContextFile("../x", "HIDDEN-TEXT"), ContextFile("a.txt", "ok")), null, null, "REQ",
    )
    return listOf(
        check("Turn cuts a 100,000-character file to 60,000 plus the marker", "cut text, marker, shortened") {
            val content = big.text.substringAfter("<file path=\"big.txt\">\n").substringBefore("\n</file>")
            big.shortened && content == "a".repeat(60_000) + "\n" + marker
        },
        check("Turn lists at most 200 paths", "200 paths and a 50 more line") {
            manyPaths.text.lines().count { it.startsWith("- f") } == 200 && manyPaths.text.contains("- ... and 50 more")
        },
        check("Turn keeps the open file first", "open.js before b.js") {
            val open = order.text.indexOf("<file path=\"open.js\">")
            open >= 0 && open < order.text.indexOf("<file path=\"b.js\">")
        },
        check("Turn uses at most 6 files", "6 file tags") {
            Regex("<file path=").findAll(sixFiles.text).count() == 6
        },
        check("Turn stays within 120,000 characters", "short enough, shortened, first file kept") {
            huge.text.length <= 120_000 && huge.shortened && huge.text.contains("<file path=\"f1.txt\">") &&
                !huge.text.contains("<file path=\"f6.txt\">")
        },
        check("Turn leaves out selection and console when null", "no such tags") {
            !plain.text.contains("<selection>") && !plain.text.contains("<console>") && !plain.shortened
        },
        check("Turn holds only what was supplied", "exactly the expected text") {
            full.text == "<project name=\"P\">\nFiles:\n- a.txt\n</project>\n<file path=\"a.txt\">\nAAA\n</file>\n" +
                "<selection>\nSEL\n</selection>\n<console>\nERR\n</console>\n<request>\nREQ\n</request>"
        },
        check("Turn refuses invalid paths", "no text from ../x") {
            !unsafe.text.contains("HIDDEN-TEXT") && !unsafe.text.contains("../x") && unsafe.text.contains("ok")
        },
    )
}

private fun historyChecks(): List<CoderCheckResult> {
    val u = ChatRole.User
    val a = ChatRole.Assistant
    val twelve = (0..12).map { chatMessage(it.toLong(), if (it % 2 == 0) u else a, if (it % 2 == 0) "u$it" else "a$it") }
    return listOf(
        check("History drops a leading assistant message", "starts with the user message") {
            buildHistory(listOf(chatMessage(1, a, "a1"), chatMessage(2, u, "u1"))) == listOf(u to "u1")
        },
        check("History joins two user messages in a row", "one merged user message") {
            buildHistory(listOf(chatMessage(1, u, "u1"), chatMessage(2, u, "u2"))) == listOf(u to "u1\n\nu2")
        },
        check("History keeps at most 10 messages and starts with the user", "10 or fewer, user first, newest last") {
            val result = buildHistory(twelve)
            result.size <= 10 && result.first().first == u && result.last().second == "u12"
        },
        check("History leaves out messages that were not sent", "failed message missing") {
            buildHistory(
                listOf(
                    chatMessage(1, u, "u1"), chatMessage(2, a, "a1"),
                    chatMessage(3, u, "u2", failed = true), chatMessage(4, u, "u3"),
                ),
            ) == listOf(u to "u1", a to "a1", u to "u3")
        },
        check("History puts the full turn on the newest question only", "older text plain, newest full") {
            buildHistory(listOf(chatMessage(1, u, "u1"), chatMessage(2, a, "a1"), chatMessage(3, u, "u2")), "FULL") ==
                listOf(u to "u1", a to "a1", u to "FULL")
        },
    )
}

private fun chatBody(request: Request): String {
    val buffer = Buffer()
    request.body?.writeTo(buffer)
    return buffer.readUtf8()
}

private fun requestBuildChecks(): List<CoderCheckResult> {
    val turns = listOf(ChatRole.User to "hi", ChatRole.Assistant to "yo", ChatRole.User to "go")
    val claude = CoderAiClient.buildRequest(CoderAiConfig(CoderAiProvider.CLAUDE, CHAT_TEST_KEY, "claude-x"), "SYS", turns)
    val openAi = CoderAiClient.buildRequest(CoderAiConfig(CoderAiProvider.OPENAI, CHAT_TEST_KEY, "gpt-x"), "SYS", turns)
    val gemini = CoderAiClient.buildRequest(
        CoderAiConfig(CoderAiProvider.GEMINI, CHAT_TEST_KEY, "models/gemini-1.5-pro"), "SYS", turns,
    )
    val claudeBody = chatBody(claude)
    val openAiBody = chatBody(openAi)
    val geminiBody = chatBody(gemini)
    return listOf(
        check("Claude request address, header and body", "fixed address, key only in the header") {
            val json = JSONObject(claudeBody)
            claude.url.toString() == "https://api.anthropic.com/v1/messages" &&
                claude.header("x-api-key") == CHAT_TEST_KEY && claude.header("anthropic-version") == "2023-06-01" &&
                !claude.url.toString().contains(CHAT_TEST_KEY) && !claudeBody.contains(CHAT_TEST_KEY) &&
                json.getInt("max_tokens") == 4096 && json.getString("system") == "SYS" &&
                json.getJSONArray("messages").length() == 3 &&
                claude.body?.contentType()?.toString() == "application/json; charset=utf-8"
        },
        check("OpenAI request has no limits and a leading system message", "no temperature or token limit") {
            val json = JSONObject(openAiBody)
            val first = json.getJSONArray("messages").getJSONObject(0)
            openAi.url.toString() == "https://api.openai.com/v1/chat/completions" &&
                openAi.header("Authorization") == "Bearer $CHAT_TEST_KEY" &&
                !openAi.url.toString().contains(CHAT_TEST_KEY) && !openAiBody.contains(CHAT_TEST_KEY) &&
                !json.has("temperature") && !json.has("max_tokens") && !json.has("max_completion_tokens") &&
                first.getString("role") == "system" && first.getString("content") == "SYS" &&
                json.getJSONArray("messages").length() == 4
        },
        check("Gemini request strips models/ and uses model for answers", "address, header and roles") {
            val json = JSONObject(geminiBody)
            val contents = json.getJSONArray("contents")
            gemini.url.toString() == "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-pro:generateContent" &&
                gemini.header("x-goog-api-key") == CHAT_TEST_KEY &&
                !gemini.url.toString().contains(CHAT_TEST_KEY) && !geminiBody.contains(CHAT_TEST_KEY) &&
                contents.getJSONObject(1).getString("role") == "model" &&
                contents.getJSONObject(0).getString("role") == "user" &&
                json.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text") == "SYS"
        },
    )
}

private fun responseParseChecks(): List<CoderCheckResult> {
    fun parsed(provider: CoderAiProvider, body: String) = CoderAiClient.parseResponse(provider, body)
    return listOf(
        check("Claude answer joins text parts and reads the cut-short flag", "joined text, truncated") {
            val cut = "{\"content\":[{\"type\":\"text\",\"text\":\"Hello \"},{\"type\":\"tool_use\"}," +
                "{\"type\":\"text\",\"text\":\"world\"}],\"stop_reason\":\"max_tokens\"}"
            val done = "{\"content\":[{\"type\":\"text\",\"text\":\"Hi\"}],\"stop_reason\":\"end_turn\"}"
            parsed(CoderAiProvider.CLAUDE, cut) == CoderChatResult.Success("Hello world", true) &&
                parsed(CoderAiProvider.CLAUDE, done) == CoderChatResult.Success("Hi", false)
        },
        check("OpenAI answer, cut-short flag and refusal", "text, truncated, refusal as text") {
            val cut = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hi\"},\"finish_reason\":\"length\"}]}"
            val refusal = "{\"choices\":[{\"message\":{\"content\":null,\"refusal\":\"I can't help with that.\"}," +
                "\"finish_reason\":\"stop\"}]}"
            parsed(CoderAiProvider.OPENAI, cut) == CoderChatResult.Success("Hi", true) &&
                parsed(CoderAiProvider.OPENAI, refusal) == CoderChatResult.Success("I can't help with that.", false)
        },
        check("Gemini answer, cut-short flag and block reason", "joined text, truncated, blocked") {
            val cut = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"A\"},{\"text\":\"B\"}]},\"finishReason\":\"MAX_TOKENS\"}]}"
            val blocked = parsed(CoderAiProvider.GEMINI, "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}")
            parsed(CoderAiProvider.GEMINI, cut) == CoderChatResult.Success("AB", true) &&
                blocked is CoderChatResult.Failure && blocked.kind == CoderChatFailure.Blocked
        },
        check("Unreadable answers are failures, not crashes", "failure for every provider") {
            CoderAiProvider.values().all { provider ->
                parsed(provider, "not json") is CoderChatResult.Failure &&
                    parsed(provider, "{}") is CoderChatResult.Failure && parsed(provider, "") is CoderChatResult.Failure
            }
        },
    )
}

private fun errorMapChecks(): List<CoderCheckResult> {
    fun kindOf(code: Int, body: String = "", provider: CoderAiProvider = CoderAiProvider.OPENAI) =
        CoderAiClient.mapError(provider, code, body).kind
    return listOf(
        check("Error 401 and 403 mean the key was refused", "InvalidKey") {
            kindOf(401) == CoderChatFailure.InvalidKey && kindOf(403) == CoderChatFailure.InvalidKey
        },
        check("Error 404 means the model was not found", "ModelNotFound") { kindOf(404) == CoderChatFailure.ModelNotFound },
        check("Error 429 means a limit was reached", "RateLimited") { kindOf(429) == CoderChatFailure.RateLimited },
        check("Error 503 and 529 mean the provider is busy", "Busy") {
            kindOf(503) == CoderChatFailure.Busy && kindOf(529) == CoderChatFailure.Busy
        },
        check("Error 400 adds the provider's one-line message", "rejected text plus the message") {
            val result = CoderAiClient.mapError(
                CoderAiProvider.OPENAI, 400, "{\"error\":{\"message\":\"Bad\\nthing   here\"}}",
            )
            result.kind == CoderChatFailure.Other &&
                result.message == "The provider rejected the request. Bad thing here"
        },
        check("Error 400 with an unreadable body has no detail", "only the rejected text") {
            CoderAiClient.mapError(CoderAiProvider.CLAUDE, 400, "<html>oops</html>").message ==
                "The provider rejected the request."
        },
        check("A long provider message is cut to 300 characters", "300 characters of detail") {
            val body = "{\"error\":{\"message\":\"" + "x".repeat(500) + "\"}}"
            CoderAiClient.mapError(CoderAiProvider.GEMINI, 400, body).message.length ==
                "The provider rejected the request. ".length + 300
        },
        check("Gemini's wrong-key answer counts as an invalid key", "InvalidKey") {
            kindOf(400, "{\"error\":{\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}", CoderAiProvider.GEMINI) ==
                CoderChatFailure.InvalidKey
        },
        check("Other codes give the code in plain words", "error (500)") {
            CoderAiClient.mapError(CoderAiProvider.OPENAI, 500, "").message == "The provider returned an error (500)."
        },
    )
}

// ---- Phase C7: GitHub foundation -----------------------------------------------------------
// Every fake secret below is joined from pieces at run time: GitHub's push protection refuses a
// commit that contains a token-looking text, so no such text may stand in this file.

private const val GH_TEST_TOKEN = "TESTTOKEN-ABCDEFGH"
private const val GH_OWNER = "octocat"
private const val GH_REPO = "hello-world"
private const val GH_SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
private const val GH_SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
private const val GH_SHA_C = "cccccccccccccccccccccccccccccccccccccccc"

private fun githubChecks(store: CoderFileStore): List<CoderCheckResult> =
    gitShaChecks(store) + repoRefChecks() + pushPlanChecks() + ignoreChecks() + snapshotChecks(store) +
        secretScannerChecks() + githubRequestChecks() + githubErrorChecks() + githubTokenChecks() + githubCryptoChecks()

private fun gitShaChecks(store: CoderFileStore): List<CoderCheckResult> = listOf(
    check("Git hash of an empty file", "e69de29b...") {
        CoderGitSha.blobSha(ByteArray(0)) == "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391"
    },
    check("Git hash of hello and a new line", "ce013625...") {
        CoderGitSha.blobSha("hello\n".toByteArray(Charsets.UTF_8)) == "ce013625030ba8dba906f756967f9e9ca394464a"
    },
    check("Git hash of a file equals the hash of its bytes", "same hash, text and binary") {
        val id = "ghsha-" + newId()
        store.write(id, "h.txt", "hello\n")
        val dir = store.resolve(id, "")!!
        val binary = byteArrayOf(0, -1, 10, 13, 127, -128, 65)
        java.io.File(dir, "img.bin").writeBytes(binary)
        val text = store.resolve(id, "h.txt")!!
        val image = store.resolve(id, "img.bin")!!
        CoderGitSha.blobSha(text) == "ce013625030ba8dba906f756967f9e9ca394464a" &&
            CoderGitSha.blobSha(image) == CoderGitSha.blobSha(binary)
    },
)

private fun repoRefChecks(): List<CoderCheckResult> {
    val good = listOf(
        "octocat/hello-world" to "hello-world",
        "https://github.com/octocat/hello-world" to "hello-world",
        "https://github.com/octocat/hello-world.git" to "hello-world",
        "https://github.com/octocat/hello-world/" to "hello-world",
        "github.com/octocat/hello-world" to "hello-world",
        "  octocat/my_repo.v2  " to "my_repo.v2",
    )
    val bad = listOf("", "octocat", "a/b/c", "octocat/", "oct ocat/x", "octocat/..", "https://gitlab.com/o/n")
    val out = ArrayList<CoderCheckResult>()
    for ((raw, expectedName) in good) {
        out.add(check("Repository reference accepted: " + raw.trim(), "octocat and $expectedName") {
            parseRepoRef(raw) == Pair("octocat", expectedName)
        })
    }
    for (raw in bad) {
        out.add(check("Repository reference refused: " + raw.ifEmpty { "empty" }, "null") { parseRepoRef(raw) == null })
    }
    out.add(check("Repository owner longer than 39 characters is refused", "null") {
        parseRepoRef("a".repeat(40) + "/x") == null && parseRepoRef("a".repeat(39) + "/x") != null
    })
    out.add(check("Repository names", "valid and invalid names") {
        isValidRepoName("my-app_1.0") && !isValidRepoName("") && !isValidRepoName(".") && !isValidRepoName("..") &&
            !isValidRepoName("a b") && !isValidRepoName("a/b") && isValidRepoName("a".repeat(100)) &&
            !isValidRepoName("a".repeat(101))
    })
    return out
}

private fun ghLocal(path: String, sha: String) = LocalFile(path, 10L, sha)

private fun ghRemote(path: String, sha: String, type: String = "blob", mode: String = "100644") =
    RemoteEntry(path, type, sha, mode)

private fun pushPlanChecks(): List<CoderCheckResult> {
    val local = listOf(
        ghLocal("new.txt", GH_SHA_A), ghLocal("changed.txt", GH_SHA_B), ghLocal("same.txt", GH_SHA_A),
        ghLocal("run.sh", GH_SHA_B), ghLocal("docs", GH_SHA_C),
    )
    val remote = listOf(
        ghRemote("changed.txt", GH_SHA_A), ghRemote("same.txt", GH_SHA_A), ghRemote("run.sh", GH_SHA_A, mode = "100755"),
        ghRemote("docs", GH_SHA_B, type = "tree", mode = "040000"), ghRemote("only-on-github.txt", GH_SHA_C),
    )
    val plan = CoderPushPlanner.computePlan(local, remote)
    return listOf(
        check("Push plan: a file GitHub does not have is new", "new.txt only") {
            plan.newFiles.map { it.path } == listOf("new.txt")
        },
        check("Push plan: a different hash is changed", "changed.txt and run.sh") {
            plan.changedFiles.map { it.path } == listOf("changed.txt", "run.sh")
        },
        check("Push plan: the same hash is unchanged", "1 unchanged") { plan.unchangedCount == 1 },
        check("Push plan: files only on GitHub are left alone", "not in any list") {
            val all = plan.newFiles + plan.changedFiles
            all.none { it.path == "only-on-github.txt" } && plan.conflicts.none { it == "only-on-github.txt" }
        },
        check("Push plan: an executable file keeps its mode", "100755 for run.sh") {
            plan.remoteModes["run.sh"] == "100755" && plan.remoteModes["changed.txt"] == "100644" &&
                plan.remoteModes.keys == setOf("changed.txt", "run.sh")
        },
        check("Push plan: a folder on GitHub is a conflict", "docs left out and listed") {
            plan.conflicts == listOf("docs") && (plan.newFiles + plan.changedFiles).none { it.path == "docs" }
        },
        check("Push plan: nothing local and nothing remote", "empty plan") {
            val empty = CoderPushPlanner.computePlan(emptyList(), emptyList())
            empty.newFiles.isEmpty() && empty.changedFiles.isEmpty() && empty.unchangedCount == 0 && empty.conflicts.isEmpty()
        },
    )
}

private fun ignoreChecks(): List<CoderCheckResult> {
    val out = ArrayList<CoderCheckResult>()
    for (path in listOf("node_modules/a.js", ".git/x", "a/.DS_Store", ".x.tmp-1")) {
        out.add(check("Push ignores $path", "ignored") { CoderPushPlanner.isIgnored(path) })
    }
    out.add(check("Push keeps src/a.js and .gitignore", "not ignored") {
        !CoderPushPlanner.isIgnored("src/a.js") && !CoderPushPlanner.isIgnored(".gitignore")
    })
    return out
}

private fun snapshotChecks(store: CoderFileStore): List<CoderCheckResult> = listOf(
    check("Push snapshot lists real files with their git hashes", "a.txt and dir/b.txt only") {
        val id = "ghsnap-" + newId()
        store.write(id, "a.txt", "hello\n")
        store.write(id, "dir/b.txt", "")
        store.write(id, "node_modules/x.js", "x")
        store.write(id, ".git/config", "x")
        val files = runBlocking { CoderPushPlanner.snapshot(store, id) }
        files.map { it.path }.sorted() == listOf("a.txt", "dir/b.txt") &&
            files.first { it.path == "a.txt" }.sha == "ce013625030ba8dba906f756967f9e9ca394464a" &&
            files.first { it.path == "dir/b.txt" }.sha == "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391" &&
            files.first { it.path == "a.txt" }.sizeBytes == 6L
    },
)

private fun secretScannerChecks(): List<CoderCheckResult> {
    val aws = "AK" + "IA" + "B".repeat(16)
    val ghToken = "gh" + "p_" + "a".repeat(36)
    val ghFine = "github" + "_pat_" + "a".repeat(50)
    val aiKey = "s" + "k-" + "a".repeat(24)
    val google = "AI" + "za" + "a".repeat(35)
    val pem = "-----BEGIN " + "RSA PRIV" + "ATE KEY-----"
    val slack = "xo" + "xb-" + "1234567890" + "-abcdefghij"
    val generic = "const password = \"" + "correct-horse-battery" + "\""
    val kinds = listOf(
        Triple("AWS access key", "key = $aws", aws),
        Triple("GitHub token", "token $ghToken", ghToken),
        Triple("GitHub token", "x $ghFine", ghFine),
        Triple("AI or payment key", "KEY=$aiKey", aiKey),
        Triple("Google API key", "k: $google", google),
        Triple("private key", pem, pem),
        Triple("Slack token", "hook $slack", slack),
        Triple("possible secret", generic, "correct-horse-battery"),
    )
    val out = ArrayList<CoderCheckResult>()
    for ((kind, line, secret) in kinds) {
        out.add(check("Secret scanner finds: $kind (${secret.length} characters)", "one finding of that kind, without the text") {
            val found = CoderSecretScanner.scan(listOf("src/config.js" to "ok\nok\n$line\n"))
            found.size == 1 && found[0].kind == kind && found[0].line == 3 && found[0].path == "src/config.js" &&
                !found[0].toString().contains(secret)
        })
    }
    out.add(check("Secret scanner skips a line of 2001 characters, not one of 2000", "only the short line is found") {
        val long = aws + "x".repeat(2001 - aws.length)
        val short = aws + "x".repeat(2000 - aws.length)
        val found = CoderSecretScanner.scan(listOf("a.txt" to "$long\n$short"))
        long.length == 2001 && found.size == 1 && found[0].line == 2
    })
    out.add(check("Secret scanner skips a binary-looking file", "nothing found") {
        CoderSecretScanner.scan(listOf("a.bin" to ("a\u0000" + aws))).isEmpty()
    })
    out.add(check("Secret scanner finds nothing in ordinary code", "nothing found") {
        val code = "fun main() {\n    val total = 1 + 2\n    println(\"hello\")\n}\n" +
            "<div class=\"ta" + "sk-management-dashboard-widget\"></div>\nconst tokenCount = 12\n"
        CoderSecretScanner.scan(listOf("Main.kt" to code)).isEmpty()
    })
    out.add(check("Secret scanner returns at most 50 findings", "50") {
        CoderSecretScanner.scan(listOf("a.txt" to List(60) { aws }.joinToString("\n"))).size == 50
    })
    return out
}

private fun ghBodyText(request: Request): String {
    val body = request.body ?: return ""
    val buffer = Buffer()
    body.writeTo(buffer)
    return buffer.readUtf8()
}

private fun githubRequestChecks(): List<CoderCheckResult> {
    val blob = byteArrayOf(0, 1, 2, -1, -2, 65, 66)
    val spaced = "my folder/na\u00efve file.txt"
    val requests: List<Pair<String, Request>> = listOf(
        "user" to CoderGitHubRequests.user(GH_TEST_TOKEN),
        "repo" to CoderGitHubRequests.repo(GH_TEST_TOKEN, GH_OWNER, GH_REPO),
        "create repo" to CoderGitHubRequests.createRepo(GH_TEST_TOKEN, GH_REPO, true),
        "branch head" to CoderGitHubRequests.branchHead(GH_TEST_TOKEN, GH_OWNER, GH_REPO, "feature/x"),
        "latest commit" to CoderGitHubRequests.latestCommit(GH_TEST_TOKEN, GH_OWNER, GH_REPO),
        "commit" to CoderGitHubRequests.commit(GH_TEST_TOKEN, GH_OWNER, GH_REPO, GH_SHA_A),
        "tree" to CoderGitHubRequests.tree(GH_TEST_TOKEN, GH_OWNER, GH_REPO, GH_SHA_A),
        "blob" to CoderGitHubRequests.createBlob(GH_TEST_TOKEN, GH_OWNER, GH_REPO, blob),
        "new tree" to CoderGitHubRequests.createTree(
            GH_TEST_TOKEN, GH_OWNER, GH_REPO, GH_SHA_A, listOf(TreeEntryInput(spaced, "100644", GH_SHA_B)),
        ),
        "commit create" to CoderGitHubRequests.createCommit(GH_TEST_TOKEN, GH_OWNER, GH_REPO, "Save", GH_SHA_A, GH_SHA_B),
        "ref update" to CoderGitHubRequests.updateRef(GH_TEST_TOKEN, GH_OWNER, GH_REPO, "main", GH_SHA_A),
        "first file" to CoderGitHubRequests.putFile(GH_TEST_TOKEN, GH_OWNER, GH_REPO, spaced, blob, "First"),
    )
    val out = ArrayList<CoderCheckResult>()
    for ((label, request) in requests) {
        out.add(check("GitHub request ($label): safe address, headers, no token in address or body", "https api.github.com, 4 headers") {
            request.url.scheme == "https" && request.url.host == "api.github.com" &&
                !request.url.toString().contains(GH_TEST_TOKEN) && !ghBodyText(request).contains(GH_TEST_TOKEN) &&
                request.header("Accept") == "application/vnd.github+json" &&
                request.header("Authorization") == "Bearer $GH_TEST_TOKEN" &&
                request.header("X-GitHub-Api-Version") == "2022-11-28" &&
                request.header("User-Agent") == "NeriboVault-Coder"
        })
    }
    out.add(check("GitHub request: the blob body is valid base64 of the file", "same bytes back") {
        val request = CoderGitHubRequests.createBlob(GH_TEST_TOKEN, GH_OWNER, GH_REPO, blob)
        val json = JSONObject(ghBodyText(request))
        request.method == "POST" && json.getString("encoding") == "base64" &&
            Base64.decode(json.getString("content"), Base64.NO_WRAP).contentEquals(blob) &&
            request.body?.contentLength() == ghBodyText(request).toByteArray(Charsets.UTF_8).size.toLong()
    })
    out.add(check("GitHub request: the ref update is a PATCH without force", "PATCH, force false") {
        val request = CoderGitHubRequests.updateRef(GH_TEST_TOKEN, GH_OWNER, GH_REPO, "main", GH_SHA_A)
        val json = JSONObject(ghBodyText(request))
        request.method == "PATCH" && !json.getBoolean("force") && json.getString("sha") == GH_SHA_A &&
            request.url.encodedPath == "/repos/$GH_OWNER/$GH_REPO/git/refs/heads/main"
    })
    out.add(check("GitHub request: a branch name keeps its slash in the address", "heads/feature/x") {
        CoderGitHubRequests.branchHead(GH_TEST_TOKEN, GH_OWNER, GH_REPO, "feature/x").url.encodedPath ==
            "/repos/$GH_OWNER/$GH_REPO/git/ref/heads/feature/x"
    })
    out.add(check("GitHub request: the tree call asks for everything and the empty-repo call for one commit", "query parameters") {
        CoderGitHubRequests.tree(GH_TEST_TOKEN, GH_OWNER, GH_REPO, GH_SHA_A).url.queryParameter("recursive") == "1" &&
            CoderGitHubRequests.latestCommit(GH_TEST_TOKEN, GH_OWNER, GH_REPO).url.queryParameter("per_page") == "1"
    })
    out.add(check("GitHub request: a new tree keeps file names exactly and is built from blobs", "exact path, blob, mode") {
        val request = CoderGitHubRequests.createTree(
            GH_TEST_TOKEN, GH_OWNER, GH_REPO, GH_SHA_A,
            listOf(TreeEntryInput(spaced, "100755", GH_SHA_B), TreeEntryInput("b.txt", "120000", GH_SHA_C)),
        )
        val json = JSONObject(ghBodyText(request))
        val tree: JSONArray = json.getJSONArray("tree")
        json.getString("base_tree") == GH_SHA_A && tree.length() == 2 &&
            tree.getJSONObject(0).getString("path") == spaced && tree.getJSONObject(0).getString("mode") == "100755" &&
            tree.getJSONObject(1).getString("mode") == "100644" && tree.getJSONObject(1).getString("type") == "blob"
    })
    out.add(check("GitHub request: a tree without a base has no base_tree", "no base_tree key") {
        !JSONObject(ghBodyText(CoderGitHubRequests.createTree(GH_TEST_TOKEN, GH_OWNER, GH_REPO, null, emptyList()))).has("base_tree")
    })
    out.add(check("GitHub request: the commit has one parent", "message, tree and parents") {
        val json = JSONObject(ghBodyText(CoderGitHubRequests.createCommit(GH_TEST_TOKEN, GH_OWNER, GH_REPO, "Save", GH_SHA_A, GH_SHA_B)))
        json.getString("message") == "Save" && json.getString("tree") == GH_SHA_A &&
            json.getJSONArray("parents").length() == 1 && json.getJSONArray("parents").getString(0) == GH_SHA_B
    })
    out.add(check("GitHub request: a new repository is empty and private on request", "private true, auto_init false") {
        val json = JSONObject(ghBodyText(CoderGitHubRequests.createRepo(GH_TEST_TOKEN, "my-app", true)))
        json.getString("name") == "my-app" && json.getBoolean("private") && !json.getBoolean("auto_init")
    })
    out.add(check("GitHub request: the first-file upload encodes the path and has no branch", "encoded segments, PUT") {
        val request = CoderGitHubRequests.putFile(GH_TEST_TOKEN, GH_OWNER, GH_REPO, spaced, blob, "First \"commit\"")
        val json = JSONObject(ghBodyText(request))
        request.method == "PUT" && request.url.encodedPath.contains("/contents/my%20folder/na") &&
            request.url.encodedPath.endsWith("%20file.txt") && !json.has("branch") &&
            json.getString("message") == "First \"commit\"" &&
            Base64.decode(json.getString("content"), Base64.NO_WRAP).contentEquals(blob)
    })
    return out
}

private fun githubErrorChecks(): List<CoderCheckResult> {
    fun kindOf(code: Int, remaining: String? = null) = CoderGitHubErrors.map(code, remaining, null).kind
    return listOf(
        check("GitHub error 401 means the token was refused", "InvalidToken and the plain text") {
            val error = CoderGitHubErrors.map(401, null, null)
            error.kind == CoderGitHubErrorKind.InvalidToken &&
                error.message == "GitHub didn't accept this token. It may be wrong or expired."
        },
        check("GitHub error 403 with no requests left is a rate limit", "RateLimited") {
            kindOf(403, "0") == CoderGitHubErrorKind.RateLimited
        },
        check("GitHub error 403 otherwise means no permission", "NoAccess") {
            kindOf(403) == CoderGitHubErrorKind.NoAccess && kindOf(403, "42") == CoderGitHubErrorKind.NoAccess &&
                CoderGitHubErrors.map(403, null, null).message.contains("'repo'")
        },
        check("GitHub error 404 means not found", "NotFound") { kindOf(404) == CoderGitHubErrorKind.NotFound },
        check("GitHub error 409 is a conflict", "Conflict") { kindOf(409) == CoderGitHubErrorKind.Conflict },
        check("GitHub error 422 adds GitHub's one-line message, at most 200 characters", "Invalid, one line") {
            val error = CoderGitHubErrors.map(422, null, "Bad\nthing   here " + "x".repeat(400))
            error.kind == CoderGitHubErrorKind.Invalid && !error.message.contains('\n') &&
                error.message.startsWith("GitHub rejected the request. Bad thing here x") &&
                error.message.length == "GitHub rejected the request. ".length + 200
        },
        check("GitHub error 422 on a ref update means the branch moved", "Conflict and the newer-changes text") {
            val error = CoderGitHubErrors.map(422, null, "Reference update failed", forRefUpdate = true)
            error.kind == CoderGitHubErrorKind.Conflict &&
                error.message == "GitHub has newer changes than this phone. Try again."
        },
        check("GitHub error 500 and 503 mean GitHub is busy", "Busy") {
            kindOf(500) == CoderGitHubErrorKind.Busy && kindOf(503) == CoderGitHubErrorKind.Busy
        },
        check("GitHub error with another code gives the code in plain words", "error (418)") {
            val error = CoderGitHubErrors.map(418, null, null)
            error.kind == CoderGitHubErrorKind.Other && error.message == "GitHub returned an error (418)."
        },
        check("A thrown error is a network error with no details from the error", "Network, no secret in the text") {
            val error = CoderGitHubErrors.fromException(IOException("token=$GH_TEST_TOKEN https://api.github.com/x"))
            error.kind == CoderGitHubErrorKind.Network && error.message == describeFailure(IOException()) &&
                !error.message.contains(GH_TEST_TOKEN) && !error.message.contains("http")
        },
        check("A missing connection is described in plain words", "No internet connection.") {
            CoderGitHubErrors.fromException(UnknownHostException("api.github.com")).message == "No internet connection."
        },
    )
}

private fun githubTokenChecks(): List<CoderCheckResult> = listOf(
    check("GitHub token cleaning removes spaces and line breaks", "abc123") {
        cleanGitHubToken("  abc 123\n") == "abc123"
    },
    check("GitHub token cleaning refuses empty text and non-ASCII characters", "null") {
        cleanGitHubToken("") == null && cleanGitHubToken("   \n") == null && cleanGitHubToken("abc\u00e9") == null
    },
    check("GitHub token cleaning accepts 512 characters and refuses 513", "512 yes, 513 null") {
        cleanGitHubToken("a".repeat(512)) != null && cleanGitHubToken("a".repeat(513)) == null
    },
)

private fun githubCryptoChecks(): List<CoderCheckResult> {
    if (!CoderGitHubKeyCrypto.isAvailable()) {
        return listOf(CoderCheckResult("GitHub token crypto: Keystore unavailable on this phone, checks skipped", true, "ok"))
    }
    return listOf(
        check("GitHub token crypto round trip", "same bytes back, ciphertext without the text") {
            val secret = GH_TEST_TOKEN.toByteArray(Charsets.UTF_8)
            val sealed = CoderGitHubKeyCrypto.encrypt(secret)
            val back = if (sealed == null) null else CoderGitHubKeyCrypto.decrypt(sealed.ciphertext, sealed.iv)
            sealed != null && back != null && back.contentEquals(secret) && !sealed.ciphertext.contains("TESTTOKEN")
        },
        check("GitHub token crypto has its own key alias", "different from the AI key alias") {
            CoderGitHubKeyCrypto.KEY_ALIAS == "neribo_coder_github_key" && CoderGitHubKeyCrypto.KEY_ALIAS != CoderKeyCrypto.KEY_ALIAS
        },
        check("GitHub token crypto refuses a wrong IV", "null") {
            val sealed = CoderGitHubKeyCrypto.encrypt(GH_TEST_TOKEN.toByteArray(Charsets.UTF_8))
            val wrongIv = Base64.encodeToString(ByteArray(12), Base64.NO_WRAP)
            sealed != null && CoderGitHubKeyCrypto.decrypt(sealed.ciphertext, wrongIv) == null
        },
    )
}

// ---- Developer link and project info (phase C8, part A) -------------------------------------

private fun infoProject(
    name: String = "Shop App",
    status: String = "active",
    description: String = "A small shop.",
    tech: List<String> = listOf("Kotlin", "Room"),
) = ProjectEntity(
    id = "dev-1",
    createdAt = 1L,
    updatedAt = 2L,
    name = name,
    description = description,
    status = status,
    techStack = tech,
    repoUrl = "https://example.invalid/repo",
    liveUrl = "https://example.invalid/live",
)

private fun developerLinkChecks(): List<CoderCheckResult> = projectInfoChecks() + infoInTurnChecks()

private fun projectInfoChecks(): List<CoderCheckResult> {
    val base = "<project_info>\nName: Shop App\nStatus: Active\nDescription: A small shop.\nTech stack: Kotlin, Room\n</project_info>"
    val longDescription = buildProjectInfo(infoProject(description = "d".repeat(5_000)), null)
    val manyTech = buildProjectInfo(infoProject(tech = (1..30).map { "tech$it" }), null)
    val longTech = buildProjectInfo(infoProject(tech = listOf("x".repeat(60))), null)
    val eightNotes = buildProjectInfo(infoProject(), (1..8).map { "T$it" to "B$it" })
    val longNote = buildProjectInfo(infoProject(), listOf("Big" to "z".repeat(3_000)))
    return listOf(
        check("Project info holds the fields in order", "exact tags and lines") {
            buildProjectInfo(infoProject(), null) == base
        },
        check("Project info never holds the repository or live address", "no address text") {
            !base.contains("example.invalid") && !buildProjectInfo(infoProject(), listOf("N" to "b")).contains("example.invalid")
        },
        check("Project info leaves out blank fields", "no empty lines") {
            buildProjectInfo(infoProject(name = "  ", status = "", description = "", tech = emptyList()), null) ==
                "<project_info>\n</project_info>" &&
                buildProjectInfo(infoProject(status = " ", description = " ", tech = listOf(" ", "")), null) ==
                "<project_info>\nName: Shop App\n</project_info>"
        },
        check("Project info cuts a 5,000-character description to 1,000", "1,000 characters") {
            val line = longDescription.lines().first { it.startsWith("Description: ") }
            line.removePrefix("Description: ") == "d".repeat(1_000)
        },
        check("Project info uses at most 20 tech items", "20 items") {
            val line = manyTech.lines().first { it.startsWith("Tech stack: ") }
            val items = line.removePrefix("Tech stack: ").split(", ")
            items.size == 20 && items.first() == "tech1" && items.last() == "tech20"
        },
        check("Project info cuts a long tech item to 40 characters", "40 characters") {
            longTech.lines().first { it.startsWith("Tech stack: ") }.removePrefix("Tech stack: ") == "x".repeat(40)
        },
        check("Project info has no notes block for null or empty notes", "no notes tag") {
            !buildProjectInfo(infoProject(), null).contains("<notes>") &&
                !buildProjectInfo(infoProject(), emptyList()).contains("<notes>") &&
                !buildProjectInfo(infoProject(), listOf("" to " ")).contains("<notes>")
        },
        check("Project info shows a note under its title", "exact notes block") {
            buildProjectInfo(infoProject(), listOf("Plan" to "Step one")) == base + "\n<notes>\n## Plan\nStep one\n</notes>"
        },
        check("Project info uses at most 5 notes, in the given order", "T1 to T5 only") {
            Regex("(?m)^## ").findAll(eightNotes).count() == 5 &&
                eightNotes.contains("## T5\nB5") && !eightNotes.contains("## T6")
        },
        check("Project info cuts a note text to 1,500 characters", "1,500 characters") {
            longNote.contains("## Big\n" + "z".repeat(1_500) + "\n</notes>")
        },
    )
}

private fun infoInTurnChecks(): List<CoderCheckResult> {
    val info = buildProjectInfo(infoProject(), listOf("Plan" to "Step one"))
    val files = listOf(ContextFile("a.txt", "AAA"))
    val without = buildUserTurn("P", listOf("a.txt"), files, null, null, "REQ")
    val withInfo = buildUserTurn("P", listOf("a.txt"), files, null, null, "REQ", info)
    val blank = buildUserTurn("P", listOf("a.txt"), files, null, null, "REQ", "   ")
    val crowded = buildUserTurn(
        "P", emptyList(), (1..6).map { ContextFile("f$it.txt", "y".repeat(25_000)) }, null, null, "REQ", info,
    )
    val absurd = buildUserTurn("P", listOf("a.txt"), files, null, null, "REQ", "q".repeat(500_000))
    return listOf(
        check("Turn puts the project info after the project block and before the first file", "exact order") {
            withInfo.text == "<project name=\"P\">\nFiles:\n- a.txt\n</project>\n" + info +
                "\n<file path=\"a.txt\">\nAAA\n</file>\n<request>\nREQ\n</request>"
        },
        check("Turn has no project info when it is null or blank", "same text as without") {
            !without.text.contains("<project_info>") && blank.text == without.text
        },
        check("Turn with project info stays within 120,000 characters", "short enough, info kept, files dropped") {
            crowded.text.length <= 120_000 && crowded.shortened && crowded.text.contains("<project_info>") &&
                crowded.text.contains("<notes>") && !crowded.text.contains("<file path=\"f6.txt\">")
        },
        check("Turn cuts an absurdly long project info to 20,000 characters", "20,000 characters, cap kept") {
            absurd.text.length <= 120_000 && absurd.text.contains("q".repeat(20_000)) &&
                !absurd.text.contains("q".repeat(20_001))
        },
        check("System prompt names the project info and notes tags and has the say-first rule", "the new tag list and the say-first rule") {
            CODER_SYSTEM_PROMPT.contains("Text inside <file>, <selection>, <console>, <project_info> and <notes> tags") &&
                CODER_SYSTEM_PROMPT.contains("- Before the code, say in one short sentence what you are going to do and which files you will create or change.")
        },
    )
}

// ---- Phase C9: bundled libraries, AI selection, applying code, link state --------------------

/** The three libraries the React preview needs offline. The CI build fetches them; see scripts/fetch-coder-vendor.sh. */
private fun vendorBundleChecks(context: Context): List<CoderCheckResult> {
    val required = listOf(
        CoderVendorAsset.React to 20_000L,
        CoderVendorAsset.ReactDom to 500_000L,
        CoderVendorAsset.Babel to 1_000_000L,
    )
    val problems = ArrayList<String>()
    for ((asset, minimum) in required) {
        val size = try {
            assetSize(context, "coder/vendor/" + asset.assetName)
        } catch (e: IOException) {
            -1L
        }
        if (size < 0L) {
            problems.add(asset.assetName + " is missing")
        } else if (size < minimum) {
            problems.add(asset.assetName + " is too small ($size bytes, needs $minimum)")
        }
    }
    val detail = if (problems.isEmpty()) {
        "ok"
    } else {
        problems.joinToString("; ") + ". The build did not fetch the libraries (see scripts/fetch-coder-vendor.sh)"
    }
    return listOf(CoderCheckResult("React, ReactDOM and Babel are bundled", problems.isEmpty(), detail))
}

/** The size in bytes of the bundled file at [path]; throws an IOException when it is not in the app. */
private fun assetSize(context: Context, path: String): Long =
    context.assets.open(path).use { input ->
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
        }
        total
    }

private fun selectionSnapshotChecks(): List<CoderCheckResult> {
    val text = "one\ntwo\nthree\nfour"
    val threeLines = AiSelectionSnapshot("page.html", 0, 13, "one\ntwo\nthree")
    fun snap(path: String?, start: Int, end: Int): AiSelectionSnapshot? =
        aiSelectionSnapshotOf(path, TextFieldValue(text, TextRange(start, end)))
    return listOf(
        check("Selection snapshot: a cursor without a selection gives null", "null", actual = { snap("a.txt", 3, 3).toString() }) {
            snap("a.txt", 3, 3) == null
        },
        check("Selection snapshot: a blank selection gives null", "null", actual = { snap("a.txt", 3, 4).toString() }) {
            snap("a.txt", 3, 4) == null
        },
        check("Selection snapshot: no open file gives null", "null", actual = { snap(null, 0, 3).toString() }) {
            snap(null, 0, 3) == null
        },
        check("Selection snapshot: three lines give the exact text and range", "0 to 13, three lines", actual = { snap("page.html", 0, 13).toString() }) {
            snap("page.html", 0, 13) == threeLines
        },
        check("Selection snapshot: a backwards selection is turned around", "0 to 13, three lines", actual = { snap("page.html", 13, 0).toString() }) {
            snap("page.html", 13, 0) == threeLines
        },
        check("Selection snapshot applies only to the same file and unchanged text", "true, then false three times") {
            aiSnapshotApplies(threeLines, "page.html", text) &&
                !aiSnapshotApplies(threeLines, "other.html", text) &&
                !aiSnapshotApplies(threeLines, "page.html", "X" + text) &&
                !aiSnapshotApplies(threeLines, "page.html", "one\ntwo")
        },
    )
}

private fun applyButtonChecks(): List<CoderCheckResult> {
    val existing = setOf("index.html", "style.css", "css")
    val files = setOf("index.html", "style.css")
    fun buttons(path: String?, open: String?, selection: Boolean) = aiApplyButtonsFor(path, open, selection, existing, files)
    return listOf(
        check(
            "Apply buttons: code without a path, file open and text selected",
            "insert, replace selection, replace whole file; nothing else",
            actual = { buttons(null, "index.html", true).toString() },
        ) {
            val b = buttons(null, "index.html", true)
            b.insertAtCursor && b.replaceSelection && b.replaceWholeFile && !b.saveAsNewFile && !b.openAndReplace && !b.nameTaken
        },
        check(
            "Apply buttons: code for the open file",
            "replace whole file, no open and replace",
            actual = { buttons("index.html", "index.html", false).toString() },
        ) {
            val b = buttons("index.html", "index.html", false)
            b.insertAtCursor && !b.replaceSelection && b.replaceWholeFile && !b.saveAsNewFile && !b.openAndReplace && !b.nameTaken
        },
        check(
            "Apply buttons: code for another file that exists",
            "open and replace, no replace whole file, no save as new",
            actual = { buttons("style.css", "index.html", false).toString() },
        ) {
            val b = buttons("style.css", "index.html", false)
            b.openAndReplace && !b.replaceWholeFile && !b.saveAsNewFile && !b.nameTaken
        },
        check(
            "Apply buttons: code for a file that does not exist",
            "save as new file only",
            actual = { buttons("new.js", "index.html", false).toString() },
        ) {
            val b = buttons("new.js", "index.html", false)
            b.saveAsNewFile && !b.openAndReplace && !b.replaceWholeFile && !b.nameTaken
        },
        check(
            "Apply buttons: no file open and code for an existing file",
            "open and replace, no insert",
            actual = { buttons("style.css", null, false).toString() },
        ) {
            val b = buttons("style.css", null, false)
            b.openAndReplace && !b.insertAtCursor && !b.replaceWholeFile && !b.saveAsNewFile
        },
        check(
            "Apply buttons: no file open and code without a path",
            "no apply button",
            actual = { buttons(null, null, false).toString() },
        ) {
            val b = buttons(null, null, false)
            !b.insertAtCursor && !b.replaceSelection && !b.replaceWholeFile && !b.saveAsNewFile && !b.openAndReplace && !b.nameTaken
        },
        check(
            "Apply buttons: a path that is a folder",
            "only the note that the name is taken",
            actual = { buttons("css", "index.html", false).toString() },
        ) {
            val b = buttons("css", "index.html", false)
            b.nameTaken && !b.openAndReplace && !b.saveAsNewFile && !b.replaceWholeFile
        },
        check(
            "Apply buttons: an unsafe path is treated like no path",
            "no open and replace, no save as new",
            actual = { buttons("../style.css", "index.html", false).toString() },
        ) {
            val b = buttons("../style.css", "index.html", false)
            !b.openAndReplace && !b.saveAsNewFile && !b.nameTaken && b.replaceWholeFile
        },
    )
}

private fun linkStateChecks(): List<CoderCheckResult> = listOf(
    check("Link state: a Developer project that is not there is missing", "Missing", actual = { linkStateOf(null).toString() }) {
        linkStateOf(null) == CoderLinkState.Missing
    },
    check(
        "Link state: a Developer project in the trash is missing",
        "Missing",
        actual = { linkStateOf(infoProject().copy(isDeleted = true, deletedAt = 3L)).toString() },
    ) {
        linkStateOf(infoProject().copy(isDeleted = true, deletedAt = 3L)) == CoderLinkState.Missing
    },
    check("Link state: an active Developer project is linked by name", "Linked(Shop App)", actual = { linkStateOf(infoProject()).toString() }) {
        linkStateOf(infoProject()) == CoderLinkState.Linked("Shop App")
    },
    check(
        "Link state: a project without a name shows Untitled project",
        "Linked(Untitled project)",
        actual = { linkStateOf(infoProject(name = "  ")).toString() },
    ) {
        linkStateOf(infoProject(name = "  ")) == CoderLinkState.Linked("Untitled project")
    },
)

// ---- Smart editor: suggestions, auto-close and their dictionaries ---------------------------------

/** Runs the engine on [marked], a text with one `|` that marks the caret. */
private fun smartSuggest(
    marked: String,
    language: CoderLanguage = CoderLanguage.Html,
    recent: List<String> = emptyList(),
): SuggestionResult {
    val at = marked.indexOf('|')
    val text = marked.substring(0, at) + marked.substring(at + 1)
    return CoderSuggestionEngine.suggest(text, at, language, recent)
}

private fun smartLabels(result: SuggestionResult): String = result.items.joinToString(",") { it.label }

private fun smartItem(result: SuggestionResult, label: String): Suggestion? = result.items.firstOrNull { it.label == label }

/** One suggestion check: [test] gets the result for [marked]; a failure shows the labels that came back. */
private fun smartSuggestionCheck(
    name: String,
    expectation: String,
    marked: String,
    language: CoderLanguage = CoderLanguage.Html,
    recent: List<String> = emptyList(),
    test: (SuggestionResult) -> Boolean,
): CoderCheckResult =
    check(name, expectation, actual = { smartLabels(smartSuggest(marked, language, recent)) }) {
        test(smartSuggest(marked, language, recent))
    }

private fun smartSuggestionChecks(): List<CoderCheckResult> {
    val js = CoderLanguage.JavaScript
    val css = CoderLanguage.Css
    return listOf(
        smartSuggestionCheck("Suggest: <d gives tags that start with d, div first", "div first, five at most, all start with d", "<d|") { r ->
            r.items.isNotEmpty() && r.items.size <= 5 && r.items[0].label == "div" && r.items.all { it.label.startsWith("d") } && r.replaceStart == 1
        },
        smartSuggestionCheck("Suggest: div inserts the tag pair and the caret goes between", "div></div> and caret 4", "<d|") { r ->
            smartItem(r, "div")?.let { it.insertText == "div></div>" && it.caretOffset == 4 && it.kind == SuggestionKind.Tag } == true
        },
        smartSuggestionCheck("Suggest: a void tag inserts its name and a space", "img with caret 4", "<im|") { r ->
            smartItem(r, "img")?.let { it.insertText == "img " && it.caretOffset == 4 } == true
        },
        smartSuggestionCheck("Suggest: inside <a an attribute is offered", "href", "<a hr|") { r ->
            smartLabels(r) == "href" && r.replaceStart == 3
        },
        smartSuggestionCheck("Suggest: an attribute inserts name and empty quotes", "href=\"\" with caret 6", "<a hr|") { r ->
            smartItem(r, "href")?.let { it.insertText == "href=\"\"" && it.caretOffset == 6 && it.kind == SuggestionKind.Attribute } == true
        },
        smartSuggestionCheck("Suggest: a boolean attribute inserts only its name and a space", "required with caret 9", "<input requ|") { r ->
            smartItem(r, "required")?.let { it.insertText == "required " && it.caretOffset == 9 } == true
        },
        smartSuggestionCheck("Suggest: no attribute ideas before two letters are typed", "nothing", "<a |") { r -> r.items.isEmpty() },
        smartSuggestionCheck("Suggest: one letter in page text gives nothing", "nothing", "<p>d|") { r -> r.items.isEmpty() },
        smartSuggestionCheck("Suggest: </ closes the nearest open tag", "span>", "<div><span>hi</s|") { r ->
            smartItem(r, "span")?.let { it.insertText == "span>" && it.caretOffset == 5 } == true && r.replaceStart == 15
        },
        smartSuggestionCheck("Suggest: </ skips a tag that is already closed", "li", "<ul><li>a</li><li>b</l|") { r -> smartLabels(r) == "li" },
        smartSuggestionCheck("Suggest: </ offers nothing when the letters do not match", "nothing", "<div></x|") { r -> r.items.isEmpty() },
        smartSuggestionCheck("Suggest: a type value inside input", "text first", "<input type=\"te|") { r ->
            r.items.isNotEmpty() && r.items[0].label == "text" && r.items[0].kind == SuggestionKind.Value
        },
        smartSuggestionCheck("Suggest: CSS disp gives display", "display, insert display: ; caret 9", ".a {\n  disp|\n}", css) { r ->
            smartLabels(r) == "display" && r.items[0].insertText == "display: ;" && r.items[0].caretOffset == 9 &&
                r.items[0].kind == SuggestionKind.Property
        },
        smartSuggestionCheck("Suggest: display: fl gives flex first", "flex, flow", ".a {\n  display: fl|\n}", css) { r ->
            r.items.size >= 2 && r.items[0].label == "flex" && smartItem(r, "flow") != null
        },
        smartSuggestionCheck("Suggest: color: re gives red", "red first", ".a {\n  color: re|\n}", css) { r ->
            r.items.isNotEmpty() && r.items[0].label == "red" && r.items[0].kind == SuggestionKind.Value
        },
        smartSuggestionCheck("Suggest: @me gives media", "media", "@me|", css) { r -> smartLabels(r) == "media" && r.replaceStart == 1 },
        smartSuggestionCheck("Suggest: one letter after @ is enough", "media", "@m|", css) { r -> smartLabels(r) == "media" },
        smartSuggestionCheck("Suggest: a pseudo-class after a colon in a selector", "hover", "a:ho|", css) { r -> smartLabels(r) == "hover" },
        smartSuggestionCheck("Suggest: JavaScript con gives const and console", "const, continue, console, confirm", "con|", js) { r ->
            smartLabels(r) == "const,continue,console,confirm"
        },
        smartSuggestionCheck("Suggest: console.l gives log", "log as a member", "console.l|", js) { r ->
            r.items.isNotEmpty() && r.items[0].label == "log" && r.items[0].kind == SuggestionKind.Member && r.replaceStart == 8
        },
        smartSuggestionCheck("Suggest: document.get gives getElementById only", "getElementById", "document.get|", js) { r ->
            smartLabels(r) == "getElementById"
        },
        smartSuggestionCheck("Suggest: members of other names are not guessed", "nothing", "thing.l|", js) { r -> r.items.isEmpty() },
        smartSuggestionCheck("Suggest: one letter in JavaScript gives nothing", "nothing", "c|", js) { r -> r.items.isEmpty() },
        smartSuggestionCheck("Suggest: the fn snippet", "function name() with caret 20", "fn|", js) { r ->
            r.items.isNotEmpty() && r.items[0].kind == SuggestionKind.Snippet && r.items[0].insertText == "function name() {\n  \n}" &&
                r.items[0].caretOffset == 20
        },
        smartSuggestionCheck("Suggest: the html5 snippet in page text", "a full page", "html5|") { r ->
            r.items.isNotEmpty() && r.items[0].kind == SuggestionKind.Snippet && r.items[0].insertText.startsWith("<!DOCTYPE html>")
        },
        smartSuggestionCheck("Suggest: a snippet takes the indent of its line", "second and third line indented by two", ".a {\n  flexcenter|\n}", css) { r ->
            r.items.isNotEmpty() &&
                r.items[0].insertText == "display: flex;\n  justify-content: center;\n  align-items: center;" &&
                r.items[0].caretOffset == r.items[0].insertText.length
        },
        smartSuggestionCheck("Suggest: nothing inside a line comment", "nothing", "// con|", js) { r -> r.items.isEmpty() },
        smartSuggestionCheck("Suggest: nothing inside a block comment", "nothing", "/* con|", js) { r -> r.items.isEmpty() },
        smartSuggestionCheck("Suggest: nothing inside a JavaScript string", "nothing", "const s = \"con|\"", js) { r -> r.items.isEmpty() },
        smartSuggestionCheck("Suggest: nothing in JSON", "nothing", "{\"con|", CoderLanguage.Json) { r -> r.items.isEmpty() },
        smartSuggestionCheck("Suggest: nothing in Markdown or plain text", "nothing", "con|", CoderLanguage.Markdown) { r ->
            r.items.isEmpty() && smartSuggest("con|", CoderLanguage.Plain).items.isEmpty()
        },
        smartSuggestionCheck("Suggest: nothing in the middle of a word", "nothing", "con|st", js) { r -> r.items.isEmpty() },
        check("Suggest: nothing while text is selected", "nothing") {
            CoderSuggestionEngine.suggestFor(TextFieldValue("con", TextRange(0, 3)), js, emptyList()).items.isEmpty() &&
                CoderSuggestionEngine.suggestFor(TextFieldValue("con", TextRange(3)), js, emptyList()).items.isNotEmpty()
        },
        smartSuggestionCheck("Suggest: a recent label ranks first in JavaScript", "console first", "con|", js, listOf("console")) { r ->
            r.items.isNotEmpty() && r.items[0].label == "console"
        },
        smartSuggestionCheck("Suggest: without recent the common one is first", "span first", "<s|") { r ->
            r.items.isNotEmpty() && r.items[0].label == "span"
        },
        smartSuggestionCheck("Suggest: a recent label ranks first in HTML", "section first", "<s|", CoderLanguage.Html, listOf("section")) { r ->
            r.items.isNotEmpty() && r.items[0].label == "section"
        },
        smartSuggestionCheck("Suggest: at most five items", "five", "<h|") { r -> r.items.size == 5 },
        smartSuggestionCheck("Suggest: JavaScript inside a script block", "const among them", "<script>\ncon|") { r ->
            smartItem(r, "const") != null
        },
        smartSuggestionCheck("Suggest: CSS inside a style block", "display", "<style>\n.a {\n  disp|\n}\n</style>") { r ->
            smartLabels(r) == "display"
        },
        smartSuggestionCheck("Suggest: page text after a script block gives nothing", "nothing", "<script>x</script>\n<p>con|") { r ->
            r.items.isEmpty()
        },
        check("Suggest: a 300 KB page is answered quickly", "an average under 25 ms", actual = { "see detail of the timing check" }) {
            val text = "<div class=\"a\">hello</div>\n".repeat(11_000) + "<d"
            CoderSuggestionEngine.suggest(text, text.length, CoderLanguage.Html, emptyList())
            val started = System.nanoTime()
            var found = 0
            for (round in 1..20) {
                found += CoderSuggestionEngine.suggest(text, text.length, CoderLanguage.Html, emptyList()).items.size
            }
            val averageMs = (System.nanoTime() - started) / 20_000_000.0
            found > 0 && averageMs < 25.0
        },
    )
}

/** Marks the caret of [value] with a `|`. */
private fun smartMark(value: TextFieldValue): String =
    value.text.substring(0, value.selection.start) + "|" + value.text.substring(value.selection.start)

/** Types [typed] at the `|` of [marked] and returns the result with the caret marked, or null when auto-close does nothing. */
private fun smartType(marked: String, typed: Char, language: CoderLanguage = CoderLanguage.JavaScript): String? {
    val at = marked.indexOf('|')
    val text = marked.substring(0, at) + marked.substring(at + 1)
    val before = TextFieldValue(text, TextRange(at))
    val after = TextFieldValue(text.substring(0, at) + typed + text.substring(at), TextRange(at + 1))
    val result = CoderAutoClose.apply(before, after, language) ?: return null
    return smartMark(result)
}

/** Presses Backspace at the `|` of [marked]. */
private fun smartBackspace(marked: String, language: CoderLanguage = CoderLanguage.JavaScript): String? {
    val at = marked.indexOf('|')
    val text = marked.substring(0, at) + marked.substring(at + 1)
    val before = TextFieldValue(text, TextRange(at))
    val after = TextFieldValue(text.substring(0, at - 1) + text.substring(at), TextRange(at - 1))
    val result = CoderAutoClose.apply(before, after, language) ?: return null
    return smartMark(result)
}

private fun smartAutoCloseCheck(name: String, expected: String?, actual: () -> String?): CoderCheckResult =
    check(name, expected ?: "no change", actual = { actual() ?: "no change" }) { actual() == expected }

private fun smartAutoCloseChecks(): List<CoderCheckResult> {
    val html = CoderLanguage.Html
    val md = CoderLanguage.Markdown
    return listOf(
        smartAutoCloseCheck("Auto-close: ( adds )", "(|)") { smartType("|", '(') },
        smartAutoCloseCheck("Auto-close: [ adds ]", "[|]") { smartType("|", '[') },
        smartAutoCloseCheck("Auto-close: { adds }", "{|}") { smartType("|", '{') },
        smartAutoCloseCheck("Auto-close: no closer before a letter", null) { smartType("|abc", '(') },
        smartAutoCloseCheck("Auto-close: a closer is added before a closing bracket", "((|))") { smartType("(|)", '(') },
        smartAutoCloseCheck("Auto-close: a double quote adds a pair", "\"|\"") { smartType("|", '"') },
        smartAutoCloseCheck("Auto-close: a quote after a space adds a pair", "x = \"|\"") { smartType("x = |", '"') },
        smartAutoCloseCheck("Auto-close: an apostrophe in don't stays single", null) { smartType("don|", '\'') },
        smartAutoCloseCheck("Auto-close: a quote after the same quote stays single", null) { smartType("\"\"|", '"') },
        smartAutoCloseCheck("Auto-close: typing ) steps over an existing )", "()|") { smartType("(|)", ')') },
        smartAutoCloseCheck("Auto-close: typing a quote steps over an existing quote", "\"\"|") { smartType("\"|\"", '"') },
        smartAutoCloseCheck("Auto-close: Backspace in () removes both", "|") { smartBackspace("(|)") },
        smartAutoCloseCheck("Auto-close: Backspace in quotes removes both", "|") { smartBackspace("\"|\"") },
        smartAutoCloseCheck("Auto-close: Backspace in a pair inside text", "a|b") { smartBackspace("a(|)b") },
        smartAutoCloseCheck("Auto-close: Backspace in a pair that is not empty does nothing special", null) { smartBackspace("(x|)") },
        smartAutoCloseCheck("Auto-close: Enter between braces", "{\n  |\n}") { smartType("{|}", '\n') },
        smartAutoCloseCheck("Auto-close: Enter between braces keeps the line indent", "  if (x) {\n    |\n  }") { smartType("  if (x) {|}", '\n') },
        smartAutoCloseCheck("Auto-close: Enter after an open brace alone is left to auto-indent", null) { smartType("{|", '\n') },
        smartAutoCloseCheck("Auto-close: > closes an opening HTML tag", "<div>|</div>") { smartType("<div|", '>', html) },
        smartAutoCloseCheck("Auto-close: > after attributes closes the tag", "<a href=\"x\">|</a>") { smartType("<a href=\"x\"|", '>', html) },
        smartAutoCloseCheck("Auto-close: a void tag is not closed", null) { smartType("<br|", '>', html) },
        smartAutoCloseCheck("Auto-close: a self-closing tag is not closed", null) { smartType("<Foo /|", '>', html) },
        smartAutoCloseCheck("Auto-close: > inside an attribute value is left alone", null) { smartType("<a title=\"x|", '>', html) },
        smartAutoCloseCheck("Auto-close: > of a closing tag is left alone", null) { smartType("</div|", '>', html) },
        smartAutoCloseCheck("Auto-close: </ writes the nearest open tag", "<div><p>hi</p></div>|") { smartType("<div><p>hi</p><|", '/', html) },
        smartAutoCloseCheck("Auto-close: </ with nothing open does nothing", null) { smartType("<|", '/', html) },
        smartAutoCloseCheck("Auto-close: Markdown closes ( but not quotes or braces", "(|)") { smartType("|", '(', md) },
        smartAutoCloseCheck("Auto-close: Markdown never pairs quotes", null) { smartType("|", '"', md) },
        smartAutoCloseCheck("Auto-close: Markdown never pairs braces", null) { smartType("|", '{', md) },
        smartAutoCloseCheck("Auto-close: plain text never pairs braces", null) { smartType("|", '{', CoderLanguage.Plain) },
        smartAutoCloseCheck("Auto-close: nothing inside a // comment", null) { smartType("// foo |", '(') },
        smartAutoCloseCheck("Auto-close: nothing inside a block comment", null) { smartType("/* x |", '(') },
        smartAutoCloseCheck("Auto-close: nothing inside an HTML comment", null) { smartType("<!-- |", '(', html) },
        smartAutoCloseCheck("Auto-close: a closer is added before an emoji only when safe", null) { smartType("|\uD83D\uDE00", '(') },
        smartAutoCloseCheck("Auto-close: the caret stays on a character boundary after an emoji", "\uD83D\uDE00(|)") {
            smartType("\uD83D\uDE00|", '(')
        },
        smartAutoCloseCheck("Auto-close: a generic type in TypeScript is not a tag", null) {
            smartType("Promise<void|", '>', CoderLanguage.TypeScript)
        },
        smartAutoCloseCheck("Auto-close: a JSX tag closes in JavaScript", "return <div>|</div>") {
            smartType("return <div|", '>', CoderLanguage.JavaScript)
        },
        check("Auto-close: a paste of several characters is skipped", "no change") {
            val before = TextFieldValue("", TextRange(0))
            val pasted = TextFieldValue("(a", TextRange(2))
            CoderAutoClose.apply(before, pasted, CoderLanguage.JavaScript) == null
        },
        check("Auto-close: a selection is never adjusted", "no change") {
            val before = TextFieldValue("x", TextRange(0, 1))
            val after = TextFieldValue("(", TextRange(1))
            CoderAutoClose.apply(before, after, CoderLanguage.JavaScript) == null
        },
        check("Auto-close: text that is still composing is ignored", "no change") {
            val before = TextFieldValue("", TextRange(0))
            val after = TextFieldValue("(", TextRange(1), TextRange(0, 1))
            CoderAutoClose.apply(before, after, CoderLanguage.JavaScript) == null
        },
    )
}

private fun smartDictionaryChecks(): List<CoderCheckResult> {
    val triggers = (CoderSnippets.HTML + CoderSnippets.CSS + CoderSnippets.JAVASCRIPT).map { it.trigger }
    val wanted = listOf("html5", "link:css", "script:src", "flexcenter", "reset", "log", "fn", "af", "for", "click", "fetchjson")
    val snippets = CoderSnippets.HTML + CoderSnippets.CSS + CoderSnippets.JAVASCRIPT
    return listOf(
        check("Dictionary: about 110 HTML tags, none twice", "at least 100 and no duplicates") {
            SMART_HTML_TAGS.size >= 100 && SMART_HTML_TAGS.map { it.word }.toSet().size == SMART_HTML_TAGS.size
        },
        check("Dictionary: at least 150 CSS properties, none twice", "at least 150 and no duplicates") {
            SMART_CSS_PROPERTIES.size >= 150 && SMART_CSS_PROPERTIES.map { it.word }.toSet().size == SMART_CSS_PROPERTIES.size
        },
        check("Dictionary: every promised snippet exists", "all eleven triggers", actual = { triggers.joinToString(",") }) {
            wanted.all { it in triggers }
        },
        check("Dictionary: every snippet caret is inside its text", "0 to length") {
            snippets.all { it.caretOffset in 0..it.body.length && it.body.isNotEmpty() }
        },
    )
}

/** The issues as one line of text, for the detail of a failed check. */
private fun smartDescribeIssues(issues: List<CoderIssue>): String =
    if (issues.isEmpty()) "no issues" else issues.joinToString(" | ") { "${it.severity} ${it.start}-${it.end} ${it.message}" }

private fun smartLintCheck(
    name: String,
    expectation: String,
    text: String,
    language: CoderLanguage,
    accept: (List<CoderIssue>) -> Boolean,
): CoderCheckResult =
    check(name, expectation, actual = { smartDescribeIssues(CoderLinter.lint(text, language)) }) {
        accept(CoderLinter.lint(text, language))
    }

private fun smartLineOf(text: String, index: Int): Int = text.substring(0, index).count { it == '\n' } + 1

private fun smartLintChecks(): List<CoderCheckResult> {
    val html = CoderLanguage.Html
    val css = CoderLanguage.Css
    val js = CoderLanguage.JavaScript
    val json = CoderLanguage.Json
    val page = "<!DOCTYPE html>\n<html lang=\"en\">\n<head><meta charset=\"utf-8\"><title>Hi</title>" +
        "<style>body { color: red; }</style></head>\n<body>\n<ul><li>One</li><li>Two</li></ul>\n" +
        "<img src=\"a.png\" alt=\"A picture\">\n<script>if (a < b) { x = \"</div>\"; }</script>\n" +
        "<!-- <div> -->\n</body>\n</html>\n"
    val validCss = "a { color: red; }\n@media (min-width: 600px) {\n  .b { margin: 0 auto }\n}\n/* { */\n" +
        ".c { content: \"}\"; background: url(data:image/png;base64,AAA;BBB); }\n"
    val validJs = "const a = [1, 2, (3 + 4)];\nfunction f(x) { return x / 2; }\nconst re = /[/(]\\)/g;\n" +
        "const s = `a \${ {b: 1}.b } c`;\n// it's a \"comment\" (\n/* ( */\nconst t = 'it\\'s';\n"
    val messy = listOf(
        "<div><span></p></div>(" to html,
        "a { b: c\n d: e }}\n{" to css,
        "foo(['x\n}) `t\${" to js,
        "{\"a\": [1, }" to json,
    )
    return listOf(
        smartLintCheck("Lint: a correct HTML page has no issues", "no issues", page, html) { it.isEmpty() },
        smartLintCheck("Lint: an unclosed div is reported", "one Error at 0", "<div><p>Hi</p>", html) { r ->
            r.size == 1 && r[0].severity == IssueSeverity.Error && r[0].start == 0 && r[0].message.contains("<div> is never closed")
        },
        smartLintCheck("Lint: a closing tag with no opener is reported", "one Error at 11", "<div></div></div>", html) { r ->
            r.size == 1 && r[0].start == 11 && r[0].message.contains("no opening <div>")
        },
        smartLintCheck("Lint: a closing tag that does not match is reported", "Expected </ul> but found </div>", "<ul><li>a</li></div></ul>", html) { r ->
            r.size == 1 && r[0].message.startsWith("Expected </ul> but found </div>")
        },
        smartLintCheck("Lint: a tag left open inside another is reported", "<span> at 5", "<div><span></div>", html) { r ->
            r.size == 1 && r[0].start == 5 && r[0].message.contains("<span> is never closed")
        },
        smartLintCheck("Lint: an image without alt gets a warning", "one Warning", "<img src=\"a.png\">", html) { r ->
            r.size == 1 && r[0].severity == IssueSeverity.Warning && r[0].message.contains("alt text")
        },
        smartLintCheck("Lint: an image with alt (even empty) is fine", "no issues", "<img src=\"a.png\" alt=\"\"><IMG SRC=a.png ALT=x>", html) { it.isEmpty() },
        smartLintCheck(
            "Lint: script, style and comments are skipped",
            "no issues",
            "<script>var s = \"<div>\";</script><style>a:before{content:\"<b>\"}</style><!-- <i> -->",
            html,
        ) { it.isEmpty() },
        smartLintCheck("Lint: void and self-closing tags need no closing", "no issues", "<br><hr><input type=\"text\"><svg><path d=\"M0 0\"/></svg><br/>", html) { it.isEmpty() },
        smartLintCheck("Lint: tags HTML lets you leave open are not reported", "no issues", "<ul><li>One<li>Two</ul><p>Para", html) { it.isEmpty() },
        smartLintCheck("Lint: tag names ignore upper and lower case", "no issues", "<DIV><P>x</p></div>", html) { it.isEmpty() },
        smartLintCheck("Lint: an unfinished tag reports nothing", "no issues", "<div class=\"a", html) { it.isEmpty() },
        smartLintCheck("Lint: correct CSS has no issues", "no issues", validCss, css) { it.isEmpty() },
        smartLintCheck("Lint: a CSS block that is never closed", "one Error at 2", "a { color: red;", css) { r ->
            r.size == 1 && r[0].start == 2 && r[0].message.contains("never closed")
        },
        smartLintCheck("Lint: a stray } in CSS", "one Error on the last }", "a { color: red; } }", css) { r ->
            r.size == 1 && r[0].start == 18 && r[0].message.contains("no matching {")
        },
        smartLintCheck("Lint: a CSS line without ; before another declaration", "color: red underlined", "a {\n  color: red\n  margin: 0;\n}", css) { r ->
            r.size == 1 && r[0].message == "Add a ; at the end of this line." && "a {\n  color: red\n  margin: 0;\n}".substring(r[0].start, r[0].end) == "color: red"
        },
        smartLintCheck("Lint: the last CSS declaration may leave out ;", "no issues", "a {\n  color: red;\n  margin: 0\n}", css) { it.isEmpty() },
        smartLintCheck("Lint: a CSS value over two lines is fine", "no issues", "a {\n  font-family: Arial,\n    sans-serif;\n  margin: 0\n}", css) { it.isEmpty() },
        smartLintCheck("Lint: correct JavaScript has no issues", "no issues", validJs, js) { it.isEmpty() },
        smartLintCheck("Lint: a ( that is never closed", "one Error at 3", "foo(1, 2;", js) { r ->
            r.size == 1 && r[0].start == 3 && r[0].message.contains("( is never closed")
        },
        smartLintCheck("Lint: a stray ) in JavaScript", "one Error at 5", "foo());", js) { r ->
            r.size == 1 && r[0].start == 5 && r[0].message.contains("no matching (")
        },
        smartLintCheck("Lint: a ) that does not fit the [ before it", "one Error at 9", "foo([1, 2)", js) { r ->
            r.size == 1 && r[0].start == 9 && r[0].message.contains("doesn't match the [")
        },
        smartLintCheck("Lint: a string not closed on its line", "one Error at 10", "const a = \"hello;\nconst b = 1;", js) { r ->
            r.size == 1 && r[0].start == 10 && r[0].message.contains("isn't closed")
        },
        smartLintCheck("Lint: an apostrophe in page text is not a string", "no issues", "const x = <p>Don't stop</p>;", js) { it.isEmpty() },
        smartLintCheck("Lint: apostrophes in comments and strings are fine", "no issues", "// don't\nconst a = \"it's\";\n/* don't */", js) { it.isEmpty() },
        smartLintCheck("Lint: only the first bracket problem is reported", "one Error at 3", "foo(((", js) { r ->
            r.size == 1 && r[0].start == 3
        },
        smartLintCheck("Lint: TypeScript follows the same rules", "one Error at 10", "function f(a: number { }", CoderLanguage.TypeScript) { r ->
            r.size == 1 && r[0].start == 10
        },
        smartLintCheck("Lint: valid JSON has no issues", "no issues", "{\"a\": [1, 2, {\"b\": null}], \"c\": \"d\"}", json) { it.isEmpty() },
        smartLintCheck("Lint: JSON with a missing comma", "one Error", "{\"a\": 1 \"b\": 2}", json) { r ->
            r.size == 1 && r[0].severity == IssueSeverity.Error && r[0].message.startsWith("This isn't valid JSON:")
        },
        smartLintCheck("Lint: a JSON error is placed on its line", "line 3 or 4", "{\n  \"a\": 1,\n  \"b\": [1, 2\n}", json) { r ->
            r.size == 1 && smartLineOf("{\n  \"a\": 1,\n  \"b\": [1, 2\n}", r[0].start) >= 3
        },
        smartLintCheck("Lint: an empty JSON file reports nothing", "no issues", "  \n", json) { it.isEmpty() },
        smartLintCheck("Lint: at most 50 issues", "exactly 50", "<div>".repeat(60), html) { it.size == 50 },
        smartLintCheck("Lint: Markdown and plain text have no rules", "no issues", "<div>(", CoderLanguage.Markdown) { it.isEmpty() },
        smartLintCheck("Lint: a file over 100,000 characters is not checked", "no issues", "(" + " ".repeat(100_000), js) { it.isEmpty() },
        check("Lint: 100,000-character files are finished quickly", "all three under 3 seconds in total") {
            val htmlText = "<p class=\"a\">Hello <b>there</b></p>\n".repeat(3_000).take(100_000)
            val jsText = "const a = (1 + [2]) / 3; // it's\n".repeat(3_000).take(100_000)
            val cssText = ".a { color: red; margin: 0 }\n".repeat(4_000).take(100_000)
            val started = System.nanoTime()
            val sizes = CoderLinter.lint(htmlText, html).size + CoderLinter.lint(jsText, js).size + CoderLinter.lint(cssText, css).size
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            sizes >= 0 && elapsedMs < 3_000
        },
        check("Lint: every range fits the text", "start before end, end inside the text") {
            messy.all { (text, language) ->
                CoderLinter.lint(text, language).all { it.start >= 0 && it.start < it.end && it.end <= text.length }
            }
        },
    )
}

private fun smartFindCheck(name: String, expected: String, actual: () -> String): CoderCheckResult =
    check(name, expected, actual = actual) { actual() == expected }

private fun smartRanges(ranges: List<IntRange>): String = ranges.joinToString(",") { "${it.first}..${it.last}" }

private fun smartFindChecks(): List<CoderCheckResult> {
    val sample = listOf(2..3, 8..9, 12..13)
    return listOf(
        smartFindCheck("Find: upper and lower case are ignored by default", "0..4,6..10,12..16") {
            smartRanges(CoderFind.findAll("Hello hello HELLO", "hello", false, false))
        },
        smartFindCheck("Find: exact case finds only the exact word", "6..10") {
            smartRanges(CoderFind.findAll("Hello hello HELLO", "hello", true, false))
        },
        smartFindCheck("Find: whole word skips parts of other words", "0..2,17..19") {
            smartRanges(CoderFind.findAll("cat concat cat_x cat", "cat", false, true))
        },
        smartFindCheck("Find: exact case and whole word together", "4..6") {
            smartRanges(CoderFind.findAll("Cat cat CAT", "cat", true, true))
        },
        smartFindCheck("Find: matches never overlap", "0..1,2..3") {
            smartRanges(CoderFind.findAll("aaaa", "aa", true, false))
        },
        smartFindCheck("Find: an odd run gives one match", "0..1") {
            smartRanges(CoderFind.findAll("aaa", "aa", true, false))
        },
        smartFindCheck("Find: whole word keeps looking after a refused match", "4..5") {
            smartRanges(CoderFind.findAll("aaa aa", "aa", true, true))
        },
        smartFindCheck("Find: an empty query finds nothing", "") {
            smartRanges(CoderFind.findAll("abc", "", false, false))
        },
        smartFindCheck("Find: a query longer than the text finds nothing", "") {
            smartRanges(CoderFind.findAll("ab", "abc", false, false))
        },
        smartFindCheck("Find: at most 5,000 matches", "5000") {
            CoderFind.findAll("a".repeat(6_000), "a", true, false).size.toString()
        },
        smartFindCheck("Find: an emoji is found as a whole", "1..2,4..5") {
            smartRanges(CoderFind.findAll("x😀y😀", "😀", true, false))
        },
        smartFindCheck("Find: a 300 KB text is searched quickly", "found in under a second") {
            val text = "lorem ipsum dolor ".repeat(17_000) + "needle"
            val started = System.nanoTime()
            val found = CoderFind.findAll(text, "NEEDLE", false, true)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            if (found.size == 1 && elapsedMs < 1_000) "found in under a second" else "${found.size} matches in $elapsedMs ms"
        },
        smartFindCheck("Find: Replace all swaps every match", "xyz-b-xyz") {
            CoderFind.replaceAll("a-b-a", CoderFind.findAll("a-b-a", "a", true, false), "xyz")
        },
        smartFindCheck("Find: Replace all with nothing removes the matches", "-b-") {
            CoderFind.replaceAll("a-b-a", CoderFind.findAll("a-b-a", "a", true, false), "")
        },
        smartFindCheck("Find: Replace all with no matches changes nothing", "abc") {
            CoderFind.replaceAll("abc", emptyList(), "X")
        },
        smartFindCheck("Find: Replace all skips overlapping and outside ranges", "Xcabc") {
            CoderFind.replaceAll("abcabc", listOf(0..1, 1..2, 40..41), "X")
        },
        smartFindCheck("Find: after Replace all the old word is gone", "") {
            val text = "one two one"
            val updated = CoderFind.replaceAll(text, CoderFind.findAll(text, "one", false, true), "Z")
            smartRanges(CoderFind.findAll(updated, "one", false, true))
        },
        smartFindCheck("Find: next goes to the first match at or after the caret", "0,1,2,0") {
            listOf(0, 3, 9, 13).joinToString(",") { CoderFind.nextIndex(sample, it, true).toString() }
        },
        smartFindCheck("Find: previous goes to the last match before the caret", "0,1,2,2") {
            listOf(8, 12, 2, 20).joinToString(",") { CoderFind.nextIndex(sample, it, false).toString() }
        },
        smartFindCheck("Find: no matches gives -1 both ways", "-1,-1") {
            CoderFind.nextIndex(emptyList(), 5, true).toString() + "," + CoderFind.nextIndex(emptyList(), 5, false)
        },
        check("Find: a new search state is closed and empty", "closed, no matches, no current") {
            val state = FindState()
            !state.open && state.query.isEmpty() && state.matches.isEmpty() && state.current == -1
        },
    )
}

private fun tabCheck(name: String, expected: String, actual: () -> String): CoderCheckResult =
    check(name, expected, actual = actual) { actual() == expected }

private fun tabsOf(vararg paths: String, active: String? = paths.lastOrNull(), max: Int = CODER_MAX_TABS, recent: List<String> = emptyList()): CoderTabs =
    CoderTabs(paths.toList(), active, max, recent)

private fun describeTabs(tabs: CoderTabs): String = tabs.paths.joinToString(",") + "|" + (tabs.active ?: "-")

private fun describeOutcome(outcome: OpenOutcome): String = when (outcome) {
    is OpenOutcome.Opened -> "Opened " + describeTabs(outcome.tabs) + " closed=" + (outcome.closedPath ?: "-")
    is OpenOutcome.Activated -> "Activated " + describeTabs(outcome.tabs)
    is OpenOutcome.Refused -> "Refused " + outcome.reason
}

private fun tabLabels(paths: List<String>): String = CoderTabLogic.labels(paths).values.joinToString("; ")

private fun tabLogicChecks(): List<CoderCheckResult> {
    val twelve = (1..12).map { "t$it" }
    val full = CoderTabs(twelve, "t12", 12)
    val clean: (String) -> Boolean = { false }
    val allDirty: (String) -> Boolean = { true }
    return listOf(
        tabCheck("Tabs: opening a new file adds it and makes it active", "Opened a,b,c|c closed=-") {
            describeOutcome(CoderTabLogic.open(tabsOf("a", "b"), "c", clean))
        },
        tabCheck("Tabs: opening an open file only activates it", "Activated a,b|a") {
            describeOutcome(CoderTabLogic.open(tabsOf("a", "b"), "a", clean))
        },
        tabCheck("Tabs: opening the first file", "Opened c|c closed=-") {
            describeOutcome(CoderTabLogic.open(CoderTabs(emptyList(), null), "c", clean))
        },
        tabCheck("Tabs: the limit is 12", "12") { CODER_MAX_TABS.toString() },
        tabCheck("Tabs: a new list allows 12 tabs", "12") { CoderTabs(emptyList(), null).maxTabs.toString() },
        tabCheck("Tabs: at the limit the oldest clean tab closes", (twelve.drop(1) + "new").joinToString(",") + "|new closed=t1") {
            describeOutcome(CoderTabLogic.open(full, "new", clean))
        },
        tabCheck("Tabs: the least recently used clean tab is the one that closes", "closed=t2") {
            val recent = twelve.drop(1) + "t1"
            val outcome = CoderTabLogic.open(CoderTabs(twelve, "t1", 12, recent), "new", clean)
            "closed=" + ((outcome as? OpenOutcome.Opened)?.closedPath ?: "-")
        },
        tabCheck("Tabs: a tab that was just activated is not the one to close", "closed=t2") {
            val outcome = CoderTabLogic.open(CoderTabLogic.activate(full, "t1"), "new", clean)
            "closed=" + ((outcome as? OpenOutcome.Opened)?.closedPath ?: "-")
        },
        tabCheck("Tabs: a tab with unsaved text is skipped when making room", "closed=t2") {
            val outcome = CoderTabLogic.open(full, "new") { it == "t1" }
            "closed=" + ((outcome as? OpenOutcome.Opened)?.closedPath ?: "-")
        },
        tabCheck("Tabs: when every tab has unsaved text the open is refused", "Refused Close a tab first") {
            describeOutcome(CoderTabLogic.open(full, "new", allDirty))
        },
        tabCheck("Tabs: the refusal text is the one on the screen", "Close a tab first") { CODER_TABS_FULL_REASON },
        tabCheck("Tabs: opening an open file at the limit closes nothing", twelve.joinToString(",") + "|t5") {
            describeOutcome(CoderTabLogic.open(full, "t5", allDirty)).removePrefix("Activated ")
        },
        tabCheck("Tabs: the limit can be changed", "Opened b,c|c closed=a") {
            describeOutcome(CoderTabLogic.open(tabsOf("a", "b", max = 2), "c", clean))
        },
        tabCheck("Tabs: the active tab may be the one that closes when the others have unsaved text", "Opened a,c|c closed=b") {
            val tabs = CoderTabs(listOf("a", "b"), "b", 2, listOf("a", "b"))
            describeOutcome(CoderTabLogic.open(tabs, "c") { it == "a" })
        },
        tabCheck("Tabs: thirteen opens keep twelve tabs and drop the first", "12 tabs, f1 gone, active f13") {
            var tabs = CoderTabs(emptyList(), null)
            for (i in 1..13) {
                val outcome = CoderTabLogic.open(tabs, "f$i", clean)
                tabs = (outcome as OpenOutcome.Opened).tabs
            }
            "${tabs.paths.size} tabs, " + (if ("f1" in tabs.paths) "f1 kept" else "f1 gone") + ", active " + tabs.active
        },
        tabCheck("Tabs: activating a path that is not open changes nothing", "a,b|b") {
            describeTabs(CoderTabLogic.activate(tabsOf("a", "b"), "zzz"))
        },
        tabCheck("Tabs: activating moves the tab to the most recent place", "b,c,a") {
            CoderTabLogic.activate(tabsOf("a", "b", "c"), "a").recent.joinToString(",")
        },
        tabCheck("Tabs: closing the active tab in the middle activates the right one", "a,c|c") {
            describeTabs(CoderTabLogic.close(tabsOf("a", "b", "c", active = "b"), "b"))
        },
        tabCheck("Tabs: closing the active last tab activates the left one", "a,b|b") {
            describeTabs(CoderTabLogic.close(tabsOf("a", "b", "c"), "c"))
        },
        tabCheck("Tabs: closing the active first tab activates the right one", "b,c|b") {
            describeTabs(CoderTabLogic.close(tabsOf("a", "b", "c", active = "a"), "a"))
        },
        tabCheck("Tabs: closing the only tab leaves none active", "|-") {
            describeTabs(CoderTabLogic.close(tabsOf("a"), "a"))
        },
        tabCheck("Tabs: closing another tab keeps the active one", "a,b|a") {
            describeTabs(CoderTabLogic.close(tabsOf("a", "b", "c", active = "a"), "c"))
        },
        tabCheck("Tabs: closing a tab that is not open changes nothing", "a,b,c|c") {
            describeTabs(CoderTabLogic.close(tabsOf("a", "b", "c"), "zzz"))
        },
        tabCheck("Tabs: the tab that becomes active is the most recent", "c") {
            CoderTabLogic.close(tabsOf("a", "b", "c", active = "b"), "b").recent.last()
        },
        tabCheck("Tabs: Close others keeps one tab and makes it active", "b|b") {
            describeTabs(CoderTabLogic.closeOthers(tabsOf("a", "b", "c", active = "a"), "b"))
        },
        tabCheck("Tabs: Close others for a tab that is not open changes nothing", "a,b,c|c") {
            describeTabs(CoderTabLogic.closeOthers(tabsOf("a", "b", "c"), "zzz"))
        },
        tabCheck("Tabs: Close all leaves nothing open", "|-") {
            describeTabs(CoderTabLogic.closeAll(tabsOf("a", "b", "c")))
        },
        tabCheck("Tabs: Close all on an empty list", "|-") {
            describeTabs(CoderTabLogic.closeAll(CoderTabs(emptyList(), null)))
        },
        tabCheck("Tabs: renaming a file renames its tab", "x,b|b") {
            describeTabs(CoderTabLogic.rename(tabsOf("a", "b"), "a", "x"))
        },
        tabCheck("Tabs: renaming the active file keeps it active", "x,b|x") {
            describeTabs(CoderTabLogic.rename(tabsOf("a", "b", active = "a"), "a", "x"))
        },
        tabCheck("Tabs: renaming a folder renames every tab inside it", "lib/a.js,lib/b.js,readme.md|lib/b.js") {
            describeTabs(CoderTabLogic.rename(tabsOf("src/a.js", "src/b.js", "readme.md", active = "src/b.js"), "src", "lib"))
        },
        tabCheck("Tabs: renaming a folder leaves a folder with a similar name alone", "lib/a.js,src2/a.js|src2/a.js") {
            describeTabs(CoderTabLogic.rename(tabsOf("src/a.js", "src2/a.js"), "src", "lib"))
        },
        tabCheck("Tabs: renaming a nested folder", "a/z/c.js|a/z/c.js") {
            describeTabs(CoderTabLogic.rename(tabsOf("a/b/c.js"), "a/b", "a/z"))
        },
        tabCheck("Tabs: renaming something that is not open changes nothing", "a,b|b") {
            describeTabs(CoderTabLogic.rename(tabsOf("a", "b"), "zz", "y"))
        },
        tabCheck("Tabs: renaming keeps the recent order", "b,x") {
            CoderTabLogic.rename(tabsOf("a", "b", active = "a", recent = listOf("b", "a")), "a", "x").recent.joinToString(",")
        },
        tabCheck("Tabs: a deleted file closes its tab", "a,c|c") {
            describeTabs(CoderTabLogic.removed(tabsOf("a", "b", "c", active = "b"), "b"))
        },
        tabCheck("Tabs: a deleted folder closes every tab inside it", "x|x") {
            describeTabs(CoderTabLogic.removed(tabsOf("src/a", "src/b", "x", active = "src/a"), "src"))
        },
        tabCheck("Tabs: a deleted folder leaves a folder with a similar name alone", "src2/a|src2/a") {
            describeTabs(CoderTabLogic.removed(tabsOf("src/a", "src2/a", active = "src/a"), "src"))
        },
        tabCheck("Tabs: deleting something that is not open changes nothing", "a,b|b") {
            describeTabs(CoderTabLogic.removed(tabsOf("a", "b"), "zz"))
        },
        tabCheck("Tabs: deleting a folder that holds every tab leaves none", "|-") {
            describeTabs(CoderTabLogic.removed(tabsOf("d/a", "d/b"), "d"))
        },
        tabCheck("Tabs: deleting an inactive file keeps the active tab", "a,b|a") {
            describeTabs(CoderTabLogic.removed(tabsOf("a", "b", "c", active = "a"), "c"))
        },
        tabCheck("Tabs: a renamed path is mapped exactly", "x") { CoderTabLogic.mapPath("a", "a", "x") },
        tabCheck("Tabs: a path inside a renamed folder is mapped", "lib/a/b.js") { CoderTabLogic.mapPath("src/a/b.js", "src", "lib") },
        tabCheck("Tabs: a path with a similar folder name is not mapped", "src2/a.js") { CoderTabLogic.mapPath("src2/a.js", "src", "lib") },
        tabCheck("Tabs: a path is inside itself and inside its folder, not inside a lookalike", "true,true,false") {
            listOf(
                CoderTabLogic.isInside("src", "src"),
                CoderTabLogic.isInside("src/a.js", "src"),
                CoderTabLogic.isInside("src2/a.js", "src"),
            ).joinToString(",")
        },
        tabCheck("Labels: different names show the file name", "a.js; b.css") { tabLabels(listOf("a.js", "b.css")) },
        tabCheck("Labels: a path in a folder shows only the file name", "main.js") { tabLabels(listOf("src/main.js")) },
        tabCheck("Labels: two files with one name show the folder", "index.js (src); index.js (lib)") {
            tabLabels(listOf("src/index.js", "lib/index.js"))
        },
        tabCheck("Labels: a top-level duplicate shows root", "index.html (root); index.html (docs)") {
            tabLabels(listOf("index.html", "docs/index.html"))
        },
        tabCheck("Labels: three files with one name", "x.js (a); x.js (b); x.js (c)") {
            tabLabels(listOf("a/x.js", "b/x.js", "c/x.js"))
        },
        tabCheck("Labels: folders that share a name fall back to the full path", "a/s/x.js; b/s/x.js; x.js (c)") {
            tabLabels(listOf("a/s/x.js", "b/s/x.js", "c/x.js"))
        },
        tabCheck("Labels: only the duplicates change", "a.js (root); a.js (src); b.js") {
            tabLabels(listOf("a.js", "src/a.js", "b.js"))
        },
        tabCheck("Labels: an empty list gives no labels", "") { tabLabels(emptyList()) },
        tabCheck("Labels: each path has its own label", "x.js (a)") { CoderTabLogic.labels(listOf("a/x.js", "b/x.js"))["a/x.js"] ?: "missing" },
        tabCheck("Save status: a write in any tab shows Saving", "Saving") { aggregateSaveStatus(true, true, true).name },
        tabCheck("Save status: a failed active tab shows Failed", "Failed") { aggregateSaveStatus(false, true, true).name },
        tabCheck("Save status: waiting for autosave shows Saving", "Saving") { aggregateSaveStatus(false, false, true).name },
        tabCheck("Save status: nothing to save shows Saved", "Saved") { aggregateSaveStatus(false, false, false).name },
        tabCheck("Save status: a write shows Saving even when nothing else is pending", "Saving") {
            aggregateSaveStatus(true, false, false).name
        },
        check("Save status: the three states exist", "Saved, Saving and Failed") {
            CoderSaveStatus.values().map { it.name }.toSet() == setOf("Saved", "Saving", "Failed")
        },
    )
}

// ---- Quiet beginner guidance (C12) -----------------------------------------------------------

private const val TIP_NOW = 1_000_000L

private fun tipState(
    mode: GuidanceMode = GuidanceMode.Beginner,
    enabled: Boolean = true,
    seen: Set<String> = emptySet(),
    visible: Boolean = false,
    shown: Int = 0,
    last: Long? = null,
) = TipState(mode, enabled, seen, visible, shown, last)

private fun tipId(context: TipContext, state: TipState = tipState()): String =
    CoderTipEngine.next(context, state)?.id ?: "none"

private fun tipCheck(name: String, expected: String, actual: () -> String): CoderCheckResult =
    check(name, expected, { actual() }) { actual() == expected }

private fun tipEngineChecks(): List<CoderCheckResult> {
    val home = TipContext(screen = TipScreen.Home, nowMillis = TIP_NOW, projectListShown = true)
    val editor = TipContext(screen = TipScreen.Editor, nowMillis = TIP_NOW, fileOpen = true, htmlFileOpen = true)
    return listOf(
        tipCheck("Tips: a fitting tip is shown to a Beginner", "home") { tipId(home) },
        tipCheck("Tips: Pro mode shows nothing", "none") { tipId(home, tipState(mode = GuidanceMode.Pro)) },
        tipCheck("Tips: the tips switch off shows nothing", "none") { tipId(home, tipState(enabled = false)) },
        tipCheck("Tips: a tip that was seen is not shown again", "none") { tipId(home, tipState(seen = setOf("home"))) },
        tipCheck("Tips: a tip is shown only on its own screen", "workspace") {
            tipId(TipContext(screen = TipScreen.Files, nowMillis = TIP_NOW, fileListShown = true, projectListShown = true))
        },
        tipCheck("Tips: a tip whose trigger is false is not shown", "none") { tipId(home.copy(projectListShown = false)) },
        tipCheck("Tips: nothing while another tip is visible", "none") { tipId(home, tipState(visible = true)) },
        tipCheck("Tips: nothing while the welcome card is visible", "none") { tipId(home.copy(welcomeVisible = true)) },
        tipCheck("Tips: nothing 1 second after typing", "none") { tipId(home.copy(lastTypedAtMillis = TIP_NOW - 1_000L)) },
        tipCheck("Tips: nothing 2.999 seconds after typing", "none") { tipId(home.copy(lastTypedAtMillis = TIP_NOW - 2_999L)) },
        tipCheck("Tips: allowed exactly 3 seconds after typing", "home") { tipId(home.copy(lastTypedAtMillis = TIP_NOW - 3_000L)) },
        tipCheck("Tips: allowed when nothing was typed yet", "home") { tipId(home.copy(lastTypedAtMillis = 0L)) },
        tipCheck("Tips: nothing while the keyboard is visible", "none") { tipId(home.copy(keyboardVisible = true)) },
        tipCheck("Tips: nothing while the screen is busy", "none") { tipId(home.copy(busy = true)) },
        tipCheck("Tips: the session limit of 3 stops a fourth tip", "none") { tipId(home, tipState(shown = 3, last = TIP_NOW - 500_000L)) },
        tipCheck("Tips: a third tip is still allowed", "home") { tipId(home, tipState(shown = 2, last = TIP_NOW - 500_000L)) },
        tipCheck("Tips: nothing 89.999 seconds after the last tip", "none") { tipId(home, tipState(shown = 1, last = TIP_NOW - 89_999L)) },
        tipCheck("Tips: allowed 90 seconds after the last tip", "home") { tipId(home, tipState(shown = 1, last = TIP_NOW - 90_000L)) },
        tipCheck("Tips: the first tip needs no cooldown", "home") { tipId(home, tipState(shown = 0, last = null)) },
        tipCheck("Tips: the keys tip wins when several editor tips fit", "editor-keys") {
            tipId(editor.copy(keyboardSeenInEditor = true, suggestionShownOnce = true, tabCount = 2))
        },
        tipCheck("Tips: then the suggestions tip", "editor-suggestions") {
            tipId(editor.copy(suggestionShownOnce = true, tabCount = 2), tipState(seen = setOf("editor-keys")))
        },
        tipCheck("Tips: then the tabs tip", "editor-tabs") {
            tipId(editor.copy(tabCount = 2), tipState(seen = setOf("editor-keys", "editor-suggestions")))
        },
        tipCheck("Tips: then the Run tip for an HTML file", "editor-run") { tipId(editor) },
        tipCheck("Tips: no Run tip when the open file is not a page", "none") { tipId(editor.copy(htmlFileOpen = false)) },
        tipCheck("Tips: the Find tip needs 200 typed characters", "none") {
            tipId(editor.copy(htmlFileOpen = false, typedChars = 199))
        },
        tipCheck("Tips: the Find tip appears at 200 typed characters", "find") {
            tipId(editor.copy(htmlFileOpen = false, typedChars = 200))
        },
        tipCheck("Tips: the Find tip comes last", "find") {
            tipId(
                editor.copy(typedChars = 500),
                tipState(seen = setOf("editor-keys", "editor-suggestions", "editor-tabs", "editor-run")),
            )
        },
        tipCheck("Tips: the four old hint ids that were seen stay seen", "none|none|none|none") {
            val seen = setOf("home", "workspace", "preview", "ai")
            listOf(
                tipId(home, tipState(seen = seen)),
                tipId(TipContext(screen = TipScreen.Files, nowMillis = TIP_NOW, fileListShown = true), tipState(seen = seen)),
                tipId(TipContext(screen = TipScreen.Preview, nowMillis = TIP_NOW, previewLoaded = true), tipState(seen = seen)),
                tipId(TipContext(screen = TipScreen.Ai, nowMillis = TIP_NOW, aiPanelIdle = true), tipState(seen = seen)),
            ).joinToString("|")
        },
        tipCheck("Tips: the preview tip needs a finished load", "none|preview") {
            val base = TipContext(screen = TipScreen.Preview, nowMillis = TIP_NOW)
            tipId(base) + "|" + tipId(base.copy(previewLoaded = true))
        },
        tipCheck("Tips: the AI tip needs an idle panel", "none|ai") {
            val base = TipContext(screen = TipScreen.Ai, nowMillis = TIP_NOW)
            tipId(base) + "|" + tipId(base.copy(aiPanelIdle = true))
        },
        tipCheck("Tips: the GitHub tip never shows", "none") {
            tipId(
                TipContext(
                    screen = TipScreen.GitHub,
                    nowMillis = TIP_NOW,
                    projectListShown = true,
                    fileListShown = true,
                    fileOpen = true,
                    previewLoaded = true,
                    aiPanelIdle = true,
                    tabCount = 5,
                    typedChars = 999,
                ),
            )
        },
        tipCheck("Tips: allowedNow follows the global rules", "true|false|false") {
            listOf(
                CoderTipEngine.allowedNow(home, tipState()),
                CoderTipEngine.allowedNow(home, tipState(mode = GuidanceMode.Pro)),
                CoderTipEngine.allowedNow(home.copy(busy = true), tipState()),
            ).joinToString("|")
        },
        tipCheck("Tips: typing means a recent change or a visible keyboard", "true|true|false|false") {
            listOf(
                CoderTipEngine.isTyping(home.copy(keyboardVisible = true)),
                CoderTipEngine.isTyping(home.copy(lastTypedAtMillis = TIP_NOW - 100L)),
                CoderTipEngine.isTyping(home.copy(lastTypedAtMillis = TIP_NOW - 5_000L)),
                CoderTipEngine.isTyping(home),
            ).joinToString("|")
        },
        tipCheck("Tips: hasPending is true only for Beginner with unseen tips", "true|false|false|false") {
            listOf(
                CoderTipEngine.hasPending(TipScreen.Home, tipState()),
                CoderTipEngine.hasPending(TipScreen.Home, tipState(seen = setOf("home"))),
                CoderTipEngine.hasPending(TipScreen.Home, tipState(mode = GuidanceMode.Pro)),
                CoderTipEngine.hasPending(TipScreen.Home, tipState(enabled = false)),
            ).joinToString("|")
        },
        tipCheck("Tips: Got it and the timeout always mark a tip seen", "true|true") {
            listOf(
                CoderTipEngine.shouldMarkSeen(TipEnd.GotIt, 500L),
                CoderTipEngine.shouldMarkSeen(TipEnd.TimedOut, CoderTipEngine.AUTO_HIDE_MS),
            ).joinToString("|")
        },
        tipCheck("Tips: leaving after under 3 seconds does not mark a tip seen", "false|true") {
            listOf(
                CoderTipEngine.shouldMarkSeen(TipEnd.Left, 2_999L),
                CoderTipEngine.shouldMarkSeen(TipEnd.Left, 3_000L),
            ).joinToString("|")
        },
        tipCheck("Tips: the numbers are the agreed ones", "12000|3000|3|90000|3000") {
            listOf(
                CoderTipEngine.AUTO_HIDE_MS,
                CoderTipEngine.MIN_SEEN_MS,
                CoderTipEngine.MAX_PER_SESSION.toLong(),
                CoderTipEngine.COOLDOWN_MS,
                CoderTipEngine.TYPING_QUIET_MS,
            ).joinToString("|")
        },
        tipCheck("Welcome: shown to a Beginner with no project who has not seen it", "true") {
            CoderTipEngine.showWelcome(GuidanceMode.Beginner, welcomeSeen = false, projectCount = 0).toString()
        },
        tipCheck("Welcome: never in Pro mode, once seen, or with a project", "false|false|false") {
            listOf(
                CoderTipEngine.showWelcome(GuidanceMode.Pro, welcomeSeen = false, projectCount = 0),
                CoderTipEngine.showWelcome(GuidanceMode.Beginner, welcomeSeen = true, projectCount = 0),
                CoderTipEngine.showWelcome(GuidanceMode.Beginner, welcomeSeen = false, projectCount = 1),
            ).joinToString("|")
        },
    )
}

private fun guidanceDataChecks(): List<CoderCheckResult> = listOf(
    tipCheck(
        "Tips: the list holds exactly the ten agreed ids",
        "home,workspace,editor-keys,editor-suggestions,editor-tabs,editor-run,preview,ai,find,github",
    ) { CoderTips.all.joinToString(",") { it.id } },
    check("Tips: every tip except the reserved one has text", "text on nine tips") {
        CoderTips.all.filter { it.id != "github" }.all { it.text.isNotBlank() }
    },
    check("Tips: ids are unique", "no repeated id") { CoderTips.all.map { it.id }.toSet().size == CoderTips.all.size },
    tipCheck("Tips: the four old hints keep their texts", "true") {
        (
            CoderTips.byId("home")?.text == "Start with a template. Tap New project, pick one, then tap Run to see it." &&
                CoderTips.byId("preview")?.text == "Something not working? Tap the console icon to see messages and errors." &&
                CoderTips.byId("ai")?.text ==
                "Ask in plain words. Nothing changes in your files until you tap Insert or Replace, and you can always undo." &&
                CoderTips.byId("workspace")?.text == "Tap a file to edit it. Tap Run to see your page."
            ).toString()
    },
    check("Glossary: at least 16 terms", "16 or more") { CoderGlossary.terms.size >= 16 },
    check("Glossary: ids are unique and no text is empty", "unique ids, filled texts") {
        CoderGlossary.terms.map { it.id }.toSet().size == CoderGlossary.terms.size &&
            CoderGlossary.terms.all { it.id.isNotBlank() && it.title.isNotBlank() && it.explanation.isNotBlank() }
    },
    check("Glossary: every required term is there", "all 16 required ids") {
        listOf(
            "html", "css", "javascript", "react", "zip", "repository", "commit", "push", "token", "api-key",
            "console", "preview", "build", "environment-variable", "cors", "dist-folder",
        ).all { CoderGlossary.find(it) != null }
    },
    check("Glossary: every What is this? term id exists", "token, api-key and console") {
        listOf("token", "api-key", "console").all { CoderGlossary.find(it) != null }
    },
    check("Glossary: an unknown id gives nothing", "null") { CoderGlossary.find("nope") == null },
    check("Guides: there are 8 with the agreed titles", "8 titles") {
        CoderGuides.all.map { it.title } == listOf(
            "Make your first page",
            "Use suggestions while you type",
            "Open several files",
            "See your page (Run)",
            "Ask the AI for help",
            "Save to GitHub",
            "Import or export a ZIP",
            "Fix a mistake",
        )
    },
    check("Guides: each has 4 to 6 steps", "4..6 steps each") { CoderGuides.all.all { it.steps.size in 4..6 } },
    check("Guides: no title or step is empty and ids are unique", "filled, unique") {
        CoderGuides.all.all { it.id.isNotBlank() && it.title.isNotBlank() && it.steps.all { step -> step.isNotBlank() } } &&
            CoderGuides.all.map { it.id }.toSet().size == CoderGuides.all.size
    },
    check("Guidance: the two modes exist", "Beginner and Pro") {
        GuidanceMode.values().map { it.name } == listOf("Beginner", "Pro")
    },
)

// ---- Phase C13: preview upgrades ---------------------------------------------------------------

private fun httpHead(vararg lines: String): ByteArray = (lines.joinToString("\r\n") + "\r\n\r\n").toByteArray(Charsets.ISO_8859_1)

private fun parsedOf(bytes: ByteArray): CoderParsedRequest? = (CoderHttpRequest.parseRequest(bytes) as? ParseResult.Ok)?.request

private fun badStatusOf(bytes: ByteArray): Int? = (CoderHttpRequest.parseRequest(bytes) as? ParseResult.Bad)?.status

private fun headOfResponse(response: CoderHttpResponse): String = response.headText()

private fun bodyOfResponse(response: CoderHttpResponse): String {
    val out = ByteArrayOutputStream()
    response.writeTo(out)
    val text = out.toString("ISO-8859-1")
    return text.substringAfter("\r\n\r\n", "")
}

private fun previewUpgradeChecks(context: Context, store: CoderFileStore): List<CoderCheckResult> =
    servedPathChecks() + splitAndRefreshChecks() + httpParseChecks() + httpDecisionChecks() + httpResponseChecks() +
        pickerAndConsoleChecks() + blockedResponderChecks(context, store) + localServerChecks(context, store)

private fun servedPathChecks(): List<CoderCheckResult> {
    val out = ArrayList<CoderCheckResult>()
    val blocked = listOf(
        ".env", ".ENV", ".env.local", "a/.env.production", ".git/config", "a/.git/x", "node_modules/x/index.js",
        ".coder-build/a.js", "x/.coder-backup/y", "key.pem", "a/site.KEY", "my.keystore",
    )
    for (path in blocked) out.add(check("Served paths: $path is blocked", "blocked") { CoderServedPaths.isBlocked(path) })
    val allowed = listOf("index.html", "env.js", "src/app.js", ".gitignore", "environment.txt", "assets/keyboard.css", "a/.envrc")
    for (path in allowed) out.add(check("Served paths: $path is served", "not blocked") { !CoderServedPaths.isBlocked(path) })
    return out
}

private fun splitAndRefreshChecks(): List<CoderCheckResult> = listOf(
    check("Split: a share below 25% becomes 25%", "0.25") { clampRatio(0.1f) == 0.25f },
    check("Split: a share above 75% becomes 75%", "0.75") { clampRatio(0.9f) == 0.75f },
    check("Split: 50% stays 50%", "0.5") { clampRatio(0.5f) == 0.5f },
    check("Split: the edges 25% and 75% stay", "0.25 and 0.75") { clampRatio(0.25f) == 0.25f && clampRatio(0.75f) == 0.75f },
    check("Split: not a number gives 50%", "0.5") { clampRatio(Float.NaN) == 0.5f },
    check("Split: negative gives 25%", "0.25") { clampRatio(-3f) == 0.25f },
    check("Auto-refresh: everything right reloads", "true") { shouldAutoRefresh(true, true, true, false, 1000L) },
    check("Auto-refresh: switched off does not reload", "false") { !shouldAutoRefresh(false, true, true, false, 5000L) },
    check("Auto-refresh: preview hidden does not reload", "false") { !shouldAutoRefresh(true, false, true, false, 5000L) },
    check("Auto-refresh: no save does not reload", "false") { !shouldAutoRefresh(true, true, false, false, 5000L) },
    check("Auto-refresh: a loading page is skipped", "false") { !shouldAutoRefresh(true, true, true, true, 5000L) },
    check("Auto-refresh: 999 ms since the last reload is too soon", "false") { !shouldAutoRefresh(true, true, true, false, 999L) },
    check("Auto-refresh: 1000 ms since the last reload is fine", "true") { shouldAutoRefresh(true, true, true, false, 1000L) },
)

private fun httpParseChecks(): List<CoderCheckResult> = listOf(
    check("HTTP: a valid GET is read", "GET /a.html with its host") {
        val r = parsedOf(httpHead("GET /a.html HTTP/1.1", "Host: 127.0.0.1:5000"))
        r != null && r.method == "GET" && r.path == "/a.html" && r.headers["host"] == "127.0.0.1:5000"
    },
    check("HTTP: header names are lower case", "host and accept") {
        val r = parsedOf(httpHead("GET / HTTP/1.1", "HOST: x", "Accept: */*"))
        r != null && r.headers.keys.toList() == listOf("host", "accept")
    },
    check("HTTP: a valid HEAD is read", "HEAD") { parsedOf(httpHead("HEAD / HTTP/1.1", "Host: x"))?.method == "HEAD" },
    check("HTTP: HTTP/1.0 is read", "GET") { parsedOf(httpHead("GET / HTTP/1.0", "Host: x"))?.method == "GET" },
    check("HTTP: a request line with two parts is bad", "400") { badStatusOf(httpHead("GET /", "Host: x")) == 400 },
    check("HTTP: an unknown version is bad", "400") { badStatusOf(httpHead("GET / HTTP/2", "Host: x")) == 400 },
    check("HTTP: a lower case method is bad", "400") { badStatusOf(httpHead("get / HTTP/1.1", "Host: x")) == 400 },
    check("HTTP: a full address as target is bad", "400") { badStatusOf(httpHead("GET http://evil/ HTTP/1.1", "Host: x")) == 400 },
    check("HTTP: a target starting with // is bad", "400") { badStatusOf(httpHead("GET //evil.com/ HTTP/1.1", "Host: x")) == 400 },
    check("HTTP: a request line over 8 KB is bad", "400") {
        badStatusOf(httpHead("GET /" + "a".repeat(9000) + " HTTP/1.1", "Host: x")) == 400
    },
    check("HTTP: 101 headers are too many", "431") {
        val lines = ArrayList<String>()
        lines.add("GET / HTTP/1.1")
        for (i in 1..101) lines.add("X-$i: 1")
        badStatusOf(httpHead(*lines.toTypedArray())) == 431
    },
    check("HTTP: 100 headers are fine", "read") {
        val lines = ArrayList<String>()
        lines.add("GET / HTTP/1.1")
        for (i in 1..100) lines.add("X-$i: 1")
        parsedOf(httpHead(*lines.toTypedArray())) != null
    },
    check("HTTP: headers over 16 KB are too large", "431") {
        badStatusOf(httpHead("GET / HTTP/1.1", "X-Big: " + "a".repeat(17000))) == 431
    },
    check("HTTP: a header without a colon is bad", "400") { badStatusOf(httpHead("GET / HTTP/1.1", "Nonsense")) == 400 },
    check("HTTP: cookies are read in order", "a=1 and coderk=tok") {
        val r = parsedOf(httpHead("GET / HTTP/1.1", "Host: x", "Cookie: a=1; coderk=tok"))
        r != null && r.cookie("a") == "1" && r.cookie("coderk") == "tok"
    },
    check("HTTP: no cookie gives none", "null") { parsedOf(httpHead("GET / HTTP/1.1", "Host: x"))?.cookie("coderk") == null },
    check("HTTP: the k value is read from the query", "abc") {
        parsedOf(httpHead("GET /?k=abc HTTP/1.1", "Host: x"))?.queryToken == "abc"
    },
    check("HTTP: no k gives null and the query is cut from the path", "null and /p") {
        val r = parsedOf(httpHead("GET /p?x=1 HTTP/1.1", "Host: x"))
        r != null && r.queryToken == null && r.path == "/p" && r.rawPath == "/p"
    },
    check("HTTP: percent-encoded paths are decoded", "/a b/c.js") {
        val r = parsedOf(httpHead("GET /a%20b/c.js HTTP/1.1", "Host: x"))
        r != null && r.path == "/a b/c.js" && r.rawPath == "/a%20b/c.js"
    },
    check("HTTP: .. stays visible so the file rules refuse it", "path with ..") {
        parsedOf(httpHead("GET /a/%2e%2e/b HTTP/1.1", "Host: x"))?.path == "/a/../b"
    },
    check("HTTP: an encoded control character is bad", "400") { badStatusOf(httpHead("GET /a%00b HTTP/1.1", "Host: x")) == 400 },
)

private fun httpDecisionChecks(): List<CoderCheckResult> {
    fun decide(vararg lines: String, port: Int = 5000, token: String = "tok"): CoderAccess? {
        val r = parsedOf(httpHead(*lines)) ?: return null
        return CoderHttpRequest.decide(r, port, token)
    }
    return listOf(
        check("Access: a missing host is refused", "Forbidden") { decide("GET /?k=tok HTTP/1.1") == CoderAccess.Forbidden },
        check("Access: a wrong host is refused", "Forbidden") {
            decide("GET /?k=tok HTTP/1.1", "Host: evil.com:5000") == CoderAccess.Forbidden
        },
        check("Access: the wrong port in the host is refused", "Forbidden") {
            decide("GET /?k=tok HTTP/1.1", "Host: 127.0.0.1:5001") == CoderAccess.Forbidden
        },
        check("Access: the right k sets the cookie and redirects", "SetCookieAndRedirect") {
            decide("GET /?k=tok HTTP/1.1", "Host: 127.0.0.1:5000") == CoderAccess.SetCookieAndRedirect
        },
        check("Access: localhost as host is allowed", "SetCookieAndRedirect") {
            decide("GET /?k=tok HTTP/1.1", "Host: localhost:5000") == CoderAccess.SetCookieAndRedirect
        },
        check("Access: a wrong k is refused", "Forbidden") {
            decide("GET /?k=nope HTTP/1.1", "Host: 127.0.0.1:5000") == CoderAccess.Forbidden
        },
        check("Access: no k and no cookie is refused", "Forbidden") {
            decide("GET / HTTP/1.1", "Host: 127.0.0.1:5000") == CoderAccess.Forbidden
        },
        check("Access: the right cookie is served", "Serve") {
            decide("GET /a.html HTTP/1.1", "Host: 127.0.0.1:5000", "Cookie: coderk=tok") == CoderAccess.Serve
        },
        check("Access: a wrong cookie is refused", "Forbidden") {
            decide("GET /a.html HTTP/1.1", "Host: 127.0.0.1:5000", "Cookie: coderk=bad") == CoderAccess.Forbidden
        },
        check("Access: POST is not allowed", "MethodNotAllowed") {
            decide("POST / HTTP/1.1", "Host: 127.0.0.1:5000", "Cookie: coderk=tok") == CoderAccess.MethodNotAllowed
        },
        check("Access: HEAD with the cookie is served", "Serve") {
            decide("HEAD / HTTP/1.1", "Host: 127.0.0.1:5000", "Cookie: coderk=tok") == CoderAccess.Serve
        },
        check("Access: the cookie header text", "name, Path, HttpOnly, SameSite=Strict") {
            CoderHttpRequest.cookieHeader("tok") == "coderk=tok; Path=/; HttpOnly; SameSite=Strict"
        },
    )
}

private fun httpResponseChecks(): List<CoderCheckResult> {
    fun head(status: Int, reason: String, headers: Map<String, String> = emptyMap(), body: String? = null): String =
        headOfResponse(CoderHttpRequest.buildResponse(status, reason, headers, body?.let { ByteArrayInputStream(it.toByteArray()) }))
    return listOf(
        check("Response: 200 starts with its status line", "HTTP/1.1 200 OK") { head(200, "OK").startsWith("HTTP/1.1 200 OK\r\n") },
        check("Response: always closes, no sniffing, no cache", "three headers") {
            val h = head(200, "OK")
            h.contains("Connection: close\r\n") && h.contains("X-Content-Type-Options: nosniff\r\n") && h.contains("Cache-Control: no-store\r\n")
        },
        check("Response: the head ends with an empty line", "CRLF CRLF") { head(200, "OK").endsWith("\r\n\r\n") },
        check("Response: 302 carries the location and the cookie", "Location and Set-Cookie") {
            val h = head(302, "Found", mapOf("Location" to "/", "Set-Cookie" to CoderHttpRequest.cookieHeader("tok")))
            h.startsWith("HTTP/1.1 302 Found\r\n") && h.contains("Location: /\r\n") && h.contains("Set-Cookie: coderk=tok; Path=/; HttpOnly; SameSite=Strict\r\n")
        },
        check("Response: 403 line", "HTTP/1.1 403 Forbidden") { head(403, "Forbidden").startsWith("HTTP/1.1 403 Forbidden\r\n") },
        check("Response: 404 line", "HTTP/1.1 404 Not Found") { head(404, "Not Found").startsWith("HTTP/1.1 404 Not Found\r\n") },
        check("Response: 405 line with Allow", "405 and Allow") {
            val h = headOfResponse(CoderHttpRequest.textResponse(405, "Method Not Allowed", "no", mapOf("Allow" to "GET, HEAD")))
            h.startsWith("HTTP/1.1 405 Method Not Allowed\r\n") && h.contains("Allow: GET, HEAD\r\n")
        },
        check("Response: line breaks in a header value are removed", "no injected header") {
            !head(200, "OK", mapOf("X-A" to "one\r\nEvil: yes")).contains("\r\nEvil:")
        },
        check("Response: length and connection headers from the caller are ignored", "one Connection: close") {
            val h = head(200, "OK", mapOf("Content-Length" to "5", "Connection" to "keep-alive"))
            !h.contains("Content-Length") && h.contains("Connection: close") && !h.contains("keep-alive")
        },
        check("Response: the body follows the head", "hello") {
            bodyOfResponse(CoderHttpRequest.buildResponse(200, "OK", emptyMap(), ByteArrayInputStream("hello".toByteArray()))) == "hello"
        },
        check("Response: a HEAD answer has no body", "empty") {
            bodyOfResponse(CoderHttpRequest.textResponse(200, "OK", "hello", head = true)).isEmpty()
        },
        check("Opening page: 200 with the cookie", "200, html, Set-Cookie") {
            val h = headOfResponse(CoderHttpRequest.openingResponse("/index.html", "tok"))
            h.startsWith("HTTP/1.1 200 OK\r\n") && h.contains("Content-Type: text/html; charset=utf-8\r\n") &&
                h.contains("Set-Cookie: coderk=tok; Path=/; HttpOnly; SameSite=Strict\r\n")
        },
        check("Opening page: the body opens the asked path and can try again", "target, replace, Try again") {
            val body = bodyOfResponse(CoderHttpRequest.openingResponse("/index.html", "tok"))
            body.contains("var target=\"/index.html\";") && body.contains("location.replace") && body.contains("Try again")
        },
        check("Opening page: the path cannot break out of its quotes", "escaped") {
            val page = CoderHttpRequest.openingPage("/a\"b<c>'d\\e.html")
            page.contains("var target=\"/a\\\"b\\u003cc\\u003e\\u0027d\\\\e.html\";") && !page.contains("<c>")
        },
        check("Opening page: a HEAD answer has no body", "empty") {
            bodyOfResponse(CoderHttpRequest.openingResponse("/", "tok", head = true)).isEmpty()
        },
        check("Token: 32 hex characters", "32 characters of 0-9a-f") {
            val t = CoderLocalServer.generateToken()
            t.length == 32 && t.all { it in '0'..'9' || it in 'a'..'f' }
        },
        check("Token: two tokens differ", "different") { CoderLocalServer.generateToken() != CoderLocalServer.generateToken() },
    )
}

private fun pickerAndConsoleChecks(): List<CoderCheckResult> {
    fun file(path: String) = CoderEntry(path, false, 1L, 0L)
    val entries = listOf(
        CoderEntry("sub", true, 0L, 0L), file("zeta.html"), file("about.HTML"), file("index.html"), file("sub/page.htm"),
        file("node_modules/x/y.html"), file(".coder-build/z.html"), file("notes.txt"), file("dist/index.html"), file("dist/a.html"),
    )
    fun msg(id: Long, level: ConsoleLevel, text: String, file: String? = null, line: Int? = null) =
        CoderConsoleMessage(id, level, text, file, line)
    val messages = listOf(
        msg(1, ConsoleLevel.Log, "hi"),
        msg(2, ConsoleLevel.Error, "boom\nsecond line", "script.js", 3),
        msg(3, ConsoleLevel.Warn, "careful", "a.js"),
    )
    return listOf(
        check("Pages: index.html first, then by name", "index.html, about.HTML, dist/a.html, dist/index.html, sub/page.htm, zeta.html") {
            htmlPagesOf(entries, "") == listOf("index.html", "about.HTML", "dist/a.html", "dist/index.html", "sub/page.htm", "zeta.html")
        },
        check("Pages: nothing hidden or blocked is listed", "no node_modules, no .coder") {
            htmlPagesOf(entries, "").none { it.contains("node_modules") || it.contains(".coder") }
        },
        check("Pages: a root folder lists only its pages, without the folder", "index.html, a.html") {
            htmlPagesOf(entries, "dist") == listOf("index.html", "a.html")
        },
        check("Pages: at most 50", "50") {
            val many = (1..80).map { file("p$it.html") }
            htmlPagesOf(many, "").size == MAX_PICKER_PAGES
        },
        check("Console: All keeps every message", "3") { filterConsoleMessages(messages, ConsoleFilter.All).size == 3 },
        check("Console: Errors keeps only errors", "id 2") { filterConsoleMessages(messages, ConsoleFilter.Errors).map { it.id } == listOf(2L) },
        check("Console: Warnings keeps only warnings", "id 3") { filterConsoleMessages(messages, ConsoleFilter.Warnings).map { it.id } == listOf(3L) },
        check("Console: copy text has one line each", "LEVEL file:line message") {
            consoleCopyText(messages) == "LOG hi\nERROR script.js:3 boom second line\nWARN a.js careful"
        },
        check("Console: copy text of nothing is empty", "empty") { consoleCopyText(emptyList()).isEmpty() },
        check("Browser address: a page is encoded and carries the token", "http://127.0.0.1:99/a%20b.html?k=t") {
            browserUrlFor(CoderServerInfo(99, "t", "x"), "a b.html") == "http://127.0.0.1:99/a%20b.html?k=t"
        },
        check("Browser address: Markdown goes through the app's page", "__coder/md/") {
            browserUrlFor(CoderServerInfo(99, "t", "x"), "README.md") == "http://127.0.0.1:99/__coder/md/README.md?k=t"
        },
    )
}

private fun blockedResponderChecks(context: Context, store: CoderFileStore): List<CoderCheckResult> {
    val id = newId()
    val host = CoderPreviewHost.hostFor(id)
    store.write(id, "index.html", "<p>home</p>")
    store.write(id, ".env", "SECRET=1")
    store.write(id, ".git/config", "[core]")
    store.write(id, "a/.coder-x/y.js", "1")
    store.write(id, "secret.pem", "KEY")
    store.write(id, "node_modules/m/i.js", "1")
    store.write(id, "dist/index.html", "<p>dist</p>")
    store.write(id, "dist/.env.local", "SECRET=2")
    val responder = CoderPreviewResponder(context, store, id, "")
    val distResponder = CoderPreviewResponder(context, store, id, "dist")
    fun get(r: CoderPreviewResponder, path: String): WebResourceResponse? = r.respondTo(Uri.parse("https://$host$path"), "GET")
    fun text(r: WebResourceResponse?): String = r?.data?.readBytes()?.toString(Charsets.UTF_8) ?: ""
    val out = ArrayList<CoderCheckResult>()
    for (path in listOf("/.env", "/.git/config", "/a/.coder-x/y.js", "/secret.pem", "/node_modules/m/i.js", "/dist/.env.local", "/%2eenv", "/.ENV")) {
        out.add(check("Preview answers 404 for $path", "404 without the secret") {
            val r = get(responder, path)
            r?.statusCode == 404 && !text(r).contains("SECRET") && text(r).contains("File not found")
        })
    }
    out.add(check("Preview with a root folder answers 404 for its .env.local", "404") { get(distResponder, "/.env.local")?.statusCode == 404 })
    out.add(check("Preview still answers a normal page", "200") { get(responder, "/")?.statusCode == 200 })
    out.add(check("Preview HEAD of a blocked file is 404", "404") {
        responder.respondTo(Uri.parse("https://$host/.env"), "HEAD")?.statusCode == 404
    })
    return out
}

private fun rawLoopbackGet(port: Int, path: String, cookie: String?, hostHeader: String = "127.0.0.1:$port"): String =
    Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 5000
        val request = StringBuilder("GET $path HTTP/1.1\r\nHost: $hostHeader\r\n")
        if (cookie != null) request.append("Cookie: ").append(cookie).append("\r\n")
        request.append("Connection: close\r\n\r\n")
        socket.getOutputStream().write(request.toString().toByteArray(Charsets.ISO_8859_1))
        socket.getOutputStream().flush()
        String(socket.getInputStream().readBytes(), Charsets.ISO_8859_1)
    }

private fun localServerChecks(context: Context, store: CoderFileStore): List<CoderCheckResult> {
    val id = newId()
    store.write(id, "index.html", "<p>served</p>")
    store.write(id, ".env", "SECRET=1")
    val server = CoderLocalServer(context, store)
    val info = server.start(id, "") ?: return listOf(CoderCheckResult("Local server starts on a free port", false, "no port could be opened"))
    val out = ArrayList<CoderCheckResult>()
    val cookie = "coderk=${info.token}"
    try {
        out.add(check("Local server: the address names loopback and carries the token", "http://127.0.0.1:port/?k=token") {
            info.url == "http://127.0.0.1:${info.port}/?k=${info.token}" && info.token.length == 32
        })
        out.add(check("Local server: it reports the project it serves", "the project id") { server.serving.value?.projectId == id })
        out.add(check("Local server: no token gives 403", "403") { rawLoopbackGet(info.port, "/", null).startsWith("HTTP/1.1 403") })
        out.add(check("Local server: a wrong host gives 403", "403") {
            rawLoopbackGet(info.port, "/?k=${info.token}", null, "evil.com:${info.port}").startsWith("HTTP/1.1 403")
        })
        out.add(check("Local server: the token gives the opening page and the cookie", "200, Set-Cookie, the page to open") {
            val answer = rawLoopbackGet(info.port, "/?k=${info.token}", null)
            answer.startsWith("HTTP/1.1 200") && answer.contains("Opening your page") && answer.contains("var target=\"/\";") &&
                answer.contains("Set-Cookie: coderk=${info.token}; Path=/; HttpOnly; SameSite=Strict")
        })
        out.add(check("Local server: bytes that are not an HTTP request are closed at once", "closed without an answer") {
            Socket("127.0.0.1", info.port).use { socket ->
                socket.soTimeout = 2000
                socket.getOutputStream().write(byteArrayOf(0x16))
                socket.getOutputStream().flush()
                socket.getInputStream().read() == -1
            }
        })
        out.add(check("Local server: the cookie gives the page", "200 and the body") {
            val answer = rawLoopbackGet(info.port, "/", cookie)
            answer.startsWith("HTTP/1.1 200") && answer.endsWith("<p>served</p>") && answer.contains("Content-Type: text/html")
        })
        out.add(check("Local server: .env gives 404 and never the secret", "404 without SECRET") {
            val answer = rawLoopbackGet(info.port, "/.env", cookie)
            answer.startsWith("HTTP/1.1 404") && !answer.contains("SECRET=1")
        })
        out.add(check("Local server: a missing file gives 404", "404") { rawLoopbackGet(info.port, "/nothing.js", cookie).startsWith("HTTP/1.1 404") })
        out.add(check("Local server: .. cannot leave the project", "404 or 400, never 200") {
            val answer = rawLoopbackGet(info.port, "/%2e%2e/%2e%2e/etc/passwd", cookie)
            !answer.startsWith("HTTP/1.1 200")
        })
    } finally {
        server.stop()
    }
    out.add(check("Local server: stop closes the port", "connection refused") {
        try {
            Socket("127.0.0.1", info.port).close()
            false
        } catch (e: ConnectException) {
            true
        }
    })
    out.add(check("Local server: after stop nothing is served", "null") { server.serving.value == null })
    return out
}

// ---- GitHub build and environment variables (phase C14) ---------------------------------------

private fun buildAndEnvChecks(context: Context, store: CoderFileStore): List<CoderCheckResult> =
    envRuleChecks() + dotEnvChecks() + envScriptChecks() + envStoreChecks(context) + analyzerChecks() +
        workflowChecks() + actionsRequestChecks() + actionsLogicChecks() + buildStateChecks(store) +
        extractChecks(store) + c14SeamChecks(context, store)

private fun envRuleChecks(): List<CoderCheckResult> {
    val out = ArrayList<CoderCheckResult>()
    for (name in listOf("API_URL", "_X", "A1", "VITE_KEY", "A".repeat(64), "Z")) {
        out.add(check("Env name accepted: ${name.take(12)}", "valid") { CoderEnvRules.validateName(name) == null })
    }
    val refused = listOf("" to "empty", "api" to "lowercase", "1A" to "starts with a digit", "A-B" to "hyphen", "A B" to "space", "A".repeat(65) to "65 characters")
    for ((name, label) in refused) out.add(check("Env name refused: $label", "a message") { CoderEnvRules.validateName(name) != null })
    for (name in listOf("GITHUB_TOKEN", "RUNNER_OS", "CI", "PATH", "HOME")) {
        out.add(check("Env name reserved: $name", "reserved message") { CoderEnvRules.validateName(name) == CoderEnvRules.MSG_RESERVED })
    }
    out.add(check("Env value: empty and 2000 characters are fine", "valid") {
        CoderEnvRules.validateValue("") == null && CoderEnvRules.validateValue("x".repeat(2000)) == null
    })
    out.add(check("Env value: 2001 characters are refused", "a message") { CoderEnvRules.validateValue("x".repeat(2001)) != null })
    out.add(check("Env value: line break, tab and DEL are refused", "a message") {
        CoderEnvRules.validateValue("a\nb") != null && CoderEnvRules.validateValue("a\tb") != null && CoderEnvRules.validateValue("a\u007Fb") != null
    })
    out.add(check("Env value: Unicode is fine", "valid") { CoderEnvRules.validateValue("héllo ✓ 日本") == null })
    out.add(check("Env suggestion: spaces and hyphens become underscores", "API_URL, MY_VAR") {
        CoderEnvRules.suggestName("api url") == "API_URL" && CoderEnvRules.suggestName("my-var!") == "MY_VAR"
    })
    out.add(check("Env suggestion: at most 64 characters", "64") { CoderEnvRules.suggestName("a".repeat(70)).length == 64 })
    val some = listOf(EnvVar("A", "1"), EnvVar("B", "2"))
    out.add(check("Env save: a new name that exists is refused", "a message") { CoderEnvRules.validateForSave(some, null, "A", "x") != null })
    out.add(check("Env save: an edit may keep its own name", "null") { CoderEnvRules.validateForSave(some, "A", "A", "new") == null })
    out.add(check("Env save: an edit may not take another name", "a message") { CoderEnvRules.validateForSave(some, "A", "B", "x") != null })
    val full = (1..50).map { EnvVar("V$it", "x") }
    out.add(check("Env save: the 51st variable is refused, an edit at 50 is fine", "refused, ok") {
        CoderEnvRules.validateForSave(full, null, "EXTRA", "x") != null && CoderEnvRules.validateForSave(full, "V1", "V1", "y") == null
    })
    out.add(check("Env merge: new names are added, existing ones overwritten", "1 added, 1 overwritten") {
        val m = CoderEnvRules.merge(some, listOf("A" to "9", "C" to "3"))
        m.added == 1 && m.overwritten == 1 && m.merged.map { it.name } == listOf("A", "B", "C") && m.merged.first().value == "9"
    })
    out.add(check("Env merge: names beyond 50 are counted, not added", "2 not added") {
        val m = CoderEnvRules.merge(full, listOf("N1" to "1", "N2" to "2", "V1" to "z"))
        m.merged.size == 50 && m.notAdded == 2 && m.overwritten == 1
    })
    out.add(check("Env merge: the names a file would overwrite are listed", "A") {
        CoderEnvRules.overwrittenNames(some, listOf("A" to "1", "Z" to "2")) == listOf("A")
    })
    return out
}

private fun dotEnvChecks(): List<CoderCheckResult> {
    fun parse(text: String) = CoderEnvRules.parseDotEnvDetailed(text)
    return listOf(
        check("dotenv: KEY=VALUE and export KEY=VALUE", "two variables") {
            parse("A=1\nexport B=2").variables == listOf("A" to "1", "B" to "2")
        },
        check("dotenv: comments and blank lines are ignored, not counted", "no skipped") {
            val r = parse("# note\n\n  # another\nA=1")
            r.variables == listOf("A" to "1") && r.skippedLines == 0
        },
        check("dotenv: double quotes are removed and \\\" is a quote", "say \"hi\"") {
            parse("A=\"say \\\"hi\\\"\"").variables == listOf("A" to "say \"hi\"")
        },
        check("dotenv: single quotes keep everything as written", "a\\nb") {
            parse("A='a\\nb'").variables == listOf("A" to "a\\nb")
        },
        check("dotenv: an inline comment needs a space before the #", "x, x#y") {
            parse("A=x # c\nB=x#y").variables == listOf("A" to "x", "B" to "x#y")
        },
        check("dotenv: a # inside quotes stays", "a # b") { parse("A=\"a # b\" # real comment").variables == listOf("A" to "a # b") },
        check("dotenv: an empty value and KEY= # comment", "two empty values") {
            parse("A=\nB= # c").variables == listOf("A" to "", "B" to "")
        },
        check("dotenv: spaces around = are allowed", "A=1") { parse("A = 1").variables == listOf("A" to "1") },
        check("dotenv: bad lines are counted and skipped", "4 skipped") {
            val r = parse("lower=1\nNOEQUALS\nGITHUB_X=1\nA=\"open\nB=2")
            r.variables == listOf("B" to "2") && r.skippedLines == 4
        },
        check("dotenv: a name that appears twice keeps its last value", "2") { parse("A=1\nA=2").variables == listOf("A" to "2") },
        check("dotenv: Windows line endings and a BOM", "two variables") {
            parse("\uFEFFA=1\r\nB=2\r\n").variables == listOf("A" to "1", "B" to "2")
        },
        check("dotenv: a value over 2000 characters is skipped", "1 skipped") {
            val r = parse("A=" + "x".repeat(2001))
            r.variables.isEmpty() && r.skippedLines == 1
        },
    )
}

private fun envScriptChecks(): List<CoderCheckResult> = listOf(
    check("env.js: no variables", "empty object") { CoderEnvRules.generateEnvJs(emptyMap()) == "window.__ENV__ = Object.freeze({});" },
    check("env.js: names in alphabetical order", "exact text") {
        CoderEnvRules.generateEnvJs(mapOf("B" to "2", "A" to "1")) == "window.__ENV__ = Object.freeze({\"A\":\"1\",\"B\":\"2\"});"
    },
    check("env.js: </script> cannot close a script tag", "escaped") {
        val js = CoderEnvRules.generateEnvJs(mapOf("A" to "</script><script>x</script>"))
        !js.contains("</") && js.contains("<\\/script>")
    },
    check("env.js: quotes and backslashes are escaped", "escaped") {
        val js = CoderEnvRules.generateEnvJs(mapOf("A" to "a\"b\\c"))
        js.contains("\"a\\\"b\\\\c\"")
    },
    check("env.js: a web address keeps its slashes", "plain slashes") {
        CoderEnvRules.generateEnvJs(mapOf("A" to "https://x.y/z")).contains("\"https://x.y/z\"")
    },
    check("env.js: <!-- and the line separators are escaped", "escaped") {
        val js = CoderEnvRules.generateEnvJs(mapOf("A" to "<!-- \u2028 \u2029"))
        !js.contains("<!--") && !js.contains("\u2028") && !js.contains("\u2029") && js.contains("\\u2028") && js.contains("\\u2029")
    },
    check("env.js: control characters are escaped", "escaped") {
        val js = CoderEnvRules.jsString("a\nb\u0001")
        !js.contains("\n") && js.contains("\\n") && js.contains("\\u0001")
    },
    check("env.json: reads back to the same values", "same map") {
        val vars = mapOf("A" to "x\"y\\z/</script>", "B" to "é ✓")
        val json = org.json.JSONObject(CoderEnvRules.generateEnvJson(vars))
        vars.all { (k, v) -> json.getString(k) == v } && json.length() == 2
    },
)

private fun envStoreChecks(context: Context): List<CoderCheckResult> {
    if (!CoderEnvKeyCrypto.isAvailable()) {
        return listOf(CoderCheckResult("Env store: Keystore unavailable on this phone, checks skipped", true, "ok"))
    }
    val envStore = CoderEnvStore.shared(context)
    val id = newId()
    val other = newId()
    val secret = "ENVTESTVALUE" + newId()
    val out = ArrayList<CoderCheckResult>()
    out.add(check("Env store: save then load gives the same variables", "same list") {
        runBlocking {
            val vars = listOf(EnvVar("API_URL", "https://x.y/z"), EnvVar("TOKEN_VALUE", secret), EnvVar("EMPTY", ""))
            envStore.save(id, vars) && envStore.load(id) == vars
        }
    })
    out.add(check("Env store: the cache answers without waiting", "same map") {
        CoderEnvCache.get(id)["TOKEN_VALUE"] == secret && CoderEnvCache.get(id).size == 3
    })
    out.add(check("Env store: the saved file holds no plain value", "no plain text") {
        val file = File(context.applicationContext.filesDir, "datastore/neribo_coder_env.preferences_pb")
        file.isFile && !String(file.readBytes(), Charsets.ISO_8859_1).contains(secret)
    })
    out.add(check("Env store: crypto round trip, new IV each time, tampering refused", "round trip") {
        val bytes = "hello".toByteArray()
        val a = CoderEnvKeyCrypto.encrypt(bytes)
        val b = CoderEnvKeyCrypto.encrypt(bytes)
        val back = if (a == null) null else CoderEnvKeyCrypto.decrypt(a.ciphertext, a.iv)
        val wrong = if (a == null) null else CoderEnvKeyCrypto.decrypt(a.ciphertext, Base64.encodeToString(ByteArray(12), Base64.NO_WRAP))
        a != null && b != null && back != null && back.contentEquals(bytes) && a.iv != b.iv && wrong == null
    })
    out.add(check("Env store: its key alias is its own", "different from the others") {
        CoderEnvKeyCrypto.KEY_ALIAS == "neribo_coder_env_key" && CoderEnvKeyCrypto.KEY_ALIAS != CoderGitHubKeyCrypto.KEY_ALIAS
    })
    out.add(check("Env store: bad names, duplicates, 51 variables and bad ids are refused", "all false") {
        runBlocking {
            !envStore.save(id, listOf(EnvVar("lower", "x"))) &&
                !envStore.save(id, listOf(EnvVar("A", "1"), EnvVar("A", "2"))) &&
                !envStore.save(id, (1..51).map { EnvVar("V$it", "x") }) &&
                !envStore.save("../bad", listOf(EnvVar("A", "1")))
        }
    })
    out.add(check("Env store: purge removes projects that are not listed", "other gone, id kept") {
        runBlocking {
            envStore.save(other, listOf(EnvVar("A", "1")))
            envStore.purgeOrphans(setOf(id))
            envStore.load(other).isEmpty() && envStore.load(id).size == 3
        }
    })
    out.add(check("Env store: saving an empty list removes the entry", "empty") {
        runBlocking { envStore.save(id, emptyList()) && envStore.load(id).isEmpty() && CoderEnvCache.get(id).isEmpty() }
    })
    out.add(check("Env store: purge with no projects removes everything", "empty") {
        runBlocking {
            envStore.save(id, listOf(EnvVar("A", "1")))
            envStore.purgeOrphans(emptySet())
            envStore.load(id).isEmpty()
        }
    })
    return out
}

private fun analyzerChecks(): List<CoderCheckResult> {
    fun plan(json: String?, files: Set<String> = emptySet(), config: String? = null) = CoderPackageAnalyzer.analyze(json, files, config)
    val vite = """{"scripts":{"build":"vite build"},"devDependencies":{"vite":"^5.0.0"}}"""
    return listOf(
        check("Analyzer: no package.json", "unsupported") { plan(null).let { !it.supported && it.unsupportedReason == CoderPackageAnalyzer.MSG_NO_PACKAGE } },
        check("Analyzer: broken package.json", "unsupported") { plan("{not json").let { !it.supported && it.unsupportedReason == CoderPackageAnalyzer.MSG_UNREADABLE } },
        check("Analyzer: no build script", "unsupported") {
            plan("""{"scripts":{"start":"x"}}""").let { !it.supported && it.unsupportedReason == CoderPackageAnalyzer.MSG_NO_BUILD }
        },
        check("Analyzer: Vite uses dist and npm install without a lock file", "Vite, dist, npm install") {
            plan(vite).let { it.supported && it.tool == CoderBuildTools.VITE && it.outputDir == "dist" && it.installCommand == "npm install" && it.buildCommand == "npm run build" }
        },
        check("Analyzer: Create React App uses build", "build") {
            plan("""{"scripts":{"build":"react-scripts build"},"dependencies":{"react-scripts":"5"}}""").let { it.tool == CoderBuildTools.CREATE_REACT_APP && it.outputDir == "build" }
        },
        check("Analyzer: Parcel and webpack use dist", "dist") {
            plan("""{"scripts":{"build":"parcel build index.html"},"devDependencies":{"parcel":"2"}}""").tool == CoderBuildTools.PARCEL &&
                plan("""{"scripts":{"build":"webpack"},"devDependencies":{"webpack":"5"}}""").tool == CoderBuildTools.WEBPACK
        },
        check("Analyzer: an unknown tool is allowed with a warning", "supported, one warning") {
            plan("""{"scripts":{"build":"node make.js"}}""").let { it.supported && it.tool == CoderBuildTools.UNKNOWN && it.warnings == listOf(CoderPackageAnalyzer.MSG_CHECK_OUTPUT) }
        },
        check("Analyzer: Next and Nuxt need a server", "unsupported") {
            plan("""{"scripts":{"build":"next build"},"dependencies":{"next":"14"}}""").unsupportedReason == CoderPackageAnalyzer.MSG_SERVER &&
                plan("""{"scripts":{"build":"nuxt build"},"dependencies":{"nuxt":"3"}}""").unsupportedReason == CoderPackageAnalyzer.MSG_SERVER
        },
        check("Analyzer: Angular, Astro and SvelteKit are not supported yet", "unsupported") {
            listOf("@angular/cli", "astro", "@sveltejs/kit").all { dep ->
                !plan("""{"scripts":{"build":"x build"},"devDependencies":{"$dep":"1","vite":"5"}}""").supported
            }
        },
        check("Analyzer: Svelte with plain Vite is supported", "Vite") {
            plan("""{"scripts":{"build":"vite build"},"devDependencies":{"svelte":"4","vite":"5"}}""").tool == CoderBuildTools.VITE
        },
        check("Analyzer: lock files choose the commands", "npm ci, yarn, pnpm") {
            plan(vite, setOf("package-lock.json")).let { it.installCommand == "npm ci" && it.buildCommand == "npm run build" } &&
                plan(vite, setOf("yarn.lock")).let { it.installCommand == "corepack enable && yarn install --frozen-lockfile" && it.buildCommand == "yarn build" } &&
                plan(vite, setOf("pnpm-lock.yaml")).let { it.installCommand == "corepack enable && pnpm install --frozen-lockfile" && it.buildCommand == "pnpm build" }
        },
        check("Analyzer: Node version from engines", "18, 20, 22") {
            CoderPackageAnalyzer.nodeMajorFor(">=18") == 18 && CoderPackageAnalyzer.nodeMajorFor("^20.1") == 20 &&
                CoderPackageAnalyzer.nodeMajorFor("16") == 18 && CoderPackageAnalyzer.nodeMajorFor("18.x") == 18 &&
                CoderPackageAnalyzer.nodeMajorFor("22.1.0") == 22 && CoderPackageAnalyzer.nodeMajorFor("24") == 22 &&
                CoderPackageAnalyzer.nodeMajorFor("19") == 20 && CoderPackageAnalyzer.nodeMajorFor(null) == 20 &&
                CoderPackageAnalyzer.nodeMajorFor("*") == 20 && CoderPackageAnalyzer.nodeMajorFor("lts") == 20
        },
        check("Analyzer: engines.node in package.json is used", "18") {
            plan("""{"scripts":{"build":"vite build"},"devDependencies":{"vite":"5"},"engines":{"node":">=18"}}""").nodeVersion == 18
        },
        check("Analyzer: a Vite base path other than / warns", "warning") {
            plan("""{"scripts":{"build":"vite build --base=/app/"},"devDependencies":{"vite":"5"}}""").warnings.contains(CoderPackageAnalyzer.MSG_BASE) &&
                plan(vite, config = "export default { base: '/x/' }").warnings.contains(CoderPackageAnalyzer.MSG_BASE) &&
                plan(vite, config = "export default { base: '/' }").warnings.isEmpty()
        },
        check("Analyzer: a Create React App homepage warns", "warning") {
            plan("""{"scripts":{"build":"react-scripts build"},"dependencies":{"react-scripts":"5"},"homepage":"https://x.github.io/y"}""")
                .warnings.contains(CoderPackageAnalyzer.MSG_HOMEPAGE)
        },
        check("Analyzer: output folder names", "plain folders only") {
            CoderPackageAnalyzer.isValidOutputDir("dist") && CoderPackageAnalyzer.isValidOutputDir("build/web") &&
                !CoderPackageAnalyzer.isValidOutputDir("") && !CoderPackageAnalyzer.isValidOutputDir("../x") &&
                !CoderPackageAnalyzer.isValidOutputDir("a b") && !CoderPackageAnalyzer.isValidOutputDir("/abs") &&
                !CoderPackageAnalyzer.isValidOutputDir("a\nb: x") && !CoderPackageAnalyzer.isValidOutputDir("./x")
        },
        check("Analyzer: variable name prefix hints", "Vite and CRA only") {
            CoderPackageAnalyzer.prefixHint(CoderBuildTools.VITE, listOf("VITE_A", "B")) != null &&
                CoderPackageAnalyzer.prefixHint(CoderBuildTools.VITE, listOf("VITE_A")) == null &&
                CoderPackageAnalyzer.prefixHint(CoderBuildTools.CREATE_REACT_APP, listOf("X"))!!.contains("REACT_APP_") &&
                CoderPackageAnalyzer.prefixHint(CoderBuildTools.WEBPACK, listOf("X")) == null
        },
    )
}

private fun workflowChecks(): List<CoderCheckResult> {
    val viteJson = """{"scripts":{"build":"vite build"},"devDependencies":{"vite":"5"}}"""
    val plan = CoderPackageAnalyzer.analyze(viteJson, setOf("package-lock.json"))
    val expectedNoVars = "# Generated by Neribo Vault Coder. You can edit this file.\n" +
        "name: Coder build\non:\n  workflow_dispatch:\npermissions:\n  contents: read\njobs:\n  build:\n" +
        "    runs-on: ubuntu-latest\n    timeout-minutes: 15\n    env:\n      CI: \"false\"\n    steps:\n" +
        "      - uses: actions/checkout@v4\n      - uses: actions/setup-node@v4\n        with:\n          node-version: 20\n" +
        "      - name: Install\n        run: npm ci\n      - name: Build\n        run: npm run build\n" +
        "      - uses: actions/upload-artifact@v4\n        with:\n          name: coder-dist\n          path: dist\n" +
        "          if-no-files-found: error\n          retention-days: 7\n"
    return listOf(
        check("Workflow: exact text without variables", "the fixed template") { CoderBuildWorkflow.generate(plan, emptyList()) == expectedNoVars },
        check("Workflow: variable names only, sorted, once each", "two lines") {
            val text = CoderBuildWorkflow.generate(plan, listOf("B_VAR", "A_VAR", "B_VAR"))
            text.contains("      CI: \"false\"\n      A_VAR: \${{ vars.A_VAR }}\n      B_VAR: \${{ vars.B_VAR }}\n    steps:") &&
                text.split("B_VAR:").size == 2
        },
        check("Workflow: starts with the marker and says workflow_dispatch", "marker first") {
            val text = CoderBuildWorkflow.generate(plan, emptyList())
            text.lineSequence().first() == CoderBuildWorkflow.MARKER && text.contains("on:\n  workflow_dispatch:\n")
        },
        check("Workflow: never holds a variable value", "no value") {
            !CoderBuildWorkflow.generate(plan, listOf("API_URL")).contains("https")
        },
        check("Workflow: yarn commands and Node 22 land in the file", "commands") {
            val yarn = CoderPackageAnalyzer.analyze(
                """{"scripts":{"build":"vite build"},"devDependencies":{"vite":"5"},"engines":{"node":"22"}}""", setOf("yarn.lock"),
            )
            val text = CoderBuildWorkflow.generate(yarn, emptyList())
            text.contains("node-version: 22") && text.contains("run: corepack enable && yarn install --frozen-lockfile") && text.contains("run: yarn build")
        },
        check("Workflow: Create React App uploads build", "path: build") {
            val cra = CoderPackageAnalyzer.analyze("""{"scripts":{"build":"react-scripts build"},"dependencies":{"react-scripts":"5"}}""", emptySet())
            CoderBuildWorkflow.generate(cra, emptyList()).contains("          path: build\n")
        },
        check("Workflow: an unsafe output folder becomes dist", "dist") {
            CoderBuildWorkflow.generate(plan.copy(outputDir = "x\n  evil: 1"), emptyList()).contains("          path: dist\n")
        },
        check("Workflow: only a file with the marker is ours", "marker rule") {
            CoderBuildWorkflow.isOurs(CoderBuildWorkflow.MARKER + "\nname: x") && !CoderBuildWorkflow.isOurs("name: mine\n") &&
                !CoderBuildWorkflow.isOurs("\n" + CoderBuildWorkflow.MARKER) && !CoderBuildWorkflow.isOurs("")
        },
        check("Workflow: path and file name", "fixed") {
            CoderBuildWorkflow.WORKFLOW_PATH == ".github/workflows/coder-build.yml" && CoderPaths.isValidPath(CoderBuildWorkflow.WORKFLOW_PATH)
        },
    )
}

private fun actionsRequestChecks(): List<CoderCheckResult> {
    val tok = "ghp_TESTTOKEN000000000000000000000000000"
    fun urlOf(r: okhttp3.Request) = r.url.toString()
    fun body(r: okhttp3.Request): String {
        val buffer = okio.Buffer()
        r.body?.writeTo(buffer)
        return buffer.readUtf8()
    }
    val base = "https://api.github.com/repos/me/site"
    return listOf(
        check("Actions request: create variable", "POST variables with name and value") {
            val r = CoderActionsRequests.createVariable(tok, "me", "site", "API_URL", "https://x")
            r.method == "POST" && urlOf(r) == "$base/actions/variables" && org.json.JSONObject(body(r)).getString("name") == "API_URL"
        },
        check("Actions request: update variable", "PATCH variables/NAME") {
            val r = CoderActionsRequests.updateVariable(tok, "me", "site", "API_URL", "v")
            r.method == "PATCH" && urlOf(r) == "$base/actions/variables/API_URL"
        },
        check("Actions request: dispatch", "POST dispatches with ref") {
            val r = CoderActionsRequests.dispatch(tok, "me", "site", "main")
            r.method == "POST" && urlOf(r) == "$base/actions/workflows/coder-build.yml/dispatches" && org.json.JSONObject(body(r)).getString("ref") == "main"
        },
        check("Actions request: runs list", "GET with event, branch, per_page") {
            val r = CoderActionsRequests.runs(tok, "me", "site", "feat/x")
            r.method == "GET" && r.url.queryParameter("event") == "workflow_dispatch" && r.url.queryParameter("branch") == "feat/x" && r.url.queryParameter("per_page") == "5"
        },
        check("Actions request: run, jobs, cancel, artifacts, zip, logs", "addresses") {
            urlOf(CoderActionsRequests.run(tok, "me", "site", 7)) == "$base/actions/runs/7" &&
                urlOf(CoderActionsRequests.jobs(tok, "me", "site", 7)) == "$base/actions/runs/7/jobs" &&
                CoderActionsRequests.cancel(tok, "me", "site", 7).let { it.method == "POST" && urlOf(it) == "$base/actions/runs/7/cancel" } &&
                urlOf(CoderActionsRequests.artifacts(tok, "me", "site", 7)) == "$base/actions/runs/7/artifacts" &&
                urlOf(CoderActionsRequests.artifactZip(tok, "me", "site", 9)) == "$base/actions/artifacts/9/zip" &&
                urlOf(CoderActionsRequests.jobLogs(tok, "me", "site", 5)) == "$base/actions/jobs/5/logs"
        },
        check("Actions request: https, api.github.com, token only in the header", "safe") {
            val r = CoderActionsRequests.dispatch(tok, "me", "site", "main")
            r.url.isHttps && r.url.host == "api.github.com" && r.header("Authorization") == "Bearer $tok" &&
                !urlOf(r).contains(tok) && r.header("X-GitHub-Api-Version") == "2022-11-28"
        },
    )
}

private fun actionsLogicChecks(): List<CoderCheckResult> {
    fun run(id: Long, at: Long) = ActionRun(id, "queued", null, at, "sha")
    fun art(name: String, size: Long, expired: Boolean = false) = ActionArtifact(1, name, size, expired)
    return listOf(
        check("Run pick: the newest run after the start", "run 3") {
            CoderActionsLogic.pickRun(listOf(run(1, 1_000), run(2, 100_000), run(3, 101_000)), 100_000)?.id == 3L
        },
        check("Run pick: 15 seconds of slack before the start", "run 2, none") {
            CoderActionsLogic.pickRun(listOf(run(2, 86_000)), 100_000)?.id == 2L && CoderActionsLogic.pickRun(listOf(run(2, 84_999)), 100_000) == null
        },
        check("Run pick: no runs", "null") { CoderActionsLogic.pickRun(emptyList(), 5) == null },
        check("Artifact pick: the named, live, small one", "found") {
            CoderActionsLogic.pickArtifact(listOf(art("other", 1), art("coder-dist", 1000))).artifact?.name == "coder-dist"
        },
        check("Artifact pick: missing, expired and too big give a reason", "three problems") {
            CoderActionsLogic.pickArtifact(listOf(art("other", 1))).problem != null &&
                CoderActionsLogic.pickArtifact(listOf(art("coder-dist", 1, expired = true))).problem!!.contains("expired") &&
                CoderActionsLogic.pickArtifact(listOf(art("coder-dist", 61L * 1024 * 1024))).problem!!.contains("60 MB") &&
                CoderActionsLogic.pickArtifact(listOf(art("coder-dist", 60L * 1024 * 1024))).artifact != null
        },
        check("Dispatch retry: 404 and the missing trigger 422 only", "retry rules") {
            CoderActionsLogic.shouldRetryDispatch(404, null) && CoderActionsLogic.shouldRetryDispatch(422, "Workflow does not have 'workflow_dispatch' trigger") &&
                !CoderActionsLogic.shouldRetryDispatch(422, "Invalid ref") && !CoderActionsLogic.shouldRetryDispatch(403, null) && !CoderActionsLogic.shouldRetryDispatch(500, null)
        },
        check("Step state: done, failed, running, waiting", "mapping") {
            mapStepState("completed", "success") == BuildStageState.Done && mapStepState("completed", "skipped") == BuildStageState.Done &&
                mapStepState("completed", "neutral") == BuildStageState.Done && mapStepState("completed", "failure") == BuildStageState.Failed &&
                mapStepState("completed", "cancelled") == BuildStageState.Failed && mapStepState("completed", "timed_out") == BuildStageState.Failed &&
                mapStepState("completed", null) == BuildStageState.Failed && mapStepState("in_progress", null) == BuildStageState.Running &&
                mapStepState("queued", null) == BuildStageState.Waiting && mapStepState("pending", null) == BuildStageState.Waiting
        },
        check("Log tail: timestamps and colours go, 60 lines stay", "60 clean lines") {
            val raw = (1..100).joinToString("\n") { "2026-10-19T10:00:00.1234567Z \u001B[31mline $it\u001B[0m" }
            val tail = CoderActionsLogic.cleanLogTail(raw).lines()
            tail.size == 60 && tail.first() == "line 41" && tail.last() == "line 100"
        },
        check("Log tail: a line that looks like a secret is hidden", "hidden") {
            val key = "AKIA" + "ABCDEFGHIJKLMNOP"
            val tail = CoderActionsLogic.cleanLogTail("ok line\nusing key $key now\nlast line")
            !tail.contains(key) && tail.contains("ok line") && tail.contains("last line") && tail.contains("hidden")
        },
        check("Log tail: empty log", "empty") { CoderActionsLogic.cleanLogTail("") == "" },
        check("Error text: workflow permission", "workflow message") {
            CoderActionsErrors.map(403, "4999", "Resource not accessible by personal access token").message == CoderActionsErrors.MSG_WORKFLOW_SCOPE &&
                CoderActionsErrors.map(404, null, "Not Found").message == CoderActionsErrors.MSG_ACTIONS_OFF
        },
        check("Error text: no build minutes", "minutes message") {
            CoderActionsErrors.map(403, "4999", "Billing: you have used all your minutes").message == CoderActionsErrors.MSG_NO_MINUTES
        },
        check("Error text: other answers use the existing messages", "same as before") {
            CoderActionsErrors.map(401, null, null).message == CoderGitHubErrors.map(401, null, null).message
        },
        check("Error text: a push refused for the workflow permission", "workflow message") {
            val err = CoderGitHubResult.Err(CoderGitHubErrorKind.Invalid, "refusing to allow an OAuth App to update workflow without workflow scope")
            CoderActionsErrors.improvePushError(err).message == CoderActionsErrors.MSG_WORKFLOW_SCOPE &&
                CoderActionsErrors.improvePushError(CoderGitHubResult.Err(CoderGitHubErrorKind.Other, "Something else")).message == "Something else"
        },
        check("Error text: no message holds a token or an address", "clean") {
            listOf(CoderActionsErrors.MSG_WORKFLOW_SCOPE, CoderActionsErrors.MSG_NO_MINUTES, CoderActionsErrors.MSG_ACTIONS_OFF).none { it.contains("http") || it.contains("ghp_") }
        },
        check("Scopes: workflow ticked, missing, or no list", "rules") {
            scopesAllowWorkflow("repo, workflow") && !scopesAllowWorkflow("repo") && !scopesAllowWorkflow("repo, workflow_x") && scopesAllowWorkflow(null)
        },
        check("Failure sentences: the common ones", "a sentence each") {
            listOf(
                "npm ERR! Missing script: \"build\"", "Error: Cannot find module 'x'", "Module not found: Error: Can't resolve './a'",
                "npm ERR! code ERESOLVE", "src/a.ts(1,1): error TS2304", "SyntaxError: Unexpected token", "sh: 1: vite: not found",
                "FATAL ERROR: JavaScript heap out of memory", "npm ERR! code EBADENGINE", "npm ERR! code ELIFECYCLE",
                "ENOENT: no such file or directory, open '/home/runner/work/x/package.json'",
            ).all { CoderBuildErrorExplainer.explain(it) != null }
        },
        check("Failure sentences: specific before general, and unknown is null", "rules") {
            CoderBuildErrorExplainer.explain("Cannot find module 'x'\nnpm ERR! code ELIFECYCLE")!!.contains("can't be found") &&
                CoderBuildErrorExplainer.explain("all fine here") == null
        },
        check("Ask AI handoff: kept once and then gone", "take empties it") {
            val id = newId()
            CoderBuildAiHandoff.put(id, "hello")
            CoderBuildAiHandoff.take(id) == "hello" && CoderBuildAiHandoff.take(id) == null
        },
    )
}

private fun buildStateChecks(store: CoderFileStore): List<CoderCheckResult> {
    val id = newId()
    return listOf(
        check("Build meta: round trip", "same values") {
            val meta = CoderBuildMeta(42, 1_700_000_000_000, "abc123", "dist")
            CoderBuildFiles.parseMeta(CoderBuildFiles.metaJson(meta)) == meta && CoderBuildFiles.parseMeta("nope") == null
        },
        check("Build state: round trip", "same values") {
            val state = CoderBuildStateFile(7, 99, "running", "me", "site", "main")
            CoderBuildFiles.parseState(CoderBuildFiles.stateJson(state)) == state && CoderBuildFiles.parseState("{}") == null
        },
        check("Build files: written and read through the store, hidden from the tree", "found, not listed") {
            val state = CoderBuildStateFile(7, 99, "running", "me", "site", "main")
            store.write(id, "index.html", "<p>x</p>")
            CoderBuildFiles.writeState(store, id, state) && CoderBuildFiles.readState(store, id) == state &&
                CoderBuildFiles.writeMeta(store, id, CoderBuildMeta(1, 2, "s", "dist")) && CoderBuildFiles.readMeta(store, id)?.runId == 1L &&
                store.listAll(id).map { it.path } == listOf("index.html") &&
                store.listAll(id, includeHidden = true).any { it.path == ".coder" } && store.fileCount(id) == 1
        },
        check("Build files: paths are the planned ones", "fixed") {
            CoderBuildFiles.STATE_PATH == ".coder/build-state.json" && CoderBuildFiles.META_PATH == ".coder-build/.meta" && CoderBuildFiles.BUILD_DIR == ".coder-build"
        },
    )
}

private fun zipBytes(entries: List<Pair<String, ByteArray>>): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        for ((name, data) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(data)
            zip.closeEntry()
        }
    }
    return bytes.toByteArray()
}

private fun extractChecks(store: CoderFileStore): List<CoderCheckResult> {
    fun extract(id: String, entries: List<Pair<String, ByteArray>>, maxFiles: Int = CoderBuildRunner.MAX_FILES, maxBytes: Long = CoderBuildRunner.MAX_BYTES): ExtractResult =
        runBlocking {
            CoderBuildRunner.extractInto(ByteArrayInputStream(zipBytes(entries)), store, id, ".coder-build-tmp-test", maxFiles, maxBytes)
        }
    val good = listOf("index.html" to "<p>hi</p>".toByteArray(), "assets/app.js" to "1".toByteArray(), "assets/" to ByteArray(0))
    return listOf(
        check("Extract: a normal build is unpacked", "2 files") {
            val id = newId()
            val r = extract(id, good)
            r is ExtractResult.Ok && r.files == 2 && File(store.projectDir(id), ".coder-build-tmp-test/assets/app.js").isFile
        },
        check("Extract: ../ names refuse the whole build and leave nothing", "refused, folder gone") {
            val id = newId()
            val r = extract(id, good + ("../x.txt" to "x".toByteArray()))
            r is ExtractResult.Refused && !File(store.projectDir(id), ".coder-build-tmp-test").exists()
        },
        check("Extract: nested ../ and absolute names are refused", "both refused") {
            extract(newId(), good + ("a/../../x" to "x".toByteArray())) is ExtractResult.Refused &&
                extract(newId(), good + ("/etc/x" to "x".toByteArray())) is ExtractResult.Refused &&
                extract(newId(), good + ("C:/x" to "x".toByteArray())) is ExtractResult.Refused &&
                extract(newId(), good + ("..\\x" to "x".toByteArray())) is ExtractResult.Refused
        },
        check("Extract: too many files is refused", "refused") {
            val many = listOf("index.html" to "x".toByteArray()) + (1..6).map { "f$it.txt" to "x".toByteArray() }
            extract(newId(), many, maxFiles = 5) is ExtractResult.Refused
        },
        check("Extract: 5,001 files against the real limit is refused", "refused") {
            val many = (1..5001).map { "f$it.txt" to ByteArray(1) } + ("index.html" to ByteArray(1))
            extract(newId(), many) is ExtractResult.Refused
        },
        check("Extract: a huge entry is refused by the real byte count", "refused, nothing left") {
            val id = newId()
            val r = extract(id, listOf("index.html" to "x".toByteArray(), "big.bin" to ByteArray(2_000_000)), maxBytes = 1_000_000)
            r is ExtractResult.Refused && !File(store.projectDir(id), ".coder-build-tmp-test").exists()
        },
        check("Extract: a build without index.html is refused", "refused") {
            val r = extract(newId(), listOf("a.js" to "x".toByteArray()))
            r is ExtractResult.Refused && r.message == CoderBuildRunner.MSG_NO_INDEX
        },
        check("Extract: something that is not a ZIP is refused", "refused") {
            val r = runBlocking { CoderBuildRunner.extractInto(ByteArrayInputStream("not a zip".toByteArray()), store, newId(), ".coder-build-tmp-test") }
            r is ExtractResult.Refused
        },
        check("Extract: duplicate names are kept once", "1 file") {
            val r = extract(newId(), listOf("index.html" to "a".toByteArray(), "INDEX.html" to "b".toByteArray()))
            r is ExtractResult.Ok && r.files == 1
        },
        check("Extract: unusable names are skipped, not fatal", "ok") {
            extract(newId(), good + ("bad:name.txt" to "x".toByteArray())).let { it is ExtractResult.Ok && it.files == 2 }
        },
        check("Swap: the new build replaces the old and the old is removed", "new content only") {
            val id = newId()
            store.write(id, ".coder-build/index.html", "old")
            extract(id, listOf("index.html" to "new".toByteArray()))
            val swapped = CoderBuildRunner.swapIn(store, id, ".coder-build-tmp-test")
            val dir = store.projectDir(id)
            swapped && File(dir, ".coder-build/index.html").readText() == "new" && !File(dir, ".coder-build-tmp-test").exists() &&
                dir.listFiles().orEmpty().none { it.name.startsWith(CoderBuildRunner.OLD_PREFIX) }
        },
        check("Swap: a refused build leaves the old one untouched", "old stays") {
            val id = newId()
            store.write(id, ".coder-build/index.html", "old")
            extract(id, listOf("a.js" to "x".toByteArray()))
            File(store.projectDir(id), ".coder-build/index.html").readText() == "old"
        },
        check("Swap: with no old build the new one is simply moved in", "moved") {
            val id = newId()
            extract(id, listOf("index.html" to "n".toByteArray()))
            CoderBuildRunner.swapIn(store, id, ".coder-build-tmp-test") && File(store.projectDir(id), ".coder-build/index.html").isFile
        },
        check("Traversal test: the helper", "names") {
            CoderBuildRunner.isTraversal("../a") && CoderBuildRunner.isTraversal("a/../b") && CoderBuildRunner.isTraversal("/a") &&
                CoderBuildRunner.isTraversal("c:/a") && !CoderBuildRunner.isTraversal("a/b.js") && !CoderBuildRunner.isTraversal("a..b/c")
        },
    )
}

private fun c14SeamChecks(context: Context, store: CoderFileStore): List<CoderCheckResult> {
    val out = ArrayList<CoderCheckResult>()
    // Hidden folders
    val hid = newId()
    store.write(hid, "index.html", "<p>x</p>")
    store.write(hid, ".coder/build-state.json", "{}")
    store.write(hid, ".coder-build/index.html", "<p>built</p>")
    store.write(hid, ".github/workflows/coder-build.yml", "x")
    out.add(check("Hidden: the file tree leaves out .coder folders", "no .coder entries") {
        store.listAll(hid).none { it.path.startsWith(".coder") } && store.listAll(hid).any { it.path == ".github" }
    })
    out.add(check("Hidden: includeHidden shows them", "present") { store.listAll(hid, includeHidden = true).any { it.path == ".coder-build" } })
    out.add(check("Hidden: they do not count toward file count or size", "2 files") {
        store.fileCount(hid) == 2 && store.projectSizeBytes(hid) == "<p>x</p>".length.toLong() + 1
    })
    out.add(check("Hidden: a user cannot create or rename into a .coder name", "refused") {
        store.createFile(hid, ".coder-x.txt", "") == CoderWriteResult.InvalidPath && store.createFolder(hid, ".coder-y") == CoderWriteResult.InvalidPath &&
            store.rename(hid, "index.html", ".coder-z") == CoderWriteResult.InvalidPath && isCoderHiddenPath(".coder-build/a") && !isCoderHiddenPath("src/.coder-x") && !isCoderHiddenPath("a.coder")
    })
    out.add(check("Hidden: the ZIP export leaves them out", "no .coder") {
        store.listAll(hid).none { it.path.startsWith(".coder") }
    })
    // ZIP rules and push ignores
    out.add(check("Zip rules: .coder names are skipped at any depth", "system file") {
        CoderZipRules.shouldSkip(".coder-build/index.html") == SkipReason.SYSTEM_FILE &&
            CoderZipRules.shouldSkip("repo-main/.coder/x.json") == SkipReason.SYSTEM_FILE && CoderZipRules.shouldSkip("src/app.js") == null
    })
    out.add(check("Push: .coder folders and .env files are never uploaded", "ignored") {
        CoderPushPlanner.isIgnored(".coder-build/index.html") && CoderPushPlanner.isIgnored(".coder/build-state.json") &&
            CoderPushPlanner.isIgnored(".env") && CoderPushPlanner.isIgnored("a/.env.production") && CoderPushPlanner.isIgnored(".env.local")
    })
    out.add(check("Push: the workflow file and similar names are uploaded", "not ignored") {
        !CoderPushPlanner.isIgnored(".github/workflows/coder-build.yml") && !CoderPushPlanner.isIgnored("a/.envrc") &&
            !CoderPushPlanner.isIgnored("environment.txt") && !CoderPushPlanner.isIgnored("src/env.js")
    })
    // Preview root and detector
    val own = newId()
    store.write(own, "index.html", "<p>own</p>")
    store.write(own, ".coder-build/index.html", "<p>built</p>")
    out.add(check("Preview root: a usable own index.html wins over a build", "empty root") { CoderPreviewRoot.find(store, own) == "" })
    val src = newId()
    store.write(src, "index.html", "<script type=\"module\" src=\"/src/main.jsx\"></script>")
    out.add(check("Preview root: a source index.html without a build still needs one", "NeedsBuild") {
        CoderBuildDetector.check(store, src) is CoderBuildCheck.NeedsBuild
    })
    store.write(src, ".coder-build/index.html", "<p>built</p>")
    out.add(check("Preview root: a source index.html gives way to the build", ".coder-build") { CoderPreviewRoot.find(store, src) == ".coder-build" })
    out.add(check("Detector: a GitHub build is ready", "Ready") { CoderBuildDetector.check(store, src) == CoderBuildCheck.Ready })
    val none = newId()
    store.write(none, "readme.txt", "hi")
    store.write(none, ".coder-build/index.html", "<p>built</p>")
    out.add(check("Preview root: no index.html at all gives the build", ".coder-build") { CoderPreviewRoot.find(store, none) == ".coder-build" })
    val plain = newId()
    store.write(plain, "index.html", "<p>plain</p>")
    out.add(check("Preview root: without a build nothing changes", "empty root") { CoderPreviewRoot.find(store, plain) == "" })
    // Responder
    val host = CoderPreviewHost.hostFor(src)
    val builtResponder = CoderPreviewResponder(context, store, src, ".coder-build")
    fun get(r: CoderPreviewResponder, path: String): WebResourceResponse? = r.respondTo(Uri.parse("https://$host$path"), "GET")
    fun text(r: WebResourceResponse?): String = r?.data?.readBytes()?.toString(Charsets.UTF_8) ?: ""
    out.add(check("Responder: the .coder-build folder can be the preview root", "served") {
        val r = get(builtResponder, "/")
        r?.statusCode == 200 && text(r).contains("built")
    })
    out.add(check("Responder: /.coder-build/ below a normal root stays blocked", "404") {
        val normal = CoderPreviewResponder(context, store, own, "")
        get(normal, "/.coder-build/index.html")?.statusCode == 404
    })
    out.add(check("Responder: /.coder-build/ below the built root is blocked too", "404") {
        get(builtResponder, "/.coder-build/index.html")?.statusCode == 404
    })
    CoderEnvCache.put(src, listOf(EnvVar("API_URL", "https://x.y/</script>")))
    out.add(check("Responder: env.js comes from the cache with no-store", "javascript") {
        val r = get(CoderPreviewResponder(context, store, src, ""), "/__coder/env.js")
        r?.statusCode == 200 && r.mimeType == "text/javascript" && text(r).startsWith("window.__ENV__ = Object.freeze(") &&
            text(r).contains("API_URL") && !text(r).contains("</script") && r.responseHeaders["Cache-Control"] == "no-store" &&
            !r.responseHeaders.containsKey("Access-Control-Allow-Origin")
    })
    out.add(check("Responder: env.json is JSON", "json") {
        val r = get(CoderPreviewResponder(context, store, src, ""), "/__coder/env.json")
        r?.statusCode == 200 && r.mimeType == "application/json" && org.json.JSONObject(text(r)).getString("API_URL").startsWith("https://x.y/")
    })
    out.add(check("Responder: a project with no variables gets an empty env.js", "empty object") {
        text(get(CoderPreviewResponder(context, store, plain, ""), "/__coder/env.js")) == "window.__ENV__ = Object.freeze({});"
    })
    out.add(check("Responder: HEAD of env.js has no body", "empty") {
        val r = CoderPreviewResponder(context, store, src, "").respondTo(Uri.parse("https://$host/__coder/env.js"), "HEAD")
        r?.statusCode == 200 && text(r).isEmpty()
    })
    return out
}

// ---- Phase C15, part A: adding files, moving, recent files, project info ----------------------

private fun c15Check(name: String, expected: String, actual: () -> String): CoderCheckResult =
    check(name, expected, actual) { actual() == expected }

/** Runs [body] on a fresh temporary project and removes the project afterwards, whatever happens. */
private fun <T> withTempProject(store: CoderFileStore, body: (String) -> T): T {
    val id = newId()
    try {
        return body(id)
    } finally {
        store.deleteProject(id)
    }
}

/** A stream of [length] bytes (value 7) that counts what was read from it, so a limit can be checked without a huge file. */
private class CountingStream(private val length: Long) : java.io.InputStream() {
    var readTotal = 0L
        private set

    override fun read(): Int {
        if (readTotal >= length) return -1
        readTotal++
        return 7
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (readTotal >= length) return -1
        val n = minOf(len.toLong(), length - readTotal).toInt()
        java.util.Arrays.fill(b, off, off + n, 7.toByte())
        readTotal += n
        return n
    }
}

private fun tempLeftovers(store: CoderFileStore, id: String): List<String> =
    store.projectDir(id).walkTopDown().filter { it.name.contains(".tmp-") }.map { it.name }.toList()

private fun c15aChecks(store: CoderFileStore): List<CoderCheckResult> =
    c15SanitizeChecks() + c15UniqueChecks() + c15PlanChecks() + c15CopyChecks(store) + c15MoveChecks(store) +
        c15RecentChecks(store) + c15InfoChecks()

private fun c15SanitizeChecks(): List<CoderCheckResult> {
    val out = ArrayList<CoderCheckResult>()
    out.add(c15Check("Add files: forbidden characters become underscores", "a_b_c_d_e_f_g_h_i_j.txt") {
        CoderFileCopy.sanitizeName("a\\b/c:d*e?f\"g<h>i|j.txt")
    })
    out.add(c15Check("Add files: control characters become underscores", "a_b_c.txt") {
        CoderFileCopy.sanitizeName("a\u0001b\nc.txt")
    })
    out.add(c15Check("Add files: spaces at the ends and dots at the end are removed", "report.txt") {
        CoderFileCopy.sanitizeName("  report.txt . .  ")
    })
    out.add(c15Check("Add files: a name of only dots becomes a fallback", "file") { CoderFileCopy.sanitizeName("...") })
    out.add(c15Check("Add files: an empty or blank name becomes a fallback", "file|file") {
        CoderFileCopy.sanitizeName("") + "|" + CoderFileCopy.sanitizeName("   ")
    })
    out.add(c15Check("Add files: Unicode letters are kept", "caf\u00E9 \u65E5\u672C.png") {
        CoderFileCopy.sanitizeName("caf\u00E9 \u65E5\u672C.png")
    })
    out.add(c15Check("Add files: a dot-file keeps its name", ".gitignore") { CoderFileCopy.sanitizeName(".gitignore") })
    val long = "a".repeat(150) + ".png"
    out.add(c15Check("Add files: a long name is cut to 100 characters and keeps its extension", "100|true") {
        val clean = CoderFileCopy.sanitizeName(long)
        clean.length.toString() + "|" + clean.endsWith(".png")
    })
    out.add(c15Check("Add files: a long name without extension is cut to 100", "100") {
        CoderFileCopy.sanitizeName("b".repeat(300)).length.toString()
    })
    out.add(c15Check("Add files: every cleaned name is a valid file name", "all valid") {
        val awkward = listOf("..", " ", "a.", " a", "CON:", "x\\y", "\u0000", "\"\"", "a".repeat(101), "\uD83D\uDE00".repeat(60) + ".jpg")
        val bad = awkward.filter { !CoderPaths.isValidSegment(CoderFileCopy.sanitizeName(it)) }
        if (bad.isEmpty()) "all valid" else "invalid for " + bad.size
    })
    return out
}

private fun c15UniqueChecks(): List<CoderCheckResult> = listOf(
    c15Check("Add files: a free name stays as it is", "logo.png") { CoderFileCopy.uniqueName(setOf("a.png"), "logo.png") },
    c15Check("Add files: one conflict gives (2) before the extension", "logo (2).png") {
        CoderFileCopy.uniqueName(setOf("logo.png"), "logo.png")
    },
    c15Check("Add files: many conflicts count up", "logo (4).png") {
        CoderFileCopy.uniqueName(setOf("logo.png", "logo (2).png", "logo (3).png"), "logo.png")
    },
    c15Check("Add files: a name without extension gets the number at the end", "README (2)") {
        CoderFileCopy.uniqueName(setOf("README"), "README")
    },
    c15Check("Add files: a dot-file gets the number at the end", ".env (2)") { CoderFileCopy.uniqueName(setOf(".env"), ".env") },
    c15Check("Add files: only the last extension is kept apart", "site.tar (2).gz") {
        CoderFileCopy.uniqueName(setOf("site.tar.gz"), "site.tar.gz")
    },
    c15Check("Add files: a unique name stays within 100 characters", "true") {
        val name = "c".repeat(96) + ".png"
        val unique = CoderFileCopy.uniqueName(setOf(name), name)
        (unique.length <= 100 && unique != name && unique.endsWith(" (2).png") && CoderPaths.isValidSegment(unique)).toString()
    },
)

private fun c15PlanChecks(): List<CoderCheckResult> {
    val disk = setOf("a.txt")
    return listOf(
        c15Check("Add files: Replace keeps the name and replaces", "a.txt|true") {
            CoderFileCopy.planName(disk, emptySet(), "a.txt", CoderConflictChoice.Replace).let { it.name + "|" + it.replaces }
        },
        c15Check("Add files: Keep both gives a new name", "a (2).txt|false") {
            CoderFileCopy.planName(disk, emptySet(), "a.txt", CoderConflictChoice.KeepBoth).let { it.name + "|" + it.replaces }
        },
        c15Check("Add files: Skip leaves the file out", "null|false") {
            CoderFileCopy.planName(disk, emptySet(), "a.txt", CoderConflictChoice.Skip).let { it.name + "|" + it.replaces }
        },
        c15Check("Add files: a free name is added as it is whatever the choice", "b.txt|false") {
            CoderFileCopy.planName(disk, emptySet(), "b.txt", CoderConflictChoice.Skip).let { it.name + "|" + it.replaces }
        },
        c15Check("Add files: two files with one name in a batch are never mixed up", "b (2).txt|false") {
            CoderFileCopy.planName(disk, setOf("b.txt"), "b.txt", CoderConflictChoice.Replace).let { it.name + "|" + it.replaces }
        },
        c15Check("Add files: conflicting names are listed once", "a.txt") {
            CoderFileCopy.conflictingNames(disk, listOf("a.txt", "b.txt", "a.txt")).joinToString(",")
        },
        c15Check("Add files: the report heading says what happened", "Added 4 files, 1 skipped|Added 1 file|Nothing was added") {
            listOf(CoderFileCopy.reportTitle(4, 1), CoderFileCopy.reportTitle(1, 0), CoderFileCopy.reportTitle(0, 0)).joinToString("|")
        },
        c15Check("Add files: picture names are recognised in any letter case", "true|true|false|false") {
            listOf("a.PNG", "b.jpeg", "c.svg", "d").map { CoderFileCopy.isImageName(it) }.joinToString("|")
        },
        c15Check("Add files: the per-file limit is 20 MB", "20971520") { CoderFileCopy.MAX_FILE_BYTES.toString() },
    )
}

private fun c15CopyChecks(store: CoderFileStore): List<CoderCheckResult> {
    val out = ArrayList<CoderCheckResult>()
    out.add(c15Check("Copy in: a text file arrives and can be read as text", "text") {
        withTempProject(store) { id ->
            val result = store.copyIn(id, "notes.txt", "hello \u2713".byteInputStream(Charsets.UTF_8), overwrite = false)
            val read = store.read(id, "notes.txt")
            if (result is CoderCopyResult.Copied && read is CoderReadResult.Text && read.text == "hello \u2713") "text" else "$result / $read"
        }
    })
    out.add(c15Check("Copy in: binary bytes arrive identical", "identical") {
        withTempProject(store) { id ->
            val bytes = ByteArray(200_000) { (it * 31 % 256).toByte() }
            val result = store.copyIn(id, "images/p.png", ByteArrayInputStream(bytes), overwrite = false)
            val same = fileBytes(store, id, "images/p.png")?.contentEquals(bytes) == true
            if (result == CoderCopyResult.Copied(200_000L) && same) "identical" else "$result same=$same"
        }
    })
    out.add(c15Check("Copy in: a file over the limit is refused and leaves nothing", "TooLarge|stopped early|no file") {
        withTempProject(store) { id ->
            val stream = CountingStream(10L * 1024 * 1024)
            val limit = 3L * CoderFileCopy.COPY_BUFFER_BYTES + 5
            val result = store.copyIn(id, "big.bin", stream, overwrite = false, maxFileBytes = limit)
            val early = stream.readTotal <= limit + CoderFileCopy.COPY_BUFFER_BYTES
            val none = store.resolve(id, "big.bin")?.exists() == false && tempLeftovers(store, id).isEmpty()
            (if (result == CoderCopyResult.TooLarge) "TooLarge" else "$result") + "|" + (if (early) "stopped early" else "read ${stream.readTotal}") + "|" +
                (if (none) "no file" else "left a file")
        }
    })
    out.add(c15Check("Copy in: a file exactly at the limit is accepted", "Copied(655360)") {
        withTempProject(store) { id ->
            store.copyIn(id, "edge.bin", CountingStream(655_360L), overwrite = false, maxFileBytes = 655_360L).toString().replace("Copied(bytes=", "Copied(")
        }
    })
    out.add(c15Check("Copy in: cancelling removes the partial file", "Cancelled|clean") {
        withTempProject(store) { id ->
            var calls = 0
            val result = store.copyIn(id, "c.bin", CountingStream(5_000_000L), overwrite = false, shouldContinue = { calls++ < 2 })
            val clean = store.resolve(id, "c.bin")?.exists() == false && tempLeftovers(store, id).isEmpty()
            (if (result == CoderCopyResult.Cancelled) "Cancelled" else "$result") + "|" + (if (clean) "clean" else "left a file")
        }
    })
    out.add(c15Check("Copy in: an existing file is not overwritten without permission", "AlreadyExists|old") {
        withTempProject(store) { id ->
            store.write(id, "a.txt", "old")
            val result = store.copyIn(id, "a.txt", "new".byteInputStream(), overwrite = false)
            (if (result == CoderCopyResult.AlreadyExists) "AlreadyExists" else "$result") + "|" +
                (fileBytes(store, id, "a.txt")?.toString(Charsets.UTF_8) ?: "missing")
        }
    })
    out.add(c15Check("Copy in: with permission the file is replaced", "new") {
        withTempProject(store) { id ->
            store.write(id, "a.txt", "old")
            store.copyIn(id, "a.txt", "new".byteInputStream(), overwrite = true)
            fileBytes(store, id, "a.txt")?.toString(Charsets.UTF_8) ?: "missing"
        }
    })
    out.add(c15Check("Copy in: a failed replace keeps the old file", "TooLarge|old") {
        withTempProject(store) { id ->
            store.write(id, "a.txt", "old")
            val result = store.copyIn(id, "a.txt", CountingStream(5_000L), overwrite = true, maxFileBytes = 1_000L)
            (if (result == CoderCopyResult.TooLarge) "TooLarge" else "$result") + "|" + (fileBytes(store, id, "a.txt")?.toString(Charsets.UTF_8) ?: "missing")
        }
    })
    out.add(c15Check("Copy in: a folder with that name is never replaced", "AlreadyExists") {
        withTempProject(store) { id ->
            store.createFolder(id, "docs")
            store.copyIn(id, "docs", "x".byteInputStream(), overwrite = true).let { if (it == CoderCopyResult.AlreadyExists) "AlreadyExists" else "$it" }
        }
    })
    out.add(c15Check("Copy in: hidden and unsafe paths are refused", "InvalidPath x3") {
        withTempProject(store) { id ->
            val results = listOf(".coder/x.txt", "../x.txt", "a//b.txt").map { store.copyIn(id, it, "x".byteInputStream(), overwrite = true) }
            if (results.all { it == CoderCopyResult.InvalidPath }) "InvalidPath x3" else results.joinToString()
        }
    })
    out.add(c15Check("Copy in: a stream that fails gives Failed and no partial file", "Failed|clean") {
        withTempProject(store) { id ->
            val broken = object : java.io.InputStream() {
                override fun read(): Int = throw IOException("boom")
            }
            val result = store.copyIn(id, "f.bin", broken, overwrite = false)
            (if (result == CoderCopyResult.Failed) "Failed" else "$result") + "|" + (if (tempLeftovers(store, id).isEmpty() && store.resolve(id, "f.bin")?.exists() == false) "clean" else "left a file")
        }
    })
    out.add(c15Check("Copy in: a full phone gives NoSpace and no partial file", "NoSpace|clean") {
        withTempProject(store) { id ->
            val full = object : java.io.InputStream() {
                override fun read(): Int = throw IOException("write failed: ENOSPC (No space left on device)")
            }
            val result = store.copyIn(id, "f.bin", full, overwrite = false)
            (if (result == CoderCopyResult.NoSpace) "NoSpace" else "$result") + "|" + (if (tempLeftovers(store, id).isEmpty()) "clean" else "left a file")
        }
    })
    out.add(c15Check("Copy in: added files do not show temp names in the listing", "true") {
        withTempProject(store) { id ->
            store.copyIn(id, "a/b.bin", CountingStream(70_000L), overwrite = false)
            store.listAll(id).none { it.path.contains(".tmp-") }.toString()
        }
    })
    return out
}

private fun c15MoveChecks(store: CoderFileStore): List<CoderCheckResult> {
    val out = ArrayList<CoderCheckResult>()
    out.add(c15Check("Move: a folder cannot go into itself", "false") { CoderFileCopy.canMoveInto("src", "src").toString() })
    out.add(c15Check("Move: a folder cannot go into one of its own folders", "false") { CoderFileCopy.canMoveInto("src", "src/lib").toString() })
    out.add(c15Check("Move: a folder whose name only starts the same can be a target", "true") { CoderFileCopy.canMoveInto("src", "src2").toString() })
    out.add(c15Check("Move: the top of the project is always a target", "true") { CoderFileCopy.canMoveInto("src/a.js", "").toString() })
    out.add(c15Check("Move: the target list starts at the top and leaves out the folder and its folders", ",img,lib,src2") {
        CoderFileCopy.moveTargets(listOf("img", "lib", "src", "src/deep", "src2"), "src").joinToString(",")
    })
    out.add(c15Check("Move: a file moves into a folder and keeps its name", "moved|true|false") {
        withTempProject(store) { id ->
            store.createFolder(id, "js")
            store.write(id, "app.js", "x")
            val result = store.moveEntry(id, "app.js", "js")
            (if (result == CoderWriteResult.Saved) "moved" else "$result") + "|" +
                (store.resolve(id, "js/app.js")?.exists() == true) + "|" + (store.resolve(id, "app.js")?.exists() == true)
        }
    })
    out.add(c15Check("Move: a folder moves with everything inside it", "Saved|inner") {
        withTempProject(store) { id ->
            store.write(id, "a/b/c.txt", "inner")
            store.createFolder(id, "z")
            val result = store.moveEntry(id, "a", "z")
            (if (result == CoderWriteResult.Saved) "Saved" else "$result") + "|" + (fileBytes(store, id, "z/a/b/c.txt")?.toString(Charsets.UTF_8) ?: "missing")
        }
    })
    out.add(c15Check("Move: a file moves back to the top of the project", "Saved|true") {
        withTempProject(store) { id ->
            store.write(id, "js/app.js", "x")
            val result = store.moveEntry(id, "js/app.js", "")
            (if (result == CoderWriteResult.Saved) "Saved" else "$result") + "|" + (store.resolve(id, "app.js")?.exists() == true)
        }
    })
    out.add(c15Check("Move: a name that is already there is refused and nothing changes", "AlreadyExists|one|two") {
        withTempProject(store) { id ->
            store.write(id, "a.txt", "one")
            store.write(id, "d/a.txt", "two")
            val result = store.moveEntry(id, "a.txt", "d")
            (if (result == CoderWriteResult.AlreadyExists) "AlreadyExists" else "$result") + "|" +
                (fileBytes(store, id, "a.txt")?.toString(Charsets.UTF_8) ?: "missing") + "|" + (fileBytes(store, id, "d/a.txt")?.toString(Charsets.UTF_8) ?: "missing")
        }
    })
    out.add(c15Check("Move: a folder into itself or its child is refused", "Failed|Failed|true") {
        withTempProject(store) { id ->
            store.write(id, "a/b/f.txt", "x")
            val self = store.moveEntry(id, "a", "a")
            val child = store.moveEntry(id, "a", "a/b")
            (if (self == CoderWriteResult.Failed) "Failed" else "$self") + "|" + (if (child == CoderWriteResult.Failed) "Failed" else "$child") + "|" +
                (store.resolve(id, "a/b/f.txt")?.exists() == true)
        }
    })
    out.add(c15Check("Move: a target folder that is not there is refused", "Failed") {
        withTempProject(store) { id ->
            store.write(id, "a.txt", "x")
            store.moveEntry(id, "a.txt", "nope").let { if (it == CoderWriteResult.Failed) "Failed" else "$it" }
        }
    })
    out.add(c15Check("Move: hidden paths cannot be moved or targeted", "InvalidPath|InvalidPath") {
        withTempProject(store) { id ->
            store.write(id, "a.txt", "x")
            store.write(id, ".coder/recent.json", "[]")
            val into = store.moveEntry(id, "a.txt", ".coder")
            val from = store.moveEntry(id, ".coder/recent.json", "")
            (if (into == CoderWriteResult.InvalidPath) "InvalidPath" else "$into") + "|" + (if (from == CoderWriteResult.InvalidPath) "InvalidPath" else "$from")
        }
    })
    out.add(c15Check("Move: a move that makes the folders too deep is refused", "Failed|true") {
        withTempProject(store) { id ->
            val deep = (1..10).joinToString("/") { "d$it" }
            store.write(id, "$deep/f.txt", "x")
            store.write(id, "x/y/z/keep.txt", "k")
            val result = store.moveEntry(id, "d1", "x/y/z")
            (if (result == CoderWriteResult.Failed) "Failed" else "$result") + "|" + (store.resolve(id, "$deep/f.txt")?.exists() == true)
        }
    })
    out.add(c15Check("Move: open tabs follow a moved file and a moved folder", "js/app.js,css/a.css|js/app/x.js,css/a.css:js/app/x.js") {
        val file = CoderTabLogic.rename(CoderTabs(listOf("app.js", "css/a.css"), "app.js"), "app.js", CoderFileCopy.movedPath("app.js", "js"))
        val folder = CoderTabLogic.rename(CoderTabs(listOf("app/x.js", "css/a.css"), "app/x.js"), "app", CoderFileCopy.movedPath("app", "js"))
        file.paths.joinToString(",") + "|" + folder.paths.joinToString(",") + ":" + folder.active
    })
    return out
}

private fun c15RecentChecks(store: CoderFileStore): List<CoderCheckResult> {
    val out = ArrayList<CoderCheckResult>()
    out.add(c15Check("Recent: the newest file comes first", "c,b,a") {
        CoderRecentFiles.record(CoderRecentFiles.record(CoderRecentFiles.record(emptyList(), "a"), "b"), "c").joinToString(",")
    })
    out.add(c15Check("Recent: opening a file again moves it to the front without a duplicate", "a,c,b") {
        CoderRecentFiles.record(listOf("c", "b", "a"), "a").joinToString(",")
    })
    out.add(c15Check("Recent: the list keeps only the last 5", "g,f,e,d,c") {
        listOf("a", "b", "c", "d", "e", "f", "g").fold(emptyList<String>()) { list, path -> CoderRecentFiles.record(list, path) }.joinToString(",")
    })
    out.add(c15Check("Recent: files that are gone are dropped", "c,a") {
        CoderRecentFiles.dropMissing(listOf("c", "b", "a"), setOf("a", "c")).joinToString(",")
    })
    out.add(c15Check("Recent: a renamed folder carries its files along", "x/a.js,b.js") {
        CoderRecentFiles.renamed(listOf("old/a.js", "b.js"), "old", "x").joinToString(",")
    })
    out.add(c15Check("Recent: merging keeps the newer entries first and the cap", "n,a,b,c,d") {
        CoderRecentFiles.merge(listOf("n", "a"), listOf("a", "b", "c", "d", "e")).joinToString(",")
    })
    out.add(c15Check("Recent: a damaged file gives an empty list", "[]|[]|[]") {
        listOf("not json", "{\"a\":1}", "[1,2").map { CoderRecentFiles.parse(it) }.joinToString("|")
    })
    out.add(c15Check("Recent: unsafe, hidden and non-text entries are ignored", "ok.js") {
        CoderRecentFiles.parse("[\"../x\", \".coder/recent.json\", 5, \"ok.js\", \"ok.js\"]").joinToString(",")
    })
    out.add(c15Check("Recent: a missing file gives an empty list", "[]") {
        withTempProject(store) { id -> CoderRecentFiles.load(store, id).toString() }
    })
    out.add(c15Check("Recent: a saved list is read back and stays hidden", "a.js,src/b.css|true|false") {
        withTempProject(store) { id ->
            store.write(id, "a.js", "x")
            CoderRecentFiles.save(store, id, listOf("a.js", "src/b.css"))
            CoderRecentFiles.load(store, id).joinToString(",") + "|" + (store.resolve(id, CoderRecentFiles.FILE_PATH)?.exists() == true) + "|" +
                store.listAll(id).any { it.path.startsWith(".coder") }
        }
    })
    return out
}

private fun c15InfoChecks(): List<CoderCheckResult> {
    val entries = listOf(
        CoderEntry("src", true, 0L, 0L),
        CoderEntry("src/a.js", false, 500L, 0L),
        CoderEntry("b.png", false, 9_000L, 0L),
        CoderEntry("c.txt", false, 100L, 0L),
        CoderEntry("d.txt", false, 100L, 0L),
        CoderEntry("e.css", false, 700L, 0L),
        CoderEntry("f.md", false, 50L, 0L),
        CoderEntry(".coder/recent.json", false, 99_999L, 0L),
    )
    val stats = coderProjectStats(entries)
    return listOf(
        c15Check("Project info: files and size leave out folders and hidden files", "6|10450") { "${stats.fileCount}|${stats.totalBytes}" },
        c15Check("Project info: the five biggest files, biggest first, ties by path", "b.png,e.css,src/a.js,c.txt,d.txt") {
            stats.largest.joinToString(",") { it.path }
        },
        c15Check("Project info: an empty project has no files", "0|0|0") {
            coderProjectStats(emptyList()).let { "${it.fileCount}|${it.totalBytes}|${it.largest.size}" }
        },
        c15Check("Project info: the template is named in words", "My first web page|Imported ZIP file|Not from a template") {
            listOf("hello-web", "zip", "other").joinToString("|") { coderTemplateLabel(it) }
        },
        c15Check("File kinds: code, picture, other text and other files", "Code|Image|Text|Other|Other|Text") {
            listOf(
                coderFileKindOf("a.js", 10L), coderFileKindOf("p.PNG", 10L), coderFileKindOf("README.md", 10L),
                coderFileKindOf("x.pdf", 10L), coderFileKindOf("big.json", CoderLimits.MAX_EDITABLE_BYTES + 1), coderFileKindOf(".gitignore", 10L),
            ).joinToString("|") { it.name }
        },
        c15Check("File kinds: a big picture is still a picture", "Image") { coderFileKindOf("a.jpg", 15_000_000L).name },
    )
}

// ---- Phase C15, part B: import from GitHub, search across all files, duplicate a project ------

private fun c15bChecks(store: CoderFileStore): List<CoderCheckResult> =
    c15bSearchChecks() + c15bGitHubChecks() + c15bCopyChecks(store) + c15bDuplicateChecks(store)

private fun c15bSearch(files: List<Pair<String, String>>, query: String, caseSensitive: Boolean = false, wholeWord: Boolean = false) =
    CoderProjectSearch.search(files.asSequence(), query, caseSensitive, wholeWord, CoderProjectSearch.MAX_TEXT_MATCHES)

private fun c15bSearchChecks(): List<CoderCheckResult> {
    val out = ArrayList<CoderCheckResult>()
    out.add(c15Check("Search: one character is refused", "true|0") {
        val r = c15bSearch(listOf("a.txt" to "aaa"), "a")
        "${r.refused}|${r.totalCount}"
    })
    out.add(c15Check("Search: blank and multi-line queries are refused", "true|true|true") {
        listOf("  ", "ab\ncd", "ab\r").joinToString("|") { c15bSearch(listOf("a.txt" to "ab cd"), it).refused.toString() }
    })
    out.add(c15Check("Search: a file name matches ignoring case", "src/Main.kt") {
        c15bSearch(listOf("src/Main.kt" to "", "x.txt" to ""), "main").nameMatches.joinToString(",")
    })
    out.add(c15Check("Search: at most 20 file names are listed", "20") {
        c15bSearch((1..30).map { "abc$it.txt" to "" }, "abc").nameMatches.size.toString()
    })
    out.add(c15Check("Search: text matches ignore case by default", "2") {
        c15bSearch(listOf("a.txt" to "Hello hello"), "hello").textMatchCount.toString()
    })
    out.add(c15Check("Search: Aa makes the match exact", "1") {
        c15bSearch(listOf("a.txt" to "Hello hello"), "hello", caseSensitive = true).textMatchCount.toString()
    })
    out.add(c15Check("Search: ab matches whole words only", "2") {
        c15bSearch(listOf("a.txt" to "cat concat cat."), "cat", wholeWord = true).textMatchCount.toString()
    })
    out.add(c15Check("Search: the line number is 1-based", "3") {
        c15bSearch(listOf("a.txt" to "a\nb\nfoo\nd"), "foo").files.first().hits.first().line.toString()
    })
    out.add(c15Check("Search: a long line gives a snippet of 120 characters or less with the match inside", "true|needle") {
        val line = "x".repeat(300) + "needle" + "y".repeat(300)
        val hit = c15bSearch(listOf("a.txt" to line), "needle").files.first().hits.first()
        "${hit.snippet.length <= CoderProjectSearch.MAX_SNIPPET_CHARS}|" + hit.snippet.substring(hit.highlightStart, hit.highlightEnd)
    })
    out.add(c15Check("Search: a cut snippet says so on both sides", "true|true") {
        val line = "x".repeat(300) + "needle" + "y".repeat(300)
        val hit = c15bSearch(listOf("a.txt" to line), "needle").files.first().hits.first()
        "${hit.cutStart}|${hit.cutEnd}"
    })
    out.add(c15Check("Search: at most 20 matches are listed per file", "20|true") {
        val r = c15bSearch(listOf("a.txt" to "kk\n".repeat(50)), "kk")
        "${r.files.first().hits.size}|${r.truncated}"
    })
    out.add(c15Check("Search: at most 200 text matches are listed in all", "200|true") {
        val r = c15bSearch((1..15).map { "f$it.txt" to "kk\n".repeat(30) }, "kk")
        "${r.textMatchCount}|${r.truncated}"
    })
    out.add(c15Check("Search: the app's folders, node_modules and .git are skipped", "0") {
        val files = listOf(".coder/notes.txt" to "needle", "node_modules/p/i.js" to "needle", "a/.git/config" to "needle")
        c15bSearch(files, "needle").totalCount.toString()
    })
    out.add(c15Check("Search: only hidden and library paths are unsearchable", "true|false|false|false") {
        listOf("src/a.js", ".coder/recent.json", "node_modules/x.js", ".git/HEAD").joinToString("|") { CoderProjectSearch.isSearchable(it).toString() }
    })
    out.add(c15Check("Search: a file can match by name and by text", "1|1|2") {
        val r = c15bSearch(listOf("todo.txt" to "todo: write tests"), "todo")
        "${r.nameMatches.size}|${r.textMatchCount}|${r.totalCount}"
    })
    out.add(c15Check("Search: a match never crosses a line", "0") {
        c15bSearch(listOf("a.txt" to "ab\ncd"), "bc").totalCount.toString()
    })
    out.add(c15Check("Search: files keep the order they were given in", "b.txt,a.txt") {
        c15bSearch(listOf("b.txt" to "zzz", "a.txt" to "zzz"), "zzz").files.joinToString(",") { it.path }
    })
    out.add(c15Check("Search: the start of a line is found for a result jump", "0|3|6|6") {
        val text = "ab\ncd\nef"
        listOf(1, 2, 3, 99).joinToString("|") { CoderProjectSearch.lineStartOffset(text, it).toString() }
    })
    return out
}

private fun c15bRepoJson(fullName: String, private: Boolean = false, branch: String = "main", size: Long = 12): String =
    JSONObject().put("full_name", fullName).put("private", private).put("default_branch", branch).put("size", size).toString()

private fun c15bGitHubChecks(): List<CoderCheckResult> {
    val out = ArrayList<CoderCheckResult>()
    out.add(c15Check("GitHub import: the repository list is read", "me/app|true|dev|42") {
        val list = CoderGitHubRepos.parseRepoList("[" + c15bRepoJson("me/app", true, "dev", 42) + "]")
        val first = list?.firstOrNull()
        "${first?.fullName}|${first?.isPrivate}|${first?.defaultBranch}|${first?.sizeKb}"
    })
    out.add(c15Check("GitHub import: an entry with a bad name is left out", "me/ok") {
        val json = "[" + c15bRepoJson("nonsense") + "," + c15bRepoJson("me/ok") + ",{\"size\":1}]"
        CoderGitHubRepos.parseRepoList(json)?.joinToString(",") { it.fullName }.orEmpty()
    })
    out.add(c15Check("GitHub import: missing fields get calm defaults", "false||0") {
        val first = CoderGitHubRepos.parseRepoList("[{\"full_name\":\"me/app\"}]")?.firstOrNull()
        "${first?.isPrivate}|${first?.defaultBranch}|${first?.sizeKb}"
    })
    out.add(c15Check("GitHub import: at most 100 repositories are kept", "100") {
        val json = "[" + (1..150).joinToString(",") { c15bRepoJson("me/r$it") } + "]"
        CoderGitHubRepos.parseRepoList(json)?.size.toString()
    })
    out.add(c15Check("GitHub import: an answer that is not a list gives null", "null|null") {
        "${CoderGitHubRepos.parseRepoList("{}")}|${CoderGitHubRepos.parseRepoList("nope")}"
    })
    out.add(c15Check("GitHub import: the search box ignores case and looks at owner/name", "me/Alpha") {
        val all = CoderGitHubRepos.parseRepoList("[" + c15bRepoJson("me/Alpha") + "," + c15bRepoJson("me/beta") + "]").orEmpty()
        CoderGitHubRepos.filterRepos(all, "ALPH").joinToString(",") { it.fullName }
    })
    out.add(c15Check("GitHub import: push permission and default branch are read", "true|dev|false") {
        val withPush = CoderGitHubRepos.parseRepoDetails(
            "{\"name\":\"app\",\"owner\":{\"login\":\"me\"},\"default_branch\":\"dev\",\"permissions\":{\"push\":true}}", "x", "y",
        )
        val without = CoderGitHubRepos.parseRepoDetails("{\"name\":\"app\"}", "me", "app")
        "${withPush?.canPush}|${withPush?.defaultBranch}|${without?.canPush}"
    })
    out.add(c15Check("GitHub import: details that are not JSON give null", "null") {
        CoderGitHubRepos.parseRepoDetails("<html>", "me", "app").toString()
    })
    out.add(c15Check("GitHub import: a missing repository gets the calm 404 message", CoderGitHubRepos.MSG_NOT_FOUND) {
        CoderGitHubRepos.failureFor(404, null, false).message
    })
    out.add(c15Check("GitHub import: a rate limit gets the calm message", "${CoderGitHubRepos.MSG_RATE_LIMITED}|${CoderGitHubRepos.MSG_RATE_LIMITED}") {
        CoderGitHubRepos.failureFor(403, "0", false).message + "|" + CoderGitHubRepos.failureFor(429, null, true).message
    })
    out.add(c15Check("GitHub import: the 50 MB limit is exact", "false|true|false") {
        val limit = CoderGitHubRepos.MAX_ZIPBALL_BYTES
        "${CoderGitHubRepos.exceedsLimit(limit)}|${CoderGitHubRepos.exceedsLimit(limit + 1)}|${CoderGitHubRepos.exceedsLimit(-1L)}"
    })
    out.add(c15Check("GitHub import: a download past the limit stops early", "TooBig|true") {
        val stream = CountingStream(20L * 1024 * 1024)
        val result = CoderGitHubRepos.copyLimited(stream, ByteArrayOutputStream(), 1L * 1024 * 1024)
        "${result.javaClass.simpleName}|${stream.readTotal < 3L * 1024 * 1024}"
    })
    out.add(c15Check("GitHub import: a download within the limit is copied whole", "1000|1000") {
        val sink = ByteArrayOutputStream()
        val result = CoderGitHubRepos.copyLimited(ByteArrayInputStream(ByteArray(1000)), sink, 1000L)
        "${(result as? CoderLimitedCopyResult.Done)?.bytes}|${sink.size()}"
    })
    out.add(c15Check("GitHub import: a stopped download is reported as stopped", "Stopped") {
        CoderGitHubRepos.copyLimited(ByteArrayInputStream(ByteArray(10)), ByteArrayOutputStream(), 1000L, { false }).javaClass.simpleName
    })
    out.add(c15Check("GitHub import: the list request asks for 100 repositories, newest first", "api.github.com|/user/repos|100|updated") {
        val url = CoderGitHubRepos.userRepos("tok").url
        "${url.host}|${url.encodedPath}|${url.queryParameter("per_page")}|${url.queryParameter("sort")}"
    })
    out.add(c15Check("GitHub import: the download request is https and keeps a slash in the branch", "https|/repos/me/app/zipball/feature/x") {
        val url = CoderGitHubRepos.zipball(null, "me", "app", "feature/x").url
        "${url.scheme}|${url.encodedPath}"
    })
    out.add(c15Check("GitHub import: no token means no Authorization header", "null|Bearer tok") {
        val anonymous = CoderGitHubRepos.zipball(null, "me", "app", "main").header("Authorization")
        val signedIn = CoderGitHubRepos.repoDetails("tok", "me", "app").header("Authorization")
        "$anonymous|$signedIn"
    })
    out.add(c15Check("GitHub import: project names are cleaned and cut to 60", "app|60|GitHub project") {
        listOf("a\u0001pp", "n".repeat(100), "  ").joinToString("|") {
            val name = CoderGitHubRepos.projectNameFor(it)
            if (name.length == 60) "60" else name
        }
    })
    out.add(c15Check("GitHub import: unsafe branch names are refused", "true|true|false|false|false|false") {
        listOf("main", "feature/x", "a..b", "", "a b", "/x").joinToString("|") { CoderGitHubRepos.isSafeRef(it).toString() }
    })
    out.add(c15Check("GitHub import: an imported repository becomes a new project named after it", "Test Repo|github") {
        // The same all-or-nothing importer the ZIP import uses; GitHub's wrapper folder is stripped.
        val zip = zipOf("me-app-1a2b3c/" to "", "me-app-1a2b3c/index.html" to "<p>hi</p>")
        val created = ArrayList<String>()
        val store = CoderFileStore(File(System.getProperty("java.io.tmpdir"), "c15b-import-" + newId()))
        try {
            val result = runBlocking {
                importStream(store, { ByteArrayInputStream(zip) }, CoderGitHubRepos.projectNameFor("Test Repo")) { id, baseName ->
                    created.add(id)
                    created.size == 1 && baseName.isNotEmpty()
                }
            }
            val ok = result is CoderImportResult.Success && store.read(created.first(), "index.html") is CoderReadResult.Text
            (if (ok) "Test Repo" else "failed") + "|" + CoderGitHubRepos.TEMPLATE_ID
        } finally {
            created.forEach { store.deleteProject(it) }
        }
    })
    return out
}

private fun c15bWriteHidden(store: CoderFileStore, id: String, path: String, text: String) {
    val file = File(store.projectDir(id), path)
    file.parentFile?.mkdirs()
    file.writeText(text)
}

private fun c15bCopyChecks(store: CoderFileStore): List<CoderCheckResult> {
    val out = ArrayList<CoderCheckResult>()
    fun filled(id: String) {
        store.createFile(id, "a.txt", "hello")
        store.createFile(id, "dir/b.txt", "world")
        store.createFolder(id, "empty")
        c15bWriteHidden(store, id, ".coder/recent.json", "[]")
        c15bWriteHidden(store, id, ".coder-extra/x.txt", "x")
    }
    out.add(c15Check("Duplicate: files and folders are copied, the original stays", "hello|world|true|hello") {
        withTempProject(store) { from ->
            filled(from)
            val to = newId()
            try {
                val result = store.copyProject(from, to)
                val a = (store.read(to, "a.txt") as? CoderReadResult.Text)?.text
                val b = (store.read(to, "dir/b.txt") as? CoderReadResult.Text)?.text
                val keptOriginal = (store.read(from, "a.txt") as? CoderReadResult.Text)?.text
                "$a|$b|${result is CoderProjectCopyResult.Copied && File(store.projectDir(to), "empty").isDirectory}|$keptOriginal"
            } finally {
                store.deleteProject(to)
            }
        }
    })
    out.add(c15Check("Duplicate: every .coder folder is left out", "false|false|true") {
        withTempProject(store) { from ->
            filled(from)
            val to = newId()
            try {
                store.copyProject(from, to)
                "${File(store.projectDir(to), ".coder").exists()}|${File(store.projectDir(to), ".coder-extra").exists()}|${File(store.projectDir(from), ".coder").exists()}"
            } finally {
                store.deleteProject(to)
            }
        }
    })
    out.add(c15Check("Duplicate: exactly twice the size free is enough, one byte less is not", "Copied|NoSpace|false") {
        withTempProject(store) { from ->
            filled(from)
            val size = store.projectSizeBytes(from)
            val tight = newId()
            val short = newId()
            try {
                val enough = store.copyProject(from, tight, freeSpaceBytes = 2L * size)
                val notEnough = store.copyProject(from, short, freeSpaceBytes = 2L * size - 1L)
                "${enough.javaClass.simpleName}|${notEnough.javaClass.simpleName}|${File(store.projectDir(short), "").exists()}"
            } finally {
                store.deleteProject(tight)
                store.deleteProject(short)
            }
        }
    })
    out.add(c15Check("Duplicate: a stopped copy leaves no folder behind", "Cancelled|false") {
        withTempProject(store) { from ->
            filled(from)
            val to = newId()
            try {
                val result = store.copyProject(from, to, shouldContinue = { false })
                "${result.javaClass.simpleName}|${store.projectDir(to).exists()}"
            } finally {
                store.deleteProject(to)
            }
        }
    })
    out.add(c15Check("Duplicate: a copy that stops half way leaves no folder behind", "Cancelled|false") {
        withTempProject(store) { from ->
            filled(from)
            val to = newId()
            try {
                var asked = 0
                val result = store.copyProject(from, to, shouldContinue = { ++asked < 3 })
                "${result.javaClass.simpleName}|${store.projectDir(to).exists()}"
            } finally {
                store.deleteProject(to)
            }
        }
    })
    out.add(c15Check("Duplicate: a missing source, the same id and an existing target are refused", "NotFound|Failed|AlreadyExists") {
        withTempProject(store) { from ->
            filled(from)
            val missing = store.copyProject(newId(), newId())
            val same = store.copyProject(from, from)
            val existing = withTempProject(store) { other ->
                store.createFile(other, "keep.txt", "k")
                store.copyProject(from, other)
            }
            "${missing.javaClass.simpleName}|${same.javaClass.simpleName}|${existing.javaClass.simpleName}"
        }
    })
    return out
}

/** A Room project table in memory, so the whole Duplicate step (copy, then row) can be checked without a database. */
private class FakeCoderProjectDao : CoderProjectDao {
    val rows = MutableStateFlow<List<CoderProjectEntity>>(emptyList())

    private fun change(id: String, update: (CoderProjectEntity) -> CoderProjectEntity) {
        rows.value = rows.value.map { if (it.id == id) update(it) else it }
    }

    override fun observeAll(): Flow<List<CoderProjectEntity>> = rows.map { list -> list.filter { !it.isDeleted } }
    override fun observeById(id: String): Flow<CoderProjectEntity?> = rows.map { list -> list.firstOrNull { it.id == id && !it.isDeleted } }
    override suspend fun getById(id: String): CoderProjectEntity? = rows.value.firstOrNull { it.id == id }
    override suspend fun upsert(item: CoderProjectEntity) {
        rows.value = rows.value.filter { it.id != item.id } + item
    }
    override suspend fun softDelete(id: String, now: Long) = change(id) { it.copy(isDeleted = true, deletedAt = now) }
    override suspend fun restore(id: String) = change(id) { it.copy(isDeleted = false, deletedAt = null) }
    override fun observeTrashed(): Flow<List<CoderProjectEntity>> = rows.map { list -> list.filter { it.isDeleted } }
    override suspend fun deletePermanently(id: String) {
        rows.value = rows.value.filter { it.id != id }
    }
    override suspend fun trashedIdsBefore(cutoff: Long): List<String> =
        rows.value.filter { it.isDeleted && (it.deletedAt ?: Long.MAX_VALUE) < cutoff }.map { it.id }
    override suspend fun deleteTrashedBefore(cutoff: Long) {
        rows.value = rows.value.filter { !(it.isDeleted && (it.deletedAt ?: Long.MAX_VALUE) < cutoff) }
    }
    override suspend fun setRepoLink(id: String, owner: String?, name: String?, branch: String?, now: Long) =
        change(id) { it.copy(repoOwner = owner, repoName = name, repoBranch = branch, updatedAt = now) }
    override suspend fun markPushed(id: String, time: Long) = change(id) { it.copy(lastPushedAt = time, updatedAt = time) }
    override suspend fun setDeveloperLink(id: String, developerProjectId: String?, now: Long) =
        change(id) { it.copy(developerProjectId = developerProjectId, updatedAt = now) }
    override fun observeByDeveloperProject(developerProjectId: String): Flow<List<CoderProjectEntity>> =
        rows.map { list -> list.filter { it.developerProjectId == developerProjectId && !it.isDeleted } }
}

private fun c15bDuplicateChecks(store: CoderFileStore): List<CoderCheckResult> {
    val out = ArrayList<CoderCheckResult>()
    out.add(c15Check("Duplicate: the first copy is called \"X copy\"", "X copy") { duplicateProjectName("X", emptyList()) })
    out.add(c15Check("Duplicate: later copies count up from 2", "X copy 2|X copy 3") {
        duplicateProjectName("X", listOf("X", "X copy")) + "|" + duplicateProjectName("X", listOf("X", "x COPY", "X copy 2"))
    })
    out.add(c15Check("Duplicate: a long name is cut so the copy name fits 60 characters", "true|true") {
        val first = duplicateProjectName("n".repeat(60), emptyList())
        val second = duplicateProjectName("n".repeat(60), listOf(first))
        "${first.length <= 60 && first.endsWith(" copy")}|${second.length <= 60 && second.endsWith(" copy 2")}"
    })
    out.add(c15Check("Duplicate: the new project keeps the template, drops the links and gets the files", "X copy|web|null|null|hello|false") {
        val dao = FakeCoderProjectDao()
        val repo = CoderProjectsRepository(dao, store)
        val sourceId = newId()
        var copyId: String? = null
        try {
            store.createFile(sourceId, "a.txt", "hello")
            c15bWriteHidden(store, sourceId, ".coder/recent.json", "[]")
            val source = CoderProjectEntity(
                id = sourceId, createdAt = 1L, updatedAt = 1L, name = "X", templateId = "web",
                repoOwner = "me", repoName = "app", repoBranch = "main", developerProjectId = "dev-1",
            )
            runBlocking { dao.upsert(source) }
            val message = runBlocking { duplicateCoderProject(store, repo, source) }
            val copy = dao.rows.value.firstOrNull { it.id != sourceId }
            copyId = copy?.id
            val text = copyId?.let { (store.read(it, "a.txt") as? CoderReadResult.Text)?.text }
            val hidden = copyId?.let { File(store.projectDir(it), ".coder").exists() }
            if (message != "Project duplicated") throw IllegalStateException("message was $message")
            "${copy?.name}|${copy?.templateId}|${copy?.repoOwner}|${copy?.developerProjectId}|$text|$hidden"
        } finally {
            store.deleteProject(sourceId)
            copyId?.let { store.deleteProject(it) }
        }
    })
    out.add(c15Check("Duplicate: copying a project twice gives X copy and X copy 2", "X copy,X copy 2") {
        val dao = FakeCoderProjectDao()
        val repo = CoderProjectsRepository(dao, store)
        val sourceId = newId()
        try {
            store.createFile(sourceId, "a.txt", "hello")
            val source = CoderProjectEntity(id = sourceId, createdAt = 1L, updatedAt = 1L, name = "X", templateId = "web")
            runBlocking { dao.upsert(source) }
            runBlocking { duplicateCoderProject(store, repo, source) }
            runBlocking { duplicateCoderProject(store, repo, source) }
            dao.rows.value.filter { it.id != sourceId }.map { it.name }.sorted().joinToString(",")
        } finally {
            store.deleteProject(sourceId)
            dao.rows.value.forEach { store.deleteProject(it.id) }
        }
    })
    out.add(c15Check("Duplicate: a source that is gone fails calmly and adds no row", "Couldn't duplicate this project. Please try again.|0") {
        val dao = FakeCoderProjectDao()
        val repo = CoderProjectsRepository(dao, store)
        val ghost = CoderProjectEntity(id = newId(), createdAt = 1L, updatedAt = 1L, name = "Ghost", templateId = "web")
        val message = runBlocking { duplicateCoderProject(store, repo, ghost) }
        "$message|${dao.rows.value.size}"
    })
    return out
}

// ---- Phase C16, Part A: streaming, progress and live code cards -----------------------------

private fun c16Check(name: String, expected: String, actual: () -> String): CoderCheckResult =
    check(name, expected, actual) { actual() == expected }

private fun c16Frame(event: String?, json: String): String =
    (if (event != null) "event: $event\n" else "") + "data: $json\n\n"

private val C16_CLAUDE_STREAM: String =
    c16Frame("message_start", """{"type":"message_start","message":{"id":"m1"}}""") +
        c16Frame("ping", """{"type": "ping"}""") +
        c16Frame("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello "}}""") +
        c16Frame("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"world"}}""") +
        c16Frame("message_delta", """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""") +
        c16Frame("message_stop", """{"type":"message_stop"}""")

private val C16_OPENAI_STREAM: String =
    c16Frame(null, """{"choices":[{"delta":{"role":"assistant","content":""}}]}""") +
        c16Frame(null, """{"choices":[{"delta":{"content":"Hi "},"finish_reason":null}]}""") +
        c16Frame(null, """{"choices":[{"delta":{"content":"there"},"finish_reason":null}]}""") +
        c16Frame(null, """{"choices":[{"delta":{},"finish_reason":"stop"}]}""") +
        "data: [DONE]\n\n"

private val C16_GEMINI_STREAM: String =
    (c16Frame(null, """{"candidates":[{"content":{"parts":[{"text":"Hel"}],"role":"model"}}]}""") +
        c16Frame(null, """{"candidates":[{"content":{"parts":[{"text":"lo"}],"role":"model"},"finishReason":"STOP"}]}""")
        ).replace("\n", "\r\n")

private fun c16Show(events: List<CoderStreamEvent>): String = events.joinToString("|") {
    when (it) {
        CoderStreamEvent.Started -> "S"
        is CoderStreamEvent.Text -> "T:" + it.delta
        is CoderStreamEvent.Finished -> "F:" + it.truncated
        is CoderStreamEvent.Failed -> "X:" + it.kind.name
    }
}

private fun c16Parse(provider: CoderAiProvider, data: String, name: String? = null): String =
    c16Show(CoderStreamEvents.parse(provider, name, data))

/** Runs a recorded stream through the decoder the way the client does: line by line, then the end. */
private fun c16Run(provider: CoderAiProvider, stream: String): List<CoderStreamEvent> {
    val decoder = CoderStreamDecoder(provider)
    val out = ArrayList<CoderStreamEvent>()
    val all = stream.split('\n').map { it.removeSuffix("\r") }
    val lines = if (all.isNotEmpty() && all.last().isEmpty()) all.dropLast(1) else all
    for (line in lines) {
        if (decoder.isDone) break
        out.addAll(decoder.feedLine(line))
    }
    out.addAll(decoder.end())
    return out
}

private fun c16Sse(vararg pieces: String): List<SseEvent> {
    val parser = CoderSseParser()
    val out = ArrayList<SseEvent>()
    for (piece in pieces) out.addAll(parser.feed(piece))
    out.addAll(parser.finish())
    return out
}

private fun c16SseSplitEverywhere(text: String): Boolean {
    val whole = c16Sse(text)
    for (i in 0..text.length) {
        if (c16Sse(text.substring(0, i), text.substring(i)) != whole) return false
    }
    return true
}

private fun c16ShowSse(events: List<SseEvent>): String = events.joinToString("|") { (it.event ?: "-") + "=" + it.data }

private fun c16Norm(list: List<CoderReplySegment>): List<CoderReplySegment> = list.map {
    if (it is CoderReplySegment.Code) CoderReplySegment.Code(it.block.copy(unfinished = false)) else it
}

private fun c16Parser(vararg pieces: String): CoderStreamParser {
    val parser = CoderStreamParser()
    for (piece in pieces) parser.feed(piece)
    return parser
}

private fun c16SameAtEveryCut(text: String): Boolean {
    val expected = parseReply(text)
    for (i in 0..text.length) {
        val parser = c16Parser(text.substring(0, i), text.substring(i))
        parser.finish()
        if (c16Norm(parser.segments) != expected) return false
    }
    return true
}

private fun c16SameAtRandomCuts(text: String, seed: Long): Boolean {
    val expected = parseReply(text)
    val random = java.util.Random(seed)
    repeat(40) {
        val parser = CoderStreamParser()
        var pos = 0
        while (pos < text.length) {
            val size = 1 + random.nextInt(11)
            val end = minOf(text.length, pos + size)
            parser.feed(text.substring(pos, end))
            pos = end
        }
        parser.finish()
        if (c16Norm(parser.segments) != expected) return false
    }
    return true
}

private fun c16SameCharByChar(text: String): Boolean {
    val parser = CoderStreamParser()
    for (ch in text) parser.feed(ch.toString())
    parser.finish()
    return c16Norm(parser.segments) == parseReply(text)
}

private val C16_REPLIES: List<String> = listOf(
    "Hello\nworld",
    "Here:\n```html path=index.html\n<p>Hi</p>\n```\nDone",
    "```css path=style.css\na{}\n```\nand\n```js\nlet x = 1\n```",
    "````md\n```js\nx\n```\n````\nafter",
    "Look:\n```js\nlet a = 1;",
    "A\r\n```js\r\nx\r\n```\r\nB\r\n",
    "```html path=../x\n<p></p>\n```",
    "```\n```",
    "Intro\n\n\n```js path=a.js\nline1\n\nline3\n```\n\nOutro\n\n",
    "Use ``` inline ``` here\n`code`\n   ```js\n  indented\n   ```\ntext",
    "```js\nabc\n",
    "",
    "```",
    "One\n```js path=a.js\nx\n```\nTwo\n```html path=b.html\n<p>y</p>\n```\nThree",
)

private fun c16SseChecks(): List<CoderCheckResult> {
    val claudeEvents = c16Sse(C16_CLAUDE_STREAM)
    return listOf(
        c16Check("Stream reader: Claude stream gives six named events", "message_start,ping,content_block_delta,content_block_delta,message_delta,message_stop") {
            claudeEvents.joinToString(",") { it.event ?: "-" }
        },
        c16Check("Stream reader: Claude stream split at every position gives the same events", "true") {
            c16SseSplitEverywhere(C16_CLAUDE_STREAM).toString()
        },
        c16Check("Stream reader: OpenAI stream split at every position gives the same events", "true") {
            c16SseSplitEverywhere(C16_OPENAI_STREAM).toString()
        },
        c16Check("Stream reader: Gemini stream with CRLF split at every position gives the same events", "true") {
            c16SseSplitEverywhere(C16_GEMINI_STREAM).toString()
        },
        c16Check("Stream reader: OpenAI stream has five data events and no names", "5|true") {
            val events = c16Sse(C16_OPENAI_STREAM)
            events.size.toString() + "|" + events.all { it.event == null }
        },
        c16Check("Stream reader: comment lines are ignored", "-=a") {
            c16ShowSse(c16Sse(": keep-alive\ndata: a\n: another\n\n"))
        },
        c16Check("Stream reader: CRLF line ends give the same events as LF", "true") {
            val lf = c16Sse("event: x\ndata: one\ndata: two\n\ndata: three\n\n")
            val crlf = c16Sse("event: x\r\ndata: one\r\ndata: two\r\n\r\ndata: three\r\n\r\n")
            (lf == crlf).toString()
        },
        c16Check("Stream reader: several data lines are joined with a newline", "x=one\ntwo") {
            c16ShowSse(c16Sse("event: x\ndata: one\ndata: two\n\n"))
        },
        c16Check("Stream reader: blank lines alone give no events", "") {
            c16ShowSse(c16Sse("\n\n\r\n\n"))
        },
        c16Check("Stream reader: an event without data gives nothing", "") {
            c16ShowSse(c16Sse("event: ping\n\n"))
        },
        c16Check("Stream reader: only one space after the colon is dropped", "-=  two spaces") {
            c16ShowSse(c16Sse("data:   two spaces\n\n"))
        },
        c16Check("Stream reader: data without a space after the colon", "-={\"a\":1}") {
            c16ShowSse(c16Sse("data:{\"a\":1}\n\n"))
        },
        c16Check("Stream reader: a field without a colon is read as an empty value", "-=") {
            c16ShowSse(c16Sse("data\n\n"))
        },
        c16Check("Stream reader: unknown fields are ignored", "-=a") {
            c16ShowSse(c16Sse("id: 7\nretry: 100\nfoo: bar\ndata: a\n\n"))
        },
        c16Check("Stream reader: garbage never throws", "true") {
            val junk = listOf("\u0000\u0001\n\n", ":::\n:\n\ndata\n\n\n", "event:\ndata:\n\n", "\r\r\r\n\n\n", "data: \uD83D\n\n")
            junk.forEach { c16Sse(it) }
            true.toString()
        },
        c16Check("Stream reader: finish delivers an event that was not closed by a blank line", "-=last") {
            c16ShowSse(c16Sse("data: last"))
        },
        c16Check("Stream reader: the event name does not leak into the next event", "a=1|-=2") {
            c16ShowSse(c16Sse("event: a\ndata: 1\n\ndata: 2\n\n"))
        },
        c16Check("Stream reader: a line over a million characters is dropped and the next event still comes", "ok=ok") {
            val big = "data: " + "x".repeat(1_100_000) + "\n\nevent: ok\ndata: ok\n\n"
            val parser = CoderSseParser()
            val out = ArrayList<SseEvent>()
            out.addAll(parser.feed(big.substring(0, 500_000)))
            out.addAll(parser.feed(big.substring(500_000)))
            out.addAll(parser.finish())
            c16ShowSse(out)
        },
    )
}

private fun c16EventChecks(): List<CoderCheckResult> {
    val claude = CoderAiProvider.CLAUDE
    val openAi = CoderAiProvider.OPENAI
    val gemini = CoderAiProvider.GEMINI
    return listOf(
        c16Check("Claude event: a text piece", "T:Hi") {
            c16Parse(claude, """{"type":"content_block_delta","delta":{"type":"text_delta","text":"Hi"}}""")
        },
        c16Check("Claude event: other delta kinds are ignored", "") {
            c16Parse(claude, """{"type":"content_block_delta","delta":{"type":"input_json_delta","partial_json":"{"}}""")
        },
        c16Check("Claude event: stop reason max_tokens means cut short", "F:true") {
            c16Parse(claude, """{"type":"message_delta","delta":{"stop_reason":"max_tokens"}}""")
        },
        c16Check("Claude event: stop reason end_turn gives nothing yet", "") {
            c16Parse(claude, """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""")
        },
        c16Check("Claude event: message_stop finishes", "F:false") { c16Parse(claude, """{"type":"message_stop"}""") },
        c16Check("Claude event: ping and start events are ignored", "") {
            listOf("ping", "message_start", "content_block_start", "content_block_stop").joinToString("") {
                c16Parse(claude, """{"type":"$it"}""")
            }
        },
        c16Check("Claude event: overloaded error means busy", "X:Busy") {
            c16Parse(claude, """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
        },
        c16Check("Claude event: rate limit error", "X:RateLimited") {
            c16Parse(claude, """{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}""")
        },
        c16Check("Claude event: authentication error means invalid key", "X:InvalidKey") {
            c16Parse(claude, """{"type":"error","error":{"type":"authentication_error","message":"bad"}}""")
        },
        c16Check("Claude event: another error shows the provider's message, one line, 300 characters", "Other|332") {
            val long = "a\n  b " + "x".repeat(500)
            val event = CoderStreamEvents.parse(claude, null, """{"type":"error","error":{"type":"api_error","message":"$long"}}""".replace("\n", "\\n")).single()
            event as CoderStreamEvent.Failed
            event.kind.name + "|" + event.message.length
        },
        c16Check("Claude event: the event name is used when the data has no type", "F:false") {
            c16Show(CoderStreamEvents.parse(claude, "message_stop", "{}"))
        },
        c16Check("Claude event: unknown fields around a text piece are ignored", "T:ok") {
            c16Parse(claude, """{"type":"content_block_delta","index":3,"extra":{"a":1},"delta":{"type":"text_delta","text":"ok","more":true}}""")
        },
        c16Check("Claude event: unreadable data gives nothing", "") {
            listOf("not json", "[1,2]", "", "{", "null", "   ").joinToString("") { c16Parse(claude, it) }
        },
        c16Check("Claude event: a null or empty text piece gives nothing", "") {
            c16Parse(claude, """{"type":"content_block_delta","delta":{"type":"text_delta","text":null}}""") +
                c16Parse(claude, """{"type":"content_block_delta","delta":{"type":"text_delta","text":""}}""")
        },
        c16Check("OpenAI event: a text piece", "T:Hi") {
            c16Parse(openAi, """{"choices":[{"delta":{"content":"Hi"},"finish_reason":null}]}""")
        },
        c16Check("OpenAI event: null, empty or missing content gives nothing", "") {
            c16Parse(openAi, """{"choices":[{"delta":{"content":null}}]}""") +
                c16Parse(openAi, """{"choices":[{"delta":{"content":""}}]}""") +
                c16Parse(openAi, """{"choices":[{"delta":{}}]}""")
        },
        c16Check("OpenAI event: a refusal is shown as text", "T:No.") {
            c16Parse(openAi, """{"choices":[{"delta":{"refusal":"No."}}]}""")
        },
        c16Check("OpenAI event: finish reason length means cut short, after its text", "T:x|F:true") {
            c16Parse(openAi, """{"choices":[{"delta":{"content":"x"},"finish_reason":"length"}]}""")
        },
        c16Check("OpenAI event: finish reason stop gives nothing yet", "") {
            c16Parse(openAi, """{"choices":[{"delta":{},"finish_reason":"stop"}]}""")
        },
        c16Check("OpenAI event: the DONE line finishes", "F:false") { c16Parse(openAi, "[DONE]") },
        c16Check("OpenAI event: empty choices (a usage chunk) give nothing", "") {
            c16Parse(openAi, """{"choices":[],"usage":{"total_tokens":3}}""")
        },
        c16Check("OpenAI event: an error object is a failure", "X:Other") {
            c16Parse(openAi, """{"error":{"message":"boom","type":"server_error"}}""")
        },
        c16Check("OpenAI event: unreadable data gives nothing", "") {
            listOf("oops", "[]", "{\"choices\":5}", "").joinToString("") { c16Parse(openAi, it) }
        },
        c16Check("Gemini event: every text part is a piece", "T:A|T:B") {
            c16Parse(gemini, """{"candidates":[{"content":{"parts":[{"text":"A"},{"text":"B"}]}}]}""")
        },
        c16Check("Gemini event: thought parts are skipped", "T:A") {
            c16Parse(gemini, """{"candidates":[{"content":{"parts":[{"text":"hmm","thought":true},{"text":"A"}]}}]}""")
        },
        c16Check("Gemini event: finish reason MAX_TOKENS means cut short", "T:A|F:true") {
            c16Parse(gemini, """{"candidates":[{"content":{"parts":[{"text":"A"}]},"finishReason":"MAX_TOKENS"}]}""")
        },
        c16Check("Gemini event: finish reason STOP adds nothing", "T:A") {
            c16Parse(gemini, """{"candidates":[{"content":{"parts":[{"text":"A"}]},"finishReason":"STOP"}]}""")
        },
        c16Check("Gemini event: a safety finish is a blocked failure", "X:Blocked") {
            c16Parse(gemini, """{"candidates":[{"finishReason":"SAFETY"}]}""")
        },
        c16Check("Gemini event: a block reason without candidates is a blocked failure", "X:Blocked") {
            c16Parse(gemini, """{"promptFeedback":{"blockReason":"SAFETY"}}""")
        },
        c16Check("Gemini event: no candidates and no block reason gives nothing", "") {
            c16Parse(gemini, """{"usageMetadata":{"totalTokenCount":3}}""") + c16Parse(gemini, """{"candidates":[]}""")
        },
        c16Check("Gemini event: an error object is a failure", "X:Other") {
            c16Parse(gemini, """{"error":{"code":500,"message":"boom"}}""")
        },
        c16Check("Gemini event: unreadable data gives nothing", "") {
            listOf("nope", "[1]", "{\"candidates\":\"x\"}").joinToString("") { c16Parse(gemini, it) }
        },
    )
}

private fun c16DecoderChecks(): List<CoderCheckResult> = listOf(
    c16Check("Decoder: a whole Claude stream", "T:Hello |T:world|F:false") {
        c16Show(c16Run(CoderAiProvider.CLAUDE, C16_CLAUDE_STREAM))
    },
    c16Check("Decoder: a whole OpenAI stream", "T:Hi |T:there|F:false") {
        c16Show(c16Run(CoderAiProvider.OPENAI, C16_OPENAI_STREAM))
    },
    c16Check("Decoder: a whole Gemini stream ends at the end of the stream", "T:Hel|T:lo|F:false") {
        c16Show(c16Run(CoderAiProvider.GEMINI, C16_GEMINI_STREAM))
    },
    c16Check("Decoder: Claude stream that breaks off keeps its text, then fails", "T:Hello |X:Network") {
        val cut = C16_CLAUDE_STREAM.substringBefore("""data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"world"}}""")
        c16Show(c16Run(CoderAiProvider.CLAUDE, cut))
    },
    c16Check("Decoder: a stream with no text at all is an empty-answer failure", "X:Other") {
        c16Show(c16Run(CoderAiProvider.CLAUDE, c16Frame("message_stop", """{"type":"message_stop"}""")))
    },
    c16Check("Decoder: an empty Gemini stream is an empty-answer failure", "X:Other") {
        c16Show(c16Run(CoderAiProvider.GEMINI, ""))
    },
    c16Check("Decoder: the first end counts (max_tokens, then message_stop)", "T:a|F:true") {
        val stream = c16Frame("content_block_delta", """{"type":"content_block_delta","delta":{"type":"text_delta","text":"a"}}""") +
            c16Frame("message_delta", """{"type":"message_delta","delta":{"stop_reason":"max_tokens"}}""") +
            c16Frame("message_stop", """{"type":"message_stop"}""")
        c16Show(c16Run(CoderAiProvider.CLAUDE, stream))
    },
    c16Check("Decoder: nothing after the OpenAI DONE line is used", "T:a|F:false") {
        val stream = c16Frame(null, """{"choices":[{"delta":{"content":"a"}}]}""") + "data: [DONE]\n\n" +
            c16Frame(null, """{"choices":[{"delta":{"content":"late"}}]}""")
        c16Show(c16Run(CoderAiProvider.OPENAI, stream))
    },
    c16Check("Decoder: a safety block after some text only cuts the answer short", "T:A|F:true") {
        val stream = c16Frame(null, """{"candidates":[{"content":{"parts":[{"text":"A"}]}}]}""") +
            c16Frame(null, """{"candidates":[{"finishReason":"SAFETY"}]}""")
        c16Show(c16Run(CoderAiProvider.GEMINI, stream))
    },
    c16Check("Decoder: a safety block with no text is a blocked failure", "X:Blocked") {
        c16Show(c16Run(CoderAiProvider.GEMINI, c16Frame(null, """{"promptFeedback":{"blockReason":"SAFETY"}}""")))
    },
    c16Check("Decoder: a Claude error after some text keeps the text", "T:a|X:Busy") {
        val stream = c16Frame("content_block_delta", """{"type":"content_block_delta","delta":{"type":"text_delta","text":"a"}}""") +
            c16Frame("error", """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
        c16Show(c16Run(CoderAiProvider.CLAUDE, stream))
    },
    c16Check("Decoder: a whitespace-only answer counts as empty", "T: |X:Other") {
        val stream = c16Frame("content_block_delta", """{"type":"content_block_delta","delta":{"type":"text_delta","text":" "}}""") +
            c16Frame("message_stop", """{"type":"message_stop"}""")
        c16Show(c16Run(CoderAiProvider.CLAUDE, stream))
    },
    c16Check("Decoder: ends exactly once", "true") {
        val decoder = CoderStreamDecoder(CoderAiProvider.CLAUDE)
        decoder.feedLine("data: {\"type\":\"message_stop\"}")
        decoder.feedLine("")
        (decoder.isDone && decoder.end().isEmpty() && decoder.feedLine("data: x").isEmpty()).toString()
    },
)

private fun c16ParserChecks(): List<CoderCheckResult> {
    val long = C16_REPLIES.joinToString("\n")
    return listOf(
        c16Check("Reply parts: chunked at every position equals the whole parse (14 sample replies)", "true") {
            val bad = C16_REPLIES.indexOfFirst { !c16SameAtEveryCut(it) }
            if (bad < 0) "true" else "reply $bad differs"
        },
        c16Check("Reply parts: chunked at every position, long reply", "true") { c16SameAtEveryCut(long).toString() },
        c16Check("Reply parts: random chunk sizes equal the whole parse", "true") {
            val bad = C16_REPLIES.indexOfFirst { !c16SameAtRandomCuts(it, 7L) }
            if (bad < 0 && c16SameAtRandomCuts(long, 11L)) "true" else "reply $bad differs"
        },
        c16Check("Reply parts: one character at a time equals the whole parse", "true") {
            val bad = C16_REPLIES.indexOfFirst { !c16SameCharByChar(it) }
            if (bad < 0 && c16SameCharByChar(long)) "true" else "reply $bad differs"
        },
        c16Check("Reply parts: a fence split across chunks opens a block", "true|a.js") {
            val parser = c16Parser("``", "`js path=a.js\nx\n")
            parser.isInsideCode.toString() + "|" + parser.openBlock?.path
        },
        c16Check("Reply parts: a closing fence split across chunks closes the block", "true|false|1") {
            val parser = c16Parser("```js\nx\n``")
            val before = parser.isInsideCode
            parser.feed("`\n")
            before.toString() + "|" + parser.isInsideCode + "|" + parser.segments.size
        },
        c16Check("Reply parts: four backticks hold a three-backtick fence inside", "md|```js\nx\n```") {
            val parser = c16Parser("````md\n```js\nx\n```\n````\n")
            parser.finish()
            val block = (parser.segments.first() as CoderReplySegment.Code).block
            block.language + "|" + block.code
        },
        c16Check("Reply parts: an unfinished block is flagged when the text ends", "true") {
            val parser = c16Parser("Look:\n```js\nlet a")
            parser.finish()
            ((parser.segments.last() as CoderReplySegment.Code).block == CoderCodeBlock("js", null, "let a", true)).toString()
        },
        c16Check("Reply parts: a closed block is not flagged", "false") {
            val parser = c16Parser("```js\nlet a\n```")
            parser.finish()
            (parser.segments.single() as CoderReplySegment.Code).block.unfinished.toString()
        },
        c16Check("Reply parts: the whole parse never flags a block", "false") {
            (parseReply("```js\nlet a").single() as CoderReplySegment.Code).block.unfinished.toString()
        },
        c16Check("Reply parts: path= names the file, language is kept", "html|index.html") {
            val parser = c16Parser("```html path=index.html\n<p>x</p>\n```\n")
            val block = (parser.segments.single() as CoderReplySegment.Code).block
            block.language + "|" + block.path
        },
        c16Check("Reply parts: an invalid path is dropped", "null") {
            val parser = c16Parser("```html path=../x\n<p>x</p>\n```\n")
            (parser.segments.single() as CoderReplySegment.Code).block.path.toString()
        },
        c16Check("Reply parts: the open block shows its language, path and complete lines", "OpenBlock(language=js, path=a.js, codeSoFar=let a = 1;)") {
            c16Parser("```js path=a.js\nlet a = 1;\nlet b").openBlock.toString()
        },
        c16Check("Reply parts: the held-back last line is not in the segments or the open block", "1|let a;") {
            val parser = c16Parser("Hi there\n```js\nlet a;\nlet b")
            parser.segments.size.toString() + "|" + parser.openBlock?.codeSoFar
        },
        c16Check("Reply parts: the live open block adds the last line", "let a;\nlet b") {
            c16Parser("```js\nlet a;\nlet b").liveOpenBlock()?.codeSoFar.toString()
        },
        c16Check("Reply parts: a last line that may be a fence is not shown", "let a;") {
            c16Parser("```js\nlet a;\n``").liveOpenBlock()?.codeSoFar.toString()
        },
        c16Check("Reply parts: live text shows the held-back last line", "Text(text=Hi\nthere)") {
            c16Parser("Hi\nthere").liveSegments().single().toString()
        },
        c16Check("Reply parts: live text hides a last line that starts with a backtick", "Text(text=Hi)") {
            c16Parser("Hi\n``").liveSegments().single().toString()
        },
        c16Check("Reply parts: live segments after a closed block and more text", "2") {
            c16Parser("```js\nx\n```\nAfter").liveSegments().size.toString()
        },
        c16Check("Reply parts: live segments equal the final segments after finish", "true") {
            val parser = c16Parser(C16_REPLIES[1])
            parser.finish()
            (parser.liveSegments() == parser.segments && parser.liveOpenBlock() == null).toString()
        },
        c16Check("Reply parts: the raw text is everything that was fed", "true") {
            val parser = c16Parser("a", "", "b\n", "```")
            (parser.rawText == "ab\n```").toString()
        },
        c16Check("Reply parts: pieces after finish are ignored and finish twice is harmless", "true") {
            val parser = c16Parser("a\n")
            parser.finish()
            val first = parser.segments
            parser.feed("more\n")
            parser.finish()
            (parser.segments == first && parser.isFinished).toString()
        },
        c16Check("Reply parts: nothing fed gives nothing", "true") {
            val parser = CoderStreamParser()
            parser.finish()
            (parser.segments.isEmpty() && parser.openBlock == null && !parser.isInsideCode).toString()
        },
        c16Check("Reply parts: a very long single line stays fast", "true") {
            val start = System.nanoTime()
            val parser = CoderStreamParser()
            repeat(50_000) { parser.feed("abcd") }
            parser.finish()
            (parser.segments.size == 1 && System.nanoTime() - start < 5_000_000_000L).toString()
        },
    )
}

private fun c16StageChecks(): List<CoderCheckResult> {
    fun walk(vararg events: CoderStageEvent): CoderAiStage {
        var stage = CoderAiStage.Reading
        for (event in events) stage = CoderAiStages.next(stage, event)
        return stage
    }
    val ctx = CoderStageEvent.ContextRead
    val text = CoderStageEvent.TextArrived
    val progress = CoderAiProgress(CoderAiStage.Reading, fileCount = 3, providerName = "Claude", model = "m1")
    fun stepsOf(stage: CoderAiStage, target: String? = null, ms: Long = 0L) =
        CoderAiStages.steps(progress.copy(stage = stage), target, ms)
    return listOf(
        c16Check("Stages: a normal answer goes Reading, Asking, Writing, Finished", "Reading|Asking|Writing|Finished") {
            listOf(
                CoderAiStage.Reading,
                walk(ctx),
                walk(ctx, text),
                walk(ctx, text, CoderStageEvent.StreamFinished),
            ).joinToString("|") { it.name }
        },
        c16Check("Stages: Stop ends the answer from every stage", "Stopped|Stopped|Stopped") {
            listOf(walk(CoderStageEvent.StopTapped), walk(ctx, CoderStageEvent.StopTapped), walk(ctx, text, CoderStageEvent.StopTapped))
                .joinToString("|") { it.name }
        },
        c16Check("Stages: an error ends the answer as Failed from every stage", "Failed|Failed|Failed") {
            listOf(walk(CoderStageEvent.ErrorHappened), walk(ctx, CoderStageEvent.ErrorHappened), walk(ctx, text, CoderStageEvent.ErrorHappened))
                .joinToString("|") { it.name }
        },
        c16Check("Stages: an end with no first text is Failed", "Failed|Failed") {
            listOf(walk(ctx, CoderStageEvent.StreamFinished), walk(ctx, CoderStageEvent.StreamCutShort)).joinToString("|") { it.name }
        },
        c16Check("Stages: cut short after text", "CutShort") { walk(ctx, text, CoderStageEvent.StreamCutShort).name },
        c16Check("Stages: an ended answer never changes", "Finished|Stopped|CutShort|Failed") {
            listOf(
                walk(ctx, text, CoderStageEvent.StreamFinished, text),
                walk(ctx, CoderStageEvent.StopTapped, text),
                walk(ctx, text, CoderStageEvent.StreamCutShort, CoderStageEvent.StopTapped),
                walk(CoderStageEvent.ErrorHappened, ctx),
            ).joinToString("|") { it.name }
        },
        c16Check("Stages: reading the context twice does not go back", "Writing") { walk(ctx, text, ctx).name },
        c16Check("Stages: only the four endings are terminal", "false,false,false,true,true,true,true") {
            CoderAiStage.values().joinToString(",") { CoderAiStages.isTerminal(it).toString() }
        },
        c16Check("Stages: reading shows one step that is not done", "Reading your files (3):false") {
            stepsOf(CoderAiStage.Reading).joinToString(";") { it.label + ":" + it.done }
        },
        c16Check("Stages: asking shows the provider and model and a seconds counter", "Reading your files (3):true;Asking Claude (m1):false:4 s") {
            stepsOf(CoderAiStage.Asking, ms = 4_200L).joinToString(";") {
                it.label + ":" + it.done + (if (it.detail != null) ":" + it.detail else "")
            }
        },
        c16Check("Stages: no seconds counter in the first second", "null") { stepsOf(CoderAiStage.Asking, ms = 900L).last().detail.toString() },
        c16Check("Stages: writing a file names its path", "Writing index.html") {
            stepsOf(CoderAiStage.Writing, target = "index.html").last().label
        },
        c16Check("Stages: writing plain text or unnamed code", "Writing|Writing code") {
            stepsOf(CoderAiStage.Writing).last().label + "|" + stepsOf(CoderAiStage.Writing, target = "code").last().label
        },
        c16Check("Stages: writing shows three steps, the first two done", "true,true,false") {
            stepsOf(CoderAiStage.Writing).joinToString(",") { it.done.toString() }
        },
        c16Check("Stages: no steps once the answer ended", "0") { stepsOf(CoderAiStage.Finished).size.toString() },
        c16Check("Stages: no files gives a plain reading label", "Reading your request") {
            CoderAiStages.steps(CoderAiProgress(CoderAiStage.Reading), null, 0L).single().label
        },
        c16Check("Stages: summary lines", "Wrote 2 files in 14 s|Wrote 1 file in 1 s|Wrote code in 3 s|Finished in 5 s|Stopped|Cut short|Couldn't finish") {
            fun s(stage: CoderAiStage, files: Int, others: Int, ms: Long) =
                CoderAiStages.summary(progress.copy(stage = stage), files, others, ms)
            listOf(
                s(CoderAiStage.Finished, 2, 0, 14_400L),
                s(CoderAiStage.Finished, 1, 0, 100L),
                s(CoderAiStage.Finished, 0, 1, 3_000L),
                s(CoderAiStage.Finished, 0, 0, 5_000L),
                s(CoderAiStage.Stopped, 0, 0, 1L),
                s(CoderAiStage.CutShort, 0, 0, 1L),
                s(CoderAiStage.Failed, 0, 0, 1L),
            ).joinToString("|")
        },
        c16Check("Stages: no summary while the answer runs", "") {
            CoderAiStages.summary(progress.copy(stage = CoderAiStage.Writing), 1, 0, 9_000L)
        },
        c16Check("Stages: seconds are rounded and at least one", "1,14,15") {
            listOf(0L, 14_400L, 14_600L).joinToString(",") { CoderAiStages.secondsOf(it).toString() }
        },
    )
}

private fun c16GateChecks(): List<CoderCheckResult> = listOf(
    c16Check("Updates: the first change is published at once", "true") { CoderPublishGate(50L).shouldPublish(1_000L, true).toString() },
    c16Check("Updates: nothing is published when nothing changed", "false") { CoderPublishGate(50L).shouldPublish(1_000L, false).toString() },
    c16Check("Updates: changes within 50 ms wait for the next update", "true|false|false|true") {
        val gate = CoderPublishGate(50L)
        val first = gate.shouldPublish(0L, true)
        gate.markPublished(0L)
        val second = gate.shouldPublish(10L, true)
        val third = gate.shouldPublish(49L, true)
        val fourth = gate.shouldPublish(50L, true)
        "$first|$second|$third|$fourth"
    },
    c16Check("Updates: a delta every millisecond for one second gives at most 21 updates", "true") {
        val gate = CoderPublishGate(50L)
        var published = 0
        for (t in 0L..1_000L) {
            if (gate.shouldPublish(t, true)) {
                gate.markPublished(t)
                published++
            }
        }
        (published in 20..21).toString()
    },
    c16Check("Updates: deltas that arrive within 50 ms end up in one update", "ab c") {
        val gate = CoderPublishGate(50L)
        val parser = CoderStreamParser()
        var shown = ""
        val deltas = listOf(0L to "a", 10L to "b", 20L to " ", 60L to "c")
        for ((t, delta) in deltas) {
            parser.feed(delta)
            if (gate.shouldPublish(t, true)) {
                gate.markPublished(t)
                shown = parser.rawText
            }
        }
        // t=0 published "a"; t=10 and t=20 waited; t=60 published everything so far.
        shown
    },
)

private fun c16RequestChecks(): List<CoderCheckResult> {
    val turns = listOf(ChatRole.User to "hi", ChatRole.Assistant to "yo", ChatRole.User to "go")
    fun build(provider: CoderAiProvider, model: String, stream: Boolean) =
        CoderAiClient.buildRequest(CoderAiConfig(provider, CHAT_TEST_KEY, model), "SYS", turns, stream)
    val claude = build(CoderAiProvider.CLAUDE, "claude-x", true)
    val openAi = build(CoderAiProvider.OPENAI, "gpt-x", true)
    val gemini = build(CoderAiProvider.GEMINI, "models/gemini-1.5-pro", true)
    return listOf(
        c16Check("Stream request: Claude body asks for a stream, same address and headers", "true") {
            val json = JSONObject(chatBody(claude))
            (json.getBoolean("stream") && claude.url.toString() == "https://api.anthropic.com/v1/messages" &&
                claude.header("x-api-key") == CHAT_TEST_KEY && claude.header("anthropic-version") == "2023-06-01" &&
                json.getInt("max_tokens") == 4096).toString()
        },
        c16Check("Stream request: a normal Claude request has no stream field", "false") {
            JSONObject(chatBody(build(CoderAiProvider.CLAUDE, "claude-x", false))).has("stream").toString()
        },
        c16Check("Stream request: OpenAI body has only model, messages and stream", "messages,model,stream") {
            JSONObject(chatBody(openAi)).keys().asSequence().toList().sorted().joinToString(",")
        },
        c16Check("Stream request: a normal OpenAI request has no stream field", "false") {
            JSONObject(chatBody(build(CoderAiProvider.OPENAI, "gpt-x", false))).has("stream").toString()
        },
        c16Check("Stream request: Gemini uses the streaming address with alt=sse", "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-pro:streamGenerateContent?alt=sse") {
            gemini.url.toString()
        },
        c16Check("Stream request: a normal Gemini request keeps its address", "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-pro:generateContent") {
            build(CoderAiProvider.GEMINI, "models/gemini-1.5-pro", false).url.toString()
        },
        c16Check("Stream request: Gemini body has no stream field and the key is in a header", "true") {
            val json = JSONObject(chatBody(gemini))
            (!json.has("stream") && gemini.header("x-goog-api-key") == CHAT_TEST_KEY).toString()
        },
        c16Check("Stream request: the key is never in an address or a body", "false") {
            listOf(claude, openAi, gemini).any {
                it.url.toString().contains(CHAT_TEST_KEY) || chatBody(it).contains(CHAT_TEST_KEY)
            }.toString()
        },
        c16Check("Stream request: every address is https", "true") {
            listOf(claude, openAi, gemini).all { it.url.isHttps }.toString()
        },
    )
}

private fun c16MiscChecks(): List<CoderCheckResult> = listOf(
    c16Check("Ideas: three chips with the agreed words", "Make a to-do app|Add a dark mode|Make it work on phones") {
        CODER_AI_IDEAS.joinToString("|")
    },
    c16Check("Message: a plain message has no stream details", "true") {
        val m = CoderChatMessage(id = 1, role = ChatRole.Assistant, text = "x")
        (m.segments.isEmpty() && m.openBlock == null && m.stage == null && !m.stopped && !m.interrupted && m.elapsedMillis == 0L).toString()
    },
    c16Check("Code block: unfinished is false unless set", "false|true") {
        CoderCodeBlock("js", null, "x").unfinished.toString() + "|" + CoderCodeBlock("js", null, "x", true).unfinished
    },
)

private fun c16aChecks(): List<CoderCheckResult> =
    c16SseChecks() + c16EventChecks() + c16DecoderChecks() + c16ParserChecks() + c16StageChecks() +
        c16GateChecks() + c16RequestChecks() + c16MiscChecks()

// ---- C16 Part B: file plan, apply with backup, undo --------------------------------------------

private val C16B_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

private fun c16bBlock(path: String?, code: String = "x", unfinished: Boolean = false): CoderCodeBlock =
    CoderCodeBlock("js", path, code, unfinished)

private fun c16bRows(vararg blocks: CoderCodeBlock, existing: Set<String> = emptySet(), limits: CoderApplyLimits = CoderApplyLimits()): List<CoderPlanRow> =
    CoderApplyPlan.build(blocks.toList(), existing, limits)

private fun c16bRefused(path: String): String =
    if (c16bRows(c16bBlock(path)).single().canApply) "applies" else "refused"

private fun c16bPut(store: CoderFileStore, id: String, path: String, bytes: ByteArray) {
    val file = store.resolve(id, path) ?: throw IOException("bad path $path")
    file.parentFile?.mkdirs()
    file.writeBytes(bytes)
}

private fun c16bSeed(store: CoderFileStore, id: String): Map<String, ByteArray> {
    val seed = linkedMapOf(
        "a.txt" to (C16B_BOM + "one\r\ntwo\r\n".toByteArray(Charsets.UTF_8)),
        "src/b.js" to "let b = 1;\n".toByteArray(Charsets.UTF_8),
        "c.css" to "body {\r\n  color: red;\r\n}\r\n".toByteArray(Charsets.UTF_8),
    )
    for ((path, bytes) in seed) c16bPut(store, id, path, bytes)
    return seed
}

private fun c16bItems(): List<CoderApplyItem> = listOf(
    CoderApplyItem("a.txt", "ONE\nTWO\n"),
    CoderApplyItem("src/b.js", "let b = 2;\n"),
    CoderApplyItem("c.css", "body {\n  color: blue;\n}\n"),
    CoderApplyItem("new1.js", "console.log(1);\n"),
    CoderApplyItem("pages/new2.html", "<p>hi</p>\n"),
)

private fun c16bText(store: CoderFileStore, id: String, path: String): String =
    fileBytes(store, id, path)?.toString(Charsets.UTF_8) ?: "(missing)"

private fun c16bExists(store: CoderFileStore, id: String, path: String): Boolean = store.resolve(id, path)?.exists() == true

private fun c16bSameBytes(store: CoderFileStore, id: String, seed: Map<String, ByteArray>): Boolean =
    seed.all { (path, bytes) -> fileBytes(store, id, path)?.contentEquals(bytes) == true }

private fun c16bChecks(store: CoderFileStore): List<CoderCheckResult> {
    val r = ArrayList<CoderCheckResult>()
    // Plan rules
    r.add(c16Check("Plan: a .coder-backup path is refused", "refused") { c16bRefused(".coder-backup/x/a.js") })
    r.add(c16Check("Plan: any .coder prefix is refused, ignoring case", "refused|refused") { c16bRefused(".coder-build/a.js") + "|" + c16bRefused(".CODER/a.js") })
    r.add(c16Check("Plan: .git and .github folders are refused", "refused|refused") { c16bRefused(".git/config") + "|" + c16bRefused(".github/workflows/ci.yml") })
    r.add(c16Check("Plan: .gitignore, .github-notes.md and .envrc are allowed", "applies|applies|applies") {
        c16bRefused(".gitignore") + "|" + c16bRefused(".github-notes.md") + "|" + c16bRefused(".envrc")
    })
    r.add(c16Check("Plan: .env and .env.local are refused, at any depth", "refused|refused|refused") {
        c16bRefused(".env") + "|" + c16bRefused(".env.local") + "|" + c16bRefused("config/.env")
    })
    r.add(c16Check("Plan: .env.example is allowed", "applies") { c16bRefused(".env.example") })
    r.add(check("Plan: a refused folder says the AI can't change it", "reason starts with The AI can't change") {
        c16bRows(c16bBlock(".github/a.yml")).single().reason?.startsWith("The AI can't change") == true
    })
    r.add(c16Check("Plan: invalid paths are refused", "refused|refused") { c16bRefused("../x.js") + "|" + c16bRefused("a//b.js") })
    r.add(c16Check("Plan: a block without a path gets no row", "1") { c16bRows(c16bBlock(null), c16bBlock("a.js")).size.toString() })
    r.add(c16Check("Plan: an unfinished block is refused", "refused|true") {
        val row = c16bRows(c16bBlock("a.js", unfinished = true)).single()
        (if (row.canApply) "applies" else "refused") + "|" + row.unfinished
    })
    r.add(c16Check("Plan: same path twice, the last wins and the first is skipped", "skipped|applies|second") {
        val rows = c16bRows(c16bBlock("a.js", "first"), c16bBlock("b.js"), c16bBlock("a.js", "second"))
        (if (rows[0].canApply) "applies" else "skipped") + "|" + (if (rows[2].canApply) "applies" else "skipped") + "|" + rows[2].code
    })
    r.add(c16Check("Plan: exactly 300000 bytes is allowed, one more is refused", "applies|refused") {
        val ok = c16bRows(c16bBlock("a.js", "a".repeat(300_000))).single().canApply
        val big = c16bRows(c16bBlock("a.js", "a".repeat(300_001))).single()
        (if (ok) "applies" else "refused") + "|" + (if (big.canApply) "applies" else "refused")
    })
    r.add(c16Check("Plan: size counts UTF-8 bytes", "2|refused") {
        val small = c16bRows(c16bBlock("a.js", "\u00E9")).single().sizeBytes
        val big = c16bRows(c16bBlock("a.js", "\u00E9".repeat(150_001))).single()
        small.toString() + "|" + (if (big.canApply) "applies" else "refused")
    })
    r.add(c16Check("Plan: 20 files apply, the 21st is refused", "20|1") {
        val rows = c16bRows(*Array(21) { c16bBlock("f$it.js") })
        rows.count { it.canApply }.toString() + "|" + rows.count { !it.canApply }
    })
    r.add(c16Check("Plan: the total past 2 MB is refused", "7|1") {
        val rows = c16bRows(*Array(8) { c16bBlock("f$it.js", "a".repeat(290_000)) })
        rows.count { it.canApply }.toString() + "|" + rows.count { it.reason?.contains("2 MB") == true }
    })
    r.add(c16Check("Plan: New and Replaces follow the existing files", "Replaces|New") {
        val rows = c16bRows(c16bBlock("a.js"), c16bBlock("b.js"), existing = setOf("a.js"))
        rows[0].status.name + "|" + rows[1].status.name
    })
    r.add(c16Check("Plan: default limits are 20 files and 2 MB", "20|2097152|300000") {
        val l = CoderApplyLimits()
        "${l.maxFiles}|${l.maxTotalBytes}|${l.maxFileBytes}"
    })
    r.add(c16Check("Offer: needs two finished files with different valid paths", "null|plan|null|null|plan") {
        fun show(vararg b: CoderCodeBlock) = if (CoderApplyPlan.offer(b.toList(), emptySet()) == null) "null" else "plan"
        listOf(
            show(c16bBlock("a.js")),
            show(c16bBlock("a.js"), c16bBlock("b.js")),
            show(c16bBlock("a.js"), c16bBlock("a.js")),
            show(c16bBlock("a.js"), c16bBlock("b.js", unfinished = true)),
            show(c16bBlock("a.js"), c16bBlock("b.js"), c16bBlock("c.js", unfinished = true)),
        ).joinToString("|")
    })
    r.add(c16Check("Offer: refused and unfinished files start unchecked", "a.js,b.js") {
        val plan = CoderApplyPlan.offer(listOf(c16bBlock("a.js"), c16bBlock(".env"), c16bBlock("b.js"), c16bBlock("c.js", unfinished = true)), emptySet())
        plan?.selected?.joinToString(",") ?: "null"
    })
    r.add(c16Check("Plan: toggle flips a file, ignores a refused one, and itemsFor follows", "b.js|a.js,b.js|b.js") {
        val plan = CoderApplyPlan.offer(listOf(c16bBlock("a.js"), c16bBlock("b.js"), c16bBlock(".env")), emptySet())
        if (plan == null) "null" else {
            val off = CoderApplyPlan.toggle(plan, "a.js")
            val same = CoderApplyPlan.toggle(plan, ".env")
            CoderApplyPlan.itemsFor(off).joinToString(",") { it.path } + "|" +
                CoderApplyPlan.itemsFor(same).joinToString(",") { it.path } + "|" +
                CoderApplyPlan.itemsFor(off).joinToString(",") { it.path }
        }
    })
    r.add(c16Check("Plan: counts and confirm text", "2,1|Creates 2 new files and replaces 1 file. A backup of the replaced files is saved so you can undo this.") {
        val plan = CoderApplyPlan.offer(listOf(c16bBlock("a.js"), c16bBlock("b.js"), c16bBlock("c.js")), setOf("a.js"))
        if (plan == null) "null" else {
            val c = CoderApplyPlan.counts(plan)
            "${c.first},${c.second}|" + CoderApplyPlan.confirmText(c.first, c.second)
        }
    })
    r.add(c16Check("Plan: one new file reads in the singular", "Creates 1 new file and replaces 0 files.") {
        CoderApplyPlan.confirmText(1, 0).substringBefore(" A backup")
    })
    r.add(c16Check("Plan: Copy all skips unfinished files and names each path", "true|false") {
        val rows = c16bRows(c16bBlock("a.js", "AAA"), c16bBlock("b.js", "BBB", unfinished = true))
        val text = CoderApplyPlan.copyAllText(rows)
        (text.contains("a.js") && text.contains("AAA")).toString() + "|" + text.contains("BBB")
    })
    r.add(c16Check("Plan: sizes and header texts", "0 B|1.5 KB|The AI wrote 3 files") {
        CoderApplyPlan.sizeText(0) + "|" + CoderApplyPlan.sizeText(1536) + "|" + CoderApplyPlan.headerText(3)
    })
    r.add(c16Check("Message: a chat message has no plan by default", "null") { CoderChatMessage(1L, ChatRole.Assistant, "hi").plan.toString() })
    r.add(c16Check("Prompt: the say-first rule follows the complete-files rule", "true") {
        val complete = CODER_SYSTEM_PROMPT.indexOf("- When you change a file, give the COMPLETE new content")
        val first = CODER_SYSTEM_PROMPT.indexOf("- Before the code, say in one short sentence what you are going to do and which files you will create or change.")
        (complete in 0 until first).toString()
    })
    r.add(c16Check("Apply: limits and ids", "20971520|5|0000000001006") {
        "${CoderAiApply.MAX_BACKUP_BYTES}|${CoderAiApply.KEEP_BACKUPS}|${CoderAiApply.backupIdFor(1006L)}"
    })
    r.add(c16Check("Apply: result lines", "Created 2, replaced 1|Created 1, replaced 0. The editor history of 1 open file was cleared.|Undone: removed 1 file, restored 2 files. x.js was edited after the AI build, so it was kept.") {
        CoderAiApply.appliedLine(CoderApplyResult.Applied("b", listOf("x", "y"), listOf("a"))) + "|" +
            CoderAiApply.appliedLine(CoderApplyResult.Applied("b", listOf("x"), emptyList(), listOf("q"))) + "|" +
            CoderAiApply.undoLine(CoderUndoResult.Done(listOf("n"), listOf("a", "b"), listOf("x.js")))
    })

    // Applying on the disk
    r.add(c16Check("Apply then undo: created and replaced files, CRLF kept, exact bytes back", "true|true|true|true|true|true") {
        withTempProject(store) { id ->
            val seed = c16bSeed(store, id)
            val result = CoderAiApply.apply(store, id, c16bItems(), now = 1000L)
            val applied = result as? CoderApplyResult.Applied
            val crlfKept = c16bText(store, id, "a.txt") == "ONE\r\nTWO\r\n" && c16bText(store, id, "c.css") == "body {\r\n  color: blue;\r\n}\r\n"
            val lfKept = c16bText(store, id, "src/b.js") == "let b = 2;\n"
            val created = c16bText(store, id, "new1.js") == "console.log(1);\n" && c16bText(store, id, "pages/new2.html") == "<p>hi</p>\n"
            val lists = applied != null && applied.created == listOf("new1.js", "pages/new2.html") && applied.replaced.size == 3
            val undo = CoderAiApply.undo(store, id, applied?.backupId ?: "")
            val back = c16bSameBytes(store, id, seed) && !c16bExists(store, id, "new1.js") && !c16bExists(store, id, "pages")
            val gone = CoderAiApply.backupIds(store, id).isEmpty() && undo is CoderUndoResult.Done
            "$lists|$crlfKept|$lfKept|$created|$back|$gone"
        }
    })
    r.add(c16Check("Apply: backup holds exact copies and a manifest, hidden from every listing", "true|true|true|true") {
        withTempProject(store) { id ->
            val seed = c16bSeed(store, id)
            val applied = CoderAiApply.apply(store, id, c16bItems(), now = 2000L) as? CoderApplyResult.Applied
            val bid = applied?.backupId ?: "none"
            val copy = fileBytes(store, id, ".coder-backup/$bid/files/a.txt")?.contentEquals(seed["a.txt"]) == true
            val manifest = try {
                val o = JSONObject(c16bText(store, id, ".coder-backup/$bid/manifest.json"))
                o.has("createdPaths") && o.has("replacedPaths") && o.getLong("createdAtMillis") == 2000L
            } catch (e: Exception) {
                false
            }
            val hidden = store.listAll(id).none { it.path.startsWith(".coder") } && store.listAll(id, true).any { it.path == ".coder-backup" }
            val counts = store.fileCount(id) == 5 && store.projectSizeBytes(id) == store.listAll(id).filter { !it.isDirectory }.sumOf { it.sizeBytes }
            "$copy|$manifest|$hidden|$counts"
        }
    })
    r.add(c16Check("Apply: a project file named manifest.json is replaced and restored", "true") {
        withTempProject(store) { id ->
            val original = "{\"a\":1}\n".toByteArray(Charsets.UTF_8)
            c16bPut(store, id, "manifest.json", original)
            val applied = CoderAiApply.apply(store, id, listOf(CoderApplyItem("manifest.json", "{\"a\":2}\n")), now = 3000L)
            val id2 = (applied as? CoderApplyResult.Applied)?.backupId ?: ""
            CoderAiApply.undo(store, id, id2)
            (applied is CoderApplyResult.Applied && fileBytes(store, id, "manifest.json")?.contentEquals(original) == true).toString()
        }
    })
    r.add(c16Check("Apply: a failing write rolls everything back and leaves no backup or temp file", "RolledBack|true|true|true") {
        withTempProject(store) { id ->
            val seed = c16bSeed(store, id)
            val items = listOf(
                CoderApplyItem("a.txt", "NEW A\n"),
                CoderApplyItem("src/b.js", "NEW B\n"),
                CoderApplyItem("x", "a file\n"),
                CoderApplyItem("x/y.js", "inside a file\n"),
            )
            val result = CoderAiApply.apply(store, id, items, now = 4000L)
            val name = result.javaClass.simpleName
            val restored = c16bSameBytes(store, id, seed) && !c16bExists(store, id, "x")
            val clean = CoderAiApply.backupIds(store, id).isEmpty() && store.resolve(id, ".coder-backup")?.list().orEmpty().isEmpty()
            "$name|$restored|$clean|${tempLeftovers(store, id).isEmpty()}"
        }
    })
    r.add(c16Check("Apply: stopping before the first step changes nothing", "Cancelled|true|true") {
        withTempProject(store) { id ->
            val seed = c16bSeed(store, id)
            val result = CoderAiApply.apply(store, id, c16bItems(), shouldContinue = { false }, now = 5000L)
            "${result.javaClass.simpleName}|${c16bSameBytes(store, id, seed)}|${!c16bExists(store, id, "new1.js")}"
        }
    })
    r.add(c16Check("Apply: stopping after the first write puts the project back", "Cancelled|true|true|true") {
        withTempProject(store) { id ->
            val seed = c16bSeed(store, id)
            var calls = 0
            val items = listOf(CoderApplyItem("a.txt", "NEW\n"), CoderApplyItem("b.txt", "fresh\n"))
            val result = CoderAiApply.apply(store, id, items, shouldContinue = { ++calls < 3 }, now = 5100L)
            "${result.javaClass.simpleName}|${c16bSameBytes(store, id, seed)}|${!c16bExists(store, id, "b.txt")}|${CoderAiApply.backupIds(store, id).isEmpty()}"
        }
    })
    r.add(c16Check("Apply: a backup over the limit is refused and nothing changes", "Refused|These files are too large to back up safely. Apply them one at a time.|true|true") {
        withTempProject(store) { id ->
            val seed = c16bSeed(store, id)
            val result = CoderAiApply.apply(store, id, listOf(CoderApplyItem("a.txt", "x\n")), now = 6000L, maxBackupBytes = 5L)
            val refused = result as? CoderApplyResult.Refused
            "${result.javaClass.simpleName}|${refused?.message}|${c16bSameBytes(store, id, seed)}|${store.resolve(id, ".coder-backup")?.list().orEmpty().isEmpty()}"
        }
    })
    r.add(c16Check("Apply: a backup exactly at the limit is allowed", "Applied") {
        withTempProject(store) { id ->
            c16bPut(store, id, "a.txt", "12345".toByteArray(Charsets.UTF_8))
            CoderAiApply.apply(store, id, listOf(CoderApplyItem("a.txt", "x\n")), now = 6100L, maxBackupBytes = 5L).javaClass.simpleName
        }
    })
    r.add(c16Check("Apply: protected paths, too many files and an empty list are refused before any write", "Refused|Refused|Refused|true") {
        withTempProject(store) { id ->
            val hidden = CoderAiApply.apply(store, id, listOf(CoderApplyItem(".coder-x/a.txt", "x")))
            val many = CoderAiApply.apply(store, id, List(21) { CoderApplyItem("f$it.txt", "x") })
            val empty = CoderAiApply.apply(store, id, emptyList())
            "${hidden.javaClass.simpleName}|${many.javaClass.simpleName}|${empty.javaClass.simpleName}|${!c16bExists(store, id, ".coder-x")}"
        }
    })
    r.add(c16Check("Apply: a folder, a binary file or a file in the way refuses the whole apply", "true|true|true|true") {
        withTempProject(store) { id ->
            c16bSeed(store, id)
            store.createFolder(id, "dir")
            c16bPut(store, id, "bin.dat", byteArrayOf(1, 0, 2, 0, 3))
            val folder = CoderAiApply.apply(store, id, listOf(CoderApplyItem("dir", "x"))) as? CoderApplyResult.Refused
            val binary = CoderAiApply.apply(store, id, listOf(CoderApplyItem("bin.dat", "x"))) as? CoderApplyResult.Refused
            val way = CoderAiApply.apply(store, id, listOf(CoderApplyItem("a.txt/z.js", "x"))) as? CoderApplyResult.Refused
            val untouched = c16bText(store, id, "src/b.js") == "let b = 1;\n"
            "${folder?.message?.contains("is a folder") == true}|${binary?.message?.contains("isn't a text file") == true}|${way?.message?.contains("is a file") == true}|$untouched"
        }
    })
    r.add(c16Check("Undo: a created file edited since is kept and reported, the rest is undone", "true|true|true|true|true") {
        withTempProject(store) { id ->
            val seed = c16bSeed(store, id)
            val applied = CoderAiApply.apply(store, id, c16bItems(), now = 7000L) as? CoderApplyResult.Applied
            c16bPut(store, id, "new1.js", "my own edit\n".toByteArray(Charsets.UTF_8))
            val done = CoderAiApply.undo(store, id, applied?.backupId ?: "") as? CoderUndoResult.Done
            val kept = done?.kept == listOf("new1.js") && c16bText(store, id, "new1.js") == "my own edit\n"
            val removed = done?.deleted == listOf("pages/new2.html") && !c16bExists(store, id, "pages")
            val restored = done?.restored?.size == 3 && c16bSameBytes(store, id, seed)
            val line = done != null && CoderAiApply.undoLine(done).contains("new1.js was edited after the AI build, so it was kept.")
            val gone = CoderAiApply.backupIds(store, id).isEmpty()
            "$kept|$removed|$restored|$line|$gone"
        }
    })
    r.add(c16Check("Undo: a second undo and an undo with no backup say there is none", "NoBackup|NoBackup|NoBackup") {
        withTempProject(store) { id ->
            c16bSeed(store, id)
            val bid = (CoderAiApply.apply(store, id, c16bItems(), now = 8000L) as? CoderApplyResult.Applied)?.backupId ?: ""
            CoderAiApply.undo(store, id, bid)
            val again = CoderAiApply.undo(store, id, bid).javaClass.simpleName
            val last = CoderAiApply.undoLast(store, id).javaClass.simpleName
            val bad = CoderAiApply.undo(store, id, "../x").javaClass.simpleName
            "$again|$last|$bad"
        }
    })
    r.add(c16Check("Undo last: undoes only the newest build", "true|true|true") {
        withTempProject(store) { id ->
            CoderAiApply.apply(store, id, listOf(CoderApplyItem("one.txt", "1")), now = 9000L)
            CoderAiApply.apply(store, id, listOf(CoderApplyItem("two.txt", "2")), now = 9001L)
            val latest = CoderAiApply.latestBackupId(store, id) == CoderAiApply.backupIdFor(9001L)
            CoderAiApply.undoLast(store, id)
            "$latest|${!c16bExists(store, id, "two.txt")}|${c16bExists(store, id, "one.txt")}"
        }
    })
    r.add(c16Check("Backups: only the last 5 are kept, newest first", "5|true|5") {
        withTempProject(store) { id ->
            for (i in 0..6) CoderAiApply.apply(store, id, listOf(CoderApplyItem("f$i.txt", "x")), now = 1000L + i)
            val ids = CoderAiApply.backupIds(store, id)
            val expected = (1006L downTo 1002L).map { CoderAiApply.backupIdFor(it) }
            "${ids.size}|${ids == expected}|${store.resolve(id, ".coder-backup")?.list()?.size}"
        }
    })
    r.add(c16Check("Backups: a failed apply does not remove an older backup", "1") {
        withTempProject(store) { id ->
            CoderAiApply.apply(store, id, listOf(CoderApplyItem("one.txt", "1")), now = 100L, keep = 1)
            CoderAiApply.apply(store, id, listOf(CoderApplyItem("x", "f"), CoderApplyItem("x/y.js", "z")), now = 101L, keep = 1)
            CoderAiApply.backupIds(store, id).size.toString()
        }
    })
    r.add(c16Check("Plan: a path inside the backup folder is refused", "refused") {
        withTempProject(store) { id ->
            val bid = (CoderAiApply.apply(store, id, listOf(CoderApplyItem("one.txt", "1")), now = 50L) as? CoderApplyResult.Applied)?.backupId ?: ""
            c16bRefused(".coder-backup/$bid/files/one.txt")
        }
    })
    return r
}
