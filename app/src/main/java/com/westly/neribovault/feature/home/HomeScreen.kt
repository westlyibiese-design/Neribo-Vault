package com.westly.neribovault.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.core.util.formatDateLong
import com.westly.neribovault.core.vault.VaultCatalog
import com.westly.neribovault.core.vault.VaultDefinition
import java.time.LocalTime

/** Home: a greeting, today's date, and one quiet list of every vault. */
@Composable
fun HomeScreen(
    onOpenVault: (route: String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val greeting = remember { greetingForHour(LocalTime.now().hour) }
    val today = remember { formatDateLong(System.currentTimeMillis()) }

    NeriboScaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screen),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.lg),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f).padding(top = spacing.md)) {
                    SectionHeader(text = "Neribo Vault")
                    Spacer(modifier = Modifier.height(spacing.sm))
                    Text(
                        text = greeting,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = today,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // The icon glyph sits 12dp inside its 48dp button; shift so it lines up with the margin.
                NeriboIconButton(
                    icon = Icons.Outlined.Settings,
                    contentDescription = "Settings",
                    onClick = onOpenSettings,
                    modifier = Modifier.offset(x = spacing.md),
                )
            }
            Spacer(modifier = Modifier.height(spacing.xl))
            NeriboCard(modifier = Modifier.fillMaxWidth()) {
                val vaults = VaultCatalog.all
                vaults.forEachIndexed { index, vault ->
                    VaultRow(vault = vault, onClick = { onOpenVault(vault.route) })
                    if (index != vaults.lastIndex) {
                        NeriboDivider(modifier = Modifier.padding(start = 72.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(spacing.xxl))
        }
    }
}

@Composable
private fun VaultRow(vault: VaultDefinition, onClick: () -> Unit) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.primaryContainer.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = vault.icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = colors.primary,
            )
        }
        Spacer(modifier = Modifier.width(spacing.lg))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = vault.name,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = vault.description,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(spacing.sm))
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = colors.outline,
        )
    }
}

/** "Good morning" before noon, "Good afternoon" until 5 PM, otherwise "Good evening". */
private fun greetingForHour(hour: Int): String = when {
    hour < 12 -> "Good morning"
    hour < 17 -> "Good afternoon"
    else -> "Good evening"
}
