package com.harmony.feature.downloads

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpotiFlacProviderRegistryTest {
    private val deezer = SpotiFlacProviderRegistry.fallbackPackages.first { it.id == "deezer" }

    private fun entry(url: String = deezer.url, hash: String = deezer.sha256, version: String = deezer.version) =
        JSONObject().put("id", "deezer").put("category", "download")
            .put("version", version).put("sha256", hash).put("download_url", url)

    private fun registry(vararg entries: JSONObject) =
        JSONObject().put("extensions", JSONArray(entries.toList())).toString()

    @Test fun fallbackIsOneCompleteImmutableOfficialSnapshot() {
        val expected = mapOf(
            "tidal-web" to Pair("1.2.9", "69ce52eabdd9df6464a30b793b802d3d7be32b331082c02a9bf2b25d1d49114d"),
            "qobuz-web" to Pair("1.2.23", "054c1d2c40058b49f5ade17a64442014ddf05944663a12c790a17d63ee99985f"),
            "deezer" to Pair("1.3.7", "39868171eee142f21de323b38c2ee5690e44659496f8e70e01554564b464f292"),
            "amazon" to Pair("2.3.14", "d45e105b4286d4c7ddc062b73b8190c93099e7f6e4d4586b420e0b31dabcb967"),
        )
        assertEquals(expected.keys, SpotiFlacProviderRegistry.fallbackPackages.map { it.id }.toSet())
        for (item in SpotiFlacProviderRegistry.fallbackPackages) {
            assertEquals(expected.getValue(item.id), Pair(item.version, item.sha256))
            assertTrue(item.url.contains("/93d55eee1cc52f088c311459e015b168c4798aa5/extensions/"))
            assertTrue(SpotiFlacProviderRegistry.isTrustedProviderUrl(item.url, item.id))
        }
        assertEquals(
            "https://raw.githubusercontent.com/mornyeapp/Mornye-Extension/main/registry.json",
            SpotiFlacProviderRegistry.OFFICIAL_REGISTRY_URL,
        )
    }

    @Test fun deadOrMalformedRegistryUsesCompletePinnedFallback() {
        for (fetch in listOf<() -> String>(
            { throw IOException("HTTP 404") },
            { "not JSON" },
            { "{}" },
        )) {
            val result = SpotiFlacProviderRegistry.resolve(fetch)
            assertEquals(SpotiFlacProviderRegistry.fallbackPackages, result.packages)
            assertEquals("bundled-fallback", result.updatedAt)
        }
    }

    @Test fun missingProvidersUsePinnedFallbackWhileValidEntriesUpdate() {
        val newHash = "a".repeat(64)
        val result = SpotiFlacProviderRegistry.parse(registry(entry(hash = newHash, version = "1.3.8")))
        assertEquals(SpotiFlacProviderRegistry.priority, result.packages.map { it.id })
        assertEquals(newHash, result.packages.first { it.id == "deezer" }.sha256)
        for (item in result.packages.filter { it.id != "deezer" }) {
            assertEquals(SpotiFlacProviderRegistry.fallbackPackages.first { it.id == item.id }, item)
        }
        val fallback = SpotiFlacProviderRegistry.resolve { throw IOException() }
        assertNotEquals("New pins must not load the old runtime cache", fallback.fingerprint, result.fingerprint)
    }

    @Test fun invalidHashVersionOrSourceCannotOverrideFallback() {
        for (invalid in listOf(entry(hash = "bad"), entry(version = ""), entry(url = "https://raw.githubusercontent.com/attacker/repo/main/extensions/deezer.sflx"))) {
            val result = SpotiFlacProviderRegistry.parse(registry(invalid))
            assertEquals(deezer, result.packages.first { it.id == "deezer" })
        }
    }

    @Test fun onlyOfficialRepositoryAndExactProviderFilenameAreTrusted() {
        for (ownerRepo in listOf("mornyeapp/Mornye-Extension", "zarzet/SpotiFLAC-Extension", "spotiflacapp/SpotiFLAC-Extension")) {
            assertTrue(SpotiFlacProviderRegistry.isTrustedProviderUrl(
                "https://raw.githubusercontent.com/$ownerRepo/main/extensions/deezer.sflx", "deezer",
            ))
        }
        val root = "https://raw.githubusercontent.com/mornyeapp/Mornye-Extension/main/extensions/"
        for (url in listOf(
            root + "qobuz-web.sflx", root + "deezer.sflx?token=x", root + "deezer.sflx#fragment",
            root + "../extensions/deezer.sflx", root + "%64eezer.sflx",
            root.replace("/Mornye-Extension/", "/Mornye-Extension-evil/") + "deezer.sflx",
            root.replace("/mornyeapp/", "/attacker/") + "deezer.sflx",
            root.replace("https:", "http:") + "deezer.sflx",
            root.replace("https://", "https://user@") + "deezer.sflx",
            root.replace(".com/", ".com:443/") + "deezer.sflx",
            root.replace("raw.githubusercontent.com", "raw.githubusercontent.com.evil.test") + "deezer.sflx",
        )) assertFalse(url, SpotiFlacProviderRegistry.isTrustedProviderUrl(url, "deezer"))
    }
}
