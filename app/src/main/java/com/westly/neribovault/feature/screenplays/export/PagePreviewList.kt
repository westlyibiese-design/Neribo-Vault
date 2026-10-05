package com.westly.neribovault.feature.screenplays.export

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.NeriboCard

/**
 * The info card followed by one white card per PDF page on a neutral background. Pages are
 * rendered only when they scroll into view. Page cards stay white in dark mode.
 */
@Composable
internal fun PagePreviewList(
    pageCount: Int,
    generation: Int,
    pageWidthPx: Int,
    listState: LazyListState,
    loadPage: suspend (index: Int, widthPx: Int) -> Bitmap?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val spacing = NeriboTheme.spacing
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item(key = "info") {
            NeriboCard(modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.screen)) {
                Text(
                    text = "This is how your PDF will look. Every page carries: ${PdfLayoutConstants.FOOTER_TEXT}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(spacing.lg),
                )
            }
        }
        items(items = (0 until pageCount).toList(), key = { "page-$it" }) { index ->
            PageCard(
                index = index,
                generation = generation,
                pageWidthPx = pageWidthPx,
                loadPage = loadPage,
                modifier = Modifier.padding(horizontal = spacing.screen),
            )
        }
    }
}

@Composable
private fun PageCard(
    index: Int,
    generation: Int,
    pageWidthPx: Int,
    loadPage: suspend (index: Int, widthPx: Int) -> Bitmap?,
    modifier: Modifier = Modifier,
) {
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(index, generation, pageWidthPx) {
        val bitmap = loadPage(index, pageWidthPx)
        if (bitmap != null) image = bitmap.asImageBitmap()
    }
    val shape = MaterialTheme.shapes.small
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(8.5f / 11f)
            .clip(shape)
            .background(Color.White, shape)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape),
    ) {
        val shown = image
        if (shown != null) {
            Image(
                bitmap = shown,
                contentDescription = "Page ${index + 1}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
        }
    }
}
