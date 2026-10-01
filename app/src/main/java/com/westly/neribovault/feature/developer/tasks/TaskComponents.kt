package com.westly.neribovault.feature.developer.tasks

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.BadgeTone
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboBottomSheet
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.core.ui.components.StatusBadge
import com.westly.neribovault.core.util.formatDateTime
import com.westly.neribovault.core.util.formatTime
import com.westly.neribovault.data.local.entity.ProjectEntity
import com.westly.neribovault.data.local.entity.TaskEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** The route argument value that means "create a new task". */
internal const val TASK_NEW_ID = "new"

internal const val PRIORITY_LOW = "low"
internal const val PRIORITY_NORMAL = "normal"
internal const val PRIORITY_HIGH = "high"

internal const val REPEAT_DAILY = "daily"
internal const val REPEAT_WEEKLY = "weekly"
internal const val REPEAT_MONTHLY = "monthly"

/** How many finished tasks the Done section shows. */
internal const val DONE_LIMIT = 20

private const val DEFAULT_DUE_HOUR = 9
private const val DEFAULT_DUE_MINUTE = 0
private const val MAX_REPEAT_STEPS = 4_000L
private const val TICK_MS = 60_000L

internal val TASK_PRIORITIES: List<String> = listOf(PRIORITY_LOW, PRIORITY_NORMAL, PRIORITY_HIGH)

/** Repeat choices in chip order; null means "None". */
internal val TASK_REPEATS: List<String?> = listOf(null, REPEAT_DAILY, REPEAT_WEEKLY, REPEAT_MONTHLY)

private val DAYS_SHORT = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

private val MONTHS_SHORT = arrayOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

internal fun priorityLabel(priority: String): String = when (priority) {
    PRIORITY_LOW -> "Low"
    PRIORITY_HIGH -> "High"
    else -> "Normal"
}

/** Sort rank of a priority: High is 0 so it comes first. */
internal fun priorityRank(priority: String): Int = when (priority) {
    PRIORITY_HIGH -> 0
    PRIORITY_LOW -> 2
    else -> 1
}

internal fun repeatLabel(rule: String?): String = when (rule) {
    REPEAT_DAILY -> "Daily"
    REPEAT_WEEKLY -> "Weekly"
    REPEAT_MONTHLY -> "Monthly"
    else -> "None"
}

/** Ticks once now and then every minute, so lists regroup when midnight passes. */
internal fun minuteTicker(): Flow<Long> = flow {
    while (true) {
        emit(System.currentTimeMillis())
        delay(TICK_MS)
    }
}

// ---- Time helpers ---------------------------------------------------------------------------

private fun zone(): ZoneId = ZoneId.systemDefault()

private fun zoned(epochMillis: Long): ZonedDateTime = Instant.ofEpochMilli(epochMillis).atZone(zone())

/** Calendar days from the day of [nowMillis] to the day of [epochMillis]; negative if earlier. */
internal fun dayOffset(epochMillis: Long, nowMillis: Long): Int =
    ChronoUnit.DAYS.between(zoned(nowMillis).toLocalDate(), zoned(epochMillis).toLocalDate()).toInt()

/** Hour of day (0 to 23) of that moment in the device time zone. */
internal fun hourOf(epochMillis: Long): Int = zoned(epochMillis).hour

/** Minute (0 to 59) of that moment in the device time zone. */
internal fun minuteOf(epochMillis: Long): Int = zoned(epochMillis).minute

/** The Material 3 date picker speaks in UTC midnight; this is the value for that local day. */
internal fun toPickerMillis(epochMillis: Long): Long =
    zoned(epochMillis).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** Combines the day the date picker returned (UTC midnight) with a local time of day. */
internal fun fromPickerMillis(pickerMillis: Long, hour: Int, minute: Int): Long =
    Instant.ofEpochMilli(pickerMillis)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
        .atTime(hour, minute)
        .atZone(zone())
        .toInstant()
        .toEpochMilli()

/** 9:00 AM today when that is still ahead, otherwise 9:00 AM tomorrow. */
internal fun defaultDueMillis(nowMillis: Long = System.currentTimeMillis()): Long {
    val today = zoned(nowMillis).toLocalDate()
    val todayAtNine = today.atTime(DEFAULT_DUE_HOUR, DEFAULT_DUE_MINUTE).atZone(zone()).toInstant().toEpochMilli()
    if (todayAtNine > nowMillis) return todayAtNine
    return today.plusDays(1).atTime(DEFAULT_DUE_HOUR, DEFAULT_DUE_MINUTE).atZone(zone()).toInstant().toEpochMilli()
}

/** Puts a newly picked day on the existing time of day, or on 9:00 AM when there is none. */
internal fun applyPickedDate(existing: Long?, pickerMillis: Long): Long {
    val hour = if (existing != null) hourOf(existing) else DEFAULT_DUE_HOUR
    val minute = if (existing != null) minuteOf(existing) else DEFAULT_DUE_MINUTE
    return fromPickerMillis(pickerMillis, hour, minute)
}

/** Puts a newly picked time of day on the existing day (or on the default day when none). */
internal fun applyPickedTime(existing: Long?, hour: Int, minute: Int): Long {
    val base = existing ?: defaultDueMillis()
    return fromPickerMillis(toPickerMillis(base), hour, minute)
}

/** "Today, 5:30 PM", "Tomorrow, 9:00 AM", "Mon 5 Oct, 9:00 AM" (the year when not this year). */
internal fun formatTaskDue(dueAt: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val date = zoned(dueAt).toLocalDate()
    val dayText = when (dayOffset(dueAt, nowMillis)) {
        0 -> "Today"
        1 -> "Tomorrow"
        -1 -> "Yesterday"
        else -> {
            val thisYear = zoned(nowMillis).toLocalDate().year
            val base = "${DAYS_SHORT[date.dayOfWeek.value - 1]} ${date.dayOfMonth} ${MONTHS_SHORT[date.monthValue - 1]}"
            if (date.year == thisYear) base else "$base ${date.year}"
        }
    }
    return "$dayText, ${formatTime(dueAt)}"
}

/**
 * The next due time of a repeating task: one day, seven days or one calendar month after
 * [previousDue], and again and again until it lands after [nowMillis]. Every step is counted
 * from the original date, so a monthly task due on the 31st does not drift. Null for an
 * unknown rule.
 */
internal fun nextDueAt(previousDue: Long, rule: String, nowMillis: Long): Long? {
    val start = zoned(previousDue)
    var step = 1L
    while (step <= MAX_REPEAT_STEPS) {
        val candidate = when (rule) {
            REPEAT_DAILY -> start.plusDays(step)
            REPEAT_WEEKLY -> start.plusWeeks(step)
            REPEAT_MONTHLY -> start.plusMonths(step)
            else -> return null
        }
        val millis = candidate.toInstant().toEpochMilli()
        if (millis > nowMillis) return millis
        step++
    }
    return null
}

// ---- Grouping -------------------------------------------------------------------------------

/** The groups of the task lists, in the order they are shown. */
internal enum class TaskSection(val header: String) {
    Overdue("Overdue"),
    Today("Today"),
    Upcoming("Upcoming"),
    NoDate("No date"),
    Done("Done"),
}

/** The tasks of each [TaskSection], already sorted. */
internal data class TaskGroups(
    val overdue: List<TaskEntity> = emptyList(),
    val today: List<TaskEntity> = emptyList(),
    val upcoming: List<TaskEntity> = emptyList(),
    val noDate: List<TaskEntity> = emptyList(),
    val done: List<TaskEntity> = emptyList(),
)

/**
 * Which section a task belongs to at [nowMillis]. Sections follow calendar days: a task due
 * earlier today is still Today, and the moment midnight passes it becomes Overdue.
 */
internal fun sectionOf(task: TaskEntity, nowMillis: Long): TaskSection {
    if (task.isDone) return TaskSection.Done
    val due = task.dueAt ?: return TaskSection.NoDate
    val offset = dayOffset(due, nowMillis)
    return when {
        offset < 0 -> TaskSection.Overdue
        offset == 0 -> TaskSection.Today
        else -> TaskSection.Upcoming
    }
}

/** Priority (High first), then due time, then creation time. */
private val OPEN_ORDER: Comparator<TaskEntity> =
    compareBy<TaskEntity> { priorityRank(it.priority) }
        .thenBy { it.dueAt ?: Long.MAX_VALUE }
        .thenBy { it.createdAt }

/** Most recently finished first. */
private val DONE_ORDER: Comparator<TaskEntity> =
    compareByDescending<TaskEntity> { it.doneAt ?: it.updatedAt }
        .thenByDescending { it.createdAt }

/** Splits [tasks] into the sorted sections at [nowMillis]. */
internal fun groupTasks(tasks: List<TaskEntity>, nowMillis: Long): TaskGroups {
    val open = tasks.filter { !it.isDone }
    val bySection = open.groupBy { sectionOf(it, nowMillis) }
    return TaskGroups(
        overdue = (bySection[TaskSection.Overdue] ?: emptyList()).sortedWith(OPEN_ORDER),
        today = (bySection[TaskSection.Today] ?: emptyList()).sortedWith(OPEN_ORDER),
        upcoming = (bySection[TaskSection.Upcoming] ?: emptyList()).sortedWith(OPEN_ORDER),
        noDate = (bySection[TaskSection.NoDate] ?: emptyList()).sortedWith(OPEN_ORDER),
        done = tasks.filter { it.isDone }.sortedWith(DONE_ORDER),
    )
}

/** A plain-text copy of a task for the clipboard. */
internal fun taskPlainText(
    title: String,
    notes: String,
    dueAt: Long?,
    priority: String,
    repeatRule: String?,
    projectName: String?,
): String {
    val lines = mutableListOf<String>()
    lines += title.trim().ifEmpty { "Untitled task" }
    if (dueAt != null) lines += "Due: ${formatDateTime(dueAt)}"
    lines += "Priority: ${priorityLabel(priority)}"
    if (repeatRule != null && dueAt != null) lines += "Repeats: ${repeatLabel(repeatRule)}"
    if (!projectName.isNullOrBlank()) lines += "Project: ${projectName.trim()}"
    val header = lines.joinToString("\n")
    return if (notes.isBlank()) header else "$header\n\n${notes.trim()}"
}

// ---- Composables ----------------------------------------------------------------------------

/**
 * One task: a check circle, the title, a quiet line with the due time, repeat and priority,
 * and optionally the project name. Checking the circle fades the row out softly before it
 * moves to Done. Tap opens the task; the three dots open [actions].
 */
@Composable
internal fun TaskRow(
    task: TaskEntity,
    projectName: String?,
    actions: List<MenuAction>,
    onToggleDone: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val scope = rememberCoroutineScope()
    var completing by remember { mutableStateOf(false) }
    LaunchedEffect(task.isDone) { completing = false }
    val fade by animateFloatAsState(
        targetValue = if (completing) 0.4f else 1f,
        animationSpec = tween(durationMillis = 220),
        label = "taskFade",
    )
    val looksDone = task.isDone || completing
    val dueAt = task.dueAt
    val now = System.currentTimeMillis()
    val hasTitle = task.title.isNotBlank()
    val showPriority = task.priority == PRIORITY_HIGH || task.priority == PRIORITY_LOW
    val hasMeta = dueAt != null || task.repeatRule != null || showPriority

    NeriboCard(
        modifier = modifier
            .fillMaxWidth()
            .alpha(fade),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.xs, end = spacing.xs, top = spacing.xs, bottom = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeriboIconButton(
                icon = if (looksDone) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = if (task.isDone) "Mark as not done" else "Mark as done",
                onClick = {
                    if (task.isDone) {
                        onToggleDone()
                    } else if (!completing) {
                        completing = true
                        scope.launch {
                            delay(240L)
                            onToggleDone()
                        }
                    }
                },
                tint = if (looksDone) colors.primary else colors.onSurfaceVariant,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = spacing.sm),
            ) {
                Text(
                    text = if (hasTitle) task.title.trim() else "Untitled task",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (looksDone || !hasTitle) colors.onSurfaceVariant else colors.onSurface,
                    textDecoration = if (looksDone) TextDecoration.LineThrough else TextDecoration.None,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hasMeta) {
                    Row(
                        modifier = Modifier.padding(top = spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        if (dueAt != null) {
                            val isLate = !looksDone && dueAt < now
                            Text(
                                text = formatTaskDue(dueAt, now),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isLate) colors.error else colors.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        if (task.repeatRule != null) {
                            Icon(
                                imageVector = Icons.Outlined.Repeat,
                                contentDescription = "Repeats ${repeatLabel(task.repeatRule).lowercase()}",
                                modifier = Modifier.size(14.dp),
                                tint = colors.onSurfaceVariant,
                            )
                        }
                        if (task.priority == PRIORITY_HIGH) {
                            StatusBadge(text = "High", tone = BadgeTone.Warning)
                        } else if (task.priority == PRIORITY_LOW) {
                            StatusBadge(text = "Low", tone = BadgeTone.Neutral)
                        }
                    }
                }
                if (!projectName.isNullOrBlank()) {
                    Text(
                        text = projectName,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = spacing.xs),
                    )
                }
            }
            OverflowMenu(actions = actions)
        }
    }
}

/** A single-line field that creates a task when the keyboard's Done key (or the plus) is pressed. */
@Composable
internal fun QuickAddField(
    placeholder: String,
    onAdd: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val shape = MaterialTheme.shapes.medium
    var text by rememberSaveable { mutableStateOf("") }
    val submit: () -> Unit = {
        val clean = text.trim()
        if (clean.isNotEmpty()) {
            onAdd(clean)
            text = ""
        }
    }
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(colors.surfaceVariant, shape)
            .padding(start = spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it.replace('\n', ' ') },
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 12.dp),
            textStyle = textStyle,
            singleLine = true,
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = textStyle.copy(color = colors.onSurfaceVariant),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
        )
        if (text.isNotBlank()) {
            NeriboIconButton(
                icon = Icons.Outlined.Add,
                contentDescription = "Add task",
                onClick = submit,
            )
        } else {
            Spacer(modifier = Modifier.width(spacing.lg))
        }
    }
}

/** The tappable "Done  ·  3" header that expands and collapses the finished tasks. */
@Composable
internal fun DoneHeader(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "DONE  \u00B7  $count",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = if (expanded) "Collapse finished tasks" else "Show finished tasks",
            tint = colors.onSurfaceVariant,
        )
    }
}

/** Adds a section header (optionally) and one item per task to a lazy list. */
internal fun LazyListScope.taskSectionItems(
    section: TaskSection,
    tasks: List<TaskEntity>,
    showHeader: Boolean,
    itemContent: @Composable (TaskEntity) -> Unit,
) {
    if (tasks.isEmpty()) return
    if (showHeader) {
        item(key = "header-${section.name}") {
            SectionHeader(
                text = section.header,
                modifier = Modifier.padding(top = NeriboTheme.spacing.sm),
            )
        }
    }
    items(tasks, key = { task -> "${section.name}-${task.id}" }) { task ->
        itemContent(task)
    }
}

/** A bottom sheet to pick the project of a task, or "No project". */
@Composable
internal fun ProjectPickerSheet(
    projects: List<ProjectEntity>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    NeriboBottomSheet(onDismiss = onDismiss) {
        Text(
            text = "Project",
            style = MaterialTheme.typography.titleLarge,
            color = colors.onSurface,
            modifier = Modifier.padding(bottom = spacing.sm),
        )
        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
            item(key = "no-project") {
                ProjectPickerRow(
                    name = "No project",
                    selected = selectedId == null,
                    onClick = { onSelect(null) },
                )
            }
            items(projects, key = { project -> project.id }) { project ->
                ProjectPickerRow(
                    name = project.name.trim().ifEmpty { "Untitled project" },
                    selected = project.id == selectedId,
                    onClick = { onSelect(project.id) },
                )
            }
        }
    }
}

@Composable
private fun ProjectPickerRow(name: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) colors.primary else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = "Selected",
                tint = colors.primary,
            )
        }
    }
}

/**
 * A Material 3 date picker in a dialog. [initialPickerMillis] and the value passed to
 * [onConfirm] are in the picker's own form (midnight UTC of the chosen day); convert with
 * [toPickerMillis] and [applyPickedDate].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskDatePickerDialog(
    initialPickerMillis: Long,
    onConfirm: (pickerMillis: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialPickerMillis)
    val selected = pickerState.selectedDateMillis
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { if (selected != null) onConfirm(selected) else onDismiss() },
                enabled = selected != null,
            ) {
                Text(text = "OK", style = MaterialTheme.typography.labelLarge, color = colors.primary)
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
        tonalElevation = 0.dp,
    ) {
        DatePicker(state = pickerState)
    }
}

/** A Material 3 time picker (12-hour clock) in a dialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TaskTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val timeState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = false,
    )
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = colors.surface,
            tonalElevation = 0.dp,
            modifier = Modifier
                .width(IntrinsicSize.Min)
                .height(IntrinsicSize.Min),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Select time",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 20.dp),
                )
                TimePicker(state = timeState)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = "Cancel",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onConfirm(timeState.hour, timeState.minute) }) {
                        Text(
                            text = "OK",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.primary,
                        )
                    }
                }
            }
        }
    }
}

/** A text field with no border or fill, sitting straight on the paper background. */
@Composable
internal fun TaskBorderlessField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    textStyle: TextStyle,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    maxLines: Int = Int.MAX_VALUE,
) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        textStyle = textStyle,
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        maxLines = maxLines,
        decorationBox = { inner ->
            Box(modifier = Modifier.fillMaxWidth()) {
                if (value.text.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = textStyle.copy(color = colors.onSurfaceVariant),
                    )
                }
                inner()
            }
        },
    )
}
