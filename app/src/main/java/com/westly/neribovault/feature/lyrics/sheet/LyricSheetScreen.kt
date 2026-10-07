package com.westly.neribovault.feature.lyrics.sheet

import androidx.compose.runtime.Composable
import com.westly.neribovault.core.ui.components.PlaceholderScreen

/** A stub that part L3 of the Lyrics track replaces; the signature is fixed. */
@Composable
fun LyricSheetScreen(songId: String, onBack: () -> Unit) {
    PlaceholderScreen(
        title = "Lyric sheet",
        message = "This part of the lyrics tools is being built.",
        onBack = onBack,
    )
}
