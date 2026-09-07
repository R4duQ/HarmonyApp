package com.harmony.domain.library.repository

import kotlinx.coroutines.flow.StateFlow

/** The exact edition chosen by the listener, including durable file provenance. */
data class AlbumJourney(
    val id: String,
    val title: String,
    val artist: String,
    val coverUrl: String,
    val editionId: String,
    val tracks: List<AlbumJourneyTrack>,
    val source: String = "SPOTIFLAC",
    val format: String = "FLAC_LOSSLESS",
    val reviewed: Boolean = false,
    val remindAfter: Long = 0L,
) {
    val availableCount get() = tracks.count { it.uri != null }
    val selectedMissing get() = tracks.filter { it.selectedForDownload && it.uri == null }
    val heardCount get() = tracks.count { it.heard }
    val readyForReview get() = tracks.isNotEmpty() && tracks.all { it.heard } && !reviewed
}

data class AlbumJourneyTrack(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val disc: Int,
    val number: Int,
    val uri: String? = null,
    val downloaded: Boolean = false,
    val sizeBytes: Long = 0L,
    val status: String = "Missing",
    val error: String? = null,
    val coverage: List<ListenedRange> = emptyList(),
    val playbackDurationMs: Long = 0L,
    val selectedForDownload: Boolean = true,
) {
    val heard get() = ListeningCoverage.isComplete(coverage, playbackDurationMs.takeIf { it > 0 } ?: durationMs)
}

data class ListenedRange(val startMs: Long, val endMs: Long)

/** Union, not a sum: replaying the chorus cannot count as hearing a full track. */
object ListeningCoverage {
    fun merge(ranges: List<ListenedRange>, addition: ListenedRange, durationMs: Long): List<ListenedRange> {
        if (durationMs <= 0 || addition.endMs <= addition.startMs) return ranges
        val sorted = (ranges + addition).map {
            ListenedRange(it.startMs.coerceIn(0, durationMs), it.endMs.coerceIn(0, durationMs))
        }.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
        val result = mutableListOf<ListenedRange>()
        for (range in sorted) {
            val last = result.lastOrNull()
            if (last != null && range.startMs <= last.endMs) {
                result[result.lastIndex] = ListenedRange(last.startMs, maxOf(last.endMs, range.endMs))
            } else result += range
        }
        return result
    }

    fun isComplete(ranges: List<ListenedRange>, durationMs: Long): Boolean = durationMs > 0 &&
        ranges.sumOf { (it.endMs.coerceAtMost(durationMs) - it.startMs.coerceAtLeast(0)).coerceAtLeast(0) } >= durationMs * 0.90
}

interface AlbumJourneyRepository {
    val journeys: StateFlow<List<AlbumJourney>>
    suspend fun save(journey: AlbumJourney)
    suspend fun updateTrack(albumId: String, trackId: String, change: (AlbumJourneyTrack) -> AlbumJourneyTrack)
    suspend fun selectDownloads(albumId: String, ids: Set<String>) {
        journeys.value.firstOrNull { it.id == albumId }?.tracks?.forEach { track ->
            updateTrack(albumId, track.id) { it.copy(selectedForDownload = it.id in ids) }
        }
    }
    suspend fun recordListening(uri: String, ranges: List<ListenedRange>, durationMs: Long)
    suspend fun review(albumId: String, done: Boolean, remindAfter: Long = 0L)
    suspend fun filesRemoved(uris: Set<String>)
}
