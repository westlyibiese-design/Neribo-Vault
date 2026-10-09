package com.westly.neribovault.feature.accounts.tools

/**
 * The friendly sentence for an audit action. The three PIN actions share one sentence on
 * purpose, so the activity list never reveals that a second PIN exists.
 */
fun activityText(action: String): String = when (action) {
    "password_revealed" -> "A password was shown"
    "password_copied" -> "A password was copied"
    "field_revealed" -> "A secret field was shown"
    "field_copied" -> "A secret field was copied"
    "secret_saved" -> "Secrets were saved"
    "pin_set", "pin_changed", "decoy_pin_set" -> "PIN settings were changed"
    "unlock_failed" -> "A wrong PIN was entered"
    "password_health_checked" -> "Password health was checked"
    else -> "Activity recorded"
}
