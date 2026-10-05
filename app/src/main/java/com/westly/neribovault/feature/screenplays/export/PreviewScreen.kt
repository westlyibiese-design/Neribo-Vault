package com.westly.neribovault.feature.screenplays.export

import androidx.compose.runtime.Composable
import com.westly.neribovault.core.ui.components.PlaceholderScreen

/** A stub that part S4 replaces. Keep this signature. */
@Composable
fun PreviewScreen(screenplayId: String, onBack: () -> Unit) {
    PlaceholderScreen(
        title = "Preview and export",
        message = "This part of the screenplay tools is being built.",
        onBack = onBack,
    )
}
