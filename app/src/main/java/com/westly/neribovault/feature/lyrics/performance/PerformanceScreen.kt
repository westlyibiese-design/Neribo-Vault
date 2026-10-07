package com.westly.neribovault.feature.lyrics.performance

import androidx.compose.runtime.Composable
import com.westly.neribovault.core.ui.components.PlaceholderScreen

/** A stub that part L2 of the Lyrics track replaces; the signature is fixed. */
@Composable
fun PerformanceScreen(songId: String, onBack: () -> Unit) {
    PlaceholderScreen(
        title = "Performance mode",
        message = "This part of the lyrics tools is being built.",
        onBack = onBack,
    )
}
