package com.westly.neribovault.feature.screenplays.editor

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.feature.screenplays.engine.BlockType

/** The element bar chips, in order: label and the block type each one sets. */
private val ELEMENTS = listOf(
    "Scene" to BlockType.SCENE_HEADING,
    "Action" to BlockType.ACTION,
    "Character" to BlockType.CHARACTER,
    "Paren" to BlockType.PARENTHETICAL,
    "Dialogue" to BlockType.DIALOGUE,
    "Transition" to BlockType.TRANSITION,
)

/**
 * The two rows pinned above the keyboard: suggestions for the focused block, and the element bar
 * that changes what the block is. A focused page break shows a delete button instead.
 */
@Composable
fun BottomPanel(
    focused: EditorBlock,
    previousType: BlockType?,
    suggestions: List<Suggestion>,
    onSuggestion: (Suggestion) -> Unit,
    onChangeType: (BlockType) -> Unit,
    onDeletePageBreak: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
    ) {
        NeriboDivider()
        if (focused.type == BlockType.PAGE_BREAK) {
            NeriboButton(
                text = "Delete page break",
                onClick = onDeletePageBreak,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.screen, vertical = spacing.md),
                style = ButtonStyle.Secondary,
                leadingIcon = Icons.Outlined.Delete,
            )
        } else {
            if (suggestions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = spacing.screen, vertical = spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    suggestions.forEach { suggestion ->
                        NeriboChip(
                            label = suggestion.label,
                            selected = false,
                            onClick = { onSuggestion(suggestion) },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = spacing.screen, vertical = spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                ELEMENTS.forEach { (label, type) ->
                    val enabled = EditorRules.isChipEnabled(type, previousType)
                    NeriboChip(
                        label = label,
                        selected = focused.type == type,
                        onClick = { if (enabled) onChangeType(type) },
                        modifier = Modifier
                            .alpha(if (enabled) 1f else 0.38f)
                            .semantics { if (!enabled) disabled() },
                    )
                }
            }
        }
    }
}
