package com.harmony.core.model

import java.io.InputStream
import java.security.MessageDigest

/**
 * The library's file identity hash (songs.fileHash): SHA-256 over the first
 * 64 KiB of the file and then its size in decimal. The phone's scanner
 * (FileHasher) and the computer (sending a song to the phone) both use this
 * one recipe, so a song the phone already has is recognised by its content,
 * never by its title or file name.
 */
object PartialHash {
    const val HEAD_BYTES = 64 * 1024

    /** [input] read from its start (at most [HEAD_BYTES] are read); [sizeBytes] the whole file's size. */
    fun of(input: InputStream?, sizeBytes: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        if (input != null) {
            val buffer = ByteArray(HEAD_BYTES)
            var read = 0
            while (read < HEAD_BYTES) {
                val n = input.read(buffer, read, HEAD_BYTES - read)
                if (n <= 0) break
                read += n
            }
            digest.update(buffer, 0, read)
        }
        digest.update(sizeBytes.toString().toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
