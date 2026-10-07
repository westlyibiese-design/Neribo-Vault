package com.westly.neribovault.feature.lyrics.details

import androidx.compose.runtime.Composable
import com.westly.neribovault.core.ui.components.PlaceholderScreen

/** A stub that part L2 of the Lyrics track replaces; the signature is fixed. */
@Composable
fun SongDetailsScreen(songId: String, onBack: () -> Unit) {
    PlaceholderScreen(
        title = "Song details",
        message = "This part of the lyrics tools is being built.",
        onBack = onBack,
    )
}
