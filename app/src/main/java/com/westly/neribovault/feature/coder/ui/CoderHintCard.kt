package com.westly.neribovault.feature.coder.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.NeriboApp
import com.westly.neribovault.core.design.NeriboTheme
import kotlinx.coroutines.launch

/**
 * A small first-time tip. It shows [text] until the person taps Got it, then never again (the choice is
 * kept by CoderPrefs under [id]). While the saved choice is still loading it draws nothing, so the screen
 * does not flicker or jump.
 */
@Composable
fun CoderHintCard(id: String, text: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember(context) { (context.applicationContext as NeriboApp).container.coderPrefs }
    val seen by remember(prefs, id) { prefs.hintSeen(id) }.collectAsStateWithLifecycle<Boolean?>(initialValue = null)
    var dismissed by remember(id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (seen != false || dismissed) return

    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = colors.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(start = spacing.md, top = spacing.xs, bottom = spacing.xs, end = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = colors.onSurfaceVariant,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(horizontal = spacing.sm),
            )
            TextButton(
                onClick = {
                    dismissed = true
                    scope.launch { prefs.markHintSeen(id) }
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(text = "Got it", style = MaterialTheme.typography.labelLarge, color = colors.primary)
            }
        }
    }
}
