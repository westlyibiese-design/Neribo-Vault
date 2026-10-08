package com.westly.neribovault.feature.accounts.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.westly.neribovault.feature.accounts.PlatformPresets

/**
 * A tonal circle with the platform's first letter in serif. Logos are never used.
 * 40dp on lists, 56dp on the detail header.
 */
@Composable
fun PlatformAvatar(platform: String, size: Dp, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val base = if (size >= 56.dp) typography.headlineSmall else typography.titleMedium
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.surfaceVariant)
            .border(1.dp, colors.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = PlatformPresets.avatarLetter(platform),
            style = base.copy(fontFamily = FontFamily.Serif),
            color = colors.onSurface,
        )
    }
}
