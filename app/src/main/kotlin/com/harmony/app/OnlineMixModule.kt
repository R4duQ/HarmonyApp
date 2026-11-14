package com.harmony.app

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.harmony.core.common.crash.DiagnosticsLog
import com.harmony.core.model.Song
import com.harmony.domain.library.discovery.TrackIdentity
import com.harmony.domain.library.repository.LibraryRepository
import com.harmony.domain.online.CatalogTrack
import com.harmony.domain.online.LocalCopies
import com.harmony.domain.online.OnlineMixCoordinator
import com.harmony.domain.online.OnlineMixFeed
import com.harmony.domain.online.SeedCleaner
import com.harmony.data.shuffle.TasteMemory
import com.harmony.domain.online.UrlConnectionTransport
import com.harmony.domain.online.YtMusicClient
import com.harmony.feature.downloads.FileFingerprinter
import com.harmony.feature.downloads.OnlineMixPreparer
import com.harmony.feature.downloads.OnlineSongKeeperImpl
import com.harmony.domain.playback.OnlineSongKeeper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The online side of "Mix din piesa asta": YouTube Music for discovery (no
 * account), Mornye for the audio, the library for recordings it already has.
 * Used only while the setting is on (SettingsApplier sets the mix source;
 * OFFLINE makes no request at all).
 */
@Module
@InstallIn(SingletonComponent::class)
object OnlineMixModule {
    /** "Keep" for online mix songs: their prepared file, copied into the library. */
    @Provides
    @Singleton
    fun onlineSongKeeper(keeper: OnlineSongKeeperImpl): OnlineSongKeeper = keeper

    @Provides
    @Singleton
    fun onlineMixFeed(
        preparer: OnlineMixPreparer,
        local: LibraryLocalCopies,
        fingerprinter: FileFingerprinter,
        taste: TasteMemory,
        guesser: GeminiNanoGuesser,
    ): OnlineMixFeed {
        val country = Locale.getDefault().country.takeIf { it.length == 2 }
        return OnlineMixCoordinator(
            catalog = YtMusicClient(
                UrlConnectionTransport(),
                location = country,
                onError = { what, error -> DiagnosticsLog.error("ONLINE_MIX", what, error) },
            ),
            preparer = preparer,
            local = local,
            // Tags not enough: the file is recognised by its sound (only a fingerprint is sent).
            fingerprinter = fingerprinter,
            // Nothing else found it: Gemini Nano on the phone guesses from the names (nothing is sent).
            guesser = guesser,
            // What each radio puts together, remembered for the offline mix (names and ids only).
            radioObserver = { seedSongId, seed, sameSong, tracks ->
                taste.record(
                    seedSongId = seedSongId,
                    seedArtist = SeedCleaner.artistOf(seed).orEmpty(),
                    sameSong = sameSong,
                    tracks = tracks.map { TasteMemory.Track(it.title, it.artists.firstOrNull().orEmpty()) },
                )
            },
            // Nothing that goes wrong online may take the app down: it is logged, and the
            // coordinator reports it as the mix's state (the library's mix plays on).
            scope = CoroutineScope(
                SupervisorJob() + Dispatchers.Default +
                    CoroutineExceptionHandler { _, error ->
                        Log.e("ONLINE_MIX", "online mix stopped", error)
                        DiagnosticsLog.error("ONLINE_MIX", "online mix stopped", error)
                    },
            ),
            // Ids, counts and timings only.
            log = { line ->
                Log.i("ONLINE_MIX", line)
                DiagnosticsLog.line("ONLINE_MIX", line.removePrefix("ONLINE_MIX "))
            },
            onError = { what, error -> DiagnosticsLog.error("ONLINE_MIX", what, error) },
        )
    }
}

/**
 * The library's song for a recording, when it has one that plays: same lead
 * artist, base title and versions, and a length within a few seconds when
 * both are known (TrackIdentity, as Discover matches). The index is rebuilt
 * at most every [REFRESH_MS].
 */
@Singleton
class LibraryLocalCopies @Inject constructor(
    private val library: LibraryRepository,
    @ApplicationContext private val context: Context,
) : LocalCopies {
    private val lock = Mutex()
    private var builtAt: Long? = null
    private var index: Map<String, List<Song>> = emptyMap()

    override suspend fun find(track: CatalogTrack): Long? {
        val probe = TrackIdentity.of(track.title, track.artists.firstOrNull().orEmpty(), track.durationMs ?: 0)
        val song = index()[probe.key]?.firstOrNull { TrackIdentity.same(probe, TrackIdentity.of(it.title, it.artist, it.durationMs)) }
            ?: return null
        return song.id.takeIf { opens(song.uri) }
    }

    private suspend fun index(): Map<String, List<Song>> = lock.withLock {
        val now = SystemClock.elapsedRealtime()
        val previous = builtAt
        if (previous == null || now - previous >= REFRESH_MS) {
            index = library.observeSongs().first().filter { it.id >= 0 }.groupBy { TrackIdentity.of(it.title, it.artist, it.durationMs).key }
            builtAt = now
        }
        index
    }

    private fun opens(uri: String): Boolean = runCatching {
        context.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
    }.getOrDefault(false)

    private companion object {
        const val REFRESH_MS = 5 * 60_000L
    }
}
