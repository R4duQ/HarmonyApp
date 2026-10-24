package com.harmony.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqAutoTest {
    @Test
    fun everyPresetFitsTheEngine() {
        for ((name, gains) in EqPresets.ALL) {
            assertEquals(name, EqSettings.BAND_COUNT, gains.size)
            assertTrue(name, gains.all { it in -EqSettings.MAX_GAIN_DB..EqSettings.MAX_GAIN_DB })
        }
        assertEquals("Flat", EqPresets.matching(EqSettings.FLAT))
    }

    @Test
    fun oldWinampCurveCarriesOver() {
        // A Winamp curve rising evenly from -10 at 60 Hz to +10 at 16 kHz.
        val winamp = listOf(-10f, -8f, -6f, -4f, -2f, 2f, 4f, 6f, 8f, 10f)
        val moved = EqAuto.fromWinampBands(winamp)
        assertEquals(-10f, moved[0]) // 31 Hz: below Winamp's lowest band
        assertEquals(10f, moved[9])
        assertTrue(moved.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(moved.all { it in -12f..12f })
        assertEquals(EqSettings.FLAT, EqAuto.fromWinampBands(listOf(1f)))
    }

    @Test
    fun sanitizeRejectsGarbage() {
        val out = EqSettings.sanitize(listOf(Float.NaN, 40f, -40f))
        assertEquals(listOf(0f, 12f, -12f) + List(7) { 0f }, out)
    }
}
