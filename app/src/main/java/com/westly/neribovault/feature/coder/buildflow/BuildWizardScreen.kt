package com.westly.neribovault.feature.coder.buildflow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.LoadingState
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTextField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.feature.coder.env.CoderEnvStore
import com.westly.neribovault.feature.coder.github.CoderGitHubClient
import com.westly.neribovault.feature.coder.github.CoderGitHubPusher
import com.westly.neribovault.feature.coder.github.PushPrepared
import com.westly.neribovault.feature.coder.github.SecretFinding
import com.westly.neribovault.feature.coder.preview.CoderPreviewPanel
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

private const val TOKEN_LINK = "https://github.com/settings/tokens/new?scopes=repo,workflow&description=Neribo%20Coder"
private const val MAX_LISTED = 10
private const val LAST_STEP = 5

/**
 * The build wizard of one project: a calm, one-step-per-screen way to build a React or npm project on GitHub for
 * free and preview the result here. A step that is already done is skipped and shown as a checked line. Nothing is
 * pushed or built without a tap, and the token is never shown.
 */
@Composable
fun BuildWizardScreen(
    projectId: String,
    onBack: () -> Unit,
    onOpenGitHub: () -> Unit,
    onOpenEnvironment: () -> Unit,
    onAskAi: () -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val cacheDir = remember(appContext) { appContext.cacheDir }
    val vm = neriboViewModel(key = "coder-build-$projectId") { c ->
        val client = CoderGitHubClient()
        val actions = CoderGitHubActions()
        BuildWizardViewModel(
            projectId = projectId,
            projects = c.coderProjectsRepository,
            settings = c.coderGitHubSettings,
            aiSettings = c.coderAiSettings,
            store = c.coderFileStore,
            envStore = CoderEnvStore.shared(appContext),
            client = client,
            pusher = CoderGitHubPusher(client, c.coderGitHubSettings, c.coderFileStore, c.coderProjectsRepository),
            runner = CoderBuildRunner(actions, c.coderFileStore, cacheDir),
            actions = actions,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var showPreview by rememberSaveable { mutableStateOf(false) }

    // The project, the variables and the GitHub connection may have changed on the screens this one opens.
    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(state.message) {
        val text = state.message ?: return@LaunchedEffect
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(text)
        vm.consumeMessage()
    }
    BackHandler(enabled = !showPreview) { if (!vm.goBackStep()) onBack() }

    if (showPreview) {
        CoderPreviewPanel(
            projectId = projectId,
            entryPath = "index.html",
            onClose = { showPreview = false },
            onOpenBuild = { showPreview = false },
        )
        return
    }

    val copyLink: () -> Unit = {
        clipboard.setText(AnnotatedString(TOKEN_LINK))
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar("Copied")
        }
    }
    val spacing = NeriboTheme.spacing
    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = "Build with GitHub",
                subtitle = state.project?.name,
                onBack = { if (!vm.goBackStep()) onBack() },
            )
        },
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
                val step = state.step
                Text(
                    text = "Step $step of $LAST_STEP",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SummaryLines(state)
                when (step) {
                    1 -> ConnectStep(state, onCopy = copyLink, onOpenGitHub = onOpenGitHub)
                    2 -> RepoStep(onOpenGitHub = onOpenGitHub)
                    3 -> CheckStep(state, onOutputDir = { vm.setOutputDir(it) }, onContinue = { vm.continueStep() }, onBackToCode = onBack)
                    4 -> EnvStep(
                        state,
                        onToggle = { name, on -> vm.setEnvSelected(name, on) },
                        onOpenEnvironment = onOpenEnvironment,
                        onContinue = { vm.continueStep() },
                    )
                    else -> BuildStep(
                        state,
                        onStart = { vm.startBuild() },
                        onCancel = { vm.cancelBuild() },
                        onTryAgain = { vm.tryAgain() },
                        onAskAi = { if (vm.prepareAskAi()) onAskAi() },
                        onPreview = { showPreview = true },
                        onDone = onBack,
                    )
                }
            }
        }
    }

    val pending = state.pendingPush
    if (pending != null) {
        PushConfirmDialog(prepared = pending, onConfirm = { vm.answerPush(true) }, onCancel = { vm.answerPush(false) })
    }
}

/** The steps that are already satisfied, each as one checked line. */
@Composable
private fun SummaryLines(state: BuildWizardUiState) {
    val step = state.step
    val project = state.project
    if (step > 1) CheckedLine("GitHub connected as ${state.login ?: "your account"}")
    if (step > 2 && project != null) CheckedLine("Repository ${project.repoOwner}/${project.repoName} · ${project.repoBranch}")
    val plan = state.plan
    if (step > 3 && plan != null) CheckedLine("Project checked: ${plan.tool}, output folder ${state.outputDir}")
    if (step > 4) {
        val count = state.envSelected.size
        CheckedLine(if (count == 0) "No variables used" else "$count ${if (count == 1) "variable" else "variables"} used")
    }
}

@Composable
private fun CheckedLine(text: String) {
    val spacing = NeriboTheme.spacing
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = "Done",
            tint = NeriboTheme.extraColors.success,
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

/** A card with a title, one sentence saying why the step is needed, and the step's own content. */
@Composable
private fun StepCard(title: String, why: String, content: @Composable () -> Unit) {
    val spacing = NeriboTheme.spacing
    NeriboCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(text = why, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun ConnectStep(state: BuildWizardUiState, onCopy: () -> Unit, onOpenGitHub: () -> Unit) {
    val reconnect = state.connected && state.needsWorkflowScope
    StepCard(
        title = if (reconnect) "Your token needs one more permission" else "Connect GitHub",
        why = if (reconnect) {
            "Saving the build file needs the 'workflow' permission. Make a new token with both 'repo' and 'workflow' ticked."
        } else {
            "GitHub does the building for you, for free. Coder needs your GitHub token."
        },
    ) {
        Text(text = TOKEN_LINK, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
        NeriboButton(text = "Copy link", onClick = onCopy, style = ButtonStyle.Secondary)
        for (line in listOf(
            "1. Open the link above in your browser and sign in to GitHub.",
            "2. Tick the boxes 'repo' and 'workflow'.",
            "3. Tap Generate token, then copy the token.",
            "4. Come back here and tap the button below.",
        )) {
            Text(text = line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        NeriboButton(
            text = if (reconnect) "Reconnect" else "Go to GitHub settings in Coder",
            onClick = onOpenGitHub,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RepoStep(onOpenGitHub: () -> Unit) {
    StepCard(
        title = "Choose a repository",
        why = "The build runs in a GitHub repository of your own, so Coder needs to know which one.",
    ) {
        NeriboButton(text = "Choose a repository in Coder", onClick = onOpenGitHub, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun CheckStep(state: BuildWizardUiState, onOutputDir: (String) -> Unit, onContinue: () -> Unit, onBackToCode: () -> Unit) {
    val plan = state.plan
    if (plan == null) {
        StepCard(title = "Check your project", why = "Coder is reading your package.json.") {
            Text(text = "One moment…", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    if (!plan.supported) {
        StepCard(title = "This project can't be built here", why = plan.unsupportedReason ?: "Coder can't build this project.") {
            NeriboButton(text = "Back to code", onClick = onBackToCode, modifier = Modifier.fillMaxWidth())
        }
        return
    }
    var dir by remember(plan) { mutableStateOf(state.outputDir) }
    val dirValid = CoderPackageAnalyzer.isValidOutputDir(dir.trim())
    StepCard(title = "Check your project", why = "Coder worked out how to build it. You can change the output folder if it is wrong.") {
        Text(text = "Detected: ${plan.tool}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        NeriboTextField(
            value = dir,
            onValueChange = {
                dir = it
                onOutputDir(it)
            },
            modifier = Modifier.fillMaxWidth(),
            label = "Output folder",
            isError = !dirValid,
            supportingText = if (dirValid) "The folder the build writes the finished site to." else "Use a plain folder name such as dist.",
        )
        Text(text = "Node version: ${plan.nodeVersion}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = "Install: ${plan.installCommand}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = "Build: ${plan.buildCommand}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (warning in plan.warnings) WarningLine(warning)
        NeriboButton(text = "Continue", onClick = onContinue, modifier = Modifier.fillMaxWidth(), enabled = dirValid)
    }
}

@Composable
private fun EnvStep(
    state: BuildWizardUiState,
    onToggle: (String, Boolean) -> Unit,
    onOpenEnvironment: () -> Unit,
    onContinue: () -> Unit,
) {
    val plan = state.plan
    val hint = if (plan == null) null else CoderPackageAnalyzer.prefixHint(plan.tool, state.envNames.filter { it in state.envSelected })
    StepCard(
        title = "Environment variables",
        why = "These are the values your project reads when it is built, such as an API address. You can skip this step.",
    ) {
        Text(
            text = "These become public in your site.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (state.envNames.isEmpty()) {
            Text(text = "You have no variables yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for (name in state.envNames) {
            val checked = name in state.envSelected
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = { onToggle(name, it) })
                Text(text = name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (hint != null) WarningLine(hint)
        NeriboButton(text = "Open the Environment screen", onClick = onOpenEnvironment, style = ButtonStyle.Text)
        NeriboButton(
            text = if (state.envNames.isEmpty()) "Skip" else "Continue",
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BuildStep(
    state: BuildWizardUiState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onTryAgain: () -> Unit,
    onAskAi: () -> Unit,
    onPreview: () -> Unit,
    onDone: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val working = state.phase == BuildPhase.Working
    StepCard(
        title = "Build",
        why = "Coder saves your files to GitHub, GitHub builds the site, and Coder brings the result back.",
    ) {
        if (state.phase == BuildPhase.Idle) {
            NeriboButton(text = "Start build", onClick = onStart, modifier = Modifier.fillMaxWidth())
        }
        if (state.phase != BuildPhase.Idle) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                for (stage in state.stages) StageRow(stage)
            }
        }
        if (working) {
            NeriboButton(text = "Cancel build", onClick = onCancel, style = ButtonStyle.Secondary, modifier = Modifier.fillMaxWidth())
        }
        if (state.phase == BuildPhase.Done) {
            Text(text = "Build finished", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            NeriboButton(text = "Preview built site", onClick = onPreview, modifier = Modifier.fillMaxWidth())
            NeriboButton(text = "Done", onClick = onDone, style = ButtonStyle.Secondary, modifier = Modifier.fillMaxWidth())
        }
        val failure = state.failure
        if (state.phase == BuildPhase.Failed && failure != null) {
            FailureBlock(failure, state.company, onTryAgain = onTryAgain, onAskAi = onAskAi)
        }
    }
}

/** One stage: an icon for its state and its name, with the state also written out so colour is never the only sign. */
@Composable
private fun StageRow(stage: BuildStage) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val word = when (stage.state) {
        BuildStageState.Waiting -> "waiting"
        BuildStageState.Running -> "running"
        BuildStageState.Done -> "done"
        BuildStageState.Failed -> "failed"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when (stage.state) {
                BuildStageState.Running -> CircularProgressIndicator(modifier = Modifier.size(18.dp), color = colors.primary, strokeWidth = 2.dp)
                BuildStageState.Done -> Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = NeriboTheme.extraColors.success, modifier = Modifier.size(20.dp))
                BuildStageState.Failed -> Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = colors.error, modifier = Modifier.size(20.dp))
                BuildStageState.Waiting -> Icon(Icons.Outlined.RadioButtonUnchecked, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
        Text(
            text = "${stage.label} · $word",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            modifier = Modifier.padding(start = spacing.sm),
        )
    }
}

/** What went wrong: the failed step, one plain sentence, the log (hidden until asked for) and the two buttons. */
@Composable
private fun FailureBlock(failure: BuildFailure, company: String?, onTryAgain: () -> Unit, onAskAi: () -> Unit) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    var showLog by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        Text(text = "The build stopped at: ${failure.stepName}", style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
        val line = failure.message ?: failure.explanation
        if (line != null) Text(text = line, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
        if (failure.logTail.isNotEmpty()) {
            NeriboButton(
                text = if (showLog) "Hide the log" else "Show the end of the log",
                onClick = { showLog = !showLog },
                style = ButtonStyle.Text,
            )
            if (showLog) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(colors.surfaceVariant)
                        .horizontalScroll(rememberScrollState())
                        .padding(spacing.sm),
                ) {
                    Text(text = failure.logTail, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant)
                }
            }
        }
        NeriboButton(text = "Try again", onClick = onTryAgain, modifier = Modifier.fillMaxWidth())
        NeriboButton(text = "Ask AI", onClick = onAskAi, style = ButtonStyle.Secondary, modifier = Modifier.fillMaxWidth())
        Text(
            text = "This sends the build log to ${company ?: "your AI provider"}.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun WarningLine(text: String) {
    val spacing = NeriboTheme.spacing
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.Warning, contentDescription = "Warning", tint = NeriboTheme.extraColors.warning, modifier = Modifier.size(20.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = spacing.sm),
        )
    }
}

/** What saving to GitHub is about to do, with the marked warning about anything that looks like a password or key. */
@Composable
private fun PushConfirmDialog(prepared: PushPrepared, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val preview = prepared.preview
    val plan = preview.plan
    val findings = preview.findings
    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = if (findings.isEmpty()) "Save and build" else "Save anyway",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (findings.isEmpty()) colors.primary else colors.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(text = "Cancel", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            }
        },
        title = { Text(text = "Save your files to GitHub?", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Text(
                    text = "${plan.newFiles.size} new, ${plan.changedFiles.size} changed, ${plan.unchangedCount} unchanged. " +
                        "GitHub needs your latest files to build them.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (prepared.oversized.isNotEmpty()) {
                    Text(text = "${prepared.oversized.size} files over 20 MB are left out.", style = MaterialTheme.typography.bodySmall)
                }
                if (findings.isNotEmpty()) SecretNote(findings, preview.isPrivate)
            }
        },
        shape = MaterialTheme.shapes.large,
        containerColor = colors.surface,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceVariant,
        tonalElevation = 0.dp,
    )
}

@Composable
private fun SecretNote(findings: List<SecretFinding>, isPrivate: Boolean) {
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
        Text(text = "Possible secrets found", style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
        for (finding in findings.take(MAX_LISTED)) {
            Text(text = "${finding.path}, line ${finding.line} (${finding.kind})", style = MaterialTheme.typography.bodySmall, color = colors.onSurface)
        }
        if (findings.size > MAX_LISTED) {
            Text(text = "and ${findings.size - MAX_LISTED} more", style = MaterialTheme.typography.bodySmall, color = colors.onSurface)
        }
        Text(
            text = "Saving publishes them. Even in a private repository they stay in GitHub's history. Remove them first if you can.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
        )
        if (!isPrivate) {
            Text(text = "This repository is public: anyone can see it.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
        }
    }
}
