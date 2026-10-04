package com.harmony.core.model

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Winamp equalizer, as reconstructed by Felipe Rivera's eq-xmms and
 * carried on by XMMS, Beep Media Player and VLC: Nullsoft never published
 * the original, and this is the open design built to sound like it.
 *
 *  - Ten bands at Winamp's own frequencies (60 Hz ... 16 kHz), not ISO octaves.
 *  - Each band is a second-order band-pass one octave wide, with unity gain
 *    and zero phase at its centre:
 *        y[n] = α·(x[n] − x[n−2]) + γ·y[n−1] − β·y[n−2]
 *  - The bands run in PARALLEL on the dry signal and are added back:
 *        out = preamp · (x + Σ (10^(dB/20) − 1) · yᵢ)
 *    so a lone band reaches exactly its slider value at its centre, and
 *    neighbouring bands overlap and add up the way Winamp's did.
 *  - Sliders and preamp run from −20 to +20 dB, as in Winamp 2.
 *
 * Pure arithmetic, shared by the audio processor and the screen's curve.
 */
object WinampEqDesign {
    val FREQUENCIES_HZ = floatArrayOf(60f, 170f, 310f, 600f, 1_000f, 3_000f, 6_000f, 12_000f, 14_000f, 16_000f)
    val LABELS = listOf("60", "170", "310", "600", "1K", "3K", "6K", "12K", "14K", "16K")
    const val MAX_DB = 20f

    /**
     * The classic Winamp presets, as (preamp, ten band gains) in dB. Values
     * as VLC ships them from Winamp; the preamp is shifted to this mixer's
     * unity (VLC counts +12 dB as unity because it scales its input by 1/4).
     */
    val PRESETS: List<Pair<String, Pair<Float, List<Float>>>> = listOf(
        "Flat" to (0f to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        "Classical" to (0f to listOf(0f, 0f, 0f, 0f, 0f, 0f, -7.2f, -7.2f, -7.2f, -9.6f)),
        "Club" to (-6f to listOf(0f, 0f, 8f, 5.6f, 5.6f, 5.6f, 3.2f, 0f, 0f, 0f)),
        "Dance" to (-7f to listOf(9.6f, 7.2f, 2.4f, 0f, 0f, -5.6f, -7.2f, -7.2f, 0f, 0f)),
        "Full Bass" to (-7f to listOf(-8f, 9.6f, 9.6f, 5.6f, 1.6f, -4f, -8f, -10.4f, -11.2f, -11.2f)),
        "Full Bass & Treble" to (-8f to listOf(7.2f, 5.6f, 0f, -7.2f, -4.8f, 1.6f, 8f, 11.2f, 12f, 12f)),
        "Full Treble" to (-9f to listOf(-9.6f, -9.6f, -9.6f, -4f, 2.4f, 11.2f, 16f, 16f, 16f, 16.8f)),
        "Headphones" to (-8f to listOf(4.8f, 11.2f, 5.6f, -3.2f, -2.4f, 1.6f, 4.8f, 9.6f, 12.8f, 14.4f)),
        "Large Hall" to (-7f to listOf(10.4f, 10.4f, 5.6f, 5.6f, 0f, -4.8f, -4.8f, -4.8f, 0f, 0f)),
        "Live" to (-5f to listOf(-4.8f, 0f, 4f, 5.6f, 5.6f, 5.6f, 4f, 2.4f, 2.4f, 2.4f)),
        "Party" to (-6f to listOf(7.2f, 7.2f, 0f, 0f, 0f, 0f, 0f, 0f, 7.2f, 7.2f)),
        "Pop" to (-6f to listOf(-1.6f, 4.8f, 7.2f, 8f, 5.6f, 0f, -2.4f, -2.4f, -1.6f, -1.6f)),
        "Reggae" to (-4f to listOf(0f, 0f, 0f, -5.6f, 0f, 6.4f, 6.4f, 0f, 0f, 0f)),
        "Rock" to (-7f to listOf(8f, 4.8f, -5.6f, -8f, -3.2f, 4f, 8.8f, 11.2f, 11.2f, 11.2f)),
        "Ska" to (-6f to listOf(-2.4f, -4.8f, -4f, 0f, 4f, 5.6f, 8.8f, 9.6f, 11.2f, 9.6f)),
        "Soft" to (-7f to listOf(4.8f, 1.6f, 0f, -2.4f, 0f, 4f, 8f, 9.6f, 11.2f, 12f)),
        "Soft Rock" to (-5f to listOf(4f, 4f, 2.4f, 0f, -4f, -5.6f, -3.2f, 0f, 2.4f, 8.8f)),
        "Techno" to (-7f to listOf(8f, 5.6f, 0f, -5.6f, -4.8f, 0f, 8f, 9.6f, 9.6f, 8.8f)),
    )

    /**
     * Band-pass coefficients (α, β, γ) for each band at [sampleRate]. Bands
     * too close to the Nyquist frequency to be designed get zeros, i.e. no effect.
     */
    fun coefficients(sampleRate: Int): Array<FloatArray> {
        val rate = sampleRate.toDouble()
        val octave = 2.0.pow(0.5)            // one octave wide: edges at f/√2 and f·√2
        val half1 = 0.5 * (octave + 1.0)
        val half2 = 0.5 * (octave - 1.0)
        return Array(FREQUENCIES_HZ.size) { i ->
            val f = FREQUENCIES_HZ[i].toDouble()
            if (rate <= 0 || f > rate * 0.45) return@Array FloatArray(3)
            val theta1 = 2 * PI * f / rate
            val theta2 = theta1 / octave
            val s = sin(theta2)
            val product = sin(theta2 * half1) * sin(theta2 * half2)
            val den = s * 0.5 + product
            floatArrayOf(
                (product / den).toFloat(),                 // α
                ((s * 0.5 - product) / den).toFloat(),     // β
                (s * cos(theta1) / den).toFloat(),         // γ
            )
        }
    }

    /** A band's slider value as the weight its band-pass is added with. */
    fun weight(gainDb: Float): Float = 10f.pow(gainDb.coerceIn(-MAX_DB, MAX_DB) / 20f) - 1f

    /**
     * The mixer's gain in dB at [frequencyHz]: |preamp · (1 + Σ wᵢ·Bᵢ(e^jω))|.
     * This is what the screen draws, so the curve is the sound, not a sketch.
     */
    fun responseDb(gainsDb: List<Float>, preampDb: Float, frequencyHz: Float, sampleRate: Int = 44_100): Float =
        responseDb(gainsDb, preampDb, floatArrayOf(frequencyHz), sampleRate)[0]

    /** [responseDb] at many frequencies at once, designing the filters only once. */
    fun responseDb(gainsDb: List<Float>, preampDb: Float, frequenciesHz: FloatArray, sampleRate: Int = 44_100): FloatArray {
        val coeffs = coefficients(sampleRate)
        val weights = DoubleArray(coeffs.size) { weight(gainsDb.getOrElse(it) { 0f }).toDouble() }
        val preamp = preampDb.coerceIn(-MAX_DB, MAX_DB)
        return FloatArray(frequenciesHz.size) { k ->
            val w = 2 * PI * frequenciesHz[k] / sampleRate
            // z^-1 and z^-2 on the unit circle
            val c1 = cos(w); val s1 = -sin(w)
            val c2 = cos(2 * w); val s2 = -sin(2 * w)
            var re = 1.0
            var im = 0.0
            for (i in coeffs.indices) {
                val alpha = coeffs[i][0].toDouble()
                if (alpha == 0.0 || weights[i] == 0.0) continue
                val beta = coeffs[i][1].toDouble()
                val gamma = coeffs[i][2].toDouble()
                // B = α(1 − z⁻²) / (1 − γz⁻¹ + βz⁻²)
                val nRe = alpha * (1 - c2); val nIm = alpha * (-s2)
                val dRe = 1 - gamma * c1 + beta * c2; val dIm = -gamma * s1 + beta * s2
                val d = dRe * dRe + dIm * dIm
                re += weights[i] * (nRe * dRe + nIm * dIm) / d
                im += weights[i] * (nIm * dRe - nRe * dIm) / d
            }
            val magnitude = sqrt(re * re + im * im).coerceAtLeast(1e-9)
            (20 * log10(magnitude) + preamp).toFloat()
        }
    }

    /**
     * Winamp slider values that sound like the old Harmony equalizer's ten
     * octave bands ([octaveGainsDb] at 31 Hz … 16 kHz): each Winamp band takes
     * the octave curve's value at its own frequency, read off a straight line
     * between octaves on a log scale. Used once, to carry an old setting over.
     */
    fun fromOctaveBands(octaveGainsDb: List<Float>): List<Float> {
        val centers = EqSettings.BAND_CENTERS_HZ
        if (octaveGainsDb.size != centers.size) return List(FREQUENCIES_HZ.size) { 0f }
        return FREQUENCIES_HZ.map { f ->
            val x = log2(f / centers.first())
            val i = x.toInt().coerceIn(0, centers.size - 2)
            val t = (x - i).coerceIn(0f, 1f)
            val v = octaveGainsDb[i] + (octaveGainsDb[i + 1] - octaveGainsDb[i]) * t
            (kotlin.math.round(v * 10f) / 10f).coerceIn(-MAX_DB, MAX_DB)
        }
    }
}
