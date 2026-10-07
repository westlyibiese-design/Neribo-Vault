package com.westly.neribovault.feature.lyrics.tools

import androidx.compose.runtime.Composable
import com.westly.neribovault.core.ui.components.PlaceholderScreen

/** A stub that part L3 of the Lyrics track replaces; the signature is fixed. */
@Composable
fun SongToolsScreen(songId: String, onJumpToSection: (sectionIndex: Int) -> Unit, onBack: () -> Unit) {
    PlaceholderScreen(
        title = "Song tools",
        message = "This part of the lyrics tools is being built.",
        onBack = onBack,
    )
}
