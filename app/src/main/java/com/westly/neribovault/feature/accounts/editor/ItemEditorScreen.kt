package com.westly.neribovault.feature.accounts.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.ConfirmDialog
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.LoadingState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTextField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.feature.accounts.detail.SecretAccessSheets
import com.westly.neribovault.feature.accounts.detail.rememberSecretAccess
import com.westly.neribovault.feature.accounts.security.AccountsSecureWindowEffect
import com.westly.neribovault.feature.accounts.security.AccountsSessionMode
import com.westly.neribovault.feature.accounts.security.AccountsVault
import kotlinx.coroutines.launch

private const val OWNER_ITEM = "item"

/**
 * The item form: name, type, link, identifier, status, notes and custom fields. [itemId] is
 * "new" to create an item under [accountId]. Nothing is written until Save; a new form that is
 * still untouched is simply left, never saved.
 */
@Composable
fun ItemEditorScreen(accountId: String, itemId: String, onBack: () -> Unit) {
    val vm = neriboViewModel(key = "accounts-item-$accountId-$itemId") { c ->
        ItemEditorViewModel(
            accountId = accountId,
            itemId = itemId,
            database = c.database,
            accounts = c.accountsRepository,
            items = c.accountItemsRepository,
            fields = c.accountFieldsRepository,
        )
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

    val form = state.form
    val isDirty = state.isDirty || vm.fieldsState.isDirty

    AccountsSecureWindowEffect(active = vm.fieldsState.hasOpenSecret)

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
    val deleteItem: () -> Unit = {
        if (AccountsVault.mode.value == AccountsSessionMode.Decoy) {
            showMessage(AccountsVault.BLOCKED_MESSAGE)
        } else {
            vm.delete(onDone = onBack)
        }
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = if (state.isNew) "Add item" else "Edit item",
                onBack = leave,
                actions = {
                    NeriboButton(
                        text = "Save",
                        onClick = trySave,
                        enabled = !state.isLoading && !state.notFound && !state.isSaving,
                        style = ButtonStyle.Text,
                    )
                    if (!state.isNew && !state.isLoading && !state.notFound) {
                        OverflowMenu(
                            actions = listOf(
                                MenuAction(
                                    label = "Delete",
                                    onClick = deleteItem,
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
                icon = Icons.Outlined.AccountCircle,
                title = "Item not found",
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
                NeriboTextField(
                    value = form.name,
                    onValueChange = { vm.setName(it) },
                    label = "Name",
                    placeholder = itemNamePlaceholder(state.platform),
                    isError = showErrors && form.name.isBlank(),
                    supportingText = if (showErrors && form.name.isBlank()) "Required" else null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )

                Spacer(modifier = Modifier.height(spacing.lg))
                SectionHeader(text = "Type", modifier = Modifier.padding(bottom = spacing.xs))
                ChoiceChips(
                    options = ITEM_TYPE_OPTIONS,
                    selected = form.itemType,
                    onSelect = { vm.setItemType(it) },
                )

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
                NeriboTextField(
                    value = form.identifier,
                    onValueChange = { vm.setIdentifier(it) },
                    label = "Identifier",
                    placeholder = identifierPlaceholder(form.itemType),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                )
                QuietNote(text = PLAIN_TEXT_REMINDER)

                Spacer(modifier = Modifier.height(spacing.lg))
                SectionHeader(text = "Status", modifier = Modifier.padding(bottom = spacing.xs))
                ChoiceChips(
                    options = ITEM_STATUS_OPTIONS,
                    selected = form.status,
                    onSelect = { vm.setStatus(it) },
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

                Spacer(modifier = Modifier.height(spacing.xl))
                FieldsEditor(
                    ownerType = OWNER_ITEM,
                    ownerId = itemId,
                    state = vm.fieldsState,
                    access = access,
                    hasDecoyPin = hasDecoyPin,
                )

                Spacer(modifier = Modifier.height(spacing.lg))
                QuietNote(text = NO_BANK_HINT)
                Spacer(modifier = Modifier.height(spacing.xxl))
            }
        }
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
