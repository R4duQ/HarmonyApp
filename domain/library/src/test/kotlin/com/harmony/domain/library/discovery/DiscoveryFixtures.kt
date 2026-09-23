package com.harmony.domain.library.discovery

import com.harmony.core.model.Song
import com.harmony.domain.library.repository.DiscoverySong

internal const val DAY = 86_400_000L
internal const val NOW = 1_800_000_000_000L

internal fun librarySong(id: Long, artist: String, title: String = "Song $id", genre: String? = "Pop", ms: Long = 200_000) = Song(
    id = id, uri = "content://songs/$id", title = title, artist = artist, album = "Album", albumId = 1, albumArtist = null,
    composer = null, year = null, genre = genre, discNumber = null, trackNumber = null, durationMs = ms, bitrateKbps = null,
    sampleRateHz = null, bitDepth = null, channels = null, artworkUri = null, embeddedLyrics = null,
    replayGainTrackDb = null, replayGainAlbumDb = null,
)

internal fun remote(i: Int, artist: String = "Artist $i", title: String = "Track $i", isrc: String? = null) =
    DiscoverySong("dz:$i", title, artist, durationMs = 180_000L + i, provider = "Deezer", isrc = isrc)

internal fun candidate(i: Int, kind: CandidateKind, artist: String = "Artist $i", score: Float = 1f, isrc: String? = null) =
    Candidate(remote(i, artist, isrc = isrc), kind, Reason(ReasonKind.LISTENED, "test"), score)

internal fun plays(songId: Long, count: Int, daysAgo: Int, completed: Boolean = true, spacingHours: Long = 48) =
    (0 until count).map { ListeningEvent(songId, NOW - daysAgo * DAY - it * spacingHours * 3_600_000L, completed) }
