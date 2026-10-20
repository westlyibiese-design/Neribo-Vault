package com.westly.neribovault.feature.coder.preview

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.BuildConfig
import com.westly.neribovault.NeriboApp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.ConfirmDialog
import com.westly.neribovault.core.ui.components.LoadingState
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTextField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.util.formatDateTime
import com.westly.neribovault.feature.coder.buildflow.CoderBuildFiles
import com.westly.neribovault.feature.coder.env.CoderEnvCache
import com.westly.neribovault.feature.coder.env.CoderEnvStore
import com.westly.neribovault.feature.coder.files.CoderEntry
import com.westly.neribovault.feature.coder.files.CoderFileStore
import com.westly.neribovault.feature.coder.files.CoderPaths
import com.westly.neribovault.feature.coder.guidance.CoderTipHost
import com.westly.neribovault.feature.coder.guidance.TipContext
import com.westly.neribovault.feature.coder.guidance.TipScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How long after a save finishes the preview reloads by itself. A new save inside this time starts the wait again. */
const val AUTO_REFRESH_DELAY_MS = 1_000L

/** The shortest time between two reloads that the preview starts by itself. */
const val AUTO_REFRESH_MIN_GAP_MS = 1_000L

private const val MSG_COPIED = "Copied"
private const val MSG_NO_BROWSER = "No browser app found."
private const val MSG_SERVER_FAILED = "Couldn't start the page on this phone. Try again."

/**
 * Whether the preview should reload by itself now: the switch is on, the preview is on screen, a save just
 * finished, the page is not still loading and the last reload was at least a second ago. Pure, so the
 * self-test checks every combination.
 */
fun shouldAutoRefresh(
    enabled: Boolean,
    previewVisible: Boolean,
    saveJustFinished: Boolean,
    loading: Boolean,
    millisSinceLastReload: Long,
): Boolean = enabled && previewVisible && saveJustFinished && !loading && millisSinceLastReload >= AUTO_REFRESH_MIN_GAP_MS

/** Holds the browser view so back presses and the lifecycle can reach it. It is not state: nothing redraws when it changes. */
private class PreviewHolder {
    var webView: WebView? = null

    /** The address the browser view was last told to open, so a change of page loads once and a redraw does not. */
    var loadedUrl: String? = null

    /** When the page was last reloaded by a button or by auto-refresh, on the `SystemClock.elapsedRealtime` clock. */
    var lastReloadAt: Long = 0L

    /** Stops the page and destroys the view. Safe to call twice. */
    fun release() {
        val view = webView ?: return
        webView = null
        view.stopLoading()
        view.loadUrl("about:blank")
        view.removeAllViews()
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
    }
}

private enum class PageDialogKind { Alert, Confirm, Prompt }

/** One alert, confirm or prompt a page asked for. The browser is answered exactly once, whatever happens. */
private class PageDialogState(
    val kind: PageDialogKind,
    val message: String,
    val defaultText: String,
    private val result: JsResult,
) {
    private var finished = false

    fun confirm(text: String = "") {
        if (finished) return
        finished = true
        if (kind == PageDialogKind.Prompt && result is JsPromptResult) result.confirm(text) else result.confirm()
    }

    fun cancel() {
        if (finished) return
        finished = true
        result.cancel()
    }

    /** What closing the dialog without a button means: an alert has only one answer, the others are cancelled. */
    fun dismiss() {
        if (kind == PageDialogKind.Alert) confirm() else cancel()
    }
}

/**
 * Shows a project's page inside the app, read from the project folder by [CoderPreviewResponder].
 * [entryPath] is the file to open. Back goes back through the page's history first and then calls
 * [onClose]. The browser is destroyed when this leaves the screen. A rotation loads the page again;
 * nothing the page saved in its own storage is lost. When [onAskAi] is set, each error in the console
 * gets a Fix with AI button that hands the message to it.
 *
 * With [embedded] set the panel has no top bar and no Back handling of its own: it is the preview pane next to
 * the editor, with a thin toolbar (reload, page picker, size, console, menu), and [onClose] turns the pane off.
 * [saveTick] goes up each time a save finishes, which reloads the page after a second when auto-refresh is on.
 * [entries] is the project's file list, for the page picker; [session] keeps the chosen size and page while the
 * screen is open; messages such as "Copied" are shown on [snackbarHostState]. When a site built on GitHub is shown,
 * a line says when it was built and, if [onOpenBuild] is set, a Rebuild button opens the build wizard.
 */
@Composable
fun CoderPreviewPanel(
    projectId: String,
    entryPath: String,
    onClose: () -> Unit,
    onAskAi: ((CoderConsoleMessage) -> Unit)? = null,
    embedded: Boolean = false,
    saveTick: Int = 0,
    entries: List<CoderEntry> = emptyList(),
    session: CoderPreviewSession? = null,
    snackbarHostState: SnackbarHostState? = null,
    onOpenBuild: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val store = remember(context) { (context.applicationContext as NeriboApp).container.coderFileStore }
    val holder = remember { PreviewHolder() }
    val snackbar = snackbarHostState ?: remember { SnackbarHostState() }
    val plan by produceState<PreviewPlan?>(initialValue = null, projectId, store) {
        // The page may ask for /__coder/env.js, which is answered from memory, so the variables are loaded first.
        CoderEnvStore.shared(context)
        CoderEnvCache.ensureLoaded(projectId)
        value = withContext(Dispatchers.IO) {
            val root = CoderPreviewRoot.find(store, projectId) ?: ""
            PreviewPlan(
                rootPrefix = root,
                build = CoderBuildDetector.check(store, projectId),
                builtAt = if (root == CoderPreviewRoot.BUILT_FOLDER) builtTimeOf(store, projectId) else null,
            )
        }
    }
    BackHandler(enabled = !embedded) {
        val view = holder.webView
        if (view != null && view.canGoBack()) view.goBack() else onClose()
    }
    val current = plan
    if (current == null) {
        if (embedded) {
            Box(modifier = Modifier.fillMaxSize()) { LoadingState() }
        } else {
            NeriboScaffold(topBar = { NeriboTopBar(title = "Preview", onBack = onClose) }) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) { LoadingState() }
            }
        }
    } else if (current.build is CoderBuildCheck.NeedsBuild) {
        NeedsBuildNotice(
            reason = current.build.reason,
            builtFolderHint = current.build.builtFolderHint,
            onBack = onClose,
            onBuild = onOpenBuild,
        )
    } else {
        PreviewContent(
            projectId = projectId,
            entryPath = entryPath,
            rootPrefix = current.rootPrefix,
            builtAt = current.builtAt,
            onOpenBuild = onOpenBuild,
            store = store,
            holder = holder,
            embedded = embedded,
            saveTick = saveTick,
            entries = entries,
            session = session,
            snackbar = snackbar,
            onClose = onClose,
            onAskAi = onAskAi,
        )
    }
}

/** What the preview found out about the project before showing it: the site's top folder and whether it needs a build. */
private class PreviewPlan(val rootPrefix: String, val build: CoderBuildCheck, val builtAt: Long?)

/** When the shown GitHub build was made: from its meta file, else from the folder's own time, else 0. */
private fun builtTimeOf(store: CoderFileStore, projectId: String): Long =
    CoderBuildFiles.readMeta(store, projectId)?.builtAtMillis
        ?: store.resolve(projectId, CoderPreviewRoot.BUILT_FOLDER)?.lastModified()
        ?: 0L

/** One quiet line above a site built on GitHub: when it was built, and a Rebuild button when the wizard can be opened. */
@Composable
private fun BuiltBanner(builtAt: Long?, onRebuild: (() -> Unit)?) {
    val spacing = NeriboTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val text = if (builtAt != null && builtAt > 0L) {
            "Showing the build from ${formatDateTime(builtAt)}."
        } else {
            "Showing the build from GitHub."
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (onRebuild != null) {
            TextButton(onClick = onRebuild) { Text(text = "Rebuild", style = MaterialTheme.typography.labelLarge) }
        }
    }
}

/** Adds a plain-English line to errors and warnings that have a known explanation. */
private fun withFriendlyText(message: CoderConsoleMessage): CoderConsoleMessage {
    val isProblem = message.level == ConsoleLevel.Error || message.level == ConsoleLevel.Warn
    if (!isProblem || message.friendly != null) return message
    return message.copy(friendly = CoderErrorExplainer.explain(message.text))
}

@Composable
private fun PreviewContent(
    projectId: String,
    entryPath: String,
    rootPrefix: String,
    builtAt: Long?,
    onOpenBuild: (() -> Unit)?,
    store: CoderFileStore,
    holder: PreviewHolder,
    embedded: Boolean,
    saveTick: Int,
    entries: List<CoderEntry>,
    session: CoderPreviewSession?,
    snackbar: SnackbarHostState,
    onClose: () -> Unit,
    onAskAi: ((CoderConsoleMessage) -> Unit)?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val host = remember(projectId) { CoderPreviewHost.hostFor(projectId) }
    val responder = remember(projectId, rootPrefix) {
        CoderPreviewResponder(context.applicationContext, store, projectId, rootPrefix)
    }
    val console = remember(projectId, rootPrefix) { CoderConsole(host, rootPrefix) }
    val view = session ?: remember { CoderPreviewSession() }
    // In the pane the page choice lives in the session; in the full screen it only lasts while this screen is open.
    var localPage by rememberSaveable { mutableStateOf<String?>(null) }
    val shownEntry = (if (embedded) view.page else localPage) ?: entryPath
    val pages = remember(entries, rootPrefix) { htmlPagesOf(entries, rootPrefix) }
    val startUrl = remember(projectId, rootPrefix, shownEntry) { previewEntryUrl(projectId, rootPrefix, shownEntry) }
    var loading by remember { mutableStateOf(true) }
    var showConsole by rememberSaveable { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var pendingDialog by remember { mutableStateOf<PageDialogState?>(null) }
    var pendingLink by remember { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember(context) { (context.applicationContext as NeriboApp).container.coderPrefs }
    val autoRefresh by prefs.autoRefresh.collectAsStateWithLifecycle(initialValue = true)
    val autoRefreshNow by rememberUpdatedState(autoRefresh)
    val server = remember(context) { CoderLocalServer.shared(context) }
    val serving by server.serving.collectAsStateWithLifecycle()
    val servingHere = serving?.projectId == projectId

    val say: (String) -> Unit = { text ->
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
    }
    val reload: () -> Unit = {
        holder.lastReloadAt = SystemClock.elapsedRealtime()
        holder.webView?.reload()
    }
    val pickPage: (String) -> Unit = { page ->
        if (embedded) {
            view.page = page
        } else {
            localPage = page
        }
    }
    val openInPhoneBrowser: () -> Unit = {
        scope.launch {
            val info = withContext(Dispatchers.IO) { server.start(projectId, rootPrefix) }
            if (info == null) {
                say(MSG_SERVER_FAILED)
            } else {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(browserUrlFor(info, shownEntry))))
                } catch (e: ActivityNotFoundException) {
                    server.stop()
                    say(MSG_NO_BROWSER)
                }
            }
        }
    }

    // A finished save reloads the page one second later; a newer save starts that wait again. A page that is
    // still loading is waited for, so the reload never cuts a load short.
    val firstSaveTick = remember { saveTick }
    LaunchedEffect(saveTick) {
        if (saveTick == firstSaveTick) return@LaunchedEffect
        delay(AUTO_REFRESH_DELAY_MS)
        snapshotFlow { loading }.first { !it }
        val now = SystemClock.elapsedRealtime()
        val ready = shouldAutoRefresh(
            enabled = autoRefreshNow,
            previewVisible = true,
            saveJustFinished = true,
            loading = loading,
            millisSinceLastReload = now - holder.lastReloadAt,
        )
        if (ready) {
            holder.lastReloadAt = now
            holder.webView?.reload()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) holder.webView?.onPause()
            if (event == Lifecycle.Event.ON_RESUME) holder.webView?.onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        onDispose {
            // A page that is waiting for an answer would freeze, so it always gets one.
            pendingDialog?.cancel()
            holder.release()
        }
    }

    val viewport: @Composable (Modifier) -> Unit = { modifier ->
        PreviewViewport(
            preset = view.preset,
            startUrl = startUrl,
            holder = holder,
            responder = responder,
            console = console,
            host = host,
            loading = loading,
            onLoading = { loading = it },
            onDialog = { state ->
                pendingDialog?.cancel()
                pendingDialog = state
            },
            onExternalLink = { pendingLink = it },
            modifier = modifier,
        )
    }
    val menu: @Composable () -> Unit = {
        PreviewMenu(
            autoRefresh = autoRefresh,
            onAutoRefreshChange = { scope.launch { prefs.setAutoRefresh(it) } },
            onOpenInBrowser = openInPhoneBrowser,
            onClearData = { showClearConfirm = true },
        )
    }

    if (embedded) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = NeriboTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NeriboIconButton(icon = Icons.Outlined.Refresh, contentDescription = "Reload", onClick = reload)
                PagePicker(pages = pages, current = shownEntry, onPick = pickPage)
                SizeChip(preset = view.preset, onPick = { view.preset = it })
                ConsoleAction(errorCount = console.errorCount, onClick = { showConsole = true })
                menu()
            }
            if (servingHere) ServingRow(onStop = { server.stop() })
            if (rootPrefix == CoderPreviewRoot.BUILT_FOLDER) BuiltBanner(builtAt, onOpenBuild)
            viewport(Modifier.weight(1f))
        }
    } else {
        NeriboScaffold(
            topBar = {
                NeriboTopBar(
                    title = "Preview",
                    onBack = onClose,
                    actions = {
                        NeriboIconButton(icon = Icons.Outlined.Refresh, contentDescription = "Reload", onClick = reload)
                        ConsoleAction(errorCount = console.errorCount, onClick = { showConsole = true })
                        menu()
                    },
                )
            },
            snackbarHostState = snackbar,
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding(),
            ) {
                CoderTipHost(
                    screen = TipScreen.Preview,
                    context = TipContext(
                        screen = TipScreen.Preview,
                        previewLoaded = !loading,
                        busy = loading || pendingDialog != null || pendingLink != null || showClearConfirm || showConsole,
                    ),
                    modifier = Modifier.padding(horizontal = NeriboTheme.spacing.md, vertical = NeriboTheme.spacing.xs),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = NeriboTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PagePicker(pages = pages, current = shownEntry, onPick = pickPage)
                    SizeChip(preset = view.preset, onPick = { view.preset = it })
                }
                if (servingHere) ServingRow(onStop = { server.stop() })
                if (rootPrefix == CoderPreviewRoot.BUILT_FOLDER) BuiltBanner(builtAt, onOpenBuild)
                viewport(Modifier.weight(1f))
            }
        }
    }

    val dialog = pendingDialog
    if (dialog != null) {
        PageDialog(state = dialog, onDone = { pendingDialog = null })
    }
    val link = pendingLink
    if (link != null) {
        ConfirmDialog(
            title = "Open this link in your browser?",
            message = link,
            confirmLabel = "Open",
            onConfirm = {
                pendingLink = null
                openInBrowser(context, link)
            },
            onDismiss = { pendingLink = null },
        )
    }
    if (showClearConfirm) {
        ConfirmDialog(
            title = "Clear preview data?",
            message = "This removes what your preview pages saved, such as to-do lists, for all Coder projects. Your project files are not changed.",
            confirmLabel = "Clear",
            onConfirm = {
                showClearConfirm = false
                clearPreviewData()
                reload()
            },
            onDismiss = { showClearConfirm = false },
            destructive = true,
        )
    }
    val shownMessages = remember(console.messages) { console.messages.map { withFriendlyText(it) } }
    val askAi: ((CoderConsoleMessage) -> Unit)? = onAskAi?.let { ask ->
        { message: CoderConsoleMessage ->
            showConsole = false
            ask(message)
        }
    }
    if (showConsole) {
        CoderConsolePanel(
            messages = shownMessages,
            onClear = { console.clear() },
            onClose = { showConsole = false },
            onAskAi = askAi,
            onCopied = { say(MSG_COPIED) },
        )
    }
}

/**
 * The page itself: the browser view laid out at exactly the width of the chosen [preset] (the whole area for Full),
 * centered with a hairline border when the area is wider and scrolling sideways when it is narrower. The page is
 * never scaled or zoomed. Changing [startUrl] opens that page in the same browser view.
 */
@Composable
private fun PreviewViewport(
    preset: CoderDevicePreset,
    startUrl: String,
    holder: PreviewHolder,
    responder: CoderPreviewResponder,
    console: CoderConsole,
    host: String,
    loading: Boolean,
    onLoading: (Boolean) -> Unit,
    onDialog: (PageDialogState) -> Unit,
    onExternalLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val scrollState = rememberScrollState()
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val paneWidth = maxWidth
        val pageWidth: Dp = if (preset.widthDp == null) paneWidth else presetWidthDp(preset, paneWidth.value.toInt()).dp
        val framed = preset.widthDp != null && pageWidth < paneWidth
        Box(modifier = Modifier.fillMaxSize().horizontalScroll(scrollState)) {
            Box(
                modifier = Modifier.width(maxOf(pageWidth, paneWidth)).fillMaxHeight(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    modifier = Modifier
                        .width(pageWidth)
                        .fillMaxHeight()
                        .then(if (framed) Modifier.border(Dp.Hairline, colors.outlineVariant) else Modifier),
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { viewContext ->
                            val browser = buildPreviewWebView(
                                context = viewContext,
                                responder = responder,
                                console = console,
                                host = host,
                                onLoading = onLoading,
                                onDialog = onDialog,
                                onExternalLink = onExternalLink,
                            )
                            holder.webView = browser
                            holder.loadedUrl = startUrl
                            browser.loadUrl(startUrl)
                            browser
                        },
                        update = { browser ->
                            if (holder.loadedUrl != startUrl) {
                                holder.loadedUrl = startUrl
                                browser.loadUrl(startUrl)
                            }
                        },
                    )
                }
            }
        }
        if (loading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                color = colors.primary,
                trackColor = colors.surfaceVariant,
            )
        }
    }
}

/** The size chip: it shows the chosen width ("Phone 360") and opens a small menu of the four sizes. */
@Composable
private fun SizeChip(preset: CoderDevicePreset, onPick: (CoderDevicePreset) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box {
        NeriboChip(label = preset.chipText, selected = false, onClick = { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (option in CoderDevicePreset.values()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (option.widthDp == null) "Full width" else option.chipText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (option == preset) colors.primary else colors.onSurface,
                        )
                    },
                    onClick = {
                        expanded = false
                        onPick(option)
                    },
                )
            }
        }
    }
}

/** The preview's three-dot menu: the auto-refresh switch, Open in browser and Clear preview data. */
@Composable
private fun PreviewMenu(
    autoRefresh: Boolean,
    onAutoRefreshChange: (Boolean) -> Unit,
    onOpenInBrowser: () -> Unit,
    onClearData: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box {
        NeriboIconButton(icon = Icons.Outlined.MoreVert, contentDescription = "More options", onClick = { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(text = "Auto-refresh", style = MaterialTheme.typography.bodyLarge, color = colors.onSurface) },
                onClick = { onAutoRefreshChange(!autoRefresh) },
                trailingIcon = { Switch(checked = autoRefresh, onCheckedChange = null) },
            )
            DropdownMenuItem(
                text = { Text(text = "Open in browser", style = MaterialTheme.typography.bodyLarge, color = colors.onSurface) },
                onClick = {
                    expanded = false
                    onOpenInBrowser()
                },
            )
            DropdownMenuItem(
                text = { Text(text = "Clear preview data", style = MaterialTheme.typography.bodyLarge, color = colors.onSurface) },
                onClick = {
                    expanded = false
                    onClearData()
                },
            )
        }
    }
}

/** Shown while the page is served to the phone's browser: a muted label, a reminder and a Stop button. */
@Composable
private fun ServingRow(onStop: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = NeriboTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NeriboTheme.spacing.sm),
    ) {
        Text(
            text = "Serving on this phone",
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .background(colors.surfaceVariant)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        Text(
            text = "Keep Coder open while you view it",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onStop, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(text = "Stop", style = MaterialTheme.typography.labelLarge, color = colors.primary)
        }
    }
}

/** The console button with a small count of errors on it. The count is hidden when there are none. */
@Composable
private fun ConsoleAction(errorCount: Int, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val description = if (errorCount > 0) {
        "Console, $errorCount " + if (errorCount == 1) "error" else "errors"
    } else {
        "Console"
    }
    Box {
        NeriboIconButton(icon = Icons.Outlined.ViewList, contentDescription = description, onClick = onClick)
        if (errorCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 4.dp)
                    .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                    .clip(CircleShape)
                    .background(colors.error),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (errorCount > 99) "99+" else errorCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onError,
                )
            }
        }
    }
}

/** A native dialog for a page's alert, confirm or prompt. It shows only the message, never the page's address. */
@Composable
private fun PageDialog(state: PageDialogState, onDone: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var text by remember(state) { mutableStateOf(state.defaultText) }
    AlertDialog(
        onDismissRequest = {
            state.dismiss()
            onDone()
        },
        confirmButton = {
            TextButton(
                onClick = {
                    state.confirm(text)
                    onDone()
                },
            ) {
                Text(text = "OK", style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = if (state.kind == PageDialogKind.Alert) {
            null
        } else {
            {
                TextButton(
                    onClick = {
                        state.cancel()
                        onDone()
                    },
                ) {
                    Text(
                        text = "Cancel",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        },
        title = { Text(text = "This page says", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                Column(
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(text = state.message, style = MaterialTheme.typography.bodyMedium)
                }
                if (state.kind == PageDialogKind.Prompt) {
                    NeriboTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.padding(top = NeriboTheme.spacing.md),
                    )
                }
            }
        },
        shape = MaterialTheme.shapes.large,
        containerColor = colors.surface,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceVariant,
        tonalElevation = 0.dp,
    )
}

/**
 * Makes the browser view with the fixed list of safe settings. There is no JavaScript bridge, no
 * file access, and nothing a page asks for (location, camera, files, new windows) is granted.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun buildPreviewWebView(
    context: Context,
    responder: CoderPreviewResponder,
    console: CoderConsole,
    host: String,
    onLoading: (Boolean) -> Unit,
    onDialog: (PageDialogState) -> Unit,
    onExternalLink: (String) -> Unit,
): WebView {
    WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    val view = WebView(context)
    val settings = view.settings
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    settings.setSupportMultipleWindows(false)
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.mediaPlaybackRequiresUserGesture = true
    settings.builtInZoomControls = false
    settings.setGeolocationEnabled(false)
    settings.textZoom = 100

    view.webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
            if (request == null) null else responder.respond(request)

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url ?: return true
            if (url.scheme?.lowercase() != "https") return true
            if (url.host?.lowercase() == host) return false
            onExternalLink(url.toString())
            return true
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            console.clear()
            onLoading(true)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            onLoading(false)
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request?.isForMainFrame == true) {
                console.addError("This page could not be loaded: " + (error?.description ?: "unknown problem"))
            }
        }
    }

    view.webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            if (consoleMessage != null) console.onConsoleMessage(consoleMessage)
            return true
        }

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            onLoading(newProgress < 100)
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
            request?.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
            callback?.invoke(origin, false, false)
        }

        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: WebChromeClient.FileChooserParams?,
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            return false
        }

        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            if (result != null) onDialog(PageDialogState(PageDialogKind.Alert, message.orEmpty(), "", result))
            return true
        }

        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            if (result != null) onDialog(PageDialogState(PageDialogKind.Confirm, message.orEmpty(), "", result))
            return true
        }

        override fun onJsPrompt(
            view: WebView?,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: JsPromptResult?,
        ): Boolean {
            if (result != null) {
                onDialog(PageDialogState(PageDialogKind.Prompt, message.orEmpty(), defaultValue.orEmpty(), result))
            }
            return true
        }
    }
    return view
}

/** The address to open for [entryPath]: Markdown and JSON files go through the app's own pages, everything else is a normal file. */
private fun previewEntryUrl(projectId: String, rootPrefix: String, entryPath: String): String {
    val extension = CoderPaths.nameOf(entryPath).substringAfterLast('.', "").lowercase()
    val virtual = CoderPreviewHost.VIRTUAL_PREFIX
    return when (extension) {
        "md", "markdown" -> CoderPreviewHost.urlFor(projectId, "$virtual/md/$entryPath")
        "json" -> CoderPreviewHost.urlFor(projectId, "$virtual/json/$entryPath")
        else -> CoderPreviewHost.urlFor(projectId, CoderPreviewRoot.join(rootPrefix, entryPath))
    }
}

/**
 * The address of [entryPath] on the local server, with the secret in the query so the first request is accepted.
 * The server adds the site's top folder itself, so only the page's own path is used; Markdown and JSON files go
 * through the app's own pages, like in [previewEntryUrl].
 */
internal fun browserUrlFor(info: CoderServerInfo, entryPath: String): String {
    val extension = CoderPaths.nameOf(entryPath).substringAfterLast('.', "").lowercase()
    val virtual = CoderPreviewHost.VIRTUAL_PREFIX
    val path = when (extension) {
        "md", "markdown" -> "$virtual/md/$entryPath"
        "json" -> "$virtual/json/$entryPath"
        else -> entryPath
    }
    val encoded = path.split('/').joinToString("/") { Uri.encode(it) }
    return "http://127.0.0.1:${info.port}/$encoded?k=${info.token}"
}

/** Removes everything pages saved. Safe because Coder owns the only browser view in the app. */
private fun clearPreviewData() {
    WebStorage.getInstance().deleteAllData()
    val cookies = CookieManager.getInstance()
    cookies.removeAllCookies(null)
    cookies.flush()
}

private fun openInBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        // No browser is installed, so there is nothing to open the link with.
    }
}
