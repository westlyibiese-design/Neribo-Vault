package com.westly.neribovault.feature.authenticator.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.ConfirmDialog
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.LoadingState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTextField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.feature.authenticator.engine.OtpAlgorithm
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The account form: service, account, secret key (new accounts, or when replacing it), advanced
 * code options and notes. The secret key is never shown for an existing account. Nothing is written
 * until Save, and leaving with unsaved changes asks first.
 */
@Composable
fun TotpEditorScreen(accountId: String, onBack: () -> Unit) {
    val vm = neriboViewModel(key = "authenticator-edit-$accountId") { c ->
        TotpEditorViewModel(accountId, c.totpAccountsRepository)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val form = state.form
    val spacing = NeriboTheme.spacing
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val showMessage: (String) -> Unit = { text ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(text)
        }
    }

    var showErrors by rememberSaveable { mutableStateOf(false) }
    var showDiscard by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var showSecret by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    // The live test code is recomputed at every full second.
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                nowMillis = System.currentTimeMillis()
                delay(1000L - nowMillis % 1000L)
            }
        }
    }

    val leave: () -> Unit = { if (state.isDirty) showDiscard = true else onBack() }
    BackHandler(enabled = state.isDirty) { showDiscard = true }

    val trySave: () -> Unit = {
        showErrors = true
        val problem = vm.validate()
        if (problem != null) {
            showMessage(problem)
        } else {
            vm.save { result ->
                when (result) {
                    is TotpSaveResult.Saved -> onBack()
                    is TotpSaveResult.Failed -> showMessage(result.message)
                }
            }
        }
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = if (state.isNew) "New account" else "Edit account",
                onBack = leave,
                actions = {
                    NeriboButton(
                        text = "Save",
                        onClick = trySave,
                        enabled = !state.isLoading && !state.notFound && !state.isSaving,
                        style = ButtonStyle.Text,
                    )
                    if (!state.isNew && !state.notFound) {
                        OverflowMenu(
                            actions = listOf(
                                MenuAction(
                                    label = "Delete",
                                    onClick = { showDelete = true },
                                    icon = Icons.Outlined.Delete,
                                    destructive = true,
                                ),
                            ),
                        )
                    }
                },
            )
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        when {
            state.isLoading -> LoadingState(modifier = Modifier.fillMaxSize().padding(padding))
            state.notFound -> EmptyState(
                icon = Icons.Outlined.VerifiedUser,
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
                val nameMissing = form.issuer.isBlank() && form.accountName.isBlank()
                NeriboTextField(
                    value = form.issuer,
                    onValueChange = { vm.setIssuer(it) },
                    label = "Service",
                    placeholder = "GitHub",
                    isError = showErrors && nameMissing,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                Spacer(modifier = Modifier.height(spacing.md))
                NeriboTextField(
                    value = form.accountName,
                    onValueChange = { vm.setAccountName(it) },
                    label = "Account",
                    placeholder = "you@example.com",
                    isError = showErrors && nameMissing,
                    supportingText = if (showErrors && nameMissing) "Enter the service or the account" else null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        capitalization = KeyboardCapitalization.None,
                    ),
                )

                // Secret key
                Spacer(modifier = Modifier.height(spacing.lg))
                if (state.isNew) {
                    SecretKeySection(
                        form = form,
                        showSecret = showSecret,
                        showErrors = showErrors,
                        problem = vm.secretProblem(form),
                        testCode = vm.testCode(nowMillis),
                        onToggleShow = { showSecret = !showSecret },
                        onChange = { vm.setSecretText(it) },
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Replace the secret key",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = form.replaceSecret, onCheckedChange = { vm.setReplaceSecret(it) })
                    }
                    if (form.replaceSecret) {
                        Spacer(modifier = Modifier.height(spacing.sm))
                        SecretKeySection(
                            form = form,
                            showSecret = showSecret,
                            showErrors = showErrors,
                            problem = vm.secretProblem(form),
                            testCode = vm.testCode(nowMillis),
                            onToggleShow = { showSecret = !showSecret },
                            onChange = { vm.setSecretText(it) },
                        )
                    }
                }

                // Advanced
                Spacer(modifier = Modifier.height(spacing.lg))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { showAdvanced = !showAdvanced },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionHeader(text = "Advanced", modifier = Modifier.weight(1f))
                    NeriboIconButton(
                        icon = if (showAdvanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (showAdvanced) "Hide advanced options" else "Show advanced options",
                        onClick = { showAdvanced = !showAdvanced },
                    )
                }
                if (showAdvanced) {
                    Text(
                        text = "Leave these as they are unless the service told you otherwise.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(spacing.md))
                    SectionHeader(text = "Algorithm", modifier = Modifier.padding(bottom = spacing.xs))
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        OtpAlgorithm.values().forEach { option ->
                            NeriboChip(
                                label = option.name,
                                selected = form.algorithm == option,
                                onClick = { vm.setAlgorithm(option) },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(spacing.md))
                    SectionHeader(text = "Digits", modifier = Modifier.padding(bottom = spacing.xs))
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        listOf(6, 8).forEach { option ->
                            NeriboChip(
                                label = option.toString(),
                                selected = form.digits == option,
                                onClick = { vm.setDigits(option) },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(spacing.md))
                    val periodBad = vm.periodOrNull(form) == null
                    NeriboTextField(
                        value = form.periodText,
                        onValueChange = { vm.setPeriodText(it) },
                        label = "Time step (seconds)",
                        isError = periodBad,
                        supportingText = if (periodBad) "Use a number from 15 to 120" else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }

                // Notes
                Spacer(modifier = Modifier.height(spacing.lg))
                NeriboTextField(
                    value = form.notes,
                    onValueChange = { vm.setNotes(it) },
                    label = "Notes",
                    singleLine = false,
                    minLines = 3,
                    supportingText = "${form.notes.length}/500",
                )
                Spacer(modifier = Modifier.height(spacing.xxl))
            }
        }
    }

    if (showDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            message = "What you changed on this screen has not been saved.",
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
    if (showDelete) {
        ConfirmDialog(
            title = "Delete this account?",
            message = "It moves to Recently deleted for 30 days, then it is gone for good.",
            confirmLabel = "Delete",
            onConfirm = {
                showDelete = false
                vm.delete { onBack() }
            },
            onDismiss = { showDelete = false },
            destructive = true,
        )
    }
}

/** The masked secret key field with its show/hide toggle, its message and the live test code. */
@Composable
private fun SecretKeySection(
    form: TotpForm,
    showSecret: Boolean,
    showErrors: Boolean,
    problem: String?,
    testCode: String?,
    onToggleShow: () -> Unit,
    onChange: (String) -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val missing = showErrors && form.secretText.isBlank()
    NeriboTextField(
        value = form.secretText,
        onValueChange = onChange,
        label = "Secret key",
        placeholder = "The setup key from the service, for example JBSW Y3DP EHPK 3PXP",
        isError = problem != null || missing,
        supportingText = problem ?: if (missing) "Enter the secret key" else null,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            capitalization = KeyboardCapitalization.None,
            autoCorrect = false,
        ),
        visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            NeriboIconButton(
                icon = if (showSecret) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                contentDescription = if (showSecret) "Hide key" else "Show key",
                onClick = onToggleShow,
            )
        },
    )
    if (testCode != null) {
        Text(
            text = "Test code: $testCode",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.sm),
        )
    }
}
