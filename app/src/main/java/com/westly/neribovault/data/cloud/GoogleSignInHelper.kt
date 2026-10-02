package com.westly.neribovault.data.cloud

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException

/** The result of asking Google for an ID token. Never log any of these values. */
sealed interface GoogleTokenResult {
    /** [rawNonce] goes to Supabase; Google received only its SHA-256 hash. */
    data class Token(val idToken: String, val rawNonce: String, val email: String?) : GoogleTokenResult

    /** The person closed the Google sheet. Not an error. */
    object Cancelled : GoogleTokenResult

    data class Failed(val message: String) : GoogleTokenResult
}

private const val NONCE_BYTES = 32
private const val NO_ACCOUNT_MESSAGE =
    "No Google account was found on this phone. Add one in your phone's Settings, then try again."
private const val COULD_NOT_START_MESSAGE = "Google sign-in couldn't start. Try again in a moment."

/**
 * Shows Google's account sheet over [activity] and returns a Google ID token.
 *
 * [activity] is only used during this call and is never stored. Call it from the main thread:
 * Credential Manager needs it to show the sheet.
 */
suspend fun requestGoogleIdToken(activity: Activity, webClientId: String): GoogleTokenResult {
    val rawNonce = newRawNonce()
    val option = GetSignInWithGoogleOption.Builder(webClientId)
        .setNonce(sha256Hex(rawNonce))
        .build()
    val request = GetCredentialRequest.Builder()
        .addCredentialOption(option)
        .build()
    return try {
        val result = CredentialManager.create(activity).getCredential(activity, request)
        val credential = result.credential
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            val google = GoogleIdTokenCredential.createFrom(credential.data)
            GoogleTokenResult.Token(google.idToken, rawNonce, google.id)
        } else {
            GoogleTokenResult.Failed(COULD_NOT_START_MESSAGE)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: GetCredentialCancellationException) {
        GoogleTokenResult.Cancelled
    } catch (e: NoCredentialException) {
        GoogleTokenResult.Failed(NO_ACCOUNT_MESSAGE)
    } catch (e: GetCredentialException) {
        GoogleTokenResult.Failed(COULD_NOT_START_MESSAGE)
    } catch (e: GoogleIdTokenParsingException) {
        GoogleTokenResult.Failed(COULD_NOT_START_MESSAGE)
    }
}

private fun newRawNonce(): String {
    val bytes = ByteArray(NONCE_BYTES)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

private fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
