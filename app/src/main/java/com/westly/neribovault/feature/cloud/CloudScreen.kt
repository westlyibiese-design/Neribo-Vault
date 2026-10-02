package com.westly.neribovault.feature.cloud

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.lock.findActivity
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.ConfirmDialog
import com.westly.neribovault.core.ui.components.LoadingState
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboDivider
import com.westly.neribovault.core.ui.components.NeriboScaffold
import com.westly.neribovault.core.ui.components.NeriboTextField
import com.westly.neribovault.core.ui.components.NeriboTopBar
import com.westly.neribovault.core.ui.components.SectionHeader
import com.westly.neribovault.core.util.copyToClipboard
import com.westly.neribovault.data.cloud.SyncTables
import kotlinx.coroutines.launch

/** The Cloud sync settings screen: set up the project, sign in, and manage what syncs. */
@Composable
fun CloudScreen(onBack: () -> Unit) {
    val vm = neriboViewModel { c -> CloudViewModel(c.cloudAuth, c.syncEngine) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val spacing = NeriboTheme.spacing

    NeriboScaffold(
        topBar = { NeriboTopBar(title = "Cloud sync", onBack = onBack) },
        snackbarHostState = snackbar,
    ) { padding ->
        if (state.loading) {
            LoadingState(modifier = Modifier.fillMaxSize().padding(padding))
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.screen, vertical = spacing.lg),
            ) {
                when {
                    !state.hasProject -> NotConfiguredSection(
                        state = state,
                        onUrlChange = vm::onUrlChange,
                        onKeyChange = vm::onKeyChange,
                        onSave = vm::saveProject,
                        onUseShared = vm::useSharedProject,
                        onCopySql = {
                            context.copyToClipboard("Neribo Vault SQL script", SQL_SCRIPT)
                            scope.launch { snackbar.showSnackbar("Script copied") }
                        },
                    )
                    !state.signedIn -> SignedOutSection(
                        state = state,
                        onGoogleSignIn = {
                            if (activity != null) vm.signInWithGoogle(activity) else vm.onGoogleUnavailable()
                        },
                        onChangeProject = vm::changeProject,
                        onUseShared = vm::useSharedProject,
                        onUseOwn = vm::useOwnProject,
                    )
                    else -> SignedInSection(
                        state = state,
                        onSyncNow = vm::syncNow,
                        onVaultToggle = vm::setVaultEnabled,
                        onSignOut = vm::askSignOut,
                    )
                }
                Spacer(modifier = Modifier.height(spacing.xxl))
            }
        }
    }

    if (state.showSignOutConfirm) {
        ConfirmDialog(
            title = "Sign out?",
            message = "Everything stays on this phone. Syncing stops until you sign in again.",
            confirmLabel = "Sign out",
            onConfirm = vm::confirmSignOut,
            onDismiss = vm::dismissSignOut,
        )
    }
}

@Composable
private fun NotConfiguredSection(
    state: CloudUiState,
    onUrlChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onSave: () -> Unit,
    onUseShared: () -> Unit,
    onCopySql: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    BodyText(
        "Sync your vaults through your own free Supabase project. " +
            "Nothing leaves this phone until you sign in.",
    )
    Spacer(modifier = Modifier.height(spacing.xl))
    SectionHeader(text = "How to set up")
    Spacer(modifier = Modifier.height(spacing.sm))
    NeriboCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(spacing.lg)) {
            SetupStep(1, "Create a free project at supabase.com.")
            SetupStep(2, "Open the SQL editor in your project.")
            SetupStep(3, "Paste the setup script and run it. Tap Copy to get the script.")
            Spacer(modifier = Modifier.height(spacing.sm))
            NeriboButton(
                text = "Copy script",
                onClick = onCopySql,
                style = ButtonStyle.Secondary,
                leadingIcon = Icons.Outlined.ContentCopy,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(spacing.md))
            SetupStep(4, "In Project Settings, open API. Copy the Project URL and the anon public key into the boxes below.")
        }
    }
    Spacer(modifier = Modifier.height(spacing.xl))
    NeriboTextField(
        value = state.urlInput,
        onValueChange = onUrlChange,
        modifier = Modifier.fillMaxWidth(),
        label = "Project URL",
        placeholder = "https://abcd1234.supabase.co",
        isError = state.urlError != null,
        supportingText = state.urlError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
    Spacer(modifier = Modifier.height(spacing.md))
    NeriboTextField(
        value = state.keyInput,
        onValueChange = onKeyChange,
        modifier = Modifier.fillMaxWidth(),
        label = "Anon (public) key",
        placeholder = "Paste the anon public key",
        isError = state.keyError != null,
        supportingText = state.keyError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
    MessageLines(error = state.error, info = state.info)
    Spacer(modifier = Modifier.height(spacing.lg))
    NeriboButton(
        text = "Save",
        onClick = onSave,
        enabled = !state.working,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(spacing.md))
    SmallNote("The URL and key are stored encrypted on this phone. They are never part of the app's code.")
    if (state.sharedAvailable) {
        Spacer(modifier = Modifier.height(spacing.md))
        NeriboButton(
            text = "Use the Neribo cloud instead",
            onClick = onUseShared,
            enabled = !state.working,
            style = ButtonStyle.Text,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SignedOutSection(
    state: CloudUiState,
    onGoogleSignIn: () -> Unit,
    onChangeProject: () -> Unit,
    onUseShared: () -> Unit,
    onUseOwn: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    if (state.googleAvailable) {
        BodyText("Sign in with your Google account to sync your vaults. Your vaults stay on this phone until you do.")
        Spacer(modifier = Modifier.height(spacing.sm))
        SmallNote(
            "Synced data is stored in the Neribo cloud and is not end-to-end encrypted. " +
                "If you would rather keep it in a project only you control, use your own Supabase project below.",
        )
        MessageLines(error = state.error, info = state.info)
        Spacer(modifier = Modifier.height(spacing.lg))
        NeriboButton(
            text = "Continue with Google",
            onClick = onGoogleSignIn,
            enabled = !state.working,
            style = ButtonStyle.Primary,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        BodyText("Google sign-in works with the Neribo cloud only. Switch back to the Neribo cloud to sign in.")
        MessageLines(error = state.error, info = state.info)
    }
    Spacer(modifier = Modifier.height(spacing.sm))
    if (state.usingOwnProject) {
        NeriboButton(
            text = "Change project",
            onClick = onChangeProject,
            enabled = !state.working,
            style = ButtonStyle.Text,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.sharedAvailable) {
            NeriboButton(
                text = "Use the Neribo cloud instead",
                onClick = onUseShared,
                enabled = !state.working,
                style = ButtonStyle.Text,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
        NeriboButton(
            text = "Use my own Supabase project instead",
            onClick = onUseOwn,
            enabled = !state.working,
            style = ButtonStyle.Text,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SignedInSection(
    state: CloudUiState,
    onSyncNow: () -> Unit,
    onVaultToggle: (String, Boolean) -> Unit,
    onSignOut: () -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    NeriboCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(spacing.lg)) {
            Text(
                text = state.email,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
            )
            Spacer(modifier = Modifier.height(spacing.xs))
            Text(
                text = if (state.usingOwnProject) "Your own Supabase project" else "Neribo cloud",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(spacing.xs))
            Text(
                text = state.statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.statusIsError) colors.error else colors.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(spacing.lg))
            NeriboButton(
                text = if (state.syncing) "Syncing" else "Sync now",
                onClick = onSyncNow,
                enabled = !state.syncing,
                leadingIcon = Icons.Outlined.Sync,
                modifier = Modifier.fillMaxWidth(),
            )
            MessageLines(error = null, info = state.info)
        }
    }
    Spacer(modifier = Modifier.height(spacing.xl))
    SectionHeader(text = "What to sync")
    Spacer(modifier = Modifier.height(spacing.sm))
    NeriboCard(modifier = Modifier.fillMaxWidth()) {
        SyncTables.VAULTS.forEachIndexed { index, vault ->
            if (index > 0) NeriboDivider()
            VaultSwitchRow(
                label = vault.label,
                note = vaultNote(vault.id, state.usingOwnProject),
                checked = state.vaultEnabled[vault.id] ?: vault.defaultEnabled,
                onCheckedChange = { enabled -> onVaultToggle(vault.id, enabled) },
            )
        }
    }
    Spacer(modifier = Modifier.height(spacing.xl))
    SectionHeader(text = "What does not sync")
    Spacer(modifier = Modifier.height(spacing.sm))
    NeriboCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(spacing.lg)) {
            BulletLine(
                "Developer secrets never leave this phone. Their key comes from your six-digit PIN, " +
                    "so keeping a copy on a server would let anyone with access to it guess the PIN.",
            )
            BulletLine("Photos and attached files stay on the phone where you added them. Only the text syncs.")
            BulletLine("The activity log stays on this phone.")
        }
    }
    Spacer(modifier = Modifier.height(spacing.xl))
    SectionHeader(text = "Good to know")
    Spacer(modifier = Modifier.height(spacing.sm))
    NeriboCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(spacing.lg)) {
            BulletLine(
                "The newest edit wins, judged by each phone's clock. Keep date and time set to automatic " +
                    "on every device.",
            )
            BulletLine("Signing out keeps all your data on this phone.")
            if (state.usingOwnProject) {
                BulletLine(
                    "To remove your cloud copy, open your Supabase dashboard, then Table editor, then " +
                        "vault_items, and delete the rows. You can also delete the whole project there.",
                )
            } else {
                BulletLine("Deleting your cloud copy from inside the app is not available yet.")
            }
        }
    }
    Spacer(modifier = Modifier.height(spacing.xl))
    NeriboButton(
        text = "Sign out",
        onClick = onSignOut,
        style = ButtonStyle.Secondary,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The note shown under a vault switch, if it has one. */
private fun vaultNote(vaultId: String, usingOwnProject: Boolean): String? = when (vaultId) {
    "diary" -> if (usingOwnProject) {
        "Cloud data is stored in your own Supabase project and is not end-to-end encrypted."
    } else {
        "Cloud data is stored in the Neribo cloud and is not end-to-end encrypted."
    }
    "developer" -> "Projects, bugs, tasks, plans, prompts and documents. Secrets never sync."
    else -> null
}

@Composable
private fun VaultSwitchRow(
    label: String,
    note: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = 56.dp)
            .padding(horizontal = spacing.lg, vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
            if (note != null) {
                Text(text = note, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        Spacer(modifier = Modifier.width(spacing.md))
        Switch(checked = checked, onCheckedChange = null, colors = cloudSwitchColors())
    }
}

@Composable
private fun cloudSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
)

@Composable
private fun SetupStep(number: Int, text: String) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    Row(modifier = Modifier.padding(bottom = spacing.sm)) {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.primary,
            modifier = Modifier.width(24.dp),
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
    }
}

@Composable
private fun BulletLine(text: String) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    Row(modifier = Modifier.padding(bottom = spacing.sm)) {
        Text(
            text = "\u2022",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.width(16.dp),
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun BodyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun SmallNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** An inline error and/or info line, shown under a form. */
@Composable
private fun MessageLines(error: String?, info: String?) {
    val spacing = NeriboTheme.spacing
    val colors = MaterialTheme.colorScheme
    if (error != null) {
        Spacer(modifier = Modifier.height(spacing.md))
        Text(text = error, style = MaterialTheme.typography.bodyMedium, color = colors.error)
    }
    if (info != null) {
        Spacer(modifier = Modifier.height(spacing.md))
        Text(text = info, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    }
}

/** The same script as supabase/neribo_vault_schema.sql, so it can be copied from the screen. */
private const val SQL_SCRIPT = """-- =====================================================================
-- Neribo Vault: cloud sync schema (Phase 15)
-- Paste this whole script into the Supabase SQL editor and press Run.
-- It is safe to run more than once.
--
-- Step 1. Create one table, vault_items. Every vault row from the app is
--         stored as a JSON object in this single table, keyed by the
--         signed-in user, the kind (the app table name) and the row id.
-- Step 2. Add an index so "what changed since my last sync" is fast.
-- Step 3. Turn on Row Level Security so each person can only ever see
--         and change their own rows.
-- Step 4. Add four policies (select, insert, update, delete), each
--         restricted to the row owner.
--
-- Also decide, in Authentication settings in the Supabase dashboard,
-- whether email confirmation is required before people can sign in.
-- To remove your cloud data later, delete the rows in the Table editor.
-- =====================================================================

create table if not exists public.vault_items (
    user_id    uuid    not null default auth.uid() references auth.users (id) on delete cascade,
    kind       text    not null,
    id         text    not null,
    updated_at bigint  not null,
    is_deleted boolean not null default false,
    data       jsonb   not null,
    primary key (user_id, kind, id)
);

create index if not exists vault_items_user_updated_idx
    on public.vault_items (user_id, updated_at);

alter table public.vault_items enable row level security;

drop policy if exists "vault_items_select_own" on public.vault_items;
create policy "vault_items_select_own" on public.vault_items
    for select to authenticated
    using (auth.uid() = user_id);

drop policy if exists "vault_items_insert_own" on public.vault_items;
create policy "vault_items_insert_own" on public.vault_items
    for insert to authenticated
    with check (auth.uid() = user_id);

drop policy if exists "vault_items_update_own" on public.vault_items;
create policy "vault_items_update_own" on public.vault_items
    for update to authenticated
    using (auth.uid() = user_id)
    with check (auth.uid() = user_id);

drop policy if exists "vault_items_delete_own" on public.vault_items;
create policy "vault_items_delete_own" on public.vault_items
    for delete to authenticated
    using (auth.uid() = user_id);
"""
