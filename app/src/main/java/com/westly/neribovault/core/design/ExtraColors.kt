package com.westly.neribovault.core.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Colors that do not exist in the Material 3 color scheme. */
@Immutable
data class ExtraColors(
    val success: Color,
    val warning: Color,
)

internal val LightExtraColors = ExtraColors(success = LightSuccess, warning = LightWarning)
internal val DarkExtraColors = ExtraColors(success = DarkSuccess, warning = DarkWarning)

val LocalExtraColors = staticCompositionLocalOf { LightExtraColors }
