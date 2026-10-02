package com.westly.neribovault.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.BuildConfig
import com.westly.neribovault.NeriboApp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.NeriboBottomSheet
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.SectionHeader
import kotlinx.coroutines.launch

/** Settings: appearance, security, data (backup, cloud, storage), notifications and About. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenCloud: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val context = LocalContext.current
    val settingsStore = remember(context) { (context.applicationContext as NeriboApp).container.settingsStore }
    val themeMode by settingsStore.themeMode.collectAsStateWithLifecycle(initialValue = "system")
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showTheme by rememberSaveable { mutableStateOf(false) }
    var showStorage by rememberSaveable { mutableStateOf(false) }

    NeriboScaffold(
        topBar = { NeriboTopBar(title = "Settings", onBack = onBack) },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screen, vertical = spacing.lg),
        ) {
            SectionHeader(text = "Appearance")
            Spacer(modifier = Modifier.height(spacing.sm))
            NeriboCard(modifier = Modifier.fillMaxWidth()) {
                SettingsRow(
                    icon = Icons.Outlined.Palette,
                    title = "Theme",
                    subtitle = themeLabel(themeMode),
                    onClick = { showTheme = true },
                )
            }

            Spacer(modifier = Modifier.height(spacing.xl))
            SectionHeader(text = "Security")
            Spacer(modifier = Modifier.height(spacing.sm))
            NeriboCard(modifier = Modifier.fillMaxWidth()) {
                SettingsRow(
                    icon = Icons.Outlined.Lock,
                    title = "Security",
                    subtitle = "App lock and vault PINs",
                    onClick = onOpenSecurity,
                )
            }

            Spacer(modifier = Modifier.height(spacing.xl))
            SectionHeader(text = "Data")
            Spacer(modifier = Modifier.height(spacing.sm))
            NeriboCard(modifier = Modifier.fillMaxWidth()) {
                SettingsRow(
                    icon = Icons.Outlined.Backup,
                    title = "Backup and restore",
                    subtitle = "Encrypted copy of everything",
                    onClick = onOpenBackup,
                )
                NeriboDivider(modifier = Modifier.padding(start = 68.dp))
                SettingsRow(
                    icon = Icons.Outlined.Cloud,
                    title = "Cloud sync",
                    subtitle = "Back up and sync with your Supabase project",
                    onClick = onOpenCloud,
                )
                NeriboDivider(modifier = Modifier.padding(start = 68.dp))
                SettingsRow(
                    icon = Icons.Outlined.Storage,
                    title = "Storage",
                    subtitle = "Space used, clean up and trash",
                    onClick = { showStorage = true },
                )
            }

            Spacer(modifier = Modifier.height(spacing.xl))
            SectionHeader(text = "Notifications")
            Spacer(modifier = Modifier.height(spacing.sm))
            NeriboCard(modifier = Modifier.fillMaxWidth()) {
                SettingsRow(
                    icon = Icons.Outlined.Notifications,
                    title = "Notifications",
                    subtitle = "Reminders for posts, documents and tasks",
                    onClick = {
                        if (!openNotificationSettings(context)) {
                            scope.launch {
                                snackbarHostState.showSnackbar("Could not open notification settings.")
                            }
                        }
                    },
                )
            }

            if (BuildConfig.DEBUG) {
                Spacer(modifier = Modifier.height(spacing.xl))
                SectionHeader(text = "Diagnostics")
                Spacer(modifier = Modifier.height(spacing.sm))
                NeriboCard(modifier = Modifier.fillMaxWidth()) {
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
                        text = "Version $ABOUT_VERSION",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(spacing.md))
                    Text(
                        text = "Built by Neribo Group",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurface,
                    )
                    Text(
                        text = "Secure storage, privacy, and control \u2014 designed with simplicity in mind.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(spacing.md))
                    Text(
                        text = "\u00A9 2026 Neribo Group. All rights reserved.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                // Placeholder entries: static text only. They are deliberately not clickable and
                // have no chevron, so they can be wired to real pages later without a redesign.
                ABOUT_ITEMS.forEach { item ->
                    NeriboDivider()
                    AboutInfoRow(label = item.label, value = item.value)
                }
            }
            Spacer(modifier = Modifier.height(spacing.xxl))
        }
    }

    if (showTheme) {
        ThemeSheet(
            current = themeMode,
            onPick = { mode ->
                scope.launch { settingsStore.setThemeMode(mode) }
                showTheme = false
            },
            onDismiss = { showTheme = false },
        )
    }
    if (showStorage) {
        StorageSheet(onDismiss = { showStorage = false })
    }
}

private fun themeLabel(mode: String): String = when (mode) {
    "light" -> "Light"
    "dark" -> "Dark"
    else -> "System default"
}

@Composable
private fun ThemeSheet(
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    NeriboBottomSheet(onDismiss = onDismiss) {
        Text(
            text = "Theme",
            style = MaterialTheme.typography.titleLarge,
            color = colors.onSurface,
        )
        Spacer(modifier = Modifier.height(spacing.sm))
        THEME_CHOICES.forEach { (mode, label) ->
            val selected = current == mode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable { onPick(mode) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) colors.primary else colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = "Selected",
                        modifier = Modifier.size(24.dp),
                        tint = colors.primary,
                    )
                }
            }
        }
    }
}

private val THEME_CHOICES: List<Pair<String, String>> = listOf(
    "system" to "System default",
    "light" to "Light",
    "dark" to "Dark",
)

/** Opens the system notification settings for this app. Returns false if no screen could open. */
private fun openNotificationSettings(context: Context): Boolean {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
    }
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
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

/** Version shown in the About section. */
private const val ABOUT_VERSION = "0.0.16"

private data class AboutItem(val label: String, val value: String? = null)

private val ABOUT_ITEMS: List<AboutItem> = listOf(
    AboutItem("Website"),
    AboutItem("Privacy Policy"),
    AboutItem("Terms of Service"),
    AboutItem("Open Source Licenses"),
    AboutItem("Contact Support"),
    AboutItem("Security & Privacy"),
    AboutItem("Build Number", "Build 16"),
)

/** A static About list row: a label and an optional value. Not clickable. */
@Composable
private fun AboutInfoRow(label: String, value: String?) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Spacer(modifier = Modifier.width(spacing.sm))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
