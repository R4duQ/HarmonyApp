package com.harmony.feature.downloads

import org.junit.Assert.*
import org.junit.Test

class SpotifyApiUrlPolicyTest {
    @Test fun acceptsSpotifyPagination() {
        assertTrue(SpotifyApiUrlPolicy.isAllowed("https://api.spotify.com/v1/me/playlists?offset=50&limit=50"))
        assertTrue(SpotifyApiUrlPolicy.isAllowed("https://api.spotify.com:443/v1/me"))
    }
    @Test fun rejectsExternalOrUnencryptedPages() {
        for (url in listOf("http://api.spotify.com/v1/me", "https://api.spotify.com.evil.test/v1/me", "https://evil.test/v1/me", "//api.spotify.com/v1/me", "https://api.spotify.com:8080/v1/me")) assertFalse(url, SpotifyApiUrlPolicy.isAllowed(url))
    }
    @Test fun rejectsAmbiguousUrls() {
        for (url in listOf("https://user@api.spotify.com/v1/me", "https://api.spotify.com/v1/../me", "https://api.spotify.com/v1/me#token", "file:///v1/me", "%%%")) assertFalse(url, SpotifyApiUrlPolicy.isAllowed(url))
    }
}
