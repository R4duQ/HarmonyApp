package com.harmony.feature.downloads

import android.app.*
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.harmony.core.database.dao.DownloadRecordDao
import com.harmony.core.database.entity.DownloadRecordEntity
import com.harmony.domain.library.discovery.LibraryIndex
import com.harmony.domain.library.discovery.PlaylistPlacement
import com.harmony.domain.library.repository.*
import com.harmony.domain.library.usecase.ScanLibraryUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

@HiltWorker
class DiscoveryDownloadWorker @AssistedInject constructor(
    @Assisted context: Context, @Assisted parameters: WorkerParameters,
    private val discovery: SongDiscoveryRepository,
    private val library: LibraryRepository,
    private val playlists: PlaylistRepository,
    private val scan: ScanLibraryUseCase,
    private val downloads: MusicDownloadRepository,
    private val spoti: SpotiFlacDownloadEngine,
    private val soulseek: SoulseekClient,
    private val records: DownloadRecordDao,
    statusCenter: DownloadStatusCenter,
) : CoroutineWorker(context, parameters) {
    private val publisher = statusCenter.forOwner(album = true)
    private val batchId get() = inputData.getString("batch") ?: error("Missing playlist selection")
    private fun batch() = discovery.state.value.batches.first { it.id == batchId }
    private suspend fun update(change: (DiscoveryBatch) -> DiscoveryBatch) = discovery.update { s ->
        s.copy(batches = s.batches.map { if (it.id == batchId) change(it) else it })
    }

    override suspend fun doWork(): Result {
        try {
            val snapshot = batch()
            require(snapshot.songs.isNotEmpty() && snapshot.songs.distinctBy { it.key }.size == snapshot.songs.size)
            val total = snapshot.songs.size
            val source = AlbumDownloadPolicy.source(inputData.getString("source") ?: snapshot.source)
            val format = SpotiFlacOutputFormat.fromName(inputData.getString("format") ?: snapshot.format)
            require(AlbumDownloadPolicy.formatFor(source, format) == format)
            setForeground(notification(snapshot.name, "Preparing $total songs…"))
            update { it.copy(locked = true) }
            // Recover any published file left unindexed by a killed process before matching.
            scan(force = false).collect { }
            val local = LibraryIndex(library.observeSongs().first())
            var transientFailure = false
            for ((index, song) in snapshot.songs.withIndex()) {
                currentCoroutineContext().ensureActive()
                val oldUri = batch().uris[song.key]
                if (oldUri != null && exists(oldUri)) continue
                val existing = local.match(song)?.uri?.takeIf(::exists)
                if (existing != null) {
                    update { it.copy(uris = it.uris + (song.key to existing), errors = it.errors - song.key) }; continue
                }
                update { it.copy(uris = it.uris - song.key, errors = it.errors - song.key, activeKey = song.key, status = "${index + 1}/$total · ${song.title}") }
                val detail = "${index + 1}/$total · ${song.title}"
                DiscoveryTransferProgress.report(snapshot.id, song.key, song.title, null)
                (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(NOTIFICATION, notification(snapshot.name, detail).notification)
                publisher.publishActive(ActiveDownload(snapshot.name, source.name, detail))
                try {
                    // One track is staged at a time; allow for hi-res input plus transcoding and publishing.
                    check(StatFs(applicationContext.cacheDir.path).availableBytes >
                        (song.durationMs.coerceAtLeast(240_000) / 1_000 * 576_000 * 3).coerceAtLeast(256L * 1024 * 1024)) {
                        "Not enough temporary storage. Free space and resume."
                    }
                    transfer(song, source, format)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    update { it.copy(errors = it.errors + (song.key to (e.message?.take(240) ?: "Download failed")),
                        verificationProvider = if (e is SpotiFlacException && e.errorType.equals("verification_required", true))
                            e.provider ?: e.verificationChallenge?.providerId else it.verificationProvider) }
                    if (e is IOException || e is DownloadEngineBusyException) transientFailure = true
                    DiscoveryTransferProgress.report(snapshot.id, null, null, null)
                    if (e is DownloadEngineBusyException || e is IllegalStateException ||
                        (e is SpotiFlacException && e.errorType.equals("verification_required", true)) ||
                        (source == DownloadSource.SOULSEEK && soulseek.connectionState.value.status != SoulseekConnectionStatus.CONNECTED)) break
                }
            }
            update { it.copy(status = "Indexing saved tracks…", activeKey = null) }
            DiscoveryTransferProgress.report(snapshot.id, null, null, null)
            scan(force = false).collect { }
            val indexed = LibraryIndex(library.observeSongs().first())
            val completed = batch()
            val progress = PlaylistPlacement.progress(completed, indexed)
            val ready = progress.availableCount
            val existingPlaylist = completed.playlistId
            when {
                // A playlist already exists (made with the songs available earlier): add the new arrivals, once.
                existingPlaylist != null && !completed.playlistDeleted -> {
                    val additions = PlaylistPlacement.toAppend(completed, progress)
                    if (additions.isNotEmpty()) {
                        if (playlists.observePlaylists().first().none { it.id == existingPlaylist }) {
                            update { it.copy(playlistDeleted = true) }
                        } else {
                            playlists.addSongs(existingPlaylist, additions.map { it.second })
                            update { it.copy(placedKeys = it.placedKeys + additions.map { a -> a.first }) }
                        }
                    }
                }
                // Everything is here and nothing was saved yet: save the whole playlist in one transaction.
                // (Never after the user deleted it: the same id would bring it back.)
                existingPlaylist == null && ready == total -> {
                    val id = playlists.saveDiscoveryBatch(snapshot.id, completed.name, PlaylistPlacement.toCreate(completed, progress))
                    update { it.copy(playlistId = id, placedKeys = progress.available.keys, errors = emptyMap(), status = "Saved in Your playlists · $total songs") }
                    publisher.publishOutcome(snapshot.name, true, "All $total songs saved in Your playlists.")
                    return Result.success()
                }
            }
            if (ready < total) {
                update { it.copy(status = "$ready/$total ready. Missing songs are kept for another try.") }
                publisher.publishOutcome(snapshot.name, false, "Progress saved. Retry from Discover; finished files aren't downloaded again.")
                return if (transientFailure && runAttemptCount < 2) Result.retry() else Result.failure()
            }
            update { it.copy(status = "All $total songs are in the playlist") }
            publisher.publishOutcome(snapshot.name, true, "All $total songs are in the playlist.")
            return Result.success()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { update { it.copy(status = "Paused · saved files kept", activeKey = null) } }
            DiscoveryTransferProgress.report(inputData.getString("batch").orEmpty(), null, null, null)
            throw e
        } catch (e: Exception) {
            update { it.copy(status = e.message?.take(240) ?: "Download needs attention. Resume to retry.", activeKey = null) }
            DiscoveryTransferProgress.report(inputData.getString("batch").orEmpty(), null, null, null)
            return if (e is IOException && runAttemptCount < 2) Result.retry() else Result.failure()
        } finally { publisher.clear() }
    }

    private fun exists(uri: String): Boolean = try {
        applicationContext.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
    } catch (_: java.io.FileNotFoundException) { false }
    // SecurityException is not a missing file: stop and ask the user to restore access.

    private suspend fun transfer(song: DiscoverySong, source: DownloadSource, format: SpotiFlacOutputFormat) {
        val track = identifiedDiscoverySong(song)
        var staged: File? = null
        try {
            var provider: String? = null; var username: String? = null
            val file: File; val extension: String
            if (source == DownloadSource.SPOTIFLAC) {
                val result = spoti.download(track, format, SpotiFlacRequestOwner.DISCOVERY_DOWNLOAD) { p ->
                    publisher.publishActive(ActiveDownload(song.title, "SpotiFLAC", p.detail ?: p.stage.label, p.fraction))
                    DiscoveryTransferProgress.report(batchId, song.key, song.title, p.fraction)
                }
                file = result.tempFile; extension = result.outputFormat.extension; provider = result.provider
            } else {
                check(soulseek.connectionState.value.status == SoulseekConnectionStatus.CONNECTED) { "Connect Soulseek in Downloads, then resume here." }
                val preference = if (format == SpotiFlacOutputFormat.MP3_320) SoulseekFormatPreference.MP3_ONLY else SoulseekFormatPreference.FLAC_ONLY
                val candidates = soulseek.searchFlac(track.displayName, SoulseekSearchMode.BALANCED, false, preference)
                    .filter { it.score >= 80 && (song.durationMs <= 0 || it.durationSeconds?.let { d -> kotlin.math.abs(d * 1000L - song.durationMs) <= 5000 } != false) }
                    .distinctBy { it.username.lowercase() }.sortedWith(compareByDescending<SoulseekSearchCandidate> { it.freeUploadSlot }
                        .thenBy { it.queueLength.coerceAtLeast(0) }.thenByDescending { it.averageSpeedBytesPerSecond }).take(3)
                require(candidates.isNotEmpty()) { "No sufficiently close peer match. Retry or choose SpotiFLAC." }
                var downloaded: SoulseekDownloadedFile? = null; var chosen: SoulseekSearchCandidate? = null; var last: Exception? = null
                for (candidate in candidates) {
                    try { downloaded = soulseek.download(candidate, SoulseekDownloadPatience.FAST, albumTransfer = true); chosen = candidate; break }
                    catch (e: CancellationException) { throw e } catch (e: Exception) { last = e }
                }
                file = downloaded?.tempFile ?: throw last ?: IOException("No reachable peer. Resume to retry.")
                extension = requireNotNull(chosen).extension.lowercase(); username = chosen.username
            }
            staged = file
            val info = if (extension == "mp3") downloads.validateStagedMp3(file) else downloads.validateStagedFlac(file)
            require(info.durationMs > 0 && (song.durationMs <= 0 || kotlin.math.abs(info.durationMs - song.durationMs) <= 5000)) {
                "Downloaded recording has a different duration. It was not imported."
            }
            val name = "${song.artist} - ${song.title}.$extension"
            withContext(NonCancellable + Dispatchers.IO) {
                val saved = if (extension == "mp3") downloads.publishStagedMp3(file, name) else downloads.publishStagedFlac(file, name)
                staged = null
                update { it.copy(uris = it.uris + (song.key to saved.uri.toString()), errors = it.errors - song.key) }
                records.upsert(DownloadRecordEntity(uri = saved.uri.toString(), title = song.title, artist = song.artist,
                    source = source.name, provider = provider, format = extension.uppercase(), bitDepth = info.bitDepth,
                    sampleRateHz = info.sampleRateHz, fileSizeBytes = saved.sizeBytes, downloadedAt = System.currentTimeMillis(),
                    soulseekUsername = username, originalTrackId = song.key, isrc = song.isrc))
            }
        } finally { staged?.delete() }
    }

    private fun notification(title: String, text: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Discover playlist downloads", NotificationManager.IMPORTANCE_LOW))
        val launch = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
        val n = NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title).setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setContentIntent(launch?.let { PendingIntent.getActivity(applicationContext, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) })
            .addAction(android.R.drawable.ic_media_pause, "Pause", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        return ForegroundInfo(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
    companion object {
        const val WORK = "harmony-discover-playlist-download"
        private const val CHANNEL = "harmony_discover_downloads"
        private const val NOTIFICATION = 7847
        fun request(batch: DiscoveryBatch, wifi: Boolean) = OneTimeWorkRequestBuilder<DiscoveryDownloadWorker>()
            .setInputData(Data.Builder().putString("batch", batch.id).putString("source", batch.source).putString("format", batch.format).build())
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).setRequiredNetworkType(
                if (batch.songs.all { it.localUri != null || batch.uris[it.key] != null }) NetworkType.NOT_REQUIRED
                else if (wifi) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag(batch.id).build()
    }
}

internal fun identifiedDiscoverySong(song: DiscoverySong) = IdentifiedTrack(song.artist, song.title,
    "${song.artist} - ${song.title}", song.artwork, song.album, song.durationMs, song.isrc,
    metadataId = song.key.takeIf { it.startsWith("dz:") }?.substringAfter(':'))

/**
 * Live per-song progress for the Discover panel. In memory on purpose: it
 * changes several times a second, and the durable facts (which files exist,
 * which failed) are already saved in the selection after every song.
 */
object DiscoveryTransferProgress {
    data class Current(val batchId: String, val songKey: String, val title: String, val fraction: Float?)
    private val mutable = MutableStateFlow<Current?>(null)
    val state: StateFlow<Current?> = mutable.asStateFlow()
    fun report(batchId: String, songKey: String?, title: String?, fraction: Float?) {
        mutable.value = if (songKey == null || title == null) null else Current(batchId, songKey, title, fraction?.coerceIn(0f, 1f))
    }
}
