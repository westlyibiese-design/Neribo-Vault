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

/** In the last seconds of a code the ring and the code turn amber. */
private const val WARNING_SECONDS = 5

/** The text shown instead of a code when the secret cannot be decrypted. */
const val UNREADABLE_TEXT = "Can't be read on this phone"

/** What the code area of a card shows. Holds only display text, never the secret. */
private sealed interface CodeUi {
    object Unreadable : CodeUi
    object Pending : CodeUi
    class Live(val shown: String, val remaining: Int, val period: Int, val warning: Boolean) : CodeUi
}

/**
 * One account: letter avatar, serif service name and account name at full width, the live code on
 * its own row below them, and on the right the countdown ring and the three dots. [hidden] shows
 * dots instead of the code. Tap calls [onClick]; long-press or the three dots open [actions].
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
    val codeUi = buildCodeUi(account, secretState, nowMillis, hidden)

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
                        maxLines = 2,
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
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                CodeText(codeUi = codeUi, modifier = Modifier.padding(top = spacing.xs))
            }
            if (codeUi is CodeUi.Live) {
                CountdownRing(
                    remaining = codeUi.remaining,
                    period = codeUi.period,
                    color = if (codeUi.warning) NeriboTheme.extraColors.warning else colors.primary,
                )
            }
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

/** Works out what to show for the code. Never logs the secret or the code. */
private fun buildCodeUi(
    account: TotpAccountEntity,
    secretState: SecretState,
    nowMillis: Long,
    hidden: Boolean,
): CodeUi = when (secretState) {
    SecretState.Unreadable -> CodeUi.Unreadable
    SecretState.Pending -> CodeUi.Pending
    is SecretState.Ready -> {
        val algorithm = OtpAlgorithm.values().firstOrNull { it.name == account.algorithm } ?: OtpAlgorithm.SHA1
        val period = account.periodSeconds
        val remaining = Totp.secondsRemaining(nowMillis, period)
        val code = try {
            Totp.code(secretState.secret, nowMillis, account.digits, period, algorithm)
        } catch (e: Exception) {
            null
        }
        if (code == null) {
            CodeUi.Unreadable
        } else {
            val shown = if (hidden) CodeFormat.group("\u2022".repeat(account.digits)) else CodeFormat.group(code)
            CodeUi.Live(shown, remaining, period, remaining <= WARNING_SECONDS)
        }
    }
}

@Composable
private fun CodeText(codeUi: CodeUi, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    when (codeUi) {
        CodeUi.Unreadable -> {
            Text(
                text = UNREADABLE_TEXT,
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
                modifier = modifier,
            )
        }
        CodeUi.Pending -> {
            Text(
                text = "\u2022\u2022\u2022 \u2022\u2022\u2022",
                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                color = colors.onSurfaceVariant,
                maxLines = 1,
                modifier = modifier,
            )
        }
        is CodeUi.Live -> {
            Text(
                text = codeUi.shown,
                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                color = if (codeUi.warning) NeriboTheme.extraColors.warning else colors.onSurface,
                maxLines = 1,
                softWrap = false,
                modifier = modifier,
            )
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
