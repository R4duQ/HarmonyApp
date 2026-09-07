package com.harmony.sync.analysis

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * All enqueue policy in one place.
 *
 * Unique-work names mean triggers coalesce: a burst of content-change events
 * while a run is active queues exactly one follow-up run (APPEND), and the
 * periodic safety net never doubles up (KEEP).
 */
@Singleton
class AnalysisScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** True = user chose "analyze only while charging" (Phase 8 settings). */
    @Volatile
    var chargingOnly: Boolean = false

    private fun constraints(): Constraints = Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .setRequiresCharging(chargingOnly)
        .build()

    /** Immediate run: app start, content change, or user-tapped "Scan now". */
    fun scheduleNow() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NOW,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<LibraryMaintenanceWorker>()
                .setConstraints(constraints())
                .build(),
        )
    }

    /** Re-enqueue the periodic job with current constraints (charging toggle changed). */
    fun reschedulePeriodic() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<LibraryMaintenanceWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints())
                .build(),
        )
    }

    /** Daily safety net for anything the observers missed (e.g. changes while dead). */
    fun schedulePeriodic() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<LibraryMaintenanceWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints())
                .build(),
        )
    }

    private companion object {
        const val WORK_NOW = "harmony_maintenance_now"
        const val WORK_PERIODIC = "harmony_maintenance_periodic"
    }
}
