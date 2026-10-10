package com.westly.neribovault.feature.authenticator.components

import androidx.compose.foundation.layout.Column
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

/** Shown when the phone's automatic date and time is off, because the codes would be wrong. */
@Composable
fun TimeBanner(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = NeriboTheme.spacing
    NeriboCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(spacing.lg)) {
            Text(
                text = "Automatic date and time is off. Your codes may be wrong.",
                style = MaterialTheme.typography.bodyMedium,
                color = NeriboTheme.extraColors.warning,
            )
            NeriboButton(
                text = "Open date settings",
                onClick = onOpenSettings,
                style = ButtonStyle.Text,
            )
        }
    }
}
