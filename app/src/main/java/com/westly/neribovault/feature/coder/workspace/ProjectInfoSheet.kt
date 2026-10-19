package com.westly.neribovault.feature.coder.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.NeriboBottomSheet
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.core.util.formatDate
import com.westly.neribovault.data.local.entity.CoderProjectEntity
import com.westly.neribovault.feature.backup.formatBytes
import com.westly.neribovault.feature.coder.files.CoderEntry
import com.westly.neribovault.feature.coder.files.isCoderHiddenPath
import com.westly.neribovault.feature.coder.link.CoderLinkState
import com.westly.neribovault.feature.coder.templates.CoderTemplates
import com.westly.neribovault.feature.coder.zip.CoderZipRules

/** How many files the info sheet lists as the biggest. */
const val CODER_LARGEST_FILES = 5

/** The numbers on the info sheet: [fileCount] files of [totalBytes] together, and the [largest] ones, biggest first. */
data class CoderProjectStats(val fileCount: Int, val totalBytes: Long, val largest: List<CoderEntry>)

/** The numbers of a project from its [entries]. Folders and the app's own hidden `.coder` files are not counted. */
fun coderProjectStats(entries: List<CoderEntry>): CoderProjectStats {
    val files = entries.filter { !it.isDirectory && !isCoderHiddenPath(it.path) }
    val largest = files.sortedWith(compareByDescending<CoderEntry> { it.sizeBytes }.thenBy { it.path }).take(CODER_LARGEST_FILES)
    return CoderProjectStats(files.size, files.sumOf { it.sizeBytes }, largest)
}

/** What a project was started from, in words: the template's name, "Imported ZIP file", or "Not from a template". */
fun coderTemplateLabel(templateId: String): String =
    CoderTemplates.find(templateId)?.name ?: if (templateId == CoderZipRules.TEMPLATE_ID) "Imported ZIP file" else "Not from a template"

private const val SHEET_MAX_HEIGHT_FRACTION = 0.8f
private val INFO_LABEL_WIDTH = 112.dp

/**
 * Read-only facts about a project: its name, dates, file count and size, what it was started from, the GitHub
 * repository and Developer project it is linked to (only when it is), and its five biggest files. Everything comes
 * from what the screen already has; nothing is loaded and nothing goes over the network.
 */
@Composable
fun ProjectInfoSheet(
    project: CoderProjectEntity,
    entries: List<CoderEntry>,
    linkState: CoderLinkState,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val stats = coderProjectStats(entries)
    val owner = project.repoOwner
    val repo = project.repoName
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    NeriboBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = (screenHeightDp * SHEET_MAX_HEIGHT_FRACTION).dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(text = "Project info", style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
            Spacer(modifier = Modifier.height(spacing.md))
            InfoRow(label = "Name", value = project.name)
            InfoRow(label = "Created", value = formatDate(project.createdAt))
            InfoRow(label = "Last changed", value = formatDate(project.updatedAt))
            InfoRow(label = "Files", value = stats.fileCount.toString())
            InfoRow(label = "Size", value = formatBytes(stats.totalBytes))
            InfoRow(label = "Started from", value = coderTemplateLabel(project.templateId))
            if (!owner.isNullOrEmpty() && !repo.isNullOrEmpty()) {
                InfoRow(label = "GitHub", value = "$owner/$repo")
            }
            if (linkState is CoderLinkState.Linked) {
                InfoRow(label = "Developer project", value = linkState.name)
            }
            if (stats.largest.isNotEmpty()) {
                Spacer(modifier = Modifier.height(spacing.lg))
                SectionHeader(text = "Biggest files")
                Spacer(modifier = Modifier.height(spacing.xs))
                for (file in stats.largest) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = file.path,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = formatBytes(file.sizeBytes),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(start = spacing.sm),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(spacing.lg))
            NeriboButton(
                text = "Close",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Secondary,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = spacing.xs)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.width(INFO_LABEL_WIDTH),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}
