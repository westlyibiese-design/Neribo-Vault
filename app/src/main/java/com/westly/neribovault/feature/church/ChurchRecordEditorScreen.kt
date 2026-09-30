package com.westly.neribovault.feature.church

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboChip
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
import com.westly.neribovault.feature.church.components.ChurchDatePickerDialog
import com.westly.neribovault.feature.church.components.ChurchTagEditor
import com.westly.neribovault.feature.church.components.ScriptureSuggestions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val FIELD_NONE = 0
private const val FIELD_TITLE = 1
private const val FIELD_SPEAKER = 2
private const val FIELD_CHURCH = 3
private const val FIELD_SCRIPTURE = 4
private const val FIELD_SUMMARY = 5
private const val FIELD_NOTES = 6
private const val FIELD_COUNT = 6

/**
 * Full-screen record editor: type chips, serif title, date, speaker, church, scripture with book
 * suggestions, summary, long notes and tags. Autosaves 600ms after the last change and whenever
 * the screen stops. No Save button.
 */
@Composable
fun ChurchRecordEditorScreen(
    recordId: String,
    onBack: () -> Unit,
    onDeleted: (deletedId: String?) -> Unit,
) {
    val vm = neriboViewModel(key = recordId) { c ->
        ChurchRecordEditorViewModel(recordId, c.churchRepository)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

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
            label = if (state.isPinned) "Unpin" else "Pin",
            onClick = { vm.togglePinned() },
            icon = Icons.Outlined.PushPin,
        ),
        MenuAction(
            label = "Copy",
            onClick = {
                context.copyToClipboard("Church record", vm.plainText())
                scope.launch { snackbarHostState.showSnackbar("Copied to clipboard") }
            },
            icon = Icons.Outlined.ContentCopy,
        ),
        MenuAction(
            label = "Share",
            onClick = { context.shareText(vm.currentTitle.ifBlank { null }, vm.plainText()) },
            icon = Icons.Outlined.Share,
        ),
        MenuAction(
            label = "Delete",
            onClick = { vm.deleteRecord(onDone = onDeleted) },
            icon = Icons.Outlined.Delete,
            destructive = true,
        ),
    )

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = if (vm.isNew) "New record" else "Record",
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

private fun subtitleFor(state: ChurchEditorUiState): String? {
    val status = when (state.saveStatus) {
        ChurchSaveStatus.Idle -> null
        ChurchSaveStatus.Saving -> "Saving\u2026"
        ChurchSaveStatus.Saved -> "Saved"
    }
    return listOfNotNull(if (state.isPinned) "Pinned" else null, status)
        .joinToString(" \u00B7 ")
        .ifEmpty { null }
}

/** A text field's state that survives rotation and process death. */
@Composable
private fun rememberFieldState(initial: String): MutableState<TextFieldValue> =
    rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    }

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun EditorContent(
    vm: ChurchRecordEditorViewModel,
    state: ChurchEditorUiState,
    padding: PaddingValues,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme

    // Each field keeps its own text so typing is never delayed; every change is also sent to
    // the ViewModel, which owns saving.
    var titleValue by rememberFieldState(vm.currentTitle)
    var speakerValue by rememberFieldState(vm.currentSpeaker)
    var churchValue by rememberFieldState(vm.currentChurch)
    var scriptureValue by rememberFieldState(vm.currentScripture)
    var summaryValue by rememberFieldState(vm.currentSummary)
    var notesValue by rememberFieldState(vm.currentNotes)
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    val requesters = remember { List(FIELD_COUNT) { FocusRequester() } }
    val notesLayout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    // Height of the visible scroll area (it shrinks when the keyboard opens) and the notes
    // field's top edge inside the scrolled content. With the cursor rectangle from the text
    // layout they tell us whether the cursor is on screen.
    var viewportHeight by remember { mutableStateOf(0) }
    var notesTopInContent by remember { mutableStateOf(0) }
    val imeVisible = WindowInsets.isImeVisible
    val imeVisibleNow by rememberUpdatedState(imeVisible)
    // Whether the keyboard was up the last time this window had focus.
    var imeWasUp by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val windowInfo = LocalWindowInfo.current

    // Which field the editor currently considers focused (one of the FIELD_ constants).
    var focusedField by remember { mutableStateOf(FIELD_NONE) }

    fun handleFocus(field: Int, focused: Boolean) {
        if (focused) {
            focusedField = field
        } else if (focusedField == field) {
            focusedField = FIELD_NONE
        }
    }

    // Remember whether the keyboard was open while we owned the window focus.
    LaunchedEffect(windowInfo) {
        snapshotFlow { windowInfo.isWindowFocused to imeVisibleNow }.collect { (focused, ime) ->
            if (focused) imeWasUp = ime
        }
    }

    // When another window (the Add tag dialog, a floating app) takes the keyboard and then gives
    // focus back, the field still believes it is focused and tapping it would never reopen the
    // keyboard. On regaining focus we restart the field's input session, but only bring the
    // keyboard back if it was actually open before.
    LaunchedEffect(windowInfo) {
        snapshotFlow { windowInfo.isWindowFocused }.collect { windowFocused ->
            if (windowFocused && focusedField != FIELD_NONE && imeWasUp) {
                val target = requesters[focusedField - 1]
                focusManager.clearFocus(force = true)
                delay(50)
                runCatching { target.requestFocus() }
                keyboard?.show()
            }
        }
    }

    // Keep the notes cursor in view while writing during a service. Runs whenever the cursor
    // moves, the text re-lays-out (Enter, spaces, wrapping) or the visible area changes
    // (keyboard opening/closing). Does nothing while the cursor is already comfortably visible.
    val marginPx = with(density) { 28.dp.toPx() }
    LaunchedEffect(scrollState) {
        snapshotFlow {
            Triple(
                notesLayout.value,
                notesValue.selection.end,
                Triple(viewportHeight, notesTopInContent, focusedField),
            )
        }.collectLatest { (layout, cursorOffset, _) ->
            if (focusedField != FIELD_NOTES || layout == null || viewportHeight <= 0) {
                return@collectLatest
            }
            // Let this frame's layout finish so the scroll range is up to date.
            withFrameNanos { }
            val length = layout.layoutInput.text.length
            val rect = runCatching { layout.getCursorRect(cursorOffset.coerceIn(0, length)) }
                .getOrNull() ?: return@collectLatest
            val cursorTop = notesTopInContent + rect.top
            val cursorBottom = notesTopInContent + rect.bottom
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
        vm.onSpeakerChange(speakerValue.text)
        vm.onChurchChange(churchValue.text)
        vm.onScriptureChange(scriptureValue.text)
        vm.onSummaryChange(summaryValue.text)
        vm.onNotesChange(notesValue.text)
        if (vm.isNew && titleValue.text.isEmpty() && notesValue.text.isEmpty()) {
            runCatching { requesters[FIELD_TITLE - 1].requestFocus() }
        }
    }

    val titleStyle = MaterialTheme.typography.titleLarge.copy(color = colors.onBackground)
    val fieldStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onBackground)
    val wordCount = remember(notesValue.text) { countWords(notesValue.text) }
    val suggestions = remember(scriptureValue.text) { suggestBooks(scriptureValue.text) }

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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CHURCH_TYPES.forEach { type ->
                    NeriboChip(
                        label = type.label,
                        selected = state.type == type.value,
                        onClick = { vm.setType(type.value) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(spacing.sm))
            EditorField(
                value = titleValue,
                onValueChange = { new ->
                    val cleaned = if ('\n' in new.text) new.copy(text = new.text.replace('\n', ' ')) else new
                    titleValue = cleaned
                    vm.onTitleChange(cleaned.text)
                },
                placeholder = "Title of the message",
                textStyle = titleStyle,
                focusRequester = requesters[FIELD_TITLE - 1],
                onFocusChange = { handleFocus(FIELD_TITLE, it) },
                singleLine = false,
                maxLines = 4,
                capitalization = KeyboardCapitalization.Sentences,
                onNext = { runCatching { requesters[FIELD_SPEAKER - 1].requestFocus() } },
            )
            DateRow(
                text = formatDateLong(state.recordDate),
                onClick = { showDatePicker = true },
            )
            EditorField(
                label = "Speaker",
                value = speakerValue,
                onValueChange = { new ->
                    speakerValue = new
                    vm.onSpeakerChange(new.text)
                },
                placeholder = "e.g. Pastor Ifeoma",
                textStyle = fieldStyle,
                focusRequester = requesters[FIELD_SPEAKER - 1],
                onFocusChange = { handleFocus(FIELD_SPEAKER, it) },
                onNext = { runCatching { requesters[FIELD_CHURCH - 1].requestFocus() } },
            )
            EditorField(
                label = "Church",
                value = churchValue,
                onValueChange = { new ->
                    churchValue = new
                    vm.onChurchChange(new.text)
                },
                placeholder = "Where did you hear it?",
                textStyle = fieldStyle,
                focusRequester = requesters[FIELD_CHURCH - 1],
                onFocusChange = { handleFocus(FIELD_CHURCH, it) },
                onNext = { runCatching { requesters[FIELD_SCRIPTURE - 1].requestFocus() } },
            )
            EditorField(
                label = "Scripture",
                value = scriptureValue,
                onValueChange = { new ->
                    val cleaned = if ('\n' in new.text) new.copy(text = new.text.replace('\n', ' ')) else new
                    scriptureValue = cleaned
                    vm.onScriptureChange(cleaned.text)
                },
                placeholder = "e.g. John 3:16-21; Romans 8:28",
                textStyle = fieldStyle,
                focusRequester = requesters[FIELD_SCRIPTURE - 1],
                onFocusChange = { handleFocus(FIELD_SCRIPTURE, it) },
                onNext = { runCatching { requesters[FIELD_SUMMARY - 1].requestFocus() } },
            )
            if (focusedField == FIELD_SCRIPTURE && suggestions.isNotEmpty()) {
                ScriptureSuggestions(
                    books = suggestions,
                    onPick = { book ->
                        val completed = completeBookName(scriptureValue.text, book)
                        scriptureValue = TextFieldValue(completed, TextRange(completed.length))
                        vm.onScriptureChange(completed)
                        // Keep the keyboard up so the owner carries straight on with the chapter.
                        runCatching { requesters[FIELD_SCRIPTURE - 1].requestFocus() }
                    },
                )
            }
            EditorField(
                label = "Summary",
                value = summaryValue,
                onValueChange = { new ->
                    summaryValue = new
                    vm.onSummaryChange(new.text)
                },
                placeholder = "The one-sentence takeaway",
                textStyle = fieldStyle,
                focusRequester = requesters[FIELD_SUMMARY - 1],
                onFocusChange = { handleFocus(FIELD_SUMMARY, it) },
                singleLine = false,
                capitalization = KeyboardCapitalization.Sentences,
            )
            Text(
                text = "NOTES",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.md, bottom = spacing.xs),
            )
            // The notes field is a direct child of the scrolling column so its position inside
            // the scrolled content can be measured for the cursor-follow logic above.
            BasicTextField(
                value = notesValue,
                onValueChange = { new ->
                    notesValue = new
                    vm.onNotesChange(new.text)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    // At least this tall, so tapping the empty space below the text puts the
                    // cursor at the end, and taps on the text place it where you touched.
                    .heightIn(min = 260.dp)
                    .onGloballyPositioned { notesTopInContent = it.positionInParent().y.toInt() }
                    .focusRequester(requesters[FIELD_NOTES - 1])
                    .onFocusChanged { handleFocus(FIELD_NOTES, it.isFocused) },
                textStyle = fieldStyle,
                onTextLayout = { notesLayout.value = it },
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                decorationBox = { inner ->
                    Box(modifier = Modifier.fillMaxWidth()) {
                        if (notesValue.text.isEmpty()) {
                            Text(
                                text = "Write as you listen. Points, verses, the words that stayed with you...",
                                style = fieldStyle.copy(color = colors.onSurfaceVariant),
                            )
                        }
                        inner()
                    }
                },
            )
            Spacer(modifier = Modifier.height(spacing.md))
            ChurchTagEditor(
                tags = state.tags,
                onAddTag = { vm.addTag(it) },
                onRemoveTag = { vm.removeTag(it) },
            )
            Spacer(modifier = Modifier.height(spacing.xl))
        }
        EditorFooter(wordCount = wordCount, updatedAt = state.updatedAt)
    }

    if (showDatePicker) {
        ChurchDatePickerDialog(
            initialDate = state.recordDate,
            onConfirm = { picked ->
                vm.setDate(picked)
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false },
        )
    }
}

/** The date of the message. Tapping it opens the date picker. */
@Composable
private fun DateRow(text: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = "Change date", onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.CalendarToday,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = colors.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(spacing.sm))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
        )
    }
}

/**
 * A borderless field on the paper background with an optional overline label. Used for every
 * field except the long notes.
 */
@Composable
private fun EditorField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    textStyle: TextStyle,
    focusRequester: FocusRequester,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Words,
    onNext: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    Column(modifier = modifier.fillMaxWidth().padding(top = spacing.md)) {
        if (label != null) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = spacing.xs),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { onFocusChange(it.isFocused) },
            textStyle = textStyle,
            cursorBrush = SolidColor(colors.primary),
            singleLine = singleLine,
            maxLines = maxLines,
            keyboardOptions = KeyboardOptions(
                capitalization = capitalization,
                imeAction = if (onNext != null) ImeAction.Next else ImeAction.Default,
            ),
            keyboardActions = KeyboardActions(onNext = { onNext?.invoke() }),
            decorationBox = { inner ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (value.text.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = textStyle.copy(color = colors.onSurfaceVariant),
                        )
                    }
                    inner()
                }
            },
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
