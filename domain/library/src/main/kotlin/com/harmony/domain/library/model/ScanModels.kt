package com.harmony.domain.library.model

import com.harmony.core.model.Song

/**
 * Identity + change-detection facts about a file, cheap to obtain for every
 * file on every scan. Full metadata extraction only happens for entries whose
 * ScanKey differs from what the repository already knows.
 */
data class ScanKey(
    val uri: String,
    val sizeBytes: Long,
    val lastModified: Long,
)

/** One fully-extracted track, ready to be persisted. */
data class ScannedTrack(
    val song: Song,
    val fileSizeBytes: Long,
    val lastModified: Long,
    /** Partial content hash — see FileHasher for the exact recipe. */
    val fileHash: String,
    /** Volume identifier: "internal", "sdcard:<uuid>", "usb:<treeUri>". */
    val storageVolume: String,
)

/** Progress + result events emitted by a scan, in order. */
sealed interface ScanEvent {
    data class Started(val totalCandidates: Int) : ScanEvent
    data class TrackScanned(val track: ScannedTrack, val index: Int, val total: Int) : ScanEvent
    /** URIs that existed in the library but no longer exist on any volume. */
    data class TracksRemoved(val uris: List<String>) : ScanEvent
    data class Failed(val uri: String, val reason: String) : ScanEvent
    data class Completed(val added: Int, val updated: Int, val removed: Int, val failed: Int) : ScanEvent
}
