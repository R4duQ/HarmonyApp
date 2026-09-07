package com.harmony.data.library

import com.harmony.core.database.dao.SongDao
import com.harmony.data.library.mapper.artistEntities
import com.harmony.data.library.mapper.toAlbumEntity
import com.harmony.data.library.mapper.toEntity
import com.harmony.domain.library.gateway.LibraryWriteGateway
import com.harmony.domain.library.model.ScanKey
import com.harmony.domain.library.model.ScannedTrack
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence half of the scan pipeline. Each upsert batch is one transaction
 * (albums -> artists -> songs, FK-safe order); pruning of newly-empty albums
 * and artists happens on delete so browse screens never show ghosts.
 */
@Singleton
class LibraryWriteGatewayImpl @Inject constructor(
    private val songDao: SongDao,
) : LibraryWriteGateway {

    override suspend fun currentScanKeys(): List<ScanKey> =
        songDao.scanKeys().map { ScanKey(it.uri, it.fileSizeBytes, it.lastModified) }

    override suspend fun upsert(tracks: List<ScannedTrack>) {
        if (tracks.isEmpty()) return
        songDao.upsertBatch(
            songs = tracks.map { it.toEntity() },
            albums = tracks.map { it.toAlbumEntity() }.distinctBy { it.id },
            artists = tracks.flatMap { it.artistEntities() }.distinctBy { it.id },
        )
    }

    override suspend fun removeByUris(uris: List<String>) {
        if (uris.isEmpty()) return
        songDao.deleteFilesAndPrune(uris)
    }
}
