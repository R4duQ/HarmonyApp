package com.harmony.core.remote

import com.harmony.core.remote.cache.CacheLimits
import com.harmony.core.remote.cache.CacheWant
import com.harmony.core.remote.cache.MediaCache
import com.harmony.core.remote.cache.SocketFetcher
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** The computer's songs for a phone (Universal Queue, stage 6): the route, as the phone's cache fetches it. */
class PcMediaRouteTest {
    @get:Rule val tmp = TemporaryFolder()
    private val closers = mutableListOf<AutoCloseable>()
    @After fun tearDown() = closers.asReversed().forEach { runCatching { it.close() } }

    private object Silent : RemoteRenderer {
        override fun play(request: PlayRequest) {}
        override fun control(request: ControlRequest) {}
        override fun setVolume(volume: Float) {}
        override fun status() = RemoteStatus(RemoteState.IDLE, null, 0, 0, 1f)
        override fun disconnect(phoneName: String?) {}
    }

    private fun server(media: (String) -> ServedMedia): MiniHttpServer {
        val receiver = ConnectReceiver({ PcInfo("pc-1", "PC", 0) }, Pairing(mapOf("tok" to "Pixel")), Silent, media = media)
        return MiniHttpServer(0, "test-media", receiver::handle).start().also { closers += it }
    }

    private fun get(url: String, range: String? = null): Pair<Int, ByteArray> =
        SocketFetcher().get(url, range?.removePrefix("bytes=")?.removeSuffix("-")?.toLong() ?: 0, null).use { r ->
            r.status to r.body.readBytes()
        }

    @Test fun aPairedPhoneGetsTheSongWithRangesAndOthersGetNothing() {
        val bytes = Random(1).nextBytes(100_000)
        val file = tmp.newFile("a.flac").apply { writeBytes(bytes) }
        val asked = mutableListOf<String>()
        val s = server { id -> asked += id; if (id == "abc") ServedMedia.Ready(FileRangeSource(file), "audio/flac") else ServedMedia.Missing }
        val port = s.boundPort
        assertEquals(200, get(Connect.mediaUrl("127.0.0.1", port, "abc", "tok")).first)
        assertArrayEquals(bytes, get(Connect.mediaUrl("127.0.0.1", port, "abc", "tok")).second)
        val (status, tail) = get(Connect.mediaUrl("127.0.0.1", port, "abc", "tok"), "bytes=60000-")
        assertEquals(206, status)
        assertArrayEquals(bytes.copyOfRange(60_000, bytes.size), tail)
        assertEquals("not paired", 403, get(Connect.mediaUrl("127.0.0.1", port, "abc", "nope")).first)
        assertEquals(403, get("http://127.0.0.1:$port/media/abc").first)
        assertEquals(404, get(Connect.mediaUrl("127.0.0.1", port, "zzz", "tok")).first)
        assertEquals("the unpaired never reached the library", listOf("abc", "abc", "abc", "zzz"), asked)
    }

    @Test fun aComputerWithoutSongsToOfferSays404() {
        val receiver = ConnectReceiver({ PcInfo("pc-1", "PC", 0) }, Pairing(mapOf("tok" to "Pixel")), Silent)
        val s = MiniHttpServer(0, "test-media", receiver::handle).start().also { closers += it }
        assertEquals(404, get(Connect.mediaUrl("127.0.0.1", s.boundPort, "abc", "tok")).first)
    }

    @Test fun aSongStillBeingConvertedIsFetchedOnceItIsReady() {
        val bytes = Random(2).nextBytes(300_000)
        val file = tmp.newFile("a.flac").apply { writeBytes(bytes) }
        val calls = AtomicInteger()
        // Converting for the first two asks.
        val s = server { if (calls.incrementAndGet() <= 2) ServedMedia.Preparing(1) else ServedMedia.Ready(FileRangeSource(file), "audio/flac") }
        val first = get(Connect.mediaUrl("127.0.0.1", s.boundPort, "x", "tok"))
        assertEquals(503, first.first)
        val cache = MediaCache(tmp.newFolder(), { CacheLimits(100L shl 20) }, { 10L shl 30 }, log = { println(it) }).also { closers += it }
        cache.want(listOf(CacheWant("pc:pc-1:x", Connect.mediaUrl("127.0.0.1", s.boundPort, "x", "tok"), 0)))
        val end = System.currentTimeMillis() + 15_000
        while (cache.localFor("pc:pc-1:x") == null) {
            assertTrue("cached once converted", System.currentTimeMillis() < end)
            Thread.sleep(20)
        }
        assertArrayEquals(bytes, File(cache.localFor("pc:pc-1:x")!!).readBytes())
    }

    @Test fun keysNameTheComputerAndTheSong() {
        val key = Connect.pcKey("pc-1", "abc")
        assertEquals("abc", Connect.pcSongOf(key, "pc-1"))
        assertEquals(null, Connect.pcSongOf(key, "pc-2"))
        assertEquals(null, Connect.pcSongOf("dev:phone:42", "pc-1"))
    }
}
