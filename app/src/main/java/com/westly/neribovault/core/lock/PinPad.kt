@file:Suppress("DEPRECATION")

package com.westly.neribovault.core.lock

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backspace
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.westly.neribovault.core.design.NeriboTheme
import kotlin.math.roundToInt

/** Every PIN in Neribo Vault is exactly this many digits. */
const val PIN_LENGTH = 6

/** Holds the digits typed so far. The value is never logged or persisted. */
@Stable
class PinInput {
    var value by mutableStateOf("")
        private set

    val isComplete: Boolean get() = value.length == PIN_LENGTH

    fun append(digit: Char) {
        if (value.length < PIN_LENGTH) value += digit
    }

    /** Ignored once all digits are in, so a check in progress is never disturbed. */
    fun backspace() {
        if (value.isNotEmpty() && !isComplete) value = value.dropLast(1)
    }

    fun clear() {
        value = ""
    }
}

@Composable
fun rememberPinInput(): PinInput = remember { PinInput() }

/** Six dots that fill as digits are entered. Shakes sideways when [shakeTrigger] increases. */
@Composable
fun PinDots(
    filled: Int,
    isError: Boolean,
    shakeTrigger: Int,
    modifier: Modifier = Modifier,
) {
    val shake = remember { Animatable(0f) }
    LaunchedEffect(shakeTrigger) {
        if (shakeTrigger > 0) {
            repeat(3) {
                shake.animateTo(12f, tween(45))
                shake.animateTo(-12f, tween(45))
            }
            shake.animateTo(0f, tween(45))
        }
    }
    val colors = MaterialTheme.colorScheme
    val activeColor = if (isError) colors.error else colors.primary
    Row(
        modifier = modifier
            .offset { IntOffset(shake.value.roundToInt(), 0) }
            .semantics { contentDescription = "$filled of $PIN_LENGTH digits entered" },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        repeat(PIN_LENGTH) { index ->
            val on = index < filled
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(if (on) activeColor else Color.Transparent)
                    .border(BorderStroke(1.5.dp, if (on) activeColor else colors.outline), CircleShape),
            )
        }
    }
}

/** A clean 3 x 4 keypad with large flat 72dp keys. */
@Composable
fun PinKeypad(
    input: PinInput,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showBiometric: Boolean = false,
    onBiometric: () -> Unit = {},
) {
    val rows = listOf(
        listOf('1', '2', '3'),
        listOf('4', '5', '6'),
        listOf('7', '8', '9'),
    )
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                row.forEach { digit ->
                    DigitKey(digit = digit, enabled = enabled, onClick = { input.append(digit) })
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBiometric) {
                IconKey(
                    icon = Icons.Outlined.Fingerprint,
                    description = "Use fingerprint",
                    enabled = enabled,
                    onClick = onBiometric,
                )
            } else {
                Spacer(modifier = Modifier.size(72.dp))
            }
            DigitKey(digit = '0', enabled = enabled, onClick = { input.append('0') })
            IconKey(
                icon = Icons.Outlined.Backspace,
                description = "Delete",
                enabled = enabled,
                onClick = { input.backspace() },
            )
        }
    }
}

@Composable
private fun DigitKey(digit: Char, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(colors.surfaceVariant)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = digit.toString(),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 26.sp, lineHeight = 32.sp),
            color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f),
        )
    }
}

@Composable
private fun IconKey(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(26.dp),
            tint = if (enabled) colors.onSurfaceVariant else colors.onSurfaceVariant.copy(alpha = 0.38f),
        )
    }
}

/**
 * Title, message line, six dots and the keypad, stacked and centered.
 * The message line keeps its height so the layout never jumps.
 */
@Composable
fun PinEntryPanel(
    input: PinInput,
    modifier: Modifier = Modifier,
    title: String? = null,
    message: String? = null,
    isError: Boolean = false,
    shakeTrigger: Int = 0,
    enabled: Boolean = true,
    showBiometric: Boolean = false,
    onBiometric: () -> Unit = {},
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(shakeTrigger) {
        if (shakeTrigger > 0) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = colors.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(spacing.sm))
        }
        Text(
            text = message.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) colors.error else colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .heightIn(min = 44.dp)
                .padding(horizontal = spacing.md),
        )
        Spacer(modifier = Modifier.height(spacing.lg))
        PinDots(filled = input.value.length, isError = isError, shakeTrigger = shakeTrigger)
        Spacer(modifier = Modifier.height(spacing.xxl))
        PinKeypad(
            input = input,
            enabled = enabled,
            showBiometric = showBiometric,
            onBiometric = onBiometric,
        )
    }
}
