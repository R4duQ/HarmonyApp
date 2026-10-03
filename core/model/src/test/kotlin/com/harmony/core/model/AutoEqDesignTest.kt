package com.harmony.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

class AutoEqDesignTest {
    private val rate = 48_000

    private fun meterOf(seconds: Float, signal: (Int) -> Float): FloatArray {
        val meter = OctaveBandMeter(rate)
        val warmup = rate / 2
        repeat(warmup) { meter.add(signal(it)) }
        meter.reset()
        repeat((seconds * rate).toInt()) { meter.add(signal(warmup + it)) }
        return meter.levelsDb()
    }

    @Test
    fun `a sine reads its level in its own band and much less elsewhere`() {
        val amp = 0.5f
        val levels = meterOf(2f) { n -> amp * sin(2 * PI * 1000.0 * n / rate).toFloat() }
        val expected = 20 * log10(amp / kotlin.math.sqrt(2f))
        assertEquals(expected, levels[5], 0.3f)
        assertTrue("2 kHz band ${levels[6]}", levels[6] < expected - 9f)
        assertTrue("250 Hz band ${levels[3]}", levels[3] < expected - 25f)
    }

    @Test
    fun `pink noise reads flat across the octaves`() {
        val pink = PinkNoise(7)
        val levels = meterOf(12f) { pink.next() }
        val mid = levels.slice(1..8)
        val spread = mid.max() - mid.min()
        assertTrue("levels ${levels.toList()}", spread < 2f)
        // 31 Hz and 16 kHz are close too (near DC and near Nyquist the filters warp a little).
        assertTrue(abs(levels[0] - mid.average().toFloat()) < 2.5f)
        assertTrue(abs(levels[9] - mid.average().toFloat()) < 2.5f)
    }

    @Test
    fun `bands above what the sample rate carries read NaN`() {
        val levels = OctaveBandMeter(16_000).apply { repeat(1000) { add(0.1f) } }.levelsDb()
        assertTrue(levels[8].isNaN() && levels[9].isNaN())
        assertTrue(!levels[7].isNaN())
    }

    @Test
    fun `a song shaped like the reference is left alone`() {
        val song = FloatArray(10) { AutoEqDesign.REFERENCE_DB[it] - 20f }
        val c = AutoEqDesign.toneCorrection(song)
        c.forEach { assertEquals(0f, it, 0.01f) }
    }

    @Test
    fun `a thin song gets a little bass, a harsh one a little less top, within limits`() {
        val thin = FloatArray(10) { AutoEqDesign.REFERENCE_DB[it] - 20f + if (it <= 2) -8f else 0f }
        val c = AutoEqDesign.toneCorrection(thin)
        assertTrue(c[1] > 1.5f && c[1] <= AutoEqDesign.TONE_MAX_BOOST_DB)
        assertTrue(c[5] < 0.5f)
        val harsh = FloatArray(10) { AutoEqDesign.REFERENCE_DB[it] - 20f + if (it in 6..8) 12f else 0f }
        val h = AutoEqDesign.toneCorrection(harsh)
        assertTrue(h[7] < -2f && h[7] >= -AutoEqDesign.TONE_MAX_CUT_DB)
    }

    @Test
    fun `a missing top octave is not boosted`() {
        // An MP3 with nothing above 16 kHz.
        val song = FloatArray(10) { AutoEqDesign.REFERENCE_DB[it] - 20f }.also { it[9] = -90f }
        val c = AutoEqDesign.toneCorrection(song)
        assertTrue("16 kHz ${c[9]}", c[9] <= 0.01f)
    }

    @Test
    fun `a flat speaker in a dead room needs no correction`() {
        val played = FloatArray(10) { -15f }
        val heard = FloatArray(10) { -40f }
        val ambient = FloatArray(10) { -75f }
        val r = AutoEqDesign.roomCorrection(played, heard, ambient, clipped = false) as AutoEqDesign.RoomResult.Measured
        r.gainsDb.forEach { assertEquals(0f, it, 0.05f) }
    }

    @Test
    fun `a boomy room gets its boom taken out, and deep bass is never boosted`() {
        val played = FloatArray(10) { -15f }
        val heard = FloatArray(10) { -40f }.also { it[3] = -32f; it[0] = -60f; it[1] = -55f }
        val ambient = FloatArray(10) { -80f }
        val r = AutoEqDesign.roomCorrection(played, heard, ambient, clipped = false) as AutoEqDesign.RoomResult.Measured
        assertTrue("250 Hz ${r.gainsDb[3]}", r.gainsDb[3] < -3f)
        assertTrue(r.gainsDb[0] <= 0f && r.gainsDb[1] <= 0f)
        assertTrue(r.gainsDb.all { it >= -AutoEqDesign.ROOM_MAX_CUT_DB && it <= AutoEqDesign.ROOM_MAX_BOOST_DB })
    }

    @Test
    fun `a test sound lost in the noise, or overloading the microphone, gives no correction`() {
        val played = FloatArray(10) { -15f }
        assertEquals(
            AutoEqDesign.RoomResult.TooQuiet,
            AutoEqDesign.roomCorrection(played, FloatArray(10) { -60f }, FloatArray(10) { -64f }, clipped = false),
        )
        assertEquals(
            AutoEqDesign.RoomResult.TooLoud,
            AutoEqDesign.roomCorrection(played, FloatArray(10) { -10f }, FloatArray(10) { -70f }, clipped = true),
        )
    }

    @Test
    fun `a quiet room lifts nothing, a bus lifts the bass most`() {
        val quiet = FloatArray(10) { AutoEqDesign.QUIET_SPL[it] - 5f }
        AutoEqDesign.noiseBoost(quiet).forEach { assertEquals(0f, it, 0.001f) }
        val bus = floatArrayOf(80f, 78f, 72f, 64f, 58f, 52f, 46f, 40f, Float.NaN, Float.NaN)
        val b = AutoEqDesign.noiseBoost(bus)
        assertTrue(b[1] > b[5] && b[5] > b[8])
        assertTrue(b.all { it in 0f..AutoEqDesign.NOISE_MAX_BOOST_DB })
    }

    @Test
    fun `layers add up within the overall limits`() {
        val big = List(10) { 6f }
        val total = AutoEqDesign.combine(big, big, big)
        total.forEach { assertEquals(AutoEqDesign.TOTAL_MAX_BOOST_DB, it, 0f) }
    }

    @Test
    fun `outputs are told apart and sorted into headphones and speakers`() {
        val bt = AudioOutput(AudioOutputType.BLUETOOTH, " JBL Flip 5 ")
        assertEquals("BLUETOOTH:jbl flip 5", AutoEqDesign.outputKey(bt))
        assertEquals(ListeningKind.UNKNOWN, AutoEqDesign.kind(bt, null))
        assertEquals(ListeningKind.SPEAKER, AutoEqDesign.kind(bt, OutputForm.SPEAKER))
        assertEquals(ListeningKind.HEADPHONES, AutoEqDesign.kind(bt, OutputForm.EARBUDS))
        assertEquals(ListeningKind.SPEAKER, AutoEqDesign.kind(AudioOutput(), null))
        assertEquals(ListeningKind.HEADPHONES, AutoEqDesign.kind(AudioOutput(AudioOutputType.WIRED), null))
    }

    @Test
    fun `noise is followed quickly up and slowly down`() {
        val tracker = NoiseTracker()
        val quiet = FloatArray(10) { AutoEqDesign.QUIET_SPL[it] - 10f - AutoEqDesign.MIC_SPL_OFFSET_DB }
        val loud = FloatArray(10) { AutoEqDesign.QUIET_SPL[it] + 20f - AutoEqDesign.MIC_SPL_OFFSET_DB }
        tracker.add(quiet, 0.5f).forEach { assertEquals(0f, it, 0.001f) }
        var lift = FloatArray(10)
        repeat(8) { lift = tracker.add(loud, 0.5f) } // 4 s of a bus
        assertTrue("after 4 s ${lift[5]}", lift[5] > 4.5f)
        repeat(4) { lift = tracker.add(quiet, 0.5f) } // 2 s after it leaves
        assertTrue("still lifted ${lift[5]}", lift[5] > 2f)
        repeat(60) { lift = tracker.add(quiet, 0.5f) }
        assertEquals(0f, lift[5], 0.01f)
    }
}
