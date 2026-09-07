package com.harmony.core.media.scanner

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Partial content hash used for change detection and duplicate detection.
 *
 * Recipe: SHA-256 over (first 64 KiB) + (declared size as 8 bytes). We do NOT
 * read the tail: SAF streams for USB documents are not required to be
 * seekable, and skipping to EOF on a 200 MB FLAC over a slow OTG reader costs
 * more than the collision risk it removes. Size + head + lastModified check in
 * the scan key already screens the realistic cases (retag, replace, truncate).
 * Full-file hashing 20k lossless files would be a battery disaster; this is a
 * deliberate accuracy/performance tradeoff, revisit only with field evidence.
 */
@Singleton
class FileHasher @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun hash(uri: String, sizeBytes: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
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

    private companion object {
        const val HEAD_BYTES = 64 * 1024
    }
}
