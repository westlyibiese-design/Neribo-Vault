package com.westly.neribovault.core.ui.components

import androidx.compose.ui.graphics.vector.ImageVector

/** Visual weight of a [NeriboButton]. */
enum class ButtonStyle { Primary, Secondary, Text, Destructive }

/** Color meaning of a [StatusBadge]. */
enum class BadgeTone { Neutral, Accent, Warning, Danger }

/** One entry of an [OverflowMenu]. */
data class MenuAction(
    val label: String,
    val onClick: () -> Unit,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
)
