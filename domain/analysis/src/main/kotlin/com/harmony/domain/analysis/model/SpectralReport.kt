package com.harmony.domain.analysis.model

/**
 * The result of inspecting one file's spectrum.
 *
 * The point of this is answering a question tags can't: is this FLAC actually
 * lossless, or is it an MP3 that someone re-encoded and renamed? A lossy
 * encoder discards everything above its cutoff, and that gap survives
 * re-encoding to FLAC — the container says lossless, the audio doesn't.
 */
data class SpectralReport(
    val fileName: String,
    val mimeType: String?,
    /** Declared by the container. */
    val sampleRate: Int,
    val channelCount: Int,
    /** Bits per sample, 0 when the container doesn't say. */
    val bitDepth: Int,
    /** Average over the file, in kbps. */
    val bitrateKbps: Int,
    val durationMs: Long,
    val fileSizeBytes: Long,
    /** True when the CONTAINER claims a lossless codec. */
    val claimsLossless: Boolean,

    /**
     * Averaged magnitude spectrum, dB relative to peak, low to high.
     * Bin i covers frequency i * sampleRate / (2 * size).
     */
    val spectrum: FloatArray,
    /** Highest frequency with meaningful energy, Hz. */
    val cutoffHz: Int,
    /**
     * How abruptly energy dies at [cutoffHz], in dB across roughly 1 kHz.
     * A lossy encoder's brick-wall filter is steep; natural rolloff isn't.
     */
    val cutoffSharpnessDb: Float,
    val verdict: Verdict,
    /** Plain-language reasoning shown under the verdict. */
    val explanation: String,
) {
    enum class Verdict {
        /** Spectrum consistent with the container's lossless claim. */
        GENUINE_LOSSLESS,
        /** Lossless container, but a lossy encoder's fingerprint. */
        LIKELY_TRANSCODE,
        /** Lossy file that is what it says it is. */
        LOSSY_AS_LABELLED,
        /** Not enough signal to judge — very short, quiet, or odd content. */
        INCONCLUSIVE,
    }

    /** Frequency in Hz at the centre of spectrum bin [index]. */
    fun binToHz(index: Int): Float =
        index * sampleRate / (2f * spectrum.size)

    // FloatArray needs identity-free equals/hashCode for a data class.
    override fun equals(other: Any?): Boolean =
        this === other || (other is SpectralReport && fileName == other.fileName &&
            spectrum.contentEquals(other.spectrum))

    override fun hashCode(): Int = 31 * fileName.hashCode() + spectrum.contentHashCode()
}
