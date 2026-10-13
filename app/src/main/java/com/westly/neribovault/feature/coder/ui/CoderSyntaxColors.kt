package com.westly.neribovault.feature.coder.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * The colors of the code editor. These are the one place where colors are not taken from the app
 * theme: every value has at least 4.5:1 contrast against the app's background and surfaces.
 */
@Immutable
data class CoderSyntaxColors(
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val tag: Color,
    val attribute: Color,
    val plain: Color,
)

private val LightPalette = CoderSyntaxColors(
    keyword = Color(0xFF8A3B9E),
    string = Color(0xFF2F6B3A),
    number = Color(0xFFA0560A),
    comment = Color(0xFF6F6B63),
    tag = Color(0xFF1F5FA8),
    attribute = Color(0xFF8A5A00),
    plain = Color(0xFF1A1917),
)

private val DarkPalette = CoderSyntaxColors(
    keyword = Color(0xFFC79BDB),
    string = Color(0xFF9CCB8A),
    number = Color(0xFFE0A56A),
    comment = Color(0xFF8B8881),
    tag = Color(0xFF7FB2E8),
    attribute = Color(0xFFD9B66A),
    plain = Color(0xFFE9E7E2),
)

/** The light or dark palette, chosen from the theme (never from the system setting). */
@Composable
fun rememberCoderSyntaxColors(): CoderSyntaxColors {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return remember(dark) { if (dark) DarkPalette else LightPalette }
}
