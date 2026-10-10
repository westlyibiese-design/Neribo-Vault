package com.westly.neribovault.feature.authenticator.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM encryption of secret keys under an Android Keystore key that never leaves the phone
 * (StrongBox is tried first, the normal Keystore is the fallback). Every encryption uses a fresh
 * random 12-byte IV. The key does not need biometrics: the app lock and the optional vault lock
 * guard access. Decrypting never creates a key, so a missing key shows up as null, not as a new key.
 */
object AuthenticatorCrypto {
    const val KEY_ALIAS = "neribo_authenticator_key"

    private const val PROVIDER = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    /** Base64 (no wrap) ciphertext and IV, exactly as they are stored in the database. */
    class Sealed(val ciphertext: String, val iv: String)

    private val lock = Any()

    /** Whether the Keystore key exists or can be created. */
    fun isAvailable(): Boolean = try {
        synchronized(lock) { loadKey() != null || createKey() != null }
    } catch (e: Exception) {
        false
    }

    /** Encrypts [secret]. Returns null on any failure. Creates the key on first use. */
    fun encrypt(secret: ByteArray): Sealed? = try {
        synchronized(lock) {
            val key = loadKey() ?: createKey()
            if (key == null) {
                null
            } else {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, key)
                val iv = cipher.iv
                val encrypted = cipher.doFinal(secret)
                Sealed(
                    ciphertext = Base64.encodeToString(encrypted, Base64.NO_WRAP),
                    iv = Base64.encodeToString(iv, Base64.NO_WRAP),
                )
            }
        }
    } catch (e: Exception) {
        null
    }

    /** Decrypts a stored secret. Null when the key is missing, the data was changed, or on any failure. */
    fun decrypt(ciphertext: String, iv: String): ByteArray? = try {
        val key = synchronized(lock) { loadKey() }
        if (key == null) {
            null
        } else {
            val ivBytes = Base64.decode(iv, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, ivBytes))
            cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
        }
    } catch (e: Exception) {
        null
    }

    private fun loadKey(): SecretKey? {
        val store = KeyStore.getInstance(PROVIDER)
        store.load(null)
        return store.getKey(KEY_ALIAS, null) as? SecretKey
    }

    private fun createKey(): SecretKey? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generate(strongBox = true)
            } catch (e: Exception) {
                // No StrongBox on this phone: fall back to the normal Keystore below.
            }
        }
        return try {
            generate(strongBox = false)
        } catch (e: Exception) {
            null
        }
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(builder.build())
        return generator.generateKey()
    }
}
