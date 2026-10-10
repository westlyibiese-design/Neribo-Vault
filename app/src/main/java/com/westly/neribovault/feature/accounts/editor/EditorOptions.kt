package com.westly.neribovault.feature.accounts.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.westly.neribovault.core.design.NeriboTheme
import com.westly.neribovault.core.ui.components.NeriboChip
import com.westly.neribovault.feature.accounts.ACCOUNT_STATUS_ACTIVE
import com.westly.neribovault.feature.accounts.ACCOUNT_STATUS_CLOSED
import com.westly.neribovault.feature.accounts.ACCOUNT_STATUS_INACTIVE
import com.westly.neribovault.feature.accounts.PlatformPresets
import com.westly.neribovault.feature.accounts.SIGN_IN_APPLE
import com.westly.neribovault.feature.accounts.SIGN_IN_EMAIL_PASSWORD
import com.westly.neribovault.feature.accounts.SIGN_IN_GITHUB
import com.westly.neribovault.feature.accounts.SIGN_IN_GOOGLE
import com.westly.neribovault.feature.accounts.SIGN_IN_OTHER
import com.westly.neribovault.feature.accounts.SIGN_IN_PHONE
import com.westly.neribovault.feature.accounts.accountStatusLabel
import com.westly.neribovault.feature.accounts.itemStatusLabel
import com.westly.neribovault.feature.accounts.itemTypeLabel
import com.westly.neribovault.feature.accounts.signInMethodLabel
import com.westly.neribovault.feature.accounts.twoFactorLabel

/** Limits for the typed fields. */
const val MAX_NAME_LENGTH = 60
const val MAX_PLATFORM_LENGTH = 40
const val MAX_FIELD_LABEL_LENGTH = 40
const val MAX_TAG_LENGTH = 24
const val MAX_TAGS = 10
const val MAX_SIGN_IN_OTHER_LENGTH = 40

/** The quiet line that says which fields are plain text. */
const val PLAIN_TEXT_REMINDER =
    "Names, logins, links and notes are stored as plain text on this phone. " +
        "Passwords and secret fields are encrypted."

/** The line at the bottom of the account editor. */
const val NO_BANK_HINT = "Don't store bank PINs or card numbers here."

/** Shown when a save was cancelled because nothing could be encrypted. */
const val ENCRYPT_FAILED_MESSAGE = "Couldn't encrypt that, so nothing was saved."

/** Shown under a link that does not start with http:// or https://. */
const val LINK_ERROR_TEXT = "Enter a link that starts with https://"

/** Result of saving an editor form. */
sealed interface EditorSaveResult {
    object Saved : EditorSaveResult
    class Failed(val message: String) : EditorSaveResult
}

/** Code and label pairs for the chip rows. */
val SIGN_IN_OPTIONS: List<Pair<String, String>> = listOf(
    SIGN_IN_GOOGLE,
    SIGN_IN_EMAIL_PASSWORD,
    SIGN_IN_PHONE,
    SIGN_IN_GITHUB,
    SIGN_IN_APPLE,
    SIGN_IN_OTHER,
).map { it to signInMethodLabel(it) }

val TWO_FACTOR_OPTIONS: List<Pair<String, String>> =
    listOf("none", "authenticator", "sms", "email", "hardware", "other").map { it to twoFactorLabel(it) }

val ACCOUNT_STATUS_OPTIONS: List<Pair<String, String>> =
    listOf(ACCOUNT_STATUS_ACTIVE, ACCOUNT_STATUS_INACTIVE, ACCOUNT_STATUS_CLOSED)
        .map { it to accountStatusLabel(it) }

val ITEM_TYPE_OPTIONS: List<Pair<String, String>> =
    listOf("project", "app", "page", "domain", "database", "bucket", "api", "server", "other")
        .map { it to itemTypeLabel(it) }

val ITEM_STATUS_OPTIONS: List<Pair<String, String>> =
    listOf("active", "paused", "archived").map { it to itemStatusLabel(it) }

/** Label suggestions offered for a new custom field. */
val FIELD_LABEL_SUGGESTIONS: List<String> = listOf(
    "API key",
    "Anon key",
    "Service role key",
    "Database URL",
    "Username",
    "Recovery code",
    "Handle",
    "Region",
)

/** The sign-in chips, with the typed name on the "Other" chip when there is one. */
fun signInOptions(otherName: String?): List<Pair<String, String>> =
    SIGN_IN_OPTIONS.map { (code, label) ->
        if (code == SIGN_IN_OTHER) code to signInMethodLabel(code, otherName) else code to label
    }

/** What the login is called for a sign-in method ("Telegram username" for a named "other"). */
fun loginLabel(signInMethod: String, otherName: String? = null): String = when (signInMethod) {
    SIGN_IN_PHONE -> "Phone number"
    SIGN_IN_OTHER -> {
        val name = otherName?.trim().orEmpty()
        if (name.isEmpty()) "Username" else "$name username"
    }
    else -> "Email"
}

/** The example shown in an empty login field. */
fun loginPlaceholder(signInMethod: String): String = when (signInMethod) {
    SIGN_IN_PHONE -> "0803 000 0000"
    SIGN_IN_OTHER -> "username"
    else -> "you@example.com"
}

/** True when [url] is empty or a usable http or https link. */
fun isValidLink(url: String): Boolean {
    val text = url.trim()
    if (text.isEmpty()) return true
    val lower = text.lowercase()
    return (lower.startsWith("https://") && lower.length > "https://".length) ||
        (lower.startsWith("http://") && lower.length > "http://".length)
}

/** A tag as it is stored: trimmed, lower case, at most [MAX_TAG_LENGTH] characters. */
fun normalizeTag(raw: String): String = raw.trim().lowercase().take(MAX_TAG_LENGTH)

/** The example name for a new item, taken from the account's platform. */
fun itemNamePlaceholder(platform: String): String = when (PlatformPresets.find(platform)?.id) {
    "supabase" -> "ElovanPoint"
    "firebase" -> "Neribo AI"
    "cloudflare" -> "neribovault.com"
    "instagram" -> "@westlywrites"
    else -> "Name"
}

/** The example shown in an empty identifier field. */
fun identifierPlaceholder(itemType: String): String = when (itemType) {
    "project", "app", "api" -> "Project ref"
    "page" -> "Handle"
    "domain" -> "Domain name"
    else -> "Region"
}

/** A row of single-choice chips that wraps onto more lines when it has to. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceChips(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NeriboTheme.spacing.sm),
    ) {
        for ((code, label) in options) {
            NeriboChip(label = label, selected = code == selected, onClick = { onSelect(code) })
        }
    }
}

/** A small, quiet explanatory line under a field. */
@Composable
fun QuietNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = NeriboTheme.spacing.xs),
    )
}
