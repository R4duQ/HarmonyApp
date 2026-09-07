package com.harmony.domain.analysis.model

/**
 * Mirror of the native feature array layout in harmony_dsp.h. The two MUST
 * stay in lockstep; any change to either side requires bumping
 * [ANALYSIS_VERSION], which invalidates stored results and triggers
 * re-analysis of the whole library (by design — silently mixing embeddings
 * from different pipelines would poison similarity search).
 */
object FeatureLayout {
    const val ANALYSIS_VERSION = 1
    const val FEATURE_COUNT = 55

    const val RMS = 0
    const val PEAK = 1
    const val LUFS = 2
    const val DYNAMIC_RANGE = 3
    const val BASS = 4
    const val MID = 5
    const val TREBLE = 6
    const val CENTROID = 7
    const val ROLLOFF = 8
    const val BANDWIDTH = 9
    const val FLATNESS = 10
    const val CONTRAST = 11
    const val BPM = 12
    const val BEAT_CONFIDENCE = 13
    const val ZCR = 14
    const val KEY_INDEX = 15
    const val KEY_IS_MAJOR = 16
    const val CHROMA_START = 17   // 12 values
    const val MFCC_MEAN_START = 29 // 13 values
    const val MFCC_STD_START = 42  // 13 values
}

/** Typed view over the raw native output. */
data class RawFeatures(val values: FloatArray) {
    init {
        require(values.size == FeatureLayout.FEATURE_COUNT) {
            "Expected ${FeatureLayout.FEATURE_COUNT} features, got ${values.size}"
        }
    }

    val rms get() = values[FeatureLayout.RMS]
    val peak get() = values[FeatureLayout.PEAK]
    val lufs get() = values[FeatureLayout.LUFS]
    val dynamicRange get() = values[FeatureLayout.DYNAMIC_RANGE]
    val bass get() = values[FeatureLayout.BASS]
    val mid get() = values[FeatureLayout.MID]
    val treble get() = values[FeatureLayout.TREBLE]
    val centroid get() = values[FeatureLayout.CENTROID]
    val rolloff get() = values[FeatureLayout.ROLLOFF]
    val bandwidth get() = values[FeatureLayout.BANDWIDTH]
    val flatness get() = values[FeatureLayout.FLATNESS]
    val contrast get() = values[FeatureLayout.CONTRAST]
    val bpm get() = values[FeatureLayout.BPM]
    val beatConfidence get() = values[FeatureLayout.BEAT_CONFIDENCE]
    val zcr get() = values[FeatureLayout.ZCR]
    val keyIndex get() = values[FeatureLayout.KEY_INDEX].toInt()
    val isMajor get() = values[FeatureLayout.KEY_IS_MAJOR] >= 0.5f
    val chroma get() = values.copyOfRange(FeatureLayout.CHROMA_START, FeatureLayout.CHROMA_START + 12)
    val mfccMean get() = values.copyOfRange(FeatureLayout.MFCC_MEAN_START, FeatureLayout.MFCC_MEAN_START + 13)
    val mfccStd get() = values.copyOfRange(FeatureLayout.MFCC_STD_START, FeatureLayout.MFCC_STD_START + 13)

    override fun equals(other: Any?) = other is RawFeatures && values.contentEquals(other.values)
    override fun hashCode() = values.contentHashCode()
}

/** Perceptual estimates, all 0..1. Derived, not measured — see PerceptualMapper. */
data class PerceptualProfile(
    val energy: Float,
    val danceability: Float,
    val acousticness: Float,
    val instrumentalness: Float,
    val brightness: Float,
    val warmth: Float,
    val aggressiveness: Float,
    val calmness: Float,
    val happiness: Float,
    val sadness: Float,
    val tension: Float,
)

/** Everything the analysis of one song produces. */
data class AnalysisResult(
    val songId: Long,
    val fileHash: String,
    val raw: RawFeatures,
    val perceptual: PerceptualProfile,
    /** L2-normalized, EMBEDDING_DIM long. */
    val embedding: FloatArray,
) {
    override fun equals(other: Any?) = other is AnalysisResult && other.songId == songId
    override fun hashCode() = songId.hashCode()
}
