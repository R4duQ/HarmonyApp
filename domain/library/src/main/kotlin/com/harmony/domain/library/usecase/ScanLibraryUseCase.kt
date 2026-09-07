package com.harmony.domain.library.usecase

import com.harmony.domain.library.gateway.LibraryWriteGateway
import com.harmony.domain.library.gateway.MediaScanGateway
import com.harmony.domain.library.model.ScanEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Full incremental scan: read known keys, run the platform scanner, persist
 * results in batches, forward events to the caller (worker / settings UI).
 *
 * Persisting in batches of [BATCH_SIZE] rather than per-track matters at 20k
 * songs: it turns tens of thousands of tiny transactions into a few hundred.
 */
@Singleton
class ScanLibraryUseCase @Inject constructor(
    private val scanner: MediaScanGateway,
    private val writer: LibraryWriteGateway,
) {
    private val scanMutex = Mutex()
    /**
     * @param force re-read every file even if its size and timestamp are
     *   unchanged. Needed after a change to how metadata is INTERPRETED —
     *   album grouping, say — because the normal incremental scan compares
     *   file stats, and a re-interpretation doesn't touch the file. Rows are
     *   updated in place, so playlists and favourites survive.
     */
    operator fun invoke(force: Boolean = false): Flow<ScanEvent> = flow {
        scanMutex.withLock {
            // Always pass the real keys. Forcing is about re-EXTRACTING files
            // whose stats haven't changed, and it's the scanner's job to decide
            // that — handing it an empty set instead would also blind it to
            // deletions, since it detects those by diffing known keys against
            // what's actually present. A forced rescan was therefore the one
            // scan that could never remove a deleted song.
            val known = writer.currentScanKeys()
            val pending = ArrayList<com.harmony.domain.library.model.ScannedTrack>(BATCH_SIZE)

            scanner.scan(known, force = force).collect { event ->
                when (event) {
                    is ScanEvent.TrackScanned -> {
                        pending += event.track
                        if (pending.size >= BATCH_SIZE) {
                            writer.upsert(pending.toList())
                            pending.clear()
                        }
                    }
                    is ScanEvent.TracksRemoved -> writer.removeByUris(event.uris)
                    is ScanEvent.Completed -> {
                        // A collector may stop at Completed. Commit the final partial batch first.
                        if (pending.isNotEmpty()) {
                            writer.upsert(pending.toList())
                            pending.clear()
                        }
                    }
                    else -> Unit
                }
                emit(event)
            }
            if (pending.isNotEmpty()) writer.upsert(pending.toList())
        }
    }

    private companion object {
        const val BATCH_SIZE = 100
    }
}
