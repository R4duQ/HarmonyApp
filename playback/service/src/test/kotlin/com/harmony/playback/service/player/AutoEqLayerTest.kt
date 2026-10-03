package com.harmony.playback.service.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class AutoEqLayerTest {
    private val rate = 48_000

    /** Plays [seconds] of mono [signal] through the layer, analysing as the processor does. */
    private fun AutoEqLayer.run(seconds: Float, signal: (Long) -> Float, from: Long = 0, out: ((Float) -> Unit)? = null): Long {
        val frames = (seconds * rate).toLong()
        for (n in from until from + frames) {
            val x = signal(n)
            analyze(x)
            val y = process(x, 0)
            out?.invoke(y)
        }
        return from + frames
    }

    private val white = Random(3).let { r -> { _: Long -> (r.nextFloat() * 2 - 1) * 0.3f } }

    @Test
    fun `a bright song gets warmer, faded in over its first seconds`() {
        // White noise is far brighter than any record: +3 dB per octave against pink.
        val layer = AutoEqLayer(rate, 1, ToneMemory()).apply { toneEnabled = true; startTrack("a") }
        layer.run(3f, white)
        val early = layer.toneDb.copyOf()
        layer.run(15f, white, from = 3L * rate)
        val settled = layer.toneDb.copyOf()
        assertTrue("bass ${settled[1]}", settled[1] > 1f)
        assertTrue("8 kHz ${settled[8]}", settled[8] < -2f)
        assertTrue("faded in: early ${early[8]} vs ${settled[8]}", kotlin.math.abs(early[8]) < kotlin.math.abs(settled[8]) * 0.5f)
        // The filters followed, never past the limits.
        assertEquals(settled[8], layer.appliedDb[8], 0.31f)
    }

    @Test
    fun `gains glide 0_3 dB per 100 ms`() {
        val layer = AutoEqLayer(rate, 1, ToneMemory()).apply {
            roomDb = FloatArray(10).also { it[3] = -4f }
            startTrack(null)
        }
        layer.run(0.1f, { 0f })
        assertEquals(-0.3f, layer.appliedDb[3], 0.001f)
        layer.run(2f, { 0f })
        assertEquals(-4f, layer.appliedDb[3], 0.001f)
    }

    @Test
    fun `a room correction shapes the sound by its amount`() {
        val layer = AutoEqLayer(rate, 1, ToneMemory()).apply {
            roomDb = FloatArray(10).also { it[3] = -4f }
            startTrack(null)
        }
        layer.run(2f, { 0f })
        var sum = 0.0
        var n = 0
        val sine = { k: Long -> 0.5f * sin(2 * PI * 250.0 * k / rate).toFloat() }
        layer.run(1f, sine, from = 0) { y -> sum += y * y; n++ }
        val outDb = 10 * log10(sum / n)
        val inDb = 20 * log10(0.5 / sqrt(2.0))
        assertEquals(-4.0, outDb - inDb, 0.6)
    }

    @Test
    fun `a song heard before starts with its correction in full`() {
        val memory = ToneMemory()
        val layer = AutoEqLayer(rate, 1, memory).apply { toneEnabled = true; startTrack("a") }
        layer.run(25f, white)
        val learned = layer.toneDb.copyOf()
        layer.startTrack("b")
        layer.run(1f, white)
        layer.startTrack("a")
        layer.run(0.5f, white)
        for (i in 0 until 10) assertEquals(learned[i], layer.toneDb[i], 0.3f)
    }

    @Test
    fun `nothing switched on, nothing changes`() {
        val layer = AutoEqLayer(rate, 1, ToneMemory()).apply { startTrack("a") }
        var maxDiff = 0f
        layer.run(2f, white) { }
        val r = Random(9)
        repeat(10_000) {
            val x = r.nextFloat() - 0.5f
            layer.analyze(x)
            maxDiff = maxOf(maxDiff, kotlin.math.abs(layer.process(x, 0) - x))
        }
        assertEquals(0f, maxDiff, 0f)
        assertEquals(0f, layer.headroomDb, 0f)
    }
}
