package com.westly.neribovault.feature.documents.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.NeriboCard

private const val MONO_FONT_SIZE_SP = 13
private const val MONO_LINE_HEIGHT_SP = 20

/**
 * A text file: its chunks in one lazy list inside one selection container, so a long file scrolls
 * smoothly and the person can long-press to select. An empty file shows one muted line.
 */
@Composable
fun ViewerTextContent(content: ViewerContent.Text, modifier: Modifier = Modifier) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val listState = rememberLazyListState()

    if (content.fullText.isBlank()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = spacing.screen, vertical = spacing.md),
        ) {
            if (content.truncated) TruncatedNote()
            Text(
                text = "This file is empty.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
        return
    }

    val textStyle = if (content.monospace) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = MONO_FONT_SIZE_SP.sp,
            lineHeight = MONO_LINE_HEIGHT_SP.sp,
        )
    } else {
        MaterialTheme.typography.bodyMedium
    }

    SelectionContainer(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = spacing.screen,
                end = spacing.screen,
                top = spacing.sm,
                bottom = spacing.xxl,
            ),
        ) {
            if (content.truncated) {
                item { TruncatedNote() }
            }
            items(count = content.chunks.size) { index ->
                Text(
                    text = content.chunks[index],
                    style = textStyle,
                    color = colors.onBackground,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** The small muted card above a file that is longer than 2 MiB. */
@Composable
private fun TruncatedNote() {
    val spacing = NeriboTheme.spacing
    NeriboCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = spacing.md),
    ) {
        Text(
            text = "Showing the first 2 MB of this file.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(spacing.md),
        )
    }
}

/** A photo, fit to the width, in a column that scrolls when the photo is tall. */
@Composable
fun ViewerImageContent(bitmap: Bitmap, description: String, modifier: Modifier = Modifier) {
    val spacing = NeriboTheme.spacing
    val picture = remember(bitmap) { bitmap.asImageBitmap() }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = spacing.screen, end = spacing.screen, top = spacing.sm, bottom = spacing.xl),
    ) {
        Image(
            bitmap = picture,
            contentDescription = description,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium),
        )
    }
}

/** The card for a file the viewer cannot show: its name, type and size, and one short reason. */
@Composable
fun ViewerUnsupportedContent(
    fileName: String,
    sizeText: String,
    content: ViewerContent.Unsupported,
    modifier: Modifier = Modifier,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val message = when (content.reason) {
        UnsupportedReason.Generic -> "This type of file can't be previewed in the app."
        UnsupportedReason.ProtectedPdf -> "This PDF is protected or damaged, so it can't be shown."
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.screen, vertical = spacing.sm),
    ) {
        NeriboCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                )
                Text(
                    text = "Type: ${content.extensionLabel}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    text = "Size: $sizeText",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurface,
                )
            }
        }
    }
}

/** A friendly message in the middle of the screen. The back arrow stays in the top bar. */
@Composable
fun ViewerErrorContent(message: String, modifier: Modifier = Modifier) {
    val spacing = NeriboTheme.spacing
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = spacing.screen),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.padding(bottom = spacing.xs),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
