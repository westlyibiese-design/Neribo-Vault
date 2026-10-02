package com.westly.neribovault.feature.developer.secrets

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.ConfirmDialog
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTextField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.feature.developer.SECRET_CATEGORIES

/**
 * Create or edit a secret. It needs a real, unlocked session: the value is decrypted into
 * memory only while the screen is open, and the screen blocks screenshots meanwhile.
 */
@Composable
fun SecretEditorScreen(
    projectId: String?,
    secretId: String,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val vm = neriboViewModel(key = "secret-edit-$secretId-${projectId ?: "none"}") { c ->
        SecretEditorViewModel(projectId, secretId, c.secretsRepository)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val mode by SecretsVault.mode.collectAsStateWithLifecycle()
    val hasDecoy by SecretsVault.hasDecoyPin.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    var showUnlock by remember { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var valueVisible by remember { mutableStateOf(false) }

    SecureWindowEffect(active = mode == SessionMode.Real)

    // If the session ends while this screen is open, ask for the PIN again right here.
    LaunchedEffect(mode) {
        showUnlock = mode == SessionMode.Locked
        if (mode != SessionMode.Real) valueVisible = false
    }

    val needsConfirm = state.hasChanges && !state.isBlank
    val requestBack: () -> Unit = {
        if (needsConfirm) confirmDiscard = true else onBack()
    }
    BackHandler(enabled = needsConfirm) { confirmDiscard = true }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = if (state.isNew) "New secret" else "Edit secret",
                onBack = requestBack,
                actions = {
                    NeriboButton(
                        text = "Save",
                        onClick = { vm.save(onSaved) },
                        enabled = mode == SessionMode.Real && !state.isSaving &&
                            !state.isLoading && !state.notFound,
                        style = ButtonStyle.Text,
                    )
                },
            )
        },
    ) { padding ->
        when {
            state.notFound -> EmptyState(
                icon = Icons.Outlined.Lock,
                title = "Secret not found",
                message = "It may have been deleted.",
                modifier = Modifier.fillMaxSize().padding(padding),
                actionLabel = "Go back",
                onAction = onBack,
            )
            mode == SessionMode.Locked -> EmptyState(
                icon = Icons.Outlined.Lock,
                title = "Secrets are locked",
                message = "Enter your secrets PIN to continue.",
                modifier = Modifier.fillMaxSize().padding(padding),
                actionLabel = "Unlock",
                onAction = { showUnlock = true },
            )
            mode == SessionMode.Decoy -> EmptyState(
                icon = Icons.Outlined.Lock,
                title = SecretsVault.BLOCKED_MESSAGE,
                message = "Please go back.",
                modifier = Modifier.fillMaxSize().padding(padding),
                actionLabel = "Go back",
                onAction = onBack,
            )
            state.isLoading -> Unit
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.screen, vertical = spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.lg),
            ) {
                NeriboTextField(
                    value = state.label,
                    onValueChange = vm::onLabelChange,
                    label = "Label",
                    placeholder = "Name of the secret",
                    isError = state.labelError,
                    supportingText = if (state.labelError) "Give this a label" else null,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Next,
                    ),
                )
                Column {
                    SectionHeader("CATEGORY")
                    Spacer(modifier = Modifier.height(spacing.xs))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        SECRET_CATEGORIES.forEach { option ->
                            NeriboChip(
                                label = option.label,
                                selected = state.category == option.value,
                                onClick = { vm.onCategoryChange(option.value) },
                            )
                        }
                    }
                }
                SecretSwitchRow(checked = state.isSecret, onCheckedChange = vm::onIsSecretChange)

                val masked = state.isSecret && !valueVisible
                NeriboTextField(
                    value = state.value,
                    onValueChange = vm::onValueChange,
                    label = "Value",
                    placeholder = "Type or paste the value",
                    singleLine = false,
                    maxLines = 6,
                    isError = state.valueError,
                    supportingText = if (state.valueError) "Add the value to keep" else null,
                    visualTransformation = if (masked) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (masked) KeyboardType.Password else KeyboardType.Text,
                    ),
                    trailingIcon = {
                        if (state.isSecret) {
                            NeriboIconButton(
                                icon = if (valueVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (valueVisible) "Hide value" else "Show value",
                                onClick = { valueVisible = !valueVisible },
                            )
                        }
                    },
                )
                if (state.isSecret && hasDecoy) {
                    NeriboTextField(
                        value = state.decoyValue,
                        onValueChange = vm::onDecoyChange,
                        label = "Decoy value",
                        placeholder = "Optional",
                        singleLine = false,
                        maxLines = 6,
                        supportingText = "Leave empty to use an automatic one.",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
                val message = state.message
                if (message != null) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(modifier = Modifier.height(spacing.xl))
            }
        }
    }

    if (showUnlock && mode == SessionMode.Locked) {
        SecretsUnlockSheet(
            onDismiss = { showUnlock = false },
            onUnlocked = { showUnlock = false },
        )
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            message = "What you typed hasn't been saved.",
            confirmLabel = "Discard",
            destructive = true,
            onConfirm = {
                confirmDiscard = false
                onBack()
            },
            onDismiss = { confirmDiscard = false },
            dismissLabel = "Keep editing",
        )
    }
}

@Composable
private fun SecretSwitchRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "This value is secret",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
            )
            Text(
                text = if (checked) {
                    "Encrypted on this phone and hidden until you reveal it."
                } else {
                    "Stored as plain text, like a username or an app id."
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
