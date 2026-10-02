package com.westly.neribovault.feature.writers.characters

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTextField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.core.ui.components.StatusBadge
import com.westly.neribovault.core.util.LifecycleSaveEffect
import com.westly.neribovault.core.util.copyToClipboard
import com.westly.neribovault.core.util.snippet
import com.westly.neribovault.data.local.entity.StoryCharacterEntity
import kotlinx.coroutines.launch

private const val NEW_ID = "new"

/**
 * The Characters tab of a story: an Add character button, then one card per character
 * (initial, name, role badge, one-line description). Deleting shows an Undo snackbar.
 */
@Composable
fun StoryCharactersTab(
    storyId: String,
    onOpenCharacter: (characterId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = neriboViewModel(key = "characters:$storyId") { c ->
        CharactersTabViewModel(storyId, c.storyCharactersRepository)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val showUndo: suspend (String) -> Unit = { id ->
        snackbarHostState.currentSnackbarData?.dismiss()
        val result = snackbarHostState.showSnackbar(
            message = "Moved to Recently deleted",
            actionLabel = "Undo",
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) vm.restore(id)
    }

    // A character deleted inside the editor is announced here once this tab is back on screen.
    LaunchedEffect(Unit) {
        CharacterUndoBus.deletedId.collect { id ->
            if (id != null) {
                CharacterUndoBus.deletedId.value = null
                showUndo(id)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            state.isLoading -> Unit
            state.characters.isEmpty() -> EmptyState(
                icon = Icons.Outlined.Person,
                title = "No characters yet",
                message = "Characters you add will gather here, with their roles and traits.",
                modifier = Modifier.fillMaxSize(),
                actionLabel = "Add character",
                onAction = { onOpenCharacter(NEW_ID) },
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = spacing.screen,
                    end = spacing.screen,
                    top = spacing.md,
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                item(key = "add-character") {
                    NeriboButton(
                        text = "Add character",
                        onClick = { onOpenCharacter(NEW_ID) },
                        modifier = Modifier.fillMaxWidth(),
                        style = ButtonStyle.Secondary,
                        leadingIcon = Icons.Outlined.Add,
                    )
                }
                items(state.characters, key = { it.id }) { character ->
                    CharacterCard(
                        character = character,
                        onClick = { onOpenCharacter(character.id) },
                        actions = listOf(
                            MenuAction(
                                label = "Edit",
                                onClick = { onOpenCharacter(character.id) },
                                icon = Icons.Outlined.Edit,
                            ),
                            MenuAction(
                                label = "Delete",
                                onClick = {
                                    vm.delete(character.id)
                                    scope.launch { showUndo(character.id) }
                                },
                                icon = Icons.Outlined.Delete,
                                destructive = true,
                            ),
                        ),
                    )
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(spacing.lg),
        ) { data ->
            Snackbar(snackbarData = data, shape = MaterialTheme.shapes.medium)
        }
    }
}

@Composable
private fun CharacterCard(
    character: StoryCharacterEntity,
    onClick: () -> Unit,
    actions: List<MenuAction>,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val initial = character.name.trim().take(1).uppercase().ifEmpty { "?" }
    val preview = snippet(character.description, 120)
    NeriboCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = spacing.lg,
                    top = spacing.md,
                    bottom = spacing.md,
                    end = spacing.xs,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initial,
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                    color = colors.onSurface,
                )
            }
            Spacer(modifier = Modifier.width(spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = character.name.ifBlank { "Unnamed character" },
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(modifier = Modifier.width(spacing.sm))
                    StatusBadge(
                        text = CharacterRoles.label(character.role),
                        tone = CharacterRoles.tone(character.role),
                    )
                }
                if (preview.isNotEmpty()) {
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            OverflowMenu(actions = actions)
        }
    }
}

/**
 * The character editor: name in the serif title style, role chips, description, trait tags
 * (up to 12) and a large backstory. Autosaves 600ms after the last change and whenever the
 * screen stops. `characterId == "new"` creates a character; an empty new one is discarded.
 */
@Composable
fun CharacterEditorScreen(
    storyId: String,
    characterId: String,
    onBack: () -> Unit,
) {
    val vm = neriboViewModel(key = "character:$storyId:$characterId") { c ->
        CharacterEditorViewModel(storyId, characterId, c.storyCharactersRepository)
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
            label = "Copy character sheet",
            onClick = {
                context.copyToClipboard("Character sheet", buildCharacterSheet(vm.currentDraft))
                scope.launch {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar("Copied to clipboard")
                }
            },
            icon = Icons.Outlined.ContentCopy,
        ),
        MenuAction(
            label = "Delete character",
            onClick = { vm.delete(onDone = onBack) },
            icon = Icons.Outlined.Delete,
            destructive = true,
        ),
    )
    val saveLabel = when (state.saveStatus) {
        CharacterSaveStatus.Idle -> null
        CharacterSaveStatus.Saving -> "Saving\u2026"
        CharacterSaveStatus.Saved -> "Saved"
    }

    NeriboScaffold(
        topBar = {
            NeriboTopBar(
                title = if (vm.isNew) "New character" else "Character",
                onBack = {
                    vm.flush()
                    onBack()
                },
                subtitle = if (state.isLoaded) saveLabel else null,
                actions = { OverflowMenu(actions = actions) },
            )
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        if (state.isLoaded) {
            CharacterEditorContent(vm = vm, state = state, padding = padding)
        } else {
            Box(modifier = Modifier.fillMaxSize().padding(padding))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CharacterEditorContent(
    vm: CharacterEditorViewModel,
    state: CharacterEditorUiState,
    padding: PaddingValues,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme

    // The fields keep their own text so typing is never delayed; every change is also sent to
    // the ViewModel, which owns saving. rememberSaveable keeps the text across rotation.
    var name by rememberSaveable { mutableStateOf(vm.currentDraft.name) }
    var description by rememberSaveable { mutableStateOf(vm.currentDraft.description) }
    var backstory by rememberSaveable { mutableStateOf(vm.currentDraft.backstory) }
    var showAddTrait by rememberSaveable { mutableStateOf(false) }
    val nameFocus = remember { FocusRequester() }
    val descriptionFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        // A no-op unless the text was restored after the process was killed.
        vm.onNameChange(name)
        vm.onDescriptionChange(description)
        vm.onBackstoryChange(backstory)
        // Only a brand-new, empty character opens the keyboard by itself.
        if (vm.isNew && name.isEmpty() && description.isEmpty() && backstory.isEmpty()) {
            runCatching { nameFocus.requestFocus() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.screen),
    ) {
        Spacer(modifier = Modifier.height(spacing.md))
        SectionHeader(text = "Name")
        Spacer(modifier = Modifier.height(spacing.sm))
        EditorField(
            value = name,
            onValueChange = { value ->
                val cleaned = value.replace('\n', ' ')
                name = cleaned
                vm.onNameChange(cleaned)
            },
            placeholder = "Character name",
            textStyle = MaterialTheme.typography.titleLarge,
            modifier = Modifier.focusRequester(nameFocus),
            capitalization = KeyboardCapitalization.Words,
            onNext = { runCatching { descriptionFocus.requestFocus() } },
        )
        Spacer(modifier = Modifier.height(spacing.sm))
        NeriboDivider()
        Spacer(modifier = Modifier.height(spacing.lg))

        SectionHeader(text = "Role")
        Spacer(modifier = Modifier.height(spacing.xs))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CharacterRoles.all.forEach { role ->
                NeriboChip(
                    label = CharacterRoles.label(role),
                    selected = state.role == role,
                    onClick = { vm.onRoleChange(role) },
                )
            }
        }
        Spacer(modifier = Modifier.height(spacing.lg))

        SectionHeader(text = "Description")
        Spacer(modifier = Modifier.height(spacing.xs))
        EditorField(
            value = description,
            onValueChange = { value ->
                description = value
                vm.onDescriptionChange(value)
            },
            placeholder = "Write a short description",
            textStyle = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.focusRequester(descriptionFocus),
            minHeight = 56.dp,
        )
        Spacer(modifier = Modifier.height(spacing.lg))

        SectionHeader(text = "Traits")
        Spacer(modifier = Modifier.height(spacing.xs))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            state.traits.forEach { trait ->
                TraitTag(trait = trait, onRemove = { vm.removeTrait(trait) })
            }
        }
        if (state.traits.size < MAX_TRAITS) {
            NeriboButton(
                text = "Add trait",
                onClick = { showAddTrait = true },
                style = ButtonStyle.Text,
                leadingIcon = Icons.Outlined.Add,
            )
        } else {
            Text(
                text = "$MAX_TRAITS of $MAX_TRAITS traits",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(vertical = spacing.sm),
            )
        }
        Spacer(modifier = Modifier.height(spacing.lg))

        SectionHeader(text = "Backstory")
        Spacer(modifier = Modifier.height(spacing.xs))
        EditorField(
            value = backstory,
            onValueChange = { value ->
                backstory = value
                vm.onBackstoryChange(value)
            },
            placeholder = "Write the backstory",
            textStyle = MaterialTheme.typography.bodyLarge,
            minHeight = 240.dp,
        )
        Spacer(modifier = Modifier.height(spacing.xxxl))
    }

    if (showAddTrait) {
        AddTraitDialog(
            onAdd = { value ->
                vm.addTrait(value)
                showAddTrait = false
            },
            onDismiss = { showAddTrait = false },
        )
    }
}

/** A borderless text field on the paper background with a soft placeholder. */
@Composable
private fun EditorField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    minHeight: Dp = 0.dp,
    onNext: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val style = textStyle.copy(color = colors.onBackground)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight),
        textStyle = style,
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(
            capitalization = capitalization,
            imeAction = if (onNext != null) ImeAction.Next else ImeAction.Default,
        ),
        keyboardActions = KeyboardActions(onNext = { onNext?.invoke() }),
        decorationBox = { inner ->
            Box(modifier = Modifier.fillMaxWidth()) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = style.copy(color = colors.onSurfaceVariant.copy(alpha = 0.55f)),
                    )
                }
                inner()
            }
        },
    )
}

/** One trait as a tag. Tapping it removes the trait. The touch target is 48dp tall. */
@Composable
private fun TraitTag(trait: String, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = "Remove $trait", onClick = onRemove),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(shape)
                .background(colors.surfaceVariant, shape)
                .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = trait,
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Remove $trait",
                modifier = Modifier.size(16.dp),
                tint = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AddTraitDialog(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onAdd(text) }, enabled = text.isNotBlank()) {
                Text(
                    text = "Add",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (text.isNotBlank()) colors.primary else colors.onSurfaceVariant,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Cancel",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                )
            }
        },
        title = { Text(text = "Add a trait", style = MaterialTheme.typography.titleLarge) },
        text = {
            NeriboTextField(
                value = text,
                onValueChange = { text = it.take(MAX_TRAIT_LENGTH) },
                label = "Trait",
                placeholder = "Type a trait",
            )
        },
        shape = MaterialTheme.shapes.large,
        containerColor = colors.surface,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceVariant,
        tonalElevation = 0.dp,
    )
}
