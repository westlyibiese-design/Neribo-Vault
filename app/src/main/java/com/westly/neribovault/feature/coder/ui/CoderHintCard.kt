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
 * A small tip card. With [onDismiss] it only draws [text] and a Got it button that calls [onDismiss]; the
 * caller decides what dismissing means (the tip host in `feature/coder/guidance` does). Without [onDismiss] it
 * manages itself as before: it shows [text] until the person taps Got it, then never again (the choice is
 * kept by CoderPrefs under [id]), and while the saved choice is still loading it draws nothing.
 */
@Composable
fun CoderHintCard(id: String, text: String, modifier: Modifier = Modifier, onDismiss: (() -> Unit)? = null) {
    if (onDismiss != null) {
        HintSurface(text = text, onGotIt = onDismiss, modifier = modifier)
    } else {
        SelfManagedHint(id = id, text = text, modifier = modifier)
    }
}

@Composable
private fun SelfManagedHint(id: String, text: String, modifier: Modifier) {
    val context = LocalContext.current
    val prefs = remember(context) { (context.applicationContext as NeriboApp).container.coderPrefs }
    val seen by remember(prefs, id) { prefs.hintSeen(id) }.collectAsStateWithLifecycle<Boolean?>(initialValue = null)
    var dismissed by remember(id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (seen != false || dismissed) return
    HintSurface(
        text = text,
        onGotIt = {
            dismissed = true
            scope.launch { prefs.markHintSeen(id) }
        },
        modifier = modifier,
    )
}

@Composable
private fun HintSurface(text: String, onGotIt: () -> Unit, modifier: Modifier) {
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
                onClick = onGotIt,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(text = "Got it", style = MaterialTheme.typography.labelLarge, color = colors.primary)
            }
        }
    }
}
