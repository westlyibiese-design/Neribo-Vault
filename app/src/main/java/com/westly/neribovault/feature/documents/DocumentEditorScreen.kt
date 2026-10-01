package com.westly.neribovault.feature.documents

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.ConfirmDialog
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTextField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.core.util.formatDateLong
import com.westly.neribovault.core.util.startOfDayMillis
import com.westly.neribovault.feature.documents.components.AttachmentThumbnail
import com.westly.neribovault.feature.documents.components.DocumentDatePickerDialog
import com.westly.neribovault.feature.documents.reminders.DocumentReminderScheduler

private const val PICKER_NONE = ""
private const val PICKER_ISSUE = "issue"
private const val PICKER_EXPIRY = "expiry"

/**
 * The document form: title, category, issuer, issue and expiry dates, "Remind me", the attachment
 * and notes. It is deliberate, not autosaved: nothing is written until the owner taps Save, and
 * leaving with unsaved changes asks first. On Android 13 and above, saving a document with a
 * reminder asks for the notification permission the first time, without blocking the save.
 */
@Composable
fun DocumentEditorScreen(
    documentId: String,
    onBack: () -> Unit,
    onDeleted: (deletedId: String?) -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val vm = neriboViewModel(key = documentId) { c ->
        DocumentEditorViewModel(
            appContext,
            documentId,
            c.personalDocumentsRepository,
            DocumentFileStore(appContext),
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }

    val requestLeave: () -> Unit = {
        if (vm.hasUnsavedChanges()) confirmDiscard = true else onBack()
    }
    BackHandler { requestLeave() }
    LaunchedEffect(state.notFound) {
        if (state.notFound) onBack()
    }
    LaunchedEffect(vm) {
        vm.messages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    var notificationsOn by remember {
        mutableStateOf(DocumentReminderScheduler.areNotificationsAllowed(context))
    }
    // The person can change the setting in the system Settings app and come back.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsOn = DocumentReminderScheduler.areNotificationsAllowed(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        notificationsOn = DocumentReminderScheduler.areNotificationsAllowed(context)
    }
    // Called when a document with a reminder was saved. On Android 13 and above this asks for the
    // notification permission, but only the first time.
    val askForNotifications: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !notificationsOn &&
            DocumentReminderScheduler.shouldAskForPermission(context)
        ) {
            DocumentReminderScheduler.markPermissionAsked(context)
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val openNotificationSettings: () -> Unit = {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }
        runCatching { context.startActivity(intent) }
    }

    // The system document picker needs no storage permission.
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> if (uri != null) vm.attach(uri) },
    )
    val onPickFile: () -> Unit = {
        filePicker.launch(arrayOf("image/*", "application/pdf"))
    }

    val onSave: () -> Unit = {
        vm.save { reminderScheduled ->
            if (reminderScheduled) askForNotifications()
            onBack()
        }
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = if (vm.isNew) "New document" else "Edit document",
                onBack = requestLeave,
                actions = {
                    if (!vm.isNew) {
                        OverflowMenu(
                            actions = listOf(
                                MenuAction(
                                    label = "Delete",
                                    onClick = { vm.deleteDocument(onDone = onDeleted) },
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
        if (state.isLoaded) {
            EditorContent(
                vm = vm,
                state = state,
                padding = padding,
                notificationsOn = notificationsOn,
                onOpenNotificationSettings = openNotificationSettings,
                onPickFile = onPickFile,
                onSave = onSave,
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().padding(padding))
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            message = "What you changed on this document hasn't been saved.",
            confirmLabel = "Discard",
            onConfirm = {
                confirmDiscard = false
                onBack()
            },
            onDismiss = { confirmDiscard = false },
            destructive = true,
            dismissLabel = "Keep editing",
        )
    }
}

@Composable
private fun EditorContent(
    vm: DocumentEditorViewModel,
    state: DocumentEditorUiState,
    padding: PaddingValues,
    notificationsOn: Boolean,
    onOpenNotificationSettings: () -> Unit,
    onPickFile: () -> Unit,
    onSave: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme

    // Each text field keeps its own text so typing is never delayed; every change is also sent to
    // the ViewModel, which owns saving.
    var title by rememberSaveable { mutableStateOf(vm.currentTitle) }
    var category by rememberSaveable { mutableStateOf(vm.currentCategory) }
    var issuer by rememberSaveable { mutableStateOf(vm.currentIssuer) }
    var notes by rememberSaveable { mutableStateOf(vm.currentNotes) }
    var picker by rememberSaveable { mutableStateOf(PICKER_NONE) }

    LaunchedEffect(Unit) {
        // A no-op unless the text was restored after the process was killed.
        vm.onTitleChange(title)
        vm.onCategoryChange(category)
        vm.onIssuerChange(issuer)
        vm.onNotesChange(notes)
    }

    val hasExpiry = state.expiryDate != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screen),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Spacer(modifier = Modifier.height(spacing.xs))
            NeriboTextField(
                value = title,
                onValueChange = { value ->
                    title = value
                    vm.onTitleChange(value)
                },
                label = "Title",
                placeholder = "e.g. My international passport",
                isError = state.titleError != null,
                supportingText = state.titleError,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            )

            SectionHeader("CATEGORY")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DOCUMENT_CATEGORIES.forEach { name ->
                    NeriboChip(
                        label = name,
                        selected = category.trim().equals(name, ignoreCase = true),
                        onClick = {
                            category = name
                            vm.onCategoryChange(name)
                        },
                    )
                }
            }
            NeriboTextField(
                value = category,
                onValueChange = { value ->
                    category = value
                    vm.onCategoryChange(value)
                },
                placeholder = "Or type your own category",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            )

            NeriboTextField(
                value = issuer,
                onValueChange = { value ->
                    issuer = value
                    vm.onIssuerChange(value)
                },
                label = "Issuer",
                placeholder = "e.g. Nigeria Immigration Service",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            )

            DateField(
                label = "Issue date",
                value = state.issueDate,
                onClick = { picker = PICKER_ISSUE },
                onClear = { vm.setIssueDate(null) },
            )
            DateField(
                label = "Expiry date",
                value = state.expiryDate,
                onClick = { picker = PICKER_EXPIRY },
                onClear = { vm.setExpiryDate(null) },
            )
            if (state.dateError != null) {
                Text(
                    text = state.dateError,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                )
            }

            SectionHeader("REMIND ME")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                REMINDER_CHOICES.forEach { days ->
                    NeriboChip(
                        label = "$days days",
                        selected = state.remindDaysBefore == days,
                        onClick = { if (hasExpiry) vm.setRemindDays(days) },
                        modifier = if (hasExpiry) Modifier else Modifier.alpha(0.4f),
                    )
                }
            }
            ReminderHelp(
                hasExpiry = hasExpiry,
                remindDaysBefore = state.remindDaysBefore,
                notificationsOn = notificationsOn,
                onOpenNotificationSettings = onOpenNotificationSettings,
            )

            SectionHeader("ATTACHMENT")
            AttachmentSection(
                path = state.attachmentPath,
                isImporting = state.isImporting,
                onPickFile = onPickFile,
                onRemove = { vm.removeAttachment() },
            )

            NeriboTextField(
                value = notes,
                onValueChange = { value ->
                    notes = value
                    vm.onNotesChange(value)
                },
                label = "Notes",
                placeholder = "Document number, where the original is kept, renewal steps...",
                singleLine = false,
                minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            Spacer(modifier = Modifier.height(spacing.md))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.screen, vertical = spacing.md),
        ) {
            NeriboButton(
                text = if (state.isSaving) "Saving\u2026" else "Save",
                onClick = onSave,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isSaving && !state.isImporting,
            )
        }
    }

    if (picker != PICKER_NONE) {
        val today = startOfDayMillis(System.currentTimeMillis())
        val isIssue = picker == PICKER_ISSUE
        DocumentDatePickerDialog(
            initialDate = (if (isIssue) state.issueDate else state.expiryDate) ?: today,
            onConfirm = { picked ->
                if (isIssue) vm.setIssueDate(picked) else vm.setExpiryDate(picked)
                picker = PICKER_NONE
            },
            onDismiss = { picker = PICKER_NONE },
        )
    }
}

/** A tappable date row on the input fill, with a small clear button once a date is chosen. */
@Composable
private fun DateField(
    label: String,
    value: Long?,
    onClick: () -> Unit,
    onClear: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val shape = MaterialTheme.shapes.medium
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(colors.surfaceVariant, shape)
            .clickable(onClickLabel = "Choose $label", onClick = onClick)
            .padding(start = spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.CalendarToday,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = colors.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(spacing.md))
        Column(modifier = Modifier.weight(1f).padding(vertical = spacing.sm)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
            Text(
                text = if (value != null) formatDateLong(value) else "Not set",
                style = MaterialTheme.typography.bodyLarge,
                color = if (value != null) colors.onSurface else colors.onSurfaceVariant,
            )
        }
        if (value != null) {
            NeriboIconButton(
                icon = Icons.Outlined.Close,
                contentDescription = "Clear $label",
                onClick = onClear,
            )
        } else {
            Spacer(modifier = Modifier.width(spacing.lg))
        }
    }
}

/** The small note under "Remind me": what to do first, when it fires, and a nudge if notifications are off. */
@Composable
private fun ReminderHelp(
    hasExpiry: Boolean,
    remindDaysBefore: Int,
    notificationsOn: Boolean,
    onOpenNotificationSettings: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val warning = NeriboTheme.extraColors.warning
    if (!hasExpiry) {
        Text(
            text = "Add an expiry date to turn on reminders.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        return
    }
    Text(
        text = "You'll be reminded around 9:00 AM, ${reminderLabel(remindDaysBefore)} the expiry date. " +
            "Reminders can arrive a few minutes late.",
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
    )
    if (!notificationsOn) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Reminders are off. Allow notifications in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = warning,
                modifier = Modifier.weight(1f),
            )
            NeriboButton(
                text = "Settings",
                onClick = onOpenNotificationSettings,
                style = ButtonStyle.Text,
            )
        }
    }
}

/** Add file, or a small preview with Replace and Remove. */
@Composable
private fun AttachmentSection(
    path: String?,
    isImporting: Boolean,
    onPickFile: () -> Unit,
    onRemove: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        if (path != null) {
            AttachmentThumbnail(path = path, previewHeight = 140.dp)
        }
        if (isImporting) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = colors.primary,
                trackColor = colors.surfaceVariant,
            )
        }
        if (path == null) {
            NeriboButton(
                text = if (isImporting) "Adding file\u2026" else "Add file",
                onClick = onPickFile,
                enabled = !isImporting,
                style = ButtonStyle.Secondary,
                leadingIcon = Icons.Outlined.AttachFile,
            )
            Text(
                text = "A photo or a PDF, up to 25 MB. It is copied into the app and kept private.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                NeriboButton(
                    text = if (isImporting) "Adding file\u2026" else "Replace",
                    onClick = onPickFile,
                    enabled = !isImporting,
                    style = ButtonStyle.Secondary,
                    leadingIcon = Icons.Outlined.AttachFile,
                )
                NeriboButton(
                    text = "Remove",
                    onClick = onRemove,
                    enabled = !isImporting,
                    style = ButtonStyle.Text,
                )
            }
        }
    }
}
