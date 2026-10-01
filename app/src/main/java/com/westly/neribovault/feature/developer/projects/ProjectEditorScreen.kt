package com.westly.neribovault.feature.developer.projects

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.ConfirmDialog
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTextField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.feature.developer.MAX_TECH_ITEMS
import com.westly.neribovault.feature.developer.MAX_TECH_LENGTH
import com.westly.neribovault.feature.developer.PROJECT_STATUSES
import com.westly.neribovault.feature.developer.TECH_SUGGESTIONS

/** Create or edit a project. Saving is explicit; leaving with unsaved text asks first. */
@Composable
fun ProjectEditorScreen(
    projectId: String,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val vm = neriboViewModel(key = "project-edit-$projectId") { c ->
        ProjectEditorViewModel(projectId, c.projectsRepository)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    var showAddTech by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }

    val needsConfirm = state.hasChanges && !state.isBlank
    val requestBack: () -> Unit = {
        if (needsConfirm) confirmDiscard = true else onBack()
    }
    BackHandler(enabled = needsConfirm) { confirmDiscard = true }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = if (state.isNew) "New project" else "Edit project",
                onBack = requestBack,
                actions = {
                    NeriboButton(
                        text = "Save",
                        onClick = { vm.save(onSaved) },
                        enabled = !state.isSaving && !state.isLoading && !state.notFound,
                        style = ButtonStyle.Text,
                    )
                },
            )
        },
    ) { padding ->
        when {
            state.notFound -> EmptyState(
                icon = Icons.Outlined.Code,
                title = "Project not found",
                message = "It may have been deleted.",
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
                    value = state.name,
                    onValueChange = vm::onNameChange,
                    label = "Name",
                    placeholder = "ElovanPoint POS for Westly Stores",
                    isError = state.nameError,
                    supportingText = if (state.nameError) "Give your project a name" else null,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Next,
                    ),
                )
                NeriboTextField(
                    value = state.description,
                    onValueChange = vm::onDescriptionChange,
                    label = "Description",
                    placeholder = "Hotel management for a Benin City hotel",
                    singleLine = false,
                    minLines = 3,
                    maxLines = 8,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                Column {
                    SectionHeader("STATUS")
                    Spacer(modifier = Modifier.height(spacing.xs))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        PROJECT_STATUSES.forEach { option ->
                            NeriboChip(
                                label = option.label,
                                selected = state.status == option.value,
                                onClick = { vm.onStatusChange(option.value) },
                            )
                        }
                    }
                }
                TechStackEditor(
                    techStack = state.techStack,
                    onRemove = vm::removeTech,
                    onAddClick = { showAddTech = true },
                )
                NeriboTextField(
                    value = state.repoUrl,
                    onValueChange = vm::onRepoChange,
                    label = "Repository link",
                    placeholder = "https://github.com/westly/elovanpoint",
                    isError = state.repoError,
                    supportingText = if (state.repoError) "Start the link with http:// or https://" else null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                )
                NeriboTextField(
                    value = state.liveUrl,
                    onValueChange = vm::onLiveChange,
                    label = "Live link",
                    placeholder = "https://elovanpoint.example.com",
                    isError = state.liveError,
                    supportingText = if (state.liveError) "Start the link with http:// or https://" else null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                )
                Spacer(modifier = Modifier.height(spacing.xl))
            }
        }
    }

    if (showAddTech) {
        AddTechDialog(
            existing = state.techStack,
            onAdd = { tech ->
                vm.addTech(tech)
                showAddTech = false
            },
            onDismiss = { showAddTech = false },
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TechStackEditor(
    techStack: List<String>,
    onRemove: (String) -> Unit,
    onAddClick: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    Column {
        SectionHeader("TECH STACK")
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            techStack.forEach { tech -> RemovableTechChip(tech = tech, onRemove = { onRemove(tech) }) }
            if (techStack.size < MAX_TECH_ITEMS) {
                NeriboChip(label = "Add", selected = false, onClick = onAddClick)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemovableTechChip(tech: String, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    Row(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .background(colors.surfaceVariant, shape)
            .clickable(onClickLabel = "Remove $tech", role = Role.Button, onClick = onRemove)
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = tech,
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = colors.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddTechDialog(
    existing: List<String>,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var input by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val clean = input.trim()
    val isDuplicate = clean.isNotEmpty() && existing.any { it.equals(clean, ignoreCase = true) }
    val canAdd = clean.isNotEmpty() && !isDuplicate
    val suggestions = TECH_SUGGESTIONS.filter { suggestion ->
        existing.none { it.equals(suggestion, ignoreCase = true) }
    }

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Add to tech stack", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NeriboTheme.spacing.md)) {
                NeriboTextField(
                    value = input,
                    onValueChange = { input = it.take(MAX_TECH_LENGTH) },
                    modifier = Modifier.focusRequester(focusRequester),
                    placeholder = "e.g. Supabase",
                    isError = isDuplicate,
                    supportingText = if (isDuplicate) "Already in the stack" else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                if (suggestions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(NeriboTheme.spacing.sm)) {
                        suggestions.forEach { suggestion ->
                            NeriboChip(
                                label = suggestion,
                                selected = false,
                                onClick = { onAdd(suggestion) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (canAdd) onAdd(clean) }, enabled = canAdd) {
                Text(
                    text = "Add",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (canAdd) colors.primary else colors.onSurface.copy(alpha = 0.38f),
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
        shape = MaterialTheme.shapes.large,
        containerColor = colors.surface,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceVariant,
        tonalElevation = 0.dp,
    )
}
