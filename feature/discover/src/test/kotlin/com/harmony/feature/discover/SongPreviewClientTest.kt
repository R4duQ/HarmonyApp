package com.harmony.feature.discover

import com.harmony.feature.discover.provider.*
import org.junit.Assert.*
import org.junit.Test

class SongPreviewClientTest {
    private val client = SongPreviewClient()
    private val song = SongTasteEngine.songs.first { it.album.id == "Rumours" }

    @Test fun separateVersionMetadataCannotTurnALiveOrRemixedTrackIntoAStudioPreview() {
        listOf("(Live)", "Remix", "Instrumental", "Sped Up").forEach { version ->
            assertFalse(version, client.matchesRecording(song, song.title, song.album.artist, version, song.album.title))
        }
    }
    @Test fun originalAndRemasteredRecordingsStillMatch() {
        assertTrue(client.matchesRecording(song, song.title, song.album.artist, "", song.album.title))
        assertTrue(client.matchesRecording(song, song.title, song.album.artist, "(2004 Remaster)", song.album.title))
        assertTrue(client.matchesRecording(song, "${song.title} (2004 Remaster)", song.album.artist, "(2004 Remaster)", song.album.title))
    }
    @Test fun coversAndDifferentSongsDoNotMatch() {
        assertFalse(client.matchesRecording(song, song.title, "Another artist", "", song.album.title))
        assertFalse(client.matchesRecording(song, "Another song", song.album.artist, "", song.album.title))
    }
    @Test fun aLiveRecordRequiresItsOwnAlbumAndAcceptsLiveVersionLabels() {
        val live = SongTasteEngine.songs.first { it.album.id == "Live-at-the-Regal" }
        assertTrue(client.matchesRecording(live, live.title, live.album.artist, "(Live)", live.album.title))
        assertTrue(client.matchesRecording(live, "${live.title} (Live)", live.album.artist, "(Live)", live.album.title))
        assertFalse(client.matchesRecording(live, live.title, live.album.artist, "", "Greatest Hits"))
    }
    @Test fun providerPreviewUrlsRejectUntrustedHostsAndSchemes() {
        assertTrue(client.isPreviewUrl("https://cdns-preview-a.dzcdn.net/stream/clip.mp3"))
        listOf("http://cdns-preview-a.dzcdn.net/a.mp3", "https://dzcdn.net.example.com/a.mp3",
            "https://name@cdns-preview-a.dzcdn.net/a.mp3", "https://cdns-preview-a.dzcdn.net:8080/a.mp3").forEach {
            assertFalse(it, client.isPreviewUrl(it))
        }
    }
}
