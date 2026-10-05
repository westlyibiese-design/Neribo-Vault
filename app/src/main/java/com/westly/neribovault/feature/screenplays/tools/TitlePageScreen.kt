package com.westly.neribovault.feature.screenplays.tools

import androidx.compose.runtime.Composable
import com.westly.neribovault.core.ui.components.PlaceholderScreen

/** A stub that part S3 replaces. Keep this signature. */
@Composable
fun TitlePageScreen(screenplayId: String, onBack: () -> Unit) {
    PlaceholderScreen(
        title = "Title page",
        message = "This part of the screenplay tools is being built.",
        onBack = onBack,
    )
}
