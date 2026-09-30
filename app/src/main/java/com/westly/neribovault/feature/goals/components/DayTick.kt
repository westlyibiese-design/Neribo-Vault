package com.westly.neribovault.feature.goals.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.delay

private const val NEXT_DAY_SLACK_MS = 1_000L

/** Milliseconds until the next local midnight, plus a small slack so the new day has begun. */
private fun millisUntilNextDay(): Long {
    val zone = ZoneId.systemDefault()
    val now = ZonedDateTime.now(zone)
    val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(zone)
    return Duration.between(now, nextMidnight).toMillis() + NEXT_DAY_SLACK_MS
}

/**
 * A number that changes when the calendar day may have changed: at local midnight while the
 * screen is open, and every time the app returns to the foreground. Pass it to anything that
 * shows "days left" or "Overdue" so those lines stay correct across midnight.
 */
@Composable
fun rememberDayTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(millisUntilNextDay())
            tick++
        }
    }
    return tick
}
