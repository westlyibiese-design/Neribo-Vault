package com.westly.neribovault.feature.documents.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.BadgeTone
import com.westly.neribovault.feature.documents.ExpiryState

/** The short word in a document's status badge. */
internal fun expiryBadgeText(state: ExpiryState): String = when (state) {
    ExpiryState.Expired -> "Expired"
    ExpiryState.ExpiringSoon -> "Expiring soon"
    ExpiryState.Valid -> "Valid"
    ExpiryState.NoExpiry -> "No expiry"
}

/** Danger for expired, warning for expiring soon, neutral for the rest. */
internal fun expiryBadgeTone(state: ExpiryState): BadgeTone = when (state) {
    ExpiryState.Expired -> BadgeTone.Danger
    ExpiryState.ExpiringSoon -> BadgeTone.Warning
    ExpiryState.Valid -> BadgeTone.Neutral
    ExpiryState.NoExpiry -> BadgeTone.Neutral
}

/** Text color for the expiry line: error, warning, or the quiet secondary color. */
@Composable
internal fun expiryTextColor(state: ExpiryState): Color = when (state) {
    ExpiryState.Expired -> MaterialTheme.colorScheme.error
    ExpiryState.ExpiringSoon -> NeriboTheme.extraColors.warning
    ExpiryState.Valid -> MaterialTheme.colorScheme.onSurfaceVariant
    ExpiryState.NoExpiry -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * The status banner at the top of the detail screen, in the tone of [state]: [label] such as
 * "Expires in 20 days" with an optional quieter [detail] line under it.
 */
@Composable
fun ExpiryBanner(
    state: ExpiryState,
    label: String,
    detail: String?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val warning = NeriboTheme.extraColors.warning
    val background = when (state) {
        ExpiryState.Expired -> colors.errorContainer
        ExpiryState.ExpiringSoon -> warning.copy(alpha = 0.16f)
        ExpiryState.Valid -> colors.surfaceVariant
        ExpiryState.NoExpiry -> colors.surfaceVariant
    }
    val content = when (state) {
        ExpiryState.Expired -> colors.onErrorContainer
        ExpiryState.ExpiringSoon -> warning
        ExpiryState.Valid -> colors.onSurface
        ExpiryState.NoExpiry -> colors.onSurfaceVariant
    }
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background, shape)
            .padding(horizontal = NeriboTheme.spacing.lg, vertical = NeriboTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(NeriboTheme.spacing.xxs),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = content,
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = content,
            )
        }
    }
}
