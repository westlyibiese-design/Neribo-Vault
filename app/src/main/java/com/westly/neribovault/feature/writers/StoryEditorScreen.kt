package com.westly.neribovault.feature.writers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ConfirmDialog
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTextField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.SectionHeader

private const val MAX_TITLE_LENGTH = 120
private const val MAX_TARGET_DIGITS = 7

private val TITLE_HINTS = listOf("The Bride Price of Ugheli", "Harmattan Nights")

/**
 * The story form: title, synopsis, genre, status and an optional target word count. Saves on
 * the explicit Save button. A new story left empty is simply discarded; leaving with unsaved
 * edits asks first. [onSaved] receives the story's id.
 */
@Composable
fun StoryEditorScreen(
    storyId: String,
    onBack: () -> Unit,
    onSaved: (savedId: String) -> Unit,
) {
    val vm = neriboViewModel(key = storyId) { c -> StoryEditorViewModel(storyId, c.storiesRepository) }
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.notFound) {
        if (state.notFound) onBack()
    }

    if (!state.isLoaded) {
        NeriboScaffold(
            topBar = {
                NeriboTopBar(
                    title = if (vm.isNew) "New story" else "Edit story",
                    onBack = onBack,
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding))
        }
    } else {
        StoryFormContent(
            vm = vm,
            initial = state.initial,
            isSaving = state.isSaving,
            onBack = onBack,
            onSaved = onSaved,
        )
    }
}

@Composable
private fun StoryFormContent(
    vm: StoryEditorViewModel,
    initial: StoryForm,
    isSaving: Boolean,
    onBack: () -> Unit,
    onSaved: (savedId: String) -> Unit,
) {
    val spacing = NeriboTheme.spacing

    // The form lives here, not in the ViewModel, so typing is never delayed by a state round
    // trip. rememberSaveable keeps it across rotation.
    var title by rememberSaveable { mutableStateOf(initial.title) }
    var synopsis by rememberSaveable { mutableStateOf(initial.synopsis) }
    var genre by rememberSaveable { mutableStateOf(initial.genre) }
    var status by rememberSaveable { mutableStateOf(initial.status) }
    var target by rememberSaveable { mutableStateOf(initial.targetWordCount) }
    var showTitleError by rememberSaveable { mutableStateOf(false) }
    var showDiscard by rememberSaveable { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }
    val titleHint = remember { TITLE_HINTS.random() }

    val hasChanges = title != initial.title ||
        synopsis != initial.synopsis ||
        genre != initial.genre ||
        status != initial.status ||
        target != initial.targetWordCount

    val leave: () -> Unit = {
        if (hasChanges) showDiscard = true else onBack()
    }
    val submit: () -> Unit = {
        when {
            // A brand-new story that is still untouched is discarded, never saved.
            vm.isNew && !hasChanges -> onBack()
            title.isBlank() -> {
                showTitleError = true
                runCatching { titleFocus.requestFocus() }
            }
            else -> vm.save(
                StoryForm(
                    title = title,
                    synopsis = synopsis,
                    genre = genre,
                    status = status,
                    targetWordCount = target,
                ),
                onSaved,
            )
        }
    }

    BackHandler(onBack = leave)
    LaunchedEffect(Unit) {
        if (vm.isNew && title.isEmpty()) runCatching { titleFocus.requestFocus() }
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = if (vm.isNew) "New story" else "Edit story",
                onBack = leave,
            )
        },
    ) { padding ->
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
                    .padding(horizontal = spacing.screen, vertical = spacing.sm),
            ) {
                NeriboTextField(
                    value = title,
                    onValueChange = { new ->
                        title = new.replace("\n", " ").take(MAX_TITLE_LENGTH)
                        if (title.isNotBlank()) showTitleError = false
                    },
                    modifier = Modifier.focusRequester(titleFocus),
                    label = "Title",
                    placeholder = titleHint,
                    singleLine = false,
                    maxLines = 3,
                    isError = showTitleError,
                    supportingText = if (showTitleError) "Give your story a title" else null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                Spacer(modifier = Modifier.height(spacing.md))
                NeriboTextField(
                    value = synopsis,
                    onValueChange = { synopsis = it },
                    label = "Synopsis",
                    placeholder = "Who is this story about, and what do they stand to lose?",
                    singleLine = false,
                    minLines = 4,
                    maxLines = 10,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                Spacer(modifier = Modifier.height(spacing.xl))
                SectionHeader("GENRE")
                ChipRow {
                    StoryOptions.genres.forEach { option ->
                        NeriboChip(
                            label = option,
                            selected = genre == option,
                            onClick = { genre = option },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(spacing.md))
                SectionHeader("STATUS")
                ChipRow {
                    StoryOptions.statuses.forEach { option ->
                        NeriboChip(
                            label = StoryOptions.statusLabel(option),
                            selected = status == option,
                            onClick = { status = option },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(spacing.md))
                SectionHeader("TARGET LENGTH")
                NeriboTextField(
                    value = target,
                    onValueChange = { new -> target = new.filter { it.isDigit() }.take(MAX_TARGET_DIGITS) },
                    label = "Target word count (optional)",
                    placeholder = "60000",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(modifier = Modifier.height(spacing.xl))
            }
            Column {
                NeriboDivider()
                NeriboButton(
                    text = "Save",
                    onClick = submit,
                    enabled = !isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.screen, vertical = spacing.md),
                )
            }
        }
    }

    if (showDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            message = "Your changes to this story have not been saved.",
            confirmLabel = "Discard",
            onConfirm = {
                showDiscard = false
                onBack()
            },
            onDismiss = { showDiscard = false },
            destructive = true,
            dismissLabel = "Keep editing",
        )
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
