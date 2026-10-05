package com.westly.neribovault.feature.screenplays.tools

import androidx.compose.runtime.Composable
import com.westly.neribovault.core.ui.components.PlaceholderScreen

/** A stub that part S3 replaces. Keep this signature. */
@Composable
fun ScenesScreen(screenplayId: String, onJumpToBlock: (blockIndex: Int) -> Unit, onBack: () -> Unit) {
    PlaceholderScreen(
        title = "Scenes and characters",
        message = "This part of the screenplay tools is being built.",
        onBack = onBack,
    )
}
