package com.harmony.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

class ClarityModelTest {

    // ---- Bands -------------------------------------------------------------

    @Test
    fun `bands run from 40 Hz to 16 kHz, rising`() {
        assertEquals(ClarityBands.COUNT + 1, ClarityBands.EDGES_HZ.size)
        assertEquals(40f, ClarityBands.EDGES_HZ.first(), 0.5f)
        assertEquals(16_000f, ClarityBands.EDGES_HZ.last(), 5f)
        for (k in 0 until ClarityBands.COUNT) {
            assertTrue(ClarityBands.EDGES_HZ[k] < ClarityBands.CENTERS_HZ[k])
            assertTrue(ClarityBands.CENTERS_HZ[k] < ClarityBands.EDGES_HZ[k + 1])
        }
    }

    @Test
    fun `a flat curve stays flat on the octaves`() {
        val oct = ClarityBands.toOctaves(FloatArray(ClarityBands.COUNT) { 2.5f })
        oct.forEach { assertEquals(2.5f, it, 1e-4f) }
    }

    @Test
    fun `band labels read naturally`() {
        assertEquals("60 Hz", ClarityBands.label(0))
        assertTrue(ClarityBands.label(12).endsWith("kHz"))
        assertTrue(ClarityBands.label(23).endsWith("kHz"))
    }

    // ---- FFT ---------------------------------------------------------------

    @Test
    fun `fft finds a sine in its bin and keeps its energy`() {
        val n = 1024
        val fft = RealFft(n)
        val x = FloatArray(n) { sin(2 * PI * 37 * it / n).toFloat() }
        val out = FloatArray(n / 2 + 1)
        fft.power(x, out)
        val peak = out.indices.maxBy { out[it] }
        assertEquals(37, peak)
        // Parseval: sum x² = (1/N) sum |X|² over all bins (both halves).
        val time = x.sumOf { (it * it).toDouble() }
        val freq = (out[0] + out[n / 2] + 2 * (1 until n / 2).sumOf { out[it].toDouble() }) / n
        assertEquals(time, freq, time * 1e-3)
    }

    // ---- Filters -----------------------------------------------------------

    @Test
    fun `fitted filters land on an even lift and stay smooth between bands`() {
        val bank = ClarityFilterBank(48_000)
        val gains = FloatArray(ClarityBands.COUNT)
        bank.fit(FloatArray(ClarityBands.COUNT) { 4f }, gains)
        for (k in 1 until ClarityBands.COUNT - 1) {
            assertEquals("band $k", 4f, bank.responseDb(gains, ClarityBands.CENTERS_HZ[k]), 0.6f)
        }
        // Between centres, from 100 Hz to 12 kHz.
        var f = 100f
        while (f < 12_000f) {
            assertEquals("at $f Hz", 4f, bank.responseDb(gains, f), 1.0f)
            f *= 1.07f
        }
    }

    @Test
    fun `fitted filters follow a curve that moves from band to band`() {
        val bank = ClarityFilterBank(44_100)
        val target = FloatArray(ClarityBands.COUNT) { (3.5 * sin(it * 0.6)).toFloat() }
        val gains = FloatArray(ClarityBands.COUNT)
        bank.fit(target, gains)
        for (k in 1 until ClarityBands.COUNT - 1) {
            assertEquals("band $k", target[k], bank.responseDb(gains, ClarityBands.CENTERS_HZ[k]), 1.0f)
        }
    }

    @Test
    fun `bands above what the sample rate carries are left out`() {
        val bank = ClarityFilterBank(22_050)
        assertTrue(bank.active.first())
        assertTrue(!bank.active.last())
        val gains = FloatArray(ClarityBands.COUNT)
        bank.fit(FloatArray(ClarityBands.COUNT) { 3f }, gains)
        assertEquals(0f, gains.last(), 0f)
    }

    // ---- Model -------------------------------------------------------------

    /** Band powers of pink noise at [db] dBFS per octave, roughly: equal power per octave. */
    private fun pink(db: Float = -20f): FloatArray = FloatArray(ClarityBands.COUNT) { k ->
        val octaves = (ln(ClarityBands.EDGES_HZ[k + 1] / ClarityBands.EDGES_HZ[k]) / ln(2f))
        10f.pow(db / 10f) * octaves
    }

    private fun ClarityModel.run(power: FloatArray, seconds: Float, s: ClaritySettings = ClaritySettings()) {
        repeat((seconds / FRAME).toInt()) { analyze(power, FRAME, s) }
    }

    @Test
    fun `silence leaves everything flat`() {
        val m = ClarityModel()
        m.run(FloatArray(ClarityBands.COUNT), 1f)
        assertTrue(m.silent)
        m.targetDb.forEach { assertEquals(0f, it, 0f) }
        assertEquals(0f, m.matchDb, 0f)
    }

    @Test
    fun `an even, steady sound needs almost nothing`() {
        val m = ClarityModel()
        m.run(pink(), 4f)
        assertTrue(!m.silent)
        m.targetDb.forEachIndexed { k, v -> assertTrue("band $k: $v", abs(v) < 1f) }
    }

    @Test
    fun `a sound covered by its neighbours is brought out`() {
        val m = ClarityModel()
        val p = pink()
        val k = 12
        p[k] *= 10f.pow(-2f) // 20 dB under its neighbours
        m.run(p, 4f, ClaritySettings(recover = 1f, tame = 0f))
        assertTrue("recover ${m.recoverDb[k]}", m.recoverDb[k] > 2f)
        assertTrue("target ${m.targetDb[k]}", m.targetDb[k] > 1f)
        // Its neighbours are clear: they get much less.
        assertTrue(m.recoverDb[k - 3] < 0.5f && m.recoverDb[k + 3] < 0.5f)
    }

    @Test
    fun `what is buried deep or can't be heard is left alone`() {
        val m = ClarityModel()
        val p = pink()
        p[12] *= 10f.pow(-6f) // 60 dB under: not there to bring out
        p[23] = 1e-11f // the empty top of a lossy file
        m.run(p, 4f, ClaritySettings(recover = 1f, tame = 0f))
        assertEquals(0f, m.recoverDb[12], 0.05f)
        assertEquals(0f, m.recoverDb[23], 0.05f)
    }

    @Test
    fun `a resonance is held back`() {
        val m = ClarityModel()
        val p = pink()
        val k = 17
        p[k] *= 10f.pow(1.2f) // 12 dB over its neighbours
        m.run(p, 4f, ClaritySettings(recover = 0f, tame = 1f))
        assertTrue("tame ${m.tameDb[k]}", m.tameDb[k] < -3f)
        assertTrue(m.targetDb[k] < -1.5f)
        assertTrue(abs(m.tameDb[k - 4]) < 0.5f)
    }

    @Test
    fun `a band that suddenly pushes forward is tamed, and Clarity gets used to a song's own balance`() {
        val m = ClarityModel()
        val normal = pink()
        m.run(normal, 4f, ClaritySettings(recover = 0f, tame = 1f))
        val k = 8
        // Broad: three bands jump 9 dB together, so it isn't a resonance, it's the song changing.
        val loud = normal.copyOf().also { for (j in k - 1..k + 1) it[j] *= 10f.pow(0.9f) }
        m.run(loud, 0.1f, ClaritySettings(recover = 0f, tame = 1f))
        assertTrue("tame ${m.tameDb[k]}", m.tameDb[k] < -1f)
        // After a while it's the song's balance, and nothing is held back any more.
        m.run(loud, 15f, ClaritySettings(recover = 0f, tame = 1f))
        assertTrue("tame ${m.tameDb[k]}", m.tameDb[k] > -0.5f)
    }

    @Test
    fun `bias leans one way or the other`() {
        val even = ClaritySettings()
        assertEquals(1f, even.recoverWeight, 1e-5f)
        assertEquals(1f, even.tameWeight, 1e-5f)
        val s = ClaritySettings(recover = 0.5f, tame = 0.5f, bias = 1f)
        assertEquals(1.6f, s.recoverWeight, 1e-5f)
        assertEquals(0.4f, s.tameWeight, 1e-5f)
        val t = s.copy(bias = -1f)
        assertEquals(0.4f, t.recoverWeight, 1e-5f)
        assertEquals(1.6f, t.tameWeight, 1e-5f)
        assertEquals(ClaritySettings.MAX_WEIGHT, ClaritySettings(recover = 1f, bias = 1f).recoverWeight, 1e-5f)
    }

    @Test
    fun `brighten tilts around 1 kHz and the level is matched`() {
        val m = ClarityModel()
        m.run(pink(), 2f, ClaritySettings(recover = 0f, tame = 0f, brighten = 1f))
        assertTrue(m.targetDb.first() < -2f)
        assertTrue(m.targetDb.last() > 2.5f)
        val pivot = ClarityBands.CENTERS_HZ.indices.minBy { abs(ClarityBands.CENTERS_HZ[it] - 1000f) }
        assertEquals(0f, m.targetDb[pivot], 0.5f)
        // Darker takes the treble's loudness away: matching turns it back up, and the other way round.
        val dark = ClarityModel()
        dark.run(pink(), 2f, ClaritySettings(recover = 0f, tame = 0f, brighten = -1f))
        assertTrue("match ${dark.matchDb} vs ${m.matchDb}", dark.matchDb > m.matchDb)
    }

    @Test
    fun `boosting is matched by turning the level down`() {
        val m = ClarityModel()
        val p = pink()
        for (k in 8..14) p[k] *= 10f.pow(-2f)
        m.run(p, 2f, ClaritySettings(recover = 1f, tame = 0f))
        assertTrue(m.targetDb.max() > 1f)
        assertTrue("match ${m.matchDb}", m.matchDb < 0f)
    }

    @Test
    fun `presets are found again from their settings`() {
        ClarityPreset.entries.forEach { assertEquals(it, ClarityPreset.matching(it.settings)) }
        assertNull(ClarityPreset.matching(ClaritySettings(recover = 0.12f)))
        assertEquals(ClarityPreset.BALANCED, ClarityPreset.matching(ClaritySettings()))
        // Boost is kept when a preset is picked, so it doesn't change which one is on.
        assertEquals(ClarityPreset.DETAIL, ClarityPreset.matching(ClarityPreset.DETAIL.settings.copy(boostDb = 2f)))
    }

    @Test
    fun `settings are kept in range`() {
        val c = ClaritySettings(recover = 3f, tame = -1f, bias = 9f, brighten = -9f, boostDb = 40f).clamped()
        assertEquals(ClaritySettings(1f, 0f, 1f, -1f, ClaritySettings.MAX_BOOST_DB), c)
    }

    @Test
    fun `the ear hears 3 kHz best and deep bass least`() {
        assertTrue(ClarityDesign.earDb(3300f) > 2f)
        assertTrue(ClarityDesign.earDb(60f) < -10f)
        assertEquals(0f, ClarityDesign.earDb(1000f), 1e-4f)
        assertTrue(ClarityDesign.quietDb(1000f) < 5f && ClarityDesign.quietDb(16_000f) > 40f)
    }

    private companion object {
        const val FRAME = 512f / 48_000f
    }
}
