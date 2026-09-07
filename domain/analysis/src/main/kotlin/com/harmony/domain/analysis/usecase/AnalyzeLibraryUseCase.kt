package com.harmony.domain.analysis.usecase

import com.harmony.domain.analysis.repository.AnalysisEvent
import com.harmony.domain.analysis.repository.AnalysisRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** Thin façade the worker calls; exists so the worker never sees repository internals. */
class AnalyzeLibraryUseCase @Inject constructor(
    private val repository: AnalysisRepository,
) {
    operator fun invoke(): Flow<AnalysisEvent> = repository.analyzeAllPending()
}
