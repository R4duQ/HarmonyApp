package com.harmony.feature.downloads

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Stage 1, the checks that block a merge: a download starts on the providers
 * at hand before a slow registry answers; a bad package is turned away; an
 * update of one provider keeps another's session; both runtimes (SpotiFLAC
 * 4.9.6 in the stable APK, Mornye 5.3.1 in the Mornye one) take only what
 * they can load; a file from another job, a link out of the staging folder
 * and a cover redirected to a local address are refused.
 */
class SpotiFlacStartupTest {
    @get:Rule val folder = TemporaryFolder()

    private val hosts = mapOf(
        "tidal-web" to listOf("tidal.com", "*.audio.tidal.com", "api.zarz.moe"),
        "deezer" to listOf("api.deezer.com", "*.dzcdn.net", "api.zarz.moe"),
    )

    private fun packageBytes(id: String, version: String, extraHost: String? = null): ByteArray {
        val network = JSONArray(hosts.getValue(id) + listOfNotNull(extraHost))
        val manifest = JSONObject().put("name", id).put("version", version)
            .put("permissions", JSONObject().put("network", network).put("storage", true).put("file", true))
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString().toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("index.js")); zip.write("// $id $version".toByteArray()); zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun described(id: String, version: String, bytes: ByteArray, hash: String = sha(bytes)) =
        ProviderPackage(id, id, version, "https://example.invalid/$id-$version.sflx", hash)

    private class Remembered : AcceptedPackages {
        val map = HashMap<String, ProviderPackage>()
        override fun get(providerId: String) = map[providerId]
        override fun put(accepted: ProviderPackage) { map[accepted.id] = accepted }
    }

    private class Server {
        val bytes = HashMap<String, ByteArray>()
        val requests = AtomicInteger()
        fun serve(item: ProviderPackage, content: ByteArray) { bytes[item.url] = content }
        fun download(item: ProviderPackage, target: File) {
            requests.incrementAndGet()
            target.writeBytes(bytes[item.url] ?: throw IOException("HTTP 404"))
        }
    }

    private fun snapshot(vararg packages: ProviderPackage) = RegistrySnapshot(packages.toList(), "today", "test registry")

    // -- Start now, ask the registry meanwhile ------------------------------------------------

    @Test fun aDownloadStartsOnTheProvidersAtHandBeforeASlowRegistryAnswers() {
        val server = Server()
        val tidal1 = packageBytes("tidal-web", "1.2.14").let { described("tidal-web", "1.2.14", it).also { p -> server.serve(p, it) } }
        val deezer1 = packageBytes("deezer", "1.3.11").let { described("deezer", "1.3.11", it).also { p -> server.serve(p, it) } }
        val tidal2 = packageBytes("tidal-web", "1.2.15").let { described("tidal-web", "1.2.15", it).also { p -> server.serve(p, it) } }
        val store = SpotiFlacPackageStore(folder.newFolder("packages"), Remembered(), emptyList())
        val startup = SpotiFlacProviderStartup(store, listOf("tidal-web", "deezer"))

        // A first run has nothing: it has to wait for the registry.
        assertTrue(startup.cached().packages.isEmpty())
        val first = startup.fromRegistry(snapshot(tidal1, deezer1), server::download)
        assertEquals(listOf(tidal1, deezer1), first.packages.map { it.accepted })
        assertEquals(2, server.requests.get())

        // Every later start: the registry is slow (it answers only when the latch opens).
        val registryMayAnswer = CountDownLatch(1)
        val registryAnswered = CountDownLatch(1)
        var refreshed: ProviderSet? = null
        val background = thread {
            registryMayAnswer.await()
            refreshed = startup.fromRegistry(snapshot(tidal2, deezer1), server::download)
            registryAnswered.countDown()
        }
        val running = startup.cached()
        assertEquals("the download has its providers", listOf(tidal1, deezer1), running.packages.map { it.accepted })
        assertEquals("without asking anyone", 2, server.requests.get())
        assertEquals("while the registry has not answered yet", 1L, registryAnswered.count)
        assertEquals(first.fingerprint, running.fingerprint)

        registryMayAnswer.countDown()
        assertTrue(registryAnswered.await(10, TimeUnit.SECONDS))
        background.join()
        val next = refreshed!!
        assertEquals("only the changed package was fetched", 3, server.requests.get())
        assertEquals(listOf(tidal2, deezer1), next.packages.map { it.accepted })
        assertNotEquals("a new set is a new runtime folder", running.fingerprint, next.fingerprint)
        // The running set's bytes are still in the store, untouched, while the new one waits.
        assertTrue(running.packages.all { it.file.isFile && sha(it.file.readBytes()) == it.accepted.sha256 })
        // The next start (or the next gap between downloads) takes the new set, again with no request.
        assertEquals(listOf(tidal2, deezer1), startup.cached().packages.map { it.accepted })
        assertEquals(3, server.requests.get())
    }

    @Test fun aWrongHashOrAGreedyPackageIsTurnedAwayAndTheLastGoodOneStays() {
        val server = Server()
        val good = packageBytes("tidal-web", "1.2.14").let { described("tidal-web", "1.2.14", it).also { p -> server.serve(p, it) } }
        val store = SpotiFlacPackageStore(folder.newFolder("packages"), Remembered(), emptyList())
        val startup = SpotiFlacProviderStartup(store, listOf("tidal-web"))
        startup.fromRegistry(snapshot(good), server::download)

        // The registry names one hash and the server sends other bytes.
        val swappedBytes = packageBytes("tidal-web", "1.2.15")
        val swapped = described("tidal-web", "1.2.15", swappedBytes, hash = "0".repeat(64))
        server.serve(swapped, swappedBytes)
        val afterSwap = startup.fromRegistry(snapshot(swapped), server::download)
        assertEquals(listOf(good), afterSwap.packages.map { it.accepted })
        assertTrue(afterSwap.notes.single().contains("SHA-256 mismatch"))

        // A package that asks to reach a host nobody reviewed.
        val greedyBytes = packageBytes("tidal-web", "1.2.16", extraHost = "collector.example")
        val greedy = described("tidal-web", "1.2.16", greedyBytes)
        server.serve(greedy, greedyBytes)
        val afterGreedy = startup.fromRegistry(snapshot(greedy), server::download)
        assertEquals(listOf(good), afterGreedy.packages.map { it.accepted })
        assertTrue(afterGreedy.notes.single().contains("collector.example"))
        assertEquals("what runs next is still the good one", listOf(good), startup.cached().packages.map { it.accepted })

        // A registry that cannot be reached for a provider at all, and nothing accepted: left out, not fatal.
        val unknown = described("deezer", "1.3.11", packageBytes("deezer", "1.3.11"))
        val partial = startup.fromRegistry(snapshot(good, unknown), server::download)
        assertEquals(listOf(good), partial.packages.map { it.accepted })
        assertTrue(partial.notes.any { it.contains("deezer") })
    }

    @Test fun eachRuntimeTakesOnlyThePackagesItCanLoad() {
        // The registry as it is today: Qobuz 1.2.27 needs runtime 5.3.0; the others 4.9.x.
        fun entry(id: String, file: String, version: String, minApp: String) = JSONObject()
            .put("id", id).put("category", "download").put("version", version).put("sha256", "a".repeat(64))
            .put("min_app_version", minApp)
            .put("download_url", "https://raw.githubusercontent.com/mornyeapp/Mornye-Extension/main/extensions/$file")
        val registry = JSONObject().put("extensions", JSONArray(listOf(
            entry("tidal-web", "tidal-web.sflx", "1.2.14", "4.9.0"),
            entry("qobuz-web", "qobuz-web.sflx", "1.2.27", "5.3.0"),
            entry("deezer", "deezer.sflx", "1.3.11", "4.9.1"),
            entry("amazon", "amzn.sflx", "2.3.19", "4.9.1"),
        ))).toString()
        fun versions(runtime: String) = SpotiFlacProviderRegistry.parse(registry, runtime).packages.associate { it.id to it.version }
        val pinnedQobuz = SpotiFlacProviderRegistry.fallbackPackages.first { it.id == "qobuz-web" }.version

        // The Mornye APK (Mornye 5.3.1): everything current.
        assertEquals(mapOf("tidal-web" to "1.2.14", "qobuz-web" to "1.2.27", "deezer" to "1.3.11", "amazon" to "2.3.19"), versions("5.3.1"))
        // The stable APK (SpotiFLAC 4.9.6): Qobuz stays on the pinned package it can load.
        assertEquals(mapOf("tidal-web" to "1.2.14", "qobuz-web" to pinnedQobuz, "deezer" to "1.3.11", "amazon" to "2.3.19"), versions("4.9.6"))
        // And a package for a runtime newer than either is left out of both.
        val future = JSONObject().put("extensions", JSONArray(listOf(entry("tidal-web", "tidal-web.sflx", "9.0.0", "6.0.0")))).toString()
        val pinnedTidal = SpotiFlacProviderRegistry.fallbackPackages.first { it.id == "tidal-web" }.version
        for (runtime in listOf("4.9.6", "5.3.1")) {
            assertEquals(pinnedTidal, SpotiFlacProviderRegistry.parse(future, runtime).packages.first { it.id == "tidal-web" }.version)
        }
    }

    // -- Sessions across an update ------------------------------------------------------------

    @Test fun updatingOneProviderKeepsTheSessionOfAnother() {
        val base = folder.newFolder("runtime-v1")
        val oldData = File(base, "aaaaaaaaaaaaaaaa/data")
        File(oldData, "tidal-web").mkdirs()
        File(oldData, "tidal-web/storage.json").writeText("tidal signed session")
        File(oldData, "tidal-web/.cred_salt").writeText("salt")
        File(oldData, "deezer").mkdirs()
        File(oldData, "deezer/storage.json").writeText("deezer session")
        val newData = File(base, "bbbbbbbbbbbbbbbb/data")

        // Deezer's package changed; Tidal's did not.
        val carried = SpotiFlacRuntimeLayout.carryOver(oldData, newData, unchangedIds = listOf("tidal-web"))
        assertEquals(listOf("tidal-web"), carried)
        assertEquals("tidal signed session", File(newData, "tidal-web/storage.json").readText())
        assertEquals("salt", File(newData, "tidal-web/.cred_salt").readText())
        assertFalse("the updated provider starts clean", File(newData, "deezer").exists())
        // The previous folder is whole: going back to it loses nothing.
        assertEquals("tidal signed session", File(oldData, "tidal-web/storage.json").readText())
        assertEquals("deezer session", File(oldData, "deezer/storage.json").readText())

        // Never over data the new runtime already wrote, never an id that is not a folder name.
        File(newData, "tidal-web/storage.json").writeText("newer session")
        assertTrue(SpotiFlacRuntimeLayout.carryOver(oldData, newData, listOf("tidal-web", "../deezer", "", ".")).isEmpty())
        assertEquals("newer session", File(newData, "tidal-web/storage.json").readText())
        assertFalse(File(base, "bbbbbbbbbbbbbbbb/deezer").exists())

        // Older runtime folders go; the running one, the one before it and the package store stay.
        File(base, "cccccccccccccccc/data").mkdirs()
        File(base, "packages").mkdirs()
        SpotiFlacRuntimeLayout.prune(base, keep = setOf("bbbbbbbbbbbbbbbb", "aaaaaaaaaaaaaaaa"))
        assertEquals(setOf("aaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbb", "packages"), base.list()!!.toSet())
    }

    // -- Which file a job may hand on --------------------------------------------------------

    @Test fun onlyAJobsOwnRegularFilesAreItsOutput() {
        val staging = folder.newFolder("staging")
        val jobA = File(staging, "job-a").apply { mkdirs() }
        val jobB = File(staging, "job-b").apply { mkdirs() }
        val mine = File(jobB, "Artist - Title.flac").apply { writeText("fLaC mine") }
        val theirs = File(jobA, "Artist - Title.flac").apply { writeText("fLaC theirs") }
        val private = File(folder.newFolder("databases"), "harmony.db").apply { writeText("private") }

        assertEquals(mine.canonicalFile, SpotiFlacJobFiles.owned(mine, jobB))
        assertNull("a file of another job", SpotiFlacJobFiles.owned(theirs, jobB))
        assertNull("a path that climbs out", SpotiFlacJobFiles.owned(File(jobB, "../job-a/Artist - Title.flac"), jobB))
        assertNull("an app file", SpotiFlacJobFiles.owned(private, jobB))
        assertNull("the folder itself", SpotiFlacJobFiles.owned(jobB, jobB))
        assertNull("a folder", SpotiFlacJobFiles.owned(File(jobB, "sub").apply { mkdirs() }, jobB))
        assertNull("an empty file", SpotiFlacJobFiles.owned(File(jobB, "empty.flac").apply { createNewFile() }, jobB))
        assertNull("a file that is not there", SpotiFlacJobFiles.owned(File(jobB, "missing.flac"), jobB))

        // Links: where the bytes really are is what counts.
        val linkOut = File(jobB, "leak.flac")
        Files.createSymbolicLink(linkOut.toPath(), private.toPath())
        assertNull("a link out of the staging folder", SpotiFlacJobFiles.owned(linkOut, jobB))
        val linkToOtherJob = File(jobB, "other.flac")
        Files.createSymbolicLink(linkToOtherJob.toPath(), theirs.toPath())
        assertNull("a link to another job's file", SpotiFlacJobFiles.owned(linkToOtherJob, jobB))
        val folderLink = File(jobB, "elsewhere")
        Files.createSymbolicLink(folderLink.toPath(), jobA.toPath())
        assertNull("a file reached through a linked folder", SpotiFlacJobFiles.owned(File(folderLink, "Artist - Title.flac"), jobB))
        val linkInside = File(jobB, "alias.flac")
        Files.createSymbolicLink(linkInside.toPath(), mine.toPath())
        assertEquals("a link to the job's own file is that file", mine.canonicalFile, SpotiFlacJobFiles.owned(linkInside, jobB))

        // Looking through the folder follows the same rule (and returns each file once).
        File(jobB, "sub/inner.flac").writeText("fLaC inner")
        assertEquals(
            setOf(mine.canonicalFile, File(jobB, "sub/inner.flac").canonicalFile),
            SpotiFlacJobFiles.all(jobB).toSet(),
        )
        assertEquals(2, SpotiFlacJobFiles.all(jobB).size)
    }

    // -- Covers ------------------------------------------------------------------------------

    /** A scripted web: what each URL answers, and every URL anything connected to. */
    private class Web(private val pages: Map<String, Page>) {
        class Page(val status: Int, val location: String? = null, val body: ByteArray = ByteArray(0), val type: String? = "image/jpeg",
                   val advertised: Long? = null)
        val connected = mutableListOf<String>()
        fun open(url: String): SpotiFlacArtworkFetcher.Exchange {
            synchronized(connected) { connected += url }
            val page = pages[url] ?: Page(404)
            return object : SpotiFlacArtworkFetcher.Exchange {
                override val status = page.status
                override fun header(name: String): String? = when (name.lowercase()) {
                    "location" -> page.location
                    "content-type" -> page.type
                    "content-length" -> (page.advertised ?: page.body.size.toLong()).toString()
                    else -> null
                }
                override fun body(): InputStream = ByteArrayInputStream(page.body)
                override fun close() = Unit
            }
        }
    }

    private val public = { _: String -> listOf(InetAddress.getByName("93.184.216.34")) }

    private fun fetcher(web: Web, maxBytes: Long = 1_000, resolve: (String) -> List<InetAddress> = public) =
        SpotiFlacArtworkFetcher(maxBytes = maxBytes, maxRedirects = 3, cacheEntries = 3, resolve = resolve, open = web::open)

    @Test fun aCoverRedirectedToALocalAddressIsRefusedBeforeAnythingConnectsToIt() {
        val cover = "https://cdn.example.com/cover.jpg"
        for (target in listOf(
            "https://192.168.1.1/admin", "https://127.0.0.1/x", "https://[::1]/x", "https://router.lan/x",
            "http://cdn.example.com/cover.jpg", "https://169.254.169.254/latest/meta-data",
        )) {
            val web = Web(mapOf(cover to Web.Page(302, location = target), target to Web.Page(200, body = "local".toByteArray())))
            assertNull(target, fetcher(web).fetch(cover))
            assertEquals("only the first, public URL was connected to", listOf(cover), web.connected)
        }
        // A public name that resolves to a private address is local too.
        val rebind = "https://images.example.net/cover.jpg"
        val web = Web(mapOf(cover to Web.Page(302, location = rebind), rebind to Web.Page(200, body = "local".toByteArray())))
        val resolve = { host: String -> listOf(InetAddress.getByName(if (host == "images.example.net") "10.0.0.7" else "93.184.216.34")) }
        assertNull(fetcher(web, resolve = resolve).fetch(cover))
        assertEquals(listOf(cover), web.connected)
        // And a first URL that is local is never connected to at all.
        val direct = Web(mapOf("https://192.168.1.1/cover.jpg" to Web.Page(200, body = "x".toByteArray())))
        assertNull(fetcher(direct).fetch("https://192.168.1.1/cover.jpg"))
        assertTrue(direct.connected.isEmpty())
    }

    @Test fun aCoverIsFollowedThroughPublicRedirectsWithinItsLimits() {
        val a = "https://cdn.example.com/cover.jpg"
        val b = "https://img.example.org/c/1000.jpg"
        val web = Web(mapOf(a to Web.Page(301, location = "//img.example.org/c/1000.jpg"), b to Web.Page(200, body = ByteArray(900) { 7 })))
        val cover = fetcher(web).fetch(a)
        assertNotNull(cover)
        assertEquals(900, cover!!.bytes.size)
        assertEquals("image/jpeg", cover.contentType)
        assertEquals(listOf(a, b), web.connected)

        // Too large, whether the server says so or not.
        val honest = Web(mapOf(a to Web.Page(200, body = ByteArray(2_000))))
        assertNull(fetcher(honest).fetch(a))
        val lying = Web(mapOf(a to Web.Page(200, body = ByteArray(2_000), advertised = 10)))
        assertNull(fetcher(lying).fetch(a))
        // A redirect loop ends.
        val loop = Web(mapOf(a to Web.Page(302, location = b), b to Web.Page(302, location = a)))
        assertNull(fetcher(loop).fetch(a))
        assertEquals(4, loop.connected.size)
        // An error page, an empty body.
        assertNull(fetcher(Web(mapOf(a to Web.Page(503)))).fetch(a))
        assertNull(fetcher(Web(mapOf(a to Web.Page(200)))).fetch(a))
    }
}
