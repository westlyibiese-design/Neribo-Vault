package com.westly.neribovault.feature.coder.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar

/**
 * A calm full-panel message shown instead of the preview when a project must be built on a computer
 * first. [reason] says why in plain English. [builtFolderHint] is "dist" or "build" when that folder
 * exists but has no index.html, which adds one extra explanation button. [onBack] returns to the code.
 */
@Composable
fun NeedsBuildNotice(reason: String, builtFolderHint: String?, onBack: () -> Unit) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    var showWhy by rememberSaveable { mutableStateOf(false) }
    NeriboScaffold(topBar = { NeriboTopBar(title = "Preview", onBack = onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screen, vertical = spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(colors.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = colors.primary,
                )
            }
            Spacer(modifier = Modifier.height(spacing.lg))
            Text(
                text = "This project needs a build step",
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
            )
            Spacer(modifier = Modifier.height(spacing.sm))
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
            )
            Spacer(modifier = Modifier.height(spacing.md))
            Text(
                text = "Phones can't run build tools such as npm. Build the project on a computer (run `npm run build`), " +
                    "then upload the `dist` or `build` folder as a ZIP.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(spacing.md))
            Bullet("Upload the built folder as a ZIP and it will run here")
            Bullet("You can still edit this project and use AI help")
            Bullet("React projects written with script tags run here without any build")
            Spacer(modifier = Modifier.height(spacing.xl))
            NeriboButton(
                text = "Back to code",
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
            )
            if (builtFolderHint != null) {
                Spacer(modifier = Modifier.height(spacing.sm))
                NeriboButton(
                    text = "Why is there no index.html in $builtFolderHint?",
                    onClick = { showWhy = !showWhy },
                    modifier = Modifier.fillMaxWidth(),
                    style = ButtonStyle.Text,
                )
                if (showWhy) {
                    Text(
                        text = "The $builtFolderHint folder has no index.html directly inside it, so there is no page to open. " +
                            "Check that the build finished and that you zipped the contents of the folder.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    val spacing = NeriboTheme.spacing
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
