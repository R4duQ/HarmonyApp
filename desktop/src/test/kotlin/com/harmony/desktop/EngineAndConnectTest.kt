package com.harmony.desktop

import com.harmony.core.remote.ConnectClient
import com.harmony.core.remote.ControlAction
import com.harmony.core.remote.ControlRequest
import com.harmony.core.remote.FileRangeSource
import com.harmony.core.remote.HttpResponse
import com.harmony.core.remote.MiniHttpServer
import com.harmony.core.remote.PlayRequest
import com.harmony.core.remote.RemoteRequest
import com.harmony.core.remote.RemoteState
import com.harmony.core.remote.RemoteTrack
import com.harmony.desktop.connect.ConnectHost
import com.harmony.desktop.engine.AudioEngine
import com.harmony.desktop.engine.CountingSink
import com.harmony.desktop.engine.EngineStatus
import com.harmony.desktop.engine.EqConfig
import com.harmony.desktop.engine.FfmpegTools
import com.harmony.desktop.library.Ffprobe
import com.harmony.desktop.library.LibraryScanner
import com.harmony.desktop.library.LocalTrack
import com.harmony.desktop.player.PlayerController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.DatagramSocket
import java.util.Collections

class EngineAndConnectTest {
    companion object {
        private lateinit var dir: File
        private lateinit var flac: File
        private lateinit var mp3: File

        private fun ffmpegAvailable() = runCatching {
            ProcessBuilder(FfmpegTools.ffmpeg, "-version").start().waitFor() == 0
        }.getOrDefault(false)

        private fun make(out: File, seconds: Int, codecArgs: List<String>, title: String) {
            val p = ProcessBuilder(
                listOf(FfmpegTools.ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=$seconds") +
                    codecArgs + listOf("-metadata", "title=$title", "-metadata", "artist=Test Artist", "-metadata", "album=Test Album", "-metadata", "track=3/9", out.absolutePath),
            ).redirectErrorStream(true).start()
            p.inputStream.readBytes()
            check(p.waitFor() == 0) { "couldn't make $out" }
        }

        @BeforeClass @JvmStatic fun makeFiles() {
            assumeTrue("ffmpeg is needed: pass -PffmpegDir", ffmpegAvailable())
            dir = createTempDir("harmony-desktop-test")
            flac = File(dir, "tone.flac").also { make(it, 3, listOf("-c:a", "flac"), "Tone Song") }
            mp3 = File(dir, "sub/tone.mp3").also { it.parentFile.mkdirs(); make(it, 2, listOf("-c:a", "libmp3lame", "-b:a", "192k"), "Short Tone") }
        }
    }

    private fun waitFor(timeoutMs: Long = 10_000, what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (check()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test fun `ffprobe reads tags and format`() {
        val t = Ffprobe.read(flac)
        assertNotNull(t)
        t!!
        assertEquals("Tone Song", t.title)
        assertEquals("Test Artist", t.artist)
        assertEquals("Test Album", t.album)
        assertEquals(3, t.trackNumber)
        assertEquals("flac", t.codec)
        assertTrue(t.lossless)
        assertEquals(3_000.0, t.durationMs.toDouble(), 60.0)
        assertEquals("FLAC 16/44.1", t.quality)
        val m = Ffprobe.read(mp3)!!
        assertEquals("mp3", m.codec)
        assertTrue(m.quality.startsWith("MP3 19"))
    }

    @Test fun `the scanner finds songs in subfolders and reuses what it knows`() {
        var reads = 0
        val scanner = LibraryScanner { f -> reads++; Ffprobe.read(f) }
        val first = scanner.scan(listOf(dir), emptyMap())
        assertEquals(setOf("Tone Song", "Short Tone"), first.map { it.title }.toSet())
        assertEquals(2, reads)
        val again = scanner.scan(listOf(dir), first.associateBy { it.path })
        assertEquals(2, again.size)
        assertEquals("nothing changed, nothing re-read", 2, reads)
    }

    @Test fun `the engine plays a song to its end and seeks`() {
        val sink = CountingSink()
        val engine = AudioEngine({ sink })
        var ended = 0
        engine.onEnded = { ended++ }
        engine.load(flac.absolutePath, 0, play = true, durationMs = 3_000)
        waitFor(what = "the end") { engine.state.value.status == EngineStatus.ENDED }
        assertEquals(1, ended)
        assertEquals(3.0 * 48_000, sink.frames.toDouble(), 48_000 * 0.05)

        val sink2 = CountingSink()
        val engine2 = AudioEngine({ sink2 })
        engine2.load(flac.absolutePath, 2_000, play = true, durationMs = 3_000)
        waitFor(what = "the end after a seek") { engine2.state.value.status == EngineStatus.ENDED }
        assertEquals(1.0 * 48_000, sink2.frames.toDouble(), 48_000 * 0.05)
    }

    @Test fun `the equalizer changes the sound and keeps it from clipping`() {
        fun peak(eq: EqConfig): Int {
            val sink = CountingSink()
            val engine = AudioEngine({ sink })
            engine.eq = eq
            engine.volume = 1f
            engine.load(flac.absolutePath, 0, play = true)
            waitFor(what = "the end") { engine.state.value.status == EngineStatus.ENDED }
            val bytes = synchronized(sink.written) { sink.written.toByteArray() }
            var max = 0
            var i = 48_000 * 4 // skip the first second, while the filters settle
            while (i + 1 < bytes.size) {
                val v = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort().toInt()
                max = maxOf(max, kotlin.math.abs(v))
                i += 2
            }
            return max
        }
        val flat = peak(EqConfig(enabled = false))
        // 440 Hz sits between the 310 and 600 Hz bands: lift them both a lot.
        val lifted = peak(EqConfig(enabled = true, winampGainsDb = listOf(0f, 0f, 12f, 12f, 0f, 0f, 0f, 0f, 0f, 0f)))
        assertTrue("flat $flat lifted $lifted", lifted > flat * 1.5)
        assertTrue("never past full scale", lifted <= 32767)
    }

    @Test fun `a phone pairs, plays a song over the network, and stays in step`() {
        // The phone's side: serving the file, with ranges.
        val ranges = Collections.synchronizedList(mutableListOf<String?>())
        val phoneFiles = MiniHttpServer(0, "phone-files") { req ->
            ranges += req.header("Range")
            if (req.path == "/track/7" && req.query["t"] == "secret") HttpResponse.ranged(FileRangeSource(flac), req.header("Range"), "audio/flac")
            else HttpResponse.text(403, "no")
        }.start()
        val sink = CountingSink(realTime = true)
        val engine = AudioEngine({ sink })
        val player = PlayerController(engine)
        val udp = DatagramSocket().use { it.localPort }
        var saved: Map<String, String> = emptyMap()
        val host = ConnectHost("pc-1", { "Test PC" }, player, emptyMap(), { saved = it }, httpPort = 0, discoveryPort = udp)
        host.start()
        try {
            val state = host.state.value
            assertTrue(state.running)
            val client = ConnectClient("127.0.0.1", state.port)
            assertEquals("Test PC", client.info().name)
            client.pair(state.code, "Pixel 8")
            assertEquals(listOf("Pixel 8"), saved.values.toList())

            val track = RemoteTrack("7", "Tone Song", "Test Artist", "Test Album", 3_000, "http://127.0.0.1:${phoneFiles.boundPort}/track/7?t=secret", null, "FLAC 16/44.1")
            client.play(PlayRequest(track, 500, playing = true, phoneName = "Pixel 8"))
            waitFor(what = "playing") { client.status().state == RemoteState.PLAYING }
            assertEquals("Pixel 8", player.phone.value)
            assertEquals("Pixel 8", player.nowPlaying.value?.fromPhone)
            Thread.sleep(400)
            val playing = client.status()
            assertEquals("7", playing.trackId)
            assertTrue("position moves on: ${playing.positionMs}", playing.positionMs in 600..1_600)

            client.control(ControlRequest(ControlAction.PAUSE))
            waitFor(what = "paused") { client.status().state == RemoteState.PAUSED }
            val pausedAt = client.status().positionMs
            Thread.sleep(300)
            assertEquals("paused stays put", pausedAt.toDouble(), client.status().positionMs.toDouble(), 30.0)

            // Next pressed on the computer goes to the phone, which owns the queue.
            player.next()
            assertEquals(listOf(RemoteRequest.NEXT), client.status().requests)

            client.control(ControlRequest(ControlAction.SEEK, 2_400))
            client.control(ControlRequest(ControlAction.PLAY))
            waitFor(what = "the end") { client.status().state == RemoteState.ENDED }
            assertTrue("ffmpeg asked for byte ranges: $ranges", ranges.any { it != null && it.startsWith("bytes=") })

            client.disconnect()
            assertEquals(null, player.phone.value)
            assertEquals(RemoteState.IDLE, client.status().state)
        } finally {
            host.close()
            phoneFiles.close()
            engine.stop()
        }
    }

    @Test fun `the local queue moves on by itself, and repeat and shuffle behave`() {
        val tracks = listOf(
            LocalTrack(flac.absolutePath, "A", "X", "Y", durationMs = 3_000),
            LocalTrack(mp3.absolutePath, "B", "X", "Y", durationMs = 2_000),
        )
        val engine = AudioEngine({ CountingSink() })
        val player = PlayerController(engine)
        player.playLocal(tracks, 0)
        assertEquals("B", player.nowPlaying.value?.upNext?.single()?.title)
        waitFor(what = "the second song") { player.index.value == 1 }
        waitFor(what = "the end of the queue") { engine.state.value.status == EngineStatus.ENDED }
        assertEquals(1, player.index.value)
        player.cycleRepeat() // ALL
        player.next()
        assertEquals("wraps round", 0, player.index.value)
        engine.stop()
    }
}
