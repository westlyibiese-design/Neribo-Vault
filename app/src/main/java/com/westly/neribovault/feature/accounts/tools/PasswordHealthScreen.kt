package com.westly.neribovault.feature.accounts.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.core.util.LifecycleSaveEffect
import com.westly.neribovault.feature.accounts.detail.SecretAccessSheets
import com.westly.neribovault.feature.accounts.detail.rememberSecretAccess
import com.westly.neribovault.feature.accounts.editor.PasswordStrength
import com.westly.neribovault.feature.accounts.security.AccountsSessionMode
import com.westly.neribovault.feature.accounts.security.AccountsVault
import kotlinx.coroutines.launch

/**
 * Which stored passwords are weak and which accounts share one. It needs the real Accounts
 * session, runs on this phone, and shows account names and strength words only: never a password.
 * The result is cleared when the session ends or the screen stops.
 */
@Composable
fun PasswordHealthScreen(onBack: () -> Unit) {
    val vm = neriboViewModel(key = "accounts-health") { c -> PasswordHealthViewModel(c.accountsRepository) }
    val state by vm.state.collectAsStateWithLifecycle()
    val mode by AccountsVault.mode.collectAsStateWithLifecycle()
    val spacing = NeriboTheme.spacing
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val access = rememberSecretAccess(
        onMessage = { text ->
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(text)
            }
        },
    )

    // A report never outlives the session or the screen.
    LifecycleSaveEffect(onSave = { vm.clear() })
    LaunchedEffect(mode) { if (mode != AccountsSessionMode.Real) vm.clear() }

    NeriboScaffold(
        topBar = { NeriboTopBar(title = "Password health", onBack = onBack) },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        val report = state.report
        when {
            mode == AccountsSessionMode.Decoy -> Text(
                text = AccountsVault.BLOCKED_MESSAGE,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxSize().padding(padding).padding(spacing.screen),
            )
            report != null && mode == AccountsSessionMode.Real -> ReportContent(
                report = report,
                unreadable = state.unreadable,
                isChecking = state.isChecking,
                onCheckAgain = { access.run(true) { vm.check() } },
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            else -> EmptyState(
                icon = Icons.Outlined.Lock,
                title = if (mode == AccountsSessionMode.Real) "Ready to check" else "Unlock to check",
                message = if (mode == AccountsSessionMode.Real) {
                    "Check your passwords on this phone for weak and shared ones."
                } else {
                    "Unlock the Accounts PIN to check your passwords on this phone."
                },
                modifier = Modifier.fillMaxSize().padding(padding),
                actionLabel = if (state.isChecking) null else if (mode == AccountsSessionMode.Real) "Check now" else "Unlock",
                onAction = { access.run(true) { vm.check() } },
            )
        }
    }
    SecretAccessSheets(access = access)
}

@Composable
private fun ReportContent(
    report: HealthReport,
    unreadable: Int,
    isChecking: Boolean,
    onCheckAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.screen, vertical = spacing.sm),
    ) {
        if (report.checked == 0) {
            EmptyState(
                icon = Icons.Outlined.Lock,
                title = "No passwords to check yet",
                message = "Add a password to an account and it will be checked here.",
                modifier = Modifier.fillMaxWidth().padding(vertical = spacing.xl),
            )
        } else {
            NeriboCard {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(spacing.lg),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    SummaryNumber(report.checked, "Checked")
                    SummaryNumber(report.strongOrGood, "Strong or good")
                    SummaryNumber(report.weak.size, "Weak")
                    SummaryNumber(report.reused.size, "Shared")
                }
            }
            if (report.weak.isEmpty() && report.reused.isEmpty()) {
                Text(
                    text = "All good. No weak or shared passwords found.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurface,
                    modifier = Modifier.padding(vertical = spacing.lg),
                )
            }
            if (report.weak.isNotEmpty()) {
                SectionHeader(
                    text = "Weak passwords",
                    modifier = Modifier.padding(top = spacing.xl, bottom = spacing.xs),
                )
                for (weak in report.weak) {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = weak.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = PasswordStrength.label(weak.score),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    NeriboDivider()
                }
            }
            if (report.reused.isNotEmpty()) {
                SectionHeader(
                    text = "Shared passwords",
                    modifier = Modifier.padding(top = spacing.xl, bottom = spacing.sm),
                )
                for (group in report.reused) {
                    NeriboCard(modifier = Modifier.padding(bottom = spacing.md)) {
                        Column(modifier = Modifier.fillMaxWidth().padding(spacing.lg)) {
                            for (label in group.labels) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colors.onSurface,
                                )
                            }
                            Text(
                                text = "Used by ${group.labels.size} accounts",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(top = spacing.xs),
                            )
                        }
                    }
                }
            }
        }
        if (unreadable > 0) {
            Text(
                text = if (unreadable == 1) {
                    "1 password could not be read."
                } else {
                    "$unreadable passwords could not be read."
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = spacing.md),
            )
        }
        Spacer(modifier = Modifier.height(spacing.lg))
        NeriboButton(
            text = "Check again",
            onClick = onCheckAgain,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isChecking,
            style = ButtonStyle.Secondary,
        )
        Text(
            text = "Checked on this phone. Passwords are never shown here and never leave it.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = spacing.md, bottom = spacing.xl),
        )
    }
}

@Composable
private fun SummaryNumber(value: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Serif),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
