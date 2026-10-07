package com.westly.neribovault.feature.lyrics.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.NeriboBottomSheet
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboTextField
import com.westly.neribovault.feature.lyrics.engine.SectionType

/** The nine section types in the order the sheet lists them. */
private val SHEET_TYPES = listOf(
    SectionType.INTRO to "Intro",
    SectionType.VERSE to "Verse",
    SectionType.PRE_CHORUS to "Pre-Chorus",
    SectionType.CHORUS to "Chorus",
    SectionType.BRIDGE to "Bridge",
    SectionType.HOOK to "Hook",
    SectionType.INTERLUDE to "Interlude",
    SectionType.OUTRO to "Outro",
    SectionType.OTHER to "Other",
)

/**
 * A bottom sheet that lists the nine section types. Choosing one applies it at once and closes
 * the sheet; choosing Other first asks for a label. [current] has a check mark (null in "add" mode).
 * [onChoose] receives the type and the label (only meaningful for Other).
 */
@Composable
fun SectionTypeSheet(
    title: String,
    current: SectionType?,
    currentLabel: String,
    onChoose: (SectionType, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    var askingForLabel by rememberSaveable { mutableStateOf(false) }
    var label by rememberSaveable {
        mutableStateOf(
            if (current == SectionType.OTHER && currentLabel.isNotBlank()) {
                currentLabel
            } else {
                EditorRules.DEFAULT_OTHER_LABEL
            },
        )
    }
    val error = if (askingForLabel) EditorRules.labelError(label) else null

    NeriboBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = spacing.sm),
            )
            SHEET_TYPES.forEach { (type, name) ->
                val selected = current == type
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) {
                            if (type == SectionType.OTHER) {
                                askingForLabel = true
                            } else {
                                onChoose(type, "")
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = "Current type",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (askingForLabel) {
                Spacer(modifier = Modifier.height(spacing.md))
                NeriboTextField(
                    value = label,
                    onValueChange = { label = it.replace("\n", " ") },
                    label = "Label",
                    placeholder = "Spoken",
                    isError = error != null,
                    supportingText = error,
                )
                Spacer(modifier = Modifier.height(spacing.md))
                NeriboButton(
                    text = "Done",
                    onClick = { onChoose(SectionType.OTHER, EditorRules.cleanLabel(label)) },
                    enabled = error == null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
