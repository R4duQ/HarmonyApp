package com.harmony.feature.downloads

import org.junit.Assert.assertEquals
import org.junit.Test

class FileAudioWindowTest {
    private val window = FileAudioWindow.SECONDS * 1_000_000L

    @Test
    fun startsAThirdIntoTheSong() {
        assertEquals(60_000_000L, FileAudioWindow.startUs(180_000_000L))
    }

    @Test
    fun neverRunsOffTheEnd() {
        // 15 s: a third in (5 s) would leave only 10 s, so it starts at 3 s.
        assertEquals(3_000_000L, FileAudioWindow.startUs(15_000_000L))
        assertEquals(0L, FileAudioWindow.startUs(window))
    }

    @Test
    fun shortOrUnknownSongsStartAtTheBeginning() {
        assertEquals(0L, FileAudioWindow.startUs(5_000_000L))
        assertEquals(0L, FileAudioWindow.startUs(0L))
        assertEquals(0L, FileAudioWindow.startUs(-1L))
    }
}
