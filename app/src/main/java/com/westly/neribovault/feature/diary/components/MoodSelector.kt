package com.westly.neribovault.feature.diary.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.feature.diary.DiaryMood

/**
 * Five quiet text chips for the day's mood. Tap a chip to pick it, tap it again to clear.
 * [selected] is the stored mood key, or null for none.
 */
@Composable
fun MoodSelector(
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(NeriboTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DiaryMood.values().forEach { mood ->
            val isSelected = mood.key == selected
            NeriboChip(
                label = mood.label,
                selected = isSelected,
                onClick = { onSelect(if (isSelected) null else mood.key) },
            )
        }
    }
}
