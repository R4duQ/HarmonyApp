package com.harmony.feature.downloads

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.harmony.domain.library.repository.AlbumJourney
import com.harmony.domain.library.repository.AlbumJourneyRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The album download queue: one place that starts, pauses, resumes,
 * cancels and retries album batches, and shows them ([batches]).
 *
 * App-scoped, so the Downloads screen (either tab), the album sheet and
 * Discover all see the same batches and act on the same queue: switching
 * screens or tabs never resets a batch or starts it twice. All state is the
 * saved albums plus WorkManager's job; this holds none of its own.
 */
@Singleton
class AlbumDownloadQueue @Inject constructor(
    @ApplicationContext context: Context,
    private val albums: AlbumJourneyRepository,
    private val soulseek: SoulseekClient,
    statusCenter: DownloadStatusCenter,
) {
    private val work = WorkManager.getInstance(context)
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val workState = work.getWorkInfosForUniqueWorkFlow(AlbumDownloadWorker.WORK)
        .map(::reduce)
        .distinctUntilChanged()

    /** Every album with a tracklist, as a batch; the newest first. */
    val batches: StateFlow<List<AlbumBatch>> = combine(albums.journeys, workState, statusCenter.active) { list, state, live ->
        val processing = AlbumBatches.processingId(list)
        list.asReversed().map { AlbumBatches.of(it, state, it.id == processing, live) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Queues [albumId]'s selected missing tracks ([trackIds] adds tracks to
     * the selection first: a retried or un-skipped song). Albums download
     * one after another, in the order they were queued.
     */
    suspend fun queue(
        albumId: String,
        source: DownloadSource,
        format: SpotiFlacOutputFormat,
        wifiOnly: Boolean = false,
        trackIds: Set<String> = emptySet(),
    ) = mutex.withLock {
        val outputFormat = AlbumDownloadPolicy.formatFor(source, format)
        if (source == DownloadSource.SOULSEEK) check(soulseek.connectionState.value.status == SoulseekConnectionStatus.CONNECTED) {
            "Connect Soulseek in Downloads first, then download the album."
        }
        val current = album(albumId)
        check(current.queuedAt == 0L) { "This album is already in the download queue." }
        val selection = current.tracks.filter { it.uri == null && (it.selectedForDownload || it.id in trackIds) }.map { it.id }.toSet()
        check(selection.isNotEmpty()) { "Select at least one missing track to download." }
        albums.save(current.copy(
            source = source.name, format = outputFormat.name, reviewed = false, remindAfter = 0L,
            queuedAt = System.currentTimeMillis(),
            tracks = current.tracks.map { t ->
                when {
                    t.id in selection -> t.copy(selectedForDownload = true, status = AlbumTrackStatus.MISSING, error = null,
                        coverage = if (current.reviewed) emptyList() else t.coverage,
                        playbackDurationMs = if (current.reviewed) 0L else t.playbackDurationMs)
                    current.reviewed -> t.copy(coverage = emptyList(), playbackDurationMs = 0L)
                    else -> t
                }
            },
        ))
        ensureWorker(wifiOnly)
        selection.size
    }

    /** Queues it again as it was: resume after a pause, or retry the failed tracks. */
    suspend fun resume(albumId: String) {
        val album = album(albumId)
        // Still queued, its job stopped (the notification's Pause, or Android): just run the queue again.
        if (album.queuedAt > 0L) {
            mutex.withLock { ensureWorker(wifiOnly = false) }
            return
        }
        queue(albumId, AlbumDownloadPolicy.source(album.source), SpotiFlacOutputFormat.fromName(album.format))
    }

    /** One track, back into the album (after a failure, or a skip). */
    suspend fun retryTrack(albumId: String, trackId: String) {
        val album = album(albumId)
        check(album.queuedAt == 0L) { "Wait for this album to finish or pause it, then retry the track." }
        queue(albumId, AlbumDownloadPolicy.source(album.source), SpotiFlacOutputFormat.fromName(album.format), trackIds = setOf(trackId))
    }

    /**
     * Stops [albumId]: the track in flight stops, saved tracks stay, the
     * rest wait for Resume. Albums queued after it carry on.
     */
    suspend fun pause(albumId: String) = mutex.withLock {
        val album = album(albumId)
        if (album.queuedAt == 0L) return@withLock
        val processing = AlbumBatches.processingId(albums.journeys.value) == albumId
        albums.save(album.copy(queuedAt = 0L, tracks = album.tracks.map {
            if (it.selectedForDownload && it.uri == null && (it.status == AlbumTrackStatus.MISSING || it.status == AlbumTrackStatus.DOWNLOADING))
                it.copy(status = AlbumTrackStatus.PAUSED, error = null) else it
        }))
        if (processing) {
            withContext(Dispatchers.IO) { work.cancelUniqueWork(AlbumDownloadWorker.WORK).result.get() }
            if (albums.journeys.value.any { it.queuedAt > 0L }) ensureWorker(wifiOnly = false)
        }
    }

    /** Pause, and take the album's missing tracks out of it. Saved tracks stay on the phone. */
    suspend fun cancel(albumId: String) {
        pause(albumId)
        mutex.withLock {
            val album = album(albumId)
            albums.save(album.copy(tracks = album.tracks.map {
                if (it.uri == null && it.selectedForDownload) it.copy(selectedForDownload = false, status = AlbumTrackStatus.CANCELLED, error = null)
                else it
            }))
        }
    }

    /** Leaves out a track that hasn't started, even while its album downloads. */
    suspend fun skipTrack(albumId: String, trackId: String) = mutex.withLock {
        val track = album(albumId).tracks.firstOrNull { it.id == trackId } ?: return@withLock
        check(track.uri == null && track.status != AlbumTrackStatus.DOWNLOADING) { "This track is already downloading. Pause the album instead." }
        albums.updateTrack(albumId, trackId) { it.copy(selectedForDownload = false, status = AlbumTrackStatus.SKIPPED, error = null) }
    }

    /** The checkboxes, while the album is not queued (its selection is then fixed). */
    suspend fun select(albumId: String, trackId: String?, selected: Boolean) = mutex.withLock {
        val album = album(albumId)
        check(album.queuedAt == 0L) { "Pause the album before changing its selection." }
        val ids = AlbumDownloadPolicy.selectedIds(album, trackId, selected)
        albums.selectDownloads(albumId, ids)
        // A track picked again is no longer "Skipped" or "Cancelled".
        album.tracks.filter { it.id in ids && it.uri == null &&
            (it.status == AlbumTrackStatus.SKIPPED || it.status == AlbumTrackStatus.CANCELLED) }.forEach { t ->
            albums.updateTrack(albumId, t.id) { it.copy(status = AlbumTrackStatus.MISSING) }
        }
    }

    /**
     * Makes sure a job works through the queue: a new one when none runs;
     * one follow-up behind a running job (it may be about to finish, having
     * looked at the queue just before this album joined it).
     */
    private suspend fun ensureWorker(wifiOnly: Boolean) {
        withContext(Dispatchers.IO) {
            val infos = work.getWorkInfosForUniqueWork(AlbumDownloadWorker.WORK).get()
            val policy = when {
                infos.none { !it.state.isFinished } -> ExistingWorkPolicy.REPLACE
                infos.none { it.state == WorkInfo.State.BLOCKED } -> ExistingWorkPolicy.APPEND_OR_REPLACE
                else -> null
            }
            if (policy != null) work.enqueueUniqueWork(AlbumDownloadWorker.WORK, policy, AlbumDownloadWorker.request(wifiOnly)).result.get()
        }
    }

    private fun album(id: String): AlbumJourney =
        albums.journeys.value.firstOrNull { it.id == id } ?: throw IllegalStateException("This album is no longer saved. Open it again.")

    private fun reduce(infos: List<WorkInfo>): AlbumWork = when {
        infos.any { it.state == WorkInfo.State.RUNNING } -> AlbumWork.RUNNING
        infos.any { !it.state.isFinished } -> AlbumWork.WAITING
        else -> AlbumWork.NONE
    }
}
