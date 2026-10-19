package com.westly.neribovault.feature.coder.workspace

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.ConfirmDialog
import com.westly.neribovault.core.ui.components.NeriboBottomSheet
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.feature.backup.formatBytes
import com.westly.neribovault.feature.coder.editor.CoderLanguage
import com.westly.neribovault.feature.coder.files.CoderEntry
import com.westly.neribovault.feature.coder.files.CoderFileCopy
import com.westly.neribovault.feature.coder.files.CoderLimits
import com.westly.neribovault.feature.coder.files.CoderPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What a file is, as far as the file lists and the tap on it are concerned. */
enum class CoderFileKind {
    /** A text file in a programming or data language. */
    Code,

    /** A picture the file sheet can show. */
    Image,

    /** Any other text, such as Markdown or a plain note. */
    Text,

    /** A file that cannot be edited: a known binary type, or one bigger than 300 KB. */
    Other,
}

/** File endings of binary files that are not pictures the sheet can show. */
private val OTHER_BINARY_EXTENSIONS = setOf(
    "ico", "pdf", "zip", "gz", "tar", "7z", "rar", "mp3", "wav", "ogg", "mp4", "mov", "webm",
    "ttf", "otf", "woff", "woff2", "apk", "jar", "class", "so",
)

/** The kind of the file at [path] with [sizeBytes]. It goes by the name and the size only, so no file is read. */
fun coderFileKindOf(path: String, sizeBytes: Long): CoderFileKind {
    val name = CoderPaths.nameOf(path)
    if (CoderFileCopy.isImageName(name)) return CoderFileKind.Image
    val extension = name.substringAfterLast('.', "").lowercase()
    if (sizeBytes > CoderLimits.MAX_EDITABLE_BYTES || extension in OTHER_BINARY_EXTENSIONS) return CoderFileKind.Other
    return when (CoderLanguage.fromPath(path)) {
        CoderLanguage.Plain, CoderLanguage.Markdown -> CoderFileKind.Text
        else -> CoderFileKind.Code
    }
}

private const val SHEET_MAX_HEIGHT_FRACTION = 0.8f
private const val IMAGE_MAX_HEIGHT_FRACTION = 0.6f
private val IMAGE_MIN_HEIGHT = 120.dp

/**
 * What a tap on a file that cannot be edited opens: a picture is shown (never edited), any other file shows its details.
 * Both offer Copy path, Rename, Delete and Replace file. Rename and Delete go back to the screen, which asks the same
 * questions as for any file; Replace file picks one file and overwrites this one after a confirmation.
 */
@Composable
fun BinaryFileSheet(
    entry: CoderEntry,
    file: File?,
    viewModel: CoderWorkspaceViewModel,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val name = CoderPaths.nameOf(entry.path)
    val showsPicture = file != null && coderFileKindOf(entry.path, entry.sizeBytes) == CoderFileKind.Image
    var replacement by remember { mutableStateOf<CoderPickedFile?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                replacement = withContext(Dispatchers.IO) {
                    readPickedFiles(context.applicationContext.contentResolver, listOf(uri)).firstOrNull()
                }
            }
        }
    }
    val pixels by produceState<Pair<Int, Int>?>(null, file, showsPicture) {
        value = if (showsPicture) withContext(Dispatchers.IO) { imageSizeOf(file) } else null
    }

    NeriboBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = (screenHeightDp * SHEET_MAX_HEIGHT_FRACTION).dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(spacing.md))
            if (showsPicture && file != null) {
                val request = remember(file) { ImageRequest.Builder(context).data(file).build() }
                AsyncImage(
                    model = request,
                    contentDescription = "Picture $name",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = IMAGE_MIN_HEIGHT, max = (screenHeightDp * IMAGE_MAX_HEIGHT_FRACTION).dp),
                )
            } else {
                NeriboCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(spacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AttachFile,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = colors.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(spacing.md))
                        Text(
                            text = whyNotEditable(entry),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(spacing.md))
            Text(text = "Size: ${formatBytes(entry.sizeBytes)}", style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            pixels?.let { (pixelWidth, pixelHeight) ->
                Text(
                    text = "Picture size: $pixelWidth \u00D7 $pixelHeight pixels",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface,
                )
            }
            Text(text = "Path: ${entry.path}", style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            Spacer(modifier = Modifier.height(spacing.lg))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                NeriboButton(
                    text = "Copy path",
                    onClick = {
                        clipboard.setText(AnnotatedString(entry.path))
                        onMessage("Path copied")
                    },
                    modifier = Modifier.weight(1f),
                    style = ButtonStyle.Secondary,
                )
                NeriboButton(
                    text = "Rename",
                    onClick = onRename,
                    modifier = Modifier.weight(1f),
                    style = ButtonStyle.Secondary,
                )
            }
            Spacer(modifier = Modifier.height(spacing.sm))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                NeriboButton(
                    text = "Replace file",
                    onClick = { picker.launch(arrayOf("*/*")) },
                    modifier = Modifier.weight(1f),
                    style = ButtonStyle.Secondary,
                )
                NeriboButton(
                    text = "Delete",
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                    style = ButtonStyle.Destructive,
                )
            }
        }
    }

    replacement?.let { chosen ->
        ConfirmDialog(
            title = "Replace this file?",
            message = "$name will be overwritten with ${chosen.displayName}. This can't be undone.",
            confirmLabel = "Replace",
            onConfirm = {
                replacement = null
                viewModel.replaceFile(entry.path, chosen)
            },
            onDismiss = { replacement = null },
            destructive = true,
        )
    }
}

/** The one sentence on the info card of a file that is not shown as a picture. */
private fun whyNotEditable(entry: CoderEntry): String = when {
    coderFileKindOf(entry.path, entry.sizeBytes) == CoderFileKind.Image -> "This picture can't be shown."
    entry.sizeBytes > CoderLimits.MAX_EDITABLE_BYTES -> "This file is bigger than 300 KB, so it can't be edited here."
    else -> "This file isn't plain text, so it can't be edited here."
}

/** The width and height in pixels of the picture in [file], read from its header only; null when it cannot be read. Does disk work. */
private fun imageSizeOf(file: File?): Pair<Int, Int>? {
    if (file == null) return null
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, options)
    return if (options.outWidth > 0 && options.outHeight > 0) Pair(options.outWidth, options.outHeight) else null
}
