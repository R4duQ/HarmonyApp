package com.harmony.domain.library.usecase

import com.harmony.domain.library.gateway.MediaScanGateway
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Stream of "something changed on disk" signals. The analysis worker module
 * (Phase 5) collects this and enqueues a scan + analysis pass, giving the
 * spec's "no manual refresh required" behaviour.
 */
class WatchLibraryChangesUseCase @Inject constructor(
    private val scanner: MediaScanGateway,
) {
    operator fun invoke(): Flow<Unit> = scanner.changes()
}
