package com.westly.neribovault.feature.lyrics.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.SectionHeader

private const val OUTLIER_DESCRIPTION = "Much longer or shorter than the other lines in this section"

/** Estimated syllables for every line, grouped by section, with outlier lines marked by a dot. */
@Composable
internal fun SyllablesTab(sections: List<SyllableSection>, modifier: Modifier = Modifier) {
    val spacing = NeriboTheme.spacing
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = spacing.screen,
            end = spacing.screen,
            top = spacing.lg,
            bottom = spacing.xxl,
        ),
    ) {
        item(key = "caption") {
            Text(
                text = "Estimated syllables per line. A dot marks a line much longer or shorter " +
                    "than the rest of its section.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        sections.forEachIndexed { sectionIndex, section ->
            item(key = "header-$sectionIndex") {
                SectionTitleRow(section = section, isFirst = sectionIndex == 0)
            }
            itemsIndexed(
                items = section.lines,
                key = { lineIndex, _ -> "line-$sectionIndex-$lineIndex" },
            ) { _, line ->
                SyllableLineRow(line)
            }
        }
    }
}

@Composable
private fun SectionTitleRow(section: SyllableSection, isFirst: Boolean) {
    val spacing = NeriboTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (isFirst) spacing.lg else spacing.xl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionHeader(text = section.label, modifier = Modifier.weight(1f))
        Text(
            text = "avg ${section.average}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    NeriboDivider(modifier = Modifier.padding(top = spacing.xs))
}

@Composable
private fun SyllableLineRow(line: SyllableLine) {
    val spacing = NeriboTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Box(modifier = Modifier.width(16.dp).padding(top = 9.dp)) {
            if (line.isOutlier) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .semantics { contentDescription = OUTLIER_DESCRIPTION },
                )
            }
        }
        Text(
            text = line.text,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Serif,
                fontSize = 15.sp,
                lineHeight = 22.sp,
            ),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(spacing.md))
        Text(
            text = line.count.toString(),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 22.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
