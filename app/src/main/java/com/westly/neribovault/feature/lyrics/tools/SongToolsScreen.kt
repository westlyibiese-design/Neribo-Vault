package com.westly.neribovault.feature.lyrics.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ConfirmDialog
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar

private const val TAB_OVERVIEW = 0
private const val TAB_STRUCTURE = 1
private const val TAB_SYLLABLES = 2
private val TAB_LABELS = listOf("Overview", "Structure", "Syllables")

/** Measures a song and lets the owner reorder, duplicate or delete its sections. */
@Composable
fun SongToolsScreen(songId: String, onJumpToSection: (sectionIndex: Int) -> Unit, onBack: () -> Unit) {
    val vm = neriboViewModel(key = "lyrics-tools-$songId") { c ->
        SongToolsViewModel(c.songsRepository, songId)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableStateOf(TAB_OVERVIEW) }
    var pendingDeleteIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            snackbarHostState.currentSnackbarData?.dismiss()
            when (event) {
                SongToolsEvent.SectionDeleted -> {
                    val result = snackbarHostState.showSnackbar(
                        message = "Section deleted",
                        actionLabel = "Undo",
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) vm.undoDelete()
                }
                SongToolsEvent.SaveFailed -> {
                    snackbarHostState.showSnackbar("Could not save the change. Please try again.")
                }
            }
        }
    }

    val actions = remember(vm, onJumpToSection) {
        StructureActions(
            onJump = onJumpToSection,
            onMoveUp = { index -> vm.moveUp(index) },
            onMoveDown = { index -> vm.moveDown(index) },
            onDuplicate = { index -> vm.duplicate(index) },
            onDelete = { index -> pendingDeleteIndex = index },
        )
    }

    NeriboScaffold(
        topBar = { NeriboTopBar(title = "Song tools", onBack = onBack) },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> Box(modifier = Modifier.fillMaxSize())
                state.isEmpty -> EmptyState(
                    icon = Icons.Outlined.MusicNote,
                    title = "Nothing to measure yet",
                    message = "Write a few sections and they will appear here.",
                    modifier = Modifier.fillMaxSize(),
                )
                else -> {
                    ToolsTabRow(selected = selectedTab, onSelect = { selectedTab = it })
                    when (selectedTab) {
                        TAB_STRUCTURE -> StructureTab(rows = state.rows, actions = actions)
                        TAB_SYLLABLES -> SyllablesTab(sections = state.syllableSections)
                        else -> OverviewTab(state = state)
                    }
                }
            }
        }
    }

    val deleteIndex = pendingDeleteIndex
    if (deleteIndex != null) {
        val label = state.rows.firstOrNull { it.index == deleteIndex }?.label
        if (label != null) {
            ConfirmDialog(
                title = "Delete $label?",
                message = "Its lines will be removed from the song. You can undo this right after.",
                confirmLabel = "Delete",
                onConfirm = {
                    pendingDeleteIndex = null
                    vm.delete(deleteIndex)
                },
                onDismiss = { pendingDeleteIndex = null },
                destructive = true,
            )
        }
    }
}

/** Three plain tabs with an accent underline under the selected one. */
@Composable
private fun ToolsTabRow(selected: Int, onSelect: (Int) -> Unit) {
    val spacing = NeriboTheme.spacing
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.screen)) {
            TAB_LABELS.forEachIndexed { index, label ->
                val isSelected = index == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .selectable(
                            selected = isSelected,
                            role = Role.Tab,
                            onClick = { onSelect(index) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onBackground
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent),
                    )
                }
            }
        }
        NeriboDivider()
    }
}
