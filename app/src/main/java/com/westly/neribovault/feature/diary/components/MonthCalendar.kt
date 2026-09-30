package com.westly.neribovault.feature.diary.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.feature.diary.title
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

private val WEEKDAY_INITIALS = listOf("M", "T", "W", "T", "F", "S", "S")
private val CELL_HEIGHT = 48.dp

/**
 * A calm month grid, Monday first. Today is ringed lightly, days with entries get a small accent
 * dot and the selected day is filled with the primary container color.
 */
@Composable
fun MonthCalendar(
    month: YearMonth,
    today: LocalDate,
    selectedDay: LocalDate?,
    entryDays: Set<LocalDate>,
    onSelectDay: (LocalDate) -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val leadingBlanks = month.atDay(1).dayOfWeek.value - 1
    val daysInMonth = month.lengthOfMonth()
    val rows = (leadingBlanks + daysInMonth + 6) / 7

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeriboIconButton(
                icon = Icons.Outlined.ChevronLeft,
                contentDescription = "Previous month",
                onClick = onPreviousMonth,
            )
            Text(
                text = month.title(),
                style = MaterialTheme.typography.titleMedium,
                color = colors.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            NeriboIconButton(
                icon = Icons.Outlined.ChevronRight,
                contentDescription = "Next month",
                onClick = onNextMonth,
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            WEEKDAY_INITIALS.forEach { initial ->
                Text(
                    text = initial,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = NeriboTheme.spacing.xs),
                )
            }
        }
        for (row in 0 until rows) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (column in 0 until 7) {
                    val dayNumber = row * 7 + column - leadingBlanks + 1
                    if (dayNumber < 1 || dayNumber > daysInMonth) {
                        Box(modifier = Modifier.weight(1f).height(CELL_HEIGHT))
                    } else {
                        val date = month.atDay(dayNumber)
                        DayCell(
                            date = date,
                            isToday = date == today,
                            isSelected = date == selectedDay,
                            hasEntry = date in entryDays,
                            onClick = { onSelectDay(date) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    isToday: Boolean,
    isSelected: Boolean,
    hasEntry: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val monthName = date.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    val description = buildString {
        append("${date.dayOfMonth} $monthName")
        if (isToday) append(", today")
        if (hasEntry) append(", has an entry")
    }
    Box(
        modifier = modifier
            .height(CELL_HEIGHT)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        val ringModifier = if (isToday) {
            Modifier.border(1.dp, colors.primary.copy(alpha = 0.55f), CircleShape)
        } else {
            Modifier
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .then(ringModifier)
                .background(
                    color = if (isSelected) colors.primaryContainer else Color.Transparent,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSelected) colors.onPrimaryContainer else colors.onBackground,
            )
        }
        if (hasEntry) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .size(4.dp)
                    .background(colors.primary, CircleShape),
            )
        }
    }
}
