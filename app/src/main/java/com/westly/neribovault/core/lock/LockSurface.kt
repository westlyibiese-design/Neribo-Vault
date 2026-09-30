package com.westly.neribovault.core.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme

/**
 * Full-screen calm background for the lock, onboarding and vault-unlock screens.
 * Content is centered and scrolls on very small screens.
 */
@Composable
internal fun LockSurface(
    modifier: Modifier = Modifier,
    topBar: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = NeriboTheme.spacing
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        if (topBar != null) topBar()
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val minHeight = maxHeight
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = minHeight)
                    .padding(horizontal = spacing.screen, vertical = spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                content = content,
            )
        }
    }
}

/** Round icon badge with a serif title and optional subtitle, used at the top of lock screens. */
@Composable
internal fun LockHeader(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(colors.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = colors.primary,
            )
        }
        Spacer(modifier = Modifier.height(spacing.md))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = colors.onBackground,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(spacing.xs))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(spacing.lg))
    }
}
