package com.westly.neribovault.feature.documents.viewer

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.LoadingState
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.util.copyToClipboard
import kotlinx.coroutines.launch

private const val MSG_COPIED = "Copied"
private const val MSG_COPIED_PART = "Copied the first 400,000 characters."
private const val MSG_COPY_FAILED = "Couldn't copy this text."
private const val FALLBACK_BAR_TITLE = "File"

/**
 * Reads and shows any saved file: text and code (selectable, with a Copy action), PDFs page by
 * page, images fit to width, and a friendly card for everything else. Opened from the document
 * detail screen's "View file" button, behind the Documents lock.
 */
@Composable
fun DocumentViewerScreen(documentId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val vm = neriboViewModel(key = documentId) { c ->
        DocumentViewerViewModel(documentId, c.personalDocumentsRepository, appContext)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val pdfListState = rememberLazyListState()
    val firstVisiblePage by remember { derivedStateOf { pdfListState.firstVisibleItemIndex + 1 } }
    val content = state.content

    val subtitle: String? = when (content) {
        ViewerContent.Loading -> null
        is ViewerContent.Error -> null
        is ViewerContent.Pdf -> pdfSubtitle(
            page = firstVisiblePage.coerceIn(1, maxOf(content.pageCount, 1)),
            pageCount = content.pageCount,
            sizeText = state.sizeText,
        )
        is ViewerContent.Text -> "${state.typeLabel} · ${state.sizeText}"
        is ViewerContent.Image -> "${state.typeLabel} · ${state.sizeText}"
        is ViewerContent.Unsupported -> "${state.typeLabel} · ${state.sizeText}"
    }

    NeriboScaffold(
        snackbarHostState = snackbarHostState,
        topBar = {
            NeriboTopBar(
                title = state.title.ifEmpty { FALLBACK_BAR_TITLE },
                subtitle = subtitle,
                onBack = onBack,
                actions = {
                    if (content is ViewerContent.Text && content.fullText.isNotBlank()) {
                        NeriboIconButton(
                            icon = Icons.Outlined.ContentCopy,
                            contentDescription = "Copy text",
                            onClick = {
                                val message = copyDocumentText(
                                    context = context,
                                    fullText = content.fullText,
                                )
                                scope.launch {
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    snackbarHostState.showSnackbar(message)
                                }
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        val bodyModifier = Modifier.padding(padding)
        when (content) {
            ViewerContent.Loading -> LoadingState(modifier = bodyModifier)
            is ViewerContent.Text -> ViewerTextContent(content = content, modifier = bodyModifier)
            is ViewerContent.Pdf -> ViewerPdfContent(
                source = content.source,
                listState = pdfListState,
                modifier = bodyModifier,
            )
            is ViewerContent.Image -> ViewerImageContent(
                bitmap = content.bitmap,
                description = state.title.ifEmpty { FALLBACK_BAR_TITLE },
                modifier = bodyModifier,
            )
            is ViewerContent.Unsupported -> ViewerUnsupportedContent(
                fileName = state.title.ifEmpty { FALLBACK_BAR_TITLE },
                sizeText = state.sizeText,
                content = content,
                modifier = bodyModifier,
            )
            is ViewerContent.Error -> ViewerErrorContent(message = content.message, modifier = bodyModifier)
        }
    }
}

/**
 * Puts the text on the clipboard (at most the first 400,000 characters, because the Android
 * clipboard fails on very large text) and returns the message to show. Never throws.
 */
private fun copyDocumentText(context: Context, fullText: String): String {
    return try {
        val (textToCopy, wasCut) = copyPrefix(fullText)
        context.copyToClipboard("Document text", textToCopy)
        if (wasCut) MSG_COPIED_PART else MSG_COPIED
    } catch (e: Exception) {
        MSG_COPY_FAILED
    }
}
