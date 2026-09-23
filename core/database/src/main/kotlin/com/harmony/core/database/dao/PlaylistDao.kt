package com.harmony.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.harmony.core.database.entity.PlaylistEntity
import com.harmony.core.database.entity.PlaylistSongCrossRef
import com.harmony.core.database.entity.SongEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    data class PlaylistRow(val id: Long, val name: String, val createdAt: Long, val songCount: Int)

    /** One row per (playlist, distinct artwork), earliest position first. */
    data class PlaylistArtworkRow(val playlistId: Long, val artworkUri: String, val firstPosition: Int)

    data class PlaylistDurationRow(val playlistId: Long, val totalDurationMs: Long)

    @Query(
        """
        SELECT p.id, p.name, p.createdAt, COUNT(ps.songId) AS songCount
        FROM playlists p LEFT JOIN playlist_songs ps ON ps.playlistId = p.id
        GROUP BY p.id ORDER BY p.name COLLATE NOCASE
        """
    )
    fun observePlaylists(): Flow<List<PlaylistRow>>

    @Query(
        """
        SELECT s.* FROM songs_effective s
        JOIN playlist_songs ps ON ps.songId = s.id
        WHERE ps.playlistId = :playlistId
        ORDER BY ps.position
        """
    )
    fun observeSongs(playlistId: Long): Flow<List<SongEntity>>

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query("SELECT COUNT(*) FROM playlists WHERE id = :id")
    suspend fun exists(id: Long): Int

    @Query("SELECT DISTINCT songId FROM playlist_songs WHERE playlistId > 0")
    suspend fun userPlaylistSongIds(): List<Long>

    /** Negative, deterministic import IDs never collide with SQLite's positive generated IDs. */
    @Transaction
    suspend fun insertDiscoveryOnce(id: Long, name: String, songIds: List<Long>): Long {
        require(id < 0 && songIds.isNotEmpty() && songIds.distinct().size == songIds.size)
        if (exists(id) == 0) {
            insert(PlaylistEntity(id = id, name = name, createdAt = System.currentTimeMillis()))
            replaceSongOrder(id, songIds)
        }
        return id
    }

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun nextPosition(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRefs(refs: List<PlaylistSongCrossRef>)

    /** Serializes the position read with insertion, including concurrent imports. */
    @Transaction
    suspend fun appendSongs(playlistId: Long, songIds: List<Long>) {
        val existing = refsFor(playlistId).map { it.songId }.toSet()
        val start = nextPosition(playlistId)
        val refs = songIds.distinct().filterNot { it in existing }.mapIndexed { index, id ->
            PlaylistSongCrossRef(playlistId, id, start + index)
        }
        if (refs.isNotEmpty()) insertCrossRefs(refs)
    }

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun clearSongs(playlistId: Long)

    /** Replaces the complete order atomically; used when imported tracks arrive over time. */
    @Transaction
    suspend fun replaceSongOrder(playlistId: Long, songIds: List<Long>) {
        clearSongs(playlistId)
        val refs = songIds.distinct().mapIndexed { index, songId ->
            PlaylistSongCrossRef(playlistId = playlistId, songId = songId, position = index)
        }
        if (refs.isNotEmpty()) insertCrossRefs(refs)
    }

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSong(playlistId: Long, songId: Long)

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId ORDER BY position")
    suspend fun refsFor(playlistId: Long): List<PlaylistSongCrossRef>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceRefs(refs: List<PlaylistSongCrossRef>)

    /** Reorder = rewrite positions in one transaction; simple and correct for playlist-sized N. */
    @Transaction
    suspend fun move(playlistId: Long, from: Int, to: Int) {
        val refs = refsFor(playlistId).toMutableList()
        if (from !in refs.indices || to !in refs.indices) return
        val item = refs.removeAt(from)
        refs.add(to, item)
        replaceRefs(refs.mapIndexed { index, ref -> ref.copy(position = index) })
    }

    /** See SongDao.observeSongCount. */
    @Query("SELECT COUNT(*) FROM playlists")
    fun observePlaylistCount(): Flow<Int>

    /**
     * Cover art candidates for the playlist mosaics.
     *
     * GROUP BY (playlist, artwork) rather than returning every song's artwork:
     * most tracks in a playlist share an album cover, so the distinct set is a
     * handful of rows even for a 5,000-song playlist, while the naive query
     * would stream one row per song on every playlist change. MIN(position)
     * keeps the ordering meaningful so the mosaic shows the covers you would
     * hear first rather than an arbitrary four.
     *
     * Reads songs_effective, so a cover replaced in the tag editor is the one
     * that shows up here.
     */
    @Query(
        """
        SELECT ps.playlistId AS playlistId, s.artworkUri AS artworkUri,
               MIN(ps.position) AS firstPosition
        FROM playlist_songs ps
        JOIN songs_effective s ON s.id = ps.songId
        WHERE s.artworkUri IS NOT NULL AND s.artworkUri != ''
        GROUP BY ps.playlistId, s.artworkUri
        ORDER BY ps.playlistId, firstPosition
        """
    )
    fun observePlaylistArtwork(): Flow<List<PlaylistArtworkRow>>

    @Query(
        """
        SELECT ps.playlistId AS playlistId,
               COALESCE(SUM(s.durationMs), 0) AS totalDurationMs
        FROM playlist_songs ps
        JOIN songs_effective s ON s.id = ps.songId
        GROUP BY ps.playlistId
        """
    )
    fun observePlaylistDurations(): Flow<List<PlaylistDurationRow>>
}
