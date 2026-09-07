package com.harmony.data.library

import com.harmony.core.database.dao.SongEditDao
import com.harmony.core.database.entity.SongEditEntity
import com.harmony.core.media.artwork.ArtworkCache
import com.harmony.domain.library.repository.SongEditRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SongEditRepositoryImpl @Inject constructor(
    private val dao: SongEditDao,
    private val artworkCache: ArtworkCache,
) : SongEditRepository {

    override fun observe(songId: Long): Flow<SongEditRepository.SongEdit?> =
        dao.observe(songId).map { entity ->
            entity?.let {
                SongEditRepository.SongEdit(
                    title = it.title,
                    artist = it.artist,
                    album = it.album,
                    albumArtist = it.albumArtist,
                    artworkUri = it.artworkUri,
                    artworkCleared = it.artworkCleared,
                )
            }
        }

    override suspend fun save(songId: Long, edit: SongEditRepository.SongEdit) {
        dao.upsert(
            SongEditEntity(
                songId = songId,
                // Blank is stored as null, not as an empty string: an empty
                // override would blank the field everywhere rather than
                // falling back to the file's own tag, which is never what
                // clearing a box is meant to mean.
                title = edit.title?.trim()?.takeIf { it.isNotEmpty() },
                artist = edit.artist?.trim()?.takeIf { it.isNotEmpty() },
                album = edit.album?.trim()?.takeIf { it.isNotEmpty() },
                albumArtist = edit.albumArtist?.trim()?.takeIf { it.isNotEmpty() },
                artworkUri = edit.artworkUri,
                artworkCleared = edit.artworkCleared,
            )
        )
    }

    override suspend fun revert(songId: Long) = dao.clear(songId)

    override suspend fun saveArtwork(songId: Long, bytes: ByteArray): String =
        artworkCache.storeUserArt(songId, bytes)
}
