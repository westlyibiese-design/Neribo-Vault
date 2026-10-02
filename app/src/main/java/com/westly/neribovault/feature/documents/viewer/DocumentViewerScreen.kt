package com.westly.neribovault.feature.documents.viewer

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.files.SecureFileStore
import com.westly.neribovault.core.ui.components.LoadingState
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.data.repository.PersonalDocumentsRepository
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val BYTES_PER_KB = 1024.0
private const val BYTES_PER_MB = 1024.0 * 1024.0

/** What the plain screen shows. */
private sealed interface ViewerState {
    object Loading : ViewerState

    data class Ready(val name: String, val sizeText: String) : ViewerState

    /** The document, or its file, is not there any more. */
    object Missing : ViewerState

    /** Anything else that went wrong while reading it. */
    object Failed : ViewerState
}

/** Looks the document and its file up once, off the main thread. */
private class PlaceholderViewerViewModel(
    private val appContext: Context,
    private val documentId: String,
    private val repository: PersonalDocumentsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ViewerState>(ViewerState.Loading)
    val state: StateFlow<ViewerState> = _state.asStateFlow()

    init {
        viewModelScope.launch { _state.value = load() }
    }

    private suspend fun load(): ViewerState = withContext(Dispatchers.IO) {
        try {
            val document = repository.getById(documentId)
            val path = document?.fileUri
            if (document == null || path.isNullOrBlank()) {
                ViewerState.Missing
            } else {
                val file = File(path)
                if (!file.isFile) {
                    ViewerState.Missing
                } else {
                    val size = SecureFileStore.plainSize(appContext, file)
                    if (size < 0L) {
                        ViewerState.Failed
                    } else {
                        ViewerState.Ready(
                            name = document.title.trim().ifEmpty { "Untitled document" },
                            sizeText = formatSize(size),
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ViewerState.Failed
        }
    }
}

/**
 * A plain placeholder for the file viewer: the file's name, its size and a short note. It proves
 * the file is saved and readable. Phase 16e-2 replaces this whole file with the real viewer, and
 * keeps this signature.
 */
@Composable
fun DocumentViewerScreen(documentId: String, onBack: () -> Unit) {
    val appContext = LocalContext.current.applicationContext
    val vm = neriboViewModel(key = documentId) { c ->
        PlaceholderViewerViewModel(appContext, documentId, c.personalDocumentsRepository)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val current = state

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = if (current is ViewerState.Ready) current.name else "File",
                onBack = onBack,
            )
        },
    ) { padding ->
        when (current) {
            ViewerState.Loading -> LoadingState(modifier = Modifier.padding(padding))
            is ViewerState.Ready -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.screen, vertical = spacing.sm),
            ) {
                NeriboCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        Text(
                            text = current.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurface,
                        )
                        Text(
                            text = "Size: ${current.sizeText}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurface,
                        )
                        Text(
                            text = "Saved safely on this phone. " +
                                "Reading the file inside the app is coming in the next update.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
            ViewerState.Missing -> CenteredMessage("This file is missing.", Modifier.padding(padding))
            ViewerState.Failed -> CenteredMessage("Couldn't open this file.", Modifier.padding(padding))
        }
    }
}

@Composable
private fun CenteredMessage(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = NeriboTheme.spacing.screen),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** "512 B", "3.4 KB" or "12.0 MB" (1 KB is 1,024 bytes). */
private fun formatSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / BYTES_PER_KB)
    else -> String.format(Locale.US, "%.1f MB", bytes / BYTES_PER_MB)
}
