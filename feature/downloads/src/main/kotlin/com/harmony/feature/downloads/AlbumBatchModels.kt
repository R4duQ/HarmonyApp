package com.harmony.feature.downloads

import com.harmony.domain.library.repository.AlbumJourney
import com.harmony.domain.library.repository.AlbumJourneyTrack

/**
 * Album downloads as one group (an "album batch"): the album, its selected
 * tracks, and how far the batch has got, for the Downloads queue card and
 * the album sheet.
 *
 * Nothing here downloads. The state lives where it always has: each track's
 * status and file in the saved [AlbumJourney], the batch's place in the
 * queue in [AlbumJourney.queuedAt], the running job in WorkManager and the
 * live transfer in [DownloadStatusCenter]. [AlbumBatches.of] only reads them
 * together, so the Tracks/Albums switch, leaving the screen or a restart of
 * the app never resets, duplicates or loses a batch.
 */

/** What each track's saved status says. Old values keep their meaning. */
object AlbumTrackStatus {
    const val MISSING = "Missing"
    const val DOWNLOADING = "Downloading"
    const val DOWNLOADED = "Downloaded"
    const val INDEXING = "Indexing 24D"
    const val INDEXED = "24D indexed"
    /** Downloaded; the 24-D measurement waits for the charger (Settings: analyze only while charging). */
    const val INDEX_ON_CHARGE = "24D on charge"
    const val IN_LIBRARY = "Already in library"
    const val NEEDS_RETRY = "Needs retry"
    const val VERIFY = "Verify provider"
    const val PAUSED = "Paused"
    /** Taken out of a running batch by hand: the worker passes over it. */
    const val SKIPPED = "Skipped"
    const val CANCELLED = "Cancelled"

    fun failed(status: String) = status == NEEDS_RETRY || status == VERIFY
}

/** One track's place in its batch. */
enum class AlbumTrackPhase {
    /** Selected, not started yet. */
    WAITING,
    DOWNLOADING,
    /** Downloaded; its 24-D vector is being measured. */
    INDEXING,
    /** Downloaded and measured for Smart Shuffle. */
    INDEXED,
    /** Downloaded; measured later (on the charger, or by the library's own pass). */
    SAVED,
    /** The same recording was already on the phone; reused, not downloaded. */
    IN_LIBRARY,
    FAILED,
    /** The provider wants a verification before it serves more tracks. */
    VERIFY,
    /** Skipped or cancelled by hand. */
    SKIPPED,
    /** Left out of the selection. */
    NOT_SELECTED,
    ;

    val done get() = this == INDEXING || this == INDEXED || this == SAVED || this == IN_LIBRARY
}

/** The batch as a whole. */
enum class AlbumBatchState {
    /** Nothing asked yet: missing tracks, none queued. */
    READY,
    /** Waiting behind another album (one runs at a time). */
    QUEUED,
    DOWNLOADING,
    /** Every selected track is down; the new ones are being measured (24-D). */
    INDEXING,
    /** Stopped by hand, or the job ended before the batch did; Resume continues. */
    PAUSED,
    COMPLETED,
    /** Finished with some tracks down and some failed. */
    PARTIAL,
    /** Finished with nothing down. */
    FAILED,
}

/** WorkManager's view of the album job, reduced to what the batch needs. */
enum class AlbumWork {
    /** No album job is running or waiting. */
    NONE,
    /** The job waits (network, retry back-off, or behind its own start). */
    WAITING,
    RUNNING,
}

data class AlbumTrackRow(
    val track: AlbumJourneyTrack,
    /** "01", or "2.01" on a multi-disc album. */
    val number: String,
    val phase: AlbumTrackPhase,
    /** 0..1 while it downloads and its size is known; null otherwise. */
    val fraction: Float?,
    /** What to say under it: the error, the provider's step, or nothing. */
    val detail: String?,
) {
    val canRetry get() = phase == AlbumTrackPhase.FAILED || phase == AlbumTrackPhase.VERIFY ||
        (phase == AlbumTrackPhase.SKIPPED && track.uri == null)
    val canSkip get() = phase == AlbumTrackPhase.WAITING
}

data class AlbumBatch(
    val albumId: String,
    val title: String,
    val artist: String,
    val coverUrl: String,
    /** "2013", or "" when the release date is unknown. */
    val year: String,
    val source: String,
    val format: String,
    val state: AlbumBatchState,
    /** Selected tracks on the phone (downloaded now, earlier, or reused from the library). */
    val completed: Int,
    /** Selected tracks. */
    val total: Int,
    val failed: Int,
    /** 0..1 over the whole batch, the current track's progress included. */
    val fraction: Float,
    /** The current transfer's speed; 0 when nothing is moving. */
    val bytesPerSecond: Long,
    val currentTrackId: String?,
    val tracks: List<AlbumTrackRow>,
) {
    val percent: Int get() = (fraction * 100).toInt().coerceIn(0, 100)

    /** "Queued", "Downloading (3/14)", "Indexing 24D", "Completed", "Partial · 2 failed", "Failed", "Paused". */
    val statusLabel: String
        get() = when (state) {
            AlbumBatchState.READY -> if (total == 0) "Select tracks" else "Ready · $total tracks"
            AlbumBatchState.QUEUED -> "Queued"
            AlbumBatchState.DOWNLOADING -> "Downloading (${(completed + 1).coerceAtMost(total)}/$total)"
            AlbumBatchState.INDEXING -> "Indexing 24D"
            AlbumBatchState.PAUSED -> "Paused · $completed/$total"
            AlbumBatchState.COMPLETED -> "Completed"
            AlbumBatchState.PARTIAL -> "Partial · $failed failed"
            AlbumBatchState.FAILED -> "Failed"
        }

    /** The batch is moving (or waiting its turn): Pause and Cancel apply. */
    val active get() = state == AlbumBatchState.QUEUED || state == AlbumBatchState.DOWNLOADING ||
        state == AlbumBatchState.INDEXING
    val canPause get() = active
    val canResume get() = state == AlbumBatchState.PAUSED
    val canRetryFailed get() = !active && failed > 0
    val canCancel get() = active || state == AlbumBatchState.PAUSED
    /** Missing selected tracks remain and nothing is queued for them. */
    val canDownload get() = !active && tracks.any { it.phase == AlbumTrackPhase.WAITING }
}

object AlbumBatches {
    /**
     * The batch of [album].
     *
     * [work] is the album job's state; [processing] is true when that job is
     * on this album now (the oldest queued one); [live] is the transfer in
     * flight, if it belongs to this album.
     */
    fun of(album: AlbumJourney, work: AlbumWork, processing: Boolean, live: ActiveDownload?): AlbumBatch {
        val mine = live?.takeIf { it.albumId == album.id && processing && work == AlbumWork.RUNNING }
        // Every transfer in flight for this album, by track (several when the engine downloads in parallel).
        val transfers = (listOfNotNull(mine) + mine?.parallel.orEmpty())
            .mapNotNull { transfer -> transfer.trackId?.let { it to transfer } }.toMap()
        val multiDisc = album.tracks.any { it.disc > 1 }
        val rows = album.tracks.map { track ->
            val phase = phaseOf(track, processing && work == AlbumWork.RUNNING)
            val transfer = transfers[track.id]?.takeIf { phase == AlbumTrackPhase.DOWNLOADING }
            AlbumTrackRow(
                track = track,
                number = if (multiDisc) "${track.disc}.${track.number.toString().padStart(2, '0')}"
                    else track.number.toString().padStart(2, '0'),
                phase = phase,
                fraction = transfer?.fraction?.coerceIn(0f, 1f),
                detail = when {
                    track.error != null && (phase == AlbumTrackPhase.FAILED || phase == AlbumTrackPhase.VERIFY) -> track.error
                    transfer != null -> transfer.detail
                    else -> null
                },
            )
        }
        val inBatch = rows.filter { it.track.selectedForDownload }
        val total = inBatch.size
        val completed = inBatch.count { it.track.uri != null }
        val failed = inBatch.count { it.phase == AlbumTrackPhase.FAILED || it.phase == AlbumTrackPhase.VERIFY }
        val current = rows.firstOrNull { it.phase == AlbumTrackPhase.DOWNLOADING }
        val indexing = rows.any { it.phase == AlbumTrackPhase.INDEXING }
        val queued = album.queuedAt > 0L
        val state = when {
            queued && processing && work == AlbumWork.RUNNING ->
                if (indexing && current == null) AlbumBatchState.INDEXING else AlbumBatchState.DOWNLOADING
            queued && work != AlbumWork.NONE -> AlbumBatchState.QUEUED
            // Queued, but no job: it was stopped (the notification's Pause, or Android).
            queued -> AlbumBatchState.PAUSED
            total == 0 -> AlbumBatchState.READY
            completed == total -> AlbumBatchState.COMPLETED
            failed > 0 -> if (completed > 0) AlbumBatchState.PARTIAL else AlbumBatchState.FAILED
            inBatch.any { it.track.status == AlbumTrackStatus.PAUSED } -> AlbumBatchState.PAUSED
            else -> AlbumBatchState.READY
        }
        // Each track in flight counts for the share of it that has arrived.
        val currentShare = rows.filter { it.phase == AlbumTrackPhase.DOWNLOADING }.sumOf { (it.fraction ?: 0f).toDouble() }.toFloat()
        val fraction = if (total == 0) 0f else ((completed + currentShare) / total).coerceIn(0f, 1f)
        return AlbumBatch(
            albumId = album.id,
            title = album.title,
            artist = album.artist,
            coverUrl = album.coverUrl,
            year = album.releaseDate.take(4).takeIf { it.length == 4 && it.all(Char::isDigit) }.orEmpty(),
            source = album.source,
            format = album.format,
            state = state,
            completed = completed,
            total = total,
            failed = failed,
            fraction = fraction,
            bytesPerSecond = if (state == AlbumBatchState.DOWNLOADING) transfers.values.sumOf { it.bytesPerSecond } else 0L,
            currentTrackId = current?.track?.id,
            tracks = rows,
        )
    }

    /**
     * The album the job works on: the oldest queued one. The worker takes
     * them in that order, so the queue screen and the job always agree.
     */
    fun processingId(albums: List<AlbumJourney>): String? =
        albums.filter { it.queuedAt > 0L }.minByOrNull { it.queuedAt }?.id

    private fun phaseOf(track: AlbumJourneyTrack, running: Boolean): AlbumTrackPhase {
        if (track.uri != null) return when (track.status) {
            AlbumTrackStatus.INDEXED -> AlbumTrackPhase.INDEXED
            AlbumTrackStatus.INDEXING -> if (running) AlbumTrackPhase.INDEXING else AlbumTrackPhase.SAVED
            AlbumTrackStatus.IN_LIBRARY -> AlbumTrackPhase.IN_LIBRARY
            else -> AlbumTrackPhase.SAVED
        }
        return when {
            track.status == AlbumTrackStatus.SKIPPED || track.status == AlbumTrackStatus.CANCELLED -> AlbumTrackPhase.SKIPPED
            !track.selectedForDownload -> AlbumTrackPhase.NOT_SELECTED
            track.status == AlbumTrackStatus.VERIFY -> AlbumTrackPhase.VERIFY
            track.status == AlbumTrackStatus.NEEDS_RETRY -> AlbumTrackPhase.FAILED
            // "Downloading" left over from a stopped job is not a download in progress.
            track.status == AlbumTrackStatus.DOWNLOADING && running -> AlbumTrackPhase.DOWNLOADING
            else -> AlbumTrackPhase.WAITING
        }
    }
}

/** "3.4 MB/s", "820 KB/s", "" when nothing is moving. */
internal fun transferSpeedLabel(bytesPerSecond: Long): String = when {
    bytesPerSecond <= 0L -> ""
    bytesPerSecond >= 1_000_000L -> String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSecond / 1_000_000.0)
    else -> "${bytesPerSecond / 1_000L} KB/s"
}

/**
 * Measures a transfer's speed from its byte counts, smoothed so the label
 * doesn't jump with every progress callback.
 */
internal class TransferSpeedMeter(private val clock: () -> Long = System::currentTimeMillis) {
    private var lastBytes = -1L
    private var lastAt = 0L
    private var smoothed = 0.0

    /** Bytes per second after this sample of [downloadedBytes]. */
    fun sample(downloadedBytes: Long): Long {
        val now = clock()
        if (lastBytes < 0L || downloadedBytes < lastBytes) {
            lastBytes = downloadedBytes; lastAt = now; smoothed = 0.0
            return 0L
        }
        val elapsed = now - lastAt
        if (elapsed < MIN_INTERVAL_MS) return smoothed.toLong()
        val instant = (downloadedBytes - lastBytes) * 1000.0 / elapsed
        smoothed = if (smoothed == 0.0) instant else smoothed * 0.6 + instant * 0.4
        lastBytes = downloadedBytes; lastAt = now
        return smoothed.toLong()
    }

    fun reset() { lastBytes = -1L; smoothed = 0.0 }

    private companion object { const val MIN_INTERVAL_MS = 400L }
}
