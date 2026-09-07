package com.harmony.domain.library.gateway

import com.harmony.domain.library.model.ScanEvent
import com.harmony.domain.library.model.ScanKey
import kotlinx.coroutines.flow.Flow

/**
 * Domain-side contract for the platform scanner, implemented in :core:media
 * (bound via DI in the app module). The domain never sees ContentResolver,
 * MediaStore, or SAF types.
 */
interface MediaScanGateway {

    /**
     * Runs an incremental scan.
     *
     * @param knownKeys everything the library currently contains; entries whose
     *   key matches are skipped without opening the file, which is what keeps
     *   a no-change rescan of 20k songs in the low hundreds of milliseconds.
     */
    /**
     * @param force re-extract every candidate even when its size and
     *   timestamp match a known key. Deletion detection still uses
     *   [knownKeys] either way.
     */
    fun scan(knownKeys: Collection<ScanKey>, force: Boolean = false): Flow<ScanEvent>

    /**
     * Emits whenever the underlying media content changes (files added,
     * removed, modified; storage volumes mounted/unmounted). Debounced.
     * Collectors are expected to respond by triggering [scan].
     */
    fun changes(): Flow<Unit>
}
