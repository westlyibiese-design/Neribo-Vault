package com.westly.neribovault.core.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private val FabShape = RoundedCornerShape(16.dp)

/** Flat accent FAB (16dp rounded square). Give it [text] to make it an extended FAB. */
@Composable
fun NeriboFab(
    onClick: () -> Unit,
    contentDescription: String,
    icon: ImageVector = Icons.Outlined.Add,
    text: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val flat = FloatingActionButtonDefaults.elevation(
        defaultElevation = 0.dp,
        pressedElevation = 0.dp,
        focusedElevation = 0.dp,
        hoveredElevation = 0.dp,
    )
    if (text != null) {
        ExtendedFloatingActionButton(
            onClick = onClick,
            shape = FabShape,
            containerColor = colors.primary,
            contentColor = colors.onPrimary,
            elevation = flat,
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        FloatingActionButton(
            onClick = onClick,
            shape = FabShape,
            containerColor = colors.primary,
            contentColor = colors.onPrimary,
            elevation = flat,
        ) {
            Icon(imageVector = icon, contentDescription = contentDescription, modifier = Modifier.size(24.dp))
        }
    }
}
