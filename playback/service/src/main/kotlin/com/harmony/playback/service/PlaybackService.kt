package com.harmony.playback.service

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.harmony.playback.service.player.CrossfadeController
import com.harmony.playback.service.player.HarmonyPlayer
import com.harmony.playback.service.session.HarmonyMediaLibraryCallback
import com.harmony.playback.service.timer.SleepTimer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Foreground playback service.
 *
 * Why [MediaLibraryService] and not plain MediaSessionService: the library
 * variant is what exposes a browsable content tree to Android Auto, Wear, and
 * Bluetooth AVRCP browsers. The browse tree itself is fed by the library
 * repository (arrives in Phase 4); until then [HarmonyMediaLibraryCallback]
 * serves an empty root so Auto connects cleanly.
 *
 * Media3 gives us for free once the session exists:
 *  - media notification with seek/skip actions + artwork
 *  - lock screen / AOD controls (via the notification on API 29+)
 *  - headset and Bluetooth media button routing
 *  - resumption of the last session after process death (playback resumption)
 *
 * The service runs in the main process. A separate :playback process was
 * considered (memory isolation) and rejected: it forces every repository the
 * browse tree needs into IPC-safe form, and modern LMK pressure on a lean
 * player service is low. Revisit only if field data shows OOM kills.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    @Inject lateinit var harmonyPlayer: HarmonyPlayer
    @Inject lateinit var callback: HarmonyMediaLibraryCallback
    @Inject lateinit var sleepTimer: SleepTimer
    @Inject lateinit var settingsRepository: com.harmony.core.datastore.SettingsRepository
    @Inject lateinit var albumJourneys: com.harmony.domain.library.repository.AlbumJourneyRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaLibrarySession? = null
    private var crossfade: CrossfadeController? = null
    private var albumListening: AlbumListeningMonitor? = null

    /** Last snapshot actually written, so the periodic save can skip no-ops. */
    private var lastSavedState: Triple<List<Long>, Int, Long>? = null

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaLibrarySession.Builder(this, harmonyPlayer.exoPlayer, callback)
            .setId(SESSION_ID)
            .build()
        crossfade = CrossfadeController(this, harmonyPlayer.exoPlayer, serviceScope).also { it.start() }
        startPeriodicStateSaving()
        albumListening = AlbumListeningMonitor(harmonyPlayer.exoPlayer, albumJourneys).also { it.start() }
        sleepTimer.attach(harmonyPlayer.exoPlayer, serviceScope)
        callback.attach(harmonyPlayer, crossfade!!, sleepTimer)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    /**
     * User swiped the app away from recents: by design (per-user preference,
     * not a bug) Harmony behaves like a podcast app here, not like Spotify —
     * playback STOPS, but exactly where it stopped is saved first, so
     * reopening the app restores the same queue, song, and position
     * (without auto-playing) instead of starting fresh.
     */
    /**
     * Reads the current queue/position off the player. Must be called on the
     * main thread (ExoPlayer requirement).
     */
    private fun snapshot(): Triple<List<Long>, Int, Long>? {
        val player = harmonyPlayer.exoPlayer
        val ids = (0 until player.mediaItemCount).mapNotNull {
            player.getMediaItemAt(it).mediaId.toLongOrNull()
        }
        if (ids.isEmpty()) return null
        return Triple(ids, player.currentMediaItemIndex, player.currentPosition.coerceAtLeast(0))
    }

    /**
     * Continuously persists playback position while the service is alive.
     *
     * onTaskRemoved alone is not enough: when music is playing in the
     * background, Android frequently keeps this service running after the
     * user swipes the app away, so onTaskRemoved never fires — nothing gets
     * saved, and the next launch restores whatever stale state was written
     * the last time the service actually died (typically the first song of
     * the session). Saving on a timer means the stored state is never more
     * than [SAVE_INTERVAL_MS] out of date, no matter how the app ends.
     */
    private fun startPeriodicStateSaving() {
        serviceScope.launch {
            while (isActive) {
                delay(SAVE_INTERVAL_MS)
                val snap = snapshot() ?: continue
                // A DataStore write is a whole-file rewrite plus an fsync.
                // Doing that every five seconds for as long as the service
                // lives — including while paused, when nothing moves, and the
                // service can sit paused for hours — is pure battery and flash
                // wear. Only write when the stored state would actually
                // differ; while playing the position moves every tick, so the
                // guard costs nothing where the saving matters.
                if (snap == lastSavedState) continue
                val (ids, index, position) = snap
                settingsRepository.saveLastPlaybackState(ids, index, position)
                lastSavedState = snap
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = harmonyPlayer.exoPlayer
        val snap = snapshot()
        val ids = snap?.first ?: emptyList()
        val index = snap?.second ?: 0
        val position = snap?.third ?: 0L
        player.pause()
        if (ids.isNotEmpty()) {
            // Blocking ON PURPOSE. The original version launched this on
            // serviceScope, but stopSelf() below triggers onDestroy(), which
            // cancels serviceScope — killing the async DataStore write before
            // it flushed. Net effect: state was saved successfully once and
            // then never again, so every relaunch restored that same stale
            // first session no matter what had actually been playing.
            //
            // The process is being torn down anyway, so briefly blocking here
            // is correct: there is no later frame to be janky. The timeout is
            // a safety valve so a wedged write can never hang teardown.
            runBlocking {
                withTimeoutOrNull(SAVE_TIMEOUT_MS) {
                    settingsRepository.saveLastPlaybackState(ids, index, position)
                }
            }
        }
        stopSelf()
    }

    override fun onDestroy() {
        // Last-chance save for the path where the service is destroyed
        // WITHOUT onTaskRemoved (system reclaiming memory, stopSelf from
        // elsewhere). Blocking is acceptable here for the same reason as in
        // onTaskRemoved: the process is going away regardless.
        runCatching {
            snapshot()?.let { (ids, index, position) ->
                runBlocking {
                    withTimeoutOrNull(SAVE_TIMEOUT_MS) {
                        settingsRepository.saveLastPlaybackState(ids, index, position)
                    }
                }
            }
        }
        albumListening?.stop()
        crossfade?.stop()
        sleepTimer.detach()
        callback.close()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val SESSION_ID = "harmony_session"
        const val SAVE_TIMEOUT_MS = 2_000L
        const val SAVE_INTERVAL_MS = 5_000L
    }
}
