package com.westly.neribovault.feature.documents.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.neriboTextFieldColors

/** How light a placeholder is next to the person's own text. */
private const val PLACEHOLDER_ALPHA = 0.55f

/**
 * A text field with its label always visible **above** it (never a label that floats away), and a
 * placeholder that is clearly lighter than the text the person types. Pass a null [label] when a
 * heading elsewhere already names the field. [minHeight] gives a roomy writing area.
 */
@Composable
fun LabeledField(
    label: String?,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    minHeight: Dp = Dp.Unspecified,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    isError: Boolean = false,
    supportingText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = minHeight),
            textStyle = textStyle,
            placeholder = placeholder?.let { hint ->
                {
                    Text(
                        text = hint,
                        style = textStyle,
                        color = colors.onSurfaceVariant.copy(alpha = PLACEHOLDER_ALPHA),
                    )
                }
            },
            supportingText = supportingText?.let { message -> { Text(message) } },
            isError = isError,
            keyboardOptions = keyboardOptions,
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
            shape = MaterialTheme.shapes.medium,
            colors = neriboTextFieldColors(),
        )
    }
}
