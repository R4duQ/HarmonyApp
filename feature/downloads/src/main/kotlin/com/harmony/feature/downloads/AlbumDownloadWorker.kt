package com.harmony.feature.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.harmony.core.database.dao.DownloadRecordDao
import com.harmony.core.database.entity.DownloadRecordEntity
import com.harmony.core.datastore.SettingsRepository
import com.harmony.domain.analysis.repository.AnalysisRepository
import com.harmony.domain.library.repository.*
import com.harmony.domain.library.usecase.ScanLibraryUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads the album queue: every album with [AlbumJourney.queuedAt] set,
 * oldest first, each one's selected tracks in disc and track order.
 *
 * One track at a time. Two at once is an experiment, off unless switched on
 * in Settings, and only on Mornye, whose runtime can take the provider from
 * each request; on SpotiFLAC and Soulseek the provider order (or the peer
 * connection) belongs to the runtime and two downloads would change each
 * other's route mid-transfer. While music plays the album always goes back to
 * one at a time ([SpotiFlacParallelPolicy]). The engine is shared with
 * single-track downloads ([DownloadEngineGate]).
 *
 * For each track: reuse the same recording if it is already in the library;
 * otherwise download it through the selected engine with the album's tags
 * (album artist, track and disc numbers, totals, release date and cover,
 * embedded by the engine), check its duration against the edition, and save
 * it as `<Artist>/<Album>/01 - Title.flac` in the download folder. A saved
 * track is checkpointed before anything else, so pausing, a crash or a retry
 * never downloads it again.
 *
 * When an album's tracks are done: one library scan, then each new song is
 * measured for Smart Shuffle (its 24-D acoustic vector, AnalysisRepository),
 * unless Settings says to analyze only while charging and the phone isn't
 * charging; then the library's own pass measures it on the charger.
 *
 * A finished album leaves the queue whatever its outcome: tracks that failed
 * stay marked for Retry. The job returns success unless the network failed
 * (retry), so an album that only partly downloaded never cancels the albums
 * queued after it.
 */
@HiltWorker
class AlbumDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val albums: AlbumJourneyRepository,
    private val library: LibraryRepository,
    private val downloads: MusicDownloadRepository,
    private val spoti: SpotiFlacDownloadEngine,
    private val soulseek: SoulseekClient,
    private val records: DownloadRecordDao,
    private val scan: ScanLibraryUseCase,
    private val analysis: AnalysisRepository,
    private val settings: SettingsRepository,
    private val playback: com.harmony.domain.playback.PlaybackController,
    downloadStatusCenter: DownloadStatusCenter,
) : CoroutineWorker(context, parameters) {
    private val status = downloadStatusCenter.forOwner(album = true)
    private val notificationStartedAt = System.currentTimeMillis()
    private var currentAlbumId: String? = null

    private enum class Outcome { DONE, RETRY_LATER, STOP }

    override suspend fun doWork(): Result {
        adoptLegacyRequest()
        var retry = false
        try {
            setForeground(notification("Album downloads", "Preparing…"))
            while (true) {
                ensureActive()
                val next = AlbumBatches.processingId(albums.journeys.value) ?: break
                val album = albums.journeys.value.first { it.id == next }
                currentAlbumId = album.id
                val outcome = try {
                    processAlbum(album)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (failure: Exception) {
                    status.publishOutcome(album.title, false, failure.message ?: "Album download needs attention.")
                    if (failure is IOException) Outcome.RETRY_LATER else Outcome.STOP
                }
                if (outcome == Outcome.RETRY_LATER && runAttemptCount < MAX_RETRIES) {
                    retry = true
                    break // The album stays queued: the retry takes it up again.
                }
                unqueue(album.id)
                currentAlbumId = null
                // Login, space or verification: the next albums would stop the same way.
                if (outcome == Outcome.STOP) break
            }
            return if (retry) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            // Pause (here or from the notification): the album being downloaded leaves the
            // queue as paused; its saved tracks stay. Albums waiting behind it stay queued.
            withContext(NonCancellable) {
                currentAlbumId?.let { id ->
                    albums.journeys.value.find { it.id == id }?.tracks
                        ?.filter { it.status == AlbumTrackStatus.DOWNLOADING || it.status == AlbumTrackStatus.INDEXING }
                        ?.forEach { t ->
                            albums.updateTrack(id, t.id) {
                                it.copy(status = if (it.uri != null) AlbumTrackStatus.DOWNLOADED else AlbumTrackStatus.PAUSED, error = null)
                            }
                        }
                    unqueue(id)
                }
            }
            throw cancelled
        } catch (failure: Exception) {
            // Android refused the foreground notification, storage went away…: the albums stay
            // queued; with no job they show as paused, and Resume starts again.
            status.publishOutcome(currentAlbumId?.let { id -> albums.journeys.value.find { it.id == id }?.title } ?: "Album downloads",
                false, failure.message ?: "Album downloads need attention. Tap Resume.")
            return if (failure is IOException && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        } finally { status.clear() }
    }

    /** One album: its selected tracks, the library scan, then the 24-D measurements. */
    private suspend fun processAlbum(album: AlbumJourney): Outcome {
        val selected = try {
            val source = AlbumDownloadPolicy.source(album.source)
            check(AlbumDownloadPolicy.formatFor(source, SpotiFlacOutputFormat.fromName(album.format)).name == album.format) {
                "This source does not support the selected album format."
            }
            AlbumDownloadPolicy.tracksFor(album, null)
        } catch (e: IllegalArgumentException) {
            status.publishOutcome(album.title, false, e.message ?: "Select tracks again, then download.")
            return Outcome.DONE
        } catch (e: IllegalStateException) {
            status.publishOutcome(album.title, false, e.message ?: "Choose another format, then download.")
            return Outcome.DONE
        }
        val multiDisc = album.tracks.any { it.disc > 1 }
        val downloadedNow = mutableListOf<String>()
        var outcome = Outcome.DONE
        updateNotification(album.title, "Preparing ${selected.size} tracks…")
        status.publishActive(ActiveDownload(album.title, album.source, "Preparing ${selected.size} selected tracks…", albumId = album.id))
        // The library as it is now: nothing downloaded here is in it before the scan below.
        val librarySongs = library.observeSongs().first()
        // One track at a time, unless the two-at-once experiment is switched on in
        // Settings and the engine can do it (SpotiFlacParallelPolicy).
        var parallel = 1
        if (album.source == "SPOTIFLAC") {
            spoti.setParallelDownloadsEnabled(settings.settings.first().experimentalParallelDownloads)
            parallel = spoti.parallelDownloads
            // Each track's provider ids, looked up while the first ones download.
            spoti.prefetchProviderIdentifiers(selected.filter { it.uri == null }.map { identified(album, it) })
        }
        fun jobsNow(): Int = SpotiFlacParallelPolicy.jobs(
            experimentOn = parallel > 1,
            runtimeJobs = parallel,
            playing = playback.playerState.value.let { it.isPlaying || it.isBuffering },
        )
        val merge = Any()
        var stop = false
        coroutineScope {
            val turns = Semaphore(parallel)
            val inFlight = java.util.concurrent.atomic.AtomicInteger()
            for ((index, original) in selected.withIndex()) {
                // The worker's own check (Pause), not the coroutine scope's.
                this@AlbumDownloadWorker.ensureActive()
                turns.acquire()
                // Music is playing: playback comes first, so the next track waits for the one in flight.
                while (inFlight.get() > 0 && jobsNow() == 1) {
                    delay(PLAYBACK_YIELD_MS)
                    this@AlbumDownloadWorker.ensureActive()
                }
                if (synchronized(merge) { stop }) { turns.release(); break }
                inFlight.incrementAndGet()
                val turn: suspend () -> Unit = {
                    try {
                        val result = trackTurn(album, original, index, selected.size, multiDisc, librarySongs)
                        synchronized(merge) {
                            if (result.downloaded) downloadedNow += original.id
                            // STOP (login, space, verification) outranks a retry.
                            result.outcome?.let { if (it == Outcome.STOP || outcome == Outcome.DONE) outcome = it }
                            if (result.stop) stop = true
                        }
                    } finally {
                        inFlight.decrementAndGet()
                        turns.release()
                    }
                }
                if (parallel == 1) turn() else launch { turn() }
            }
        }
        // One incremental scan for the album, including partial batches, instead of a full scan per song.
        status.publishActive(ActiveDownload(album.title, album.source, "Updating your library…", albumId = album.id))
        scan(force = false).collect { }
        index24D(album, downloadedNow)

        val after = albums.journeys.value.find { it.id == album.id }
        val remaining = after?.tracks?.count { it.selectedForDownload && it.uri == null && it.status != AlbumTrackStatus.SKIPPED } ?: selected.size
        status.publishOutcome(album.title, remaining == 0,
            if (remaining == 0) "${after?.tracks?.count { it.selectedForDownload } ?: selected.size} tracks ready in Library."
            else "$remaining tracks need another attempt.")
        return outcome
    }

    /** What one track's turn came to: saved now, an outcome for the album, and whether the album stops here. */
    private class TrackTurn(val downloaded: Boolean = false, val outcome: Outcome? = null, val stop: Boolean = false)

    /** One selected track: skipped, found in the library, or downloaded and saved. */
    private suspend fun trackTurn(
        album: AlbumJourney,
        original: AlbumJourneyTrack,
        index: Int,
        count: Int,
        multiDisc: Boolean,
        librarySongs: List<com.harmony.core.model.Song>,
    ): TrackTurn {
        var track = trackOf(album.id, original.id) ?: return TrackTurn()
        // Skipped by hand while the album was waiting or running.
        if (track.status == AlbumTrackStatus.SKIPPED || !track.selectedForDownload) return TrackTurn()
        // A published URI is checkpointed BEFORE scanning; an indexing failure must never re-download it.
        if (track.uri?.let(::fileExists) == true) return TrackTurn()
        if (track.uri != null) {
            albums.updateTrack(album.id, track.id) { it.copy(uri = null, downloaded = false, sizeBytes = 0, coverage = emptyList()) }
            track = track.copy(uri = null)
        }
        val match = AlbumTrackMatcher.match(track, album.title, librarySongs)
        if (match != null && fileExists(match.uri)) {
            albums.updateTrack(album.id, track.id) { it.copy(uri = match.uri, status = AlbumTrackStatus.IN_LIBRARY, error = null) }
            return TrackTurn()
        }
        try {
            val detail = "${index + 1}/$count · ${track.title}"
            // Update the same notification; do not restart foreground work for each song.
            updateNotification(album.title, detail)
            live.show(track.id, ActiveDownload(album.title, album.source, detail, albumId = album.id, trackId = track.id))
            albums.updateTrack(album.id, track.id) { it.copy(status = AlbumTrackStatus.DOWNLOADING, error = null) }
            // Lossless staging plus publishing need room for two copies of the current track only.
            val estimate = (track.durationMs / 1_000 * 176_400L * 2).coerceAtLeast(64L * 1024 * 1024)
            check(StatFs(applicationContext.cacheDir.path).availableBytes > estimate) {
                "Not enough temporary storage. Free space and resume the album."
            }
            downloadWhenEngineFree(album, track, multiDisc)
            return TrackTurn(downloaded = true)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) {
            val verification = failure is SpotiFlacException && failure.errorType.equals("verification_required", true)
            albums.updateTrack(album.id, track.id) {
                it.copy(status = if (verification) AlbumTrackStatus.VERIFY else AlbumTrackStatus.NEEDS_RETRY,
                    error = if (verification) "Open provider verification, then resume this album." else failure.message?.take(240) ?: "Download failed")
            }
            if (verification) return TrackTurn(outcome = Outcome.STOP, stop = true)
            val retry = failure is IOException || failure.cause is IOException || failure is DownloadEngineBusyException
            // Space, login and shared-engine contention need user action/waiting, not attempts on every track.
            val halts = failure is IllegalStateException || failure is DownloadEngineBusyException ||
                (album.source == "SOULSEEK" && soulseek.connectionState.value.status != SoulseekConnectionStatus.CONNECTED)
            return TrackTurn(
                outcome = when {
                    failure is IllegalStateException && halts -> Outcome.STOP
                    retry -> Outcome.RETRY_LATER
                    else -> null
                },
                stop = halts,
            )
        } finally { live.done(track.id) }
    }

    /**
     * The album's transfers in flight, published together: the first is the
     * banner's, the others ride along ([ActiveDownload.parallel]) so each
     * track row shows its own progress.
     */
    private inner class LiveTracks {
        private val tracks = LinkedHashMap<String, ActiveDownload>()

        @Synchronized fun show(trackId: String, download: ActiveDownload) {
            tracks[trackId] = download
            publish()
        }

        @Synchronized fun done(trackId: String) {
            if (tracks.remove(trackId) != null && tracks.isNotEmpty()) publish()
        }

        private fun publish() {
            val all = tracks.values.toList()
            status.publishActive(all.first().copy(parallel = all.drop(1)))
        }
    }

    private val live = LiveTracks()

    /** [track] as the engines look for it, with the album's own tags to embed. */
    private fun identified(album: AlbumJourney, track: AlbumJourneyTrack): IdentifiedTrack {
        val tags = AlbumTags(
            albumArtist = album.artist,
            trackNumber = track.number,
            discNumber = track.disc,
            totalTracks = album.tracks.count { it.disc == track.disc },
            totalDiscs = album.tracks.maxOf { it.disc },
            releaseDate = album.releaseDate,
        )
        return IdentifiedTrack(track.artist, track.title, "${track.artist} - ${track.title}",
            album.coverUrl, album.title, track.durationMs, metadataId = track.id, albumTags = tags)
    }

    /**
     * The new songs' 24-D acoustic vectors, measured now so they join Smart
     * Shuffle straight away (the same measurement as the library's own pass).
     */
    private suspend fun index24D(album: AlbumJourney, trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        val waitForCharger = settings.settings.first().chargingOnlyAnalysis && !charging()
        val songs = library.observeSongs().first().associateBy { it.uri }
        for ((index, id) in trackIds.withIndex()) {
            ensureActive()
            val track = trackOf(album.id, id) ?: continue
            val song = track.uri?.let(songs::get) ?: continue // Not indexed by the scan yet: the library's pass measures it.
            if (waitForCharger) {
                albums.updateTrack(album.id, id) { it.copy(status = AlbumTrackStatus.INDEX_ON_CHARGE) }
                continue
            }
            albums.updateTrack(album.id, id) { it.copy(status = AlbumTrackStatus.INDEXING) }
            status.publishActive(ActiveDownload(album.title, album.source, "Indexing 24D · ${index + 1}/${trackIds.size} · ${track.title}",
                albumId = album.id, trackId = id))
            val measured = withContext(Dispatchers.Default) { analysis.analyzeSong(song.id) }
            albums.updateTrack(album.id, id) {
                it.copy(status = if (measured.isSuccess) AlbumTrackStatus.INDEXED else AlbumTrackStatus.DOWNLOADED)
            }
        }
    }

    private fun charging(): Boolean {
        val battery = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return plugged != 0
    }

    /**
     * A single-track download (or a paused album's transfer still winding
     * down) may hold the engine for a moment: wait for it briefly instead
     * of failing the track.
     */
    private suspend fun downloadWhenEngineFree(album: AlbumJourney, track: AlbumJourneyTrack, multiDisc: Boolean) {
        var waited = 0
        while (true) {
            try { return downloadTrack(album, track, multiDisc) }
            catch (busy: DownloadEngineBusyException) {
                if (waited >= ENGINE_WAIT_TRIES) throw busy
                waited++
                delay(ENGINE_WAIT_MS)
            }
        }
    }

    private suspend fun unqueue(albumId: String) {
        val album = albums.journeys.value.find { it.id == albumId } ?: return
        if (album.queuedAt != 0L) albums.save(album.copy(queuedAt = 0L))
    }

    /** A request enqueued by an earlier version names its album; queue it the new way. */
    private suspend fun adoptLegacyRequest() {
        val id = inputData.getString(KEY_ALBUM) ?: return
        val album = albums.journeys.value.find { it.id == id } ?: return
        if (album.queuedAt != 0L) return
        val ids = inputData.getStringArray(KEY_TRACKS)?.toSet()
        if (ids != null && album.tracks.map { it.id }.toSet().containsAll(ids)) albums.selectDownloads(id, ids)
        albums.journeys.value.find { it.id == id }?.let { albums.save(it.copy(queuedAt = System.currentTimeMillis())) }
    }

    private fun trackOf(albumId: String, trackId: String): AlbumJourneyTrack? =
        albums.journeys.value.find { it.id == albumId }?.tracks?.find { it.id == trackId }

    private fun ensureActive() { if (isStopped) throw CancellationException("Album paused") }

    private fun fileExists(uri: String): Boolean = try {
        applicationContext.contentResolver.openAssetFileDescriptor(android.net.Uri.parse(uri), "r")?.use { true } ?: false
    } catch (_: java.io.FileNotFoundException) { false }
    // SecurityException deliberately propagates: inaccessible storage is not evidence that a file is missing.

    private suspend fun downloadTrack(album: AlbumJourney, track: AlbumJourneyTrack, multiDisc: Boolean) {
        val identified = identified(album, track)
        var staged: File? = null
        val speed = TransferSpeedMeter()
        try {
            var provider: String? = null
            var username: String? = null
            var downloaded: SpotiFlacDownloadedFile? = null
            val file: File
            val extension: String
            if (album.source == "SPOTIFLAC") {
                val result = spoti.download(identified, SpotiFlacOutputFormat.fromName(album.format), SpotiFlacRequestOwner.ALBUM_DOWNLOAD) { p ->
                    live.show(track.id, ActiveDownload(track.title, displaySource(), p.detail ?: p.stage.label, p.fraction, album.id,
                        trackId = track.id, bytesPerSecond = if (p.downloadedBytes > 0) speed.sample(p.downloadedBytes) else 0L))
                }
                file = result.tempFile; extension = result.outputFormat.extension; provider = result.provider
                downloaded = result
            } else {
                check(soulseek.connectionState.value.status == SoulseekConnectionStatus.CONNECTED) { "Connect Soulseek in Downloads, then resume this album." }
                val preference = if (album.format == "MP3_320") SoulseekFormatPreference.MP3_ONLY else SoulseekFormatPreference.FLAC_ONLY
                live.show(track.id, ActiveDownload(track.title, "Soulseek", "Finding an available peer…", null, album.id, trackId = track.id))
                val candidates = soulseek.searchFlac(identified.displayName, SoulseekSearchMode.BALANCED, false, preference)
                    .filter { it.score >= 80 && it.durationSeconds?.let { d -> kotlin.math.abs(d * 1_000L - track.durationMs) <= 5_000 } != false }
                    .distinctBy { it.username.lowercase() }
                    .sortedWith(compareByDescending<SoulseekSearchCandidate> { it.freeUploadSlot }
                        .thenBy { it.queueLength.coerceAtLeast(0) }.thenByDescending { it.averageSpeedBytesPerSecond })
                    .take(3)
                require(candidates.isNotEmpty()) { "No sufficiently close peer match. Retry later or choose another source." }
                try { soulseek.racePeerConnections(candidates.take(2), maxPeers = 2) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { }
                var result: SoulseekDownloadedFile? = null
                var chosen: SoulseekSearchCandidate? = null
                var last: Exception? = null
                val progress = CoroutineScope(currentCoroutineContext()).launch {
                    soulseek.transferProgress.collect { p -> if (p != null) {
                        live.show(track.id, ActiveDownload(track.title, "Soulseek", p.status, p.fraction, album.id,
                            trackId = track.id, bytesPerSecond = p.speedBytesPerSecond))
                    } }
                }
                try {
                    for (candidate in candidates) {
                        try {
                            result = soulseek.download(candidate, SoulseekDownloadPatience.FAST, albumTransfer = true)
                            chosen = candidate; break
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { last = e }
                    }
                } finally { progress.cancel() }
                file = result?.tempFile ?: throw last ?: IOException("No reachable peer. Resume to retry.")
                extension = requireNotNull(chosen).extension.lowercase(); username = chosen.username
            }
            staged = file
            // The last phase of the download's timeline: the checks and the copy into the library.
            val importStartedAt = android.os.SystemClock.elapsedRealtime()
            val info = if (extension == "mp3") downloads.validateStagedMp3(file) else downloads.validateStagedFlac(file)
            require(AlbumDownloadPolicy.isSameRecording(track.durationMs, info.durationMs)) {
                "The downloaded duration does not match this edition. The file was not imported."
            }
            val name = AlbumFiles.trackFileName(track, multiDisc, extension)
            val folders = AlbumFiles.albumFolders(album.artist, album.title)
            // Finish the short publish/checkpoint transaction even if Pause is tapped during the copy.
            withContext(NonCancellable + Dispatchers.IO) {
                val saved = if (extension == "mp3") downloads.publishStagedMp3(file, name, folders)
                    else downloads.publishStagedFlac(file, name, folders)
                staged = null
                albums.updateTrack(album.id, track.id) { it.copy(uri = saved.uri.toString(), downloaded = true,
                    sizeBytes = saved.sizeBytes, status = AlbumTrackStatus.DOWNLOADED, error = null) }
                records.upsert(DownloadRecordEntity(uri = saved.uri.toString(), title = track.title, artist = track.artist,
                    source = album.source, provider = provider, format = extension.uppercase(), bitDepth = info.bitDepth,
                    sampleRateHz = info.sampleRateHz, fileSizeBytes = saved.sizeBytes,
                    downloadedAt = System.currentTimeMillis(), soulseekUsername = username, originalTrackId = track.id, isrc = null))
            }
            downloaded?.let { spoti.reportImported(it, android.os.SystemClock.elapsedRealtime() - importStartedAt) }
        } finally { staged?.delete() }
    }

    /** The engine's name as the app shows it ("Mornye"). */
    private fun displaySource(): String = spoti.engineName

    private fun updateNotification(title: String, detail: String) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification(title, detail).notification)
    }

    private fun notification(title: String, detail: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Album downloads", NotificationManager.IMPORTANCE_LOW))
        val launch = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(title).setContentText(detail)
            .setOngoing(true).setOnlyAlertOnce(true).setWhen(notificationStartedAt).setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(launch?.let { PendingIntent.getActivity(applicationContext, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) })
            .addAction(android.R.drawable.ic_media_pause, "Pause", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        /** Only in requests from earlier versions (see adoptLegacyRequest). */
        const val KEY_ALBUM = "albumId"
        const val KEY_TRACKS = "trackIds"
        const val WORK = "harmony-album-download"
        private const val CHANNEL = "harmony_album_downloads"
        private const val NOTIFICATION_ID = 7_846
        private const val MAX_RETRIES = 2
        private const val ENGINE_WAIT_TRIES = 10
        private const val ENGINE_WAIT_MS = 1_500L
        /** While music plays and a track is in flight, how often to look again before starting the next. */
        private const val PLAYBACK_YIELD_MS = 750L

        /**
         * The job that works through the album queue. Albums are queued by
         * setting [AlbumJourney.queuedAt]; this only makes sure a job runs.
         */
        fun request(wifiOnly: Boolean): OneTimeWorkRequest = OneTimeWorkRequestBuilder<AlbumDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag(WORK).build()
    }
}
