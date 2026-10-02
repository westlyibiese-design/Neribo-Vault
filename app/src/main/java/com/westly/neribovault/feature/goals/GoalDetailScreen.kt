package com.westly.neribovault.feature.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTextField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.data.local.entity.GoalEntity
import com.westly.neribovault.data.local.entity.GoalMilestoneEntity
import com.westly.neribovault.feature.goals.components.DueLine
import com.westly.neribovault.feature.goals.components.GoalDatePickerDialog
import com.westly.neribovault.feature.goals.components.GoalProgressBar
import com.westly.neribovault.feature.goals.components.MilestoneRow
import com.westly.neribovault.feature.goals.components.rememberDayTick

/** Longest a step title can be. */
private const val MAX_STEP_LENGTH = 200

/**
 * One goal: header (title, description, category, status, target date, progress) and the list
 * of steps with an inline "Add a step" field. Never completes a goal on its own.
 */
@Composable
fun GoalDetailScreen(
    goalId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDeleted: (deletedId: String?) -> Unit,
) {
    val vm = neriboViewModel(key = goalId) { c -> GoalDetailViewModel(goalId, c.goalsRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val dayTick = rememberDayTick()
    // Set the moment we start deleting, so the goal vanishing from the database is not mistaken
    // for "this goal does not exist" and does not pop the screen a second time.
    var leaving by remember { mutableStateOf(false) }
    var editingStepId by rememberSaveable { mutableStateOf<String?>(null) }
    var datingStepId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(state.notFound) {
        if (state.notFound && !leaving) onBack()
    }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                GoalDetailEvent.AllStepsDone -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    val result = snackbarHostState.showSnackbar(
                        message = "All steps done. Mark this goal as completed?",
                        actionLabel = "Complete",
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) vm.setStatus("completed")
                }
                is GoalDetailEvent.StepDeleted -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    val result = snackbarHostState.showSnackbar(
                        message = "Step deleted",
                        actionLabel = "Undo",
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) vm.restoreMilestone(event.milestone)
                }
            }
        }
    }

    val goal = state.goal

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = "Goal",
                onBack = onBack,
                actions = {
                    if (goal != null) {
                        NeriboIconButton(
                            icon = Icons.Outlined.Edit,
                            contentDescription = "Edit goal",
                            onClick = onEdit,
                        )
                        OverflowMenu(
                            actions = listOf(
                                MenuAction(
                                    label = if (goal.isPinned) "Unpin" else "Pin",
                                    onClick = { vm.togglePinned() },
                                    icon = Icons.Outlined.PushPin,
                                ),
                                MenuAction(
                                    label = "Delete",
                                    onClick = {
                                        leaving = true
                                        vm.deleteGoal(onDone = { id -> onDeleted(id) })
                                    },
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
        if (goal == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding))
        } else {
            DetailContent(
                vm = vm,
                goal = goal,
                milestones = state.milestones,
                dayTick = dayTick,
                padding = padding,
                onEditStep = { editingStepId = it.id },
                onDateStep = { datingStepId = it.id },
            )
        }
    }

    val editingStep = state.milestones.firstOrNull { it.id == editingStepId }
    if (editingStep != null) {
        EditStepDialog(
            initial = editingStep.title,
            onSave = { title ->
                vm.renameMilestone(editingStep, title)
                editingStepId = null
            },
            onDismiss = { editingStepId = null },
        )
    }

    val datingStep = state.milestones.firstOrNull { it.id == datingStepId }
    if (datingStep != null) {
        val clearDate: () -> Unit = {
            vm.setMilestoneDueDate(datingStep, null)
            datingStepId = null
        }
        GoalDatePickerDialog(
            initialDate = datingStep.dueDate,
            onConfirm = { date ->
                vm.setMilestoneDueDate(datingStep, date)
                datingStepId = null
            },
            onDismiss = { datingStepId = null },
            onClear = if (datingStep.dueDate != null) clearDate else null,
        )
    }
}

@Composable
private fun DetailContent(
    vm: GoalDetailViewModel,
    goal: GoalEntity,
    milestones: List<GoalMilestoneEntity>,
    dayTick: Int,
    padding: PaddingValues,
    onEditStep: (GoalMilestoneEntity) -> Unit,
    onDateStep: (GoalMilestoneEntity) -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val listState = rememberLazyListState()
    var lastStepCount by remember { mutableIntStateOf(-1) }

    // After adding a step, scroll so the "Add a step" field stays in view for the next one.
    LaunchedEffect(milestones.size) {
        val count = milestones.size
        if (lastStepCount >= 0 && count > lastStepCount) {
            // Items above the field: header, "STEPS" label, then one per step.
            listState.animateScrollToItem(count + 2)
        }
        lastStepCount = count
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
            .imePadding(),
        contentPadding = PaddingValues(
            start = spacing.screen,
            end = spacing.screen,
            top = spacing.sm,
            bottom = spacing.xxl,
        ),
    ) {
        item(key = "header") {
            GoalHeader(
                goal = goal,
                milestones = milestones,
                dayTick = dayTick,
                onStatusSelected = { vm.setStatus(it) },
            )
        }
        item(key = "steps-header") {
            SectionHeader(
                text = "STEPS",
                modifier = Modifier.padding(top = spacing.xl, bottom = spacing.xs),
            )
        }
        if (milestones.isEmpty()) {
            item(key = "steps-empty") {
                Text(
                    text = "Break it into small steps you can tick off one by one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = spacing.sm),
                )
            }
        }
        itemsIndexed(milestones, key = { _, step -> step.id }) { index, step ->
            Column {
                MilestoneRow(
                    milestone = step,
                    dayTick = dayTick,
                    canMoveUp = index > 0,
                    canMoveDown = index < milestones.lastIndex,
                    onToggle = { vm.toggleMilestone(step) },
                    onEdit = { onEditStep(step) },
                    onMoveUp = { vm.moveMilestone(step, -1) },
                    onMoveDown = { vm.moveMilestone(step, 1) },
                    onDueDate = { onDateStep(step) },
                    onDelete = { vm.deleteMilestone(step) },
                )
                NeriboDivider()
            }
        }
        item(key = "add-step") {
            AddStepField(
                onAdd = { vm.addMilestone(it) },
                modifier = Modifier.padding(top = spacing.md),
            )
        }
    }
}

@Composable
private fun GoalHeader(
    goal: GoalEntity,
    milestones: List<GoalMilestoneEntity>,
    dayTick: Int,
    onStatusSelected: (String) -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val progress = remember(goal.status, milestones) { goalProgress(goal.status, milestones) }
    val due = remember(goal.targetDate, goal.status, dayTick) {
        goal.targetDate?.let { goalDueInfo(it, isCompleted = goal.status == "completed") }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = goal.title.trim().ifEmpty { "Untitled goal" },
            style = MaterialTheme.typography.headlineSmall,
            color = colors.onBackground,
        )
        Spacer(modifier = Modifier.height(spacing.xs))
        Text(
            text = goalCategoryLabel(goal.category),
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )
        if (goal.description.isNotBlank()) {
            Spacer(modifier = Modifier.height(spacing.md))
            Text(
                text = goal.description.trim(),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
            )
        }
        Spacer(modifier = Modifier.height(spacing.lg))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GOAL_STATUSES.forEach { status ->
                NeriboChip(
                    label = goalStatusLabel(status),
                    selected = goal.status == status,
                    onClick = { if (goal.status != status) onStatusSelected(status) },
                )
            }
        }
        if (due != null) {
            Spacer(modifier = Modifier.height(spacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Event,
                    contentDescription = "Target date",
                    modifier = Modifier.size(18.dp),
                    tint = colors.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(spacing.sm))
                DueLine(info = due)
            }
        }
        Spacer(modifier = Modifier.height(spacing.lg))
        GoalProgressBar(fraction = progress.fraction)
        Spacer(modifier = Modifier.height(spacing.sm))
        Text(
            text = progress.label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }
}

/** The inline "Add a step" field. Enter (Done) adds the step and keeps the keyboard open. */
@Composable
private fun AddStepField(onAdd: (String) -> Unit, modifier: Modifier = Modifier) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    var text by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    fun submit() {
        val clean = text.trim()
        if (clean.isNotEmpty()) {
            onAdd(clean)
            text = ""
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(colors.surfaceVariant, shape)
            .padding(start = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Add,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = colors.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(spacing.sm))
        BasicTextField(
            value = text,
            onValueChange = { new -> text = new.replace("\n", "").take(MAX_STEP_LENGTH) },
            modifier = Modifier
                .weight(1f)
                .padding(vertical = spacing.md)
                .focusRequester(focusRequester),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            decorationBox = { inner ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (text.isEmpty()) {
                        Text(
                            text = "Add a step",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant.copy(alpha = 0.55f),
                        )
                    }
                    inner()
                }
            },
        )
        if (text.isNotBlank()) {
            NeriboIconButton(
                icon = Icons.Outlined.Check,
                contentDescription = "Add step",
                onClick = { submit() },
                tint = colors.primary,
            )
        } else {
            Spacer(modifier = Modifier.width(spacing.md))
        }
    }
}

@Composable
private fun EditStepDialog(
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var input by rememberSaveable { mutableStateOf(initial) }
    val focusRequester = remember { FocusRequester() }
    val canSave = input.isNotBlank()

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Edit step", style = MaterialTheme.typography.titleLarge) },
        text = {
            NeriboTextField(
                value = input,
                onValueChange = { input = it.replace("\n", "").take(MAX_STEP_LENGTH) },
                modifier = Modifier.focusRequester(focusRequester),
                label = "Step",
                placeholder = "Describe the step",
                singleLine = false,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (canSave) onSave(input) }, enabled = canSave) {
                Text(
                    text = "Save",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (canSave) colors.primary else colors.onSurface.copy(alpha = 0.38f),
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
