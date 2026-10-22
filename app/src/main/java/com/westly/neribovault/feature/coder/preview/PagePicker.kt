package com.westly.neribovault.feature.coder.preview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.westly.neribovault.feature.coder.files.CoderEntry
import com.westly.neribovault.feature.coder.files.CoderPaths

/** The most pages the picker lists. */
const val MAX_PICKER_PAGES = 50

/**
 * What the person picked in the preview, kept while the screen is open: the page to show ([page], or null to follow
 * the open file). The workspace screen holds one so the choice survives turning the preview off and on and rotating
 * the phone.
 */
class CoderPreviewSession(page: String? = null) {
    var page: String? by mutableStateOf(page)

    companion object {
        /** Saves the choice as one string so it survives rotation and a killed process. The last saved item is the page. */
        val Saver: Saver<CoderPreviewSession, Any> = listSaver(
            save = { listOf(it.page.orEmpty()) },
            restore = { CoderPreviewSession(it.last().ifEmpty { null }) },
        )
    }
}

/**
 * The HTML pages of the project as paths under the preview root [rootPrefix] ("" or a folder name), `index.html`
 * first and the rest sorted by name ignoring case. Pages in hidden or blocked places are left out, and so are
 * pages outside the root folder. At most [MAX_PICKER_PAGES] are returned.
 */
fun htmlPagesOf(entries: List<CoderEntry>, rootPrefix: String): List<String> {
    val prefix = if (rootPrefix.isEmpty()) "" else "$rootPrefix/"
    val pages = ArrayList<String>()
    for (entry in entries) {
        if (entry.isDirectory || !entry.path.startsWith(prefix)) continue
        val relative = entry.path.removePrefix(prefix)
        val extension = CoderPaths.nameOf(relative).substringAfterLast('.', "").lowercase()
        if (extension != "html" && extension != "htm") continue
        if (CoderServedPaths.isBlocked(entry.path) || CoderServedPaths.isBlocked(relative)) continue
        pages.add(relative)
    }
    val sorted = pages.sortedWith(compareBy<String>({ it != "index.html" }, { it.lowercase() }))
    return sorted.take(MAX_PICKER_PAGES)
}

/**
 * A small menu button that shows the page being previewed and lets the person pick another one. [pages] is the
 * list from [htmlPagesOf]; with one page or none it shows nothing. [current] is the path of the page shown.
 */
@Composable
fun PagePicker(pages: List<String>, current: String, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    if (pages.size < 2) return
    var expanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val name = CoderPaths.nameOf(current)
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(MaterialTheme.shapes.small)
                .semantics { contentDescription = "Choose page, now $name" }
                .clickable(role = Role.Button) { expanded = true }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp),
            )
            Icon(imageVector = Icons.Outlined.ExpandMore, contentDescription = null, tint = colors.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (page in pages) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = page,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (page == current) colors.primary else colors.onSurface,
                        )
                    },
                    onClick = {
                        expanded = false
                        onPick(page)
                    },
                )
            }
        }
    }
}
