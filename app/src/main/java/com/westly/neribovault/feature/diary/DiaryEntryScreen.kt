package com.westly.neribovault.feature.diary

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.util.LifecycleSaveEffect
import com.westly.neribovault.core.util.copyToClipboard
import com.westly.neribovault.core.util.countWords
import com.westly.neribovault.core.util.formatDateLong
import com.westly.neribovault.core.util.formatRelative
import com.westly.neribovault.core.util.shareText
import com.westly.neribovault.feature.diary.components.DiaryDatePickerDialog
import com.westly.neribovault.feature.diary.components.DiaryTagEditor
import com.westly.neribovault.feature.diary.components.MoodSelector
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TITLE_PLACEHOLDER = "Title (optional)"
private const val BODY_PLACEHOLDER =
    "What happened today? The rain, the light, who you spoke to..."

/**
 * Full-screen diary entry: the day, an optional serif title, a roomy body, mood, tags and a slim
 * footer. Autosaves 600ms after the last change and whenever the screen stops. No Save button.
 */
@Composable
fun DiaryEntryScreen(
    entryId: String,
    onBack: () -> Unit,
    onDeleted: (deletedId: String?) -> Unit,
) {
    val vm = neriboViewModel(key = entryId) { c -> DiaryEntryViewModel(entryId, c.diaryRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    DiaryPrivacyGuard()
    LifecycleSaveEffect(onSave = { vm.flush() })
    BackHandler {
        vm.flush()
        onBack()
    }
    LaunchedEffect(state.notFound) {
        if (state.notFound) onBack()
    }

    val actions = listOf(
        MenuAction(
            label = "Copy",
            onClick = {
                context.copyToClipboard(
                    "Diary entry",
                    composeEntryText(state.entryDate, vm.currentTitle, vm.currentBody),
                )
                scope.launch { snackbarHostState.showSnackbar("Copied to clipboard") }
            },
            icon = Icons.Outlined.ContentCopy,
        ),
        MenuAction(
            label = "Share",
            // No subject line, so no diary text is put in the share sheet's title.
            onClick = {
                context.shareText(
                    null,
                    composeEntryText(state.entryDate, vm.currentTitle, vm.currentBody),
                )
            },
            icon = Icons.Outlined.Share,
        ),
        MenuAction(
            label = "Delete",
            onClick = { vm.deleteEntry(onDone = onDeleted) },
            icon = Icons.Outlined.Delete,
            destructive = true,
        ),
    )

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = if (vm.isNew) "New entry" else "Diary entry",
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
            EditorContent(vm = vm, state = state, padding = padding)
        } else {
            Box(modifier = Modifier.fillMaxSize().padding(padding))
        }
    }
}

private fun subtitleFor(state: DiaryEntryUiState): String? = when (state.saveStatus) {
    DiarySaveStatus.Idle -> null
    DiarySaveStatus.Saving -> "Saving\u2026"
    DiarySaveStatus.Saved -> "Saved"
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun EditorContent(
    vm: DiaryEntryViewModel,
    state: DiaryEntryUiState,
    padding: PaddingValues,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme

    // The two fields keep their own text so typing is never delayed; every change is also
    // sent to the ViewModel, which owns saving. rememberSaveable keeps the text across rotation.
    var titleValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(vm.currentTitle, TextRange(vm.currentTitle.length)))
    }
    var bodyValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(vm.currentBody, TextRange(vm.currentBody.length)))
    }
    val titleFocus = remember { FocusRequester() }
    val bodyFocus = remember { FocusRequester() }
    val bodyLayout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    // Height of the visible scroll area (it shrinks when the keyboard opens) and the body
    // field's top edge inside the scrolled content. Together with the cursor rectangle from the
    // text layout they tell us whether the cursor is on screen.
    var viewportHeight by remember { mutableStateOf(0) }
    var bodyTopInContent by remember { mutableStateOf(0) }
    val imeVisible = WindowInsets.isImeVisible
    val imeVisibleNow by androidx.compose.runtime.rememberUpdatedState(imeVisible)
    // Whether the keyboard was up the last time this window had focus.
    var imeWasUp by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val windowInfo = LocalWindowInfo.current

    // Which field the editor currently considers focused: 0 = none, 1 = title, 2 = body.
    var focusedField by remember { mutableStateOf(0) }

    // Remember whether the keyboard was open while we owned the window focus.
    LaunchedEffect(windowInfo) {
        snapshotFlow { windowInfo.isWindowFocused to imeVisibleNow }.collect { (focused, ime) ->
            if (focused) imeWasUp = ime
        }
    }

    // When another window (the Add tag dialog, a floating app) takes the keyboard and then gives
    // focus back, the field still believes it is focused and tapping it would never reopen the
    // keyboard. On regaining focus we restart the field's input session, but only bring the
    // keyboard back if it was actually open before. Otherwise the keyboard stays closed.
    LaunchedEffect(windowInfo) {
        snapshotFlow { windowInfo.isWindowFocused }.collect { windowFocused ->
            if (windowFocused && focusedField != 0 && imeWasUp) {
                val target = if (focusedField == 1) titleFocus else bodyFocus
                focusManager.clearFocus(force = true)
                delay(50)
                runCatching { target.requestFocus() }
                keyboard?.show()
            }
        }
    }

    // Keep the cursor in view. Runs whenever the cursor moves, the text re-lays-out (Enter,
    // spaces, wrapping) or the visible area changes (keyboard opening/closing). Does nothing
    // while the cursor is already comfortably visible.
    val marginPx = with(density) { 28.dp.toPx() }
    LaunchedEffect(scrollState) {
        snapshotFlow {
            Triple(
                bodyLayout.value,
                bodyValue.selection.end,
                Triple(viewportHeight, bodyTopInContent, focusedField),
            )
        }.collectLatest { (layout, cursorOffset, _) ->
            if (focusedField != 2 || layout == null || viewportHeight <= 0) return@collectLatest
            // Let this frame's layout finish so the scroll range is up to date.
            withFrameNanos { }
            val length = layout.layoutInput.text.length
            val rect = runCatching { layout.getCursorRect(cursorOffset.coerceIn(0, length)) }
                .getOrNull() ?: return@collectLatest
            val cursorTop = bodyTopInContent + rect.top
            val cursorBottom = bodyTopInContent + rect.bottom
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
        vm.onBodyChange(bodyValue.text)
        if (vm.isNew && titleValue.text.isEmpty() && bodyValue.text.isEmpty()) {
            runCatching { bodyFocus.requestFocus() }
        }
    }

    val titleStyle = MaterialTheme.typography.titleLarge.copy(color = colors.onBackground)
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onBackground)
    val wordCount = remember(bodyValue.text) { countWords(bodyValue.text) }
    var datePickerOpen by rememberSaveable { mutableStateOf(false) }

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
            Text(
                text = formatDateLong(state.entryDate),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier
                    .clickable(onClickLabel = "Change the date", onClick = { datePickerOpen = true })
                    .heightIn(min = 48.dp)
                    .padding(vertical = spacing.md),
            )
            BasicTextField(
                value = titleValue,
                onValueChange = { new ->
                    val cleaned = if ('\n' in new.text) new.copy(text = new.text.replace('\n', ' ')) else new
                    titleValue = cleaned
                    vm.onTitleChange(cleaned.text)
                },
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
                textStyle = titleStyle,
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(
                    onNext = { runCatching { bodyFocus.requestFocus() } },
                ),
                maxLines = 4,
                decorationBox = { inner ->
                    Box(modifier = Modifier.fillMaxWidth()) {
                        if (titleValue.text.isEmpty()) {
                            Text(
                                text = TITLE_PLACEHOLDER,
                                style = titleStyle.copy(color = colors.onSurfaceVariant),
                            )
                        }
                        inner()
                    }
                },
            )
            Spacer(modifier = Modifier.height(spacing.sm))
            BasicTextField(
                value = bodyValue,
                onValueChange = { new ->
                    bodyValue = new
                    vm.onBodyChange(new.text)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    // The field itself is at least this tall, so tapping the empty space below
                    // the text puts the cursor at the end, and taps on the text place the cursor
                    // exactly where you touched.
                    .heightIn(min = 260.dp)
                    .onGloballyPositioned { bodyTopInContent = it.positionInParent().y.toInt() }
                    .focusRequester(bodyFocus)
                    .onFocusChanged { focus ->
                        if (focus.isFocused) {
                            focusedField = 2
                        } else if (focusedField == 2) {
                            focusedField = 0
                        }
                    },
                textStyle = bodyStyle,
                onTextLayout = { bodyLayout.value = it },
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                decorationBox = { inner ->
                    Box(modifier = Modifier.fillMaxWidth()) {
                        if (bodyValue.text.isEmpty()) {
                            Text(
                                text = BODY_PLACEHOLDER,
                                style = bodyStyle.copy(color = colors.onSurfaceVariant),
                            )
                        }
                        inner()
                    }
                },
            )
            Spacer(modifier = Modifier.height(spacing.md))
            MoodSelector(selected = state.mood, onSelect = { vm.setMood(it) })
            Spacer(modifier = Modifier.height(spacing.sm))
            DiaryTagEditor(
                tags = state.tags,
                onAddTag = { vm.addTag(it) },
                onRemoveTag = { vm.removeTag(it) },
            )
            Spacer(modifier = Modifier.height(spacing.xl))
        }
        EditorFooter(wordCount = wordCount, updatedAt = state.updatedAt)
    }

    if (datePickerOpen) {
        DiaryDatePickerDialog(
            initialDate = state.entryDate,
            onConfirm = { day ->
                vm.setDate(day)
                datePickerOpen = false
            },
            onDismiss = { datePickerOpen = false },
        )
    }
}

@Composable
private fun EditorFooter(wordCount: Int, updatedAt: Long?) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
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
                text = if (wordCount == 1) "1 word" else "$wordCount words",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
            if (updatedAt != null) {
                Text(
                    text = "Edited ${formatRelative(updatedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}
