package com.westly.neribovault.feature.writers.notes

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.PlaceholderScreen

/**
 * STUB from Phase 6. Phase 7 replaces this file with the real story notes list.
 * The public signatures must not change.
 */
@Composable
fun StoryNotesTab(
    storyId: String,
    onOpenNote: (noteId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        icon = Icons.Outlined.Schedule,
        title = "Coming soon",
        message = "This section is being built.",
        modifier = modifier.fillMaxSize(),
    )
}

/**
 * STUB from Phase 6. Phase 7 replaces this file with the real story note editor.
 * `noteId == "new"` will create a note.
 */
@Composable
fun StoryNoteEditorScreen(
    storyId: String,
    noteId: String,
    onBack: () -> Unit,
) {
    PlaceholderScreen(
        title = "Story note",
        message = "This section is being built.",
        onBack = onBack,
    )
}
