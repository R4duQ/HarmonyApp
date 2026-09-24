package com.harmony.data.library

import com.harmony.core.database.dao.AnalysisDao
import com.harmony.core.database.dao.HistoryDao
import com.harmony.core.database.dao.PlaylistDao
import com.harmony.core.database.dao.SongDao
import com.harmony.core.database.entity.PlaylistEntity
import com.harmony.core.model.Playlist
import com.harmony.core.model.SmartPlaylistType
import com.harmony.core.model.Song
import com.harmony.data.library.m3u.M3uCodec
import com.harmony.data.library.mapper.toDomain
import com.harmony.domain.library.repository.PlaylistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistRepositoryImpl @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val songDao: SongDao,
    private val historyDao: HistoryDao,
    private val analysisDao: AnalysisDao,
) : PlaylistRepository {

    override fun observePlaylists(): Flow<List<Playlist>> =
        playlistDao.observePlaylists().map { rows -> rows.map { it.toDomain() } }

    override fun observePlaylistCount(): Flow<Int> = playlistDao.observePlaylistCount()

    override fun observePlaylistArtwork(): Flow<Map<Long, List<String>>> =
        playlistDao.observePlaylistArtwork().map { rows ->
            // The query already orders by playlist then first position, so
            // grouping preserves that order and the take(4) picks the covers
            // heard earliest rather than four arbitrary ones.
            rows.groupBy { it.playlistId }
                .mapValues { (_, entries) -> entries.take(MOSAIC_TILES).map { it.artworkUri } }
        }

    override fun observePlaylistDurations(): Flow<Map<Long, Long>> =
        playlistDao.observePlaylistDurations().map { rows ->
            rows.associate { it.playlistId to it.totalDurationMs }
        }

    override fun observePlaylistSongs(playlistId: Long): Flow<List<Song>> =
        playlistDao.observeSongs(playlistId).map { rows -> rows.map { it.toDomain() } }

    /**
     * Smart playlists are live queries, so they stay correct with zero
     * bookkeeping: play a song and Most Played reorders itself.
     */
    override fun observeSmartPlaylist(type: SmartPlaylistType, limit: Int): Flow<List<Song>> =
        when (type) {
            SmartPlaylistType.FAVORITES -> historyDao.observeFavorites()
            SmartPlaylistType.MOST_PLAYED -> historyDao.observeMostPlayed(limit)
            SmartPlaylistType.RECENTLY_PLAYED -> historyDao.observeRecentlyPlayed(limit)
            SmartPlaylistType.RECENTLY_ADDED -> songDao.observeRecentlyAdded(limit)
            SmartPlaylistType.HIGHEST_ENERGY -> analysisDao.observeHighestEnergy(limit)
            SmartPlaylistType.LOWEST_ENERGY -> analysisDao.observeLowestEnergy(limit)
        }.map { rows -> rows.map { it.toDomain() } }

    override suspend fun create(name: String): Long =
        playlistDao.insert(PlaylistEntity(name = name, createdAt = System.currentTimeMillis()))

    override suspend fun userPlaylistSongIds(): Set<Long> = playlistDao.userPlaylistSongIds().toSet()

    override suspend fun saveDiscoveryBatch(batchId: String, name: String, songIds: List<Long>): Long {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(("harmony-discover:" + batchId).toByteArray(Charsets.UTF_8))
        val id = java.nio.ByteBuffer.wrap(digest).long or Long.MIN_VALUE
        return playlistDao.insertDiscoveryOnce(id, name, songIds)
    }

    override suspend fun rename(playlistId: Long, name: String) = playlistDao.rename(playlistId, name)

    override suspend fun delete(playlistId: Long) = playlistDao.delete(playlistId)

    override suspend fun addSongs(playlistId: Long, songIds: List<Long>) =
        playlistDao.appendSongs(playlistId, songIds)

    override suspend fun replaceSongs(playlistId: Long, songIds: List<Long>) =
        playlistDao.replaceSongOrder(playlistId, songIds)

    override suspend fun removeSong(playlistId: Long, songId: Long) =
        playlistDao.removeSong(playlistId, songId)

    override suspend fun moveSong(playlistId: Long, fromPosition: Int, toPosition: Int) =
        playlistDao.move(playlistId, fromPosition, toPosition)

    override suspend fun exportM3u(playlistId: Long): String =
        M3uCodec.export(observePlaylistSongs(playlistId).first())

    override suspend fun importM3u(name: String, m3uContent: String): Long {
        val library = songDao.observeAll().first().map { it.toDomain() }
        val matchedIds = M3uCodec.matchImport(m3uContent, library)
        val playlistId = create(name)
        if (matchedIds.isNotEmpty()) addSongs(playlistId, matchedIds)
        return playlistId
    }

    private companion object {
        /** A 2x2 mosaic, so four covers is all the UI can show. */
        const val MOSAIC_TILES = 4
    }
}
