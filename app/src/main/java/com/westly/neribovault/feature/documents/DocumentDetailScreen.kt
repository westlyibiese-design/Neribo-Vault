package com.westly.neribovault.feature.documents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.files.SecureFileStore
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.core.util.copyToClipboard
import com.westly.neribovault.core.util.formatDateLong
import com.westly.neribovault.data.local.entity.PersonalDocumentEntity
import com.westly.neribovault.feature.documents.components.AttachmentThumbnail
import com.westly.neribovault.feature.documents.components.ExpiryBanner
import com.westly.neribovault.feature.documents.components.rememberFileExists
import java.io.File
import kotlinx.coroutines.launch

/**
 * One document, read-only: the serif title, a status banner in the right tone, a clean list of
 * its details and the attachment as a thumbnail card, with a **View file** button whenever a file
 * is saved for it.
 */
@Composable
fun DocumentDetailScreen(
    documentId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpenAttachment: () -> Unit,
    onViewFile: () -> Unit,
    onDeleted: (deletedId: String?) -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val vm = neriboViewModel(key = documentId) { c ->
        DocumentDetailViewModel(appContext, documentId, c.personalDocumentsRepository)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    // Set the moment we start deleting, so the document vanishing from the list is not mistaken
    // for "this document does not exist" and does not pop the screen a second time.
    var leaving by remember { mutableStateOf(false) }

    LaunchedEffect(state.notFound) {
        if (state.notFound && !leaving) onBack()
    }

    val document = state.document

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = "Document",
                onBack = onBack,
                actions = {
                    if (document != null) {
                        NeriboIconButton(
                            icon = Icons.Outlined.Edit,
                            contentDescription = "Edit document",
                            onClick = onEdit,
                        )
                        OverflowMenu(
                            actions = listOf(
                                MenuAction(
                                    label = "Copy details as text",
                                    onClick = {
                                        context.copyToClipboard("Document", document.toPlainText())
                                        scope.launch { snackbarHostState.showSnackbar("Copied to clipboard") }
                                    },
                                    icon = Icons.Outlined.ContentCopy,
                                ),
                                MenuAction(
                                    label = "Delete",
                                    onClick = {
                                        leaving = true
                                        vm.delete(onDone = { id -> onDeleted(id) })
                                    },
                                    icon = Icons.Outlined.Delete,
                                    destructive = true,
                                ),
                            ),
                        )
                    }
                },
            )
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        if (document == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding))
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.screen),
            ) {
                Spacer(modifier = Modifier.height(spacing.sm))
                val hasTitle = document.title.isNotBlank()
                Text(
                    text = if (hasTitle) document.title.trim() else "Untitled document",
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (hasTitle) colors.onBackground else colors.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(spacing.md))
                StatusBanner(document = document)
                Spacer(modifier = Modifier.height(spacing.lg))
                DetailsCard(document = document)
                Spacer(modifier = Modifier.height(spacing.xl))
                SectionHeader("ATTACHMENT")
                Spacer(modifier = Modifier.height(spacing.sm))
                val path = document.fileUri
                if (path != null && path.isNotBlank()) {
                    // Our encrypted files have no photo to preview, so the card opens the file
                    // viewer. The older photos and PDFs keep opening the older viewer.
                    val isSecure = SecureFileStore.isSecure(File(path))
                    AttachmentThumbnail(
                        path = path,
                        onClick = if (isSecure) onViewFile else onOpenAttachment,
                    )
                    val exists by rememberFileExists(path)
                    if (exists == true) {
                        Spacer(modifier = Modifier.height(spacing.md))
                        NeriboButton(
                            text = "View file",
                            onClick = onViewFile,
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = Icons.Outlined.Description,
                        )
                    }
                } else {
                    Text(
                        text = "No file attached. Edit this document to add a photo or a PDF.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(spacing.xxl))
            }
        }
    }
}

@Composable
private fun StatusBanner(document: PersonalDocumentEntity) {
    val expiry = document.expiryDate
    val state = expiryState(expiry, document.remindDaysBefore)
    val detail = if (expiry == null) {
        "This document does not expire, so there is no reminder."
    } else {
        formatDateLong(expiry)
    }
    ExpiryBanner(state = state, label = expiryLabel(expiry), detail = detail)
}

@Composable
private fun DetailsCard(document: PersonalDocumentEntity) {
    val expiry = document.expiryDate
    NeriboCard(modifier = Modifier.fillMaxWidth()) {
        DetailRow(label = "CATEGORY", value = document.category.trim())
        NeriboDivider()
        DetailRow(label = "ISSUER", value = document.issuer.trim())
        NeriboDivider()
        DetailRow(
            label = "ISSUE DATE",
            value = document.issueDate?.let { formatDateLong(it) }.orEmpty(),
        )
        NeriboDivider()
        DetailRow(
            label = "EXPIRY DATE",
            value = expiry?.let { formatDateLong(it) }.orEmpty(),
        )
        NeriboDivider()
        DetailRow(
            label = "REMINDER",
            value = if (expiry == null) "" else reminderLabel(document.remindDaysBefore),
            emptyText = "Off, no expiry date",
        )
        if (document.notes.isNotBlank()) {
            NeriboDivider()
            DetailRow(label = "NOTES", value = document.notes.trim())
        }
    }
}

/** An overline label over its value. A blank value reads as a quiet "Not set". */
@Composable
private fun DetailRow(label: String, value: String, emptyText: String = "Not set") {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.xxs),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )
        if (value.isBlank()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
            )
        } else {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
            )
        }
    }
}
