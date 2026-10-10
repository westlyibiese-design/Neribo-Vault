package com.westly.neribovault.feature.authenticator.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboCard

/** A quiet reminder, shown until the first export is made, that the codes live only on this phone. */
@Composable
fun BackupReminder(onBackUp: () -> Unit, onLater: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = NeriboTheme.spacing
    NeriboCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(spacing.lg)) {
            Text(
                text = "Your codes live only on this phone. Make an encrypted backup so you never lose them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row {
                NeriboButton(text = "Back up now", onClick = onBackUp, style = ButtonStyle.Text)
                NeriboButton(text = "Later", onClick = onLater, style = ButtonStyle.Text)
            }
        }
    }
}
