package com.harmony.playback.service.player

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Overlaps a temporary player with the session player, which still owns the queue. */
@UnstableApi
class CrossfadeController(
    context: Context,
    private val player: ExoPlayer,
    private val scope: CoroutineScope,
    private val previewFactory: () -> ExoPlayer = {
        ExoPlayer.Builder(context, DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER))
            .build().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
            }
    },
) {
    var crossfadeSeconds: Int = 0
        set(value) {
            field = value.coerceIn(0, 12)
            if (field == 0) cancelOverlap()
        }

    private var monitorJob: Job? = null
    private var fadeJob: Job? = null
    private var previewPlayer: ExoPlayer? = null
    private var nextItem: MediaItem? = null
    private var triggered = false
    private var started = false

    // The handoff: the second player keeps sounding until the session player,
    // which has just jumped to the same song, is audible again.
    private var swapPreview: ExoPlayer? = null
    private var swapItem: MediaItem? = null
    private var swapJob: Job? = null
    private var ownSeek = false

    /** How long the session player takes to start at a new position; learned at each handoff. */
    private var seekLeadMs = INITIAL_SEEK_LEAD_MS

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) handoff()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) {
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) handoff()
                else cancelOverlap()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Includes buffering, audio-focus loss and becoming noisy.
            if (!isPlaying && previewPlayer != null) cancelOverlap()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Our own handoff moves the session player to the song already playing.
            if (swapPreview == null || mediaItem != swapItem) cancelOverlap()
            triggered = false
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                if (ownSeek) {
                    ownSeek = false
                    return
                }
                cancelOverlap()
                triggered = false
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) { cancelOverlap() }
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { cancelOverlap() }
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (previewPlayer != null && !targetStillNext()) cancelOverlap()
        }
        override fun onPlayerError(error: PlaybackException) { cancelOverlap() }
    }

    fun start() {
        if (started) return
        started = true
        player.addListener(listener)
        player.setPauseAtEndOfMediaItems(false)
        monitorJob = scope.launch {
            while (isActive) {
                val remaining = player.duration - player.currentPosition
                if (crossfadeSeconds > 0 && player.repeatMode != Player.REPEAT_MODE_ONE &&
                    previewPlayer == null && !triggered && player.isPlaying &&
                    !player.isCurrentMediaItemLive && player.duration > 0 &&
                    remaining in 1..(crossfadeSeconds * 1000L)
                ) {
                    val index = player.nextMediaItemIndex
                    if (index in 0 until player.mediaItemCount && index != player.currentMediaItemIndex) {
                        val item = player.getMediaItemAt(index)
                        if (item.localConfiguration != null) {
                            triggered = true
                            startOverlap(item)
                        }
                    }
                }
                delay(250)
            }
        }
    }

    private fun targetStillNext(): Boolean {
        val index = player.nextMediaItemIndex
        return index in 0 until player.mediaItemCount && player.getMediaItemAt(index) == nextItem
    }

    private fun startOverlap(item: MediaItem) {
        try {
            val preview = previewFactory()
            previewPlayer = preview
            nextItem = item
            preview.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) { cancelOverlap() }
            })
            preview.volume = 0f
            preview.playbackParameters = player.playbackParameters
            preview.setMediaItem(item)
            preview.prepare()
            preview.playWhenReady = true
            fadeJob = scope.launch {
                // Do not silence or hold the main track while the second decoder loads.
                while (previewPlayer === preview && !preview.isPlaying) delay(25)
                if (previewPlayer !== preview) return@launch
                val initialRemaining = (player.duration - player.currentPosition).coerceAtLeast(1)
                player.setPauseAtEndOfMediaItems(true)
                while (isActive && previewPlayer === preview) {
                    if (!preview.isPlaying || !targetStillNext()) {
                        cancelOverlap()
                        return@launch
                    }
                    preview.playbackParameters = player.playbackParameters
                    // Follow actual playback, including speeds above/below 1x.
                    val remaining = (player.duration - player.currentPosition).coerceAtLeast(0)
                    val fraction = (1f - remaining.toFloat() / initialRemaining).coerceIn(0f, 1f)
                    val gain = (1f - kotlin.math.cos(fraction * Math.PI.toFloat())) / 2f
                    player.volume = 1f - gain
                    preview.volume = gain
                    delay(50)
                }
            }
        } catch (_: Exception) {
            cancelOverlap()
        }
    }

    /**
     * The outgoing song has ended and the second player is at full volume on
     * the next one. The session player has to jump into that song, and a jump
     * to a new position takes a moment to start sounding. Stopping the second
     * player first left that moment silent, so it keeps playing until the
     * session player is audible, and a short equal-power swap hands over.
     * The session player aims where the second player will be by then.
     */
    private fun handoff() {
        val preview = previewPlayer ?: return
        if (!targetStillNext() || !preview.isPlaying) {
            cancelOverlap()
            return
        }
        val index = player.nextMediaItemIndex
        // Clear before seeking so our own callbacks cannot repeat the handoff.
        fadeJob?.cancel()
        fadeJob = null
        swapItem = nextItem
        previewPlayer = null
        nextItem = null
        swapPreview = preview
        preview.volume = 1f
        player.setPauseAtEndOfMediaItems(false)
        player.volume = 0f
        val requestedAt = SystemClock.elapsedRealtime()
        ownSeek = true
        val target = (preview.currentPosition + seekLeadMs).let { if (preview.duration > 0) it.coerceAtMost(preview.duration - 1) else it }
        player.seekTo(index, target)
        player.play()
        triggered = false
        swapJob = scope.launch {
            val audible = withTimeoutOrNull(SWAP_TIMEOUT_MS) {
                while (!player.isPlaying) delay(10)
                true
            } == true
            if (audible && swapPreview === preview) {
                val latency = SystemClock.elapsedRealtime() - requestedAt
                seekLeadMs = ((seekLeadMs * 3 + latency) / 4).coerceIn(MIN_SEEK_LEAD_MS, MAX_SEEK_LEAD_MS)
                for (step in 1..SWAP_STEPS) {
                    val g = step.toFloat() / SWAP_STEPS * (PI.toFloat() / 2f)
                    player.volume = sin(g)
                    preview.volume = cos(g)
                    delay(SWAP_STEP_MS)
                }
            }
            if (swapPreview === preview) finishSwap()
        }
    }

    /** Ends the handoff: the session player plays alone at full volume. */
    private fun finishSwap() {
        val preview = swapPreview ?: return
        swapPreview = null
        swapItem = null
        ownSeek = false
        val job = swapJob
        swapJob = null
        runCatching { preview.release() }
        player.volume = 1f
        job?.cancel()
    }

    private fun releasePreview() {
        fadeJob?.cancel()
        fadeJob = null
        val preview = previewPlayer
        previewPlayer = null
        nextItem = null
        runCatching { preview?.release() }
    }

    private fun cancelOverlap() {
        releasePreview()
        finishSwap()
        player.setPauseAtEndOfMediaItems(false)
        player.volume = 1f
    }

    private companion object {
        const val INITIAL_SEEK_LEAD_MS = 250L
        const val MIN_SEEK_LEAD_MS = 40L
        const val MAX_SEEK_LEAD_MS = 1_500L
        /** Give up waiting and play the session player alone after this long. */
        const val SWAP_TIMEOUT_MS = 3_000L
        const val SWAP_STEPS = 6
        const val SWAP_STEP_MS = 20L
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        if (started) player.removeListener(listener)
        started = false
        cancelOverlap()
    }
}
