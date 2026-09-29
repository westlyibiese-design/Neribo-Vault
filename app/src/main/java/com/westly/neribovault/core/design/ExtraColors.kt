package com.westly.neribovault.core.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Colors that do not exist in the Material 3 color scheme. */
@Immutable
data class ExtraColors(
    val success: Color,
    val warning: Color,
    val vaultIcon: Color,
)

internal val LightExtraColors = ExtraColors(
    success = LightSuccess,
    warning = LightWarning,
    vaultIcon = LightVaultIcon,
)
internal val DarkExtraColors = ExtraColors(
    success = DarkSuccess,
    warning = DarkWarning,
    vaultIcon = DarkVaultIcon,
)

val LocalExtraColors = staticCompositionLocalOf { LightExtraColors }
