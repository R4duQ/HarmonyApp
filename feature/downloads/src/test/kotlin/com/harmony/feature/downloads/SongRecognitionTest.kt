package com.harmony.feature.downloads

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SongRecognitionTest {

    private val match = """
        {"status":"success","result":{"artist":"Imagine Dragons","title":"Warriors","album":"Warriors",
         "release_date":"2014-09-18","label":"Universal Music","timecode":"00:40","song_link":"https://lis.tn/Warriors",
         "deezer":{"id":82793030,"isrc":"USUM71414143","album":{"cover_xl":"https://cdn-images.dzcdn.net/xl.jpg"}},
         "apple_music":{"isrc":"USUM71414143","artwork":{"url":"https://is1.mzstatic.com/{w}x{h}bb.jpg"}}}}
    """.trimIndent()

    @Test fun `a match carries title, artist, year, link, cover and ISRC`() {
        val outcome = AudDApi.parse(match, now = 42L) as RecognitionOutcome.Match
        val song = outcome.song
        assertEquals("Warriors", song.title)
        assertEquals("Imagine Dragons", song.artist)
        assertEquals("2014", song.year)
        assertEquals("https://lis.tn/Warriors", song.songLink)
        assertEquals("https://cdn-images.dzcdn.net/xl.jpg", song.coverUrl)
        assertEquals("USUM71414143", song.isrc)
        assertEquals("Imagine Dragons - Warriors", song.searchQuery)
        assertEquals(42L, song.recognizedAt)
    }

    @Test fun `apple artwork is used when deezer has no cover, sized up`() {
        val body = """{"status":"success","result":{"artist":"A","title":"T","apple_music":{"artwork":{"url":"https://x/{w}x{h}bb.jpg"}}}}"""
        val song = (AudDApi.parse(body, 0) as RecognitionOutcome.Match).song
        assertEquals("https://x/1000x1000bb.jpg", song.coverUrl)
        assertEquals("", song.year)
    }

    @Test fun `no result is no match, and so is a fingerprint of silence`() {
        assertEquals(RecognitionOutcome.NoMatch, AudDApi.parse("""{"status":"success","result":null}""", 0))
        assertEquals(RecognitionOutcome.NoMatch,
            AudDApi.parse("""{"status":"error","error":{"error_code":300,"error_message":"Recognition failed"}}""", 0))
    }

    @Test fun `token problems ask for a token, other errors do not`() {
        for (code in listOf(900, 901, 902)) {
            val outcome = AudDApi.parse("""{"status":"error","error":{"error_code":$code,"error_message":"x"}}""", 0)
            assertTrue("code $code", (outcome as RecognitionOutcome.Failed).needsToken)
        }
        val other = AudDApi.parse("""{"status":"error","error":{"error_code":500,"error_message":"Bad file"}}""", 0)
                as RecognitionOutcome.Failed
        assertEquals("Recognition failed: Bad file", other.message)
        assertTrue(!other.needsToken)
        assertTrue(AudDApi.parse("<html>502</html>", 0) is RecognitionOutcome.Failed)
    }

    @Test fun `the request has the token only when there is one, and the file last`() {
        val wav = byteArrayOf(1, 2, 3)
        val withToken = String(AudDApi.multipartBody("B", " tok ", wav), Charsets.ISO_8859_1)
        assertTrue("name=\"api_token\"\r\n\r\ntok\r\n" in withToken)
        assertTrue("name=\"return\"\r\n\r\ndeezer,apple_music,spotify\r\n" in withToken)
        assertTrue(withToken.endsWith("\u0001\u0002\u0003\r\n--B--\r\n"))
        val without = String(AudDApi.multipartBody("B", "  ", wav), Charsets.ISO_8859_1)
        assertTrue("api_token" !in without)
    }

    @Test fun `the wav header describes 16-bit mono at the given rate`() {
        val wav = Pcm.wav(shortArrayOf(1, -1, 300), 22_050)
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(36 + 6, b.getInt(4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(1, b.getShort(22).toInt())          // mono
        assertEquals(22_050, b.getInt(24))               // sample rate
        assertEquals(44_100, b.getInt(28))               // byte rate
        assertEquals(16, b.getShort(34).toInt())         // bits per sample
        assertEquals(6, b.getInt(40))                    // data size
        assertEquals(300, b.getShort(48).toInt())
        assertEquals(50, wav.size)
    }

    @Test fun `decimation averages pairs and drops an odd tail`() {
        assertArrayEquals(shortArrayOf(15, -5), Pcm.decimateBy2(shortArrayOf(10, 20, 0, -10, 99)))
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
