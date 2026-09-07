package com.harmony.feature.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.harmony.core.database.dao.DownloadRecordDao
import com.harmony.core.database.entity.DownloadRecordEntity
import com.harmony.domain.library.repository.*
import com.harmony.domain.library.usecase.ScanLibraryUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

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
    downloadStatusCenter: DownloadStatusCenter,
) : CoroutineWorker(context, parameters) {
    private val status = downloadStatusCenter.forOwner(album = true)
    private val notificationStartedAt = System.currentTimeMillis()
    override suspend fun doWork(): Result {
        val albumId = inputData.getString(KEY_ALBUM) ?: return Result.failure()
        val stored = albums.journeys.value.find { it.id == albumId } ?: return Result.failure()
        val album = stored.copy(source = inputData.getString(KEY_SOURCE) ?: stored.source,
            format = inputData.getString(KEY_FORMAT) ?: stored.format)
        val selectedIds = inputData.getStringArray(KEY_TRACKS)?.toSet()
            ?: album.tracks.filter { it.selectedForDownload }.map { it.id }.toSet()
        val selected = album.tracks.filter { it.id in selectedIds }
        if (selected.isEmpty()) return Result.failure()
        var needsRetry = false
        try {
            setForeground(notification(album.title, "Preparing album…"))
            status.publishActive(ActiveDownload(album.title, album.source, "Preparing ${selected.size} selected tracks…", albumId = album.id))
            for ((index, original) in selected.withIndex()) {
                ensureActive()
                var track = albums.journeys.value.first { it.id == albumId }.tracks.first { it.id == original.id }
                // A published URI is checkpointed BEFORE scanning; an indexing failure must never re-download it.
                if (track.uri?.let(::fileExists) == true) continue
                if (track.uri != null) {
                    albums.updateTrack(albumId, track.id) { it.copy(uri = null, downloaded = false, sizeBytes = 0, coverage = emptyList()) }
                    track = track.copy(uri = null)
                }
                val match = AlbumTrackMatcher.match(track, album.title, library.observeSongs().first())
                if (match != null && fileExists(match.uri)) {
                    albums.updateTrack(albumId, track.id) { it.copy(uri = match.uri, status = "Already in library", error = null) }
                    continue
                }
                try {
                    val detail = "${index + 1}/${selected.size} · ${track.title}"
                    // Update the same notification; do not restart foreground work for each song.
                    val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, notification(album.title, detail).notification)
                    status.publishActive(ActiveDownload(album.title, album.source, detail, albumId = album.id))
                    albums.updateTrack(albumId, track.id) { it.copy(status = "Downloading", error = null) }
                    // Lossless staging plus publishing need room for two copies of the current track only.
                    val estimate = (track.durationMs / 1_000 * 176_400L * 2).coerceAtLeast(64L * 1024 * 1024)
                    check(StatFs(applicationContext.cacheDir.path).availableBytes > estimate) {
                        "Not enough temporary storage. Free space and resume the album."
                    }
                    downloadTrack(album, track)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (failure: Exception) {
                    val verification = failure is SpotiFlacException && failure.errorType.equals("verification_required", true)
                    albums.updateTrack(albumId, track.id) {
                        it.copy(status = if (verification) "Verify provider" else "Needs retry",
                            error = if (verification) "Open provider verification, then resume this album." else failure.message?.take(240) ?: "Download failed")
                    }
                    if (verification) return Result.failure()
                    if (failure is IOException || failure.cause is IOException || failure is DownloadEngineBusyException) needsRetry = true
                    // Space, login and shared-engine contention need user action/waiting, not attempts on every track.
                    if (failure is IllegalStateException || failure is DownloadEngineBusyException ||
                        (album.source == "SOULSEEK" && soulseek.connectionState.value.status != SoulseekConnectionStatus.CONNECTED)) break
                }
            }
            // One incremental scan for the album, including partial batches, instead of a full scan per song.
            status.publishActive(ActiveDownload(album.title, album.source, "Updating your library…", albumId = album.id))
            scan(force = false).collect { }
            val remaining = albums.journeys.value.find { it.id == albumId }?.tracks
                ?.count { it.id in selectedIds && it.uri == null } ?: selected.size
            status.publishOutcome(album.title, remaining == 0,
                if (remaining == 0) "Selected tracks are ready in Library." else "$remaining selected tracks need another attempt.")
            return if (needsRetry && runAttemptCount < 2) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                albums.journeys.value.find { it.id == albumId }?.tracks?.filter { it.status == "Downloading" }?.forEach { t ->
                    albums.updateTrack(albumId, t.id) { it.copy(status = "Paused", error = null) }
                }
            }
            throw cancelled
        } catch (failure: Exception) {
            status.publishOutcome(album.title, false, failure.message ?: "Album download needs attention.")
            return if (failure is IOException && runAttemptCount < 2) Result.retry() else Result.failure()
        } finally { status.clear() }
    }

    private fun ensureActive() { if (isStopped) throw CancellationException("Album paused") }

    private fun fileExists(uri: String): Boolean = try {
        applicationContext.contentResolver.openAssetFileDescriptor(android.net.Uri.parse(uri), "r")?.use { true } ?: false
    } catch (_: java.io.FileNotFoundException) { false }
    // SecurityException deliberately propagates: inaccessible storage is not evidence that a file is missing.

    private suspend fun downloadTrack(album: AlbumJourney, track: AlbumJourneyTrack) {
        val identified = IdentifiedTrack(track.artist, track.title, "${track.artist} - ${track.title}",
            album.coverUrl, album.title, track.durationMs, metadataId = track.id)
        var staged: File? = null
        try {
            var provider: String? = null
            var username: String? = null
            val file: File
            val extension: String
            if (album.source == "SPOTIFLAC") {
                val result = spoti.download(identified, SpotiFlacOutputFormat.fromName(album.format), SpotiFlacRequestOwner.ALBUM_DOWNLOAD) { p ->
                    status.publishActive(ActiveDownload(track.title, "SpotiFLAC", p.detail ?: p.stage.label, p.fraction, album.id))
                }
                file = result.tempFile; extension = result.outputFormat.extension; provider = result.provider
            } else {
                check(soulseek.connectionState.value.status == SoulseekConnectionStatus.CONNECTED) { "Connect Soulseek in Downloads, then resume this album." }
                val preference = if (album.format == "MP3_320") SoulseekFormatPreference.MP3_ONLY else SoulseekFormatPreference.FLAC_ONLY
                status.publishActive(ActiveDownload(track.title, "Soulseek", "Finding an available peer…", null, album.id))
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
                        status.publishActive(ActiveDownload(track.title, "Soulseek", p.status, p.fraction, album.id))
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
            val info = if (extension == "mp3") downloads.validateStagedMp3(file) else downloads.validateStagedFlac(file)
            require(info.durationMs > 0 && kotlin.math.abs(info.durationMs - track.durationMs) <= 5_000) {
                "The downloaded duration does not match this edition. The file was not imported."
            }
            val name = "${album.artist} - ${album.title} - ${track.disc}-${track.number.toString().padStart(2, '0')} - ${track.title}.$extension"
            // Finish the short publish/checkpoint transaction even if Pause is tapped during the copy.
            withContext(NonCancellable + Dispatchers.IO) {
                val saved = if (extension == "mp3") downloads.publishStagedMp3(file, name) else downloads.publishStagedFlac(file, name)
                staged = null
                albums.updateTrack(album.id, track.id) { it.copy(uri = saved.uri.toString(), downloaded = true,
                    sizeBytes = saved.sizeBytes, status = "Downloaded", error = null) }
                records.upsert(DownloadRecordEntity(uri = saved.uri.toString(), title = track.title, artist = track.artist,
                    source = album.source, provider = provider, format = extension.uppercase(), bitDepth = info.bitDepth,
                    sampleRateHz = info.sampleRateHz, fileSizeBytes = saved.sizeBytes,
                    downloadedAt = System.currentTimeMillis(), soulseekUsername = username, originalTrackId = track.id, isrc = null))
            }
        } finally { staged?.delete() }
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
        const val KEY_ALBUM = "albumId"
        const val KEY_TRACKS = "trackIds"
        const val KEY_SOURCE = "source"
        const val KEY_FORMAT = "format"
        const val WORK = "harmony-album-download"
        private const val CHANNEL = "harmony_album_downloads"
        private const val NOTIFICATION_ID = 7_846
        fun request(id: String, wifiOnly: Boolean, trackIds: List<String>? = null,
            source: String? = null, format: String? = null): OneTimeWorkRequest = OneTimeWorkRequestBuilder<AlbumDownloadWorker>()
            .setInputData(Data.Builder().putString(KEY_ALBUM, id).apply {
                trackIds?.let { putStringArray(KEY_TRACKS, it.toTypedArray()) }
                source?.let { putString(KEY_SOURCE, it) }; format?.let { putString(KEY_FORMAT, it) }
            }.build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag(id).build()
    }
}
