package com.harmony.feature.downloads

import com.harmony.core.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyPlaylistMatcherTest {
    @Test
    fun exactMetadataAndCloseDurationMatchesLocalSong() {
        val remote = remote(title = "Dragostea din tei", artists = "O-Zone", durationMs = 214_000)
        val local = song(title = "Dragostea din Tei", artist = "O-Zone", durationMs = 215_000)

        val match = SpotifyPlaylistMatcher.match(remote, listOf(local))

        assertEquals(local.id, match?.song?.id)
        assertEquals(PlaylistMatchConfidence.EXACT, match?.confidence)
    }

    @Test
    fun diacriticsAndFeaturedArtistRemainCompatible() {
        val remote = remote(title = "Înapoi acasă", artists = "Ștefan, Mara", durationMs = 180_000)
        val local = song(title = "Inapoi acasa", artist = "Stefan feat. Mara", durationMs = 181_500)

        assertEquals(local.id, SpotifyPlaylistMatcher.match(remote, listOf(local))?.song?.id)
    }

    @Test
    fun liveEditionDoesNotSilentlyMatchStudioTrack() {
        val remote = remote(title = "The Chain - Live", artists = "Fleetwood Mac", durationMs = 270_000)
        val local = song(title = "The Chain", artist = "Fleetwood Mac", durationMs = 270_000)

        assertNull(SpotifyPlaylistMatcher.match(remote, listOf(local)))
    }

    @Test
    fun extractsSpotifyWebAndUriPlaylistIds() {
        val id = "37i9dQZF1DX4WYpdgoIcn6"
        assertEquals(id, SpotifyPlaylistClient.extractPlaylistId("https://open.spotify.com/playlist/$id?si=abc"))
        assertEquals(id, SpotifyPlaylistClient.extractPlaylistId("spotify:playlist:$id"))
        assertNull(SpotifyPlaylistClient.extractPlaylistId("https://open.spotify.com/track/$id"))
    }

    private fun remote(
        title: String,
        artists: String,
        durationMs: Long,
    ) = SpotifyPlaylistTrack(
        spotifyId = "0123456789abcdefghijKL",
        title = title,
        artists = artists,
        album = "Album",
        durationMs = durationMs,
        isrc = null,
        artworkUrl = null,
        spotifyUrl = null,
        position = 0,
    )

    private fun song(
        title: String,
        artist: String,
        durationMs: Long,
    ) = Song(
        id = 7L,
        uri = "content://music/7",
        title = title,
        artist = artist,
        album = "Album",
        albumId = 1L,
        albumArtist = artist,
        composer = null,
        year = null,
        genre = null,
        discNumber = null,
        trackNumber = null,
        durationMs = durationMs,
        bitrateKbps = 1_000,
        sampleRateHz = 44_100,
        bitDepth = 16,
        channels = 2,
        artworkUri = null,
        embeddedLyrics = null,
        replayGainTrackDb = null,
        replayGainAlbumDb = null,
    )
}
