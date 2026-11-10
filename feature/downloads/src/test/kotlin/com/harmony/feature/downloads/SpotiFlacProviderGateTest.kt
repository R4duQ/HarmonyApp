package com.harmony.feature.downloads

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** What a provider package may ask for, which package a provider runs on, and which URLs Harmony follows. */
class SpotiFlacProviderGateTest {
    @get:Rule val folder = TemporaryFolder()

    private val tidalHosts = listOf(
        "tidal.com", "www.tidal.com", "listen.tidal.com", "resources.tidal.com", "api.zarz.moe",
        "*.audio.tidal.com", "*.manifest.tidal.com",
    )

    /** A package as the registry ships them: a ZIP with manifest.json and index.js. */
    private fun packageBytes(
        id: String = "tidal-web",
        version: String = "1.2.14",
        hosts: List<String> = tidalHosts,
        extraPermission: String? = null,
        marker: String = "",
    ): ByteArray {
        val permissions = JSONObject().put("network", JSONArray(hosts)).put("storage", true).put("file", true)
        extraPermission?.let { permissions.put(it, true) }
        val manifest = JSONObject().put("name", id).put("version", version).put("permissions", permissions)
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString().toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("index.js"))
            zip.write("// $marker".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun described(bytes: ByteArray, id: String = "tidal-web", version: String = "1.2.14") =
        ProviderPackage(id, "Tidal", version, "https://example.invalid/$id-$version.sflx", sha(bytes))

    private fun refusal(bytes: ByteArray, expected: ProviderPackage = described(bytes)): String? {
        val file = folder.newFile().apply { writeBytes(bytes) }
        return SpotiFlacProviderGate.refusal(file, expected)
    }

    // -- The gate ---------------------------------------------------------------------------

    @Test fun theHostsEachProviderUsesTodayAreAccepted() {
        val shipped = mapOf(
            "tidal-web" to tidalHosts,
            "qobuz-web" to listOf(
                "api.zarz.moe", "www.qobuz.com", "qobuz.com", "play.qobuz.com", "open.qobuz.com", "static.qobuz.com",
                "streaming-qobuz-std.akamaized.net", "streaming.qobuz.com",
            ),
            "deezer" to listOf(
                "api.zarz.moe", "dl.musicdl.me", "api.deezer.com", "www.deezer.com", "deezer.page.link",
                "link.deezer.com", "*.dzcdn.net",
            ),
            "amazon" to listOf(
                "na.mesk.skill.music.a2z.com", "na.web.skill.music.a2z.com", "music.amazon.com", "music.amazon.co",
                "music.amazon.de", "music.amazon.co.uk", "music.amazon.co.jp", "music.amazon.fr", "music.amazon.com.br",
                "m.media-amazon.com", "api.zarz.moe", "song.link", "album.link", "artist.link", "odesli.co",
                "www.odesli.co", "odesli.io", "api.deezer.com", "open.spotify.com", "songstats.com", "*.cloudfront.net",
            ),
        )
        for ((id, hosts) in shipped) {
            val bytes = packageBytes(id = id, hosts = hosts)
            assertNull("$id as shipped", refusal(bytes, described(bytes, id)))
        }
    }

    @Test fun aPackageAskingForMoreIsRefused() {
        fun refused(hosts: List<String>) = refusal(packageBytes(hosts = hosts))
        assertTrue(refused(tidalHosts + "evil.example")!!.contains("evil.example"))
        assertNotNull("any host", refused(listOf("*")))
        assertNotNull("a whole TLD", refused(listOf("*.com")))
        assertNotNull("a lookalike suffix", refused(listOf("nottidal.com")))
        assertNotNull("a wildcard inside a name", refused(listOf("a*.tidal.com")))
        assertNotNull("an address on the local network", refused(listOf("192.168.1.1")))
        assertNull("a narrower list is fine", refused(listOf("tidal.com")))
        assertNull("a new host under a reviewed domain is fine", refused(tidalHosts + "fsu.fa.tidal.com"))
        // Amazon's regional sites are exact hosts of one shape, never a wildcard.
        val amazon = packageBytes(id = "amazon", hosts = listOf("music.amazon.nl"))
        assertNull(refusal(amazon, described(amazon, "amazon")))
        val wild = packageBytes(id = "amazon", hosts = listOf("music.amazon.*"))
        assertNotNull(refusal(wild, described(wild, "amazon")))
        val other = packageBytes(id = "amazon", hosts = listOf("music.amazon.evil.example"))
        assertNotNull(refusal(other, described(other, "amazon")))
    }

    @Test fun aPackageMustBeWhatTheRegistrySaysItIs() {
        val bytes = packageBytes()
        assertNotNull("another provider's package", refusal(bytes, described(bytes, id = "deezer")))
        assertNotNull("another version", refusal(bytes, described(bytes, version = "9.9.9")))
        assertNotNull("a new kind of permission", refusal(packageBytes(extraPermission = "shell")))
        assertNotNull("not a package at all", refusal("plain text".toByteArray()))
        val noManifest = java.io.ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { it.putNextEntry(ZipEntry("index.js")); it.write(1); it.closeEntry() }
        }.toByteArray()
        assertNotNull(refusal(noManifest))
    }

    @Test fun versionsCompareByNumber() {
        fun cmp(a: String, b: String) = SpotiFlacProviderGate.compareVersions(a, b)
        assertTrue(cmp("1.2.10", "1.2.9") > 0)
        assertTrue(cmp("5.3.0", "4.9.6") > 0)
        assertTrue(cmp("4.9.6", "5.3.0") < 0)
        assertEquals(0, cmp("5.3", "5.3.0"))
        assertEquals(0, cmp("v5.3.1", "5.3.1"))
        assertTrue(cmp("2.0.0-beta", "1.9.9") > 0)
        assertEquals(0, cmp("", "0"))
    }

    @Test fun readsAreBounded() {
        assertEquals(5, SpotiFlacProviderGate.readBounded(ByteArrayInputStream(ByteArray(5)), 5).size)
        try {
            SpotiFlacProviderGate.readBounded(ByteArrayInputStream(ByteArray(6)), 5)
            fail("six bytes must not fit in five")
        } catch (_: IOException) {
        }
        val target = folder.newFile()
        try {
            SpotiFlacProviderGate.copyBounded(ByteArrayInputStream(ByteArray(100_000)), target, 50_000)
            fail("an oversized download must stop")
        } catch (_: IOException) {
        }
    }

    // -- The store --------------------------------------------------------------------------

    private class Remembered : AcceptedPackages {
        val map = HashMap<String, ProviderPackage>()
        override fun get(providerId: String) = map[providerId]
        override fun put(accepted: ProviderPackage) { map[accepted.id] = accepted }
    }

    private class Server(vararg served: Pair<ProviderPackage, ByteArray>) {
        private val bytes = served.associate { it.first.url to it.second }
        val requests = mutableListOf<String>()
        fun download(item: ProviderPackage, target: File) {
            requests += item.url
            target.writeBytes(bytes[item.url] ?: throw IOException("HTTP 404"))
        }
    }

    @Test fun aPackageIsFetchedOnceAndKept() {
        val bytes = packageBytes()
        val item = described(bytes)
        val server = Server(item to bytes)
        val memory = Remembered()
        val store = SpotiFlacPackageStore(folder.newFolder(), memory, emptyList())
        val first = store.resolve(item, server::download)
        val second = store.resolve(item, server::download)
        assertEquals(1, server.requests.size)
        assertEquals(first.file, second.file)
        assertTrue(first.file.readBytes().contentEquals(bytes))
        assertEquals(item, memory.get("tidal-web"))
        assertTrue(first.notes.isEmpty())
    }

    @Test fun aRefusedUpdateKeepsTheLastAcceptedPackage() {
        val good = packageBytes(version = "1.2.14")
        val goodItem = described(good, version = "1.2.14")
        val greedy = packageBytes(version = "1.2.15", hosts = tidalHosts + "collector.example")
        val greedyItem = described(greedy, version = "1.2.15")
        val server = Server(goodItem to good, greedyItem to greedy)
        val memory = Remembered()
        val root = folder.newFolder()
        val store = SpotiFlacPackageStore(root, memory, emptyList())
        store.resolve(goodItem, server::download)

        val result = store.resolve(greedyItem, server::download)
        assertEquals("the provider stays on what was accepted", goodItem, result.accepted)
        assertTrue(result.file.readBytes().contentEquals(good))
        assertTrue(result.notes.single().contains("collector.example"))
        assertEquals("and the refusal is not remembered as accepted", goodItem, memory.get("tidal-web"))
        assertFalse("the refused bytes are not kept", root.listFiles()!!.any { it.name.contains(greedyItem.sha256) })
    }

    @Test fun anOlderOrTamperedPackageIsNotInstalled() {
        val current = packageBytes(version = "1.2.14")
        val currentItem = described(current, version = "1.2.14")
        val old = packageBytes(version = "1.2.9", marker = "old")
        val oldItem = described(old, version = "1.2.9")
        val server = Server(currentItem to current, oldItem to old)
        val store = SpotiFlacPackageStore(folder.newFolder(), Remembered(), emptyList())
        store.resolve(currentItem, server::download)

        // A registry replaying an old version: refused without even fetching it.
        val downgrade = store.resolve(oldItem, server::download)
        assertEquals(currentItem, downgrade.accepted)
        assertEquals(1, server.requests.size)
        assertTrue(downgrade.notes.single().contains("older"))

        // Bytes that are not the ones the registry names.
        val claimed = currentItem.copy(version = "1.2.15", url = "https://example.invalid/swapped.sflx", sha256 = "f".repeat(64))
        val swapped = Server(claimed to packageBytes(version = "1.2.15", marker = "swapped"))
        val result = store.resolve(claimed, swapped::download)
        assertEquals(currentItem, result.accepted)
        assertTrue(result.notes.single().contains("SHA-256 mismatch"))
    }

    @Test fun withNothingAcceptedYetThePinnedPackageIsUsed() {
        // The pinned package is trusted by its hash: it is not re-reviewed.
        val pinnedBytes = packageBytes(version = "1.2.9", hosts = tidalHosts + "legacy.example")
        val pinned = described(pinnedBytes, version = "1.2.9")
        val greedy = packageBytes(version = "1.2.15", hosts = listOf("*"))
        val greedyItem = described(greedy, version = "1.2.15")
        val server = Server(pinned to pinnedBytes, greedyItem to greedy)
        val store = SpotiFlacPackageStore(folder.newFolder(), Remembered(), listOf(pinned))
        val result = store.resolve(greedyItem, server::download)
        assertEquals(pinned, result.accepted)
        assertTrue(result.file.readBytes().contentEquals(pinnedBytes))

        // Nothing to fall back on: the provider is unavailable, with the reasons.
        val alone = SpotiFlacPackageStore(folder.newFolder(), Remembered(), emptyList())
        try {
            alone.resolve(greedyItem, server::download)
            fail("a refused package with no fallback must not be installed")
        } catch (failure: SpotiFlacException) {
            assertEquals("provider_install", failure.errorType)
            assertTrue(failure.technicalDetails!!.contains("not reviewed"))
        }
    }

    @Test fun pruneKeepsWhatRunsAndWhatWasLastAccepted() {
        val a = packageBytes(version = "1.2.13", marker = "a")
        val b = packageBytes(version = "1.2.14", marker = "b")
        val itemA = described(a, version = "1.2.13")
        val itemB = described(b, version = "1.2.14")
        val server = Server(itemA to a, itemB to b)
        val root = folder.newFolder()
        val store = SpotiFlacPackageStore(root, Remembered(), emptyList())
        store.resolve(itemA, server::download)
        store.resolve(itemB, server::download)
        assertEquals(2, root.listFiles()!!.size)
        store.prune(listOf(itemB))
        assertEquals(listOf("tidal-web-${itemB.sha256}.sflx"), root.listFiles()!!.map { it.name })
    }

    // -- The registry -----------------------------------------------------------------------

    private val deezer = SpotiFlacProviderRegistry.fallbackPackages.first { it.id == "deezer" }

    private fun registry(version: String, minApp: String?, hash: String = "a".repeat(64)): String {
        val entry = JSONObject().put("id", "deezer").put("category", "download").put("version", version)
            .put("sha256", hash).put("download_url", deezer.url)
        minApp?.let { entry.put("min_app_version", it) }
        return JSONObject().put("extensions", JSONArray(listOf(entry))).put("updated_at", "today").toString()
    }

    @Test fun aPackageThatNeedsANewerRuntimeIsLeftOut() {
        fun version(minApp: String?, app: String) =
            SpotiFlacProviderRegistry.parse(registry("9.0.0", minApp), app).packages.first { it.id == "deezer" }.version
        assertEquals("9.0.0", version("5.3.0", "5.3.1"))
        assertEquals("9.0.0", version("5.3.1", "5.3.1"))
        assertEquals("the pinned package stays", deezer.version, version("5.4.0", "5.3.1"))
        assertEquals("the Go runtime cannot load a 5.x package", deezer.version, version("5.3.0", "4.9.6"))
        assertEquals("9.0.0", version(null, "4.9.6"))
        assertEquals("an unknown runtime version filters nothing", "9.0.0", version("99.0.0", ""))
    }

    @Test fun theRegistryIsAskedForOnlyWhenTheCachedCopyIsOld() {
        var now = 1_000_000L
        val cache = SpotiFlacRegistryCache(File(folder.newFolder(), "registry.json"), maxAgeMs = 1_000) { now }
        var fetches = 0
        val fetch = { fetches++; registry("9.0.$fetches", null) }
        fun version(fetcher: () -> String) =
            SpotiFlacProviderRegistry.resolve("5.3.1", cache, fetcher).packages.first { it.id == "deezer" }.version

        assertEquals("9.0.1", version(fetch))
        now += 500
        assertEquals("still fresh: no request", "9.0.1", version(fetch))
        assertEquals(1, fetches)
        now += 600
        assertEquals("old: asked again", "9.0.2", version(fetch))
        assertEquals(2, fetches)

        // Unreachable later: the stale copy beats the bundled fallback.
        now += 5_000
        val offline = SpotiFlacProviderRegistry.resolve("5.3.1", cache) { throw IOException("offline") }
        assertEquals("9.0.2", offline.packages.first { it.id == "deezer" }.version)
        assertTrue(offline.source.contains("stale"))
        // And with no copy at all, the pinned packages.
        val empty = SpotiFlacRegistryCache(File(folder.newFolder(), "registry.json"), 1_000) { now }
        val bundled = SpotiFlacProviderRegistry.resolve("5.3.1", empty) { throw IOException("offline") }
        assertEquals(SpotiFlacProviderRegistry.fallbackPackages, bundled.packages)
        // A broken answer is not cached over a good one.
        now += 5_000
        SpotiFlacProviderRegistry.resolve("5.3.1", cache) { "not JSON" }
        assertEquals("9.0.2", SpotiFlacProviderRegistry.parse(cache.read()!!, "5.3.1").packages.first { it.id == "deezer" }.version)
    }

    // -- URLs from a provider's answer ------------------------------------------------------

    @Test fun onlyPublicHttpsUrlsAreFollowed() {
        for (ok in listOf(
            "https://resources.tidal.com/images/a/b/c/1280x1280.jpg",
            "https://e-cdns-images.dzcdn.net/images/cover/x/1000x1000.jpg",
            "https://m.media-amazon.com/images/I/x.jpg",
            "https://8.8.8.8/x.png",
        )) assertTrue(ok, SpotiFlacUrlSafety.isPublicHttpsUrl(ok))
        for (bad in listOf(
            "http://resources.tidal.com/cover.jpg",
            "ftp://example.com/x",
            "file:///data/data/com.harmony.app/databases/harmony.db",
            "content://media/external/audio/media/1",
            "https://user:pass@example.com/x.jpg",
            "https://localhost/x", "https://router/x", "https://printer.local/x", "https://nas.lan/x",
            "https://127.0.0.1/x", "https://10.0.0.5/x", "https://192.168.1.1/admin", "https://172.16.4.4/x",
            "https://172.31.255.1/x", "https://169.254.169.254/latest/meta-data", "https://100.64.0.1/x",
            "https://0.0.0.0/x", "https://[::1]/x", "https://[fd00::1]/x", "https://[fe80::1]/x",
            "https://[::ffff:192.168.1.1]/x", "https://2130706433/x", "", "not a url",
        )) assertFalse(bad, SpotiFlacUrlSafety.isPublicHttpsUrl(bad))
        assertTrue("172.32 is public", SpotiFlacUrlSafety.isPublicHttpsUrl("https://172.32.0.1/x"))
    }

    @Test fun aNameThatPointsAtAPrivateAddressIsNotPublic() {
        fun resolved(vararg addresses: String): (String) -> List<InetAddress> =
            { addresses.map { InetAddress.getByName(it) } }
        assertTrue(SpotiFlacUrlSafety.resolvesToPublic("cdn.example", resolved("93.184.216.34")))
        assertFalse(SpotiFlacUrlSafety.resolvesToPublic("rebind.example", resolved("192.168.1.1")))
        assertFalse("one private address among public ones", SpotiFlacUrlSafety.resolvesToPublic("mixed.example", resolved("93.184.216.34", "10.0.0.1")))
        assertFalse(SpotiFlacUrlSafety.resolvesToPublic("loop.example", resolved("127.0.0.1")))
        assertFalse(SpotiFlacUrlSafety.resolvesToPublic("v6.example", resolved("fd00::1")))
        assertFalse("nothing resolved", SpotiFlacUrlSafety.resolvesToPublic("none.example") { emptyList() })
        assertFalse("resolution failed", SpotiFlacUrlSafety.resolvesToPublic("fail.example") { throw java.net.UnknownHostException() })
    }
}
