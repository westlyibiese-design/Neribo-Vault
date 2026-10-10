package com.westly.neribovault.feature.authenticator.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.MenuAction
import com.westly.neribovault.core.ui.components.NeriboCard
import com.westly.neribovault.core.ui.components.NeriboIconButton
import com.westly.neribovault.data.local.entity.TotpAccountEntity
import com.westly.neribovault.feature.authenticator.SecretState
import com.westly.neribovault.feature.authenticator.engine.CodeFormat
import com.westly.neribovault.feature.authenticator.engine.OtpAlgorithm
import com.westly.neribovault.feature.authenticator.engine.Totp

/** In the last seconds of a code the ring and the code turn amber and the next code is shown. */
private const val WARNING_SECONDS = 5

/** The text shown instead of a code when the secret cannot be decrypted. */
const val UNREADABLE_TEXT = "Can't be read on this phone"

/**
 * One account: letter avatar, serif service name, account name, and on the right the live code with
 * its countdown ring. [hidden] shows dots instead of the code. Tap calls [onClick]; long-press or
 * the three dots open [actions].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CodeCard(
    account: TotpAccountEntity,
    secretState: SecretState,
    nowMillis: Long,
    hidden: Boolean,
    actions: List<MenuAction>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    var menuOpen by remember { mutableStateOf(false) }

    NeriboCard(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuOpen = true },
                onLongClickLabel = "More options",
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.lg, top = spacing.md, end = spacing.xs, bottom = spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LetterAvatar(letter = avatarLetter(account))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = spacing.md, end = spacing.sm),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = account.issuer.ifBlank { account.accountName },
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (account.isPinned) {
                        Icon(
                            imageVector = Icons.Outlined.PushPin,
                            contentDescription = "Pinned",
                            modifier = Modifier
                                .padding(start = spacing.xs)
                                .size(14.dp),
                            tint = colors.onSurfaceVariant,
                        )
                    }
                }
                if (account.issuer.isNotBlank() && account.accountName.isNotBlank()) {
                    Text(
                        text = account.accountName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            CodeBlock(account = account, secretState = secretState, nowMillis = nowMillis, hidden = hidden)
            Box {
                NeriboIconButton(
                    icon = Icons.Outlined.MoreVert,
                    contentDescription = "More options",
                    onClick = { menuOpen = true },
                )
                CardMenu(expanded = menuOpen, actions = actions, onDismiss = { menuOpen = false })
            }
        }
    }
}

@Composable
private fun CodeBlock(
    account: TotpAccountEntity,
    secretState: SecretState,
    nowMillis: Long,
    hidden: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    when (secretState) {
        SecretState.Unreadable -> {
            Text(
                text = UNREADABLE_TEXT,
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
                modifier = Modifier.padding(end = spacing.xs),
            )
        }
        SecretState.Pending -> {
            Text(
                text = "\u2022\u2022\u2022 \u2022\u2022\u2022",
                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                color = colors.onSurfaceVariant,
            )
        }
        is SecretState.Ready -> ReadyCode(account, secretState.secret, nowMillis, hidden)
    }
}

@Composable
private fun ReadyCode(
    account: TotpAccountEntity,
    secret: ByteArray,
    nowMillis: Long,
    hidden: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val spacing = NeriboTheme.spacing
    val algorithm = OtpAlgorithm.values().firstOrNull { it.name == account.algorithm } ?: OtpAlgorithm.SHA1
    val period = account.periodSeconds
    val remaining = Totp.secondsRemaining(nowMillis, period)
    val warning = remaining <= WARNING_SECONDS
    val accent = if (warning) NeriboTheme.extraColors.warning else colors.primary
    val codeColor = if (warning) NeriboTheme.extraColors.warning else colors.onSurface

    val code = try {
        Totp.code(secret, nowMillis, account.digits, period, algorithm)
    } catch (e: Exception) {
        null
    }
    if (code == null) {
        Text(
            text = UNREADABLE_TEXT,
            style = MaterialTheme.typography.bodySmall,
            color = colors.error,
            modifier = Modifier.padding(end = spacing.xs),
        )
        return
    }
    val shown = if (hidden) CodeFormat.group("\u2022".repeat(account.digits)) else CodeFormat.group(code)

    Column(horizontalAlignment = Alignment.End) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = shown,
                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                color = codeColor,
                maxLines = 1,
            )
            CountdownRing(remaining = remaining, period = period, color = accent)
        }
        if (warning && !hidden) {
            val next = try {
                Totp.code(secret, nowMillis + remaining * 1000L, account.digits, period, algorithm)
            } catch (e: Exception) {
                null
            }
            if (next != null) {
                Text(
                    text = "Next " + CodeFormat.group(next),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun CountdownRing(remaining: Int, period: Int, color: androidx.compose.ui.graphics.Color) {
    val target = remaining.toFloat() / period.toFloat()
    var previous by remember { mutableFloatStateOf(target) }
    // Drain smoothly each second, but jump straight back to full when a new code starts.
    val spec: AnimationSpec<Float> = if (target > previous) snap() else tween(durationMillis = 1000, easing = LinearEasing)
    val progress by animateFloatAsState(targetValue = target, animationSpec = spec, label = "ring")
    SideEffect { previous = target }
    CircularProgressIndicator(
        progress = { progress },
        modifier = Modifier
            .size(28.dp)
            .semantics { contentDescription = "$remaining seconds left" },
        color = color,
        strokeWidth = 3.dp,
        trackColor = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun LetterAvatar(letter: String) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(colors.surfaceVariant)
            .border(1.dp, colors.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter,
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
            color = colors.onSurface,
        )
    }
}

/** The first letter of the service, or of the account name when there is no service. */
private fun avatarLetter(account: TotpAccountEntity): String {
    val source = account.issuer.trim().ifEmpty { account.accountName.trim() }
    val first = source.firstOrNull { it.isLetterOrDigit() } ?: return "?"
    return first.uppercaseChar().toString()
}

@Composable
private fun CardMenu(expanded: Boolean, actions: List<MenuAction>, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        actions.forEach { action ->
            val tint = if (action.destructive) colors.error else colors.onSurface
            DropdownMenuItem(
                text = {
                    Text(text = action.label, style = MaterialTheme.typography.bodyLarge, color = tint)
                },
                onClick = {
                    onDismiss()
                    action.onClick()
                },
                leadingIcon = action.icon?.let { vector ->
                    {
                        Icon(
                            imageVector = vector,
                            contentDescription = null,
                            tint = if (action.destructive) colors.error else colors.onSurfaceVariant,
                        )
                    }
                },
            )
        }
    }
}
