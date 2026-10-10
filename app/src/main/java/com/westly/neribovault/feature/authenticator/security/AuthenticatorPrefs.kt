package com.westly.neribovault.feature.authenticator.security

import android.content.Context

/** Plain preferences of the Authenticator vault (file `neribo_authenticator_prefs`). Nothing secret is kept here. */
object AuthenticatorPrefs {
    private const val FILE_NAME = "neribo_authenticator_prefs"
    private const val KEY_LAST_EXPORT = "last_export_at"
    private const val KEY_REMINDER_UNTIL = "reminder_dismissed_until"
    private const val KEY_HIDE_CODES = "hide_codes"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** When the last encrypted export was made, or 0 when never. */
    fun lastExportAt(context: Context): Long = prefs(context).getLong(KEY_LAST_EXPORT, 0L)

    fun markExported(context: Context, atMillis: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_LAST_EXPORT, atMillis).apply()
    }

    /** The backup reminder stays hidden until this time. */
    fun reminderDismissedUntil(context: Context): Long = prefs(context).getLong(KEY_REMINDER_UNTIL, 0L)

    fun dismissReminder(context: Context, untilMillis: Long) {
        prefs(context).edit().putLong(KEY_REMINDER_UNTIL, untilMillis).apply()
    }

    /** "Hide codes until tapped"; off by default. */
    fun hideCodes(context: Context): Boolean = prefs(context).getBoolean(KEY_HIDE_CODES, false)

    fun setHideCodes(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_HIDE_CODES, value).apply()
    }
}
