package com.harmony.playback.service.player

import android.content.Context
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
            cancelOverlap()
            triggered = false
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
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

    private fun handoff() {
        val preview = previewPlayer ?: return
        if (!targetStillNext() || !preview.isPlaying) {
            cancelOverlap()
            return
        }
        val index = player.nextMediaItemIndex
        val position = preview.currentPosition
        // Clear before seeking so our own callbacks cannot repeat the handoff.
        releasePreview()
        player.setPauseAtEndOfMediaItems(false)
        player.seekTo(index, position)
        player.volume = 1f
        player.play()
        triggered = false
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
        player.setPauseAtEndOfMediaItems(false)
        player.volume = 1f
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        if (started) player.removeListener(listener)
        started = false
        cancelOverlap()
    }
}
