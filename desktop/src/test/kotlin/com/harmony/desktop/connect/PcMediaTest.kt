package com.harmony.desktop.connect

import com.harmony.core.remote.ServedMedia
import com.harmony.desktop.engine.FfmpegTools
import com.harmony.desktop.library.Ffprobe
import com.harmony.desktop.library.LocalTrack
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** The computer's songs for a phone (Universal Queue, stage 6), with real ffmpeg. */
class PcMediaTest {
    companion object {
        private lateinit var dir: File
        private lateinit var flac: LocalTrack
        private lateinit var wavpack: LocalTrack
        private lateinit var broken: LocalTrack

        private fun make(out: File, codecArgs: List<String>) {
            val p = ProcessBuilder(
                listOf(FfmpegTools.ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=2") +
                    codecArgs + listOf("-metadata", "title=Tone", out.absolutePath),
            ).redirectErrorStream(true).start()
            p.inputStream.readBytes()
            check(p.waitFor() == 0) { "couldn't make $out" }
        }

        @BeforeClass @JvmStatic fun makeFiles() {
            assumeTrue("ffmpeg is needed", runCatching { ProcessBuilder(FfmpegTools.ffmpeg, "-version").start().waitFor() == 0 }.getOrDefault(false))
            dir = createTempDir("harmony-pcmedia")
            flac = Ffprobe.read(File(dir, "a.flac").also { make(it, listOf("-c:a", "flac")) })!!
            wavpack = Ffprobe.read(File(dir, "b.wv").also { make(it, listOf("-c:a", "wavpack")) })!!
            broken = File(dir, "c.ape").apply { writeBytes(ByteArray(4_000) { 7 }) }
                .let { LocalTrack(it.absolutePath, "Broken", "", "", codec = "ape", modified = it.lastModified()) }
        }
    }

    private fun media(tracks: List<LocalTrack>) = PcMedia({ tracks }, createTempDir("harmony-converted"), log = { println(it) })

    private fun ready(m: PcMedia, id: String, ms: Long = 30_000): ServedMedia.Ready {
        val end = System.currentTimeMillis() + ms
        while (true) {
            when (val s = m(id)) {
                is ServedMedia.Ready -> return s
                is ServedMedia.Preparing -> Thread.sleep(50)
                ServedMedia.Missing -> throw AssertionError("missing: $id")
            }
            if (System.currentTimeMillis() > end) throw AssertionError("not ready: $id")
        }
    }

    @Test fun aSongThePhoneCanPlayIsServedAsItIs() {
        val m = media(listOf(flac))
        val served = m(PcMedia.idFor(flac))
        assertTrue(served is ServedMedia.Ready)
        served as ServedMedia.Ready
        assertEquals("audio/flac", served.contentType)
        assertArrayEquals(File(flac.path).readBytes(), served.source.open(0).use { it.readBytes() })
        assertEquals(File(flac.path), m.file(PcMedia.idFor(flac)))
    }

    @Test fun anotherFormatIsConvertedToFlacOnceThenServed() {
        val m = media(listOf(wavpack))
        val id = PcMedia.idFor(wavpack)
        assertTrue("converting first", m(id) is ServedMedia.Preparing)
        val served = ready(m, id)
        assertEquals("audio/flac", served.contentType)
        val bytes = served.source.open(0).use { it.readBytes() }
        assertEquals("fLaC", String(bytes, 0, 4, Charsets.US_ASCII))
        // The same sound: a FLAC of the same length.
        val out = File(dir, "check.flac").apply { writeBytes(bytes) }
        val probed = Ffprobe.read(out)!!
        assertEquals("flac", probed.codec)
        assertEquals(wavpack.durationMs.toDouble(), probed.durationMs.toDouble(), 50.0)
        // Asked again: served from the converted copy straight away.
        assertTrue(m(id) is ServedMedia.Ready)
        // The computer itself still plays the original.
        assertEquals(File(wavpack.path), m.file(id))
    }

    @Test fun aFileThatWontConvertIsMissingNotRetriedForever() {
        val m = media(listOf(broken))
        val id = PcMedia.idFor(broken)
        val end = System.currentTimeMillis() + 20_000
        while (m(id) is ServedMedia.Preparing) {
            assertTrue(System.currentTimeMillis() < end)
            Thread.sleep(50)
        }
        assertEquals(ServedMedia.Missing, m(id))
    }

    @Test fun unknownSongsAndGoneFilesAreMissing() {
        val gone = LocalTrack(File(dir, "gone.flac").absolutePath, "Gone", "", "", codec = "flac")
        val m = media(listOf(flac, gone))
        assertEquals(ServedMedia.Missing, m("nope"))
        assertEquals(ServedMedia.Missing, m(PcMedia.idFor(gone)))
    }

    @Test fun aRetaggedFileIsAnotherSong() {
        assertNotEquals(PcMedia.idFor(flac), PcMedia.idFor(flac.copy(modified = flac.modified + 1000)))
        assertFalse("never the path", PcMedia.idFor(flac).contains("flac"))
    }

    @Test fun whatThePhonePlaysByItself() {
        fun t(ext: String, codec: String?) = LocalTrack("x.$ext", "", "", "", codec = codec)
        assertTrue(PcMedia.phoneCanPlay(t("mp3", "mp3")))
        assertTrue(PcMedia.phoneCanPlay(t("flac", "flac")))
        assertTrue(PcMedia.phoneCanPlay(t("m4a", "aac")))
        assertTrue(PcMedia.phoneCanPlay(t("opus", "opus")))
        assertTrue(PcMedia.phoneCanPlay(t("wav", "pcm_s24le")))
        for ((ext, codec) in listOf("m4a" to "alac", "ape" to "ape", "wv" to "wavpack", "wma" to "wmav2", "dsf" to "dsd_lsbf", "aiff" to "pcm_s16be")) {
            assertFalse("$ext/$codec", PcMedia.phoneCanPlay(t(ext, codec)))
        }
    }
}
