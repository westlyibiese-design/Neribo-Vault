package com.westly.neribovault.feature.coder.editor.smart

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.NeriboApp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.NeriboBottomSheet
import kotlinx.coroutines.launch

/**
 * The three editor switches: suggestions, auto-close and mistake underlines. Each is saved at once in
 * CoderPrefs. (Mistake underlines is saved now and shows its effect once the underlines exist.)
 */
@Composable
fun EditorSettingsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { (context.applicationContext as NeriboApp).container.coderPrefs }
    val scope = rememberCoroutineScope()
    val suggestions by remember(prefs) { prefs.suggestionsEnabled }.collectAsStateWithLifecycle(initialValue = true)
    val autoClose by remember(prefs) { prefs.autoCloseEnabled }.collectAsStateWithLifecycle(initialValue = true)
    val lint by remember(prefs) { prefs.lintEnabled }.collectAsStateWithLifecycle(initialValue = true)
    NeriboBottomSheet(onDismiss = onDismiss) {
        SettingRow(
            title = "Suggestions",
            explanation = "Shows tag, property and keyword ideas while you type.",
            checked = suggestions,
            onChange = { value -> scope.launch { prefs.setSuggestionsEnabled(value) } },
        )
        SettingRow(
            title = "Auto-close",
            explanation = "Adds the closing bracket, quote or tag for you.",
            checked = autoClose,
            onChange = { value -> scope.launch { prefs.setAutoCloseEnabled(value) } },
        )
        SettingRow(
            title = "Mistake underlines",
            explanation = "Marks code that looks wrong.",
            checked = lint,
            onChange = { value -> scope.launch { prefs.setLintEnabled(value) } },
        )
    }
}

@Composable
private fun SettingRow(title: String, explanation: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = spacing.md)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
            Text(text = explanation, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
