package com.westly.neribovault.feature.memories.components

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.westly.neribovault.feature.memories.localStartToPickerMillis
import com.westly.neribovault.feature.memories.pickerMillisToLocalStart

/**
 * A Material 3 date picker in a dialog. Works in start-of-day millis for the device time zone,
 * both for [initialDate] and for what [onConfirm] returns.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryDatePickerDialog(
    initialDate: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = localStartToPickerMillis(initialDate),
    )
    val selected = pickerState.selectedDateMillis
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    if (selected != null) onConfirm(pickerMillisToLocalStart(selected)) else onDismiss()
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
