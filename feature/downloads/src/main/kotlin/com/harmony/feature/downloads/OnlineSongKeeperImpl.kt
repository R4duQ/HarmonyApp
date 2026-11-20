package com.harmony.feature.downloads

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.harmony.core.common.crash.DiagnosticsLog
import com.harmony.core.database.dao.DownloadRecordDao
import com.harmony.core.database.entity.DownloadRecordEntity
import com.harmony.core.model.Song
import com.harmony.domain.library.discovery.TrackIdentity
import com.harmony.domain.library.repository.LibraryRepository
import com.harmony.domain.library.usecase.ScanLibraryUseCase
import com.harmony.domain.playback.KeepStatus
import com.harmony.domain.playback.OnlineSongKeeper
import com.harmony.domain.playback.OnlineSongs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps online mix songs: the prepared FLAC in the online cache (tagged by
 * the engine, cover included, as any download) is copied, checked, and
 * published through the download folder like a download the user started;
 * the library scan then adds it, and it is analysed like any song.
 *
 * The cached file is copied, never moved: the player may be reading it.
 * Kept songs and the count of plays to the end are remembered in a small
 * preferences file, by video id.
 */
@Singleton
class OnlineSongKeeperImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadsLazy: dagger.Lazy<MusicDownloadRepository>,
    private val records: DownloadRecordDao,
    private val scan: ScanLibraryUseCase,
    private val library: LibraryRepository,
) : OnlineSongKeeper {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val lock = Mutex()
    private val _status = MutableStateFlow<Map<String, KeepStatus>>(emptyMap())
    private var loaded = false

    override val status: StateFlow<Map<String, KeepStatus>> = _status.asStateFlow()

    init {
        // What was kept before is known by the time the queue is shown, read off the main thread.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { runCatching { load() } }
    }

    override suspend fun keep(song: Song): Song? = withContext(Dispatchers.IO) {
        val videoId = OnlineSongs.keyOf(song.uri)?.let(OnlineSongs::videoIdOf) ?: return@withContext null
        load()
        lock.withLock {
            // Kept before (by hand or by itself), or the library has this recording already.
            (_status.value[videoId] as? KeepStatus.Kept)?.let { kept ->
                librarySong(kept.uri, song)?.let { return@withLock it }
                // Saved, and the library hasn't listed it yet: not a second copy.
                if (stillThere(kept.uri)) return@withLock null
            }
            libraryCopyOf(song)?.let { existing ->
                remember(videoId, existing.uri)
                return@withLock existing
            }
            set(videoId, KeepStatus.Keeping)
            try {
                val saved = copyIntoLibrary(videoId, song)
                // Saved either way: a song the library hasn't listed yet is still kept, never copied twice.
                remember(videoId, saved.library?.uri ?: saved.fileUri)
                DiagnosticsLog.line("KEEP", "kept video=$videoId inLibrary=${saved.library != null}")
                saved.library
            } catch (e: CancellationException) {
                set(videoId, null)
                throw e
            } catch (e: KeepException) {
                set(videoId, KeepStatus.Failed(e.message ?: "Not saved"))
                DiagnosticsLog.line("KEEP", "failed video=$videoId ${e.message}")
                null
            } catch (e: HarmonyDownloadException) {
                set(videoId, KeepStatus.Failed(e.userMessage))
                DiagnosticsLog.error("KEEP", "keeping an online song failed: ${e.userMessage}", e)
                null
            } catch (e: Exception) {
                set(videoId, KeepStatus.Failed("Not saved (${e.javaClass.simpleName})"))
                DiagnosticsLog.error("KEEP", "keeping an online song failed", e)
                null
            }
        }
    }

    override suspend fun finished(song: Song): Int = withContext(Dispatchers.IO) {
        val videoId = OnlineSongs.keyOf(song.uri)?.let(OnlineSongs::videoIdOf) ?: return@withContext 0
        lock.withLock {
            val count = prefs.getInt(PLAYS + videoId, 0) + 1
            prefs.edit().putInt(PLAYS + videoId, count).apply()
            count
        }
    }

    /** A kept file: where it was saved, and the library's song for it once the library lists it. */
    private class Saved(val fileUri: String, val library: Song?)

    private suspend fun copyIntoLibrary(videoId: String, song: Song): Saved {
        val downloads = downloadsLazy.get()
        val source = OnlineSongs.fileFor(context.cacheDir, videoId)
        if (!source.isFile) throw KeepException("No longer on the phone: play it in a mix again to save it")
        val staging = File(context.cacheDir, STAGING).apply { mkdirs() }
        val copy = File(staging, "$videoId.flac")
        try {
            source.copyTo(copy, overwrite = true)
            val info = try {
                downloads.validateStagedFlac(copy)
            } catch (e: HarmonyDownloadException) {
                throw KeepException("The file is not whole: ${e.userMessage}")
            }
            val name = listOf(song.artist, song.title).filter { it.isNotBlank() }.joinToString(" - ").ifBlank { videoId }
            // From here on the file is the library's: no cancellation half-way through.
            val saved = withContext(NonCancellable) {
                val published = downloads.publishStagedFlac(copy, name)
                records.upsert(
                    DownloadRecordEntity(
                        uri = published.uri.toString(), title = song.title, artist = song.artist,
                        source = DownloadSource.SPOTIFLAC.name, provider = "online mix", format = "FLAC",
                        bitDepth = info.bitDepth, sampleRateHz = info.sampleRateHz, fileSizeBytes = published.sizeBytes,
                        downloadedAt = System.currentTimeMillis(), soulseekUsername = null,
                        originalTrackId = OnlineSongs.keyFor(videoId), isrc = null,
                    ),
                )
                published
            }
            val fileUri = saved.uri.toString()
            // Android may list a new file a moment after it is saved: look again briefly.
            repeat(LIBRARY_LOOKS) { attempt ->
                if (attempt > 0) delay(LIBRARY_LOOK_GAP_MS)
                scan(force = false).collect { }
                librarySong(fileUri, song)?.let { return Saved(fileUri, it) }
            }
            return Saved(fileUri, null)
        } finally {
            if (copy.exists()) copy.delete()
        }
    }

    /** The library's song for the saved file at [uri] (by MediaStore row), or the library's copy of the same recording. */
    private suspend fun librarySong(uri: String, song: Song): Song? {
        val songs = library.observeSongs().first()
        return KeptFileMatch.librarySong(songs, uri, mediaRowIdOf(uri)) ?: libraryCopyOf(song, songs)
    }

    /** Whether the file saved at [uri] is still there (the person may have deleted it since). */
    private fun stillThere(uri: String): Boolean = runCatching {
        context.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
    }.getOrDefault(false)

    /** The MediaStore row of a saved file: in its address, or (a chosen folder's document) asked of Android. */
    private fun mediaRowIdOf(uri: String): Long? = KeptFileMatch.mediaRowId(uri) ?: runCatching {
        MediaStore.getMediaUri(context, Uri.parse(uri))?.let { KeptFileMatch.mediaRowId(it.toString()) }
    }.getOrNull()

    /** The library's song of the same recording as [song] (artist, title, version, length), or null. */
    private suspend fun libraryCopyOf(song: Song, songs: List<Song>? = null): Song? {
        val probe = TrackIdentity.of(song.title, song.artist, song.durationMs)
        return (songs ?: library.observeSongs().first()).firstOrNull {
            it.id >= 0 && TrackIdentity.same(probe, TrackIdentity.of(it.title, it.artist, it.durationMs))
        }
    }

    private fun remember(videoId: String, uri: String) {
        prefs.edit().putString(KEPT + videoId, uri).apply()
        set(videoId, KeepStatus.Kept(uri))
    }

    private fun set(videoId: String, status: KeepStatus?) =
        _status.update { if (status == null) it - videoId else it + (videoId to status) }

    /** The songs kept before, read once. */
    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        val kept = prefs.all.mapNotNull { (key, value) ->
            if (key.startsWith(KEPT) && value is String) key.removePrefix(KEPT) to KeepStatus.Kept(value) else null
        }
        _status.update { current -> kept.toMap() + current }
    }

    private class KeepException(message: String) : Exception(message)

    private companion object {
        const val PREFS = "online_keep"
        const val KEPT = "kept:"
        const val PLAYS = "plays:"
        const val STAGING = "keep-staging"
        const val LIBRARY_LOOKS = 3
        const val LIBRARY_LOOK_GAP_MS = 1_500L
    }
}
