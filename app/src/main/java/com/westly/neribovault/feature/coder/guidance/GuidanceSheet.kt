package com.westly.neribovault.feature.coder.guidance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.NeriboApp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.NeriboBottomSheet
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.feature.coder.GuidanceMode
import kotlinx.coroutines.launch

/**
 * The Guidance sheet: a Beginner / Pro choice with one line each, and a text button that makes every tip
 * appear again. The confirmation is a line inside the sheet, never a pop-up.
 */
@Composable
fun GuidanceSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { (context.applicationContext as NeriboApp).container.coderPrefs }
    val mode by prefs.guidanceMode.collectAsStateWithLifecycle(initialValue = GuidanceMode.Beginner)
    val scope = rememberCoroutineScope()
    var tipsReset by rememberSaveable { mutableStateOf(false) }
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme

    NeriboBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Text(
                text = "Guidance",
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
            )
            ModeRow(
                label = "Beginner",
                description = "Beginner: short tips and explanations.",
                selected = mode == GuidanceMode.Beginner,
                onClick = { scope.launch { prefs.setGuidanceMode(GuidanceMode.Beginner) } },
            )
            ModeRow(
                label = "Pro",
                description = "Pro: no tips, no explanations.",
                selected = mode == GuidanceMode.Pro,
                onClick = { scope.launch { prefs.setGuidanceMode(GuidanceMode.Pro) } },
            )
            NeriboButton(
                text = "Show tips again",
                onClick = {
                    scope.launch {
                        prefs.resetHints()
                        prefs.setTipsEnabled(true)
                        tipsReset = true
                    }
                },
                style = ButtonStyle.Text,
            )
            if (tipsReset) {
                Text(
                    text = "Tips will appear again as you use Coder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = spacing.sm),
                )
            }
        }
    }
}

/** One choice: its chip and the line that says what it does. */
@Composable
private fun ModeRow(label: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NeriboTheme.spacing.md),
    ) {
        NeriboChip(label = label, selected = selected, onClick = onClick, modifier = Modifier.widthIn(min = 96.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}
