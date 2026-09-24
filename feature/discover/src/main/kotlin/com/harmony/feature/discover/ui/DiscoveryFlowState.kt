package com.harmony.feature.discover.ui

import com.harmony.domain.library.discovery.BatchProgress
import com.harmony.domain.library.discovery.DiscoveryDraft
import com.harmony.domain.library.discovery.DraftStep
import com.harmony.domain.library.repository.DiscoveryBatch
import com.harmony.domain.library.repository.DiscoveryFilter
import com.harmony.feature.discover.provider.SourceStatus

enum class Connection { CHECKING, ONLINE, RECONNECTING, OFFLINE }

/** Long-running work shown on screen. Never unbounded: every path ends in Idle. */
sealed interface DiscoveryWork {
    data object Idle : DiscoveryWork
    data class Generating(val message: String) : DiscoveryWork
}

data class TasteSummary(
    val loaded: Boolean = false,
    val hasHistory: Boolean = false,
    /** Artist name → the real reason it is in the profile. */
    val artists: List<Pair<String, String>> = emptyList(),
    val genres: List<String> = emptyList(),
)

data class BatchSummary(
    val id: String,
    val name: String,
    val songCount: Int,
    val availableCount: Int,
    val playlistId: Long?,
)

data class DiscoveryUiState(
    val loaded: Boolean = false,
    val step: DraftStep = DraftStep.PREFERENCES,
    val draft: DiscoveryDraft? = null,
    /** The saved selection on the review step. */
    val review: DiscoveryBatch? = null,
    val progress: BatchProgress? = null,
    val connection: Connection = Connection.CHECKING,
    val reconnectSeconds: Int = 0,
    val sources: List<SourceStatus> = emptyList(),
    val work: DiscoveryWork = DiscoveryWork.Idle,
    /** Keys whose replacement is being looked up. */
    val replacing: Set<String> = emptySet(),
    /** What went wrong with sources in the last generation; results may be partial. */
    val notes: List<String> = emptyList(),
    val message: String? = null,
    val taste: TasteSummary = TasteSummary(),
    val genres: List<String> = emptyList(),
    val artistQuery: String = "",
    val artistResults: List<String> = emptyList(),
    val batches: List<BatchSummary> = emptyList(),
    val creating: Boolean = false,
    val libraryEmpty: Boolean = false,
    val filter: DiscoveryFilter = DiscoveryFilter(),
) {
    val online: Boolean get() = connection == Connection.ONLINE
    val generating: Boolean get() = work is DiscoveryWork.Generating
    /** The songs on screen were made earlier and cannot be refreshed right now. */
    val showingSaved: Boolean get() = !online && draft?.generatedOnline == true && draft.items.isNotEmpty()
}

sealed interface DiscoveryEvent {
    data class OpenPlaylist(val id: Long) : DiscoveryEvent
}
