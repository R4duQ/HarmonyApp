package com.harmony.domain.analysis.repository

import com.harmony.domain.analysis.model.AnalysisResult
import kotlinx.coroutines.flow.Flow

/** Progress events from a batch analysis run. */
sealed interface AnalysisEvent {
    data class Started(val pendingCount: Int) : AnalysisEvent
    data class SongAnalyzed(val songId: Long, val index: Int, val total: Int) : AnalysisEvent
    data class SongFailed(val songId: Long, val reason: String) : AnalysisEvent
    data class Completed(val analyzed: Int, val failed: Int) : AnalysisEvent
}

/**
 * Implemented by :data:analysis. "Pending" = songs with no stored result, a
 * stale fileHash (file changed), or a stale analysisVersion (pipeline
 * changed) — the once-and-only-once rule from the spec lives in this
 * definition.
 */
interface AnalysisRepository {
    suspend fun pendingSongIds(): List<Long>
    suspend fun analyzeSong(songId: Long): Result<AnalysisResult>
    fun analyzeAllPending(): Flow<AnalysisEvent>
}
