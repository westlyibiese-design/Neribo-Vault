package com.westly.neribovault.feature.documents.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.feature.documents.isPdfPath
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Whether the file at [path] exists, checked off the main thread. Null until the check is done,
 * so callers can show nothing rather than a false "missing" flash.
 */
@Composable
fun rememberFileExists(path: String): State<Boolean?> =
    produceState<Boolean?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { File(path).isFile }.getOrDefault(false)
        }
    }

/**
 * The attachment as a calm card: a photo preview or a PDF tile, with a small line naming the
 * kind of file. When [onClick] is given the whole card opens the viewer.
 */
@Composable
fun AttachmentThumbnail(
    path: String,
    modifier: Modifier = Modifier,
    previewHeight: Dp = 180.dp,
    onClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val exists by rememberFileExists(path)
    val isPdf = isPdfPath(path)

    NeriboCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.sm)
                .height(previewHeight)
                .clip(MaterialTheme.shapes.small)
                .background(colors.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            when {
                exists == null -> Unit
                exists == false -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AttachFile,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = colors.onSurfaceVariant,
                    )
                    Text(
                        text = "This file is no longer on the phone",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                isPdf -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Description,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = colors.onSurfaceVariant,
                    )
                    Text(
                        text = "PDF",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurfaceVariant,
                    )
                }
                else -> DocumentImage(
                    path = path,
                    contentDescription = "Attached photo",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.lg, end = spacing.lg, bottom = spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.AttachFile,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = colors.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(spacing.xs))
            val kind = if (isPdf) "PDF document" else "Photo"
            Text(
                text = if (onClick != null) "$kind \u00B7 tap to open" else kind,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}
