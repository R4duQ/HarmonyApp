package com.harmony.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EqAutoTest {
    private val manual = listOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f)

    @Test
    fun songCurveWinsOverGenre() {
        val saved = List(10) { -3f }
        val curve = EqAuto.curveFor(7L, "Rock", mapOf(7L to saved), manual)
        assertEquals(EqAutoSource.SONG, curve.source)
        assertEquals(saved, curve.gainsDb)
    }

    @Test
    fun genrePicksItsPreset() {
        val curve = EqAuto.curveFor(7L, "Alternative Rock", emptyMap(), manual)
        assertEquals(EqAutoSource.GENRE, curve.source)
        assertEquals("Rock", curve.preset)
        assertEquals(EqPresets.named("Rock"), curve.gainsDb)
    }

    @Test
    fun unknownGenreKeepsTheListenersCurve() {
        val curve = EqAuto.curveFor(7L, "Gregorian Throat Singing Fusion", emptyMap(), manual)
        assertEquals(EqAutoSource.MANUAL, curve.source)
        assertEquals(manual, curve.gainsDb)
        assertEquals(EqAutoSource.MANUAL, EqAuto.curveFor(null, null, emptyMap(), manual).source)
    }

    @Test
    fun specificGenresBeatTheWordsInsideThem() {
        assertEquals("Hip-Hop", EqAuto.presetForGenre("Hip Hop/Rap"))
        assertEquals("Techno", EqAuto.presetForGenre("Dubstep"))
        assertEquals("Techno", EqAuto.presetForGenre("Drum and Bass"))
        assertEquals("Reggae", EqAuto.presetForGenre("Dancehall"))
        assertEquals("Dance", EqAuto.presetForGenre("Deep House"))
        assertEquals("Soft Rock", EqAuto.presetForGenre("Soft Rock"))
        assertEquals("Rock", EqAuto.presetForGenre("Indie Pop"))
        assertEquals("Pop", EqAuto.presetForGenre("K-Pop"))
        assertEquals("Classical", EqAuto.presetForGenre("Soundtrack"))
        assertNull(EqAuto.presetForGenre("  "))
    }

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

class EqResponseTest {
    private val probe = EqSettings.BAND_CENTERS_HZ.toFloatArray()

    @Test
    fun flatIsZero() {
        assertTrue(EqResponse.bandsDb(EqSettings.FLAT, probe).all { kotlin.math.abs(it) < 1e-3f })
        assertEquals(0f, EqResponse.autoPreampDb(EqSettings.FLAT), 1e-3f)
    }

    @Test
    fun aBandHitsItsGainAndThePreampTakesBack65Percent() {
        val gains = EqSettings.FLAT.toMutableList().also { it[5] = 6f }
        assertEquals(6f, EqResponse.bandsDb(gains, floatArrayOf(1000f))[0], 0.05f)
        assertEquals(-0.65f * 6f, EqResponse.autoPreampDb(gains), 0.3f)
        assertTrue(EqResponse.autoPreampDb(List(10) { -12f }) == 0f)
    }
}
