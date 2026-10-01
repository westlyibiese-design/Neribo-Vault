package com.westly.neribovault.feature.developer.secrets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.di.neriboViewModel
import com.westly.neribovault.core.ui.components.ButtonStyle
import com.westly.neribovault.core.ui.components.EmptyState
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboButton
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.OverflowMenu
import com.westly.neribovault.data.local.entity.SecretEntity
import com.westly.neribovault.feature.developer.secretCategoryLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MASKED_VALUE = "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"
private const val REVEAL_SECONDS = 15
private const val COPIED_MESSAGE = "Copied. Clipboard clears in 30 seconds"

/**
 * The Secrets tab of a project: a calm list with masked values, Reveal and Copy (which ask for
 * the secrets PIN first) and an Add button. The tab brings its own add button, so the project
 * detail screen shows no floating action button here.
 */
@Composable
fun SecretsTab(
    projectId: String,
    snackbarHostState: SnackbarHostState,
    onOpenSecret: (secretId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    remember(context) {
        SecretsVault.attach(context)
        true
    }
    val vm = neriboViewModel(key = "secrets-tab-$projectId") { c ->
        SecretsTabViewModel(projectId, c.secretsRepository)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val setup by SecretsVault.setup.collectAsStateWithLifecycle()
    val mode by SecretsVault.mode.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val spacing = NeriboTheme.spacing

    var showUnlock by remember { mutableStateOf(false) }
    var showSetup by remember { mutableStateOf(false) }
    var showPinSettings by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    // No screenshots while any secret is on screen.
    SecureWindowEffect(active = mode != SessionMode.Locked && state.revealedIds.isNotEmpty())

    fun showMessage(text: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(text)
        }
    }

    // Runs [action] in an open session, asking for the PIN first when the secrets are locked.
    // Write actions ([requireReal]) are refused, with a neutral message, in the other session.
    fun runWithSession(requireReal: Boolean, action: () -> Unit) {
        when (SecretsVault.mode.value) {
            SessionMode.Real -> {
                SecretsVault.touch()
                action()
            }
            SessionMode.Decoy -> {
                if (requireReal) {
                    showMessage(SecretsVault.BLOCKED_MESSAGE)
                } else {
                    SecretsVault.touch()
                    action()
                }
            }
            SessionMode.Locked -> {
                pendingAction = { runWithSession(requireReal, action) }
                showUnlock = true
            }
        }
    }

    fun copySecret(secret: SecretEntity) {
        val copyNow = {
            val value = SecretsVault.displayValue(secret)
            if (value == null) {
                showMessage("Couldn't read that secret")
            } else {
                SecretsClipboard.copy(context, value)
                if (secret.isSecret) vm.logCopied(secret.id)
                showMessage(COPIED_MESSAGE)
            }
        }
        if (secret.isSecret) runWithSession(false, copyNow) else copyNow()
    }

    fun toggleReveal(secret: SecretEntity) {
        runWithSession(false) {
            if (secret.id in state.revealedIds) vm.hide(secret.id) else vm.reveal(secret.id)
        }
    }

    fun deleteSecret(secret: SecretEntity) {
        runWithSession(true) {
            vm.delete(secret.id)
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                val result = snackbarHostState.showSnackbar(
                    message = "Moved to Recently deleted",
                    actionLabel = "Undo",
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) vm.restore(secret.id)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when (setup) {
            SecretsSetup.Loading -> Unit
            SecretsSetup.Unavailable -> EmptyState(
                icon = Icons.Outlined.Lock,
                title = "Secrets aren't available",
                message = "This phone's secure storage couldn't be opened. Close the app and try again.",
                modifier = Modifier.fillMaxSize(),
            )
            SecretsSetup.NotSetUp -> EmptyState(
                icon = Icons.Outlined.Lock,
                title = "Keep your keys safe",
                message = "Secrets are encrypted on this phone with a PIN of their own. " +
                    "If you forget that PIN, they can't be recovered.",
                modifier = Modifier.fillMaxSize(),
                actionLabel = "Set up secrets",
                onAction = { showSetup = true },
            )
            SecretsSetup.Ready -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = spacing.screen,
                    end = spacing.screen,
                    top = spacing.sm,
                    bottom = spacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                item(key = "status") {
                    SessionBar(
                        unlocked = mode != SessionMode.Locked,
                        onUnlock = { showUnlock = true },
                        onLock = { SecretsVault.endSession() },
                        onPin = { runWithSession(true) { showPinSettings = true } },
                    )
                }
                item(key = "add") {
                    NeriboButton(
                        text = "Add secret",
                        onClick = { runWithSession(true) { onOpenSecret("new") } },
                        modifier = Modifier.fillMaxWidth(),
                        style = ButtonStyle.Secondary,
                        leadingIcon = Icons.Outlined.Add,
                    )
                }
                when {
                    state.isLoading -> Unit
                    state.secrets.isEmpty() -> item(key = "empty") {
                        EmptyState(
                            icon = Icons.Outlined.Lock,
                            title = "No secrets yet",
                            message = "Keep API keys, database URLs and tokens for this project here, " +
                                "encrypted.",
                        )
                    }
                    else -> items(state.secrets, key = { it.id }) { secret ->
                        SecretRow(
                            secret = secret,
                            mode = mode,
                            revealed = secret.id in state.revealedIds,
                            onToggleReveal = { toggleReveal(secret) },
                            onAutoHide = { vm.hide(secret.id) },
                            onCopy = { copySecret(secret) },
                            actions = listOf(
                                MenuAction(
                                    label = "Edit",
                                    onClick = { runWithSession(true) { onOpenSecret(secret.id) } },
                                    icon = Icons.Outlined.Edit,
                                ),
                                MenuAction(
                                    label = "Delete",
                                    onClick = { deleteSecret(secret) },
                                    icon = Icons.Outlined.Delete,
                                    destructive = true,
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }

    if (showUnlock) {
        SecretsUnlockSheet(
            onDismiss = {
                showUnlock = false
                pendingAction = null
            },
            onUnlocked = {
                showUnlock = false
                val next = pendingAction
                pendingAction = null
                next?.invoke()
            },
        )
    }
    if (showSetup) {
        SecretsSetupSheet(
            onDismiss = { showSetup = false },
            onDone = {
                showSetup = false
                showMessage("Your secrets are ready")
            },
        )
    }
    if (showPinSettings) {
        SecretsPinSettingsSheet(
            onDismiss = { showPinSettings = false },
            onMessage = { text -> showMessage(text) },
        )
    }
}

@Composable
private fun SessionBar(
    unlocked: Boolean,
    onUnlock: () -> Unit,
    onLock: () -> Unit,
    onPin: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Lock,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (unlocked) colors.primary else colors.onSurfaceVariant,
        )
        Text(
            text = if (unlocked) "Unlocked" else "Locked",
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .padding(start = spacing.sm),
        )
        if (unlocked) {
            NeriboButton(text = "PIN", onClick = onPin, style = ButtonStyle.Text)
            NeriboButton(text = "Lock", onClick = onLock, style = ButtonStyle.Text)
        } else {
            NeriboButton(text = "Unlock", onClick = onUnlock, style = ButtonStyle.Text)
        }
    }
}

@Composable
private fun SecretRow(
    secret: SecretEntity,
    mode: SessionMode,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
    onAutoHide: () -> Unit,
    onCopy: () -> Unit,
    actions: List<MenuAction>,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val showing = revealed && mode != SessionMode.Locked

    // Only a revealed value is ever decrypted, and it is dropped again when masked.
    val shownValue: String? = if (!secret.isSecret) {
        secret.publicValue.orEmpty()
    } else if (showing) {
        remember(secret.ciphertext, secret.iv, secret.decoyValue, mode) {
            SecretsVault.displayValue(secret)
        } ?: "Unavailable"
    } else {
        null
    }

    LaunchedEffect(showing) {
        if (showing) {
            delay(REVEAL_SECONDS * 1000L)
            onAutoHide()
        }
    }

    NeriboCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.lg, top = spacing.xs, end = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = spacing.sm),
            ) {
                Text(
                    text = secret.label.trim().ifEmpty { "Untitled" },
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = secretCategoryLabel(secret.category),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
            OverflowMenu(actions = actions)
        }
        Text(
            text = shownValue ?: MASKED_VALUE,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = if (shownValue == null) colors.onSurfaceVariant else colors.onSurface,
            maxLines = if (showing) 8 else 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.xs),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.sm, vertical = spacing.xs),
            horizontalArrangement = Arrangement.End,
        ) {
            if (secret.isSecret) {
                NeriboButton(
                    text = if (showing) "Hide" else "Reveal",
                    onClick = onToggleReveal,
                    style = ButtonStyle.Text,
                )
            }
            NeriboButton(text = "Copy", onClick = onCopy, style = ButtonStyle.Text)
        }
    }
}
