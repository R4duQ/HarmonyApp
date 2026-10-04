package com.harmony.playback.service.player

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
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
import kotlin.math.abs

/**
 * Overlaps a temporary player with the session player, which still owns the queue.
 *
 * The next song is loaded early: a few seconds before the fade, the second
 * player is built and prepared, paused and muted, so its file is open, its
 * decoder running and its first audio waiting. When the fade is due it only
 * has to start, and the work of loading never lands on the part you hear.
 */
@UnstableApi
class CrossfadeController(
    context: Context,
    private val player: ExoPlayer,
    private val scope: CoroutineScope,
    /**
     * Builds the second player for [MediaItem]. The session passes one with
     * the same ReplayGain and EQ as the session player, so the handoff
     * changes neither loudness nor tone; this plain one is the fallback.
     */
    private val previewFactory: (MediaItem) -> ExoPlayer = {
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

    /** The second player has been told to play: the fade is on. Before that it is only loaded. */
    private var fading = false
    private var started = false

    // The handoff: the second player keeps sounding until the session player,
    // which has just jumped to the same song, is audible again.
    private var swapPreview: ExoPlayer? = null
    private var swapItem: MediaItem? = null
    private var swapJob: Job? = null
    private var ownSeek = false

    /** How long the session player takes to start at a new position; learned at each handoff. */
    private var seekLeadMs = INITIAL_SEEK_LEAD_MS

    /** The session player's own speed while it is being nudged into step; restored afterwards. */
    private var nudgedFrom: PlaybackParameters? = null

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
            // Includes buffering, audio-focus loss and becoming noisy. A song
            // that is only loaded is let go too, and loaded again on resume.
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
            // The queue changed under a loaded song: cancelOverlap lets the new next one load.
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
                val window = crossfadeSeconds * 1000L
                val remaining = player.duration - player.currentPosition
                val eligible = crossfadeSeconds > 0 && player.repeatMode != Player.REPEAT_MODE_ONE &&
                    player.isPlaying && !player.isCurrentMediaItemLive && player.duration > 0
                // 1. A few seconds ahead: load the next song, paused and muted.
                if (eligible && previewPlayer == null && !triggered && remaining in 1..(window + PRELOAD_MS)) {
                    val index = player.nextMediaItemIndex
                    if (index in 0 until player.mediaItemCount && index != player.currentMediaItemIndex) {
                        val item = player.getMediaItemAt(index)
                        if (item.localConfiguration != null) {
                            triggered = true
                            loadNext(item)
                        }
                    }
                }
                // 2. On time: start it and fade.
                val loaded = previewPlayer
                if (eligible && loaded != null && !fading && remaining in 1..window) startFade(loaded)
                // Look often near the fade, so it starts within a frame or two of when it should.
                delay(if (remaining <= window + PRELOAD_MS + LOOK_AHEAD_MS) NEAR_POLL_MS else FAR_POLL_MS)
            }
        }
    }

    private fun targetStillNext(): Boolean {
        val index = player.nextMediaItemIndex
        return index in 0 until player.mediaItemCount && player.getMediaItemAt(index) == nextItem
    }

    /** Builds the second player for [item] and prepares it, paused and muted, ready to start at once. */
    private fun loadNext(item: MediaItem) {
        try {
            val preview = previewFactory(item)
            previewPlayer = preview
            nextItem = item
            fading = false
            preview.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) { cancelOverlap() }
            })
            preview.volume = 0f
            preview.playbackParameters = player.playbackParameters
            preview.setMediaItem(item)
            preview.playWhenReady = false
            preview.prepare()
        } catch (_: Exception) {
            cancelOverlap()
        }
    }

    private fun startFade(preview: ExoPlayer) {
        if (!targetStillNext()) {
            cancelOverlap()
            return
        }
        fading = true
        try {
            preview.playbackParameters = player.playbackParameters
            preview.playWhenReady = true
            fadeJob = scope.launch {
                // Loaded ahead, it starts in a moment; if it is still loading, the
                // song playing goes on at full volume until it does.
                while (previewPlayer === preview && !preview.isPlaying) delay(10)
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
     * the next one. The session player has to take over that song without a
     * gap and without a jump:
     *
     * 1. It seeks, muted, to where the second player will be once it starts.
     * 2. Harmony waits until it is really sounding: its position moving, not
     *    just "playing", which it reports before the first sample is out.
     * 3. Its offset from the second player is measured. A seek never lands
     *    exactly, and swapping while they are 50-200 ms apart is the hitch
     *    you hear, so the muted player runs a little faster or slower until
     *    the two are within a few milliseconds.
     * 4. Only then does a short swap hand over, with gains that add up to one
 *    (see [HandoffAlignment.swapGains]).
     *
     * The second player keeps sounding at full volume the whole time, so none
     * of this is audible. Whatever happens, after [ALIGN_BUDGET_MS] the swap
     * goes ahead anyway.
     */
    private fun handoff() {
        val preview = previewPlayer ?: return
        if (!fading || !targetStillNext() || !preview.isPlaying) {
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
        fading = false
        swapPreview = preview
        preview.volume = 1f
        player.setPauseAtEndOfMediaItems(false)
        player.volume = 0f
        aimAt(index, preview)
        player.play()
        triggered = false
        swapJob = scope.launch {
            alignWith(index, preview)
            if (swapPreview !== preview) return@launch
            if (nudgedFrom != null) {
                // Out of time while nudged: the faster or slower audio is still
                // queued, and swapping onto it would be heard. Normal speed
                // first, and give it time to come through.
                restoreSpeed()
                delay(SETTLE_MS)
                if (swapPreview !== preview) return@launch
            }
            for (step in 1..SWAP_STEPS) {
                val (incoming, outgoing) = HandoffAlignment.swapGains(step.toFloat() / SWAP_STEPS)
                player.volume = incoming
                preview.volume = outgoing
                delay(SWAP_STEP_MS)
            }
            if (swapPreview === preview) finishSwap()
        }
    }

    /** Seeks the session player, muted, to where the second player will be once it starts sounding. */
    private fun aimAt(index: Int, preview: ExoPlayer) {
        ownSeek = true
        val target = (preview.currentPosition + seekLeadMs)
            .let { if (preview.duration > 0) it.coerceAtMost(preview.duration - 1) else it }
        player.seekTo(index, target)
    }

    /** Steps 2 and 3 of [handoff]. Returns when the two players are in step, or out of time. */
    private suspend fun alignWith(index: Int, preview: ExoPlayer) {
        val deadline = SystemClock.elapsedRealtime() + ALIGN_BUDGET_MS
        fun inTime() = SystemClock.elapsedRealtime() < deadline && swapPreview === preview && preview.isPlaying
        var learned = false
        var reseeks = 0
        while (inTime()) {
            if (!awaitSounding(::inTime)) return
            // The audio clock reads a little loose for the first moments after a start.
            delay(SETTLE_MS)
            if (!inTime()) return
            while (inTime()) {
                val offset = offsetFrom(preview)
                if (!learned) {
                    seekLeadMs = HandoffAlignment.nextLead(seekLeadMs, offset)
                    learned = true
                }
                if (abs(offset) > HandoffAlignment.RESEEK_OVER_MS && reseeks < MAX_RESEEKS) {
                    // Too far to close by speed in time: aim again, with the lead just learned.
                    reseeks++
                    restoreSpeed()
                    aimAt(index, preview)
                    break
                }
                val factor = HandoffAlignment.catchUpFactor(offset)
                if (factor == null) {
                    // In step at its own speed: ready to swap.
                    if (nudgedFrom == null) return
                    // In step while nudged. A speed change only reaches the
                    // speaker once the audio already queued has played, so
                    // go back to normal speed and check again after that.
                    restoreSpeed()
                    delay(SETTLE_MS)
                    continue
                }
                nudge(factor)
                delay(NUDGE_STEP_MS)
            }
        }
    }

    /**
     * Waits until the session player's position has clearly moved on from
     * where it started: then audio is really coming out. Between its internal
     * updates ExoPlayer extrapolates the position by a few ms even before the
     * first sample plays, so a tiny advance doesn't count.
     */
    private suspend fun awaitSounding(inTime: () -> Boolean): Boolean {
        var start = -1L
        while (inTime()) {
            if (!player.isPlaying) {
                start = -1L
            } else {
                val position = player.currentPosition
                if (start < 0) start = position
                else if (position - start >= SOUNDING_MS) return true
            }
            delay(10)
        }
        return false
    }

    /** How far the session player is ahead of the second one (negative: behind), in ms. */
    private suspend fun offsetFrom(preview: ExoPlayer): Long {
        fun read() = player.currentPosition - preview.currentPosition
        val a = read()
        delay(5)
        val b = read()
        delay(5)
        return HandoffAlignment.median(a, b, read())
    }

    /** Plays the muted session player at [factor] times its own speed. */
    private fun nudge(factor: Float) {
        val own = nudgedFrom ?: player.playbackParameters.also { nudgedFrom = it }
        // In 1 % steps, and only when it changes: each change is queued behind
        // the audio already buffered, so fewer of them settle faster.
        val speed = own.speed * (Math.round(factor * 100f) / 100f)
        if (player.playbackParameters.speed == speed) return
        // Pitch as it was; HarmonyPlayer re-applies tape mode if that is on. It's muted either way.
        player.playbackParameters = PlaybackParameters(speed, own.pitch)
    }

    private fun restoreSpeed() {
        val own = nudgedFrom ?: return
        nudgedFrom = null
        player.playbackParameters = own
    }

    /** Ends the handoff: the session player plays alone at full volume. */
    private fun finishSwap() {
        val preview = swapPreview ?: return
        swapPreview = null
        swapItem = null
        ownSeek = false
        restoreSpeed()
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
        // A song that was only loaded can be loaded again when it is due; one
        // whose fade was cut short isn't, and that song ends without a fade.
        if (preview != null && !fading) triggered = false
        previewPlayer = null
        nextItem = null
        fading = false
        runCatching { preview?.release() }
    }

    private fun cancelOverlap() {
        releasePreview()
        finishSwap()
        player.setPauseAtEndOfMediaItems(false)
        player.volume = 1f
    }

    private companion object {
        /** How long before the fade the next song is loaded. */
        const val PRELOAD_MS = 6_000L
        const val FAR_POLL_MS = 250L
        const val NEAR_POLL_MS = 20L
        /** Start polling often a little before loading is due. */
        const val LOOK_AHEAD_MS = 500L
        const val INITIAL_SEEK_LEAD_MS = 250L
        /** After this long the swap goes ahead whether or not the players are in step. */
        const val ALIGN_BUDGET_MS = 4_500L
        const val SETTLE_MS = 250L
        const val SOUNDING_MS = 40L
        const val NUDGE_STEP_MS = 40L
        const val MAX_RESEEKS = 1
        /**
         * The swap itself: 80 ms. Two copies a few ms apart blur together while
         * they overlap, so the shorter the overlap, the less of that is heard.
         */
        const val SWAP_STEPS = 8
        const val SWAP_STEP_MS = 10L
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        if (started) player.removeListener(listener)
        started = false
        cancelOverlap()
    }
}
