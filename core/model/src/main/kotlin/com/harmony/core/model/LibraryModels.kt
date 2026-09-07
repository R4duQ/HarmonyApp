package com.harmony.core.model

data class Album(
    val id: Long,
    val name: String,
    val albumArtist: String?,
    val year: Int?,
    val artworkUri: String?,
    val songCount: Int,
)

data class Artist(
    val id: Long,
    val name: String,
    val albumCount: Int,
    val songCount: Int,
)

data class Playlist(
    val id: Long,
    val name: String,
    val songCount: Int,
    val createdAt: Long,
)

/**
 * Smart playlists are queries, not stored song lists. HIGHEST/LOWEST_ENERGY
 * join the analysis table and return empty until Phase 5 populates it —
 * which is fine: the UI shows them as empty rather than crashing or hiding.
 */
enum class SmartPlaylistType {
    FAVORITES, MOST_PLAYED, RECENTLY_ADDED, RECENTLY_PLAYED, HIGHEST_ENERGY, LOWEST_ENERGY
}
