package com.westly.neribovault.feature.coder.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboSearchField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.feature.coder.files.CoderPaths
import com.westly.neribovault.feature.coder.files.CoderProjectSearch
import com.westly.neribovault.feature.coder.files.SearchHit
import com.westly.neribovault.feature.coder.files.SearchResults

/**
 * The project search: the query, the two switches and what was found. [results] is null until a search has finished
 * (or when the query is too short); [searching] is true from the moment a search is waiting or running.
 */
data class ProjectSearchState(
    val open: Boolean = false,
    val query: String = "",
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val searching: Boolean = false,
    val results: SearchResults? = null,
)

/**
 * A request for the editor to scroll to [offset] in the file [path]. [tick] goes up with every request (0 means none),
 * so asking for the same place twice still scrolls twice.
 */
data class CoderJump(val path: String = "", val tick: Int = 0, val offset: Int = 0)

/** One line of the result list. */
private sealed interface SearchRow {
    data class Header(val text: String) : SearchRow
    data class FileName(val path: String) : SearchRow
    data class FileTitle(val path: String, val count: Int) : SearchRow
    data class Hit(val path: String, val hit: SearchHit) : SearchRow
    data class Note(val text: String) : SearchRow
}

private fun rowsOf(results: SearchResults?): List<SearchRow> {
    if (results == null) return emptyList()
    val rows = ArrayList<SearchRow>()
    if (results.nameMatches.isNotEmpty()) {
        rows.add(SearchRow.Header("File names"))
        for (path in results.nameMatches) rows.add(SearchRow.FileName(path))
    }
    if (results.files.isNotEmpty()) {
        rows.add(SearchRow.Header("Inside files"))
        for (file in results.files) {
            rows.add(SearchRow.FileTitle(file.path, file.hits.size))
            for (hit in file.hits) rows.add(SearchRow.Hit(file.path, hit))
        }
    }
    if (results.truncated) rows.add(SearchRow.Note("Some matches are not shown. Type a longer search to narrow it down."))
    return rows
}

/**
 * Full-screen search across every file of the project. It only shows [state] and reports taps; the search itself runs in
 * the ViewModel. A tap on a result calls [onOpenResult] with the file and the line (1 for a file name match).
 */
@Composable
fun ProjectSearchPanel(
    state: ProjectSearchState,
    onQueryChange: (String) -> Unit,
    onToggleCase: () -> Unit,
    onToggleWholeWord: () -> Unit,
    onOpenResult: (path: String, line: Int) -> Unit,
    onBack: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val focusRequester = remember { FocusRequester() }
    // The box keeps its own text so fast typing is never overwritten by a slightly older value from the ViewModel.
    var text by rememberSaveable { mutableStateOf(state.query) }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val rows = remember(state.results) { rowsOf(state.results) }
    val tooShort = !CoderProjectSearch.isQueryAllowed(state.query)
    val results = state.results
    val status = when {
        tooShort -> "Type at least 2 characters."
        state.searching -> "Searching…"
        results == null -> ""
        results.totalCount == 0 -> "No results"
        results.totalCount == 1 -> "1 result"
        else -> "${results.totalCount} results"
    }

    NeriboScaffold(topBar = { NeriboTopBar(title = "Search", onBack = onBack) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.padding(horizontal = spacing.screen)) {
                NeriboSearchField(
                    query = text,
                    onQueryChange = {
                        text = it
                        onQueryChange(it)
                    },
                    modifier = Modifier.focusRequester(focusRequester),
                    placeholder = "Search in all files",
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f).padding(start = spacing.sm),
                    )
                    NeriboChip(
                        label = "Aa",
                        selected = state.caseSensitive,
                        onClick = onToggleCase,
                        modifier = Modifier.semantics { contentDescription = "Match upper and lower case" },
                    )
                    Spacer(modifier = Modifier.width(spacing.xs))
                    NeriboChip(
                        label = "ab",
                        selected = state.wholeWord,
                        onClick = onToggleWholeWord,
                        modifier = Modifier.semantics { contentDescription = "Whole words only" },
                    )
                }
            }
            NeriboDivider()
            when {
                tooShort -> EmptyState(
                    icon = Icons.Outlined.Search,
                    title = "Search your files",
                    message = "Type at least 2 characters to look in every file of this project.",
                    modifier = Modifier.fillMaxSize(),
                )
                results != null && results.totalCount == 0 && !state.searching -> EmptyState(
                    icon = Icons.Outlined.Search,
                    title = "No results",
                    message = "Try other words, or turn off Aa and ab.",
                    modifier = Modifier.fillMaxSize(),
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(rows) { row ->
                        when (row) {
                            is SearchRow.Header -> SectionHeader(
                                text = row.text,
                                modifier = Modifier.padding(start = spacing.screen, end = spacing.screen, top = spacing.lg, bottom = spacing.xs),
                            )
                            is SearchRow.FileName -> FileNameRow(path = row.path, onClick = { onOpenResult(row.path, 1) })
                            is SearchRow.FileTitle -> Text(
                                text = row.path,
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = spacing.screen, end = spacing.screen, top = spacing.md, bottom = spacing.xs),
                            )
                            is SearchRow.Hit -> HitRow(path = row.path, hit = row.hit, onClick = { onOpenResult(row.path, row.hit.line) })
                            is SearchRow.Note -> Text(
                                text = row.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = spacing.screen, vertical = spacing.lg),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileNameRow(path: String, onClick: () -> Unit) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val parent = CoderPaths.parentOf(path)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = "Open file", onClick = onClick)
            .padding(horizontal = spacing.screen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(20.dp), tint = colors.onSurfaceVariant)
        Spacer(modifier = Modifier.width(spacing.md))
        Text(
            text = CoderPaths.nameOf(path),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (parent.isNotEmpty()) {
            Text(
                text = parent,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = spacing.sm).widthIn(max = 120.dp),
            )
        }
    }
}

@Composable
private fun HitRow(path: String, hit: SearchHit, onClick: () -> Unit) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val tint = colors.primary.copy(alpha = 0.25f)
    val styled = remember(hit, tint) {
        buildAnnotatedString {
            if (hit.cutStart) append("…")
            append(hit.snippet.substring(0, hit.highlightStart))
            withStyle(SpanStyle(background = tint, fontWeight = FontWeight.SemiBold)) {
                append(hit.snippet.substring(hit.highlightStart, hit.highlightEnd))
            }
            append(hit.snippet.substring(hit.highlightEnd))
            if (hit.cutEnd) append("…")
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = "Open ${CoderPaths.nameOf(path)} at line ${hit.line}", onClick = onClick)
            .padding(horizontal = spacing.screen, vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = hit.line.toString(),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = colors.onSurfaceVariant,
            modifier = Modifier.width(40.dp),
        )
        Text(
            text = styled,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
