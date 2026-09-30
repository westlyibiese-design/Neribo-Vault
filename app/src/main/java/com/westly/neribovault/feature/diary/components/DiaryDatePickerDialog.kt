package com.westly.neribovault.feature.diary.components

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.westly.neribovault.feature.diary.toLocalDate
import com.westly.neribovault.feature.diary.toStartOfDayMillis
import java.time.Instant
import java.time.ZoneOffset

/**
 * A Material 3 date picker in a dialog. [initialDate] and the value passed to [onConfirm] are
 * start-of-day millis in the device time zone. (The picker itself works in UTC midnights, so the
 * two are converted through calendar dates.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryDatePickerDialog(
    initialDate: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val initialPickerMillis = initialDate.toLocalDate()
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialPickerMillis)
    val selected = pickerState.selectedDateMillis
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    if (selected != null) {
                        val day = Instant.ofEpochMilli(selected).atZone(ZoneOffset.UTC).toLocalDate()
                        onConfirm(day.toStartOfDayMillis())
                    } else {
                        onDismiss()
                    }
                },
                enabled = selected != null,
            ) {
                Text(text = "OK", style = MaterialTheme.typography.labelLarge, color = colors.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Cancel",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                )
            }
        },
        shape = MaterialTheme.shapes.large,
        tonalElevation = 0.dp,
    ) {
        DatePicker(state = pickerState)
    }
}
