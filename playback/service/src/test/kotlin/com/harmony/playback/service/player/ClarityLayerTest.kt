package com.harmony.playback.service.player

import com.harmony.core.model.ClarityBands
import com.harmony.core.model.ClaritySettings
import com.harmony.core.model.PinkNoise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class ClarityLayerTest {
    private val rate = 48_000

    /** Runs [seconds] of [signal] (mono, the same on both channels) through [layer]; returns the left output. */
    private fun run(layer: ClarityLayer, seconds: Float, signal: (Int) -> Float): Pair<FloatArray, FloatArray> {
        val frames = (seconds * rate).toInt()
        val input = FloatArray(frames)
        val output = FloatArray(frames)
        for (i in 0 until frames) {
            val x = signal(i)
            input[i] = x
            output[i] = layer.process(x, 0)
            layer.process(x, 1)
            layer.analyze(x)
        }
        return input to output
    }

    private fun rmsDb(x: FloatArray, from: Int = x.size / 2): Double {
        var sum = 0.0
        for (i in from until x.size) sum += x[i].toDouble() * x[i]
        return 10 * log10(sum / (x.size - from))
    }

    /** Level of [hz] in the second half of [x], dB (Goertzel). */
    private fun toneDb(x: FloatArray, hz: Double): Double {
        val from = x.size / 2
        val w = 2 * PI * hz / rate
        val c = 2 * cos(w)
        var s1 = 0.0
        var s2 = 0.0
        for (i in from until x.size) {
            val s = x[i] + c * s1 - s2
            s2 = s1; s1 = s
        }
        val n = x.size - from
        return 20 * log10(sqrt(s1 * s1 + s2 * s2 - c * s1 * s2) * 2 / n)
    }

    private fun band(hz: Float) = ClarityBands.CENTERS_HZ.indices.minBy { abs(ClarityBands.CENTERS_HZ[it] - hz) }

    @Test
    fun `silence stays silent and flat`() {
        val layer = ClarityLayer(rate, 2)
        val (_, out) = run(layer, 1f) { 0f }
        assertTrue(out.all { it == 0f })
        assertTrue(layer.appliedDb.all { it == 0f })
        assertTrue(layer.readout().silent)
    }

    @Test
    fun `steady pink noise is barely touched and keeps its level`() {
        val layer = ClarityLayer(rate, 2)
        val pink = PinkNoise(3)
        val (input, output) = run(layer, 6f) { pink.next() * 0.5f }
        val mean = layer.appliedDb.map { abs(it) }.average()
        assertTrue("mean ${layer.appliedDb.toList()}", mean < 1.2)
        assertTrue("max ${layer.appliedDb.toList()}", layer.appliedDb.all { abs(it) < 3f })
        assertEquals(rmsDb(input), rmsDb(output), 1.0)
    }

    @Test
    fun `a softer tone just above a loud one is brought out`() {
        val layer = ClarityLayer(rate, 2).apply { settings = ClaritySettings(recover = 1f, tame = 0f) }
        val loud = 590.0
        val quiet = ClarityBands.CENTERS_HZ[band(740f)].toDouble()
        val (input, output) = run(layer, 3f) { i ->
            (0.3 * sin(2 * PI * loud * i / rate) + 0.03 * sin(2 * PI * quiet * i / rate)).toFloat()
        }
        val k = band(quiet.toFloat())
        assertTrue("applied ${layer.appliedDb[k]}", layer.appliedDb[k] > 0.5f)
        assertTrue(layer.appliedDb[k] > layer.appliedDb[band(loud.toFloat())])
        // Against the loud one, it comes forward.
        val lift = (toneDb(output, quiet) - toneDb(output, loud)) - (toneDb(input, quiet) - toneDb(input, loud))
        assertTrue("lift $lift", lift > 0.3)
    }

    @Test
    fun `a ringing resonance over the music is held back`() {
        val layer = ClarityLayer(rate, 2).apply { settings = ClaritySettings(recover = 0f, tame = 1f) }
        val pink = PinkNoise(5)
        val ring = ClarityBands.CENTERS_HZ[band(3000f)].toDouble()
        val (input, output) = run(layer, 4f) { i -> pink.next() * 0.3f + (0.1 * sin(2 * PI * ring * i / rate)).toFloat() }
        val k = band(ring.toFloat())
        assertTrue("applied ${layer.appliedDb[k]}", layer.appliedDb[k] < -1f)
        val drop = (toneDb(output, ring) - rmsDb(output)) - (toneDb(input, ring) - rmsDb(input))
        assertTrue("drop $drop", drop < -1.0)
    }

    @Test
    fun `boost turns the level up, smoothly`() {
        val layer = ClarityLayer(rate, 2).apply { settings = ClaritySettings(recover = 0f, tame = 0f, boostDb = 6f) }
        val (input, output) = run(layer, 3f) { i -> (0.1 * sin(2 * PI * 440.0 * i / rate)).toFloat() }
        assertEquals(6.0, rmsDb(output) - rmsDb(input), 0.6)
        // No step anywhere: sample to sample the output never moves more than the sine itself could, boosted.
        var worst = 0f
        for (i in 1 until output.size) worst = maxOf(worst, abs(output[i] - output[i - 1]))
        assertTrue("step $worst", worst < 0.2f * 2 * PI.toFloat() * 440f / rate * 1.2f)
    }

    @Test
    fun `it keeps up with real time with plenty to spare`() {
        val layer = ClarityLayer(rate, 2)
        val pink = PinkNoise(9)
        run(layer, 2f) { pink.next() * 0.3f } // warm up the JIT
        val start = System.nanoTime()
        run(layer, 10f) { pink.next() * 0.3f }
        val ms = (System.nanoTime() - start) / 1e6
        assertTrue("10 s of stereo took $ms ms", ms < 2_500)
    }

    @Test
    fun `a 96 kHz stream uses a longer window and still works`() {
        val layer = ClarityLayer(96_000, 1)
        assertEquals(4096, layer.fftSize)
        for (i in 0 until 96_000) {
            val x = (0.2 * sin(2 * PI * 1000.0 * i / 96_000)).toFloat()
            layer.process(x, 0)
            layer.analyze(x)
        }
        assertTrue(!layer.readout().silent)
    }
}
