package com.westly.neribovault.feature.posts

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.core.util.LifecycleSaveEffect
import com.westly.neribovault.core.util.copyToClipboard
import com.westly.neribovault.core.util.formatDateTime
import com.westly.neribovault.core.util.shareText
import com.westly.neribovault.feature.posts.components.HashtagEditor
import com.westly.neribovault.feature.posts.components.PostDatePickerDialog
import com.westly.neribovault.feature.posts.components.PostTimePickerDialog
import com.westly.neribovault.feature.posts.reminders.PostReminderScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TITLE_PLACEHOLDER = "Title"
private const val CAPTION_PLACEHOLDER = "Write your caption"
private const val NOTES_PLACEHOLDER = "Write private notes"

/**
 * Full-screen post editor: platform chips, a borderless serif working title, a large caption
 * with a live character counter, hashtags, status, a schedule row and private notes. Autosaves
 * 600ms after the last change and whenever the screen stops. There is no Save button.
 */
@Composable
fun PostEditorScreen(
    postId: String,
    onBack: () -> Unit,
    onDeleted: (deletedId: String?) -> Unit,
    onOpenPost: (String) -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val vm = neriboViewModel(key = postId) { c ->
        PostEditorViewModel(postId, c.postsRepository, appContext)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    var notificationsOn by remember {
        mutableStateOf(PostReminderScheduler.areNotificationsAllowed(context))
    }
    // The person can change the setting in the system Settings app and come back.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsOn = PostReminderScheduler.areNotificationsAllowed(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        notificationsOn = PostReminderScheduler.areNotificationsAllowed(context)
    }

    // Called whenever the person schedules a post. On Android 13 and above this asks for the
    // notification permission, but only the first time.
    val askForNotifications: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !notificationsOn &&
            PostReminderScheduler.shouldAskForPermission(context)
        ) {
            PostReminderScheduler.markPermissionAsked(context)
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

    val showMessage: (String) -> Unit = { message ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    LifecycleSaveEffect(onSave = { vm.flush() })
    BackHandler {
        vm.flush()
        onBack()
    }
    LaunchedEffect(state.notFound) {
        if (state.notFound) onBack()
    }

    val actions = buildList<MenuAction> {
        add(
            MenuAction(
                label = "Copy caption",
                onClick = {
                    context.copyToClipboard("Post caption", vm.currentCaption.trimEnd())
                    showMessage("Caption copied")
                },
                icon = Icons.Outlined.ContentCopy,
            ),
        )
        add(
            MenuAction(
                label = "Copy hashtags",
                onClick = {
                    if (state.hashtags.isEmpty()) {
                        showMessage("No hashtags to copy")
                    } else {
                        context.copyToClipboard("Post hashtags", hashtagsLine(state.hashtags))
                        showMessage("Hashtags copied")
                    }
                },
                icon = Icons.Outlined.ContentCopy,
            ),
        )
        add(
            MenuAction(
                label = "Copy both",
                onClick = {
                    context.copyToClipboard(
                        "Post caption and hashtags",
                        composePostText(vm.currentCaption, state.hashtags),
                    )
                    showMessage("Caption and hashtags copied")
                },
                icon = Icons.Outlined.ContentCopy,
            ),
        )
        add(
            MenuAction(
                label = "Share",
                onClick = {
                    context.shareText(
                        vm.currentTitle.ifBlank { null },
                        composePostText(vm.currentCaption, state.hashtags),
                    )
                },
                icon = Icons.Outlined.Share,
            ),
        )
        add(
            MenuAction(
                label = "Duplicate",
                onClick = {
                    vm.duplicate { copyId ->
                        scope.launch {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            if (copyId == null) {
                                snackbarHostState.showSnackbar("Nothing to duplicate yet")
                            } else {
                                val result = snackbarHostState.showSnackbar(
                                    message = "Duplicated as a draft",
                                    actionLabel = "Open",
                                    duration = SnackbarDuration.Short,
                                )
                                if (result == SnackbarResult.ActionPerformed) onOpenPost(copyId)
                            }
                        }
                    }
                },
                icon = Icons.Outlined.FileCopy,
            ),
        )
        if (state.status != STATUS_POSTED) {
            add(
                MenuAction(
                    label = "Mark as posted",
                    onClick = {
                        vm.markPosted()
                        showMessage("Marked as posted")
                    },
                    icon = Icons.Outlined.Check,
                ),
            )
        }
        add(
            MenuAction(
                label = "Delete",
                onClick = { vm.deletePost(onDone = onDeleted) },
                icon = Icons.Outlined.Delete,
                destructive = true,
            ),
        )
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = if (vm.isNew) "New post" else "Post",
                onBack = {
                    vm.flush()
                    onBack()
                },
                subtitle = subtitleFor(state),
                actions = { OverflowMenu(actions = actions) },
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
                onScheduled = askForNotifications,
                onOpenNotificationSettings = openNotificationSettings,
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().padding(padding))
        }
    }
}

private fun subtitleFor(state: PostEditorUiState): String? = when (state.saveStatus) {
    PostSaveStatus.Idle -> null
    PostSaveStatus.Saving -> "Saving\u2026"
    PostSaveStatus.Saved -> "Saved"
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun EditorContent(
    vm: PostEditorViewModel,
    state: PostEditorUiState,
    padding: PaddingValues,
    notificationsOn: Boolean,
    onScheduled: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme

    // The three fields keep their own text so typing is never delayed; every change is also
    // sent to the ViewModel, which owns saving. rememberSaveable keeps the text across rotation.
    var titleValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(vm.currentTitle, TextRange(vm.currentTitle.length)))
    }
    var captionValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(vm.currentCaption, TextRange(vm.currentCaption.length)))
    }
    var notesValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(vm.currentNotes, TextRange(vm.currentNotes.length)))
    }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var pendingPickerMillis by rememberSaveable { mutableStateOf<Long?>(null) }

    val titleFocus = remember { FocusRequester() }
    val captionFocus = remember { FocusRequester() }
    val captionLayout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    // Height of the visible scroll area (it shrinks when the keyboard opens) and the caption
    // field's top edge inside the scrolled content. Together with the cursor rectangle from the
    // text layout they tell us whether the cursor is on screen.
    var viewportHeight by remember { mutableStateOf(0) }
    var captionTopInContent by remember { mutableStateOf(0) }
    val imeVisible = WindowInsets.isImeVisible
    val imeVisibleNow by rememberUpdatedState(imeVisible)
    // Whether the keyboard was up the last time this window had focus.
    var imeWasUp by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val windowInfo = LocalWindowInfo.current

    // Which field the editor currently considers focused: 0 = none, 1 = title, 2 = caption.
    var focusedField by remember { mutableStateOf(0) }

    // Remember whether the keyboard was open while we owned the window focus.
    LaunchedEffect(windowInfo) {
        snapshotFlow { windowInfo.isWindowFocused to imeVisibleNow }.collect { (focused, ime) ->
            if (focused) imeWasUp = ime
        }
    }

    // When a dialog (date or time picker) takes the window focus and then gives it back, the
    // field still believes it is focused and tapping it would never reopen the keyboard. On
    // regaining focus we restart the field's input session, but only bring the keyboard back
    // if it was actually open before.
    LaunchedEffect(windowInfo) {
        snapshotFlow { windowInfo.isWindowFocused }.collect { windowFocused ->
            if (windowFocused && focusedField != 0 && imeWasUp) {
                val target = if (focusedField == 1) titleFocus else captionFocus
                focusManager.clearFocus(force = true)
                delay(50)
                runCatching { target.requestFocus() }
                keyboard?.show()
            }
        }
    }

    // Keep the cursor in view while typing in the caption. Does nothing while the cursor is
    // already comfortably visible.
    val marginPx = with(density) { 28.dp.toPx() }
    LaunchedEffect(scrollState) {
        snapshotFlow {
            Triple(
                captionLayout.value,
                captionValue.selection.end,
                Triple(viewportHeight, captionTopInContent, focusedField),
            )
        }.collectLatest { (layout, cursorOffset, _) ->
            if (focusedField != 2 || layout == null || viewportHeight <= 0) return@collectLatest
            // Let this frame's layout finish so the scroll range is up to date.
            withFrameNanos { }
            val length = layout.layoutInput.text.length
            val rect = runCatching { layout.getCursorRect(cursorOffset.coerceIn(0, length)) }
                .getOrNull() ?: return@collectLatest
            val cursorTop = captionTopInContent + rect.top
            val cursorBottom = captionTopInContent + rect.bottom
            val viewTop = scrollState.value.toFloat()
            val viewBottom = viewTop + viewportHeight
            val target = when {
                cursorBottom + marginPx > viewBottom -> cursorBottom + marginPx - viewportHeight
                cursorTop - marginPx < viewTop -> cursorTop - marginPx
                else -> return@collectLatest
            }
            scrollState.animateScrollTo(
                target.toInt().coerceAtLeast(0),
                animationSpec = tween(durationMillis = 120),
            )
        }
    }

    LaunchedEffect(Unit) {
        // A no-op unless the text was restored after the process was killed.
        vm.onTitleChange(titleValue.text)
        vm.onCaptionChange(captionValue.text)
        vm.onNotesChange(notesValue.text)
        // Quick capture: a brand-new post opens with the title focused and the keyboard up.
        if (vm.isNew && titleValue.text.isEmpty() && captionValue.text.isEmpty()) {
            runCatching { titleFocus.requestFocus() }
            keyboard?.show()
        }
    }

    val titleStyle = MaterialTheme.typography.titleLarge.copy(color = colors.onBackground)
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onBackground)
    val info = platformInfo(state.platform)
    val characterCount = remember(captionValue.text) { captionLength(captionValue.text) }

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
                .onSizeChanged { viewportHeight = it.height }
                .verticalScroll(scrollState)
                .padding(horizontal = spacing.screen),
        ) {
            SectionHeader("PLATFORM")
            ChipRow {
                POST_PLATFORMS.forEach { platform ->
                    NeriboChip(
                        label = platform.displayName,
                        selected = state.platform == platform.key,
                        onClick = { vm.setPlatform(platform.key) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(spacing.lg))
            SectionHeader("Title")
            Spacer(modifier = Modifier.height(spacing.sm))
            BorderlessField(
                value = titleValue,
                onValueChange = { new ->
                    val cleaned = if ('\n' in new.text) new.copy(text = new.text.replace('\n', ' ')) else new
                    titleValue = cleaned
                    vm.onTitleChange(cleaned.text)
                },
                textStyle = titleStyle,
                placeholder = TITLE_PLACEHOLDER,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(titleFocus)
                    .onFocusChanged { focus ->
                        if (focus.isFocused) {
                            focusedField = 1
                        } else if (focusedField == 1) {
                            focusedField = 0
                        }
                    },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { runCatching { captionFocus.requestFocus() } },
                ),
                maxLines = 3,
            )
            Spacer(modifier = Modifier.height(spacing.sm))
            NeriboDivider()
            Spacer(modifier = Modifier.height(spacing.lg))
            SectionHeader("Caption")
            Spacer(modifier = Modifier.height(spacing.sm))
            BorderlessField(
                value = captionValue,
                onValueChange = { new ->
                    captionValue = new
                    vm.onCaptionChange(new.text)
                },
                textStyle = bodyStyle,
                placeholder = CAPTION_PLACEHOLDER,
                modifier = Modifier
                    .fillMaxWidth()
                    // The field itself is at least this tall, so tapping the empty space below
                    // the text puts the cursor at the end.
                    .heightIn(min = 240.dp)
                    .onGloballyPositioned { captionTopInContent = it.positionInParent().y.toInt() }
                    .focusRequester(captionFocus)
                    .onFocusChanged { focus ->
                        if (focus.isFocused) {
                            focusedField = 2
                        } else if (focusedField == 2) {
                            focusedField = 0
                        }
                    },
                onTextLayout = { captionLayout.value = it },
            )
            Spacer(modifier = Modifier.height(spacing.lg))
            SectionHeader("HASHTAGS")
            Spacer(modifier = Modifier.height(spacing.xs))
            HashtagEditor(
                hashtags = state.hashtags,
                onAdd = { vm.addHashtags(it) },
                onRemove = { vm.removeHashtag(it) },
            )
            Spacer(modifier = Modifier.height(spacing.sm))
            val hashtagLimit = info.maxHashtags
            if (hashtagLimit != null && state.hashtags.size > hashtagLimit) {
                Text(
                    text = "${info.displayName} allows up to $hashtagLimit hashtags. " +
                        "You have ${state.hashtags.size}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NeriboTheme.extraColors.warning,
                )
                Spacer(modifier = Modifier.height(spacing.xs))
            }
            Text(
                text = info.hashtagAdvice,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(spacing.lg))
            SectionHeader("STATUS")
            ChipRow {
                POST_STATUSES.forEach { status ->
                    NeriboChip(
                        label = postStatusLabel(status),
                        selected = state.status == status,
                        onClick = {
                            vm.setStatus(status)
                            if (status == STATUS_SCHEDULED) onScheduled()
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(spacing.lg))
            SectionHeader("SCHEDULE")
            ScheduleSection(
                state = state,
                notificationsOn = notificationsOn,
                onPick = { showDatePicker = true },
                onClear = { vm.clearSchedule() },
                onOpenNotificationSettings = onOpenNotificationSettings,
            )
            Spacer(modifier = Modifier.height(spacing.lg))
            SectionHeader("NOTES")
            Spacer(modifier = Modifier.height(spacing.xs))
            BorderlessField(
                value = notesValue,
                onValueChange = { new ->
                    notesValue = new
                    vm.onNotesChange(new.text)
                },
                textStyle = bodyStyle,
                placeholder = NOTES_PLACEHOLDER,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp),
            )
            Spacer(modifier = Modifier.height(spacing.xl))
        }
        CounterFooter(
            count = characterCount,
            limit = info.charLimit,
            platformName = info.displayName,
        )
    }

    if (showDatePicker) {
        PostDatePickerDialog(
            initialPickerMillis = toPickerMillis(state.scheduledAt ?: defaultScheduleMillis()),
            onConfirm = { picked ->
                pendingPickerMillis = picked
                showDatePicker = false
                showTimePicker = true
            },
            onDismiss = { showDatePicker = false },
        )
    }
    if (showTimePicker) {
        val base = state.scheduledAt ?: defaultScheduleMillis()
        PostTimePickerDialog(
            initialHour = hourOf(base),
            initialMinute = minuteOf(base),
            onConfirm = { hour, minute ->
                val day = pendingPickerMillis ?: toPickerMillis(base)
                vm.setSchedule(fromPickerMillis(day, hour, minute))
                pendingPickerMillis = null
                showTimePicker = false
                onScheduled()
            },
            onDismiss = {
                pendingPickerMillis = null
                showTimePicker = false
            },
        )
    }
}

/** The date and time row, its helper text and the notification note. */
@Composable
private fun ScheduleSection(
    state: PostEditorUiState,
    notificationsOn: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val warning = NeriboTheme.extraColors.warning
    val scheduledAt = state.scheduledAt
    val postedAt = state.postedAt
    val isScheduled = state.status == STATUS_SCHEDULED
    val hasPassed = isScheduled && scheduledAt != null && scheduledAt < System.currentTimeMillis()

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Event,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = colors.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(spacing.sm))
            Text(
                text = if (scheduledAt != null) formatPostSchedule(scheduledAt) else "Not scheduled",
                style = MaterialTheme.typography.bodyLarge,
                color = if (scheduledAt != null) colors.onSurface else colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            NeriboButton(
                text = if (scheduledAt == null) "Pick date and time" else "Change",
                onClick = onPick,
                style = ButtonStyle.Secondary,
            )
            if (scheduledAt != null) {
                NeriboButton(text = "Clear", onClick = onClear, style = ButtonStyle.Text)
            }
        }
        Spacer(modifier = Modifier.height(spacing.xs))
        Text(
            text = "Reminders can arrive a few minutes after the time you set.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        if (hasPassed) {
            Spacer(modifier = Modifier.height(spacing.xs))
            Text(
                text = "This time has passed, so the post shows as overdue.",
                style = MaterialTheme.typography.bodySmall,
                color = warning,
            )
        }
        if (isScheduled && !notificationsOn) {
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
        if (state.status == STATUS_POSTED && postedAt != null) {
            Spacer(modifier = Modifier.height(spacing.xs))
            Text(
                text = "Posted ${formatDateTime(postedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/**
 * The live character counter, always visible under the editor. It turns warning-toned at 90% of
 * the limit and error-toned over it. It never blocks saving.
 */
@Composable
private fun CounterFooter(count: Int, limit: Int?, platformName: String) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val isOver = limit != null && count > limit
    val isNear = limit != null && !isOver && count * 10 >= limit * 9
    val counterColor = when {
        isOver -> colors.error
        isNear -> NeriboTheme.extraColors.warning
        else -> colors.onSurfaceVariant
    }
    val counterText = if (limit != null) {
        "${formatCount(count)} / ${formatCount(limit)}"
    } else {
        "${formatCount(count)} characters"
    }
    Column {
        NeriboDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.screen, vertical = spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (isOver) "Over the $platformName limit. You can still save." else platformName,
                style = MaterialTheme.typography.labelSmall,
                color = if (isOver) colors.error else colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = counterText,
                style = MaterialTheme.typography.labelMedium,
                color = counterColor,
            )
        }
    }
}

/** A horizontally scrolling row of chips. */
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(NeriboTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/** A text field with no border or fill, sitting straight on the paper background. */
@Composable
private fun BorderlessField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    textStyle: TextStyle,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    maxLines: Int = Int.MAX_VALUE,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        textStyle = textStyle,
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        maxLines = maxLines,
        onTextLayout = onTextLayout,
        decorationBox = { inner ->
            Box(modifier = Modifier.fillMaxWidth()) {
                if (value.text.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = textStyle.copy(color = colors.onSurfaceVariant.copy(alpha = 0.55f)),
                    )
                }
                inner()
            }
        },
    )
}
