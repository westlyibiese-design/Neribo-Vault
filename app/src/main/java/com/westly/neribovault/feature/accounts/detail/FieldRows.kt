package com.westly.neribovault.feature.accounts.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.data.local.entity.AccountFieldEntity

/**
 * A labelled plain-text value with an optional Copy button and an optional text action such as
 * "Open". Nothing here is encrypted.
 */
@Composable
fun ValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onCopy: (() -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
            Text(
                text = value.ifEmpty { "—" },
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
            )
        }
        if (actionLabel != null && onAction != null) {
            NeriboButton(text = actionLabel, onClick = onAction, style = ButtonStyle.Text)
        }
        if (onCopy != null) {
            NeriboIconButton(
                icon = Icons.Outlined.ContentCopy,
                contentDescription = "Copy $label",
                onClick = onCopy,
            )
        }
    }
}

/** The category passed to the fake-value generator for a custom field. */
fun fieldDisplayCategory(label: String): String {
    val lower = label.lowercase()
    return if ("key" in lower || "token" in lower || "secret" in lower) "api_key" else "text"
}

/**
 * The custom fields of an account or item: plain fields show their value with Copy; secret
 * fields use [SecretRow].
 */
@Composable
fun FieldRows(
    fields: List<AccountFieldEntity>,
    reveal: RevealState,
    onRevealField: (AccountFieldEntity) -> Unit,
    onCopyField: (AccountFieldEntity) -> Unit,
    onCopyPlain: (label: String, value: String) -> Unit,
) {
    for (field in fields) {
        val label = field.label.ifBlank { "Field" }
        if (field.isSecret) {
            SecretRow(
                label = label,
                isStored = field.valueCipher != null && field.valueIv != null,
                revealedValue = reveal.value(field.id),
                onReveal = { onRevealField(field) },
                onHide = { reveal.hide(field.id) },
                onCopy = { onCopyField(field) },
                emptyText = "No value stored",
            )
        } else {
            val value = field.valuePlain.orEmpty()
            ValueRow(
                label = label,
                value = value,
                onCopy = if (value.isNotEmpty()) ({ onCopyPlain(label, value) }) else null,
            )
        }
    }
}
