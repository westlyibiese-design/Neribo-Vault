package com.westly.neribovault.feature.posts.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.NeriboIconButton

/**
 * Hashtag chips plus a small input. Typing a word and pressing space, comma or Done adds it;
 * [onAdd] receives the raw text and the caller strips `#`, lower-cases and removes duplicates.
 * Tap a chip to remove that hashtag.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HashtagEditor(
    hashtags: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = NeriboTheme.spacing
    var input by rememberSaveable { mutableStateOf("") }

    val commit = {
        if (input.isNotBlank()) onAdd(input)
        input = ""
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (hashtags.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                hashtags.forEach { tag ->
                    HashtagChip(tag = tag, onRemove = { onRemove(tag) })
                }
            }
            Spacer(modifier = Modifier.padding(top = spacing.sm))
        }
        HashtagInput(
            value = input,
            onValueChange = { typed ->
                if (typed.any { it.isWhitespace() || it == ',' }) {
                    onAdd(typed)
                    input = ""
                } else {
                    input = typed.replace("#", "")
                }
            },
            onCommit = commit,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HashtagChip(tag: String, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    Row(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .background(colors.surfaceVariant, shape)
            .clickable(onClickLabel = "Remove hashtag $tag", role = Role.Button, onClick = onRemove)
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "#$tag",
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 200.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = colors.onSurfaceVariant,
        )
    }
}

/** A filled single-line field with an Add button that appears once something is typed. */
@Composable
private fun HashtagInput(
    value: String,
    onValueChange: (String) -> Unit,
    onCommit: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(colors.surfaceVariant, shape)
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            textStyle = textStyle,
            singleLine = true,
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onCommit() }),
            decorationBox = { inner ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (value.isEmpty()) {
                        Text(
                            text = "Add a hashtag, e.g. benincity",
                            style = textStyle.copy(color = colors.onSurfaceVariant),
                        )
                    }
                    inner()
                }
            },
        )
        if (value.isNotBlank()) {
            NeriboIconButton(
                icon = Icons.Outlined.Add,
                contentDescription = "Add hashtag",
                onClick = onCommit,
                tint = colors.primary,
            )
        }
    }
}
