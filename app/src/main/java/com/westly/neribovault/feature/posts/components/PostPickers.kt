package com.westly.neribovault.feature.posts.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * A Material 3 date picker in a dialog. [initialPickerMillis] and the value passed to
 * [onConfirm] are in the picker's own form (midnight UTC of the chosen day); convert with
 * `toPickerMillis` and `fromPickerMillis`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostDatePickerDialog(
    initialPickerMillis: Long,
    onConfirm: (pickerMillis: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialPickerMillis)
    val selected = pickerState.selectedDateMillis
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { if (selected != null) onConfirm(selected) else onDismiss() },
                enabled = selected != null,
            ) {
                Text(text = "Next", style = MaterialTheme.typography.labelLarge, color = colors.primary)
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

/** A Material 3 time picker (12-hour clock) in a dialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val timeState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = false,
    )
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = colors.surface,
            tonalElevation = 0.dp,
            modifier = Modifier
                .width(IntrinsicSize.Min)
                .height(IntrinsicSize.Min),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Select time",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 20.dp),
                )
                TimePicker(state = timeState)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = "Cancel",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onConfirm(timeState.hour, timeState.minute) }) {
                        Text(
                            text = "OK",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.primary,
                        )
                    }
                }
            }
        }
    }
}
