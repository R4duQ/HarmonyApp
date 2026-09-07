package com.harmony.domain.library.repository

import kotlinx.coroutines.flow.Flow

/**
 * Manual corrections to a song's tags, applied wherever Harmony shows it —
 * the library, the player, the notification and Android Auto — without
 * touching the audio file itself.
 */
interface SongEditRepository {

    data class SongEdit(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        val artworkUri: String? = null,
        val artworkCleared: Boolean = false,
    )

    fun observe(songId: Long): Flow<SongEdit?>

    suspend fun save(songId: Long, edit: SongEdit)

    /** Removes all overrides, restoring the file's own tags. */
    suspend fun revert(songId: Long)

    /**
     * Stores [bytes] as this song's artwork and returns the URI to save.
     * Kept in the repository so the caller doesn't need to know where
     * artwork lives on disk.
     */
    suspend fun saveArtwork(songId: Long, bytes: ByteArray): String
}
