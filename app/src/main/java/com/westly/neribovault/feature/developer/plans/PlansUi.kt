package com.westly.neribovault.feature.developer.plans

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.PlaceholderScreen

/** Stub from Phase 12; Phase 14 replaces this file with the real plans tab. */
@Composable
fun ProjectPlansTab(
    projectId: String,
    onOpenPlanningDoc: (docId: String) -> Unit,
    onOpenFolderPlan: (planId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        icon = Icons.Outlined.Schedule,
        title = "Coming soon",
        message = "This section is being built.",
        modifier = modifier.fillMaxSize(),
    )
}

/** Stub from Phase 12; Phase 14 replaces this file with the real planning document editor. */
@Composable
fun PlanningDocEditorScreen(projectId: String, docId: String, onBack: () -> Unit) {
    PlaceholderScreen(title = "Plan", message = "This section is being built.", onBack = onBack)
}

/** Stub from Phase 12; Phase 14 replaces this file with the real folder plan editor. */
@Composable
fun FolderPlanEditorScreen(projectId: String, planId: String, onBack: () -> Unit) {
    PlaceholderScreen(title = "Folder plan", message = "This section is being built.", onBack = onBack)
}
