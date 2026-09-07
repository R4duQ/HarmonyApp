package com.harmony.data.library.mapper

import com.harmony.core.database.dao.CollectionDao
import com.harmony.core.database.dao.PlaylistDao
import com.harmony.core.database.entity.AlbumEntity
import com.harmony.core.database.entity.ArtistEntity
import com.harmony.core.database.entity.SongEntity
import com.harmony.core.media.metadata.MetadataExtractor
import com.harmony.core.model.Album
import com.harmony.core.model.Artist
import com.harmony.core.model.Playlist
import com.harmony.core.model.Song
import com.harmony.domain.library.model.ScannedTrack

/**
 * The only place entity<->domain translation happens. Mappers are dumb on
 * purpose: no logic beyond field shuffling, so nothing here needs testing
 * beyond compile-time exhaustiveness.
 */

fun SongEntity.toDomain(): Song = Song(
    id = id,
    uri = uri,
    title = title,
    artist = artist,
    album = album,
    albumId = albumId,
    albumArtist = albumArtist,
    composer = composer,
    year = year,
    genre = genre,
    discNumber = discNumber,
    trackNumber = trackNumber,
    durationMs = durationMs,
    bitrateKbps = bitrateKbps,
    sampleRateHz = sampleRateHz,
    bitDepth = bitDepth,
    channels = channels,
    artworkUri = artworkUri,
    embeddedLyrics = embeddedLyrics,
    replayGainTrackDb = replayGainTrackDb,
    replayGainAlbumDb = replayGainAlbumDb,
)

fun ScannedTrack.toEntity(): SongEntity = SongEntity(
    id = song.id,
    uri = song.uri,
    title = song.title,
    artist = song.artist,
    album = song.album,
    albumId = song.albumId,
    albumArtist = song.albumArtist,
    composer = song.composer,
    year = song.year,
    genre = song.genre,
    discNumber = song.discNumber,
    trackNumber = song.trackNumber,
    durationMs = song.durationMs,
    bitrateKbps = song.bitrateKbps,
    sampleRateHz = song.sampleRateHz,
    bitDepth = song.bitDepth,
    channels = song.channels,
    fileSizeBytes = fileSizeBytes,
    fileHash = fileHash,
    lastModified = lastModified,
    dateAdded = System.currentTimeMillis(),
    storageVolume = storageVolume,
    embeddedLyrics = song.embeddedLyrics,
    artworkUri = song.artworkUri,
    replayGainTrackDb = song.replayGainTrackDb,
    replayGainAlbumDb = song.replayGainAlbumDb,
)

fun ScannedTrack.toAlbumEntity(): AlbumEntity = AlbumEntity(
    id = song.albumId,
    name = song.album,
    albumArtist = song.albumArtist,
    year = song.year,
    artworkUri = song.artworkUri,
)

fun ScannedTrack.artistEntities(): List<ArtistEntity> = buildList {
    add(ArtistEntity(id = MetadataExtractor.songId("artist:${song.artist}"), name = song.artist))
    song.albumArtist?.let {
        add(ArtistEntity(id = MetadataExtractor.songId("artist:$it"), name = it))
    }
}

fun CollectionDao.AlbumRow.toDomain(): Album =
    Album(id, name, albumArtist, year, artworkUri, songCount)

/**
 * The artist id is derived from the NAME, not taken from the row.
 *
 * `CollectionDao.observeArtists` projects `MIN(s.id) AS id`, which is a song
 * id, not an artist identity. A single song joins to both its `artist` and its
 * `albumArtist` name, so when those differ and that song happens to be the
 * lowest-id row in both groups, two different artists come back carrying the
 * same id. Song ids are FNV-1a hashes of the URI rather than sequential
 * numbers, so "lowest id in the group" is effectively a random member — which
 * makes the collision easy to hit for any name with only a handful of tracks,
 * exactly what a featured credit or a compilation album produces.
 *
 * That duplicate reached Compose as a LazyColumn key and crashed the app on
 * scroll. Hashing the name here matches how [ArtistEntity] ids are already
 * built above, so the id is unique per artist and consistent across the two
 * places artists are constructed.
 */
fun CollectionDao.ArtistRow.toDomain(): Artist =
    Artist(
        id = MetadataExtractor.songId("artist:$name"),
        name = name,
        albumCount = albumCount,
        songCount = songCount,
    )

fun PlaylistDao.PlaylistRow.toDomain(): Playlist =
    Playlist(id, name, songCount, createdAt)
