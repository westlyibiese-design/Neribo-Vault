package com.westly.neribovault.feature.authenticator.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.BuildConfig
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboBottomSheet
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTextField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.util.formatDate
import com.westly.neribovault.feature.authenticator.CheckResult
import java.time.LocalDate
import kotlinx.coroutines.launch

private const val MIN_PASSWORD = 8

/** Makes the password-protected backup file and restores accounts from one. */
@Composable
fun AuthenticatorBackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val spacing = NeriboTheme.spacing
    val vm = neriboViewModel { c -> AuthenticatorBackupViewModel(c.totpAccountsRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var showErrors by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.loadLastExport(context) }

    LaunchedEffect(state.message) {
        val text = state.message ?: return@LaunchedEffect
        vm.messageShown()
        snackbarHostState.currentSnackbarData?.dismiss()
        // Launched on the screen's scope so that clearing the message does not cancel the snackbar.
        scope.launch { snackbarHostState.showSnackbar(text) }
    }

    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            vm.createBackup(context, uri, password)
            password = ""
            confirm = ""
            showErrors = false
            showPassword = false
        }
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onFileChosen(context, uri)
    }

    val menuActions = buildList {
        if (BuildConfig.DEBUG) {
            add(MenuAction(label = "Run backup self-test", onClick = { vm.runSelfTest() }))
        }
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = "Backup and restore",
                onBack = onBack,
                actions = {
                    if (menuActions.isNotEmpty()) OverflowMenu(actions = menuActions)
                },
            )
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screen, vertical = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            NeriboCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    CardTitle("Encrypted backup")
                    Text(
                        text = if (state.lastExportAt > 0L) {
                            "Last backup: ${formatDate(state.lastExportAt)}"
                        } else {
                            "You have not made a backup yet"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Your codes can only be read on this phone. A backup file lets you move them " +
                            "to a new phone or recover them after a reset.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PasswordField(
                        value = password,
                        onValueChange = { password = it },
                        label = "Backup password",
                        visible = showPassword,
                        onToggleVisible = { showPassword = !showPassword },
                        isError = showErrors && password.length < MIN_PASSWORD,
                        supportingText = if (showErrors && password.length < MIN_PASSWORD) {
                            "Use at least 8 characters"
                        } else {
                            null
                        },
                    )
                    PasswordField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = "Confirm password",
                        visible = showPassword,
                        onToggleVisible = { showPassword = !showPassword },
                        isError = showErrors && confirm != password,
                        supportingText = if (showErrors && confirm != password) {
                            "The passwords do not match"
                        } else {
                            null
                        },
                    )
                    WarningNote(
                        "If you forget this password, the backup cannot be opened. There is no way to " +
                            "recover it. Keep the file and the password somewhere safe.",
                    )
                    NeriboButton(
                        text = "Create backup file",
                        onClick = {
                            showErrors = true
                            when {
                                password.length < MIN_PASSWORD || password != confirm -> Unit
                                state.accountCount == 0 -> vm.postMessage("Add an account first")
                                else -> createLauncher.launch("NeriboAuthenticator-${LocalDate.now()}.nvauth")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isBusy,
                    )
                }
            }

            NeriboCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    CardTitle("Restore from a backup file")
                    Text(
                        text = "Add the accounts from a backup file. Accounts already in the vault are skipped; " +
                            "nothing is deleted.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    NeriboButton(
                        text = "Choose backup file",
                        onClick = { openLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isBusy,
                        style = ButtonStyle.Secondary,
                    )
                }
            }
        }
    }

    if (state.askPassword) {
        RestorePasswordDialog(
            error = state.passwordError,
            isBusy = state.isBusy,
            onOpen = { vm.submitPassword(it) },
            onCancel = { vm.cancelPassword() },
        )
    }

    val preview = state.preview
    if (preview != null) {
        RestorePreviewSheet(
            preview = preview,
            checked = state.checked,
            isBusy = state.isBusy,
            onToggle = { vm.toggle(it) },
            onToggleAll = { vm.toggleAll() },
            onCancel = { vm.cancelPreview() },
            onAdd = { vm.saveChecked() },
        )
    }

    val selfTest = state.selfTest
    if (selfTest != null) {
        SelfTestDialog(results = selfTest, onDismiss = { vm.dismissSelfTest() })
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** A masked password field with a show/hide button. */
@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    visible: Boolean,
    onToggleVisible: () -> Unit,
    isError: Boolean,
    supportingText: String?,
    modifier: Modifier = Modifier,
) {
    NeriboTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = label,
        isError = isError,
        supportingText = supportingText,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            NeriboIconButton(
                icon = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                contentDescription = if (visible) "Hide password" else "Show password",
                onClick = onToggleVisible,
            )
        },
    )
}

/** The warning about forgetting the password. */
@Composable
private fun WarningNote(text: String) {
    val spacing = NeriboTheme.spacing
    NeriboCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Outlined.Warning,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = NeriboTheme.extraColors.warning,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = spacing.sm),
            )
        }
    }
}

/** Asks for the password of the chosen file. Stays open after a wrong password so the owner can retry. */
@Composable
private fun RestorePasswordDialog(
    error: String?,
    isBusy: Boolean,
    onOpen: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!isBusy) onCancel() },
        confirmButton = {
            TextButton(onClick = { onOpen(password) }, enabled = password.isNotEmpty() && !isBusy) {
                Text(text = "Open", style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !isBusy) {
                Text(text = "Cancel", style = MaterialTheme.typography.labelLarge)
            }
        },
        title = { Text(text = "Backup password", style = MaterialTheme.typography.titleLarge) },
        text = {
            PasswordField(
                value = password,
                onValueChange = { password = it },
                label = "Backup password",
                visible = visible,
                onToggleVisible = { visible = !visible },
                isError = error != null,
                supportingText = error,
            )
        },
        shape = MaterialTheme.shapes.large,
        containerColor = colors.surface,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceVariant,
    )
}

/** Lists every account of the file so the owner can choose what to add. Secret keys are never drawn. */
@Composable
private fun RestorePreviewSheet(
    preview: RestorePreview,
    checked: Set<Int>,
    isBusy: Boolean,
    onToggle: (Int) -> Unit,
    onToggleAll: () -> Unit,
    onCancel: () -> Unit,
    onAdd: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val count = preview.rows.size
    val selectable = preview.rows.indices.filter { !preview.rows[it].isDuplicate }
    val allChecked = selectable.isNotEmpty() && checked.containsAll(selectable)
    val checkedCount = checked.count { it in selectable }

    NeriboBottomSheet(onDismiss = onCancel) {
        Text(
            text = if (count == 1) "Add 1 account" else "Add $count accounts",
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif),
            color = colors.onSurface,
            modifier = Modifier.padding(bottom = spacing.sm),
        )
        if (selectable.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onToggleAll),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = allChecked,
                    onCheckedChange = null,
                    colors = CheckboxDefaults.colors(checkedColor = colors.primary),
                )
                Text(
                    text = "Select all",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurface,
                    modifier = Modifier.padding(start = spacing.md),
                )
            }
            NeriboDivider()
        }
        LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
            itemsIndexed(preview.rows) { index, row ->
                val enabled = !row.isDuplicate
                val account = row.account
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable(enabled = enabled) { onToggle(index) }
                        .padding(vertical = spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = enabled && index in checked,
                        onCheckedChange = null,
                        enabled = enabled,
                        colors = CheckboxDefaults.colors(checkedColor = colors.primary),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = spacing.md),
                    ) {
                        Text(
                            text = rowTitle(account),
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                            color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (account.issuer.isNotBlank() && account.accountName.isNotBlank()) {
                            Text(
                                text = account.accountName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val details = detailLine(account)
                        if (details != null) {
                            Text(
                                text = details,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                    if (row.isDuplicate) {
                        Text(
                            text = "Already added",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(start = spacing.sm),
                        )
                    }
                }
            }
        }
        if (preview.skipped > 0) {
            val n = preview.skipped
            Text(
                text = if (n == 1) {
                    "1 account in the file was not valid and was left out."
                } else {
                    "$n accounts in the file were not valid and were left out."
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.sm),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            NeriboButton(
                text = "Cancel",
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                style = ButtonStyle.Secondary,
            )
            NeriboButton(
                text = if (checkedCount == 1) "Add 1 account" else "Add $checkedCount accounts",
                onClick = onAdd,
                modifier = Modifier.weight(1f),
                enabled = checkedCount > 0 && !isBusy,
            )
        }
    }
}

private fun rowTitle(account: BackupAccount): String = when {
    account.issuer.isNotBlank() -> account.issuer
    account.accountName.isNotBlank() -> account.accountName
    else -> "Unnamed account"
}

/** A quiet line with only the settings that are not the defaults, or null when all are default. */
private fun detailLine(account: BackupAccount): String? {
    val parts = ArrayList<String>()
    if (account.digits != 6) parts.add("${account.digits} digits")
    if (account.algorithm != "SHA1") parts.add(account.algorithm)
    if (account.periodSeconds != 30) parts.add("${account.periodSeconds} s")
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}

/** Debug only: one line per backup check, then a summary such as "7 of 7 passed". */
@Composable
private fun SelfTestDialog(results: List<CheckResult>, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val passed = results.count { it.passed }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Done", style = MaterialTheme.typography.labelLarge, color = colors.primary)
            }
        },
        title = { Text(text = "Backup self-test", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Text(
                    text = "$passed of ${results.size} passed",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface,
                )
                for (result in results) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = if (result.passed) Icons.Outlined.Check else Icons.Outlined.Close,
                            contentDescription = if (result.passed) "Passed" else "Failed",
                            modifier = Modifier.size(18.dp),
                            tint = if (result.passed) NeriboTheme.extraColors.success else colors.error,
                        )
                        Column(modifier = Modifier.padding(start = spacing.sm)) {
                            Text(text = result.name, style = MaterialTheme.typography.bodySmall, color = colors.onSurface)
                            if (!result.passed) {
                                Text(text = result.detail, style = MaterialTheme.typography.bodySmall, color = colors.error)
                            }
                        }
                    }
                }
            }
        },
        shape = MaterialTheme.shapes.large,
        containerColor = colors.surface,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceVariant,
    )
}
