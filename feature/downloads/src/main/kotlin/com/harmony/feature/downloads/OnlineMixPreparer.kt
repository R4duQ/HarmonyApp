package com.harmony.feature.downloads

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import android.os.SystemClock
import com.harmony.core.datastore.SettingsRepository
import com.harmony.domain.online.CandidatePreparer
import com.harmony.domain.online.CatalogTrack
import com.harmony.domain.online.PrepareOutcome
import com.harmony.domain.online.PreparedAudio
import com.harmony.domain.playback.OnlineSongs
import com.harmony.domain.playback.PlaybackController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Gets an online recommendation ready to play, through the download engine
 * the build ships (Mornye or SpotiFLAC) and its own routing, sign-in,
 * quality rules and staging, as for any track download:
 *
 *  1. A file prepared earlier for the same video, still whole and of the
 *     right length, is used again (the online cache).
 *  2. Rules first: Wi-Fi only unless mobile data is allowed, enough free
 *     space, the cache within [OnlineCache.LIMIT_BYTES].
 *  3. The providers' own search (the Downloads screen's), and only a result
 *     that is the same recording ([OnlineSourceMatch]).
 *  4. The engine's download as FLAC, in the background: a download the
 *     user starts takes the engine and this one is tried again later.
 *  5. The file is read, not trusted: real FLAC (`fLaC`, STREAMINFO), and a
 *     length within [OnlineSourceMatch.LENGTH_TOLERANCE_MS] of the catalog's.
 *     Then it moves into the online cache under its video id.
 *
 * Each failure keeps its meaning: nothing listed by the providers is
 * "not available"; a provider needing sign-in or verification is "not
 * verified" (nothing confirmed either way); the network, a busy engine or a
 * rate limit is "try later"; a file that is not lossless audio of this
 * recording is "rejected" and deleted.
 */
@Singleton
class OnlineMixPreparer @Inject constructor(
    @ApplicationContext private val context: Context,
    // Lazy: created with the queue manager at start-up, but the engine is heavy and only needed
    // once an online mix prepares a song.
    private val engineLazy: dagger.Lazy<SpotiFlacDownloadEngine>,
    private val downloadsLazy: dagger.Lazy<MusicDownloadRepository>,
    private val settings: SettingsRepository,
    private val playback: PlaybackController,
) : CandidatePreparer {
    private val cache = OnlineCache(File(context.cacheDir, OnlineSongs.CACHE_DIR))
    private val engine: SpotiFlacDownloadEngine get() = engineLazy.get()
    private val downloads: MusicDownloadRepository get() = downloadsLazy.get()

    override suspend fun prepare(track: CatalogTrack, onProgress: (Float?) -> Unit): PrepareOutcome = withContext(Dispatchers.IO) {
        val target = OnlineSongs.fileFor(context.cacheDir, track.videoId)
        cached(target, track)?.let { return@withContext PrepareOutcome.Ready(it) }

        held()?.let { return@withContext it }

        val lead = track.artists.firstOrNull().orEmpty()
        val listed = try {
            downloads.searchSpotiFlacTracks("$lead ${track.title}".trim())
        } catch (e: CancellationException) {
            throw e
        } catch (e: SpotiFlacException) {
            return@withContext OnlineSourceMatch.outcomeOf(e)
        } catch (e: Exception) {
            return@withContext PrepareOutcome.TemporaryError("search failed (${e.javaClass.simpleName})")
        }
        val chosen = OnlineSourceMatch.pick(track, listed)
            ?: return@withContext PrepareOutcome.NotAvailable("no lossless source lists this recording")
        val identified = try {
            downloads.resolveSpotiFlacTrack(chosen)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext PrepareOutcome.TemporaryError("lookup failed (${e.javaClass.simpleName})")
        }

        val downloaded = try {
            engine.download(identified, SpotiFlacOutputFormat.FLAC_LOSSLESS, SpotiFlacRequestOwner.ONLINE_MIX) { p -> onProgress(p.fraction) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DownloadEngineBusyException) {
            return@withContext PrepareOutcome.TemporaryError("the download engine is in use", BUSY_RETRY_MS)
        } catch (e: SpotiFlacException) {
            return@withContext OnlineSourceMatch.outcomeOf(e)
        } catch (e: IOException) {
            return@withContext PrepareOutcome.TemporaryError("network (${e.javaClass.simpleName})")
        }

        val started = SystemClock.elapsedRealtime()
        val staged = downloaded.tempFile
        try {
            val info = try {
                downloads.validateStagedFlac(staged)
            } catch (e: HarmonyDownloadException) {
                return@withContext PrepareOutcome.RejectedAudio("not lossless FLAC: ${e.userMessage}")
            }
            val expected = track.durationMs ?: identified.durationMs.takeIf { it > 0 }
            if (!OnlineSourceMatch.lengthAgrees(info.durationMs, expected)) {
                return@withContext PrepareOutcome.RejectedAudio("length differs by ${abs(info.durationMs - (expected ?: 0)) / 1000} s")
            }
            withContext(NonCancellable) {
                cache.makeRoom(staged.length(), inUse())
                cache.place(staged, target)
            }
            engine.reportImported(downloaded, SystemClock.elapsedRealtime() - started)
            PrepareOutcome.Ready(PreparedAudio(target.path, info.codec, info.bitDepth, info.sampleRateHz, info.durationMs, target.length(), downloaded.provider))
        } finally {
            if (staged.exists()) staged.delete()
        }
    }

    /** A file prepared before for this video, still FLAC and of the right length. */
    private suspend fun cached(target: File, track: CatalogTrack): PreparedAudio? {
        if (!target.isFile) return null
        val info = runCatching { downloads.validateStagedFlac(target) }.getOrNull()
        if (info == null || !OnlineSourceMatch.lengthAgrees(info.durationMs, track.durationMs)) {
            target.delete()
            return null
        }
        target.setLastModified(System.currentTimeMillis())
        return PreparedAudio(target.path, info.codec, info.bitDepth, info.sampleRateHz, info.durationMs, target.length(), null)
    }

    /** A rule that says "not now", or null. */
    private suspend fun held(): PrepareOutcome? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val caps = connectivity?.activeNetwork?.let(connectivity::getNetworkCapabilities)
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return PrepareOutcome.TemporaryError("no network")
        }
        val metered = connectivity.isActiveNetworkMetered
        if (metered && !settings.settings.first().onlineMixOnMobileData) return PrepareOutcome.Held("waiting for Wi-Fi")
        val free = runCatching { StatFs(context.cacheDir.path).availableBytes }.getOrDefault(0L)
        if (free < MIN_FREE_BYTES) return PrepareOutcome.Held("not enough free space")
        return null
    }

    /** The online songs in the queue now: their files are never evicted. */
    private fun inUse(): Set<String> =
        playback.playerState.value.queue.mapNotNullTo(HashSet()) { OnlineSongs.keyOf(it.uri)?.let(OnlineSongs::videoIdOf) }

    private companion object {
        const val BUSY_RETRY_MS = 60_000L
        /** Room for a hi-res download, its finalization and the move (as the album worker checks). */
        const val MIN_FREE_BYTES = 512L * 1024 * 1024
    }
}
