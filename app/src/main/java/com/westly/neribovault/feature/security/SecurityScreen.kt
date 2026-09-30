package com.westly.neribovault.feature.security

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.lock.BiometricAuth
import com.westly.neribovault.core.lock.PinSetupFlow
import com.westly.neribovault.core.lock.rememberLockManager
import com.westly.neribovault.core.ui.components.NeriboBottomSheet
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.SectionHeader
import kotlinx.coroutines.launch

private val autoLockOptions = listOf(
    "Immediately" to 0,
    "30 seconds" to 30,
    "1 minute" to 60,
    "5 minutes" to 300,
)

private fun autoLockLabel(seconds: Int): String =
    autoLockOptions.firstOrNull { it.second == seconds }?.first ?: "30 seconds"

/** Settings, Security: PIN, biometrics, auto-lock and screenshot blocking. */
@Composable
internal fun SecurityScreen(onBack: () -> Unit) {
    val manager = rememberLockManager()
    val context = LocalContext.current
    val activity = remember(context) { findFragmentActivity(context) }
    val biometricSupported = remember { BiometricAuth.isAvailable(context) }
    val biometricsOn by manager.biometricsEnabled.collectAsStateWithLifecycle()
    val autoLockSeconds by manager.autoLockSeconds.collectAsStateWithLifecycle()
    val blockScreenshots by manager.blockScreenshots.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showChangePin by remember { mutableStateOf(false) }
    var showAutoLock by remember { mutableStateOf(false) }

    NeriboScaffold(
        topBar = { NeriboTopBar(title = "Security", onBack = onBack) },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screen, vertical = spacing.lg),
        ) {
            SectionHeader(text = "Access")
            Spacer(modifier = Modifier.height(spacing.sm))
            NeriboCard(modifier = Modifier.fillMaxWidth()) {
                SecurityRow(
                    icon = Icons.Outlined.Edit,
                    title = "Change PIN",
                    subtitle = "Your six-digit app PIN",
                    onClick = { showChangePin = true },
                )
                if (biometricSupported) {
                    NeriboDivider()
                    SecurityRow(
                        icon = Icons.Outlined.Fingerprint,
                        title = "Fingerprint or face unlock",
                        subtitle = "Your PIN always works too",
                        onClick = null,
                        trailing = {
                            Switch(
                                checked = biometricsOn,
                                onCheckedChange = { wantOn ->
                                    if (!wantOn) {
                                        manager.setBiometricsEnabled(false)
                                    } else if (activity != null) {
                                        BiometricAuth.authenticate(
                                            activity = activity,
                                            title = "Turn on biometric unlock",
                                            subtitle = null,
                                            onSuccess = { manager.setBiometricsEnabled(true) },
                                            onCancel = {},
                                        )
                                    }
                                },
                                colors = switchColors(),
                            )
                        },
                    )
                }
                NeriboDivider()
                SecurityRow(
                    icon = Icons.Outlined.Timer,
                    title = "Auto-lock",
                    subtitle = autoLockLabel(autoLockSeconds),
                    onClick = { showAutoLock = true },
                )
            }

            Spacer(modifier = Modifier.height(spacing.xl))
            SectionHeader(text = "Privacy")
            Spacer(modifier = Modifier.height(spacing.sm))
            NeriboCard(modifier = Modifier.fillMaxWidth()) {
                SecurityRow(
                    icon = Icons.Outlined.VisibilityOff,
                    title = "Hide app in recent apps",
                    subtitle = "Also blocks screenshots",
                    onClick = null,
                    trailing = {
                        Switch(
                            checked = blockScreenshots,
                            onCheckedChange = { manager.setBlockScreenshots(it) },
                            colors = switchColors(),
                        )
                    },
                )
            }

            Spacer(modifier = Modifier.height(spacing.lg))
            Text(
                text = "The Diary and other vaults can each have their own extra lock. " +
                    "Open a vault and choose its lock settings to turn it on.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.xs),
            )
        }
    }

    if (showChangePin) {
        NeriboBottomSheet(onDismiss = { showChangePin = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                PinSetupFlow(
                    onSave = { pin -> manager.savePin(pin) },
                    onFinished = {
                        showChangePin = false
                        scope.launch { snackbarHostState.showSnackbar("PIN changed") }
                    },
                    requireCurrent = true,
                    verifyCurrent = { pin -> manager.checkPin(pin) },
                    newTitle = "Choose a new PIN",
                )
            }
        }
    }

    if (showAutoLock) {
        NeriboBottomSheet(onDismiss = { showAutoLock = false }) {
            Text(
                text = "Auto-lock",
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
            )
            Text(
                text = "How long the app can be out of sight before it asks for your PIN.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.xs, bottom = spacing.md),
            )
            autoLockOptions.forEach { (label, seconds) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable {
                            manager.setAutoLockSeconds(seconds)
                            showAutoLock = false
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurface,
                    )
                    if (seconds == autoLockSeconds) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = "Selected",
                            tint = colors.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
)

@Composable
private fun SecurityRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)?,
    trailing: @Composable () -> Unit = {},
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickModifier)
            .heightIn(min = 64.dp)
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = colors.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(spacing.lg))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}

private fun findFragmentActivity(context: android.content.Context): FragmentActivity? {
    var current: android.content.Context = context
    while (current is android.content.ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return null
}
