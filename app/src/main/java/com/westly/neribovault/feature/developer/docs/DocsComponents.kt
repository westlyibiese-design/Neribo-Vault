package com.westly.neribovault.feature.developer.docs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboBottomSheet
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboTextField
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.core.ui.components.StatusBadge
import com.westly.neribovault.core.util.copyToClipboard
import com.westly.neribovault.core.util.formatRelative
import com.westly.neribovault.core.util.snippet
import com.westly.neribovault.data.local.entity.ProjectDocumentEntity
import com.westly.neribovault.data.local.entity.ProjectEntity
import com.westly.neribovault.data.local.entity.PromptEntity
import kotlinx.coroutines.launch

/** The route argument value that means "create a new item". */
internal const val DOC_NEW_ID = "new"

/** The route value that means "no project"; it never reaches these screens, but be safe. */
internal const val DOC_NO_PROJECT = "none"

internal const val DOC_KIND_README = "readme"
internal const val DOC_KIND_API = "api"
internal const val DOC_KIND_CHANGELOG = "changelog"
internal const val DOC_KIND_MEETING = "meeting"
internal const val DOC_KIND_OTHER = "other"

/** Every project document kind, in the order the editor offers them. */
internal val DOC_KINDS: List<String> =
    listOf(DOC_KIND_README, DOC_KIND_API, DOC_KIND_CHANGELOG, DOC_KIND_MEETING, DOC_KIND_OTHER)

/** Display name of a document kind. Unknown values read as Other. */
internal fun docKindLabel(kind: String): String = when (kind) {
    DOC_KIND_README -> "README"
    DOC_KIND_API -> "API notes"
    DOC_KIND_CHANGELOG -> "Changelog"
    DOC_KIND_MEETING -> "Meeting notes"
    else -> "Other"
}

internal const val PROMPT_CATEGORY_CODING = "coding"
internal const val PROMPT_CATEGORY_DEBUGGING = "debugging"
internal const val PROMPT_CATEGORY_DESIGN = "design"
internal const val PROMPT_CATEGORY_WRITING = "writing"
internal const val PROMPT_CATEGORY_PLANNING = "planning"
internal const val PROMPT_CATEGORY_OTHER = "other"

/** Every prompt category, in the order the editor and the library offer them. */
internal val PROMPT_CATEGORIES: List<String> = listOf(
    PROMPT_CATEGORY_CODING,
    PROMPT_CATEGORY_DEBUGGING,
    PROMPT_CATEGORY_DESIGN,
    PROMPT_CATEGORY_WRITING,
    PROMPT_CATEGORY_PLANNING,
    PROMPT_CATEGORY_OTHER,
)

/** Display name of a prompt category. Unknown values read as Other. */
internal fun promptCategoryLabel(category: String): String = when (category) {
    PROMPT_CATEGORY_CODING -> "Coding"
    PROMPT_CATEGORY_DEBUGGING -> "Debugging"
    PROMPT_CATEGORY_DESIGN -> "Design"
    PROMPT_CATEGORY_WRITING -> "Writing"
    PROMPT_CATEGORY_PLANNING -> "Planning"
    else -> "Other"
}

/** The title given to a duplicate. */
internal fun docCopyTitle(title: String): String {
    val trimmed = title.trim()
    return if (trimmed.isEmpty()) "Untitled copy" else "$trimmed (copy)"
}

/** Plain text for Copy and Share of a document: the title, a blank line, then the body. */
internal fun docPlainText(title: String, body: String): String {
    val heading = title.trim()
    val text = body.trim()
    return when {
        heading.isEmpty() -> text
        text.isEmpty() -> heading
        else -> heading + "\n\n" + text
    }
}

/** A text field with no border or fill, sitting straight on the paper background. */
@Composable
internal fun DocBorderlessField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    textStyle: TextStyle,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    maxLines: Int = Int.MAX_VALUE,
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

/** A slim line at the bottom of an editor, above the keyboard. */
@Composable
internal fun DocEditorFooter(text: String) {
    val spacing = NeriboTheme.spacing
    NeriboDivider()
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.screen, vertical = spacing.sm),
    )
}

/** "1 word" or "250 words". */
internal fun wordCountLabel(count: Int): String = if (count == 1) "1 word" else "$count words"

/**
 * One project document in a list: title, kind, a two-line preview and when it last changed.
 * Tap opens it; the three dots open [actions].
 */
@Composable
internal fun DocumentCard(
    doc: ProjectDocumentEntity,
    actions: List<MenuAction>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val hasTitle = doc.title.isNotBlank()
    val preview = snippet(doc.body, 160)
    NeriboCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.lg, top = spacing.xs, end = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (hasTitle) doc.title.trim() else "Untitled document",
                style = MaterialTheme.typography.titleMedium,
                color = if (hasTitle) colors.onSurface else colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = spacing.sm),
            )
            OverflowMenu(actions = actions)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            StatusBadge(text = docKindLabel(doc.kind))
            Text(
                text = formatRelative(doc.updatedAt),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (preview.isNotEmpty()) {
            Text(
                text = preview,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = spacing.lg, end = spacing.lg, top = spacing.sm),
            )
        }
        Spacer(modifier = Modifier.size(spacing.lg))
    }
}

/**
 * One prompt in the project's Prompts section: title, category, a star when it is a favorite,
 * and a quick Copy button. [onCopy] decides whether to copy straight away or ask for variables.
 */
@Composable
internal fun PromptRow(
    prompt: PromptEntity,
    actions: List<MenuAction>,
    onCopy: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val hasTitle = prompt.title.isNotBlank()
    NeriboCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.lg, end = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = spacing.sm),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (prompt.isFavorite) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = "Favorite",
                            tint = colors.primary,
                            modifier = Modifier
                                .padding(end = spacing.xs)
                                .size(16.dp),
                        )
                    }
                    Text(
                        text = if (hasTitle) prompt.title.trim() else "Untitled prompt",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (hasTitle) colors.onSurface else colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = promptCategoryLabel(prompt.category),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            NeriboIconButton(
                icon = Icons.Outlined.ContentCopy,
                contentDescription = "Copy prompt",
                onClick = onCopy,
            )
            OverflowMenu(actions = actions)
        }
    }
}

/** A bottom sheet to pick a prompt's project, or "No project". */
@Composable
internal fun PromptProjectSheet(
    projects: List<ProjectEntity>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    NeriboBottomSheet(onDismiss = onDismiss) {
        Text(
            text = "Project",
            style = MaterialTheme.typography.titleLarge,
            color = colors.onSurface,
            modifier = Modifier.padding(bottom = spacing.sm),
        )
        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
            item(key = "no-project") {
                ProjectSheetRow(
                    name = "No project",
                    selected = selectedId == null,
                    onClick = { onSelect(null) },
                )
            }
            items(projects, key = { project -> project.id }) { project ->
                ProjectSheetRow(
                    name = project.name.trim().ifEmpty { "Untitled project" },
                    selected = project.id == selectedId,
                    onClick = { onSelect(project.id) },
                )
            }
        }
    }
}

@Composable
private fun ProjectSheetRow(name: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) colors.primary else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = "Selected",
                tint = colors.primary,
            )
        }
    }
}

/**
 * The fill-in sheet for a prompt with variables: one field per variable (pre-filled with the last
 * value used this session), a live preview and a Copy prompt button. Copying never changes the
 * stored prompt.
 */
@Composable
internal fun PromptFillSheet(
    title: String,
    body: String,
    onCopied: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val variables = remember(body) { extractVariables(body) }
    val values = remember(variables) {
        mutableStateMapOf<String, String>().apply {
            variables.forEach { name ->
                val last = PromptFillMemory.recall(name)
                if (last != null) put(name, last)
            }
        }
    }
    val preview = fillVariables(body, values.filterValues { it.isNotEmpty() })

    NeriboBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .imePadding(),
        ) {
            Text(
                text = "Fill in the blanks",
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
            )
            if (title.isNotBlank()) {
                Text(
                    text = title.trim(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = spacing.xxs),
                )
            }
            Spacer(modifier = Modifier.size(spacing.md))
            variables.forEach { name ->
                NeriboTextField(
                    value = values[name] ?: "",
                    onValueChange = { typed ->
                        values[name] = typed
                        PromptFillMemory.store(name, typed)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = name,
                    singleLine = false,
                    minLines = 1,
                    maxLines = 6,
                )
                Spacer(modifier = Modifier.size(spacing.sm))
            }
            Text(
                text = "PREVIEW",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.xs, bottom = spacing.xs),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(colors.surfaceVariant),
            ) {
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = colors.onSurface,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(spacing.md),
                )
            }
            Spacer(modifier = Modifier.size(spacing.md))
            NeriboButton(
                text = "Copy prompt",
                onClick = { onCopied(preview) },
                modifier = Modifier.fillMaxWidth(),
                style = ButtonStyle.Primary,
                leadingIcon = Icons.Outlined.ContentCopy,
            )
        }
    }
}

/**
 * Returns the function that copies a prompt. A prompt without variables is copied straight away
 * with a "Prompt copied" snackbar; one with variables opens the fill-in sheet first. Call it with
 * the prompt's title and body.
 */
@Composable
internal fun rememberPromptCopier(snackbarHostState: SnackbarHostState): (String, String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sheetTitle by remember { mutableStateOf<String?>(null) }
    var sheetBody by remember { mutableStateOf("") }

    val notifyCopied: () -> Unit = {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar("Prompt copied")
        }
    }

    val openTitle = sheetTitle
    if (openTitle != null) {
        PromptFillSheet(
            title = openTitle,
            body = sheetBody,
            onCopied = { filled ->
                context.copyToClipboard(openTitle.ifBlank { "Prompt" }, filled)
                sheetTitle = null
                notifyCopied()
            },
            onDismiss = { sheetTitle = null },
        )
    }

    return { title, body ->
        if (body.isBlank()) {
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar("Nothing to copy yet")
            }
        } else if (extractVariables(body).isEmpty()) {
            context.copyToClipboard(title.ifBlank { "Prompt" }, body)
            notifyCopied()
        } else {
            sheetBody = body
            sheetTitle = title
        }
    }
}
