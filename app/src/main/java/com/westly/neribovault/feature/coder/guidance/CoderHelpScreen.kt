package com.westly.neribovault.feature.coder.guidance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar

/**
 * The Help screen: eight short guides, one open at a time, and a Glossary row that opens the list of terms.
 * It is always available, also in Pro mode. Back closes the Glossary first and then leaves the screen.
 */
@Composable
fun CoderHelpScreen(onBack: () -> Unit) {
    var showGlossary by rememberSaveable { mutableStateOf(false) }
    var openGuide by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = showGlossary) { showGlossary = false }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = if (showGlossary) "Glossary" else "Help",
                onBack = { if (showGlossary) showGlossary = false else onBack() },
            )
        },
    ) { padding ->
        val spacing = NeriboTheme.spacing
        // A new list for each page, so the Glossary always starts at its top.
        key(showGlossary) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(
                    start = spacing.screen,
                    end = spacing.screen,
                    top = spacing.sm,
                    bottom = spacing.xl,
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                if (showGlossary) {
                    items(CoderGlossary.terms, key = { it.id }) { term -> GlossaryRow(term) }
                } else {
                    items(CoderGuides.all, key = { it.id }) { guide ->
                        GuideRow(
                            guide = guide,
                            expanded = openGuide == guide.id,
                            onToggle = { openGuide = if (openGuide == guide.id) null else guide.id },
                        )
                    }
                    item(key = "glossary") { GlossaryEntryRow(onClick = { showGlossary = true }) }
                }
            }
        }
    }
}

/** One guide: its title, and its numbered steps while it is open. */
@Composable
private fun GuideRow(guide: Guide, expanded: Boolean, onToggle: () -> Unit) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    NeriboCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { stateDescription = if (expanded) "Open" else "Closed" },
        onClick = onToggle,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(start = spacing.lg, end = spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = guide.title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
                modifier = Modifier.weight(1f).padding(vertical = spacing.sm),
            )
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = spacing.lg, end = spacing.lg, bottom = spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                guide.steps.forEachIndexed { index, step ->
                    Text(
                        text = "${index + 1}. $step",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The row under the guides that opens the Glossary. */
@Composable
private fun GlossaryEntryRow(onClick: () -> Unit) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    NeriboCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(start = spacing.lg, end = spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Glossary",
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
                modifier = Modifier.weight(1f).padding(vertical = spacing.sm),
            )
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
            )
        }
    }
}

/** One glossary term with its explanation. */
@Composable
private fun GlossaryRow(term: GlossaryTerm) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    NeriboCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                text = term.title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
            )
            Text(
                text = term.explanation,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}
