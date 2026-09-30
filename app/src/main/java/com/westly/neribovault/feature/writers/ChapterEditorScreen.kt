package com.westly.neribovault.feature.writers

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.util.LifecycleSaveEffect
import com.westly.neribovault.core.util.copyToClipboard
import com.westly.neribovault.core.util.countWords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TITLE_PLACEHOLDER = "Chapter title"
private const val BODY_PLACEHOLDER =
    "Begin the scene. Harmattan dust, a danfo horn, a letter that changes everything..."

/** How long typing must pause before the live word count is recalculated. */
private const val WORD_COUNT_DELAY_MS = 150L

/**
 * The distraction-free chapter editor: a serif title, a large roomy body, and nothing else on
 * screen. Autosaves 600ms after the last change and whenever the screen stops. The top bar
 * shows the live word count and a quiet Saved indicator.
 */
@Composable
fun ChapterEditorScreen(
    storyId: String,
    chapterId: String,
    onBack: () -> Unit,
    onOpenChapter: (chapterId: String) -> Unit,
    onDeleted: (deletedId: String?) -> Unit,
) {
    val vm = neriboViewModel(key = "$storyId:$chapterId") { c ->
        ChapterEditorViewModel(storyId, chapterId, c.storiesRepository)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var wordCount by rememberSaveable { mutableStateOf(0) }

    LifecycleSaveEffect(onSave = { vm.flush() })
    BackHandler {
        vm.flush()
        onBack()
    }
    LaunchedEffect(state.notFound) {
        if (state.notFound) onBack()
    }

    val actions = listOfNotNull(
        state.previousId?.let { id ->
            MenuAction(
                label = "Previous chapter",
                onClick = {
                    vm.flush()
                    onOpenChapter(id)
                },
                icon = Icons.Outlined.ChevronLeft,
            )
        },
        state.nextId?.let { id ->
            MenuAction(
                label = "Next chapter",
                onClick = {
                    vm.flush()
                    onOpenChapter(id)
                },
                icon = Icons.Outlined.ChevronRight,
            )
        },
        MenuAction(
            label = "Copy chapter",
            onClick = {
                context.copyToClipboard("Chapter", composeChapterText(vm.currentTitle, vm.currentBody))
                scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar("Copied to clipboard")
                }
            },
            icon = Icons.Outlined.ContentCopy,
        ),
        MenuAction(
            label = "Delete chapter",
            onClick = { vm.deleteChapter(onDone = onDeleted) },
            icon = Icons.Outlined.Delete,
            destructive = true,
        ),
    )

    val saveLabel = when (state.saveStatus) {
        ChapterSaveStatus.Idle -> null
        ChapterSaveStatus.Saving -> "Saving\u2026"
        ChapterSaveStatus.Saved -> "Saved"
    }
    val subtitle = if (state.isLoaded) {
        listOfNotNull(wordCountLabel(wordCount), saveLabel).joinToString(" \u00B7 ")
    } else {
        null
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = "Chapter ${state.chapterNumber}",
                onBack = {
                    vm.flush()
                    onBack()
                },
                subtitle = subtitle,
                actions = { OverflowMenu(actions = actions) },
            )
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        if (state.isLoaded) {
            ChapterEditorContent(
                vm = vm,
                padding = padding,
                onWordCount = { wordCount = it },
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().padding(padding))
        }
    }
}

@Composable
private fun ChapterEditorContent(
    vm: ChapterEditorViewModel,
    padding: PaddingValues,
    onWordCount: (Int) -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current

    // The two fields keep their own text so typing is never delayed; every change is also sent
    // to the ViewModel, which owns saving. rememberSaveable keeps the text across rotation.
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
    // Height of the visible scroll area (it shrinks when the keyboard opens) and the body
    // field's top edge inside the scrolled content. With the cursor rectangle from the text
    // layout they tell us whether the cursor is on screen.
    var viewportHeight by remember { mutableStateOf(0) }
    var bodyTopInContent by remember { mutableStateOf(0) }
    var bodyFocused by remember { mutableStateOf(false) }

    // Keep the cursor in view: whenever it moves, the text re-lays-out (Enter, wrapping) or the
    // visible area changes (keyboard opening). Does nothing while the cursor is comfortable.
    val marginPx = with(density) { 40.dp.toPx() }
    LaunchedEffect(scrollState) {
        snapshotFlow {
            Triple(
                bodyLayout.value,
                bodyValue.selection.end,
                Triple(viewportHeight, bodyTopInContent, bodyFocused),
            )
        }.collectLatest { (layout, cursorOffset, metrics) ->
            val (viewport, bodyTop, focused) = metrics
            if (!focused || layout == null || viewport <= 0) return@collectLatest
            // Let this frame's layout finish so the scroll range is up to date.
            withFrameNanos { }
            val length = layout.layoutInput.text.length
            val rect = runCatching { layout.getCursorRect(cursorOffset.coerceIn(0, length)) }
                .getOrNull() ?: return@collectLatest
            val cursorTop = bodyTop + rect.top
            val cursorBottom = bodyTop + rect.bottom
            val viewTop = scrollState.value.toFloat()
            val viewBottom = viewTop + viewport
            val target = when {
                cursorBottom + marginPx > viewBottom -> cursorBottom + marginPx - viewport
                cursorTop - marginPx < viewTop -> cursorTop - marginPx
                else -> return@collectLatest
            }
            scrollState.animateScrollTo(
                target.toInt().coerceAtLeast(0),
                animationSpec = tween(durationMillis = 120),
            )
        }
    }

    // The word count is recalculated off the main thread shortly after typing pauses, so very
    // long chapters never make typing stutter. The first count is immediate.
    LaunchedEffect(Unit) {
        var first = true
        snapshotFlow { bodyValue.text }.collectLatest { text ->
            if (!first) delay(WORD_COUNT_DELAY_MS)
            first = false
            onWordCount(withContext(Dispatchers.Default) { countWords(text) })
        }
    }

    LaunchedEffect(Unit) {
        // A no-op unless the text was restored after the process was killed.
        vm.onTitleChange(titleValue.text)
        vm.onBodyChange(bodyValue.text)
        // Only a brand-new, empty chapter opens the keyboard by itself.
        if (vm.isNew && titleValue.text.isEmpty() && bodyValue.text.isEmpty()) {
            runCatching { titleFocus.requestFocus() }
        }
    }

    val titleStyle = MaterialTheme.typography.titleLarge.copy(color = colors.onBackground)
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(
        color = colors.onBackground,
        fontSize = 18.sp,
        lineHeight = 28.sp,
    )

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
            BasicTextField(
                value = titleValue,
                onValueChange = { new ->
                    val cleaned = if ('\n' in new.text) new.copy(text = new.text.replace('\n', ' ')) else new
                    titleValue = cleaned
                    vm.onTitleChange(cleaned.text)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(titleFocus),
                textStyle = titleStyle,
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
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
            Spacer(modifier = Modifier.height(spacing.md))
            BasicTextField(
                value = bodyValue,
                onValueChange = { new ->
                    bodyValue = new
                    vm.onBodyChange(new.text)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    // The field is at least this tall, so tapping the empty space below the
                    // text puts the cursor at the end, and taps on the text place the cursor
                    // exactly where you touched.
                    .heightIn(min = 320.dp)
                    .onGloballyPositioned { bodyTopInContent = it.positionInParent().y.toInt() }
                    .focusRequester(bodyFocus)
                    .onFocusChanged { focus -> bodyFocused = focus.isFocused },
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
            Spacer(modifier = Modifier.height(spacing.xxxl))
        }
    }
}
