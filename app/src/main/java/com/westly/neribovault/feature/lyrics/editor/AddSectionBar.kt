package com.westly.neribovault.feature.lyrics.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.feature.lyrics.engine.SectionType

/** The quick-add chips, in order: label and the type each one adds. "More..." opens the type sheet. */
private val QUICK_TYPES = listOf(
    "Verse" to SectionType.VERSE,
    "Chorus" to SectionType.CHORUS,
    "Pre-Chorus" to SectionType.PRE_CHORUS,
    "Bridge" to SectionType.BRIDGE,
)

private const val MORE_LABEL = "More..."

/** The row at the very end of the song: a caption and chips that append a new section. */
@Composable
fun AddSectionBar(
    onAdd: (SectionType) -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = NeriboTheme.spacing
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Add a section",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = spacing.xs),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            QUICK_TYPES.forEach { (label, type) ->
                NeriboChip(label = label, selected = false, onClick = { onAdd(type) })
            }
            NeriboChip(label = MORE_LABEL, selected = false, onClick = onMore)
        }
    }
}

/**
 * The same chips in a compact row pinned above the keyboard while a section is focused. They
 * insert the new section directly after the focused one.
 */
@Composable
fun AddSectionKeyboardRow(
    onAdd: (SectionType) -> Unit,
    onMore: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
    ) {
        NeriboDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = spacing.screen, vertical = spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            QUICK_TYPES.forEach { (label, type) ->
                NeriboChip(label = label, selected = false, onClick = { onAdd(type) })
            }
            NeriboChip(label = MORE_LABEL, selected = false, onClick = onMore)
        }
    }
}
