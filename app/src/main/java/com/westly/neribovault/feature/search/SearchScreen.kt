package com.westly.neribovault.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.search.GlobalIndex
import com.westly.neribovault.core.search.SearchHit
import com.westly.neribovault.core.search.highlightRanges
import com.westly.neribovault.core.search.rememberHiddenVaults
import com.westly.neribovault.core.search.snippetAround
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.LoadingState
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.SectionHeader
import kotlinx.coroutines.delay

/** Locked vaults are left out of search; this is told to the owner when results are few. */
private const val FEW_RESULTS = 5

/**
 * Global search across every vault. The box opens focused, results are grouped by vault, and
 * locked vaults are never searched.
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenRoute: (String) -> Unit,
) {
    val vm = neriboViewModel { c -> SearchViewModel(GlobalIndex(c)) }
    val state by vm.state.collectAsStateWithLifecycle()
    val hidden = rememberHiddenVaults()
    val spacing = NeriboTheme.spacing
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    // The text field edits this local copy so typing never waits for the ViewModel.
    var localQuery by rememberSaveable { mutableStateOf(state.query) }
    var didAutoFocus by rememberSaveable { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, hidden) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.reload(hidden)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        if (!didAutoFocus) {
            didAutoFocus = true
            delay(150)
            runCatching { focusRequester.requestFocus() }
        }
    }

    val changeQuery: (String) -> Unit = { value ->
        localQuery = value
        vm.onQueryChange(value)
    }
    val openHit: (SearchHit) -> Unit = { hit ->
        focusManager.clearFocus()
        vm.rememberCurrentSearch()
        onOpenRoute(hit.item.route)
    }

    NeriboScaffold(
        topBar = {
            SearchTopBar(
                query = localQuery,
                onQueryChange = changeQuery,
                focusRequester = focusRequester,
                onBack = onBack,
                onSearchAction = { focusManager.clearFocus() },
            )
        },
    ) { padding ->
        val groups = state.visibleGroups
        val hasQuery = localQuery.isNotBlank()
        val searched = state.committedQuery.isNotBlank()
        val showLockedNote = hidden.isNotEmpty() && searched && state.totalHits < FEW_RESULTS

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                !hasQuery -> PromptState(
                    recent = state.recentSearches,
                    onPick = changeQuery,
                )
                state.isLoading && !searched -> LoadingState()
                searched && state.totalHits == 0 -> NoResultsState(
                    query = state.committedQuery,
                    showLockedNote = showLockedNote,
                )
                searched -> {
                    VaultChips(
                        chips = state.groups.map { it.vaultId to it.vaultLabel },
                        selected = state.effectiveVault,
                        onSelect = vm::selectVault,
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = spacing.screen,
                            end = spacing.screen,
                            top = spacing.sm,
                            bottom = spacing.xxl,
                        ),
                        verticalArrangement = Arrangement.spacedBy(spacing.md),
                    ) {
                        groups.forEachIndexed { groupIndex, group ->
                            item(key = "header:${group.vaultId}") {
                                SectionHeader(
                                    text = "${group.vaultLabel}  \u00B7  ${group.hits.size}",
                                    modifier = Modifier.padding(top = if (groupIndex == 0) 0.dp else spacing.sm),
                                )
                            }
                            items(
                                items = group.hits,
                                key = { hit -> "${hit.item.vaultId}:${hit.item.kind}:${hit.item.id}" },
                            ) { hit ->
                                ResultCard(
                                    hit = hit,
                                    terms = state.terms,
                                    onClick = { openHit(hit) },
                                )
                            }
                        }
                        if (showLockedNote) {
                            item(key = "locked-note") { LockedVaultsNote() }
                        }
                    }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
    onBack: () -> Unit,
    onSearchAction: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .heightIn(min = 64.dp)
            .padding(start = spacing.xs, end = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeriboIconButton(
            icon = Icons.AutoMirrored.Outlined.ArrowBack,
            contentDescription = "Back",
            onClick = onBack,
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = spacing.xs)
                .focusRequester(focusRequester),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onBackground),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearchAction() }),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isEmpty()) {
                        Text(
                            text = "Search all your vaults",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (query.isNotEmpty()) {
            NeriboIconButton(
                icon = Icons.Outlined.Close,
                contentDescription = "Clear search",
                onClick = { onQueryChange("") },
            )
        }
    }
}

@Composable
private fun VaultChips(
    chips: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    val spacing = NeriboTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = spacing.screen),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeriboChip(label = "All", selected = selected == null, onClick = { onSelect(null) })
        chips.forEach { (vaultId, label) ->
            NeriboChip(label = label, selected = selected == vaultId, onClick = { onSelect(vaultId) })
        }
    }
}

@Composable
private fun ResultCard(
    hit: SearchHit,
    terms: List<String>,
    onClick: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val item = hit.item
    val highlight = SpanStyle(fontWeight = FontWeight.Bold, color = colors.primary)
    val title = remember(item.title, terms, colors.primary) {
        buildHighlighted(item.title, highlightRanges(item.title, terms), highlight)
    }
    val preview = remember(item.id, hit.bodyMatchIndex, terms, colors.primary) {
        val text = snippetAround(item.body, hit.bodyMatchIndex)
        buildHighlighted(text, highlightRanges(text, terms), highlight)
    }
    NeriboCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md)) {
            Text(
                text = item.kind,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(spacing.xxs))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (preview.isNotEmpty()) {
                Spacer(modifier = Modifier.height(spacing.xs))
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** [text] with the character [ranges] styled by [style]. Ranges must be sorted and apart. */
private fun buildHighlighted(text: String, ranges: List<IntRange>, style: SpanStyle): AnnotatedString =
    buildAnnotatedString {
        var cursor = 0
        for (range in ranges) {
            val start = range.first.coerceIn(0, text.length)
            val endExclusive = (range.last + 1).coerceIn(start, text.length)
            if (start < cursor) continue
            if (start > cursor) append(text.substring(cursor, start))
            withStyle(style) { append(text.substring(start, endExclusive)) }
            cursor = endExclusive
        }
        if (cursor < text.length) append(text.substring(cursor))
    }

@Composable
private fun PromptState(
    recent: List<String>,
    onPick: (String) -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        EmptyState(
            icon = Icons.Outlined.Search,
            title = "Search everything",
            message = "Find a note, a chapter, a sermon or a memory without opening each vault.",
        )
        if (recent.isNotEmpty()) {
            SectionHeader(
                text = "Recent searches",
                modifier = Modifier.padding(horizontal = spacing.screen),
            )
            recent.forEach { query ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { onPick(query) }
                        .padding(horizontal = spacing.screen),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = colors.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(spacing.md))
                    Text(
                        text = query,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun NoResultsState(
    query: String,
    showLockedNote: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        EmptyState(
            icon = Icons.Outlined.Search,
            title = "No results",
            message = "Nothing found for \u2018$query\u2019. Try fewer or different words.",
        )
        if (showLockedNote) LockedVaultsNote()
    }
}

@Composable
private fun LockedVaultsNote() {
    val spacing = NeriboTheme.spacing
    Text(
        text = "Locked vaults are not included in search.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.screen, vertical = spacing.md),
    )
}
