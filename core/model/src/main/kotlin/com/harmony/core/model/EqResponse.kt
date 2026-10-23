package com.harmony.core.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The equalizer's response as the native engine builds it (MobileEqEngine.cpp:
 * designBand), for drawing: a low shelf at 31 Hz, peaks from 62 Hz to 8 kHz
 * whose Q narrows as their gain grows, a high shelf at 16 kHz, and the
 * automatic preamp that keeps the loudest point of the curve from clipping.
 */
object EqResponse {
    private const val SAMPLE_RATE = 48_000.0
    private const val SHELF_Q = 0.70710678118654752
    private const val PREAMP_COMPENSATION = 0.65f

    /** The gain of the bands alone at each of [frequenciesHz], in dB. */
    fun bandsDb(gainsDb: List<Float>, frequenciesHz: FloatArray): FloatArray {
        val gains = EqSettings.sanitize(gainsDb)
        val filters = List(EqSettings.BAND_COUNT) { design(it, gains[it].toDouble()) }
        return FloatArray(frequenciesHz.size) { i ->
            var db = 0.0
            for (f in filters) db += 20.0 * log10(max(1e-9, f.magnitudeAt(frequenciesHz[i].toDouble())))
            db.toFloat()
        }
    }

    /**
     * The headroom the engine takes back for [gainsDb], in dB (zero or
     * negative): 65 % of the loudest point of the whole curve.
     */
    fun autoPreampDb(gainsDb: List<Float>): Float {
        val grid = FloatArray(74) { i -> 20f * 1000f.pow(i / 73f) }
        val peak = bandsDb(gainsDb, grid + EqSettings.BAND_CENTERS_HZ.toFloatArray()).maxOrNull() ?: 0f
        return -PREAMP_COMPENSATION * max(0f, peak)
    }

    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        fun magnitudeAt(frequency: Double): Double {
            val w = 2.0 * PI * frequency / SAMPLE_RATE
            val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
            val nr = b0 + b1 * c1 + b2 * c2
            val ni = -(b1 * s1 + b2 * s2)
            val dr = 1.0 + a1 * c1 + a2 * c2
            val di = -(a1 * s1 + a2 * s2)
            return sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
        }
    }

    private fun normalised(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
        Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

    private fun design(band: Int, gainDb: Double): Biquad {
        val f = EqSettings.BAND_CENTERS_HZ[band].toDouble()
        return when (band) {
            0 -> shelf(f, gainDb, low = true)
            EqSettings.BAND_COUNT - 1 -> shelf(f, gainDb, low = false)
            else -> peaking(f, gainDb, 1.2 + 0.8 * min(abs(gainDb), 12.0) / 12.0)
        }
    }

    private fun peaking(f: Double, gainDb: Double, q: Double): Biquad {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f / SAMPLE_RATE
        val cosW = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        return normalised(1 + alpha * a, -2 * cosW, 1 - alpha * a, 1 + alpha / a, -2 * cosW, 1 - alpha / a)
    }

    private fun shelf(f: Double, gainDb: Double, low: Boolean): Biquad {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f / SAMPLE_RATE
        val cosW = cos(w0)
        val k = 2.0 * sqrt(a) * sin(w0) / (2.0 * SHELF_Q)
        return if (low) {
            normalised(
                a * ((a + 1) - (a - 1) * cosW + k), 2 * a * ((a - 1) - (a + 1) * cosW), a * ((a + 1) - (a - 1) * cosW - k),
                (a + 1) + (a - 1) * cosW + k, -2 * ((a - 1) + (a + 1) * cosW), (a + 1) + (a - 1) * cosW - k,
            )
        } else {
            normalised(
                a * ((a + 1) + (a - 1) * cosW + k), -2 * a * ((a - 1) + (a + 1) * cosW), a * ((a + 1) + (a - 1) * cosW - k),
                (a + 1) - (a - 1) * cosW + k, 2 * ((a - 1) - (a + 1) * cosW), (a + 1) - (a - 1) * cosW - k,
            )
        }
    }
}
