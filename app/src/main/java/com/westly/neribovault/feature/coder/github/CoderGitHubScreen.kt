package com.westly.neribovault.feature.coder.github

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.ConfirmDialog
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.LoadingState
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTextField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.core.util.formatDateTime
import com.westly.neribovault.data.local.entity.CoderProjectEntity

private const val TOKEN_PAGE_URL = "https://github.com/settings/tokens/new?scopes=repo&description=Neribo%20Coder"

/** The most characters the token field accepts; a real token is far shorter, the rest allows for pasted spaces. */
private const val MAX_TYPED_TOKEN = 2000
private const val MAX_COMMIT_MESSAGE = 200
private const val MAX_LISTED = 10

/**
 * The GitHub screen of one project: connect an account, link or create a repository, and commit and push.
 * The token is typed into a plain `remember` field and handed to the ViewModel; it is never saved with the
 * screen's state and is emptied as soon as the connection works.
 */
@Composable
fun CoderGitHubScreen(projectId: String, onBack: () -> Unit) {
    val vm = neriboViewModel(key = "coder-github-$projectId") { c ->
        val client = CoderGitHubClient()
        CoderGitHubViewModel(
            projectId = projectId,
            projects = c.coderProjectsRepository,
            settings = c.coderGitHubSettings,
            client = client,
            pusher = CoderGitHubPusher(client, c.coderGitHubSettings, c.coderFileStore, c.coderProjectsRepository),
            developerProjects = c.projectsRepository,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val snackbarHostState = remember { SnackbarHostState() }
    var commitMessage by rememberSaveable { mutableStateOf(DEFAULT_COMMIT_MESSAGE) }
    var showDisconnect by rememberSaveable { mutableStateOf(false) }
    var showUnlink by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        val text = state.message ?: return@LaunchedEffect
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(text)
        vm.consumeMessage()
    }

    NeriboScaffold(
        topBar = { NeriboTopBar(title = "GitHub", subtitle = state.project?.name, onBack = onBack) },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        val project = state.project
        when {
            state.isLoading -> LoadingState(modifier = Modifier.padding(padding))
            project == null -> EmptyState(
                icon = Icons.Outlined.ErrorOutline,
                title = "This project isn't available",
                message = "It may have been deleted.",
                modifier = Modifier.fillMaxSize().padding(padding),
                actionLabel = "Go back",
                onAction = onBack,
            )
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.screen, vertical = spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                AccountCard(
                    state = state,
                    onConnect = { vm.connect(it) },
                    onTokenEdited = { vm.clearConnectError() },
                    onDisconnect = { showDisconnect = true },
                )
                RepositoryCard(
                    state = state,
                    project = project,
                    onLinkExisting = { vm.linkExisting(it) },
                    onCreate = { name, isPrivate -> vm.createAndLink(name, isPrivate) },
                    onEdited = { vm.clearRepoError() },
                    onUnlink = { showUnlink = true },
                )
                PushCard(
                    state = state,
                    project = project,
                    message = commitMessage,
                    onMessageChange = { commitMessage = it.replace('\n', ' ').take(MAX_COMMIT_MESSAGE) },
                    onPush = { vm.startPush(commitMessage) },
                    onCancel = { vm.cancelPush() },
                    onRetry = { vm.retryPush() },
                )
            }
        }
    }

    val push = state.push
    if (push is CoderPushUiState.Confirm) {
        PushSummaryDialog(
            prepared = push.prepared,
            onConfirm = { vm.confirmPush() },
            onDismiss = { vm.dismissSummary() },
        )
    }
    val addressPrompt = state.addressPrompt
    if (addressPrompt != null) {
        ConfirmDialog(
            title = "Save this repository address in your Developer project?",
            message = addressPrompt.url,
            confirmLabel = "Yes",
            onConfirm = { vm.answerAddressPrompt(true) },
            onDismiss = { vm.answerAddressPrompt(false) },
            dismissLabel = "No",
        )
    }
    if (showDisconnect) {
        ConfirmDialog(
            title = "Disconnect GitHub?",
            message = "The token will be removed from this phone. This project keeps its link to the repository, and nothing is deleted on GitHub.",
            confirmLabel = "Disconnect",
            onConfirm = {
                showDisconnect = false
                vm.disconnect()
            },
            onDismiss = { showDisconnect = false },
            destructive = true,
        )
    }
    if (showUnlink) {
        ConfirmDialog(
            title = "Unlink from GitHub?",
            message = "Unlink this project from GitHub? Nothing is deleted on GitHub or on your phone.",
            confirmLabel = "Unlink",
            onConfirm = {
                showUnlink = false
                vm.unlink()
            },
            onDismiss = { showUnlink = false },
        )
    }
}

/** The account card: how to make a token and paste it, or who is connected. */
@Composable
private fun AccountCard(
    state: CoderGitHubUiState,
    onConnect: (String) -> Unit,
    onTokenEdited: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var token by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(state.connected) {
        if (state.connected) {
            token = ""
            visible = false
        }
    }

    NeriboCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            SectionHeader(text = "Account")
            if (state.connected) {
                val last4 = state.tokenLast4
                val ending = if (last4.isNullOrEmpty()) "" else " (token ending $last4)"
                Text(
                    text = "Connected as ${state.login ?: "your account"}$ending",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurface,
                )
                if (state.scopeWarning) {
                    MessageLine(text = "This token may not be allowed to write to repositories.", isWarning = true)
                }
                NeriboButton(
                    text = "Disconnect",
                    onClick = onDisconnect,
                    style = ButtonStyle.Secondary,
                )
            } else {
                Text(
                    text = "To save your project to GitHub, make a token. It is like a password just for this app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    text = "1. Open the link below on github.com.\n2. Tick 'repo', create the token and copy it.\n3. Paste it here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface,
                )
                Text(
                    text = TOKEN_PAGE_URL,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurface,
                )
                NeriboButton(
                    text = "Copy link",
                    onClick = { clipboard.setText(AnnotatedString(TOKEN_PAGE_URL)) },
                    style = ButtonStyle.Secondary,
                    leadingIcon = Icons.Outlined.ContentCopy,
                )
                NeriboTextField(
                    value = token,
                    onValueChange = {
                        token = it.take(MAX_TYPED_TOKEN)
                        onTokenEdited()
                    },
                    label = "GitHub token",
                    isError = state.connectError != null,
                    supportingText = state.connectError,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrect = false,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        NeriboIconButton(
                            icon = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (visible) "Hide token" else "Show token",
                            onClick = { visible = !visible },
                        )
                    },
                )
                NeriboButton(
                    text = "Paste",
                    onClick = {
                        val pasted = clipboard.getText()?.text
                        if (!pasted.isNullOrEmpty()) {
                            token = pasted.take(MAX_TYPED_TOKEN)
                            onTokenEdited()
                        }
                    },
                    style = ButtonStyle.Secondary,
                    leadingIcon = Icons.Outlined.ContentPaste,
                )
                if (state.connecting) {
                    ProgressRow(text = "Checking your token…", onCancel = null)
                }
                NeriboButton(
                    text = "Connect",
                    onClick = { onConnect(token) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.connecting && token.isNotBlank(),
                )
            }
        }
    }
}

/** The repository card: link an existing repository, create a new one, or show the link. */
@Composable
private fun RepositoryCard(
    state: CoderGitHubUiState,
    project: CoderProjectEntity,
    onLinkExisting: (String) -> Unit,
    onCreate: (String, Boolean) -> Unit,
    onEdited: () -> Unit,
    onUnlink: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    var repoRef by rememberSaveable { mutableStateOf("") }
    var typedName by rememberSaveable { mutableStateOf<String?>(null) }
    var makePrivate by rememberSaveable { mutableStateOf(true) }
    val owner = project.repoOwner
    val name = project.repoName
    val branch = project.repoBranch

    NeriboCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            SectionHeader(text = "Repository")
            if (owner != null && name != null && branch != null) {
                val visibility = when (state.repoIsPrivate) {
                    true -> " · Private"
                    false -> " · Public"
                    null -> ""
                }
                Text(
                    text = "$owner/$name · branch $branch$visibility",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurface,
                )
                NeriboButton(
                    text = "Unlink",
                    onClick = onUnlink,
                    style = ButtonStyle.Secondary,
                )
            } else {
                if (!state.connected) {
                    Text(
                        text = "Connect your account first, then link a repository.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                Text(
                    text = "Use an existing repository",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface,
                )
                NeriboTextField(
                    value = repoRef,
                    onValueChange = {
                        repoRef = it.take(300)
                        onEdited()
                    },
                    label = "Repository",
                    placeholder = "owner/name or a github.com address",
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrect = false,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                )
                NeriboButton(
                    text = "Link",
                    onClick = { onLinkExisting(repoRef) },
                    enabled = state.connected && !state.repoBusy && repoRef.isNotBlank(),
                )
                Text(
                    text = "Create a new repository",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface,
                    modifier = Modifier.padding(top = spacing.sm),
                )
                val shownName = typedName ?: suggestRepoName(project.name)
                NeriboTextField(
                    value = shownName,
                    onValueChange = {
                        typedName = it.take(100)
                        onEdited()
                    },
                    label = "Repository name",
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrect = false,
                        imeAction = ImeAction.Done,
                    ),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(value = makePrivate, role = Role.Switch, onValueChange = { makePrivate = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (makePrivate) "Private: only you can see it" else "Public: anyone can see it",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = makePrivate, onCheckedChange = null)
                }
                NeriboButton(
                    text = "Create and link",
                    onClick = { onCreate(shownName, makePrivate) },
                    enabled = state.connected && !state.repoBusy && shownName.isNotBlank(),
                )
                if (state.repoBusy) {
                    ProgressRow(text = "Talking to GitHub…", onCancel = null)
                }
                val error = state.repoError
                if (error != null) {
                    MessageLine(text = error, isWarning = false)
                }
            }
        }
    }
}

/** The push card: commit message, the push button, progress, the result or the error. */
@Composable
private fun PushCard(
    state: CoderGitHubUiState,
    project: CoderProjectEntity,
    message: String,
    onMessageChange: (String) -> Unit,
    onPush: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val linked = project.repoOwner != null && project.repoName != null && project.repoBranch != null
    val ready = state.connected && linked
    val push = state.push
    val busy = push is CoderPushUiState.Checking || push is CoderPushUiState.Uploading || push is CoderPushUiState.Confirm
    val lastPushed = project.lastPushedAt

    NeriboCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            SectionHeader(text = "Push")
            if (!state.connected) {
                Text(
                    text = "Connect your account to push.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            } else if (!linked) {
                Text(
                    text = "Link a repository to push your files.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            NeriboTextField(
                value = message,
                onValueChange = onMessageChange,
                label = "Commit message",
                supportingText = "A short note about what changed.",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            NeriboButton(
                text = "Commit and push",
                onClick = onPush,
                modifier = Modifier.fillMaxWidth(),
                enabled = ready && !busy,
            )
            if (lastPushed != null && push !is CoderPushUiState.Done) {
                Text(
                    text = "Last pushed ${formatDateTime(lastPushed)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            when (push) {
                CoderPushUiState.Idle, is CoderPushUiState.Confirm -> Unit
                CoderPushUiState.Checking -> ProgressRow(text = "Checking your files…", onCancel = onCancel)
                is CoderPushUiState.Uploading -> ProgressRow(
                    text = if (push.total == 0) "Starting the upload…" else "Uploading ${push.done} of ${push.total}",
                    onCancel = onCancel,
                )
                is CoderPushUiState.Done -> {
                    val count = push.result.uploadedCount
                    val url = "https://github.com/${push.owner}/${push.name}/commit/${push.result.commitSha}"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = NeriboTheme.extraColors.success,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = "Pushed $count ${if (count == 1) "file" else "files"}",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.onSurface,
                            modifier = Modifier.padding(start = spacing.sm),
                        )
                    }
                    Text(text = url, style = MaterialTheme.typography.bodySmall, color = colors.onSurface)
                    NeriboButton(
                        text = "Copy link",
                        onClick = { clipboard.setText(AnnotatedString(url)) },
                        style = ButtonStyle.Secondary,
                        leadingIcon = Icons.Outlined.ContentCopy,
                    )
                    if (lastPushed != null) {
                        Text(
                            text = "Last pushed ${formatDateTime(lastPushed)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                is CoderPushUiState.Failed -> {
                    MessageLine(text = push.error.message, isWarning = false)
                    if (push.error.kind == CoderGitHubErrorKind.InvalidToken) {
                        Text(
                            text = "Disconnect and connect again with a new token.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    NeriboButton(text = "Try again", onClick = onRetry, style = ButtonStyle.Secondary)
                }
            }
        }
    }
}

/** What a push is about to do, shown before anything is uploaded. Secrets get a clearly marked warning. */
@Composable
private fun PushSummaryDialog(prepared: PushPrepared, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val preview = prepared.preview
    val plan = preview.plan
    val uploadCount = plan.newFiles.size + plan.changedFiles.size
    val hasFindings = preview.findings.isNotEmpty()
    val nothing = uploadCount == 0
    val confirmLabel = when {
        nothing -> "OK"
        hasFindings -> "Push anyway"
        else -> "Push"
    }
    val noFilesAtAll = nothing && plan.unchangedCount == 0 && plan.conflicts.isEmpty() && prepared.oversized.isEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = if (nothing) onDismiss else onConfirm) {
                Text(
                    text = confirmLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (hasFindings && !nothing) colors.error else colors.primary,
                )
            }
        },
        dismissButton = {
            if (!nothing) {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = "Cancel",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        },
        title = {
            Text(
                text = if (nothing) "Nothing to push" else "Ready to push?",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                if (nothing) {
                    Text(
                        text = if (noFilesAtAll) {
                            "Nothing to push. This project has no files yet."
                        } else {
                            "GitHub already has your latest files."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    val added = plan.newFiles.size
                    val changed = plan.changedFiles.size
                    Text(
                        text = "$added new ${fileWord(added)}, $changed changed ${fileWord(changed)}, ${plan.unchangedCount} unchanged.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "Files you delete on your phone stay on GitHub.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                SkippedList(prefix = "Skipped (a folder with this name is on GitHub)", paths = plan.conflicts)
                SkippedList(prefix = "Skipped (over 20 MB)", paths = prepared.oversized)
                if (hasFindings && !nothing) {
                    SecretWarning(findings = preview.findings, isPrivate = preview.isPrivate)
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

private fun fileWord(count: Int): String = if (count == 1) "file" else "files"

/** Lists up to 10 skipped paths, each with [prefix], and how many more there are. Shows nothing for an empty list. */
@Composable
private fun SkippedList(prefix: String, paths: List<String>) {
    for (path in paths.take(MAX_LISTED)) {
        Text(text = "$prefix: $path", style = MaterialTheme.typography.bodySmall)
    }
    if (paths.size > MAX_LISTED) {
        Text(text = "$prefix: and ${paths.size - MAX_LISTED} more", style = MaterialTheme.typography.bodySmall)
    }
}

/** The marked warning about lines that look like passwords or keys. It lists where they are, never what they say. */
@Composable
private fun SecretWarning(findings: List<SecretFinding>, isPrivate: Boolean) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val warning = NeriboTheme.extraColors.warning
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(warning.copy(alpha = 0.14f))
            .padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Warning,
                contentDescription = null,
                tint = warning,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = "Possible secrets found",
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface,
                modifier = Modifier.padding(start = spacing.sm),
            )
        }
        for (finding in findings.take(MAX_LISTED)) {
            Text(
                text = "${finding.path}, line ${finding.line} (${finding.kind})",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurface,
            )
        }
        if (findings.size > MAX_LISTED) {
            Text(
                text = "and ${findings.size - MAX_LISTED} more",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurface,
            )
        }
        Text(
            text = "Pushing publishes them. Even in a private repository they stay in GitHub's history. Remove them first if you can.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
        )
        if (!isPrivate) {
            Text(
                text = "This repository is public: anyone can see it.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
            )
        }
    }
}

/** A line with an icon and text, so the meaning never depends on color alone. */
@Composable
private fun MessageLine(text: String, isWarning: Boolean) {
    val spacing = NeriboTheme.spacing
    val tint = if (isWarning) NeriboTheme.extraColors.warning else MaterialTheme.colorScheme.error
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = if (isWarning) Icons.Outlined.Warning else Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = spacing.sm),
        )
    }
}

/** A spinner with a line of text and, when [onCancel] is given, a Cancel button. */
@Composable
private fun ProgressRow(text: String, onCancel: (() -> Unit)?) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            color = colors.primary,
            strokeWidth = 2.dp,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .padding(start = spacing.md),
        )
        if (onCancel != null) {
            NeriboButton(text = "Cancel", onClick = onCancel, style = ButtonStyle.Text)
        }
    }
}
