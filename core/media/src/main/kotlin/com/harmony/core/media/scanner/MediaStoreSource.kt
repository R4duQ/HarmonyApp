package com.harmony.core.media.scanner

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.harmony.core.media.model.MediaCandidate
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enumerates audio files that Android's MediaStore already indexes: internal
 * storage and mounted SD cards. This is the fast path — a single indexed
 * query per volume, no file I/O.
 *
 * What MediaStore does NOT reliably cover is USB OTG storage (many OEMs never
 * index it); that gap is handled by [SafTreeSource].
 *
 * We deliberately query IS_MUSIC-agnostically and filter by MIME/extension
 * ourselves: MediaStore's IS_MUSIC heuristic wrongly excludes some tagged
 * files (notably certain Opus/OGG files) on several OEM builds.
 */
@Singleton
class MediaStoreSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val resolver: ContentResolver get() = context.contentResolver

    fun mountedAudioPrefixes(): Set<String> = MediaStore.getExternalVolumeNames(context)
        .map { "${MediaStore.Audio.Media.getContentUri(it)}/" }.toSet()

    fun queryAll(): List<MediaCandidate> {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.VOLUME_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
        )
        val candidates = ArrayList<MediaCandidate>(2048)
        checkNotNull(resolver.query(collection, projection, "${MediaStore.Audio.Media.IS_PENDING} = 0", null, null)) {
            "Android could not read the audio library. No library entries were removed."
        }.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val modCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val volCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.VOLUME_NAME)
            val pathCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: continue
                val mime = cursor.getString(mimeCol)
                if (!SupportedFormats.isSupported(name, mime)) continue
                val volumeName = cursor.getString(volCol) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                val rowCollection = MediaStore.Audio.Media.getContentUri(volumeName) ?: collection
                val uri = ContentUris.withAppendedId(rowCollection, cursor.getLong(idCol))
                candidates += MediaCandidate(
                    uri = uri.toString(),
                    displayName = name,
                    sizeBytes = cursor.getLong(sizeCol),
                    lastModified = cursor.getLong(modCol) * 1000L,
                    storageVolume = if (volumeName == MediaStore.VOLUME_EXTERNAL_PRIMARY) {
                        "internal"
                    } else {
                        "sdcard:$volumeName"
                    },
                    // Volume-qualified: the same relative path on internal
                    // storage and on an SD card are different directories.
                    folder = if (pathCol >= 0) {
                        "$volumeName/" + (cursor.getString(pathCol) ?: "").trim('/')
                    } else {
                        ""
                    },
                    physicalPath = ScanIdentity.mediaPath(volumeName, if (pathCol >= 0) cursor.getString(pathCol) else null, name),
                )
            }
        }
        return candidates
    }
}

/** Single place that defines which files Harmony considers audio it can play. */
object SupportedFormats {
    private val extensions = setOf(
        "flac", "mp3", "wav", "m4a", "aac", "ogg", "oga", "opus", "alac",
        // Optional formats (playable only if the FFmpeg extension is bundled):
        "aiff", "aif", "dsf", "dff",
    )
    private val mimePrefixes = setOf("audio/")

    fun isSupported(displayName: String, mimeType: String?): Boolean {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        if (ext in extensions) return true
        return mimeType != null && mimePrefixes.any(mimeType::startsWith)
    }
}
