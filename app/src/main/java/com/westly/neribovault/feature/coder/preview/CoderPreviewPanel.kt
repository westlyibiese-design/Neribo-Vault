package com.westly.neribovault.feature.coder.preview

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.westly.neribovault.BuildConfig
import com.westly.neribovault.NeriboApp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.ConfirmDialog
import com.westly.neribovault.core.ui.components.LoadingState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTextField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.feature.coder.files.CoderFileStore
import com.westly.neribovault.feature.coder.files.CoderPaths
import com.westly.neribovault.feature.coder.ui.CoderHintCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Holds the browser view so back presses and the lifecycle can reach it. It is not state: nothing redraws when it changes. */
private class PreviewHolder {
    var webView: WebView? = null

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
 */
@Composable
fun CoderPreviewPanel(
    projectId: String,
    entryPath: String,
    onClose: () -> Unit,
    onAskAi: ((CoderConsoleMessage) -> Unit)? = null,
) {
    val context = LocalContext.current
    val store = remember(context) { (context.applicationContext as NeriboApp).container.coderFileStore }
    val holder = remember { PreviewHolder() }
    val plan by produceState<PreviewPlan?>(initialValue = null, projectId, store) {
        value = withContext(Dispatchers.IO) {
            PreviewPlan(
                rootPrefix = CoderPreviewRoot.find(store, projectId) ?: "",
                build = CoderBuildDetector.check(store, projectId),
            )
        }
    }
    BackHandler {
        val view = holder.webView
        if (view != null && view.canGoBack()) view.goBack() else onClose()
    }
    val current = plan
    if (current == null) {
        NeriboScaffold(topBar = { NeriboTopBar(title = "Preview", onBack = onClose) }) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) { LoadingState() }
        }
    } else if (current.build is CoderBuildCheck.NeedsBuild) {
        NeedsBuildNotice(
            reason = current.build.reason,
            builtFolderHint = current.build.builtFolderHint,
            onBack = onClose,
        )
    } else {
        PreviewContent(projectId, entryPath, current.rootPrefix, store, holder, onClose, onAskAi)
    }
}

/** What the preview found out about the project before showing it: the site's top folder and whether it needs a build. */
private class PreviewPlan(val rootPrefix: String, val build: CoderBuildCheck)

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
    store: CoderFileStore,
    holder: PreviewHolder,
    onClose: () -> Unit,
    onAskAi: ((CoderConsoleMessage) -> Unit)?,
) {
    val context = LocalContext.current
    val host = remember(projectId) { CoderPreviewHost.hostFor(projectId) }
    val responder = remember(projectId, rootPrefix) {
        CoderPreviewResponder(context.applicationContext, store, projectId, rootPrefix)
    }
    val console = remember(projectId, rootPrefix) { CoderConsole(host, rootPrefix) }
    val startUrl = remember(projectId, rootPrefix, entryPath) { previewEntryUrl(projectId, rootPrefix, entryPath) }
    var loading by remember { mutableStateOf(true) }
    var showConsole by rememberSaveable { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var pendingDialog by remember { mutableStateOf<PageDialogState?>(null) }
    var pendingLink by remember { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

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

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = "Preview",
                onBack = onClose,
                actions = {
                    NeriboIconButton(
                        icon = Icons.Outlined.Refresh,
                        contentDescription = "Reload",
                        onClick = { holder.webView?.reload() },
                    )
                    ConsoleAction(errorCount = console.errorCount, onClick = { showConsole = true })
                    OverflowMenu(
                        actions = listOf(MenuAction("Clear preview data", { showClearConfirm = true })),
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            CoderHintCard(
                id = "preview",
                text = "Something not working? Tap the console icon to see messages and errors.",
                modifier = Modifier.padding(horizontal = NeriboTheme.spacing.md, vertical = NeriboTheme.spacing.xs),
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    val view = buildPreviewWebView(
                        context = viewContext,
                        responder = responder,
                        console = console,
                        host = host,
                        onLoading = { loading = it },
                        onDialog = { state ->
                            pendingDialog?.cancel()
                            pendingDialog = state
                        },
                        onExternalLink = { pendingLink = it },
                    )
                    holder.webView = view
                    view.loadUrl(startUrl)
                    view
                },
            )
            if (loading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
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
                holder.webView?.reload()
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
        )
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
