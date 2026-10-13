package com.westly.neribovault.feature.coder.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.NeriboDivider

private class ExtraKey(val label: String, val insert: String, val description: String)

private val EXTRA_KEYS = listOf(
    ExtraKey("Tab", "  ", "Insert tab"),
    ExtraKey("{", "{", "Insert opening curly bracket"),
    ExtraKey("}", "}", "Insert closing curly bracket"),
    ExtraKey("(", "(", "Insert opening parenthesis"),
    ExtraKey(")", ")", "Insert closing parenthesis"),
    ExtraKey("[", "[", "Insert opening square bracket"),
    ExtraKey("]", "]", "Insert closing square bracket"),
    ExtraKey("<", "<", "Insert less-than sign"),
    ExtraKey(">", ">", "Insert greater-than sign"),
    ExtraKey("/", "/", "Insert slash"),
    ExtraKey(";", ";", "Insert semicolon"),
    ExtraKey(":", ":", "Insert colon"),
    ExtraKey("\"", "\"", "Insert double quote"),
    ExtraKey("'", "'", "Insert single quote"),
    ExtraKey("=", "=", "Insert equals sign"),
    ExtraKey("!", "!", "Insert exclamation mark"),
    ExtraKey("&", "&", "Insert ampersand"),
    ExtraKey("|", "|", "Insert vertical bar"),
    ExtraKey("_", "_", "Insert underscore"),
    ExtraKey("#", "#", "Insert hash"),
    ExtraKey("$", "$", "Insert dollar sign"),
)

/**
 * One scrollable row of keys that phone keyboards make hard to reach. Each tap calls [onInsert]
 * with the text of the key. Shown above the keyboard while a file is open.
 */
@Composable
fun ExtraKeysBar(onInsert: (String) -> Unit, modifier: Modifier = Modifier) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth().background(colors.surface)) {
        NeriboDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = spacing.xs, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (key in EXTRA_KEYS) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .defaultMinSize(minWidth = 40.dp, minHeight = 44.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onInsert(key.insert) }
                        .semantics {
                            contentDescription = key.description
                            role = Role.Button
                        }
                        .padding(horizontal = spacing.sm),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = key.label,
                        style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                        color = colors.onSurface,
                    )
                }
            }
        }
    }
}
