package com.harmony.data.shuffle

import com.harmony.core.dsp.ShuffleWeights
import com.harmony.domain.shuffle.model.ShuffleConfig
import com.harmony.domain.shuffle.model.SmartShuffleStyle
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Cold start: a song not yet measured in 24-D, but analysed by the older
 * pipeline, gets an approximate vector from that analysis so Smart Shuffle
 * can place it before the indexer reaches it. Unknown dimensions are NaN,
 * which the engine treats as "the library average".
 */
object ColdStartVectors {
    const val DIMS = 24
    private const val BANDS = 10

    /**
     * How the older analysis's three energy shares spread over the ten octave
     * bands: bass (< 250 Hz) over 31-125 Hz and half of 250 Hz, mid (250 Hz to
     * 4 kHz) over the other half, 500 Hz-2 kHz and half of 4 kHz, treble over
     * the rest.
     */
    private val BASS_SHARE = floatArrayOf(1f, 1f, 1f, 0.5f, 0f, 0f, 0f, 0f, 0f, 0f)
    private val MID_SHARE = floatArrayOf(0f, 0f, 0f, 0.5f, 1f, 1f, 1f, 0.5f, 0f, 0f)
    private val TREBLE_SHARE = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.5f, 1f, 1f)

    fun fromAnalysis(
        bassEnergy: Float,
        midEnergy: Float,
        trebleEnergy: Float,
        rms: Float,
        peak: Float,
        bpm: Float?,
        beatConfidence: Float?,
    ): FloatArray {
        val out = FloatArray(DIMS) { Float.NaN }
        val shares = listOf(bassEnergy, midEnergy, trebleEnergy).map { if (it.isFinite()) it.coerceAtLeast(0f) else 0f }
        val units = listOf(BASS_SHARE.sum(), MID_SHARE.sum(), TREBLE_SHARE.sum())
        val energy = FloatArray(BANDS) { k ->
            shares[0] * BASS_SHARE[k] / units[0] + shares[1] * MID_SHARE[k] / units[1] + shares[2] * TREBLE_SHARE[k] / units[2]
        }
        val total = energy.sum()
        if (total > 1e-9f) {
            // As the extractor does: each band's RMS level, L2-normalized.
            val amplitude = FloatArray(BANDS) { sqrt(energy[it]) }
            val norm = sqrt(amplitude.sumOf { (it * it).toDouble() }).toFloat()
            for (k in 0 until BANDS) out[k] = amplitude[k] / norm
            // 21: the centroid on the octave axis, from the same energies.
            out[21] = (energy.indices.sumOf { (it * energy[it]).toDouble() } / total / (BANDS - 1)).toFloat()
        }
        // 22: crest factor, 3..30 dB.
        if (rms > 1e-9f && peak > 0f && rms.isFinite() && peak.isFinite()) {
            val crestDb = 20f * log10(peak / rms)
            out[22] = ((crestDb - 3f) / 27f).coerceIn(0f, 1f)
        }
        // 23: pulse rate, from a confident tempo (beats per second, 3/s = 0.5).
        if (bpm != null && bpm > 0f && (beatConfidence ?: 0f) >= 0.3f) {
            val rate = bpm / 60f
            out[23] = rate / (rate + 3f)
        }
        return out
    }
}

/** The Smart Shuffle sheet's style and sliders, as engine weights. */
object BrainWeights {
    fun forConfig(config: ShuffleConfig): ShuffleWeights {
        val familiarity = config.familiarity.coerceIn(0f, 1f)
        val discovery = config.discovery.coerceIn(0f, 1f)
        val variety = config.variety.coerceIn(0f, 1f)
        return ShuffleWeights(
            similarity = 1.0f,
            markov = 0.15f + 0.25f * familiarity,
            affinity = 0.12f + 0.48f * familiarity,
            explore = 0.05f + 0.40f * discovery,
            temperature = 0.04f + 0.30f * variety,
            // Flow asks for seamless transitions: a stricter harshness limit.
            jerkThreshold = if (config.style == SmartShuffleStyle.FLOW) 1.05f else 1.25f,
            echoThreshold = 0.97f,
            minArtistSpacing = 3,
        )
    }

    /** How hard an energy target or a mood pulls (folded into the vibe term). */
    const val ENERGY_STEERING = 0.8f
    const val MOOD_STEERING = 0.9f
}
