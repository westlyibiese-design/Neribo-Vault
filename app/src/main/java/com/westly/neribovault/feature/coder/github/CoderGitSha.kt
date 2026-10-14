package com.westly.neribovault.feature.coder.github

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Git's own hash of a file's content: SHA-1 of "blob <size>", a zero byte, then the raw bytes. Two files
 * with the same hash here are the same file on GitHub, so this is how a push finds out what changed
 * without downloading anything. Always hashes raw bytes, so images and other binary files are exact.
 */
object CoderGitSha {
    private const val HEX = "0123456789abcdef"
    private const val BUFFER_BYTES = 8192

    /** The blob hash of [file], read as a stream. Throws [IOException] when the file cannot be read or changes while it is read. */
    fun blobSha(file: File): String {
        val size = file.length()
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(header(size))
        var total = 0L
        FileInputStream(file).use { stream ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                total += count
            }
        }
        if (total != size) throw IOException("The file changed while it was read.")
        return toHex(digest.digest())
    }

    /** The blob hash of [bytes]. */
    fun blobSha(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(header(bytes.size.toLong()))
        digest.update(bytes)
        return toHex(digest.digest())
    }

    private fun header(size: Long): ByteArray = ("blob " + size + "\u0000").toByteArray(Charsets.UTF_8)

    private fun toHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val value = b.toInt() and 0xFF
            out.append(HEX[value shr 4])
            out.append(HEX[value and 0x0F])
        }
        return out.toString()
    }
}
