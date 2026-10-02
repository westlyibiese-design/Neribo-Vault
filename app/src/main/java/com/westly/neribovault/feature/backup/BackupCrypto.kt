package com.westly.neribovault.feature.backup

import com.westly.neribovault.core.lock.PinHasher
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The backup file cannot be read as a Neribo Vault backup at all (wrong magic or too short). */
class NotABackupException : IOException("Not a Neribo Vault backup")

/**
 * Encryption of the backup file (format version 1).
 *
 * Layout: ASCII magic `NVBK1`, 16-byte random salt, 12-byte random IV, then the AES-256-GCM
 * ciphertext (which ends with the 16-byte authentication tag). The key comes from
 * PBKDF2WithHmacSHA256(password, salt, 210,000 iterations, 256 bits).
 */
object BackupCrypto {
    private val MAGIC = "NVBK1".toByteArray(Charsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** PBKDF2 iterations for the backup key. */
    const val ITERATIONS = 210_000

    /**
     * Writes the header to [target] and returns a stream that encrypts everything written to it.
     * The returned stream **must be closed**: closing writes the authentication tag.
     */
    fun encryptTo(target: OutputStream, password: String): OutputStream {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        target.write(MAGIC)
        target.write(salt)
        target.write(iv)
        return CipherOutputStream(target, cipher)
    }

    /**
     * Reads the header from [source] and returns a stream of the decrypted bytes. Throws
     * [NotABackupException] when the header is not a backup header. A wrong password or damaged
     * data shows up later, as an [IOException] while the returned stream is read to its end.
     */
    fun decryptFrom(source: InputStream, password: String): InputStream {
        val magic = readExactly(source, MAGIC.size)
        if (!magic.contentEquals(MAGIC)) throw NotABackupException()
        val salt = readExactly(source, SALT_BYTES)
        val iv = readExactly(source, IV_BYTES)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return CipherInputStream(source, cipher)
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec =
        SecretKeySpec(PinHasher.hash(password, salt, ITERATIONS), "AES")

    private fun readExactly(source: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var done = 0
        while (done < count) {
            val read = source.read(buffer, done, count - done)
            if (read < 0) throw NotABackupException()
            done += read
        }
        return buffer
    }
}
