package com.harmony.sync.analysis

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.harmony.domain.analysis.repository.AnalysisEvent
import com.harmony.domain.analysis.usecase.AnalyzeLibraryUseCase
import com.harmony.domain.library.model.ScanEvent
import com.harmony.domain.library.usecase.ScanLibraryUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach

/**
 * The single background job: scan first (cheap diff), then analyze whatever
 * the scan left pending (expensive, CPU-bound). Combining them means every
 * trigger path — periodic, content-change, manual — behaves identically.
 *
 * Battery behaviour comes from three layers:
 *  - WorkManager constraints (set at enqueue in [AnalysisScheduler]):
 *    requiresBatteryNotLow always; requiresCharging when the user opts in.
 *    "Pause when battery is low + resume automatically" is exactly what
 *    constraint-based stop/reschedule gives us for free.
 *  - CoroutineWorker cancellation: constraint loss cancels the coroutine;
 *    the pipeline is per-song transactional, so a cancelled run resumes at
 *    the next unanalyzed song, losing at most one song of work.
 *  - setProgress: the settings screen (Phase 8) shows live progress.
 */
@HiltWorker
class LibraryMaintenanceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val scanLibrary: ScanLibraryUseCase,
    private val analyzeLibrary: AnalyzeLibraryUseCase,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // CPU-heavy batch job: demote so decode/FFT never competes with UI
        // rendering or the audio thread. THREAD_PRIORITY_BACKGROUND also
        // places us in the background cpuset on big.LITTLE devices, which is
        // most of the battery win (little cores at ~1/4 the power).
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        return try {
            scanLibrary().collect { event ->
                if (event is ScanEvent.TrackScanned) {
                    setProgress(
                        workDataOf(
                            KEY_PHASE to PHASE_SCAN,
                            KEY_DONE to event.index + 1,
                            KEY_TOTAL to event.total,
                        )
                    )
                }
            }
            analyzeLibrary().onEach { event ->
                if (event is AnalysisEvent.SongAnalyzed) {
                    setProgress(
                        workDataOf(
                            KEY_PHASE to PHASE_ANALYZE,
                            KEY_DONE to event.index + 1,
                            KEY_TOTAL to event.total,
                        )
                    )
                }
            }.collect()
            Result.success()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("HarmonyWorker", "Scan/analyze failed", e)
            // Transient by default; WorkManager backs off exponentially.
            Result.retry()
        }
    }

    private fun workDataOf(vararg pairs: Pair<String, Any>) =
        androidx.work.Data.Builder().apply {
            pairs.forEach { (k, v) ->
                when (v) {
                    is Int -> putInt(k, v)
                    is String -> putString(k, v)
                }
            }
        }.build()

    companion object {
        const val KEY_PHASE = "phase"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val PHASE_SCAN = "scan"
        const val PHASE_ANALYZE = "analyze"
    }
}
