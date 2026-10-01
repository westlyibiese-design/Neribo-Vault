package com.westly.neribovault.feature.developer.plans

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.ui.components.StatusBadge
import com.westly.neribovault.core.util.formatRelative
import com.westly.neribovault.core.util.snippet
import com.westly.neribovault.data.local.entity.FolderPlanEntity
import com.westly.neribovault.data.local.entity.PlanningDocEntity

/** The route argument value that means "create a new item". */
internal const val PLAN_NEW_ID = "new"

/** The route value that means "no project"; it never reaches these screens, but be safe. */
internal const val PLAN_NO_PROJECT = "none"

internal const val PLAN_KIND_FEATURE = "feature"
internal const val PLAN_KIND_ROADMAP = "roadmap"
internal const val PLAN_KIND_ARCHITECTURE = "architecture"
internal const val PLAN_KIND_OTHER = "other"

/** Every planning document kind, in the order the editor offers them. */
internal val PLAN_KINDS: List<String> =
    listOf(PLAN_KIND_FEATURE, PLAN_KIND_ROADMAP, PLAN_KIND_ARCHITECTURE, PLAN_KIND_OTHER)

/** Display name of a planning document kind. Unknown values read as Other. */
internal fun planKindLabel(kind: String): String = when (kind) {
    PLAN_KIND_FEATURE -> "Feature"
    PLAN_KIND_ROADMAP -> "Roadmap"
    PLAN_KIND_ARCHITECTURE -> "Architecture"
    else -> "Other"
}

/** The title given to a duplicate. */
internal fun copyTitle(title: String): String {
    val trimmed = title.trim()
    return if (trimmed.isEmpty()) "Untitled copy" else "$trimmed (copy)"
}

/** Plain text for Copy and Share: the title, a blank line, then the body. */
internal fun planPlainText(title: String, body: String): String {
    val heading = title.trim()
    val text = body.trim()
    return when {
        heading.isEmpty() -> text
        text.isEmpty() -> heading
        else -> heading + "\n\n" + text
    }
}

// ---------------------------------------------------------------------------------------------
// Checklist lines: "- [ ] task" and "- [x] task"
// ---------------------------------------------------------------------------------------------

private val CHECKLIST_LINE = Regex("^(\\s*)- \\[([ xX])\\]\\s?(.*)\$")

/** One parsed checklist line. */
internal data class ChecklistLine(val indent: Int, val checked: Boolean, val text: String)

/** How many checklist lines a body has, and how many of them are ticked. */
internal data class ChecklistProgress(val done: Int, val total: Int)

/** Parses [line] as a checklist line, or returns null when it is not one. */
internal fun parseChecklistLine(line: String): ChecklistLine? {
    val match = CHECKLIST_LINE.find(line) ?: return null
    val indent = match.groupValues[1].length
    val checked = match.groupValues[2].equals("x", ignoreCase = true)
    return ChecklistLine(indent = indent, checked = checked, text = match.groupValues[3])
}

/** Counts the checklist lines of [body] and how many are done. */
internal fun checklistProgress(body: String): ChecklistProgress {
    var done = 0
    var total = 0
    body.lineSequence().forEach { line ->
        val item = parseChecklistLine(line)
        if (item != null) {
            total++
            if (item.checked) done++
        }
    }
    return ChecklistProgress(done = done, total = total)
}

/**
 * Flips the box on line [lineIndex] (counting from 0, split on newlines) and leaves every other
 * character of [body] untouched. Returns [body] unchanged when that line is not a checklist line.
 */
internal fun toggleChecklistLine(body: String, lineIndex: Int): String {
    val lines = body.split("\n").toMutableList()
    if (lineIndex !in lines.indices) return body
    val line = lines[lineIndex]
    val match = CHECKLIST_LINE.find(line) ?: return body
    val box = match.groups[2] ?: return body
    val replacement = if (box.value == " ") "x" else " "
    lines[lineIndex] = line.substring(0, box.range.first) + replacement +
        line.substring(box.range.last + 1)
    return lines.joinToString("\n")
}

// ---------------------------------------------------------------------------------------------
// Cursor helpers for the outline editor's little button row
// ---------------------------------------------------------------------------------------------

private const val INDENT_UNIT = "  "

private fun lineStart(text: String, cursor: Int): Int =
    text.lastIndexOf('\n', cursor.coerceIn(0, text.length) - 1) + 1

private fun lineEnd(text: String, cursor: Int): Int {
    val end = text.indexOf('\n', cursor.coerceIn(0, text.length))
    return if (end < 0) text.length else end
}

/** Adds one level of indentation to the line the cursor is on. */
internal fun indentLineAtCursor(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val start = lineStart(text, value.selection.min)
    val updated = text.substring(0, start) + INDENT_UNIT + text.substring(start)
    return TextFieldValue(
        text = updated,
        selection = TextRange(
            value.selection.start + INDENT_UNIT.length,
            value.selection.end + INDENT_UNIT.length,
        ),
    )
}

/** Removes one level of indentation from the line the cursor is on, if it has any. */
internal fun outdentLineAtCursor(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val start = lineStart(text, value.selection.min)
    val end = lineEnd(text, value.selection.min)
    val line = text.substring(start, end)
    val remove = when {
        line.startsWith("\t") -> 1
        line.startsWith(INDENT_UNIT) -> INDENT_UNIT.length
        line.startsWith(" ") -> 1
        else -> 0
    }
    if (remove == 0) return value
    val updated = text.removeRange(start, start + remove)
    return TextFieldValue(
        text = updated,
        selection = TextRange(
            (value.selection.start - remove).coerceAtLeast(start),
            (value.selection.end - remove).coerceAtLeast(start),
        ),
    )
}

/**
 * Marks the current line as a folder (adds a trailing slash) and starts a new line inside it.
 * Does nothing on a blank line.
 */
internal fun newFolderAtCursor(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val start = lineStart(text, value.selection.min)
    val end = lineEnd(text, value.selection.min)
    val line = text.substring(start, end)
    if (line.isBlank()) return value
    val trimmed = line.trimEnd()
    val indent = line.substring(0, line.length - line.trimStart().length)
    val folderLine = if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    val addition = "\n" + indent + INDENT_UNIT
    val updated = text.substring(0, start) + folderLine + addition + text.substring(end)
    val cursor = start + folderLine.length + addition.length
    return TextFieldValue(text = updated, selection = TextRange(cursor))
}

/**
 * Starts a new line for a file: inside the current line when that is a folder, beside it when it
 * is a file. Does nothing on a blank line.
 */
internal fun newFileAtCursor(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val start = lineStart(text, value.selection.min)
    val end = lineEnd(text, value.selection.min)
    val line = text.substring(start, end)
    if (line.isBlank()) return value
    val trimmed = line.trimEnd()
    val indent = line.substring(0, line.length - line.trimStart().length)
    val newIndent = if (trimmed.endsWith("/")) indent + INDENT_UNIT else indent
    val updated = text.substring(0, start) + trimmed + "\n" + newIndent + text.substring(end)
    val cursor = start + trimmed.length + 1 + newIndent.length
    return TextFieldValue(text = updated, selection = TextRange(cursor))
}

// ---------------------------------------------------------------------------------------------
// Shared composables
// ---------------------------------------------------------------------------------------------

/** A text field with no border or fill, sitting straight on the paper background. */
@Composable
internal fun PlanBorderlessField(
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

/** A slim line at the bottom of an editor, above the keyboard. */
@Composable
internal fun PlanEditorFooter(text: String) {
    val spacing = NeriboTheme.spacing
    NeriboDivider()
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.screen, vertical = spacing.sm),
    )
}

/** The monospace style used for outlines and trees. */
@Composable
internal fun planMonoStyle(): TextStyle =
    MaterialTheme.typography.bodyMedium.copy(
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onBackground,
    )

/**
 * The body as a checklist: `- [ ]` and `- [x]` lines become real checkboxes, every other line is
 * plain text. Tapping a box calls [onToggle] with that line's index.
 */
@Composable
internal fun ChecklistView(
    title: String,
    body: String,
    onToggle: (lineIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val lines = remember(body) { body.split("\n") }
    val hasChecklist = remember(body) { checklistProgress(body).total > 0 }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = spacing.screen,
            end = spacing.screen,
            top = spacing.sm,
            bottom = spacing.xxl,
        ),
    ) {
        if (title.isNotBlank()) {
            item(key = "title") {
                Text(
                    text = title.trim(),
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onBackground,
                    modifier = Modifier.padding(bottom = spacing.md),
                )
            }
        }
        if (!hasChecklist) {
            item(key = "hint") {
                Text(
                    text = "No checklist lines yet. Start a line with - [ ] and it shows up here as a box you can tick.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = spacing.md),
                )
            }
        }
        itemsIndexed(lines) { index, line ->
            ChecklistLineRow(index = index, line = line, onToggle = onToggle)
        }
    }
}

@Composable
private fun ChecklistLineRow(index: Int, line: String, onToggle: (Int) -> Unit) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val item = parseChecklistLine(line)
    when {
        item != null -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(start = (item.indent.coerceAtMost(16) * 6).dp)
                .toggleable(
                    value = item.checked,
                    role = Role.Checkbox,
                    onValueChange = { onToggle(index) },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = item.checked, onCheckedChange = null)
            Text(
                text = item.text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (item.checked) colors.onSurfaceVariant else colors.onBackground,
                textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                modifier = Modifier.padding(start = spacing.sm),
            )
        }
        line.isBlank() -> Spacer(modifier = Modifier.height(spacing.md))
        else -> Text(
            text = line,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onBackground,
            modifier = Modifier.padding(vertical = spacing.xs),
        )
    }
}

/**
 * One planning document in a list: title, kind, a two-line preview and when it last changed.
 * Tap opens it; the three dots open [actions].
 */
@Composable
internal fun PlanningDocCard(
    doc: PlanningDocEntity,
    actions: List<MenuAction>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val hasTitle = doc.title.isNotBlank()
    val preview = snippet(doc.body, 160)
    NeriboCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.lg, top = spacing.xs, end = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (hasTitle) doc.title.trim() else "Untitled plan",
                style = MaterialTheme.typography.titleMedium,
                color = if (hasTitle) colors.onSurface else colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = spacing.sm),
            )
            OverflowMenu(actions = actions)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            StatusBadge(text = planKindLabel(doc.kind))
            Text(
                text = formatRelative(doc.updatedAt),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (preview.isNotEmpty()) {
            Text(
                text = preview,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = spacing.lg, end = spacing.lg, top = spacing.sm),
            )
        }
        Spacer(modifier = Modifier.size(spacing.lg))
    }
}

/** The first three lines of a folder plan's tree, for its card. */
internal fun treePreview(outline: String): String =
    renderTree(parseOutline(outline)).lines().take(3).joinToString("\n")

/**
 * One folder plan in a list: title, the first three lines of its tree in monospace and when it
 * last changed. Tap opens it; the three dots open [actions].
 */
@Composable
internal fun FolderPlanCard(
    plan: FolderPlanEntity,
    actions: List<MenuAction>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val hasTitle = plan.title.isNotBlank()
    val preview = remember(plan.treeText) { treePreview(plan.treeText) }
    NeriboCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.lg, top = spacing.xs, end = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (hasTitle) plan.title.trim() else "Untitled folder plan",
                style = MaterialTheme.typography.titleMedium,
                color = if (hasTitle) colors.onSurface else colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = spacing.sm),
            )
            OverflowMenu(actions = actions)
        }
        if (preview.isNotEmpty()) {
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = colors.onSurfaceVariant,
                maxLines = 3,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = spacing.lg, end = spacing.lg),
            )
        }
        Text(
            text = formatRelative(plan.updatedAt),
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(start = spacing.lg, end = spacing.lg, top = spacing.sm),
        )
        Spacer(modifier = Modifier.size(spacing.lg))
    }
}
