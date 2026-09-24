package com.harmony.feature.discover

import com.harmony.feature.discover.provider.LiveSongCatalog
import org.junit.Assert.*
import org.junit.Test

class LiveSongCatalogPolicyTest {
    @Test fun previewsMustBeHttpsFromProviderCdns() {
        assertTrue(LiveSongCatalog.validPreview("https://cdnt-preview.dzcdn.net/api/song.mp3"))
        assertTrue(LiveSongCatalog.validPreview("https://audio-ssl.itunes.apple.com/AudioPreview/file.m4a"))
        for (url in listOf("http://cdnt-preview.dzcdn.net/song.mp3", "https://dzcdn.net.evil.test/a", "file:///sdcard/song.mp3",
            "https://user:pass@cdnt-preview.dzcdn.net/a", "https://cdnt-preview.dzcdn.net:8888/a")) assertFalse(url, LiveSongCatalog.validPreview(url))
    }
    @Test fun artistCountryIdentityRequiresAnActualDeezerArtistRelationship() {
        assertEquals(123L, LiveSongCatalog.deezerArtistId("https://www.deezer.com/artist/123"))
        assertEquals(123L, LiveSongCatalog.deezerArtistId("https://deezer.com/en/artist/123"))
        for (url in listOf("https://www.deezer.com/track/123", "https://deezer.com.evil.test/artist/123", "https://user@deezer.com/artist/123", "file:///artist/123"))
            assertNull(LiveSongCatalog.deezerArtistId(url))
    }
}
