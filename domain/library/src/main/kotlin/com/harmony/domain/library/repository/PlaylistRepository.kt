package com.harmony.domain.library.repository

import com.harmony.core.model.Playlist
import com.harmony.core.model.SmartPlaylistType
import com.harmony.core.model.Song
import kotlinx.coroutines.flow.Flow

interface PlaylistRepository {
    fun observePlaylists(): Flow<List<Playlist>>

    /** Live count for the Home dashboard; see LibraryRepository.observeSongCount. */
    fun observePlaylistCount(): Flow<Int>

    /**
     * Playlist id -> up to four distinct cover URIs, in playing order, for the
     * mosaic tiles on the Playlists screen.
     */
    fun observePlaylistArtwork(): Flow<Map<Long, List<String>>>

    /** Playlist id -> total runtime in milliseconds. */
    fun observePlaylistDurations(): Flow<Map<Long, Long>>
    fun observePlaylistSongs(playlistId: Long): Flow<List<Song>>
    fun observeSmartPlaylist(type: SmartPlaylistType, limit: Int = 200): Flow<List<Song>>

    suspend fun create(name: String): Long
    suspend fun rename(playlistId: Long, name: String)
    suspend fun delete(playlistId: Long)
    suspend fun addSongs(playlistId: Long, songIds: List<Long>)
    /** Replaces every entry while preserving the supplied order. */
    suspend fun replaceSongs(playlistId: Long, songIds: List<Long>)
    suspend fun removeSong(playlistId: Long, songId: Long)
    suspend fun moveSong(playlistId: Long, fromPosition: Int, toPosition: Int)

    /**
     * M3U/M3U8 text exchange. Import matches entries against the library by
     * file path/URI suffix (playlists written by other apps use absolute
     * paths that won't equal our content URIs; suffix matching on the
     * path's tail is the pragmatic compromise every player ends up using).
     */
    suspend fun exportM3u(playlistId: Long): String
    suspend fun importM3u(name: String, m3uContent: String): Long
}
