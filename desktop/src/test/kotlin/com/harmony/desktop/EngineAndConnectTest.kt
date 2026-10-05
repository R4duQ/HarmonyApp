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
        private lateinit var low: File
        private lateinit var quiet: File
        private lateinit var stereo: File

        private fun ffmpegAvailable() = runCatching {
            ProcessBuilder(FfmpegTools.ffmpeg, "-version").start().waitFor() == 0
        }.getOrDefault(false)

        private fun make(out: File, seconds: Int, codecArgs: List<String>, title: String, hz: Int = 440, volume: String = "1.0") {
            val p = ProcessBuilder(
                listOf(FfmpegTools.ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=$hz:sample_rate=44100:duration=$seconds") +
                    (if (volume != "1.0") listOf("-af", "volume=$volume") else emptyList()) +
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
            // Apart from the library folder the scanner test reads.
            val extra = createTempDir("harmony-desktop-extra")
            low = File(extra, "low.wav").also { make(it, 3, listOf("-c:a", "pcm_s16le"), "Low", hz = 60, volume = "0.25") }
            // About -37 dB RMS (the generator is at 1/8 of full scale).
            quiet = File(extra, "quiet.wav").also { make(it, 12, listOf("-c:a", "pcm_s16le"), "Quiet", hz = 1000, volume = "0.15") }
            // 440 Hz on the left, 660 Hz on the right.
            stereo = File(extra, "stereo.wav").also { out ->
                val p = ProcessBuilder(
                    FfmpegTools.ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=3",
                    "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=48000:duration=3",
                    "-filter_complex", "amerge=inputs=2", "-c:a", "pcm_s16le", out.absolutePath,
                ).redirectErrorStream(true).start()
                p.inputStream.readBytes()
                check(p.waitFor() == 0) { "couldn't make $out" }
            }
        }

        /** The decoded output of [eq] on [file], after the first second (the filters settling). */
        private fun render(file: File, eq: EqConfig): ShortArray {
            val sink = CountingSink()
            val engine = AudioEngine({ sink })
            engine.eq = eq
            engine.volume = 1f
            engine.load(file.absolutePath, 0, play = true)
            val end = System.currentTimeMillis() + 20_000
            while (engine.state.value.status != EngineStatus.ENDED && System.currentTimeMillis() < end) Thread.sleep(10)
            val bytes = synchronized(sink.written) { sink.written.toByteArray() }
            val skip = 48_000 * 4
            return ShortArray(((bytes.size - skip) / 2).coerceAtLeast(0)) { i ->
                ((bytes[skip + 2 * i + 1].toInt() shl 8) or (bytes[skip + 2 * i].toInt() and 0xFF)).toShort()
            }
        }

        private fun rms(s: ShortArray, from: Int = 0, to: Int = s.size): Double {
            var sum = 0.0
            for (i in from until to) sum += s[i].toDouble() * s[i]
            return kotlin.math.sqrt(sum / (to - from).coerceAtLeast(1))
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
        // The scanner reads files on several threads at once.
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val scanner = LibraryScanner { f -> reads.incrementAndGet(); Ffprobe.read(f) }
        val first = scanner.scan(listOf(dir), emptyMap())
        assertEquals(setOf("Tone Song", "Short Tone"), first.map { it.title }.toSet())
        assertEquals(2, reads.get())
        val again = scanner.scan(listOf(dir), first.associateBy { it.path })
        assertEquals(2, again.size)
        assertEquals("nothing changed, nothing re-read", 2, reads.get())
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

    @Test fun `the local queue moves on by itself without a gap, and repeat and shuffle behave`() {
        val tracks = listOf(
            LocalTrack(flac.absolutePath, "A", "X", "Y", durationMs = 3_000),
            LocalTrack(mp3.absolutePath, "B", "X", "Y", durationMs = 2_000),
        )
        val sink = CountingSink()
        val engine = AudioEngine({ sink })
        val player = PlayerController(engine)
        val statuses = Collections.synchronizedList(mutableListOf<EngineStatus>())
        val watcher = Thread {
            var last: EngineStatus? = null
            while (!Thread.currentThread().isInterrupted) {
                val st = engine.state.value.status
                if (st != last) { statuses += st; last = st }
                try { Thread.sleep(1) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }
        player.playLocal(tracks, 0)
        assertEquals("B", player.nowPlaying.value?.upNext?.single()?.title)
        waitFor(what = "the second song") { player.index.value == 1 }
        assertEquals("B", player.nowPlaying.value?.title)
        waitFor(what = "the end of the queue") { engine.state.value.status == EngineStatus.ENDED }
        watcher.interrupt()
        assertEquals(1, player.index.value)
        // One song straight into the next: no stop, no reload in between, every frame of both.
        assertEquals("statuses $statuses", listOf(EngineStatus.LOADING, EngineStatus.PLAYING, EngineStatus.ENDED), statuses.filter { it != EngineStatus.IDLE })
        assertEquals(5.0 * 48_000, sink.frames.toDouble(), 48_000 * 0.08)
        player.cycleRepeat() // ALL
        player.next()
        assertEquals("wraps round", 0, player.index.value)
        engine.stop()
    }

    @Test fun `buttons never wait on the decoder`() {
        val slow = object : com.harmony.desktop.engine.Decoder {
            val real = com.harmony.desktop.engine.FfmpegDecoder()
            override fun open(source: String, startMs: Long, sampleRate: Int, channels: Int): com.harmony.desktop.engine.DecodeStream {
                Thread.sleep(1_500)
                return real.open(source, startMs, sampleRate, channels)
            }
        }
        val engine = AudioEngine({ CountingSink(realTime = true) }, slow)
        val t0 = System.nanoTime()
        engine.load(flac.absolutePath, 0, play = true, durationMs = 3_000)
        engine.pause()
        engine.play()
        engine.seek(1_000)
        engine.load(mp3.absolutePath, 0, play = true, durationMs = 2_000)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("five commands took $ms ms", ms < 200)
        waitFor(what = "the last one playing") { engine.state.value.status == EngineStatus.PLAYING }
        assertEquals(mp3.absolutePath, engine.state.value.source)
        engine.stop()
    }

    @Test fun `a seek is heard at once and the position follows it`() {
        val engine = AudioEngine({ CountingSink(realTime = true) })
        engine.load(flac.absolutePath, 0, play = true, durationMs = 3_000)
        waitFor(what = "playing") { engine.state.value.status == EngineStatus.PLAYING }
        Thread.sleep(300)
        engine.seek(2_000)
        waitFor(what = "playing after the seek") { engine.state.value.status == EngineStatus.PLAYING }
        Thread.sleep(200)
        val pos = engine.positionMs()
        assertTrue("position after the seek: $pos", pos in 2_050..2_500)
        engine.stop()
    }

    @Test fun `bass, treble, width and even volume each do what they say`() {
        // 60 Hz: the bass shelf lifts it by about its setting.
        val flatLow = rms(render(low, EqConfig(enabled = false)))
        val bassLow = rms(render(low, EqConfig(enabled = true, bassDb = 9f)))
        val liftDb = 20 * kotlin.math.log10(bassLow / flatLow)
        assertTrue("bass +9 dB lifted 60 Hz by $liftDb dB", liftDb in 6.0..10.0)
        // 440 Hz barely moves with the treble shelf.
        val flatMid = rms(render(flac, EqConfig(enabled = false)))
        val trebleMid = rms(render(flac, EqConfig(enabled = true, trebleDb = 9f)))
        assertTrue("treble leaves 440 Hz alone", kotlin.math.abs(20 * kotlin.math.log10(trebleMid / flatMid)) < 1.0)
        // Width 0: left and right become the same; as recorded they differ.
        fun sideRatio(s: ShortArray): Double {
            var side = 0.0
            var all = 0.0
            for (i in 0 until s.size / 2) {
                val l = s[2 * i].toDouble(); val r = s[2 * i + 1].toDouble()
                side += (l - r) * (l - r); all += l * l + r * r
            }
            return side / all.coerceAtLeast(1.0)
        }
        assertTrue("as recorded, the sides differ", sideRatio(render(stereo, EqConfig(enabled = false))) > 0.5)
        assertTrue("width 0 is mono", sideRatio(render(stereo, EqConfig(enabled = true, width = 0f))) < 0.001)
        // Even volume brings a quiet song up, slowly.
        val q = render(quiet, EqConfig(enabled = true, leveling = true))
        val early = rms(q, 0, 48_000 * 2)
        val late = rms(q, q.size - 48_000 * 2, q.size)
        val off = rms(render(quiet, EqConfig(enabled = true, leveling = false)))
        assertTrue("quiet song rises slowly: $early then $late", late > early * 1.3)
        assertTrue("to about +6 dB: ${late / off}", late / off in 1.7..2.2)
    }

    @Test fun `the analyzer shows the music as heard, before and after`() {
        val engine = AudioEngine({ CountingSink(realTime = true) })
        engine.eq = EqConfig(enabled = true, winampGainsDb = listOf(0f, 0f, 12f, 12f, 0f, 0f, 0f, 0f, 0f, 0f))
        engine.load(flac.absolutePath, 0, play = true, durationMs = 3_000)
        waitFor(what = "playing") { engine.state.value.status == EngineStatus.PLAYING }
        Thread.sleep(1_200)
        val f = engine.spectrum()
        assertNotNull(f)
        f!!
        val peak = f.after.indices.maxByOrNull { f.after[it] }!!
        val hz = com.harmony.desktop.engine.SpectrumTap.centreHz(peak)
        assertTrue("the loudest band is at the 440 Hz tone: $hz", hz in 350.0..560.0)
        assertTrue("the equalizer lifted it: ${f.before[peak]} -> ${f.after[peak]}", f.after[peak] - f.before[peak] > 4f)
        engine.stop()
    }

    @Test fun `a phone that allows it lets the computer go straight on, and fetched songs seek locally`() {
        val served = Collections.synchronizedList(mutableListOf<String>())
        val phoneFiles = MiniHttpServer(0, "phone-files") { req ->
            served += req.path
            when (req.path) {
                "/track/7" -> HttpResponse.ranged(FileRangeSource(flac), req.header("Range"), "audio/flac")
                "/track/8" -> HttpResponse.ranged(FileRangeSource(mp3), req.header("Range"), "audio/mpeg")
                else -> HttpResponse.text(404, "no")
            }
        }.start()
        val engine = AudioEngine({ CountingSink(realTime = true) })
        val cache = com.harmony.desktop.connect.RemoteCache(createTempDir("harmony-cache"))
        val player = PlayerController(engine, cache)
        try {
            val base = "http://127.0.0.1:${phoneFiles.boundPort}"
            val a = RemoteTrack("7", "Tone Song", "Test Artist", "Test Album", 3_000, "$base/track/7")
            val b = RemoteTrack("8", "Short Tone", "Test Artist", "Test Album", 2_000, "$base/track/8")
            player.play(PlayRequest(a, 0, playing = true, phoneName = "Pixel 8", upNext = listOf(b), autoAdvance = true))
            waitFor(what = "playing") { player.status().state == RemoteState.PLAYING }
            // Both songs are fetched in the background; a seek then plays from the copy.
            waitFor(what = "the copies") { cache.localFor(a.url) != null && cache.localFor(b.url) != null }
            player.seek(2_200)
            waitFor(what = "playing after the seek") { engine.state.value.status == EngineStatus.PLAYING }
            assertEquals(cache.localFor(a.url), engine.state.value.source)
            // At the end it goes straight on to the phone's next song, and says so.
            waitFor(what = "the next song") { player.status().trackId == "8" }
            assertEquals("Short Tone", player.nowPlaying.value?.title)
            assertEquals(RemoteState.PLAYING, player.status().state)
            // The phone catches up: the song carries on, not restarted.
            Thread.sleep(500)
            val before = player.status().positionMs
            player.play(PlayRequest(b, 0, playing = true, phoneName = "Pixel 8", autoAdvance = true, followUp = true))
            Thread.sleep(200)
            assertTrue("carried on from $before to ${player.status().positionMs}", player.status().positionMs >= before)
            // Nothing after it: the end is reported to the phone.
            waitFor(what = "the end") { player.status().state == RemoteState.ENDED }

            // Next on the computer goes straight to the phone's next song too.
            player.play(PlayRequest(a, 0, playing = true, phoneName = "Pixel 8", upNext = listOf(b), autoAdvance = true))
            waitFor(what = "playing again") { player.status().state == RemoteState.PLAYING }
            player.next()
            assertEquals("8", player.status().trackId)
            assertTrue("nothing asked of the phone", player.status().requests.isEmpty())
        } finally {
            engine.stop()
            phoneFiles.close()
        }
    }
}
