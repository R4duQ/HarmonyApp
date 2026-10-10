package com.harmony.desktop

import com.harmony.desktop.engine.FfmpegTools
import com.harmony.desktop.engine.Waveforms
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PlayStatsTest {
    @Test fun playsAreCountedAndKept() {
        val file = File(createTempDir("harmony-plays"), "plays.json")
        var now = 1_000L
        val plays = PlayStats(file) { now }
        plays.record("/a.flac")
        now = 2_000L
        plays.record("/a.flac")
        plays.record("/b.flac")
        assertEquals(PlayStat(2, 2_000L), plays.stats.value["/a.flac"])
        plays.flush()
        val again = PlayStats(file)
        assertEquals(2, again.stats.value["/a.flac"]?.count)
        assertEquals(1, again.stats.value["/b.flac"]?.count)
    }

    @Test fun aBrokenFileStartsEmpty() {
        val file = File(createTempDir("harmony-plays"), "plays.json").apply { writeText("{not json") }
        assertTrue(PlayStats(file).stats.value.isEmpty())
    }

    @Test fun waveformFollowsTheLoudness() {
        assumeTrue("ffmpeg is needed: pass -PffmpegDir", runCatching { ProcessBuilder(FfmpegTools.ffmpeg, "-version").start().waitFor() == 0 }.getOrDefault(false))
        // Quiet for the first half, loud for the second.
        val wav = File(createTempDir("harmony-wave"), "steps.wav")
        ProcessBuilder(
            FfmpegTools.ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi",
            "-i", "aevalsrc=if(lt(t\\,5)\\,0.05\\,0.8)*sin(2*PI*440*t):s=44100:d=10", wav.path,
        ).inheritIO().start().waitFor()
        val bars = runBlocking { Waveforms.of(wav.path) }
        assertNotNull(bars)
        assertEquals(Waveforms.BARS, bars!!.size)
        assertTrue("quiet half stays low", bars.take(40).all { it < 0.3f })
        assertTrue("loud half is near the top", bars.takeLast(40).all { it > 0.9f })
        assertTrue(Waveforms.cached(wav.path) === bars)
    }
}
