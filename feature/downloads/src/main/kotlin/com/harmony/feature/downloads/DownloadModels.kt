package com.harmony.feature.downloads

import android.net.Uri

data class IdentifiedTrack(
    val artist: String,
    val title: String,
    val sourceTitle: String,
    val thumbnailUrl: String? = null,
    val album: String = "",
    val durationMs: Long = 0L,
    val isrc: String? = null,
    /** Native Spotify id when the track came from Playlist Transfer. */
    val spotifyId: String? = null,
    /** Deezer metadata id used by the existing manual SpotiFLAC search. */
    val metadataId: String? = null,
) {
    val displayName: String
        get() = listOf(artist, title)
            .filter { it.isNotBlank() }
            .joinToString(" - ")
            .ifBlank { sourceTitle.ifBlank { "Harmony download" } }
}


data class SpotiFlacSearchCandidate(
    val metadataId: String,
    val artist: String,
    val title: String,
    val album: String,
    val durationMs: Long,
    val thumbnailUrl: String?,
    val matchScore: Int,
    val rank: Long = 0L,
) {
    val displayName: String
        get() = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" - ")
}

data class ConvertedFlac(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
)

data class ValidatedFlac(
    val finalUrl: String,
    val suggestedFileName: String?,
    val contentType: String?,
)

enum class HarmonyDownloadStatus {
    PENDING,
    RUNNING,
    PAUSED,
    SUCCESSFUL,
    FAILED,
}

data class HarmonyDownloadProgress(
    val id: Long,
    val status: HarmonyDownloadStatus,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val reason: Int = 0,
    val localUri: Uri? = null,
) {
    val fraction: Float?
        get() = if (totalBytes > 0L) {
            (downloadedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            null
        }
}

class HarmonyDownloadException(
    val userMessage: String,
    val technicalDetails: String? = null,
    cause: Throwable? = null,
) : Exception(userMessage, cause)
