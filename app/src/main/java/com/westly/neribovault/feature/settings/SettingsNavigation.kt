package com.westly.neribovault.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.westly.neribovault.BuildConfig
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.navigation.Routes
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.SectionHeader

/** Route names for Settings. */
object SettingsRoutes {
    const val LIST = Routes.SETTINGS
}

/** Registers the Settings screen. */
fun NavGraphBuilder.settingsGraph(navController: NavHostController) {
    composable(SettingsRoutes.LIST) {
        SettingsScreen(
            onBack = { navController.popBackStack() },
            onOpenSecurity = { navController.navigate(Routes.SECURITY) },
            onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
        )
    }
}

@Composable
private fun SettingsScreen(
    onBack: () -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    NeriboScaffold(
        topBar = { NeriboTopBar(title = "Settings", onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screen, vertical = spacing.lg),
        ) {
            SectionHeader(text = "General")
            Spacer(modifier = Modifier.height(spacing.sm))
            NeriboCard(modifier = Modifier.fillMaxWidth()) {
                SettingsRow(
                    icon = Icons.Outlined.Lock,
                    title = "Security",
                    subtitle = "App lock and vault PINs",
                    onClick = onOpenSecurity,
                )
                if (BuildConfig.DEBUG) {
                    NeriboDivider(modifier = Modifier.padding(start = 68.dp))
                    SettingsRow(
                        icon = Icons.Outlined.Build,
                        title = "Diagnostics",
                        subtitle = "For testing builds only",
                        onClick = onOpenDiagnostics,
                    )
                }
            }
            Spacer(modifier = Modifier.height(spacing.xl))
            SectionHeader(text = "About")
            Spacer(modifier = Modifier.height(spacing.sm))
            NeriboCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(spacing.lg)) {
                    Text(
                        text = "Neribo Vault",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface,
                    )
                    Text(
                        text = "Version ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(spacing.sm))
                    Text(
                        text = "Built by Westly Ibiese in Benin City, Nigeria",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(spacing.xxl))
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
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
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.primaryContainer.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = colors.primary,
            )
        }
        Spacer(modifier = Modifier.width(spacing.lg))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
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
