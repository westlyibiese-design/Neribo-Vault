package com.westly.neribovault.feature.coder.env

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.ConfirmDialog
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.LoadingState
import com.westly.neribovault.core.ui.components.NeriboBottomSheet
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTextField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.SectionHeader
import kotlinx.coroutines.launch

private const val SNIPPET_SCRIPT = "<script src=\"/__coder/env.js\"></script>"
private const val SNIPPET_READ = "const apiUrl = window.__ENV__.API_URL;"
private const val DOTS = "••••••••"

/**
 * The Environment screen of one project: the variables a page can read (such as an API address), how to use them
 * in a page, and an import from a `.env` file. Values are shown as dots until the eye is tapped, are kept encrypted
 * on the phone and are never sent to an AI or pushed to GitHub.
 */
@Composable
fun EnvironmentScreen(projectId: String, onBack: () -> Unit) {
    val appContext = LocalContext.current.applicationContext
    val vm = neriboViewModel(key = "coder-env-$projectId") { c ->
        EnvironmentViewModel(projectId, c.coderProjectsRepository, CoderEnvStore.shared(appContext), c.coderFileStore)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    // These hold names and values, so they are plain remember state: never saved with the screen.
    var sheetOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<EnvVar?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var revealed by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(state.message) {
        val text = state.message ?: return@LaunchedEffect
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(text)
        vm.consumeMessage()
    }

    NeriboScaffold(
        topBar = { NeriboTopBar(title = "Environment", subtitle = state.project?.name, onBack = onBack) },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        when {
            state.isLoading -> LoadingState(modifier = Modifier.padding(padding))
            state.project == null -> EmptyState(
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
                NeriboCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Environment variables are values your page can read, such as an API address. " +
                            "Anything a web page uses can be seen by anyone who opens it, so never put passwords here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(spacing.lg),
                    )
                }
                SectionHeader(text = "Variables")
                if (state.vars.isEmpty()) {
                    EmptyState(
                        icon = Icons.Outlined.Storage,
                        title = "No variables yet",
                        message = "Tap Add variable to make your first one, for example API_URL.",
                    )
                }
                for (variable in state.vars) {
                    VariableRow(
                        variable = variable,
                        shown = variable.name in revealed,
                        onToggle = {
                            revealed = if (variable.name in revealed) revealed - variable.name else revealed + variable.name
                        },
                        onEdit = {
                            editing = variable
                            sheetOpen = true
                        },
                        onDelete = { deleting = variable.name },
                    )
                }
                NeriboButton(
                    text = "Add variable",
                    onClick = {
                        editing = null
                        sheetOpen = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                SnippetCard(
                    onCopy = {
                        clipboard.setText(AnnotatedString("$SNIPPET_SCRIPT\n$SNIPPET_READ"))
                        scope.launch {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            snackbarHostState.showSnackbar("Copied")
                        }
                    },
                )
                ImportCard(files = state.envFiles, onPick = { vm.prepareImport(it) })
            }
        }
    }

    if (sheetOpen) {
        VariableSheet(
            original = editing,
            problemWith = { name, value -> vm.problemWith(editing?.name, name, value) },
            onSave = { name, value ->
                if (vm.save(editing?.name, name, value)) sheetOpen = false
            },
            onDismiss = { sheetOpen = false },
        )
    }
    val toDelete = deleting
    if (toDelete != null) {
        ConfirmDialog(
            title = "Delete $toDelete?",
            message = "Pages that read this variable will no longer find it.",
            confirmLabel = "Delete",
            onConfirm = {
                deleting = null
                vm.delete(toDelete)
            },
            onDismiss = { deleting = null },
            destructive = true,
        )
    }
    val pending = state.pendingImport
    if (pending != null) {
        val count = pending.variables.size
        val skipped = if (pending.skippedLines > 0) " (${pending.skippedLines} lines skipped)" else ""
        val replace = if (pending.overwrites.isEmpty()) {
            "The values are saved encrypted on this phone."
        } else {
            "These names already exist and get the new value: ${pending.overwrites.joinToString(", ")}."
        }
        ConfirmDialog(
            title = "Add $count ${if (count == 1) "variable" else "variables"}?$skipped",
            message = replace,
            confirmLabel = "Add",
            onConfirm = { vm.confirmImport() },
            onDismiss = { vm.cancelImport() },
        )
    }
    val askDelete = state.askDeleteFile
    if (askDelete != null) {
        ConfirmDialog(
            title = "Delete the .env file from this project?",
            message = "$askDelete. The variables are saved in Coder now, and a .env file is never pushed to GitHub.",
            confirmLabel = "Delete file",
            onConfirm = { vm.answerDeleteFile(true) },
            onDismiss = { vm.answerDeleteFile(false) },
            destructive = true,
            dismissLabel = "Keep it",
        )
    }
}

/** One variable: its name, its value as dots or text, and the show, edit and delete buttons. */
@Composable
private fun VariableRow(variable: EnvVar, shown: Boolean, onToggle: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    NeriboCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.lg, top = spacing.xs, bottom = spacing.xs, end = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                Text(text = variable.name, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                Text(
                    text = if (shown) variable.value.ifEmpty { "(empty)" } else DOTS,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 3,
                )
            }
            NeriboIconButton(
                icon = if (shown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                contentDescription = if (shown) "Hide value of ${variable.name}" else "Show value of ${variable.name}",
                onClick = onToggle,
            )
            NeriboIconButton(icon = Icons.Outlined.Edit, contentDescription = "Edit ${variable.name}", onClick = onEdit)
            NeriboIconButton(icon = Icons.Outlined.Delete, contentDescription = "Delete ${variable.name}", onClick = onDelete)
        }
    }
}

/** The two lines to paste into a page, and how built React and Vite projects read the same variables. */
@Composable
private fun SnippetCard(onCopy: () -> Unit) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    NeriboCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            SectionHeader(text = "Use in your page")
            Text(
                text = "$SNIPPET_SCRIPT\n$SNIPPET_READ",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurface,
            )
            Text(
                text = "Built React/Vite projects read these as import.meta.env.VITE_NAME or process.env.REACT_APP_NAME when you build with GitHub.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            NeriboButton(text = "Copy", onClick = onCopy, style = ButtonStyle.Secondary)
        }
    }
}

/** The project's `.env` files, each with a button that reads it. */
@Composable
private fun ImportCard(files: List<String>, onPick: (String) -> Unit) {
    val spacing = NeriboTheme.spacing
    NeriboCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            SectionHeader(text = "Import from a .env file")
            if (files.isEmpty()) {
                Text(
                    text = "This project has no .env file. If you add one, it will be listed here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            for (path in files) {
                NeriboButton(text = "Import $path", onClick = { onPick(path) }, style = ButtonStyle.Secondary)
            }
        }
    }
}

/** The sheet for a new variable or an edit. The value is hidden until "Show value" is tapped. */
@Composable
private fun VariableSheet(
    original: EnvVar?,
    problemWith: (String, String) -> String?,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    var name by remember { mutableStateOf(original?.name.orEmpty()) }
    var value by remember { mutableStateOf(original?.value.orEmpty()) }
    var showValue by remember { mutableStateOf(false) }
    val nameProblem = if (name.isEmpty()) null else CoderEnvRules.validateName(name)
    val valueProblem = CoderEnvRules.validateValue(value)
    val saveProblem = problemWith(name, value)
    val clash = if (nameProblem == null && valueProblem == null) saveProblem else null
    NeriboBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.screen, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(
                text = if (original == null) "Add variable" else "Edit variable",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            NeriboTextField(
                value = name,
                onValueChange = { name = CoderEnvRules.suggestName(it) },
                modifier = Modifier.fillMaxWidth(),
                label = "Name",
                placeholder = "API_URL",
                isError = nameProblem != null || clash != null,
                supportingText = nameProblem ?: clash,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            )
            NeriboTextField(
                value = value,
                onValueChange = { value = it.replace("\n", "") },
                modifier = Modifier.fillMaxWidth(),
                label = "Value",
                isError = valueProblem != null,
                supportingText = valueProblem,
                visualTransformation = if (showValue) VisualTransformation.None else PasswordVisualTransformation(),
            )
            NeriboButton(
                text = if (showValue) "Hide value" else "Show value",
                onClick = { showValue = !showValue },
                style = ButtonStyle.Text,
                leadingIcon = if (showValue) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
            )
            NeriboButton(
                text = "Save",
                onClick = { onSave(name, value) },
                modifier = Modifier.fillMaxWidth(),
                enabled = saveProblem == null,
            )
        }
    }
}
