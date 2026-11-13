package com.harmony.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqClarityTest {
    @Test
    fun bandsRunFrom40HzTo16kHzOnTheErbScale() {
        val c = EqClarity.CENTERS_HZ
        assertEquals(EqClarity.BAND_COUNT, c.size)
        assertTrue(c.first() in 50f..80f) // about 64 Hz: the first band runs from 40 Hz
        assertTrue(c.last() in 14_000f..16_000f)
        assertTrue(c.zipWithNext().all { (a, b) -> b > a })
        // Narrow in the bass, wide in the treble.
        assertTrue(c[1] - c[0] < c[23] - c[22])
    }

    @Test
    fun settingsAreClampedAndDefaultsAreOff() {
        val eq = EqSettings()
        assertTrue(!eq.clarity)
        assertEquals(0.5f, eq.clarityIntensity)
        assertEquals(1f, EqClarity.intensity(3f))
        assertEquals(EqClarity.DEFAULT_INTENSITY, EqClarity.intensity(Float.NaN))
        assertEquals(-1f, EqClarity.balance(-9f))
        assertEquals(0f, EqClarity.balance(Float.POSITIVE_INFINITY))
    }

    @Test
    fun labelsReadNaturally() {
        assertTrue(EqClarity.label(0).all { it.isDigit() })
        assertTrue(EqClarity.label(23).endsWith("K"))
    }
}
