package com.westly.neribovault.feature.church.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.NeriboBottomSheet
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.feature.church.SpeakerCount

/**
 * A bottom sheet listing every speaker with how many records they have. Tapping one filters the
 * list by that speaker; [onClear] removes the filter.
 */
@Composable
fun SpeakersSheet(
    speakers: List<SpeakerCount>,
    selected: String?,
    onSelect: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    NeriboBottomSheet(onDismiss = onDismiss) {
        Text(
            text = "Speakers",
            style = MaterialTheme.typography.titleLarge,
            color = colors.onSurface,
            modifier = Modifier.padding(bottom = spacing.sm),
        )
        if (speakers.isEmpty()) {
            Text(
                text = "Speakers you add to your records will appear here, so you can find " +
                    "everything you have heard from one person.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(vertical = spacing.md),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                speakers.forEach { speaker ->
                    val isSelected = selected != null && speaker.name.equals(selected, ignoreCase = true)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { onSelect(speaker.name) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = speaker.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isSelected) colors.primary else colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = speaker.count.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(start = spacing.md),
                        )
                    }
                }
            }
        }
        if (selected != null) {
            NeriboButton(
                text = "Show all speakers",
                onClick = onClear,
                modifier = Modifier.padding(top = spacing.sm),
                style = ButtonStyle.Secondary,
            )
        }
    }
}
