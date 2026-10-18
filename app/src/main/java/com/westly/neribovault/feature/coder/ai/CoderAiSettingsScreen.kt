package com.westly.neribovault.feature.coder.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.BadgeTone
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.ConfirmDialog
import com.westly.neribovault.core.ui.components.LoadingState
import com.westly.neribovault.core.ui.components.NeriboBottomSheet
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboSearchField
import com.westly.neribovault.core.ui.components.NeriboTextField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.StatusBadge
import com.westly.neribovault.feature.coder.guidance.WhatIsThis

/** The most characters the key field accepts; a real key is far shorter, the rest allows for pasted spaces. */
private const val MAX_TYPED_KEY = 2000

/**
 * AI settings: one card per provider with Add or Replace key, Choose model, Use this one and Remove key.
 * No key is ever shown, copied or logged; the typed key lives in plain `remember`, so it is never saved
 * with the screen's state.
 */
@Composable
fun CoderAiSettingsScreen(onBack: () -> Unit) {
    val vm = neriboViewModel { c -> CoderAiSettingsViewModel(c.coderAiSettings) }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val snackbarHostState = remember { SnackbarHostState() }
    var removeId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(state.message) {
        val text = state.message ?: return@LaunchedEffect
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(text)
        vm.consumeMessage()
    }

    NeriboScaffold(
        topBar = { NeriboTopBar(title = "AI settings", onBack = onBack) },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        if (state.isLoading) {
            LoadingState(modifier = Modifier.padding(padding))
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.screen, vertical = spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                NeriboCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "AI is optional. Bring your own account: you pay your provider directly, " +
                            "and your key stays on this phone, encrypted. Code you ask the AI about is " +
                            "sent to the provider you choose.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(spacing.lg),
                    )
                }
                val hint = when {
                    !state.hasAnyKey -> "No AI connected yet. The editor works fine without it."
                    !state.hasActive -> "No provider is active. Choose one with \"Use this one\"."
                    else -> null
                }
                if (hint != null) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                for (item in state.providers) {
                    ProviderCard(
                        item = item,
                        onAddOrReplace = { vm.openKeySheet(item.provider) },
                        onChooseModel = { vm.openModelChooser(item.provider) },
                        onUse = { vm.useProvider(item.provider) },
                        onRemove = { removeId = item.provider.id },
                    )
                }
            }
        }
    }

    val keyProvider = state.keySheetFor
    if (keyProvider != null) {
        val replacing = remember(keyProvider) {
            state.providers.firstOrNull { it.provider == keyProvider }?.hasKey == true
        }
        KeySheet(
            provider = keyProvider,
            replacing = replacing,
            isTesting = state.isTesting,
            error = state.keyError,
            onSave = { raw -> vm.saveAndTest(keyProvider, raw) },
            onDismiss = { vm.closeKeySheet() },
        )
    }

    val sheet = state.modelSheet
    if (sheet != null) {
        val selected = state.providers.firstOrNull { it.provider == sheet.provider }?.model
        ModelSheet(
            sheet = sheet,
            selected = selected,
            onPick = { name -> vm.chooseModel(sheet.provider, name) },
            onTypedNameChange = { vm.clearModelInputError() },
            onDismiss = { vm.closeModelChooser() },
        )
    }

    val removing = CoderAiProvider.fromId(removeId)
    if (removing != null) {
        ConfirmDialog(
            title = "Remove key?",
            message = "The " + removing.displayName + " key will be deleted from this phone. You can add it again later.",
            confirmLabel = "Remove key",
            onConfirm = {
                vm.removeKey(removing)
                removeId = null
            },
            onDismiss = { removeId = null },
            destructive = true,
        )
    }
}

@Composable
private fun ProviderCard(
    item: CoderProviderUiState,
    onAddOrReplace: () -> Unit,
    onChooseModel: () -> Unit,
    onUse: () -> Unit,
    onRemove: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val status = if (!item.hasKey) {
        "No key yet"
    } else if (item.last4.isNullOrEmpty()) {
        "Key saved"
    } else {
        "Key saved ending in " + item.last4
    }
    NeriboCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.provider.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface,
                    )
                    Text(
                        text = item.provider.company,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                if (item.isActive) {
                    StatusBadge(text = "Active", tone = BadgeTone.Accent)
                }
            }
            Text(text = status, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            if (item.hasKey) {
                Text(
                    text = if (item.model.isNullOrEmpty()) "No model chosen yet" else "Model: " + item.model,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                NeriboButton(
                    text = if (item.hasKey) "Replace key" else "Add key",
                    onClick = onAddOrReplace,
                    modifier = Modifier.weight(1f),
                    style = ButtonStyle.Secondary,
                )
                if (item.hasKey) {
                    NeriboButton(
                        text = "Choose model",
                        onClick = onChooseModel,
                        modifier = Modifier.weight(1f),
                        style = ButtonStyle.Secondary,
                    )
                }
            }
            if (item.hasKey) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    if (!item.isActive) {
                        NeriboButton(
                            text = "Use this one",
                            onClick = onUse,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    NeriboButton(
                        text = "Remove key",
                        onClick = onRemove,
                        modifier = Modifier.weight(1f),
                        style = ButtonStyle.Destructive,
                    )
                }
            }
        }
    }
}

/** The sheet that takes a key. The field is emptied the moment the key is handed on, and is never saved with the screen's state. */
@Composable
private fun KeySheet(
    provider: CoderAiProvider,
    replacing: Boolean,
    isTesting: Boolean,
    error: String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var key by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    NeriboBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(
                text = (if (replacing) "Replace " else "Add ") + provider.displayName + " key",
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
            )
            Text(
                text = "Make a key on the " + provider.company + " website, then paste it here. " +
                    "It is not shown again after you save it.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            Text(
                text = provider.keysPageUrl,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
            )
            NeriboButton(
                text = "Copy link",
                onClick = { clipboard.setText(AnnotatedString(provider.keysPageUrl)) },
                style = ButtonStyle.Secondary,
                leadingIcon = Icons.Outlined.Link,
            )
            WhatIsThis(termId = "api-key")
            NeriboTextField(
                value = key,
                onValueChange = { key = it.take(MAX_TYPED_KEY) },
                label = "API key",
                isError = error != null,
                supportingText = error,
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
                        contentDescription = if (visible) "Hide key" else "Show key",
                        onClick = { visible = !visible },
                    )
                },
            )
            NeriboButton(
                text = "Paste",
                onClick = {
                    val pasted = clipboard.getText()?.text
                    if (!pasted.isNullOrEmpty()) key = pasted.take(MAX_TYPED_KEY)
                },
                style = ButtonStyle.Secondary,
                leadingIcon = Icons.Outlined.ContentPaste,
            )
            if (isTesting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = colors.primary,
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = "Testing your key\u2026",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = spacing.md),
                    )
                }
            }
            NeriboButton(
                text = "Save and test",
                onClick = {
                    val typed = key
                    key = ""
                    onSave(typed)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isTesting && key.isNotBlank(),
            )
        }
    }
}

/** The sheet for choosing a model: the loaded list with a search box, and a field to type a name. */
@Composable
private fun ModelSheet(
    sheet: CoderModelSheetState,
    selected: String?,
    onPick: (String) -> Unit,
    onTypedNameChange: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    var typed by rememberSaveable { mutableStateOf("") }
    val shown = remember(sheet.models, query) {
        val needle = query.trim()
        if (needle.isEmpty()) sheet.models else sheet.models.filter { it.contains(needle, ignoreCase = true) }
    }

    NeriboBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(
                text = "Model for " + sheet.provider.displayName,
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
            )
            if (sheet.isLoading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = colors.primary,
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = "Loading models\u2026",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = spacing.md),
                    )
                }
            } else {
                if (sheet.notice != null) {
                    Text(text = sheet.notice, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                }
                if (sheet.failure != null) {
                    Text(text = sheet.failure, style = MaterialTheme.typography.bodyMedium, color = colors.error)
                }
                if (sheet.models.isNotEmpty()) {
                    NeriboSearchField(
                        query = query,
                        onQueryChange = { query = it },
                        placeholder = "Search models",
                    )
                    if (shown.isEmpty()) {
                        Text(
                            text = "No model matches that search.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                            items(shown, key = { it }) { id ->
                                ModelRow(id = id, isSelected = id == selected, onClick = { onPick(id) })
                            }
                        }
                    }
                }
                NeriboTextField(
                    value = typed,
                    onValueChange = {
                        typed = it.take(120)
                        onTypedNameChange()
                    },
                    label = "Or type a model name",
                    isError = sheet.inputError != null,
                    supportingText = sheet.inputError,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrect = false,
                        imeAction = ImeAction.Done,
                    ),
                )
                NeriboButton(
                    text = "Use this name",
                    onClick = { onPick(typed) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = typed.isNotBlank(),
                )
            }
        }
    }
}

@Composable
private fun ModelRow(id: String, isSelected: Boolean, onClick: () -> Unit) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = id,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (isSelected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = "Selected",
                modifier = Modifier.size(20.dp),
                tint = colors.primary,
            )
        }
    }
}
