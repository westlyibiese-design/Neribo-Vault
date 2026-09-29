package com.westly.neribovault.core.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Stand-in screen for features that are not built yet. */
@Composable
fun PlaceholderScreen(title: String, message: String, onBack: () -> Unit) {
    NeriboScaffold(
        topBar = { NeriboTopBar(title = title, onBack = onBack) },
    ) { padding ->
        EmptyState(
            icon = Icons.Outlined.Schedule,
            title = "Coming soon",
            message = message,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}
