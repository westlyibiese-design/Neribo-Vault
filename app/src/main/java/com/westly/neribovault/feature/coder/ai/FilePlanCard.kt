package com.westly.neribovault.feature.coder.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboCard

private val PLAN_CODE_FONT_SIZE = 12.sp
private val PLAN_CODE_LINE_HEIGHT = 17.sp
private const val MAX_PLAN_CODE_LINES = 300

/**
 * The card under an answer that wrote several files. It lists every file with its tag (New or Replaces), its size and
 * a checkbox (a file that can't be applied is unchecked, disabled and says why); tapping a row shows or hides its code.
 * [onApply] asks to apply the checked files (the screen confirms first), [onCopyAll] copies every finished file,
 * [onUndo] undoes the build once it is applied. The checkboxes and buttons only work while the plan is waiting for a
 * choice; the result line is a polite live region, so a screen reader announces it.
 */
@Composable
fun FilePlanCard(
    plan: CoderFilePlan,
    onToggle: (String) -> Unit,
    onApply: () -> Unit,
    onCopyAll: () -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val idle = plan.phase == CoderPlanPhase.Idle
    val selectedCount = CoderApplyPlan.selectedCount(plan)
    NeriboCard(modifier = modifier.fillMaxWidth()) {
        Text(
            text = CoderApplyPlan.headerText(plan.rows.size),
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurface,
            modifier = Modifier.padding(start = spacing.md, end = spacing.md, top = spacing.md, bottom = spacing.xs),
        )
        plan.rows.forEachIndexed { index, row ->
            PlanFileRow(
                row = row,
                checked = row.canApply && row.path in plan.selected,
                interactive = idle,
                onToggle = { onToggle(row.path) },
                index = index,
            )
        }
        val note = plan.note
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurface,
                modifier = Modifier
                    .padding(horizontal = spacing.md, vertical = spacing.sm)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        when (plan.phase) {
            CoderPlanPhase.Idle -> Row(
                modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NeriboButton(text = "Apply selected ($selectedCount)", onClick = onApply, enabled = selectedCount > 0)
                NeriboButton(text = "Copy all", onClick = onCopyAll, style = ButtonStyle.Secondary)
            }
            CoderPlanPhase.Working -> Row(
                modifier = Modifier
                    .padding(horizontal = spacing.md, vertical = spacing.md)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.primary)
                Text(text = "Working\u2026", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            is CoderPlanPhase.Applied -> Row(
                modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NeriboButton(text = "Undo", onClick = onUndo, style = ButtonStyle.Secondary)
                NeriboButton(text = "Copy all", onClick = onCopyAll, style = ButtonStyle.Text)
            }
        }
    }
}

/** One file of the plan: checkbox, path, tag and size, the reason when it is refused, and its code when expanded. */
@Composable
private fun PlanFileRow(
    row: CoderPlanRow,
    checked: Boolean,
    interactive: Boolean,
    onToggle: () -> Unit,
    index: Int,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    var expanded by rememberSaveable(row.path, index) { mutableStateOf(false) }
    val tag = CoderApplyPlan.statusLabel(row.status) + " \u00B7 " + CoderApplyPlan.sizeText(row.sizeBytes)
    val reason = row.reason
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
                enabled = interactive && row.canApply,
                modifier = Modifier.semantics { contentDescription = "Apply " + row.path },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clickable(onClickLabel = if (expanded) "Hide the code" else "Show the code") { expanded = !expanded }
                    .padding(vertical = spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.xxs),
            ) {
                Text(
                    text = row.path,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (row.canApply) colors.onSurface else colors.onSurfaceVariant,
                )
                Text(text = tag, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                if (reason != null) {
                    Text(text = reason, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.sm),
            )
        }
        if (expanded) PlanCode(row.code)
    }
}

/** The code of one row, in a scrolling box; very long files show their first lines only. */
@Composable
private fun PlanCode(code: String) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val lines = remember(code) { code.split('\n') }
    val shown = remember(code) { if (lines.size > MAX_PLAN_CODE_LINES) lines.take(MAX_PLAN_CODE_LINES).joinToString("\n") else code }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.sm, vertical = spacing.xs)
            .clip(MaterialTheme.shapes.small)
            .background(colors.surfaceVariant)
            .horizontalScroll(rememberScrollState())
            .padding(spacing.sm),
    ) {
        Text(
            text = shown,
            color = colors.onSurface,
            fontFamily = FontFamily.Monospace,
            fontSize = PLAN_CODE_FONT_SIZE,
            lineHeight = PLAN_CODE_LINE_HEIGHT,
            softWrap = false,
        )
    }
    if (lines.size > MAX_PLAN_CODE_LINES) {
        Text(
            text = "Showing the first $MAX_PLAN_CODE_LINES of ${lines.size} lines. Applying writes the whole file.",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.xs),
        )
    }
}
