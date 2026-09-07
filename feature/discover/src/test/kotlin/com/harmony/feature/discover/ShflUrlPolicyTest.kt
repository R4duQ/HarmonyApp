package com.harmony.feature.discover

import com.harmony.feature.discover.model.ShflUrlPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SHFL gateway's only real risk is opening something it shouldn't, so the
 * allowlist is the part that gets tested.
 *
 * These run on the JVM with no Robolectric and no device, which is why
 * [ShflUrlPolicy] is written against `java.net.URI` instead of
 * `android.net.Uri` — the latter is a stub in unit tests and throws.
 */
class ShflUrlPolicyTest {

    @Test
    fun `verified album pages open but unknown album paths remain blocked`() {
        assertEquals("https://theshfl.com/album/Discovery-1",
            ShflUrlPolicy.sanitizeDestination("https://www.theshfl.com/album/Discovery-1"))
        assertNull(ShflUrlPolicy.sanitizeDestination("https://theshfl.com/album/Discovery"))
        assertNull(ShflUrlPolicy.sanitizeDestination("https://theshfl.com/album/Rumours?library=private"))
        assertNull(ShflUrlPolicy.sanitizeDestination("https://theshfl.com/album/%2e%2e/login"))
    }

    @Test
    fun `every published destination is allowed and canonical`() {
        ShflUrlPolicy.ALLOWED_DESTINATIONS.forEach { url ->
            assertEquals(url, ShflUrlPolicy.sanitizeDestination(url))
        }
    }

    @Test
    fun `equivalent spellings of an allowed destination resolve to it`() {
        assertEquals(ShflUrlPolicy.HOME, ShflUrlPolicy.sanitizeDestination("https://theshfl.com"))
        assertEquals(
            ShflUrlPolicy.GUIDES,
            ShflUrlPolicy.sanitizeDestination("HTTPS://TheSHFL.com/guides"),
        )
        assertEquals(
            ShflUrlPolicy.GUIDES,
            ShflUrlPolicy.sanitizeDestination("https://theshfl.com:443/guides"),
        )
        assertEquals(
            ShflUrlPolicy.BEST_OF,
            ShflUrlPolicy.sanitizeDestination("  https://theshfl.com/best-of  "),
        )
    }

    @Test
    fun `cleartext is rejected rather than upgraded`() {
        assertNull(ShflUrlPolicy.sanitizeDestination("http://theshfl.com/"))
        assertFalse(ShflUrlPolicy.isAllowedHost("http://theshfl.com/"))
    }

    @Test
    fun `lookalike hosts are rejected`() {
        listOf(
            "https://theshfl.com.example.com/",
            "https://eviltheshfl.com/",
            "https://shfl.com/",
            "https://sub.theshfl.com/guides",
        ).forEach { assertNull(it, ShflUrlPolicy.sanitizeDestination(it)) }
    }

    @Test
    fun `user info cannot disguise the real host`() {
        // Parses to host example.com, but reads to a human as theshfl.com.
        assertNull(ShflUrlPolicy.sanitizeDestination("https://theshfl.com@example.com/"))
        assertFalse(ShflUrlPolicy.isAllowedHost("https://theshfl.com@example.com/"))
    }

    @Test
    fun `non-default ports are rejected`() {
        assertNull(ShflUrlPolicy.sanitizeDestination("https://theshfl.com:8443/guides"))
    }

    @Test
    fun `query and fragment are refused, not stripped`() {
        // This is the route personal data would have to take to leave the
        // device, so it fails the URL rather than being silently sanitised.
        assertNull(ShflUrlPolicy.sanitizeDestination("https://theshfl.com/guides?from=harmony"))
        assertNull(ShflUrlPolicy.sanitizeDestination("https://theshfl.com/guides#section"))
    }

    @Test
    fun `path traversal is rejected`() {
        assertNull(ShflUrlPolicy.sanitizeDestination("https://theshfl.com/guides/../private"))
        assertNull(ShflUrlPolicy.sanitizeDestination("https://theshfl.com/./guides"))
    }

    @Test
    fun `non-web schemes are rejected`() {
        listOf(
            "javascript:alert(1)",
            "intent://theshfl.com/#Intent;scheme=https;end",
            "file:///data/data/com.harmony.app/databases/harmony.db",
        ).forEach { assertNull(it, ShflUrlPolicy.sanitizeDestination(it)) }
    }

    @Test
    fun `malformed and relative input is rejected`() {
        listOf("", "   ", "not a url", "//theshfl.com/guides", "theshfl.com/guides")
            .forEach { assertNull(it, ShflUrlPolicy.sanitizeDestination(it)) }
    }

    @Test
    fun `an SHFL page outside the allowlist passes the host gate but not the destination gate`() {
        val url = "https://theshfl.com/login"
        assertTrue(ShflUrlPolicy.isAllowedHost(url))
        assertFalse(ShflUrlPolicy.isAllowedDestination(url))
        assertNull(ShflUrlPolicy.sanitizeDestination(url))
    }
}
