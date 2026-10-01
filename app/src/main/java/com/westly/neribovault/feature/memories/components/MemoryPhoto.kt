package com.westly.neribovault.feature.memories.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import java.io.File

/**
 * One stored memory photo, loaded from its private file path with Coil. A soft tonal block is
 * shown while it loads and when the file is missing. Set [fullSize] for the zoomable viewer so
 * the image is decoded at its stored size instead of the size of the box it sits in.
 */
@Composable
fun MemoryPhoto(
    path: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fullSize: Boolean = false,
) {
    val context = LocalContext.current
    val request = remember(path, fullSize) {
        ImageRequest.Builder(context)
            .data(File(path))
            .apply { if (fullSize) size(Size.ORIGINAL) }
            .build()
    }
    val softColor = MaterialTheme.colorScheme.surfaceVariant
    val soft = remember(softColor) { ColorPainter(softColor) }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        placeholder = soft,
        error = soft,
        contentScale = contentScale,
    )
}
