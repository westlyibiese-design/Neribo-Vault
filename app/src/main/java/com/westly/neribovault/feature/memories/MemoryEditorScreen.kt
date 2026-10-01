package com.westly.neribovault.feature.memories

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.util.LifecycleSaveEffect
import com.westly.neribovault.core.util.copyToClipboard
import com.westly.neribovault.core.util.formatDateLong
import com.westly.neribovault.core.util.shareText
import com.westly.neribovault.feature.memories.components.MemoryChipEditor
import com.westly.neribovault.feature.memories.components.MemoryDatePickerDialog
import com.westly.neribovault.feature.memories.components.MemoryPhoto
import kotlinx.coroutines.launch

/**
 * Full-screen memory editor: serif title, date, place, story, people, tags and photos.
 * Autosaves 600ms after the last change and whenever the screen stops. No Save button.
 */
@Composable
fun MemoryEditorScreen(
    memoryId: String,
    onBack: () -> Unit,
    onDeleted: (deletedId: String?) -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val vm = neriboViewModel(key = memoryId) { c ->
        MemoryEditorViewModel(memoryId, c.memoriesRepository, MemoryPhotoStore(appContext))
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
    LaunchedEffect(vm) {
        vm.messages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    // The system Photo Picker needs no permission. The picker is limited to the free slots, so
    // the owner can never choose more photos than the memory can still hold. The multi-pick
    // contract needs a limit of at least 2, so a single free slot uses the single-pick contract.
    val freeSlots = MAX_PHOTOS - state.photos.size
    val pickMany = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxOf(freeSlots, 2)),
        onResult = { uris -> vm.addPhotos(uris) },
    )
    val pickOne = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> if (uri != null) vm.addPhotos(listOf(uri)) },
    )
    val onAddPhotos: () -> Unit = {
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        when {
            freeSlots <= 0 -> scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar("A memory holds up to $MAX_PHOTOS photos.")
            }
            freeSlots == 1 -> pickOne.launch(request)
            else -> pickMany.launch(request)
        }
    }

    val actions = listOf(
        MenuAction(
            label = "Copy text",
            onClick = {
                context.copyToClipboard("Memory", vm.plainText())
                scope.launch { snackbarHostState.showSnackbar("Copied to clipboard") }
            },
            icon = Icons.Outlined.ContentCopy,
        ),
        MenuAction(
            label = "Share text",
            onClick = { context.shareText(vm.currentTitle.ifBlank { null }, vm.plainText()) },
            icon = Icons.Outlined.Share,
        ),
        MenuAction(
            label = "Delete",
            onClick = { vm.deleteMemory(onDone = onDeleted) },
            icon = Icons.Outlined.Delete,
            destructive = true,
        ),
    )

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = if (vm.isNew) "New memory" else "Memory",
                onBack = {
                    vm.flush()
                    onBack()
                },
                subtitle = when (state.saveStatus) {
                    MemorySaveStatus.Idle -> null
                    MemorySaveStatus.Saving -> "Saving\u2026"
                    MemorySaveStatus.Saved -> "Saved"
                },
                actions = { OverflowMenu(actions = actions) },
            )
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        if (state.isLoaded) {
            EditorContent(vm = vm, state = state, padding = padding, onAddPhotos = onAddPhotos)
        } else {
            Box(modifier = Modifier.fillMaxSize().padding(padding))
        }
    }
}

/** A text field's state that survives rotation and process death. */
@Composable
private fun rememberFieldState(initial: String): MutableState<TextFieldValue> =
    rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    }

@Composable
private fun EditorContent(
    vm: MemoryEditorViewModel,
    state: MemoryEditorUiState,
    padding: PaddingValues,
    onAddPhotos: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme

    // Each field keeps its own text so typing is never delayed; every change is also sent to
    // the ViewModel, which owns saving.
    var titleValue by rememberFieldState(vm.currentTitle)
    var placeValue by rememberFieldState(vm.currentLocation)
    var storyValue by rememberFieldState(vm.currentDescription)
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }
    val placeFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        // A no-op unless the text was restored after the process was killed.
        vm.onTitleChange(titleValue.text)
        vm.onLocationChange(placeValue.text)
        vm.onDescriptionChange(storyValue.text)
        if (vm.isNew && titleValue.text.isEmpty() && storyValue.text.isEmpty()) {
            runCatching { titleFocus.requestFocus() }
        }
    }

    val titleStyle = MaterialTheme.typography.titleLarge.copy(color = colors.onBackground)
    val fieldStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onBackground)

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
        ) {
            EditorField(
                value = titleValue,
                onValueChange = { new ->
                    val cleaned = if ('\n' in new.text) new.copy(text = new.text.replace('\n', ' ')) else new
                    titleValue = cleaned
                    vm.onTitleChange(cleaned.text)
                },
                placeholder = "Give this moment a name",
                textStyle = titleStyle,
                modifier = Modifier.focusRequester(titleFocus),
                singleLine = false,
                maxLines = 4,
                capitalization = KeyboardCapitalization.Sentences,
                onNext = { runCatching { placeFocus.requestFocus() } },
            )
            DateRow(
                text = formatDateLong(state.memoryDate),
                onClick = { showDatePicker = true },
            )
            EditorField(
                label = "Place",
                value = placeValue,
                onValueChange = { new ->
                    val cleaned = if ('\n' in new.text) new.copy(text = new.text.replace('\n', ' ')) else new
                    placeValue = cleaned
                    vm.onLocationChange(cleaned.text)
                },
                placeholder = "e.g. Benin City",
                textStyle = fieldStyle,
                modifier = Modifier.focusRequester(placeFocus),
                capitalization = KeyboardCapitalization.Words,
            )
            EditorField(
                label = "Story",
                value = storyValue,
                onValueChange = { new ->
                    storyValue = new
                    vm.onDescriptionChange(new.text)
                },
                placeholder = "Who was there, what was said, how it felt...",
                textStyle = fieldStyle,
                singleLine = false,
                minHeight = 160.dp,
                capitalization = KeyboardCapitalization.Sentences,
            )
            SectionLabel(text = "People")
            MemoryChipEditor(
                items = state.people,
                addLabel = "Add person",
                dialogTitle = "Add person",
                placeholder = "e.g. Adaeze",
                maxItems = MAX_PEOPLE,
                maxLength = MAX_PERSON_LENGTH,
                normalize = { normalizePerson(it) },
                onAdd = { vm.addPerson(it) },
                onRemove = { vm.removePerson(it) },
                capitalization = KeyboardCapitalization.Words,
            )
            SectionLabel(text = "Tags")
            MemoryChipEditor(
                items = state.tags,
                addLabel = "Add tag",
                dialogTitle = "Add tag",
                placeholder = "e.g. family",
                maxItems = MAX_TAGS,
                maxLength = MAX_TAG_LENGTH,
                normalize = { normalizeTag(it) },
                onAdd = { vm.addTag(it) },
                onRemove = { vm.removeTag(it) },
            )
            PhotosSection(
                photos = state.photos,
                isImporting = state.isImporting,
                onAddPhotos = onAddPhotos,
                onRemovePhoto = { vm.removePhoto(it) },
            )
            Spacer(modifier = Modifier.height(spacing.xxl))
        }
    }

    if (showDatePicker) {
        MemoryDatePickerDialog(
            initialDate = state.memoryDate,
            onConfirm = { picked ->
                vm.setDate(picked)
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false },
        )
    }
}

/** The date of the memory. Tapping it opens the date picker. */
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

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = NeriboTheme.spacing.lg, bottom = NeriboTheme.spacing.xs),
    )
}

/** The photo strip: thumbnails with a small remove control, a quiet progress bar and Add photos. */
@Composable
private fun PhotosSection(
    photos: List<String>,
    isImporting: Boolean,
    onAddPhotos: () -> Unit,
    onRemovePhoto: (String) -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        SectionLabel(text = "Photos")
        Text(
            text = "${photos.size} of $MAX_PHOTOS",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = spacing.xs),
        )
    }
    if (photos.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = spacing.md),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            photos.forEachIndexed { index, path ->
                PhotoThumbnail(
                    path = path,
                    position = index + 1,
                    onRemove = { onRemovePhoto(path) },
                )
            }
        }
    }
    if (isImporting) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = spacing.md),
            color = colors.primary,
            trackColor = colors.surfaceVariant,
        )
    }
    NeriboButton(
        text = if (isImporting) "Adding photos\u2026" else "Add photos",
        onClick = onAddPhotos,
        enabled = !isImporting,
        style = ButtonStyle.Secondary,
        leadingIcon = Icons.Outlined.AddPhotoAlternate,
    )
}

@Composable
private fun PhotoThumbnail(path: String, position: Int, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(modifier = Modifier.size(96.dp)) {
        MemoryPhoto(
            path = path,
            contentDescription = "Photo $position",
            modifier = Modifier
                .fillMaxSize()
                .clip(MaterialTheme.shapes.small),
        )
        // A 40dp touch area around a small round control in the corner.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(40.dp)
                .clickable(onClickLabel = "Remove photo $position", role = Role.Button, onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(colors.background.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Remove photo $position",
                    modifier = Modifier.size(14.dp),
                    tint = colors.onSurface,
                )
            }
        }
    }
}

/**
 * A borderless field on the paper background with an optional overline label. Used for every
 * field of the editor.
 */
@Composable
private fun EditorField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    label: String? = null,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minHeight: Dp = 0.dp,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Words,
    onNext: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    Column(modifier = Modifier.fillMaxWidth().padding(top = spacing.md)) {
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
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = minHeight),
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
