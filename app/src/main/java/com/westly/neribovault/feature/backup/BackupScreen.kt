package com.westly.neribovault.feature.backup

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.ConfirmDialog
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTextField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.SectionHeader

/** Create an encrypted backup of everything, or restore one onto this phone. */
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm = neriboViewModel { c -> BackupViewModel(c, context.applicationContext as Application) }
    val state by vm.state.collectAsStateWithLifecycle()
    val phase = state.phase
    val busy = phase is BackupPhase.Working

    // Leaving mid-way could cut a backup short, so back does nothing while work is running.
    BackHandler(enabled = busy) {}

    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? -> if (uri != null) vm.createBackup(uri) }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? -> if (uri != null) vm.onRestoreFilePicked(uri, displayNameOf(context, uri)) }

    NeriboScaffold(
        topBar = { NeriboTopBar(title = "Backup and restore", onBack = if (busy) null else onBack) },
    ) { padding ->
        when (phase) {
            is BackupPhase.Working -> WorkingView(
                message = phase.message,
                modifier = Modifier.padding(padding),
            )
            is BackupPhase.RestoreDone -> RestoreDoneView(
                warnings = phase.warnings,
                onRestart = { findActivity(context)?.recreate() },
                modifier = Modifier.padding(padding),
            )
            else -> FormsView(
                state = state,
                vm = vm,
                onChooseSave = {
                    if (vm.canStartCreate()) createLauncher.launch(vm.suggestedFileName())
                },
                onChooseFile = { openLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (phase is BackupPhase.ConfirmRestore) {
        val lines = phase.lines.joinToString(", ").ifEmpty { "No items" }
        val made = phase.createdText?.let { "Made on $it. " }.orEmpty()
        ConfirmDialog(
            title = "Replace everything on this phone?",
            message = "$made$lines. This replaces everything currently on this phone and cannot be undone.",
            confirmLabel = "Replace everything",
            destructive = true,
            onConfirm = vm::confirmRestore,
            onDismiss = vm::cancelRestore,
        )
    }
}

@Composable
private fun FormsView(
    state: BackupUiState,
    vm: BackupViewModel,
    onChooseSave: () -> Unit,
    onChooseFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    var showPasswords by rememberSaveable { mutableStateOf(false) }
    val transformation: VisualTransformation =
        if (showPasswords) VisualTransformation.None else PasswordVisualTransformation()
    val passwordOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
    val eye: @Composable () -> Unit = {
        NeriboIconButton(
            icon = if (showPasswords) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
            contentDescription = if (showPasswords) "Hide passwords" else "Show passwords",
            onClick = { showPasswords = !showPasswords },
        )
    }
    val done = state.phase as? BackupPhase.BackupDone

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.screen, vertical = spacing.lg),
    ) {
        if (done != null) {
            SuccessCard(
                title = "Backup saved",
                body = "Size ${done.sizeText}. " + done.lines.joinToString(", ") + ".",
                actionLabel = "Done",
                onAction = vm::dismissResult,
            )
            Spacer(modifier = Modifier.height(spacing.xl))
        }

        SectionHeader(text = "Create a backup")
        Spacer(modifier = Modifier.height(spacing.sm))
        NeriboCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                Text(
                    text = "Saves every vault, your memory photos, document files and the Developer " +
                        "secrets setup into one encrypted file. Keep it somewhere safe, such as " +
                        "Google Drive or a computer.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                NeriboTextField(
                    value = state.password,
                    onValueChange = vm::onPassword,
                    label = "Backup password",
                    placeholder = "At least $MIN_PASSWORD_LENGTH characters",
                    keyboardOptions = passwordOptions,
                    visualTransformation = transformation,
                    isError = state.passwordTooShort,
                    supportingText = if (state.passwordTooShort) "Use at least $MIN_PASSWORD_LENGTH characters" else null,
                    trailingIcon = eye,
                )
                NeriboTextField(
                    value = state.confirmPassword,
                    onValueChange = vm::onConfirmPassword,
                    label = "Repeat the password",
                    keyboardOptions = passwordOptions,
                    visualTransformation = transformation,
                    isError = state.passwordsDiffer,
                    supportingText = if (state.passwordsDiffer) "The two passwords do not match" else null,
                )
                Text(
                    text = "This password cannot be recovered. If you forget it, the backup can never " +
                        "be opened, not even by us.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                )
                if (state.error != null) {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.error,
                    )
                }
                NeriboButton(
                    text = "Create backup",
                    onClick = onChooseSave,
                    enabled = state.canCreate,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = Icons.Outlined.Folder,
                )
            }
        }

        Spacer(modifier = Modifier.height(spacing.xl))
        SectionHeader(text = "Restore from a backup")
        Spacer(modifier = Modifier.height(spacing.sm))
        NeriboCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                Text(
                    text = "Choose a .nvbackup file and enter its password. You will see what is inside " +
                        "before anything on this phone is replaced.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                NeriboButton(
                    text = if (state.pickedRestoreName == null) "Choose backup file" else "Choose a different file",
                    onClick = onChooseFile,
                    style = ButtonStyle.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.pickedRestoreName != null) {
                    Text(
                        text = state.pickedRestoreName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurface,
                    )
                    NeriboTextField(
                        value = state.restorePassword,
                        onValueChange = vm::onRestorePassword,
                        label = "Backup password",
                        keyboardOptions = passwordOptions,
                        visualTransformation = transformation,
                        trailingIcon = eye,
                    )
                }
                if (state.restoreError != null) {
                    Text(
                        text = state.restoreError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.error,
                    )
                }
                NeriboButton(
                    text = "Check backup",
                    onClick = vm::checkBackup,
                    enabled = state.pickedRestoreName != null && state.restorePassword.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(modifier = Modifier.height(spacing.xxl))
    }
}

@Composable
private fun SuccessCard(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    NeriboCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = NeriboTheme.extraColors.success,
                )
                Text(text = title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            }
            Text(text = body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            NeriboButton(text = actionLabel, onClick = onAction, style = ButtonStyle.Text)
        }
    }
}

@Composable
private fun WorkingView(message: String, modifier: Modifier = Modifier) {
    val spacing = NeriboTheme.spacing
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.dp,
        )
        Spacer(modifier = Modifier.height(spacing.lg))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(spacing.sm))
        Text(
            text = "Please keep the app open.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RestoreDoneView(
    warnings: List<String>,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = NeriboTheme.extraColors.success,
        )
        Spacer(modifier = Modifier.height(spacing.lg))
        Text(
            text = "Restore complete. Restart the app.",
            style = MaterialTheme.typography.titleLarge,
            color = colors.onSurface,
            textAlign = TextAlign.Center,
        )
        warnings.forEach { warning ->
            Spacer(modifier = Modifier.height(spacing.md))
            Text(
                text = warning,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.error,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(spacing.xl))
        NeriboButton(text = "Restart", onClick = onRestart)
    }
}

/** The display name of the file at [uri], or null when the provider does not give one. */
private fun displayNameOf(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()

private fun findActivity(context: Context): Activity? {
    var current: Context? = context
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
