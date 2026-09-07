package com.harmony.domain.analysis.perceptual

import com.harmony.domain.analysis.model.PerceptualProfile
import com.harmony.domain.analysis.model.RawFeatures
import kotlin.math.exp

/**
 * Maps measured DSP features to perceptual estimates.
 *
 * Honesty first: these are HEURISTICS, not learned models. Each score is a
 * hand-tuned combination of signal facts that correlate with the perceptual
 * quality. They are good enough to drive shuffle weighting and mood filters
 * (relative ordering within one person's library is what matters there);
 * they are not Spotify-grade absolute judgments. The upgrade path — a small
 * on-device TFLite model trained on these same inputs — plugs in behind this
 * exact interface without touching callers (flagged in Phase 1, still open).
 *
 * Every input is squashed to 0..1 with documented anchor points, so the
 * output stays stable across libraries with wildly different mastering.
 * Anchors were chosen from published feature distributions of large music
 * datasets and rounded to memorable values.
 */
object PerceptualMapper {

    fun map(f: RawFeatures): PerceptualProfile {
        // ---- Normalized building blocks (0..1) ----
        val loudness = ramp(f.lufs, from = -35f, to = -5f)          // quiet .. loud master
        val tempo = ramp(f.bpm, from = 60f, to = 180f)
        val beatStrength = clamp01(f.beatConfidence)
        val brightnessRaw = ramp(f.centroid, from = 500f, to = 4000f)
        val bassiness = clamp01(f.bass * 2.5f)                      // bass fraction rarely exceeds 0.4
        val noisiness = clamp01(f.flatness * 4f)                    // tonal ~0.05, noise ~0.4+
        val dynamics = ramp(f.dynamicRange, from = 3f, to = 20f)    // brickwalled .. very dynamic
        val tonality = 1f - noisiness
        val onsetDensity = beatStrength * tempo                     // proxy for rhythmic activity

        // ---- Perceptual estimates ----
        val energy = clamp01(0.45f * loudness + 0.30f * tempo + 0.25f * brightnessRaw)

        // Danceable = steady beat in the groove tempo range + bass presence.
        val grooveTempo = bell(f.bpm, center = 115f, width = 45f)
        val danceability = clamp01(0.50f * beatStrength + 0.30f * grooveTempo + 0.20f * bassiness)

        // Acoustic sources: dynamic, tonal, not brutally loud, softer top end.
        val acousticness = clamp01(
            0.35f * dynamics + 0.30f * tonality + 0.20f * (1f - loudness) + 0.15f * (1f - brightnessRaw)
        )

        // Vocals add mid-band spectral variance; high MFCC variance in the
        // vocal-formant coefficients is our (rough) vocal-presence proxy.
        val vocalProxy = clamp01(f.mfccStd.drop(1).take(5).sum() / 25f)
        val instrumentalness = clamp01(1f - vocalProxy)

        val brightness = brightnessRaw
        val warmth = clamp01(0.55f * bassiness + 0.25f * (1f - brightnessRaw) + 0.20f * tonality)

        val aggressiveness = clamp01(
            0.35f * loudness + 0.25f * noisiness + 0.20f * brightnessRaw + 0.20f * tempo
        )
        val calmness = clamp01(
            0.40f * (1f - loudness) + 0.25f * (1f - onsetDensity) + 0.20f * dynamics + 0.15f * tonality
        )

        // Mode is the strongest simple valence signal we have; temper it with
        // energy so an anguished loud minor track doesn't read "sad and calm".
        val modeBoost = if (f.isMajor) 1f else 0f
        val happiness = clamp01(0.40f * modeBoost + 0.35f * energy + 0.25f * danceability)
        val sadness = clamp01(0.45f * (1f - modeBoost) + 0.30f * (1f - energy) + 0.25f * (1f - tempo))

        // Tension: dissonance proxy (chroma spread beyond the key triad) + drive.
        val chromaSpread = clamp01(chromaEntropy(f.chroma))
        val tension = clamp01(0.40f * chromaSpread + 0.35f * aggressiveness + 0.25f * (1f - modeBoost))

        return PerceptualProfile(
            energy = energy,
            danceability = danceability,
            acousticness = acousticness,
            instrumentalness = instrumentalness,
            brightness = brightness,
            warmth = warmth,
            aggressiveness = aggressiveness,
            calmness = calmness,
            happiness = happiness,
            sadness = sadness,
            tension = tension,
        )
    }

    // ---- math helpers ----

    private fun clamp01(v: Float) = v.coerceIn(0f, 1f)

    /** Linear ramp: 0 at [from], 1 at [to]. */
    private fun ramp(v: Float, from: Float, to: Float) = clamp01((v - from) / (to - from))

    /** Gaussian bell peaking at [center]. */
    private fun bell(v: Float, center: Float, width: Float): Float {
        val d = (v - center) / width
        return exp((-d * d).toDouble()).toFloat()
    }

    /** Normalized Shannon entropy of the chroma vector: 0 = one pitch class, 1 = uniform. */
    private fun chromaEntropy(chroma: FloatArray): Float {
        val sum = chroma.sum().takeIf { it > 1e-9f } ?: return 0f
        var h = 0.0
        for (c in chroma) {
            val p = (c / sum).toDouble()
            if (p > 1e-9) h -= p * kotlin.math.ln(p)
        }
        return (h / kotlin.math.ln(12.0)).toFloat()
    }
}
