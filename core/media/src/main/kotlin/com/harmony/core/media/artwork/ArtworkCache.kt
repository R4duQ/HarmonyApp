package com.harmony.core.media.artwork

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists embedded artwork extracted during scanning to the app's private
 * files dir, so Coil can load it by plain file URI without re-opening (and
 * re-parsing) the audio file every time a list row binds.
 *
 * Content-addressed by song id; re-storing overwrites, orphans are pruned by
 * a maintenance pass in the analysis worker (Phase 5) after deletions.
 */
@Singleton
class ArtworkCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val dir: File by lazy {
        File(context.filesDir, "artwork").apply { mkdirs() }
    }

    /** @return a file:// URI string for the stored image. */
    fun store(songId: Long, bytes: ByteArray): String {
        val file = File(dir, "$songId.img")
        file.writeBytes(bytes)
        return file.toURI().toString()
    }

    /**
     * The stored image as bytes, scaled down for transport.
     *
     * Used for the media item that is actually playing: MediaMetadata can
     * carry artwork as raw bytes, and bytes cross the process boundary
     * without any URI permission at all — unlike a content:// URI, which
     * the Auto host has to be willing and able to resolve.
     *
     * Only ever for a single item. A browse list of these would exceed the
     * ~1 MB binder transaction limit and throw.
     */
    fun bytes(songId: Long, maxSize: Int = 512, userArtwork: Boolean = false): ByteArray? {
        val file = File(dir, if (userArtwork) "$songId-user.img" else "$songId.img")
        if (!file.exists()) return null
        return try {
            val bounds = android.graphics.BitmapFactory.Options()
                .apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSize.coerceAtLeast(1)) sample *= 2
            val bmp = android.graphics.BitmapFactory.decodeFile(
                file.absolutePath,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return null
            java.io.ByteArrayOutputStream().use { out ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                bmp.recycle()
                out.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Stores artwork the USER supplied, under a separate key from the
     * scanned copy.
     *
     * Separate because a rescan overwrites `{songId}.img` with whatever the
     * file contains — a user's chosen cover written there would survive
     * only until the next scan. The `-user` suffix is never touched by
     * scanning.
     */
    fun storeUserArt(songId: Long, bytes: ByteArray): String {
        val file = File(dir, "$songId-user.img")
        file.writeBytes(bytes)
        return file.toURI().toString()
    }

    fun delete(songId: Long) {
        File(dir, "$songId.img").delete()
    }
}
