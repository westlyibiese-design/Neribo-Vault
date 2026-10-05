package com.westly.neribovault.feature.screenplays.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ConfirmDialog
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTextField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.ui.components.SectionHeader

private const val MAX_NAME_LENGTH = 40

/** Jump to a scene, move, duplicate or delete scenes, and see and rename characters. */
@Composable
fun ScenesScreen(screenplayId: String, onJumpToBlock: (blockIndex: Int) -> Unit, onBack: () -> Unit) {
    val vm = neriboViewModel(key = "scenes-$screenplayId") { c ->
        ScenesViewModel(c.screenplaysRepository, screenplayId)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val snackbarHostState = remember { SnackbarHostState() }

    var deleteBlockIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var deleteNumber by rememberSaveable { mutableStateOf(0) }
    var renameFrom by rememberSaveable { mutableStateOf<String?>(null) }
    var renameInput by rememberSaveable { mutableStateOf("") }
    var renameConfirm by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            snackbarHostState.currentSnackbarData?.dismiss()
            when (event) {
                is ScenesEvent.SceneDeleted -> {
                    val result = snackbarHostState.showSnackbar(
                        message = "Scene deleted",
                        actionLabel = "Undo",
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) vm.undoDelete()
                }
                is ScenesEvent.Message -> snackbarHostState.showSnackbar(message = event.text)
            }
        }
    }

    NeriboScaffold(
        topBar = { NeriboTopBar(title = "Scenes and characters", onBack = onBack) },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> Unit
                state.isEmpty -> EmptyState(
                    icon = Icons.Outlined.Movie,
                    title = "No scenes yet",
                    message = "Write a few scenes and they will appear here.",
                    modifier = Modifier.fillMaxSize(),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = spacing.screen,
                        end = spacing.screen,
                        top = spacing.sm,
                        bottom = spacing.xxl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    item(key = "scenes-header") {
                        SectionHeader("Scenes  \u00B7  ${state.scenes.size}")
                    }
                    val preamble = state.preamble
                    if (preamble != null) {
                        item(key = "preamble") {
                            PreambleRow(preamble = preamble, onClick = { onJumpToBlock(preamble.blockIndex) })
                        }
                    }
                    items(state.scenes, key = { "scene-${it.info.blockIndex}" }) { row ->
                        SceneRow(
                            row = row,
                            onClick = { onJumpToBlock(row.info.blockIndex) },
                            actions = sceneActions(
                                row = row,
                                onMoveUp = { vm.moveUp(row.info.blockIndex) },
                                onMoveDown = { vm.moveDown(row.info.blockIndex) },
                                onDuplicate = { vm.duplicate(row.info.blockIndex) },
                                onDelete = {
                                    deleteNumber = row.info.number
                                    deleteBlockIndex = row.info.blockIndex
                                },
                            ),
                        )
                    }
                    item(key = "characters-header") {
                        SectionHeader("Characters  \u00B7  ${state.characters.size}")
                    }
                    items(state.characters, key = { "character-${it.name}" }) { speaker ->
                        CharacterRow(
                            speaker = speaker,
                            onRename = {
                                renameFrom = speaker.name
                                renameInput = speaker.name
                            },
                        )
                    }
                }
            }
        }
    }

    val pendingDelete = deleteBlockIndex
    if (pendingDelete != null) {
        ConfirmDialog(
            title = "Delete scene $deleteNumber?",
            message = "Its text will be removed from the script. You can undo this right after.",
            confirmLabel = "Delete",
            onConfirm = {
                deleteBlockIndex = null
                vm.delete(pendingDelete, deleteNumber)
            },
            onDismiss = { deleteBlockIndex = null },
            destructive = true,
        )
    }

    val from = renameFrom
    if (from != null) {
        val cleaned = cleanName(renameInput)
        val changes = cleaned.isNotEmpty() && !cleaned.equals(from, ignoreCase = true)
        if (!renameConfirm) {
            RenameDialog(
                oldName = from,
                input = renameInput,
                canContinue = changes,
                onInputChange = { renameInput = limitName(it) },
                onContinue = { renameConfirm = true },
                onDismiss = {
                    renameFrom = null
                    renameConfirm = false
                },
            )
        } else {
            val cues = state.characters.firstOrNull { it.name == from }?.speeches ?: 0
            val exists = state.characters.any { it.name.equals(cleaned, ignoreCase = true) }
            val cueText = if (cues == 1) "1 cue" else "$cues cues"
            val merged = if (exists) " These characters will be merged." else ""
            ConfirmDialog(
                title = "Rename character",
                message = "Rename $cueText from $from to $cleaned?$merged",
                confirmLabel = "Rename",
                onConfirm = {
                    vm.rename(from, cleaned)
                    renameFrom = null
                    renameConfirm = false
                },
                onDismiss = {
                    renameFrom = null
                    renameConfirm = false
                },
            )
        }
    }
}

/** The menu for one scene. Moves that are not possible are left out. */
private fun sceneActions(
    row: SceneRowItem,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
): List<MenuAction> = buildList {
    if (row.canMoveUp) add(MenuAction("Move up", onMoveUp, Icons.Outlined.ArrowUpward))
    if (row.canMoveDown) add(MenuAction("Move down", onMoveDown, Icons.Outlined.ArrowDownward))
    add(MenuAction("Duplicate scene", onDuplicate, Icons.Outlined.ContentCopy))
    add(MenuAction("Delete scene", onDelete, Icons.Outlined.Delete, destructive = true))
}

@Composable
private fun PreambleRow(preamble: PreambleItem, onClick: () -> Unit) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    NeriboCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(modifier = Modifier.fillMaxWidth().padding(spacing.lg)) {
            Text(
                text = "Before the first scene",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
            )
            if (preamble.snippet.isNotEmpty()) {
                Text(
                    text = preamble.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SceneRow(row: SceneRowItem, onClick: () -> Unit, actions: List<MenuAction>) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    NeriboCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.lg, top = spacing.sm, bottom = spacing.sm, end = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = row.info.number.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurface,
                )
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = spacing.md)) {
                Text(
                    text = row.info.heading,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    ),
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.snippet.isNotEmpty()) {
                    Text(
                        text = row.snippet,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = "p. ${row.info.page}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            OverflowMenu(actions = actions)
        }
    }
}

@Composable
private fun CharacterRow(speaker: SpeakerStat, onRename: () -> Unit) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val scenes = if (speaker.scenes == 1) "1 scene" else "${speaker.scenes} scenes"
    NeriboCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.lg, top = spacing.sm, bottom = spacing.sm, end = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = speaker.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${speechesLabel(speaker.speeches)} \u00B7 $scenes",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(spacing.sm))
            OverflowMenu(actions = listOf(MenuAction("Rename", onRename, Icons.Outlined.Edit)))
        }
    }
}

@Composable
private fun RenameDialog(
    oldName: String,
    input: String,
    canContinue: Boolean,
    onInputChange: (String) -> Unit,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onContinue, enabled = canContinue) {
                Text(
                    text = "Continue",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (canContinue) colors.primary else colors.onSurfaceVariant,
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
        title = { Text(text = "Rename $oldName", style = MaterialTheme.typography.titleLarge) },
        text = {
            NeriboTextField(
                value = input,
                onValueChange = onInputChange,
                label = "New name",
                placeholder = "ADAEZE",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                supportingText = "Up to $MAX_NAME_LENGTH letters, no brackets. The extension stays, like (V.O.).",
            )
        },
        shape = MaterialTheme.shapes.large,
        containerColor = colors.surface,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceVariant,
        tonalElevation = 0.dp,
    )
}

/** As typed: upper case, no brackets or line breaks, at most 40 characters. */
private fun limitName(raw: String): String =
    raw.uppercase().filter { it != '(' && it != ')' && it != '\n' && it != '\r' }.take(MAX_NAME_LENGTH)

/** The name that will be saved: [limitName] with spaces trimmed and repeated spaces collapsed. */
private fun cleanName(raw: String): String =
    limitName(raw).trim().split(' ').filter { it.isNotEmpty() }.joinToString(" ")
