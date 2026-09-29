package com.westly.neribovault.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme

/** Flat button in one of four [ButtonStyle]s. At least 48dp tall. */
@Composable
fun NeriboButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: ButtonStyle = ButtonStyle.Primary,
    leadingIcon: ImageVector? = null,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val sized = modifier.heightIn(min = 48.dp)
    val label: @Composable RowScope.() -> Unit = {
        if (leadingIcon != null) {
            Icon(imageVector = leadingIcon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(spacing.sm))
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
    when (style) {
        ButtonStyle.Primary -> Button(
            onClick = onClick,
            modifier = sized,
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.primary,
                contentColor = colors.onPrimary,
            ),
            elevation = null,
            content = label,
        )
        ButtonStyle.Secondary -> OutlinedButton(
            onClick = onClick,
            modifier = sized,
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
            border = BorderStroke(1.dp, colors.outline),
            content = label,
        )
        ButtonStyle.Text -> TextButton(
            onClick = onClick,
            modifier = sized,
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.textButtonColors(contentColor = colors.primary),
            content = label,
        )
        ButtonStyle.Destructive -> Button(
            onClick = onClick,
            modifier = sized,
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.error,
                contentColor = colors.onError,
            ),
            elevation = null,
            content = label,
        )
    }
}
