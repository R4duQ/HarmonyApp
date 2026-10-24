package com.harmony.domain.analysis.repository

/**
 * Where the background indexer keeps each song's 24 raw acoustic features
 * for Smart Shuffle (implemented in :data:shuffle, SmartShuffleDatabase).
 *
 * A song needs measuring when it has no entry, its file changed (another
 * [fileHash]) or the extractor changed ([ACOUSTIC_VERSION]).
 */
interface AcousticVectorStore {
    /** songId -> (fileHash, version) of what is stored. */
    suspend fun keys(): Map<Long, Pair<String, Int>>

    /**
     * Stores the measurement. [raw] null means the song was measured and had
     * nothing usable (under two seconds of sound): recorded, so it is not
     * decoded again and again.
     */
    suspend fun put(songId: Long, fileHash: String, raw: FloatArray?)

    companion object {
        /** Bump when AcousticExtractor's features change: every song is measured again. */
        const val ACOUSTIC_VERSION = 1
    }
}
