package com.westly.neribovault.feature.memories

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.util.copyToClipboard
import com.westly.neribovault.core.util.formatDateLong
import com.westly.neribovault.core.util.shareText
import com.westly.neribovault.feature.memories.components.MemoryInfoChip
import com.westly.neribovault.feature.memories.components.MemoryPhoto
import kotlinx.coroutines.launch

/**
 * A memory laid out like a page in an album: swipeable photos, the serif title, the date,
 * quiet chips for place, people and tags, then the story.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun MemoryDetailScreen(
    memoryId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpenPhoto: (Int) -> Unit,
    onDeleted: (deletedId: String?) -> Unit,
) {
    val vm = neriboViewModel(key = memoryId) { c -> MemoryDetailViewModel(memoryId, c.memoriesRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    // Set the moment we start deleting, so the memory vanishing from the list is not mistaken
    // for "this memory does not exist" and does not pop the screen a second time.
    var leaving by remember { mutableStateOf(false) }

    LaunchedEffect(state.notFound) {
        if (state.notFound && !leaving) onBack()
    }

    val memory = state.memory

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = "Memory",
                onBack = onBack,
                actions = {
                    if (memory != null) {
                        NeriboIconButton(
                            icon = Icons.Outlined.Edit,
                            contentDescription = "Edit memory",
                            onClick = onEdit,
                        )
                        OverflowMenu(
                            actions = listOf(
                                MenuAction(
                                    label = "Copy text",
                                    onClick = {
                                        context.copyToClipboard("Memory", memory.toPlainText())
                                        scope.launch { snackbarHostState.showSnackbar("Copied to clipboard") }
                                    },
                                    icon = Icons.Outlined.ContentCopy,
                                ),
                                MenuAction(
                                    label = "Share text",
                                    onClick = {
                                        context.shareText(memory.title.ifBlank { null }, memory.toPlainText())
                                    },
                                    icon = Icons.Outlined.Share,
                                ),
                                MenuAction(
                                    label = "Delete",
                                    onClick = {
                                        leaving = true
                                        vm.delete(onDone = { id -> onDeleted(id) })
                                    },
                                    icon = Icons.Outlined.Delete,
                                    destructive = true,
                                ),
                            ),
                        )
                    }
                },
            )
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        if (memory == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding))
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (memory.photoUris.isNotEmpty()) {
                    PhotoPager(photos = memory.photoUris, onOpenPhoto = onOpenPhoto)
                    Spacer(modifier = Modifier.height(spacing.lg))
                }
                Column(modifier = Modifier.padding(horizontal = spacing.screen)) {
                    val hasTitle = memory.title.isNotBlank()
                    Text(
                        text = if (hasTitle) memory.title.trim() else "Untitled memory",
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (hasTitle) colors.onBackground else colors.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(spacing.xs))
                    Text(
                        text = formatDateLong(memory.memoryDate),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    val place = memory.location.trim()
                    val people = memory.people.filter { it.isNotBlank() }
                    val tags = memory.tags.filter { it.isNotBlank() }
                    if (place.isNotEmpty() || people.isNotEmpty() || tags.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(spacing.md))
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(spacing.sm),
                        ) {
                            if (place.isNotEmpty()) {
                                MemoryInfoChip(text = place, icon = Icons.Outlined.LocationOn)
                            }
                            people.forEach { name ->
                                MemoryInfoChip(text = name, icon = Icons.Outlined.Person)
                            }
                            tags.forEach { tag ->
                                MemoryInfoChip(text = tag)
                            }
                        }
                    }
                    if (memory.description.isNotBlank()) {
                        Spacer(modifier = Modifier.height(spacing.lg))
                        Text(
                            text = memory.description.trim(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onBackground,
                        )
                    }
                    Spacer(modifier = Modifier.height(spacing.xxl))
                }
            }
        }
    }
}

/** The swipeable photos with a row of small dots under them. Tap a photo to open it full screen. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoPager(photos: List<String>, onOpenPhoto: (Int) -> Unit) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val pagerState = rememberPagerState(pageCount = { photos.size })
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.screen)
                .aspectRatio(4f / 3f)
                .clip(MaterialTheme.shapes.medium),
        ) { page ->
            MemoryPhoto(
                path = photos[page],
                contentDescription = "Photo ${page + 1} of ${photos.size}",
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClickLabel = "Open photo full screen") { onOpenPhoto(page) },
            )
        }
        if (photos.size > 1) {
            Spacer(modifier = Modifier.height(spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(photos.size) { index ->
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == pagerState.currentPage) colors.primary else colors.outlineVariant,
                            ),
                    )
                }
            }
        }
    }
}
