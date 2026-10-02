package com.harmony.feature.downloads

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class SongRecognitionTest {

    private val match = """
        {"matches":[{"id":"20066955","offset":40.1,"timeskew":0.0001}],"timestamp":1,"timezone":"Europe/Bucharest",
         "track":{"key":"20066955","title":"Warriors","subtitle":"Imagine Dragons","isrc":"USUM71414143",
          "images":{"background":"https://is1-ssl.mzstatic.com/bg.jpg","coverart":"https://is1-ssl.mzstatic.com/400x400cc.jpg",
                    "coverarthq":"https://is1-ssl.mzstatic.com/400x400cc-hq.jpg"},
          "share":{"href":"https://www.shazam.com/track/20066955/warriors"},
          "url":"https://www.shazam.com/track/20066955/warriors",
          "sections":[{"type":"SONG","metadata":[{"title":"Album","text":"Warriors"},{"title":"Label","text":"KIDinaKORNER/Interscope Records"},
                                                {"title":"Released","text":"2014"}]},{"type":"LYRICS","text":["..."]}]}}
    """.trimIndent()

    @Test fun `a match carries title, artist, year, link, cover and ISRC`() {
        val song = (ShazamApi.parse(200, match, now = 42L) as RecognitionOutcome.Match).song
        assertEquals("Warriors", song.title)
        assertEquals("Imagine Dragons", song.artist)
        assertEquals("Warriors", song.album)
        assertEquals("KIDinaKORNER/Interscope Records", song.label)
        assertEquals("2014", song.year)
        assertEquals("https://www.shazam.com/track/20066955/warriors", song.songLink)
        assertEquals("https://is1-ssl.mzstatic.com/400x400cc-hq.jpg", song.coverUrl)
        assertEquals("USUM71414143", song.isrc)
        assertEquals("Imagine Dragons - Warriors", song.searchQuery)
        assertEquals(42L, song.recognizedAt)
    }

    @Test fun `a sparse match still works`() {
        val body = """{"matches":[{"id":"1"}],"track":{"title":"T","subtitle":"A","images":{"coverart":"https://x/c.jpg"},
            "share":{"href":"https://www.shazam.com/track/1"}}}"""
        val song = (ShazamApi.parse(200, body, 0) as RecognitionOutcome.Match).song
        assertEquals("https://x/c.jpg", song.coverUrl)
        assertEquals("https://www.shazam.com/track/1", song.songLink)
        assertEquals("", song.year)
        assertEquals("", song.album)
    }

    @Test fun `no matches is no match`() {
        assertEquals(RecognitionOutcome.NoMatch, ShazamApi.parse(200, """{"matches":[],"retryms":4000,"tagid":"x"}""", 0))
        assertEquals(RecognitionOutcome.NoMatch, ShazamApi.parse(200, """{"matches":[{"id":"1"}]}""", 0))
    }

    @Test fun `rate limits stop the listen, server hiccups let it try again`() {
        val limited = ShazamApi.parse(429, "", 0) as RecognitionOutcome.Failed
        assertTrue(limited.message.contains("too many requests"))
        assertFalse(limited.retryable)
        val down = ShazamApi.parse(503, "oops", 0) as RecognitionOutcome.Failed
        assertTrue(down.message.contains("HTTP 503"))
        assertTrue(down.retryable)
        assertTrue((ShazamApi.parse(200, "<html>502</html>", 0) as RecognitionOutcome.Failed).retryable)
    }

    @Test fun `each attempt fingerprints the latest 12 seconds`() {
        assertEquals(0, RecognitionWindow.start(filled = 8 * 16_000, rate = 16_000, windowSeconds = 12))
        assertEquals(0, RecognitionWindow.start(filled = 12 * 16_000, rate = 16_000, windowSeconds = 12))
        assertEquals(8 * 16_000, RecognitionWindow.start(filled = 20 * 16_000, rate = 16_000, windowSeconds = 12))
        assertEquals(4 * 44_100, RecognitionWindow.start(filled = 16 * 44_100, rate = 44_100, windowSeconds = 12))
    }

    @Test fun `a quiet recording is brought up to an ordinary level without moving its peaks`() {
        val rate = ShazamSignature.SAMPLE_RATE
        val loud = ShortArray(rate * 6) { i ->
            val inBurst = (i % rate) < rate / 10
            if (inBurst) (12_000 * sin(2 * PI * 1000 * i / rate)).toInt().toShort() else 0
        }
        val quiet = ShortArray(loud.size) { (loud[it] / 40 + 3).toShort() }      // -32 dB with a DC offset
        val raised = Pcm.normalize(quiet)
        assertEquals(16_384.0, raised.maxOf { kotlin.math.abs(it.toInt()) }.toDouble(), 600.0)
        assertEquals(0.0, raised.average(), 2.0)
        fun peaks(s: ShazamSignature) = s.bands.flatten().associate { (it.fftPass to it.frequencyBin / 64) to it.magnitude }
        val reference = peaks(ShazamSignature.of(loud))
        val before = peaks(ShazamSignature.of(quiet))
        val after = peaks(ShazamSignature.of(raised))
        // Gain moves none of the song's peaks (rounding the quiet copy adds faint harmonics of its own)...
        assertTrue(before.keys.containsAll(reference.keys))
        assertTrue(after.keys.containsAll(reference.keys))
        // ...but brings their loudness back to where the full-volume recording has it.
        val gap = { p: Map<Pair<Int, Int>, Int> -> reference.keys.map { reference.getValue(it) - p.getValue(it) }.average() }
        assertTrue("quiet ${gap(before)}", gap(before) > 9_000)
        assertEquals(0.0, gap(after), 1_000.0)
        // Hiss alone is raised at most 64 times, not to full volume.
        val hiss = ShortArray(rate) { if (it % 2 == 0) 2 else -2 }
        assertTrue(Pcm.normalize(hiss).maxOf { kotlin.math.abs(it.toInt()) } <= 2 * 64)
        assertEquals(0, Pcm.normalize(ShortArray(0)).size)
    }

    @Test fun `the meter reads quiet rooms as well as loud ones`() {
        assertEquals(0f, Pcm.meter(0f), 0f)
        assertEquals(0f, Pcm.meter(0.0003f), 0.01f)          // about -70 dBFS
        assertEquals(0.44f, Pcm.meter(0.0056f), 0.02f)       // about -45 dBFS
        assertEquals(1f, Pcm.meter(0.2f), 0f)
    }

    @Test fun `the request carries the signature, its length and the time zone`() {
        val signature = ShazamSignature(32_000, listOf(listOf(ShazamSignature.Peak(3, 9000, 9000)), emptyList(), emptyList(), emptyList()))
        val body = JSONObject(ShazamApi.body(signature, 1_700_000_000_000L, "Europe/Bucharest"))
        assertEquals("Europe/Bucharest", body.getString("timezone"))
        assertEquals(1_700_000_000_000L, body.getLong("timestamp"))
        assertEquals(2000, body.getJSONObject("signature").getInt("samplems"))
        assertTrue(body.getJSONObject("signature").getString("uri").startsWith("data:audio/vnd.shazam.sig;base64,"))
        assertTrue(ShazamApi.url("A-B", "c-d").startsWith("https://amp.shazam.com/discovery/v5/en/US/android/-/tag/A-B/c-d?"))
    }

    @Test fun `the signature has Shazam's header, a valid checksum and delta-coded peaks`() {
        val peaks = listOf(ShazamSignature.Peak(2, 0x1234, 0x0567), ShazamSignature.Peak(5, 7000, 8000), ShazamSignature.Peak(400, 7100, 8100))
        val bytes = ShazamSignature(48_000, listOf(emptyList(), peaks, emptyList(), emptyList())).encode()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0xCAFE2580.toInt(), b.getInt(0))
        assertEquals(CRC32().apply { update(bytes, 8, bytes.size - 8) }.value.toInt(), b.getInt(4))
        assertEquals(bytes.size - 48, b.getInt(8))
        assertEquals(0x94119C00.toInt(), b.getInt(12))
        assertEquals(3 shl 27, b.getInt(28))
        assertEquals(48_000 + 3840, b.getInt(40))
        assertEquals(0x7C0000, b.getInt(44))
        assertEquals(0x40000000, b.getInt(48))
        assertEquals(bytes.size - 48, b.getInt(52))
        assertEquals(0x60030041, b.getInt(56))            // band 520-1450 Hz
        // 2 (+0x1234, 0x0567), 3 more, then a jump of 395 passes written as 0xFF + absolute pass
        assertEquals(5 + 5 + 5 + 5, b.getInt(60))
        assertArrayEquals(byteArrayOf(2, 0x34, 0x12, 0x67, 0x05, 3), bytes.copyOfRange(64, 70))
        assertEquals(0xFF.toByte(), bytes[74])
        assertEquals(400, b.getInt(75))
        assertEquals(0.toByte(), bytes[79])
        assertEquals(0, bytes.size % 4)
    }

    @Test fun `tone bursts become peaks at their pitch, silence gives none`() {
        val rate = ShazamSignature.SAMPLE_RATE
        val pcm = ShortArray(rate * 6) { i ->
            val inBurst = (i % rate) < rate / 10   // 100 ms of 1 kHz every second
            if (inBurst) (12_000 * sin(2 * PI * 1000 * i / rate)).toInt().toShort() else 0
        }
        val signature = ShazamSignature.of(pcm)
        assertEquals(rate * 6, signature.numberSamples)
        assertTrue("peaks: ${signature.peakCount}", signature.peakCount > 0)
        assertTrue(signature.bands[0].isEmpty() && signature.bands[2].isEmpty() && signature.bands[3].isEmpty())
        for (peak in signature.bands[1]) {
            val hz = peak.frequencyBin * (16000.0 / 2 / 1024 / 64)
            assertEquals(1000.0, hz, 15.0)
        }
        assertEquals(0, ShazamSignature.of(ShortArray(rate * 4)).peakCount)
    }

    @Test fun `resampling keeps the tune and drops what 16 kHz can't hold`() {
        val from = 44_100
        val tone = { hz: Double -> ShortArray(from) { (10_000 * sin(2 * PI * hz * it / from)).toInt().toShort() } }
        val low = Pcm.resample(tone(1000.0), from, from, 16_000)
        assertEquals(16_000, low.size)
        val crossings = (1 until low.size).count { low[it - 1] < 0 && low[it] >= 0 }
        assertEquals(1000.0, crossings.toDouble(), 3.0)
        assertEquals(10_000 / sqrt(2.0) / 32768, Pcm.level(low).toDouble(), 0.01)
        val high = Pcm.resample(tone(12_000.0), from, from, 16_000)
        assertTrue(Pcm.level(high) < Pcm.level(low) / 20)
        assertArrayEquals(shortArrayOf(1, 2), Pcm.resample(shortArrayOf(1, 2, 3), 2, 16_000, 16_000))
        assertEquals(0f, Pcm.level(ShortArray(100)), 0f)
        assertEquals(1f, Pcm.level(ShortArray(10) { Short.MIN_VALUE }), 0.001f)
    }

    @Test fun `history keeps newest first, moves repeats up and survives a round trip`() {
        val a = RecognizedSong("Warriors", "Imagine Dragons", isrc = "X1", recognizedAt = 1)
        val b = RecognizedSong("Yellow", "Coldplay", recognizedAt = 2)
        var history = RecognitionHistoryCodec.add(emptyList(), a)
        history = RecognitionHistoryCodec.add(history, b)
        history = RecognitionHistoryCodec.add(history, a.copy(recognizedAt = 3))
        assertEquals(listOf("Warriors", "Yellow"), history.map { it.title })
        assertEquals(3L, history[0].recognizedAt)
        assertEquals(history, RecognitionHistoryCodec.decode(RecognitionHistoryCodec.encode(history)))
        assertEquals(emptyList<RecognizedSong>(), RecognitionHistoryCodec.decode("not json"))
        val many = (1..40).fold(emptyList<RecognizedSong>()) { h, i -> RecognitionHistoryCodec.add(h, RecognizedSong("T$i", "A")) }
        assertEquals(RecognitionHistoryCodec.MAX, many.size)
        assertEquals("T40", many.first().title)
    }
}
