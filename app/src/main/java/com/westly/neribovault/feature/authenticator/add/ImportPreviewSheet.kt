package com.westly.neribovault.feature.authenticator.add

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.NeriboBottomSheet
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.feature.authenticator.engine.OtpAlgorithm
import com.westly.neribovault.feature.authenticator.engine.OtpEntry

/**
 * Lists every account found in a link or export code so the owner can choose what to add.
 * Only the service and account names are shown; secret keys are never drawn.
 */
@Composable
fun ImportPreviewSheet(
    preview: ImportPreview,
    checked: Set<Int>,
    isBusy: Boolean,
    message: String?,
    onToggle: (Int) -> Unit,
    onToggleAll: () -> Unit,
    onCancel: () -> Unit,
    onAdd: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val count = preview.rows.size
    val selectable = preview.rows.indices.filter { !preview.rows[it].isDuplicate }
    val allChecked = selectable.isNotEmpty() && checked.containsAll(selectable)
    val checkedCount = checked.count { it in selectable }

    NeriboBottomSheet(onDismiss = onCancel) {
        Text(
            text = if (count == 1) "Add 1 account" else "Add $count accounts",
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif),
            color = colors.onSurface,
            modifier = Modifier.padding(bottom = spacing.sm),
        )
        if (selectable.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onToggleAll),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = allChecked,
                    onCheckedChange = null,
                    colors = CheckboxDefaults.colors(checkedColor = colors.primary),
                )
                Text(
                    text = "Select all",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurface,
                    modifier = Modifier.padding(start = spacing.md),
                )
            }
            NeriboDivider()
        }
        LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
            itemsIndexed(preview.rows) { index, row ->
                val enabled = !row.isDuplicate
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable(enabled = enabled) { onToggle(index) }
                        .padding(vertical = spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = enabled && index in checked,
                        onCheckedChange = null,
                        enabled = enabled,
                        colors = CheckboxDefaults.colors(checkedColor = colors.primary),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = spacing.md),
                    ) {
                        val title = rowTitle(row.entry)
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                            color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (row.entry.issuer.isNotBlank() && row.entry.accountName.isNotBlank()) {
                            Text(
                                text = row.entry.accountName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val details = detailLine(row.entry)
                        if (details != null) {
                            Text(
                                text = details,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                    if (row.isDuplicate) {
                        Text(
                            text = "Already added",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(start = spacing.sm),
                        )
                    }
                }
            }
        }
        if (preview.skippedCounterBased > 0 || preview.skippedUnsupported > 0 || message != null) {
            Column(
                modifier = Modifier.padding(top = spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                if (preview.skippedCounterBased > 0) {
                    val n = preview.skippedCounterBased
                    Text(
                        text = if (n == 1) {
                            "1 counter-based account can't be imported."
                        } else {
                            "$n counter-based accounts can't be imported."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                if (preview.skippedUnsupported > 0) {
                    val n = preview.skippedUnsupported
                    Text(
                        text = if (n == 1) {
                            "1 account uses a format that is not supported."
                        } else {
                            "$n accounts use a format that is not supported."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                if (message != null) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error,
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            NeriboButton(
                text = "Cancel",
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                style = ButtonStyle.Secondary,
            )
            NeriboButton(
                text = if (checkedCount == 1) "Add 1 account" else "Add $checkedCount accounts",
                onClick = onAdd,
                modifier = Modifier.weight(1f),
                enabled = checkedCount > 0 && !isBusy,
            )
        }
    }
}

private fun rowTitle(entry: OtpEntry): String = when {
    entry.issuer.isNotBlank() -> entry.issuer
    entry.accountName.isNotBlank() -> entry.accountName
    else -> "Unnamed account"
}

/** A quiet line with only the settings that are not the defaults, or null when all are default. */
private fun detailLine(entry: OtpEntry): String? {
    val parts = ArrayList<String>()
    if (entry.digits != 6) parts.add("${entry.digits} digits")
    if (entry.algorithm != OtpAlgorithm.SHA1) parts.add(entry.algorithm.name)
    if (entry.periodSeconds != 30) parts.add("${entry.periodSeconds} s")
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}
