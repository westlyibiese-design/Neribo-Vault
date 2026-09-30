package com.westly.neribovault.core.lock

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Finds the hosting [Activity] behind a Compose context, or null. */
internal fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** Thin wrapper around the system biometric prompt (strong biometrics only). */
object BiometricAuth {
    /** True when the device has an enrolled fingerprint or face that counts as strong. */
    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Shows the system prompt. [onSuccess] runs after a good scan. [onCancel] runs when the person
     * taps "Use PIN", dismisses the prompt, or the sensor gives up. A single bad scan is not an
     * error; the system lets them retry.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String?,
        onSuccess: () -> Unit,
        onCancel: () -> Unit,
    ) {
        try {
            val executor = ContextCompat.getMainExecutor(activity)
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onCancel()
                }
            }
            val prompt = BiometricPrompt(activity, executor, callback)
            val builder = BiometricPrompt.PromptInfo.Builder().setTitle(title)
            if (subtitle != null) builder.setSubtitle(subtitle)
            val info = builder
                .setNegativeButtonText("Use PIN")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()
            prompt.authenticate(info)
        } catch (e: IllegalStateException) {
            onCancel()
        }
    }
}
