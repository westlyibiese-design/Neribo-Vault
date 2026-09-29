package com.westly.neribovault.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Three-dot menu. Each [MenuAction] closes the menu and then runs. */
@Composable
fun OverflowMenu(actions: List<MenuAction>) {
    var expanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box {
        NeriboIconButton(
            icon = Icons.Outlined.MoreVert,
            contentDescription = "More options",
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            actions.forEach { action ->
                val tint = if (action.destructive) colors.error else colors.onSurface
                DropdownMenuItem(
                    text = {
                        Text(
                            text = action.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = tint,
                        )
                    },
                    onClick = {
                        expanded = false
                        action.onClick()
                    },
                    leadingIcon = action.icon?.let { vector ->
                        {
                            Icon(
                                imageVector = vector,
                                contentDescription = null,
                                tint = if (action.destructive) colors.error else colors.onSurfaceVariant,
                            )
                        }
                    },
                )
            }
        }
    }
}
