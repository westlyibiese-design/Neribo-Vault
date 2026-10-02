package com.westly.neribovault.feature.goals

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ConfirmDialog
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTextField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.core.util.formatDate
import com.westly.neribovault.feature.goals.components.GoalDatePickerDialog

private const val MAX_TITLE_LENGTH = 120

/**
 * The goal form: title, description, category, optional target date and status. Saves on the
 * explicit Save button. A new goal left empty is simply discarded; leaving with unsaved edits
 * asks first. [onSaved] receives the goal's id and whether it was just created.
 */
@Composable
fun GoalEditorScreen(
    goalId: String,
    onBack: () -> Unit,
    onSaved: (savedId: String, wasNew: Boolean) -> Unit,
) {
    val vm = neriboViewModel(key = goalId) { c -> GoalEditorViewModel(goalId, c.goalsRepository) }
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.notFound) {
        if (state.notFound) onBack()
    }

    if (!state.isLoaded) {
        NeriboScaffold(
            topBar = {
                NeriboTopBar(
                    title = if (vm.isNew) "New goal" else "Edit goal",
                    onBack = onBack,
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding))
        }
    } else {
        GoalFormContent(
            vm = vm,
            initial = state.initial,
            isSaving = state.isSaving,
            onBack = onBack,
            onSaved = onSaved,
        )
    }
}

@Composable
private fun GoalFormContent(
    vm: GoalEditorViewModel,
    initial: GoalForm,
    isSaving: Boolean,
    onBack: () -> Unit,
    onSaved: (savedId: String, wasNew: Boolean) -> Unit,
) {
    val spacing = NeriboTheme.spacing

    // The form lives here, not in the ViewModel, so typing is never delayed by a state round
    // trip. rememberSaveable keeps it across rotation.
    var title by rememberSaveable { mutableStateOf(initial.title) }
    var description by rememberSaveable { mutableStateOf(initial.description) }
    var category by rememberSaveable { mutableStateOf(initial.category) }
    var targetDate by rememberSaveable { mutableStateOf(initial.targetDate) }
    var status by rememberSaveable { mutableStateOf(initial.status) }
    var showTitleError by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showDiscard by rememberSaveable { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }

    val hasChanges = title != initial.title ||
        description != initial.description ||
        category != initial.category ||
        targetDate != initial.targetDate ||
        status != initial.status

    val leave: () -> Unit = {
        if (hasChanges) showDiscard = true else onBack()
    }
    val submit: () -> Unit = {
        if (title.isBlank()) {
            showTitleError = true
            runCatching { titleFocus.requestFocus() }
        } else {
            vm.save(
                GoalForm(
                    title = title,
                    description = description,
                    category = category,
                    targetDate = targetDate,
                    status = status,
                ),
            ) { savedId -> onSaved(savedId, vm.isNew) }
        }
    }

    BackHandler(onBack = leave)
    LaunchedEffect(Unit) {
        if (vm.isNew && title.isEmpty()) runCatching { titleFocus.requestFocus() }
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = if (vm.isNew) "New goal" else "Edit goal",
                onBack = leave,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.screen, vertical = spacing.sm),
            ) {
                NeriboTextField(
                    value = title,
                    onValueChange = { new ->
                        title = new.replace("\n", " ").take(MAX_TITLE_LENGTH)
                        if (title.isNotBlank()) showTitleError = false
                    },
                    modifier = Modifier.focusRequester(titleFocus),
                    label = "Title",
                    placeholder = goalTitleHint(category),
                    singleLine = false,
                    maxLines = 3,
                    isError = showTitleError,
                    supportingText = if (showTitleError) "Give your goal a title" else null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                Spacer(modifier = Modifier.height(spacing.md))
                NeriboTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = "Why it matters",
                    placeholder = "Write here",
                    singleLine = false,
                    minLines = 3,
                    maxLines = 8,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                Spacer(modifier = Modifier.height(spacing.xl))
                SectionHeader("CATEGORY")
                ChipRow {
                    GOAL_CATEGORIES.forEach { option ->
                        NeriboChip(
                            label = goalCategoryLabel(option),
                            selected = category == option,
                            onClick = { category = option },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(spacing.md))
                SectionHeader("TARGET DATE")
                TargetDateRow(
                    targetDate = targetDate,
                    onPick = { showDatePicker = true },
                    onClear = { targetDate = null },
                )
                Spacer(modifier = Modifier.height(spacing.md))
                SectionHeader("STATUS")
                ChipRow {
                    GOAL_STATUSES.forEach { option ->
                        NeriboChip(
                            label = goalStatusLabel(option),
                            selected = status == option,
                            onClick = { status = option },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(spacing.xl))
            }
            Column {
                NeriboDivider()
                NeriboButton(
                    text = "Save",
                    onClick = submit,
                    enabled = !isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.screen, vertical = spacing.md),
                )
            }
        }
    }

    if (showDatePicker) {
        GoalDatePickerDialog(
            initialDate = targetDate,
            onConfirm = { date ->
                targetDate = date
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false },
        )
    }
    if (showDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            message = "Your changes to this goal have not been saved.",
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
}

/** A horizontally scrolling row of chips. */
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(NeriboTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/** Tap to pick a date; a small X clears it. Shows "No target date" while empty. */
@Composable
private fun TargetDateRow(
    targetDate: Long?,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(colors.surfaceVariant, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 52.dp)
                .clickable(role = Role.Button, onClickLabel = "Pick a target date", onClick = onPick)
                .padding(horizontal = spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Event,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (targetDate != null) colors.primary else colors.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(spacing.sm))
            Text(
                text = if (targetDate != null) formatDate(targetDate) else "No target date",
                style = MaterialTheme.typography.bodyLarge,
                color = if (targetDate != null) colors.onSurface else colors.onSurfaceVariant,
            )
        }
        if (targetDate != null) {
            NeriboIconButton(
                icon = Icons.Outlined.Close,
                contentDescription = "Clear target date",
                onClick = onClear,
            )
        }
    }
}
