package com.westly.neribovault.feature.developer.docs

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.PlaceholderScreen

/** Stub from Phase 12; Phase 14 replaces this file with the real docs tab. */
@Composable
fun ProjectDocsTab(
    projectId: String,
    onOpenDocument: (docId: String) -> Unit,
    onOpenPrompt: (promptId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        icon = Icons.Outlined.Schedule,
        title = "Coming soon",
        message = "This section is being built.",
        modifier = modifier.fillMaxSize(),
    )
}

/** Stub from Phase 12; Phase 14 replaces this file with the real document editor. */
@Composable
fun ProjectDocEditorScreen(projectId: String, docId: String, onBack: () -> Unit) {
    PlaceholderScreen(title = "Document", message = "This section is being built.", onBack = onBack)
}

/** Stub from Phase 12; Phase 14 replaces this file with the real prompt editor. */
@Composable
fun PromptEditorScreen(projectId: String?, promptId: String, onBack: () -> Unit) {
    PlaceholderScreen(title = "Prompt", message = "This section is being built.", onBack = onBack)
}

/** Stub from Phase 12; Phase 14 replaces this file with the real prompts library. */
@Composable
fun PromptsLibraryScreen(
    onOpenPrompt: (projectId: String?, promptId: String) -> Unit,
    onBack: () -> Unit,
) {
    PlaceholderScreen(title = "Prompts", message = "This section is being built.", onBack = onBack)
}
