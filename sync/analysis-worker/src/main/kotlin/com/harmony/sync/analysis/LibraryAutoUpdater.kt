package com.harmony.sync.analysis

import com.harmony.domain.library.usecase.WatchLibraryChangesUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Glues the change stream to the scheduler: while the app process is alive,
 * any media change enqueues a maintenance run (WorkManager dedupes). Started
 * once from HarmonyApplication.
 */
@Singleton
class LibraryAutoUpdater @Inject constructor(
    private val watchChanges: WatchLibraryChangesUseCase,
    private val scheduler: AnalysisScheduler,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        scheduler.schedulePeriodic()
        scheduler.scheduleNow()
        scope.launch {
            watchChanges().collect { scheduler.scheduleNow() }
        }
    }
}
