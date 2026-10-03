package com.harmony.domain.library.gateway

import com.harmony.domain.library.model.ScanKey
import com.harmony.domain.library.model.ScannedTrack

/**
 * Persistence side of scanning, implemented by :data:library over Room in
 * Phase 4. Split from the read repository so the scan pipeline depends on
 * the narrowest possible surface.
 */
interface LibraryWriteGateway {
    suspend fun currentScanKeys(): List<ScanKey>
    suspend fun upsert(tracks: List<ScannedTrack>)
    suspend fun removeByUris(uris: List<String>)

    /**
     * Drops albums and artists no song points to any more. A re-read file can
     * move to a different album row (its album name was repaired, say), which
     * leaves the old row empty.
     */
    suspend fun pruneOrphans() {}
}
