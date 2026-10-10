package com.westly.neribovault.feature.accounts.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTextField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.feature.accounts.PlatformPresets
import com.westly.neribovault.feature.accounts.SIGN_IN_OTHER
import com.westly.neribovault.feature.accounts.SIGN_IN_PHONE
import com.westly.neribovault.feature.accounts.components.NewAccountSheet
import com.westly.neribovault.feature.accounts.components.PlatformAvatar
import com.westly.neribovault.feature.accounts.detail.SECRET_MASK
import com.westly.neribovault.feature.accounts.detail.SecretAccessSheets
import com.westly.neribovault.feature.accounts.detail.rememberSecretAccess
import com.westly.neribovault.feature.accounts.security.AccountsSecureWindowEffect
import com.westly.neribovault.feature.accounts.security.AccountsSessionMode
import com.westly.neribovault.feature.accounts.security.AccountsVault
import com.westly.neribovault.feature.accounts.signInMethodUsesPassword
import kotlinx.coroutines.launch

private const val OWNER_ACCOUNT = "account"

/**
 * The account form: platform, sign-in, login, password (encrypted), link, two-factor, recovery,
 * status, tags, notes and custom fields. Nothing is written until Save. Leaving with unsaved
 * changes asks first.
 */
@Composable
fun AccountEditorScreen(accountId: String, onBack: () -> Unit) {
    val vm = neriboViewModel(key = "accounts-edit-$accountId") { c ->
        AccountEditorViewModel(accountId, c.database, c.accountsRepository, c.accountFieldsRepository)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val hasDecoyPin by AccountsVault.hasDecoyPin.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val showMessage: (String) -> Unit = { text ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(text)
        }
    }
    val access = rememberSecretAccess(onMessage = showMessage)

    var showErrors by rememberSaveable { mutableStateOf(false) }
    var showDiscard by rememberSaveable { mutableStateOf(false) }
    var showPlatformSheet by rememberSaveable { mutableStateOf(false) }
    var showGenerator by rememberSaveable { mutableStateOf(false) }
    var showAddTag by rememberSaveable { mutableStateOf(false) }
    var showOtherDialog by rememberSaveable { mutableStateOf(false) }
    var confirmRemovePassword by rememberSaveable { mutableStateOf(false) }
    var showNewPassword by rememberSaveable { mutableStateOf(false) }

    val form = state.form
    val isDirty = state.isDirty || vm.fieldsState.isDirty

    // No screenshots while a secret can be on screen in this form.
    AccountsSecureWindowEffect(active = form.changingPassword || vm.fieldsState.hasOpenSecret)

    val leave: () -> Unit = { if (isDirty) showDiscard = true else onBack() }
    BackHandler(enabled = isDirty) { showDiscard = true }

    val doSave: () -> Unit = {
        vm.save { result ->
            when (result) {
                is EditorSaveResult.Saved -> onBack()
                is EditorSaveResult.Failed -> showMessage(result.message)
            }
        }
    }
    val trySave: () -> Unit = {
        showErrors = true
        val problem = vm.validate()
        when {
            problem != null -> showMessage(problem)
            AccountsVault.mode.value == AccountsSessionMode.Decoy -> showMessage(AccountsVault.BLOCKED_MESSAGE)
            vm.needsSession() -> access.run(true) { doSave() }
            else -> doSave()
        }
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = "Edit account",
                onBack = leave,
                actions = {
                    NeriboButton(
                        text = "Save",
                        onClick = trySave,
                        enabled = !state.isLoading && !state.notFound && !state.isSaving,
                        style = ButtonStyle.Text,
                    )
                },
            )
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        when {
            state.isLoading -> LoadingState(modifier = Modifier.fillMaxSize().padding(padding))
            state.notFound -> EmptyState(
                icon = Icons.Outlined.AccountCircle,
                title = "Account not found",
                message = "It may have been deleted.",
                modifier = Modifier.fillMaxSize().padding(padding),
                actionLabel = "Back",
                onAction = onBack,
            )
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = spacing.screen, vertical = spacing.sm),
            ) {
                // 1. Platform
                SectionHeader(text = "Platform", modifier = Modifier.padding(bottom = spacing.xs))
                val isCustomPlatform = form.platformId == PlatformPresets.CUSTOM_ID
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlatformAvatar(
                        platform = if (isCustomPlatform) form.customName else form.platformId,
                        size = 40.dp,
                    )
                    Text(
                        text = if (isCustomPlatform) {
                            "Other platform"
                        } else {
                            PlatformPresets.displayName(form.platformId)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).padding(start = spacing.md),
                    )
                    NeriboButton(
                        text = "Change",
                        onClick = { showPlatformSheet = true },
                        style = ButtonStyle.Text,
                    )
                }
                if (isCustomPlatform) {
                    Spacer(modifier = Modifier.height(spacing.sm))
                    NeriboTextField(
                        value = form.customName,
                        onValueChange = { vm.setCustomName(it) },
                        label = "Platform name",
                        placeholder = "My bank portal",
                        isError = showErrors && form.customName.isBlank(),
                        supportingText = if (showErrors && form.customName.isBlank()) "Required" else null,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    )
                }

                // 2. Name
                Spacer(modifier = Modifier.height(spacing.lg))
                NeriboTextField(
                    value = form.name,
                    onValueChange = { vm.setName(it) },
                    label = "Name",
                    placeholder = "Westly main",
                    isError = showErrors && form.name.isBlank(),
                    supportingText = if (showErrors && form.name.isBlank()) "Required" else null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )

                // 3. Sign-in method
                Spacer(modifier = Modifier.height(spacing.lg))
                SectionHeader(text = "Sign-in method", modifier = Modifier.padding(bottom = spacing.xs))
                ChoiceChips(
                    options = signInOptions(form.signInOtherName),
                    selected = form.signInMethod,
                    onSelect = { code ->
                        // "Other" asks for the app name first; nothing changes until it is confirmed.
                        if (code == SIGN_IN_OTHER) showOtherDialog = true else vm.setSignInMethod(code)
                    },
                )

                // 4. Login
                Spacer(modifier = Modifier.height(spacing.lg))
                NeriboTextField(
                    value = form.loginId,
                    onValueChange = { vm.setLogin(it) },
                    label = loginLabel(form.signInMethod, form.signInOtherName),
                    placeholder = loginPlaceholder(form.signInMethod),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (form.signInMethod == SIGN_IN_PHONE) {
                            KeyboardType.Phone
                        } else {
                            KeyboardType.Email
                        },
                        capitalization = KeyboardCapitalization.None,
                    ),
                )
                QuietNote(text = PLAIN_TEXT_REMINDER)

                // 5. Password
                if (signInMethodUsesPassword(form.signInMethod)) {
                    Spacer(modifier = Modifier.height(spacing.lg))
                    SectionHeader(text = "Password", modifier = Modifier.padding(bottom = spacing.xs))
                    if (form.changingPassword) {
                        NeriboTextField(
                            value = form.newPassword,
                            onValueChange = { vm.setNewPassword(it) },
                            label = "New password",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = if (showNewPassword) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                NeriboIconButton(
                                    icon = if (showNewPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = if (showNewPassword) "Hide password" else "Show password",
                                    onClick = { showNewPassword = !showNewPassword },
                                )
                            },
                        )
                        Spacer(modifier = Modifier.height(spacing.sm))
                        PasswordStrengthBar(password = form.newPassword)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = spacing.sm),
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        ) {
                            NeriboButton(
                                text = "Generate",
                                onClick = { showGenerator = true },
                                style = ButtonStyle.Secondary,
                            )
                            NeriboButton(
                                text = "Cancel",
                                onClick = {
                                    showNewPassword = false
                                    vm.cancelChangingPassword()
                                },
                                style = ButtonStyle.Text,
                            )
                        }
                    } else {
                        Text(
                            text = if (state.passwordKept) SECRET_MASK else "No password stored",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (state.passwordKept) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(vertical = spacing.sm),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            NeriboButton(
                                text = "Change password",
                                onClick = { access.run(true) { vm.startChangingPassword() } },
                                style = ButtonStyle.Secondary,
                            )
                            if (state.passwordKept) {
                                NeriboButton(
                                    text = "Remove password",
                                    onClick = { confirmRemovePassword = true },
                                    style = ButtonStyle.Text,
                                )
                            }
                        }
                    }
                } else if (state.passwordKept) {
                    QuietNote(text = "A password is still stored for this account.")
                }

                // 6. Second-PIN text
                if (signInMethodUsesPassword(form.signInMethod) && hasDecoyPin &&
                    (state.passwordKept || form.changingPassword)
                ) {
                    Spacer(modifier = Modifier.height(spacing.md))
                    NeriboTextField(
                        value = form.decoyText,
                        onValueChange = { vm.setDecoyText(it) },
                        label = "Text to show instead of the password",
                    )
                }

                // 7. Link, two-factor, recovery, status, tags, notes
                Spacer(modifier = Modifier.height(spacing.lg))
                val linkInvalid = !isValidLink(form.url)
                NeriboTextField(
                    value = form.url,
                    onValueChange = { vm.setUrl(it) },
                    label = "Link",
                    placeholder = "https://",
                    isError = linkInvalid,
                    supportingText = if (linkInvalid) LINK_ERROR_TEXT else null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        capitalization = KeyboardCapitalization.None,
                    ),
                )

                Spacer(modifier = Modifier.height(spacing.lg))
                SectionHeader(text = "Two-factor", modifier = Modifier.padding(bottom = spacing.xs))
                ChoiceChips(
                    options = TWO_FACTOR_OPTIONS,
                    selected = form.twoFactor,
                    onSelect = { vm.setTwoFactor(it) },
                )

                Spacer(modifier = Modifier.height(spacing.lg))
                NeriboTextField(
                    value = form.recovery,
                    onValueChange = { vm.setRecovery(it) },
                    label = "Recovery",
                    placeholder = "Recovery email, phone or notes",
                    singleLine = false,
                    minLines = 2,
                    maxLines = 6,
                    supportingText = "Stored as plain text on this phone",
                )

                Spacer(modifier = Modifier.height(spacing.lg))
                SectionHeader(text = "Status", modifier = Modifier.padding(bottom = spacing.xs))
                ChoiceChips(
                    options = ACCOUNT_STATUS_OPTIONS,
                    selected = form.status,
                    onSelect = { vm.setStatus(it) },
                )

                Spacer(modifier = Modifier.height(spacing.lg))
                SectionHeader(text = "Tags", modifier = Modifier.padding(bottom = spacing.xs))
                TagRow(
                    tags = form.tags,
                    onRemove = { vm.removeTag(it) },
                    onAdd = { showAddTag = true },
                )

                Spacer(modifier = Modifier.height(spacing.lg))
                NeriboTextField(
                    value = form.notes,
                    onValueChange = { vm.setNotes(it) },
                    label = "Notes",
                    singleLine = false,
                    minLines = 3,
                    maxLines = 10,
                    supportingText = "Stored as plain text on this phone",
                )

                // 8. Custom fields
                Spacer(modifier = Modifier.height(spacing.xl))
                FieldsEditor(
                    ownerType = OWNER_ACCOUNT,
                    ownerId = accountId,
                    state = vm.fieldsState,
                    access = access,
                    hasDecoyPin = hasDecoyPin,
                )

                // 9. Hint
                Spacer(modifier = Modifier.height(spacing.lg))
                QuietNote(text = NO_BANK_HINT)
                Spacer(modifier = Modifier.height(spacing.xxl))
            }
        }
    }

    if (showPlatformSheet) {
        NewAccountSheet(
            onDismiss = { showPlatformSheet = false },
            onCreate = { preset, platformName, _ ->
                vm.setPlatform(preset, platformName)
                showPlatformSheet = false
            },
        )
    }
    if (showGenerator) {
        PasswordGeneratorDialog(
            onDismiss = { showGenerator = false },
            onUse = { generated ->
                vm.setNewPassword(generated)
                showNewPassword = true
                showGenerator = false
            },
        )
    }
    if (showAddTag) {
        AddTagDialog(
            onDismiss = { showAddTag = false },
            onAdd = { vm.addTag(it) },
        )
    }
    if (showOtherDialog) {
        SignInOtherDialog(
            initialName = form.signInOtherName.ifEmpty { state.initial.signInOtherName },
            onDismiss = { showOtherDialog = false },
            onConfirm = { name ->
                vm.setSignInOther(name)
                showOtherDialog = false
            },
        )
    }
    if (confirmRemovePassword) {
        ConfirmDialog(
            title = "Remove the password?",
            message = "The stored password is deleted when you save. This can't be undone.",
            confirmLabel = "Remove",
            onConfirm = {
                confirmRemovePassword = false
                access.run(true) { vm.removePassword() }
            },
            onDismiss = { confirmRemovePassword = false },
            destructive = true,
        )
    }
    if (showDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            message = "What you changed on this form won't be saved.",
            confirmLabel = "Discard",
            onConfirm = {
                showDiscard = false
                onBack()
            },
            onDismiss = { showDiscard = false },
            destructive = true,
            dismissLabel = "Keep editing",
        )
    }
    SecretAccessSheets(access = access)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagRow(
    tags: List<String>,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        for (tag in tags) {
            NeriboChip(label = "$tag ×", selected = false, onClick = { onRemove(tag) })
        }
        NeriboButton(
            text = "Add tag",
            onClick = onAdd,
            style = ButtonStyle.Text,
            leadingIcon = Icons.Outlined.Add,
            enabled = tags.size < MAX_TAGS,
        )
    }
}

@Composable
private fun AddTagDialog(
    onDismiss: () -> Unit,
    onAdd: (String) -> Boolean,
) {
    val colors = MaterialTheme.colorScheme
    var text by rememberSaveable { mutableStateOf("") }
    var failed by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        containerColor = colors.surface,
        title = {
            Text(
                text = "Add a tag",
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
            )
        },
        text = {
            NeriboTextField(
                value = text,
                onValueChange = {
                    text = it.take(MAX_TAG_LENGTH)
                    failed = false
                },
                label = "Tag",
                placeholder = "work",
                isError = failed,
                supportingText = if (failed) "Already added, or you have 10 tags" else "Lower case, up to 24 characters",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (onAdd(text)) onDismiss() else failed = true
                },
            ) {
                Text(
                    text = "Add",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Cancel",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun SignInOtherDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var text by rememberSaveable { mutableStateOf(initialName) }
    val canSave = text.trim().isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        containerColor = colors.surface,
        title = {
            Text(
                text = "Which app or service do you sign in with?",
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
            )
        },
        text = {
            NeriboTextField(
                value = text,
                onValueChange = { text = it.take(MAX_SIGN_IN_OTHER_LENGTH) },
                label = "App or service",
                placeholder = "Telegram, Discord, Facebook",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = canSave) {
                Text(
                    text = "Save",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (canSave) colors.primary else colors.onSurfaceVariant,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Cancel",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                )
            }
        },
    )
}
