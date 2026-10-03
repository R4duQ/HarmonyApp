package com.harmony.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WinampEqDesignTest {
    private val flat = List(10) { 0f }
    private fun one(band: Int, db: Float) = flat.toMutableList().also { it[band] = db }

    @Test fun `flat is exactly unity everywhere`() {
        for (f in listOf(30f, 60f, 1000f, 5000f, 16000f, 20000f)) {
            assertEquals(0f, WinampEqDesign.responseDb(flat, 0f, f), 0.001f)
        }
    }

    @Test fun `a lone band reaches its slider value at its own frequency`() {
        WinampEqDesign.FREQUENCIES_HZ.forEachIndexed { i, f ->
            assertEquals("band $f", 12f, WinampEqDesign.responseDb(one(i, 12f), 0f, f), 0.05f)
            assertEquals("band $f", -12f, WinampEqDesign.responseDb(one(i, -12f), 0f, f), 0.05f)
        }
    }

    @Test fun `each band is an octave wide`() {
        // +12 dB at 1 kHz: down to about +9 dB half an octave away, +5.7 dB an octave away.
        val g = one(4, 12f)
        assertEquals(9.25f, WinampEqDesign.responseDb(g, 0f, 707f), 0.2f)
        assertEquals(9.25f, WinampEqDesign.responseDb(g, 0f, 1414f), 0.2f)
        assertTrue(WinampEqDesign.responseDb(g, 0f, 2000f) < 6f)
        assertTrue(WinampEqDesign.responseDb(g, 0f, 8000f) < 1f)
    }

    @Test fun `neighbouring bands add up the way Winamp's did`() {
        // Every band at +12 dB is louder than +12 in places: the overlaps sum.
        val all = List(10) { 12f }
        assertTrue(WinampEqDesign.responseDb(all, 0f, 14_000f) > 15f)
    }

    @Test fun `the preamp shifts the whole curve`() {
        assertEquals(-6f, WinampEqDesign.responseDb(flat, -6f, 1000f), 0.001f)
        assertEquals(6f, WinampEqDesign.responseDb(one(4, 12f), -6f, 1000f), 0.05f)
    }

    @Test fun `bands beyond what the sample rate can carry are switched off`() {
        val at22k = WinampEqDesign.coefficients(22_050)
        assertTrue(at22k[9].all { it == 0f })   // 16 kHz
        assertTrue(at22k[4].any { it != 0f })   // 1 kHz
        assertTrue(WinampEqDesign.coefficients(44_100)[9].any { it != 0f })
    }

    @Test fun `all eighteen Winamp presets are there, within range`() {
        assertEquals(18, WinampEqDesign.PRESETS.size)
        assertEquals("Flat", WinampEqDesign.PRESETS.first().first)
        WinampEqDesign.PRESETS.forEach { (name, preset) ->
            val (preamp, gains) = preset
            assertEquals(name, 10, gains.size)
            assertTrue(name, gains.all { it in -20f..20f } && preamp in -20f..20f)
        }
    }

    @Test fun `slider values outside the range are clamped`() {
        assertEquals(WinampEqDesign.weight(20f), WinampEqDesign.weight(35f), 0f)
        assertEquals(0f, WinampEqDesign.weight(0f), 0f)
    }
}
