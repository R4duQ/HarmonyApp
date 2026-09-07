package com.harmony.feature.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyAuthorizationTest {
    @Test
    fun rejectsPlaceholderUsernameAndPlaylistValuesPreviouslyAcceptedByLooseValidation() {
        listOf("", "YOUR_CLIENT_ID", "yourspotifyusername", "a".repeat(16), "g".repeat(32),
            "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M").forEach { input ->
            assertFalse(SpotifyAuthorization.isValidClientId(input))
            assertNotNull(SpotifyAuthorization.clientIdError(input))
        }
    }

    @Test
    fun acceptsDashboardIdFormatWithoutClaimingTheAppExists() {
        assertTrue(SpotifyAuthorization.isValidClientId("0123456789abcdef0123456789abcdef"))
        assertNull(SpotifyAuthorization.clientIdError("0123456789abcdef0123456789abcdef"))
    }

    @Test
    fun pkceMatchesRfc7636AppendixB() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            SpotifyAuthorization.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun everyAttemptGetsFreshUrlSafeVerifierAndState() {
        val verifier = SpotifyAuthorization.newVerifier()
        assertTrue(verifier.matches(Regex("[A-Za-z0-9_-]{43,128}")))
        assertTrue(SpotifyAuthorization.newState().matches(Regex("[A-Za-z0-9_-]{43}")))
        assertNotEquals(verifier, SpotifyAuthorization.newVerifier())
        assertNotEquals(SpotifyAuthorization.newState(), SpotifyAuthorization.newState())
    }

    @Test
    fun rejectsOtherCallbackDestinations() {
        assertTrue(SpotifyAuthorization.isExpectedCallback(
            "${SpotifyAuthorization.REDIRECT_URI}?code=example&state=example",
        ))
        listOf(
            "https://example.com?code=example",
            "com.harmony.app://attacker@spotify-playlist-callback?code=example",
            "com.harmony.app://spotify-playlist-callback:1234?code=example",
            "com.harmony.app://spotify-playlist-callback:?code=example",
            "com.harmony.app://spotify-playlist-callback/other?code=example",
            "com.harmony.app://spotify-playlist-callback.evil?code=example",
            "com.harmony.app://spotify-playlist-callback//?code=example",
            "com.harmony.app://spotify-playlist-callback/%2F?code=example",
            "com.harmony.app://spotify-playlist-callback/../?code=example",
            "com.harmony.app://spotify-playlist-callback.evil#${SpotifyAuthorization.REDIRECT_URI}",
        ).forEach { assertFalse(SpotifyAuthorization.isExpectedCallback(it)) }
    }

    @Test
    fun acceptsCallbackWithEmptyOrRootPathAndIgnoresFragment() {
        for (path in listOf("", "/")) {
            for (fragment in listOf("", "#", "#_=_", "#state=ignored&code=ignored")) {
                assertTrue(SpotifyAuthorization.isExpectedCallback(
                    "${SpotifyAuthorization.REDIRECT_URI}$path?code=example&state=example$fragment",
                ))
            }
        }
    }

    @Test
    fun callbackRejectionsHaveSafeSpecificDiagnostics() {
        val base = SpotifyAuthorization.REDIRECT_URI
        val query = "?code=private-code&state=private-state"
        val cases = mapOf(
            "$base/invalid path$query" to "malformed address",
            "https://spotify-playlist-callback$query" to "scheme mismatch",
            "com.harmony.app://other$query" to "host mismatch",
            "com.harmony.app://private-user@spotify-playlist-callback$query" to "unexpected user information",
            "$base:1234$query" to "unexpected port",
            "$base/other$query" to "unexpected path",
        )
        cases.forEach { (value, reason) ->
            assertEquals(reason, SpotifyAuthorization.callbackLocationError(value))
        }
    }

    @Test
    fun missingOrWrongStateIsRejected() {
        for (returned in listOf(null, "", "another-attempt")) {
            assertThrows(IllegalArgumentException::class.java) {
                SpotifyAuthorization.validateState("current-attempt", returned, 1_000L, 2_000L)
            }
        }
    }

    @Test
    fun expiredOrInvalidTimestampIsRejected() {
        for ((started, now) in listOf(1_000L to 601_001L, 0L to 1_000L, 2_000L to 1_000L)) {
            assertThrows(IllegalArgumentException::class.java) {
                SpotifyAuthorization.validateState("same", "same", started, now)
            }
        }
        SpotifyAuthorization.validateState("same", "same", 1_000L, 601_000L)
    }

    @Test
    fun spotifyClientRejectionExplainsWhichValueToReplace() {
        val error = SpotifyAuthorization.errorMessage("invalid_client", "Invalid client")
        assertTrue(error.contains("Client ID"))
        assertTrue(error.contains("Developer Dashboard"))
        assertTrue(error.contains("replace"))
    }
}
