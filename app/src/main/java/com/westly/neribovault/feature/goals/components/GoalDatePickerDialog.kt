package com.westly.neribovault.feature.goals.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.westly.neribovault.feature.goals.localStartToPickerMillis
import com.westly.neribovault.feature.goals.pickerMillisToLocalStart

/**
 * A Material 3 date picker in a dialog. Works in start-of-day millis for the device time zone,
 * both for [initialDate] and for what [onConfirm] returns. Pass [onClear] to offer a Clear button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalDatePickerDialog(
    initialDate: Long?,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
    onClear: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialDate?.let { localStartToPickerMillis(it) },
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
            Row {
                if (onClear != null) {
                    TextButton(onClick = onClear) {
                        Text(
                            text = "Clear",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(
                        text = "Cancel",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        },
        shape = MaterialTheme.shapes.large,
        tonalElevation = 0.dp,
    ) {
        DatePicker(state = pickerState)
    }
}
