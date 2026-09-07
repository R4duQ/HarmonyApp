package com.harmony.domain.similarity.model

/** One song's presence in the index. */
data class IndexEntry(
    val songId: Long,
    val albumId: Long,
    /** Perceptual energy 0..1 — kept as its own field (also perceptual[0]) since the Energy Slider reads it directly and often. */
    val energy: Float,
    /**
     * Full 11-axis perceptual profile, same order MoodProfiles expects:
     * energy, danceability, acousticness, instrumentalness, brightness,
     * warmth, aggressiveness, calmness, happiness, sadness, tension.
     * Carried here (not just energy) so mood-filtered Smart Shuffle can
     * score real mood fit instead of energy-proximity alone.
     */
    val perceptual: FloatArray,
    /** L2-normalized embedding. */
    val vector: FloatArray,
    /** Analyzed tempo when available; null for beatless/unanalyzed material. */
    val bpm: Float? = null,
) {
    override fun equals(other: Any?) = other is IndexEntry && other.songId == songId
    override fun hashCode() = songId.hashCode()
}

/** A similarity search hit. */
data class Neighbor(
    val songId: Long,
    /** Cosine similarity in [-1, 1]; embeddings are non-negative-ish so typically [0, 1]. */
    val similarity: Float,
    val albumId: Long,
    val energy: Float,
    /** See [IndexEntry.perceptual] for axis order. */
    val perceptual: FloatArray,
    /** Tempo propagated from the local analysis index. */
    val bpm: Float? = null,
)

/** Options bundle so call sites read declaratively. */
data class SimilarityOptions(
    val k: Int = 20,
    val excludeSongIds: Set<Long> = emptySet(),
    /** Max songs from one album in a result set; Int.MAX_VALUE disables. */
    val maxPerAlbum: Int = 2,
    /** Similarity above this = duplicate recording, dropped. */
    val dedupeThreshold: Float = 0.995f,
)