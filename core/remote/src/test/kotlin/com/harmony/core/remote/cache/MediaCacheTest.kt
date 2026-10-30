package com.harmony.core.remote.cache

import com.harmony.core.remote.FileRangeSource
import com.harmony.core.remote.HttpResponse
import com.harmony.core.remote.MiniHttpServer
import com.harmony.core.remote.RangeSource
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

class MediaCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    /** The phone's file server, as far as the cache can tell: ranges, validators, and a cable to pull. */
    private inner class Phone : AutoCloseable {
        val files = HashMap<String, File>()
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val served = AtomicLong()
        /** The next transfer breaks after this many bytes (once). */
        @Volatile var cutAfter = -1L
        private val server = MiniHttpServer(0, "test-phone") { req ->
            requests += "${req.path} range=${req.header("Range")} if-range=${req.header("If-Range")}"
            val file = files[req.path.removePrefix("/")] ?: return@MiniHttpServer HttpResponse.text(404, "no")
            HttpResponse.ranged(Counting(FileRangeSource(file)), req.header("Range"), "audio/flac", req.header("If-Range"), req.header("If-None-Match"))
        }.start()
        val port get() = server.boundPort
        fun url(name: String) = "http://127.0.0.1:$port/$name"

        fun song(name: String, size: Int, seed: Int = name.hashCode()): ByteArray {
            val bytes = Random(seed).nextBytes(size)
            val f = tmp.newFile("$name-${System.nanoTime()}")
            f.writeBytes(bytes)
            files[name] = f
            return bytes
        }

        private inner class Counting(private val src: FileRangeSource) : RangeSource {
            override val length get() = src.length
            override val etag get() = src.etag
            override fun open(offset: Long): InputStream {
                val input = src.open(offset)
                val cut = cutAfter.also { cutAfter = -1 }
                return object : InputStream() {
                    var sent = 0L
                    override fun read(): Int = throw UnsupportedOperationException()
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (cut >= 0 && sent >= cut) throw IOException("cable pulled")
                        val max = if (cut >= 0) minOf(len.toLong(), cut - sent).toInt().coerceAtLeast(1) else len
                        val n = input.read(b, off, max)
                        if (n > 0) { sent += n; served.addAndGet(n.toLong()) }
                        return n
                    }
                    override fun close() = input.close()
                }
            }
        }

        override fun close() = server.close()
    }

    private val closing = mutableListOf<AutoCloseable>()
    @After fun tearDown() = closing.asReversed().forEach { runCatching { it.close() } }

    private fun phone() = Phone().also { closing += it }

    private fun cache(
        dir: File = tmp.newFolder(),
        maxBytes: Long = 100L * 1024 * 1024,
        rate: Long = 0,
        space: () -> Long = { 10L * 1024 * 1024 * 1024 },
    ) = MediaCache(dir, { CacheLimits(maxBytes, prefetchBytesPerSecond = rate) }, space, log = { println(it) }).also { closing += it }

    private fun waitFor(ms: Long, what: String, test: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!test()) {
            if (System.currentTimeMillis() > end) throw AssertionError("not within $ms ms: $what")
            Thread.sleep(10)
        }
    }

    private fun MediaCache.has(key: String) = localFor(key) != null
    private fun MediaCache.bytes(key: String) = File(localFor(key)!!).readBytes()

    @Test fun theSongPlayingAndTheNextAreFetchedWholeInThatOrder() {
        val p = phone()
        val a = p.song("a", 300_000)
        val b = p.song("b", 200_000)
        val c = cache()
        c.want(listOf(CacheWant("k:b", p.url("b"), 1), CacheWant("k:a", p.url("a"), 0)))
        waitFor(5_000, "both") { c.has("k:a") && c.has("k:b") }
        assertArrayEquals(a, c.bytes("k:a"))
        assertArrayEquals(b, c.bytes("k:b"))
        assertTrue("the song playing first: ${p.requests}", p.requests.first().startsWith("/a "))
    }

    @Test fun aCutTransferResumesWhereItStopped() {
        val p = phone()
        val song = p.song("a", 2_000_000)
        p.cutAfter = 700_000
        val c = cache()
        c.want(listOf(CacheWant("k:a", p.url("a"), 0)))
        waitFor(10_000, "resumed and complete") { c.has("k:a") }
        assertArrayEquals(song, c.bytes("k:a"))
        assertTrue("the second request asks for the rest: ${p.requests}", p.requests.last().contains("range=bytes=700000-"))
        assertTrue("with the validator: ${p.requests}", p.requests.last().contains("if-range=\""))
        assertTrue("fetched about once: ${c.downloadedBytes}", c.downloadedBytes < song.size * 1.1)
    }

    @Test fun aFileChangedBetweenTriesComesWholeNotGlued() {
        val p = phone()
        p.song("a", 1_000_000, seed = 1)
        p.cutAfter = 400_000
        val c = cache()
        c.want(listOf(CacheWant("k:a", p.url("a"), 0)))
        waitFor(5_000, "the cut") { p.requests.size >= 1 && p.served.get() >= 400_000 }
        // Re-tagged on the phone meanwhile (before the cache tries again): other bytes, another size.
        val newer = p.song("a", 1_000_100, seed = 2)
        waitFor(10_000, "complete") { c.has("k:a") }
        assertArrayEquals(newer, c.bytes("k:a"))
    }

    @Test fun theSameSongTwiceIsOneCopyFetchedOnce() {
        val p = phone()
        p.song("x", 300_000)
        val c = cache()
        c.want(listOf(CacheWant("k:x", p.url("x"), 0), CacheWant("k:x", p.url("x"), 2)))
        waitFor(5_000, "cached") { c.has("k:x") }
        Thread.sleep(200)
        assertEquals(1, p.requests.size)
        assertEquals(1, tmp.root.walk().count { it.name.endsWith(".audio") })
    }

    @Test fun aMoreUrgentSongTakesOverAndTheOtherResumesLater() {
        val p = phone()
        val big = p.song("big", 4_000_000)
        val now = p.song("now", 200_000)
        val c = cache(rate = 1_000_000) // ahead-of-time downloads at 1 MB/s
        c.want(listOf(CacheWant("k:big", p.url("big"), 2)))
        waitFor(3_000, "under way") { p.served.get() > 500_000 }
        // Skip: another song is playing now and isn't here.
        c.want(listOf(CacheWant("k:now", p.url("now"), 0), CacheWant("k:big", p.url("big"), 2)))
        waitFor(3_000, "the song playing first") { c.has("k:now") }
        assertNull("the other one isn't done yet", c.localFor("k:big"))
        waitFor(10_000, "then the other one, resumed") { c.has("k:big") }
        assertArrayEquals(now, c.bytes("k:now"))
        assertArrayEquals(big, c.bytes("k:big"))
        // What the cache took in (the server may have pushed more into the socket's buffers before it let go).
        assertTrue("resumed, not refetched: ${c.downloadedBytes}", c.downloadedBytes < (big.size + now.size) * 1.15)
    }

    @Test fun downloadsAheadOfTimeKeepToTheirRate() {
        val p = phone()
        p.song("next", 3_000_000)
        val c = cache(rate = 2_000_000)
        val t0 = System.nanoTime()
        c.want(listOf(CacheWant("k:next", p.url("next"), 1)))
        waitFor(10_000, "cached") { c.has("k:next") }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("3 MB at 2 MB/s took $ms ms", ms >= 1_300)
        // The song playing isn't held back.
        p.song("now", 3_000_000)
        val t1 = System.nanoTime()
        c.want(listOf(CacheWant("k:now", p.url("now"), 0)))
        waitFor(10_000, "cached") { c.has("k:now") }
        val ms2 = (System.nanoTime() - t1) / 1_000_000
        assertTrue("the song playing at full speed: $ms2 ms", ms2 < 1_300)
    }

    @Test fun theLimitRemovesTheLeastRecentlyUsedNeverTheSongPlayingOrTheNext() {
        val p = phone()
        val c = cache(maxBytes = 1_000_000)
        for (n in listOf("old1", "old2", "old3")) p.song(n, 300_000)
        for (n in listOf("old1", "old2", "old3")) {
            c.want(listOf(CacheWant("k:$n", p.url(n), 0)))
            waitFor(5_000, n) { c.has("k:$n") }
            Thread.sleep(5)
        }
        c.localFor("k:old1") // played again: the most recent now
        p.song("now", 300_000)
        p.song("next", 300_000)
        c.want(listOf(CacheWant("k:now", p.url("now"), 0), CacheWant("k:next", p.url("next"), 1)))
        waitFor(5_000, "both") { c.has("k:now") && c.has("k:next") }
        assertTrue("within the limit: ${c.usage()}", c.usage() <= 1_000_000)
        assertNull("least recently used went", c.localFor("k:old2"))
        assertNotNull("played again: kept", c.localFor("k:old1"))
    }

    @Test fun noRoomMeansTheSongPlaysFromTheNetwork() {
        val p = phone()
        p.song("huge", 2_000_000)
        val c = cache(space = { 5_000_000 }) // 10% of 5 MB is too little
        c.want(listOf(CacheWant("k:huge", p.url("huge"), 0)))
        Thread.sleep(800)
        assertNull(c.localFor("k:huge"))
        assertEquals("nothing half-written left behind", 0L, c.usage())
    }

    @Test fun anUnplayableCopyIsFetchedOnceMoreThenLeftToTheNetwork() {
        val p = phone()
        val song = p.song("a", 200_000)
        val c = cache()
        c.want(listOf(CacheWant("k:a", p.url("a"), 0)))
        waitFor(5_000, "cached") { c.has("k:a") }
        c.corrupt("k:a")
        assertNull(c.localFor("k:a"))
        waitFor(5_000, "fetched again") { c.has("k:a") }
        assertArrayEquals(song, c.bytes("k:a"))
        c.corrupt("k:a")
        Thread.sleep(500)
        assertNull("not a third time", c.localFor("k:a"))
        assertEquals(2, p.requests.size)
    }

    @Test fun whatWasCachedIsStillThereAfterARestartAndAPartialResumes() {
        val p = phone()
        val done = p.song("done", 300_000)
        val half = p.song("half", 2_000_000)
        val dir = tmp.newFolder()
        val first = cache(dir)
        first.want(listOf(CacheWant("k:done", p.url("done"), 0)))
        waitFor(5_000, "cached") { first.has("k:done") }
        p.cutAfter = 900_000
        first.want(listOf(CacheWant("k:half", p.url("half"), 0)))
        waitFor(5_000, "cut") { p.served.get() >= 300_000 + 900_000 }
        Thread.sleep(200)
        first.close()
        // The app starts again.
        val before = p.requests.size
        val second = cache(dir)
        assertArrayEquals("no network needed", done, second.bytes("k:done"))
        second.want(listOf(CacheWant("k:half", p.url("half"), 0)))
        waitFor(10_000, "resumed") { second.has("k:half") }
        assertArrayEquals(half, second.bytes("k:half"))
        assertTrue("resumed from where it was: ${p.requests.drop(before)}", p.requests.drop(before).any { it.contains("range=bytes=") })
    }

    @Test fun aFileGoneFromTheDiskIsNotServed() {
        val p = phone()
        p.song("a", 100_000)
        val dir = tmp.newFolder()
        val c = cache(dir)
        c.want(listOf(CacheWant("k:a", p.url("a"), 0)))
        waitFor(5_000, "cached") { c.has("k:a") }
        File(c.localFor("k:a")!!).writeBytes(ByteArray(10)) // truncated behind our back
        assertNull(c.localFor("k:a"))
    }
}
