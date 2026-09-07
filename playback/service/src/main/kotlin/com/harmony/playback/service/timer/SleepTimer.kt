package com.harmony.playback.service.timer

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import dagger.hilt.android.scopes.ServiceScoped

/**
 * Sleep timer living inside the service (not the UI) so it survives the
 * activity being destroyed — the whole point of a sleep timer.
 *
 * [remainingMs] is exposed as a flow and mirrored to the UI via the session's
 * extras so the settings sheet can show a live countdown.
 *
 * "Finish track" semantics: on expiry we don't pause immediately; we set a
 * flag and pause on the next media item transition, so the current song plays
 * to its end.
 */
@ServiceScoped
class SleepTimer @Inject constructor() {

    private val _remainingMs = MutableStateFlow<Long?>(null)
    val remainingMs: StateFlow<Long?> = _remainingMs

    private var job: Job? = null
    private var player: Player? = null
    private var pauseAtTrackEnd = false
    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            finishIfPending()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) finishIfPending()
        }
    }

    private fun finishIfPending() {
        if (pauseAtTrackEnd) {
            pauseAtTrackEnd = false
            player?.pause()
        }
    }

    fun attach(player: Player, scope: CoroutineScope) {
        detach()
        this.player = player
        player.addListener(listener)
        this.scope = scope
    }

    fun detach() {
        job?.cancel()
        player?.removeListener(listener)
        player = null
        scope = null
        pauseAtTrackEnd = false
        _remainingMs.value = null
    }

    private var scope: CoroutineScope? = null

    fun set(durationMs: Long?, finishTrack: Boolean) {
        job?.cancel()
        pauseAtTrackEnd = false
        if (durationMs == null) {
            _remainingMs.value = null
            return
        }
        val activeScope = scope ?: return
        job = activeScope.launch {
            val started = SystemClock.elapsedRealtime()
            val duration = durationMs.coerceAtLeast(0)
            _remainingMs.value = duration
            while (true) {
                val remaining = (duration - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(0)
                _remainingMs.value = remaining
                if (remaining == 0L) break
                delay(minOf(TICK_MS, remaining))
            }
            _remainingMs.value = null
            if (finishTrack && player?.playbackState != Player.STATE_ENDED && player?.currentMediaItem != null) {
                pauseAtTrackEnd = true
            } else {
                player?.pause()
            }
        }
    }

    private companion object {
        const val TICK_MS = 1_000L
    }
}
