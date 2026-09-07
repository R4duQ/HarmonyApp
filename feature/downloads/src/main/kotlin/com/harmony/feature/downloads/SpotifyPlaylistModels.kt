package com.harmony.feature.downloads

/** Lightweight Spotify playlist metadata. Audio is never fetched from Spotify. */
data class SpotifyPlaylistSummary(
    val id: String,
    val name: String,
    val description: String,
    val ownerName: String,
    val totalTracks: Int,
    val imageUrl: String?,
    val spotifyUrl: String?,
    val snapshotId: String?,
    /** Development Mode only exposes items for owned/collaborative playlists. */
    val canImportItems: Boolean,
)

data class SpotifyPlaylistTrack(
    val spotifyId: String?,
    val title: String,
    val artists: String,
    val album: String,
    val durationMs: Long,
    val isrc: String?,
    val artworkUrl: String?,
    val spotifyUrl: String?,
    val position: Int,
) {
    val stableKey: String
        get() = spotifyId?.let { "spotify:$it:$position" }
            ?: "position:$position:${artists.lowercase()}:${title.lowercase()}"

    val displayName: String
        get() = listOf(artists, title).filter(String::isNotBlank).joinToString(" - ")
}

sealed interface SpotifyAuthorizationEvent {
    data class Connected(val message: String) : SpotifyAuthorizationEvent
    data class Failed(val message: String) : SpotifyAuthorizationEvent
}

class SpotifyApiException(
    message: String,
    val httpCode: Int? = null,
) : Exception(message)

enum class PlaylistMatchConfidence(val label: String) {
    EXACT("Exact library match"),
    LIKELY("Likely library match"),
}

enum class PlaylistTransferTrackStatus {
    IN_LIBRARY,
    READY,
    SEARCHING,
    DOWNLOADING,
    DOWNLOADED,
    WAITING_FOR_VERIFICATION,
    FAILED,
}

data class PlaylistTransferTrack(
    val remote: SpotifyPlaylistTrack,
    val localSongId: Long? = null,
    val confidence: PlaylistMatchConfidence? = null,
    val status: PlaylistTransferTrackStatus = PlaylistTransferTrackStatus.READY,
    val selected: Boolean = true,
    val detail: String? = null,
)
