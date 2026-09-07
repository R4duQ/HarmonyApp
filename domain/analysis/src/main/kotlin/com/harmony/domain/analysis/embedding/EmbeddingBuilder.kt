package com.harmony.domain.analysis.embedding

import com.harmony.domain.analysis.model.PerceptualProfile
import com.harmony.domain.analysis.model.RawFeatures
import kotlin.math.sqrt

/**
 * Builds the song embedding — the vector similarity search runs on.
 *
 * Dimensionality: 48.
 *   12 chroma (harmony/tonality)
 *   13 MFCC means (timbre) — z-scaled by fixed anchors
 *    6 MFCC stddevs 1..6 (timbral variability / vocal presence)
 *    6 rhythm+dynamics: tempo, beat conf, dynamics, loudness, flatness, zcr
 *   11 perceptual scores
 *
 * Why hand-picked features and not the full 55: raw Hz-valued features
 * (centroid, rolloff) at native scale would dominate the distance metric by
 * sheer magnitude; everything entering the embedding is first squashed to a
 * comparable 0..1-ish range so no single axis owns the geometry. Weights bias
 * the metric toward timbre+harmony (what "sounds similar" mostly means)
 * over mood scores (which are derived and noisier).
 *
 * The vector is L2-normalized, so similarity = plain dot product downstream.
 */
object EmbeddingBuilder {

    const val EMBEDDING_DIM = 48

    // Section weights (applied before normalization).
    private const val W_CHROMA = 0.8f
    private const val W_MFCC = 1.2f
    private const val W_MFCC_STD = 0.8f
    private const val W_RHYTHM = 1.0f
    private const val W_PERCEPTUAL = 0.7f

    fun build(raw: RawFeatures, p: PerceptualProfile): FloatArray {
        val v = FloatArray(EMBEDDING_DIM)
        var i = 0

        // Chroma is already L1-normalized by the native side.
        raw.chroma.forEach { v[i++] = it * 3f * W_CHROMA } // x3: bring 1/12-ish values to ~0..1

        // MFCCs: fixed z-anchors (means/stds observed on broad music corpora);
        // fixed rather than per-library so embeddings stay comparable when
        // the library grows.
        val mfccScale = floatArrayOf(40f, 20f, 15f, 12f, 10f, 10f, 8f, 8f, 7f, 7f, 6f, 6f, 6f)
        raw.mfccMean.forEachIndexed { idx, m ->
            v[i++] = (m / mfccScale[idx]).coerceIn(-1.5f, 1.5f) * W_MFCC
        }
        raw.mfccStd.take(6).forEachIndexed { idx, s ->
            v[i++] = (s / mfccScale[idx]).coerceIn(0f, 1.5f) * W_MFCC_STD
        }

        v[i++] = (raw.bpm / 200f).coerceIn(0f, 1f) * W_RHYTHM
        v[i++] = raw.beatConfidence.coerceIn(0f, 1f) * W_RHYTHM
        v[i++] = (raw.dynamicRange / 20f).coerceIn(0f, 1f) * W_RHYTHM
        v[i++] = ((raw.lufs + 35f) / 30f).coerceIn(0f, 1f) * W_RHYTHM
        v[i++] = (raw.flatness * 4f).coerceIn(0f, 1f) * W_RHYTHM
        v[i++] = raw.zcr.coerceIn(0f, 1f) * W_RHYTHM

        floatArrayOf(
            p.energy, p.danceability, p.acousticness, p.instrumentalness,
            p.brightness, p.warmth, p.aggressiveness, p.calmness,
            p.happiness, p.sadness, p.tension,
        ).forEach { v[i++] = it * W_PERCEPTUAL }

        check(i == EMBEDDING_DIM) { "Embedding dim mismatch: $i" }
        return l2Normalize(v)
    }

    fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += (x * x).toDouble()
        val norm = sqrt(sum).toFloat()
        if (norm < 1e-9f) return v
        for (idx in v.indices) v[idx] /= norm
        return v
    }
}
