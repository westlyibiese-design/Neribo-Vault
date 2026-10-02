package com.westly.neribovault.feature.documents

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.lock.VaultLockSettingsSheet
import com.westly.neribovault.core.lock.rememberVaultLockEnabled
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboBottomSheet
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboFab
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboSearchField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.feature.documents.components.DocumentCard
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val VAULT_ID = "documents"
private const val VAULT_NAME = "Documents"

/**
 * The Documents list: an attention summary when something is expired or close to it, search,
 * filter chips, and the documents grouped by urgency with the Undo snackbar for deletes
 * (including deletes made elsewhere).
 */
@Composable
fun DocumentsScreen(
    deletedIdFlow: StateFlow<String?>,
    onDeletedIdConsumed: () -> Unit,
    onBack: () -> Unit,
    onOpenDocument: (String) -> Unit,
    onEditDocument: (String) -> Unit,
    onNewDocument: () -> Unit,
    onOpenViewer: (String) -> Unit,
    onOpenTrash: () -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val vm = neriboViewModel { c -> DocumentsViewModel(appContext, c.personalDocumentsRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val lockEnabled by rememberVaultLockEnabled(VAULT_ID)
    val spacing = NeriboTheme.spacing
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val searchFocus = remember { FocusRequester() }
    // Set only by the user tapping the search icon, so coming back to this screen never
    // pops the keyboard open on its own.
    var focusSearchOnOpen by rememberSaveable { mutableStateOf(false) }
    var showLockSheet by rememberSaveable { mutableStateOf(false) }
    var showAddSheet by rememberSaveable { mutableStateOf(false) }

    // The system picker needs no storage permission. The file is copied into the app straight away.
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? -> if (uri != null) vm.addFile(uri) },
    )

    // The text field edits this local copy so typing is never delayed by the database.
    var localQuery by rememberSaveable { mutableStateOf(state.query) }

    val showUndo: suspend (String, () -> Unit) -> Unit = { message, onUndo ->
        snackbarHostState.currentSnackbarData?.dismiss()
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) onUndo()
    }

    LaunchedEffect(deletedIdFlow) {
        deletedIdFlow.collect { id ->
            if (id != null) {
                onDeletedIdConsumed()
                showUndo("Moved to Recently deleted", { vm.restore(id) })
            }
        }
    }

    // A file that was just saved opens in the viewer exactly once, even after a rotation.
    LaunchedEffect(state.openViewerId) {
        val id = state.openViewerId
        if (id != null) {
            vm.onViewerOpened(id)
            onOpenViewer(id)
        }
    }

    // Held in the state so a message survives rotation; cleared only after it has been shown.
    LaunchedEffect(state.message) {
        val message = state.message
        if (message != null) {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
            vm.onMessageShown(message)
        }
    }

    LaunchedEffect(state.isSearchOpen, focusSearchOnOpen) {
        if (state.isSearchOpen && focusSearchOnOpen) {
            focusSearchOnOpen = false
            runCatching { searchFocus.requestFocus() }
        }
    }

    BackHandler(enabled = state.isSearchOpen) {
        localQuery = ""
        vm.closeSearch()
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = "Documents",
                onBack = onBack,
                actions = {
                    if (lockEnabled) {
                        NeriboIconButton(
                            icon = Icons.Outlined.Lock,
                            contentDescription = "Documents lock is on",
                            onClick = { showLockSheet = true },
                        )
                    }
                    NeriboIconButton(
                        icon = if (state.isSearchOpen) Icons.Outlined.Close else Icons.Outlined.Search,
                        contentDescription = if (state.isSearchOpen) "Close search" else "Search documents",
                        onClick = {
                            if (state.isSearchOpen) {
                                localQuery = ""
                                vm.closeSearch()
                            } else {
                                focusSearchOnOpen = true
                                vm.openSearch()
                            }
                        },
                    )
                    OverflowMenu(
                        actions = listOf(
                            MenuAction(
                                label = "Documents lock",
                                onClick = { showLockSheet = true },
                                icon = Icons.Outlined.Lock,
                            ),
                            MenuAction(
                                label = "Recently deleted",
                                onClick = onOpenTrash,
                                icon = Icons.Outlined.History,
                            ),
                        ),
                    )
                },
            )
        },
        floatingActionButton = {
            NeriboFab(onClick = { showAddSheet = true }, contentDescription = "Add to Documents")
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.isSearchOpen) {
                NeriboSearchField(
                    query = localQuery,
                    onQueryChange = { value ->
                        localQuery = value
                        vm.onQueryChange(value)
                    },
                    modifier = Modifier
                        .padding(horizontal = spacing.screen)
                        .focusRequester(searchFocus),
                    placeholder = "Search documents",
                )
            }
            if (state.hasAnyDocuments) {
                FilterRow(state = state, vm = vm)
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.isLoading -> Unit
                    state.isEmpty -> DocumentsEmptyState(state = state, onNewDocument = onNewDocument)
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = spacing.screen,
                            end = spacing.screen,
                            top = spacing.sm,
                            bottom = 96.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(spacing.md),
                    ) {
                        if (state.needsAttention && state.query.isBlank()) {
                            item(key = "summary") {
                                AttentionSummary(
                                    state = state,
                                    onToggle = { vm.toggleFilter(DocumentFilter.NeedsAttention) },
                                )
                            }
                        }
                        state.sections.forEach { section ->
                            item(key = "header-${section.state.name}") { SectionHeader(section.title) }
                            items(section.documents, key = { it.id }) { document ->
                                DocumentCard(
                                    document = document,
                                    onClick = { onOpenDocument(document.id) },
                                    onEdit = { onEditDocument(document.id) },
                                    onDelete = {
                                        vm.delete(document.id)
                                        scope.launch {
                                            showUndo("Moved to Recently deleted", { vm.restore(document.id) })
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLockSheet) {
        VaultLockSettingsSheet(
            vaultId = VAULT_ID,
            vaultName = VAULT_NAME,
            onDismiss = { showLockSheet = false },
        )
    }

    if (showAddSheet) {
        NeriboBottomSheet(onDismiss = { showAddSheet = false }) {
            AddRow(
                icon = Icons.Outlined.Description,
                label = "Add document",
                onClick = {
                    showAddSheet = false
                    onNewDocument()
                },
            )
            AddRow(
                icon = Icons.Outlined.AttachFile,
                label = "Add a file",
                onClick = {
                    showAddSheet = false
                    filePicker.launch(arrayOf("*/*"))
                },
            )
        }
    }

    if (state.isSavingFile) {
        SavingDialog()
    }
}

/** One tall, clearly labelled row of the add sheet. */
@Composable
private fun AddRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = colors.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(spacing.lg))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurface,
        )
    }
}

/** Shown while a picked file is being saved. It cannot be dismissed, so the save is never cut short. */
@Composable
private fun SavingDialog() {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        shape = MaterialTheme.shapes.large,
        containerColor = colors.surface,
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = colors.primary,
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(spacing.lg))
                Text(
                    text = "Saving to your Documents\u2026",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurface,
                )
            }
        },
    )
}

@Composable
private fun FilterRow(state: DocumentsUiState, vm: DocumentsViewModel) {
    val spacing = NeriboTheme.spacing
    val selected = state.selectedFilter
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = spacing.screen),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeriboChip(
            label = "All",
            selected = selected == DocumentFilter.All,
            onClick = { vm.clearFilter() },
        )
        NeriboChip(
            label = "Expiring soon",
            selected = selected == DocumentFilter.ExpiringSoon,
            onClick = { vm.toggleFilter(DocumentFilter.ExpiringSoon) },
        )
        NeriboChip(
            label = "Expired",
            selected = selected == DocumentFilter.Expired,
            onClick = { vm.toggleFilter(DocumentFilter.Expired) },
        )
        if (selected == DocumentFilter.NeedsAttention) {
            NeriboChip(
                label = "Needs attention",
                selected = true,
                onClick = { vm.clearFilter() },
            )
        }
        state.categories.forEach { category ->
            val filter = DocumentFilter.Category(category)
            NeriboChip(
                label = category,
                selected = selected is DocumentFilter.Category &&
                    selected.name.equals(category, ignoreCase = true),
                onClick = { vm.toggleFilter(filter) },
            )
        }
    }
}

/** "2 expired · 1 expiring soon" in warning tone. Tap to filter to those documents. */
@Composable
private fun AttentionSummary(state: DocumentsUiState, onToggle: () -> Unit) {
    val spacing = NeriboTheme.spacing
    val warning = NeriboTheme.extraColors.warning
    val parts = buildList<String> {
        if (state.expiredCount > 0) add("${state.expiredCount} expired")
        if (state.expiringSoonCount > 0) add("${state.expiringSoonCount} expiring soon")
    }
    val active = state.selectedFilter == DocumentFilter.NeedsAttention
    NeriboCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.lg, end = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = warning,
            )
            Spacer(modifier = Modifier.width(spacing.md))
            Text(
                text = parts.joinToString(" \u00B7 "),
                style = MaterialTheme.typography.titleSmall,
                color = warning,
                modifier = Modifier.weight(1f),
            )
            NeriboButton(
                text = if (active) "Show all" else "Show",
                onClick = onToggle,
                style = ButtonStyle.Text,
            )
        }
    }
}

@Composable
private fun DocumentsEmptyState(state: DocumentsUiState, onNewDocument: () -> Unit) {
    when {
        state.query.isNotBlank() -> EmptyState(
            icon = Icons.Outlined.Search,
            title = "No matches",
            message = "Nothing found for \u201C${state.query.trim()}\u201D.",
            modifier = Modifier.fillMaxSize(),
        )
        state.isFiltering -> EmptyState(
            icon = Icons.Outlined.Search,
            title = "No matches",
            message = "No documents fit this filter right now.",
            modifier = Modifier.fillMaxSize(),
        )
        else -> EmptyState(
            icon = Icons.Outlined.Description,
            title = "No documents yet",
            message = "Keep your important papers in one safe place. Add your first document.",
            modifier = Modifier.fillMaxSize(),
            actionLabel = "Add document",
            onAction = onNewDocument,
        )
    }
}
