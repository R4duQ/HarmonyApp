package com.harmony.playback.service.player

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.FlagSet
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.cos
import kotlin.random.Random

/**
 * Two ExoPlayers that take turns, seen from outside as one player.
 *
 * Both hold the same queue. One plays (the active one, which everything
 * reads and controls); the other waits. A few seconds before a crossfade the
 * waiting one loads the next song, muted and paused, so its file is open and
 * its first audio decoded. When the fade is due it starts, the two are faded
 * against each other, and when the outgoing song ends the waiting player
 * simply becomes the active one, already playing the next song at full
 * volume. Nothing is reloaded, sought or realigned at that moment, so there is
 * nothing to hear: the old player stops, and becomes the one that waits.
 *
 * The earlier design kept one session player and a temporary second one, and
 * at the end of the fade had to move the session player onto the song the
 * second one was already playing: a cold seek, a wait for it to sound, and a
 * swap. However carefully timed, that last step was where the music hitched.
 *
 * Everything not overridden here goes straight to the active player. Events
 * from the active player are passed on as this player's; the waiting one's
 * never are. When the turn changes, listeners are told the song moved on, as
 * if one player had played from one song into the next.
 *
 * Must be used on the players' application thread (the main thread).
 */
@UnstableApi
class TwinPlayer private constructor(
    private val twin: Twin,
    dispatcher: CoroutineDispatcher,
) : Player by twin.forward {

    constructor(
        first: ExoPlayer,
        second: ExoPlayer,
        dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    ) : this(Twin(first, second), dispatcher)

    /** The two players and which one plays; [forward] answers for whichever that is. */
    private class Twin(val first: ExoPlayer, val second: ExoPlayer) {
        var active: ExoPlayer = first

        val forward: Player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, args ->
            try {
                method.invoke(active, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as Player
    }

    private enum class Phase {
        /** Only the active player is in use. */
        IDLE,
        /** The other player holds the next song, loaded, muted and paused. */
        LOADED,
        /** Both are playing, fading into each other. */
        FADING,
    }

    /** The player that plays: what the session, the notification and the app all see. */
    val active: ExoPlayer get() = twin.active

    /** The player that waits for its turn, or plays the incoming song during a fade. */
    val standby: ExoPlayer get() = if (twin.active === twin.first) twin.second else twin.first

    val players: List<ExoPlayer> get() = listOf(twin.first, twin.second)

    /** Called when the turn changes, with the player that now plays. */
    var onActiveChanged: ((ExoPlayer) -> Unit)? = null

    /**
     * A crossfade is loaded, under way, or due within [aheadMs]: moving the
     * music to another device (Harmony Connect) waits for it to be over.
     */
    fun crossfadeNear(aheadMs: Long): Boolean {
        if (phase != Phase.IDLE) return true
        val window = crossfadeSeconds * 1000L
        if (window <= 0) return false
        val duration = active.duration
        return duration > 0 && duration - active.currentPosition <= window + PRELOAD_MS + aheadMs
    }

    var crossfadeSeconds: Int = 0
        set(value) {
            field = value.coerceIn(0, MAX_CROSSFADE_SECONDS)
            if (field == 0) cancel()
        }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val listeners = CopyOnWriteArrayList<Player.Listener>()
    private var phase = Phase.IDLE
    private var loadedItem: MediaItem? = null
    private var fadeJob: Job? = null

    /** A song the waiting player couldn't load; it isn't tried again until the song changes. */
    private var failedItem: MediaItem? = null

    /** The volume asked of this player. Fades are applied under it. */
    private var userVolume = 1f

    /** How audio focus was asked for; it follows the active player. */
    private var audioAttributes: AudioAttributes = active.audioAttributes
    private var handlesAudioFocus = true

    /**
     * One shuffle order for both. Each ExoPlayer would otherwise invent its
     * own, and after a turn the next player would shuffle differently.
     */
    private val random = Random.Default
    private var shuffleOrder: ShuffleOrder = ShuffleOrder.DefaultShuffleOrder(0, random.nextLong())

    /** Depth of queue changes in progress, while both players are not yet in step. */
    private var mutating = 0

    // What listeners last heard about playing, so a turn can tell them only what changed.
    private var heardState = active.playbackState
    private var heardPlayWhenReady = active.playWhenReady
    private var heardPlaying = active.isPlaying

    init {
        twin.first.addListener(Relay(twin.first))
        twin.second.addListener(Relay(twin.second))
        scope.launch { monitor() }
    }

    // ---- The crossfade --------------------------------------------------------------

    private suspend fun monitor() {
        while (scope.isActive) {
            val player = active
            val window = crossfadeSeconds * 1000L
            val duration = player.duration
            val remaining = if (duration > 0) duration - player.currentPosition else Long.MAX_VALUE
            if (window > 0 && eligible(player)) {
                // 1. A few seconds ahead: the other player loads the next song.
                if (phase == Phase.IDLE && remaining in 1..(window + PRELOAD_MS)) preload()
                // 2. On time: it starts, and the two fade.
                if (phase == Phase.LOADED && remaining in 1..window) startFade()
            }
            // Look often near the fade, so it starts within a frame or two of when it should.
            delay(if (window > 0 && remaining <= window + PRELOAD_MS + LOOK_AHEAD_MS) NEAR_POLL_MS else FAR_POLL_MS)
        }
    }

    private fun eligible(player: ExoPlayer) =
        player.repeatMode != Player.REPEAT_MODE_ONE && player.isPlaying &&
            !player.isCurrentMediaItemLive && player.duration > 0

    private fun preload() {
        val player = active
        val next = player.nextMediaItemIndex
        if (next == C.INDEX_UNSET || next == player.currentMediaItemIndex || next >= player.mediaItemCount) return
        val item = player.getMediaItemAt(next)
        if (item == failedItem) return
        val waiting = standby
        // Both always hold the same queue; if not, this is no time to find out why.
        if (waiting.mediaItemCount != player.mediaItemCount || waiting.getMediaItemAt(next) != item) return
        phase = Phase.LOADED
        loadedItem = item
        waiting.volume = 0f
        waiting.playWhenReady = false
        if (waiting.repeatMode != player.repeatMode) waiting.repeatMode = player.repeatMode
        waiting.playbackParameters = player.playbackParameters
        waiting.seekTo(next, 0)
        waiting.prepare()
    }

    private fun startFade() {
        if (!targetStillNext()) {
            cancel()
            return
        }
        phase = Phase.FADING
        val outgoing = active
        val incoming = standby
        // At the end of its song the outgoing player stops there rather than
        // going on into the next one itself: that is when the turn changes.
        outgoing.setPauseAtEndOfMediaItems(true)
        incoming.playbackParameters = outgoing.playbackParameters
        incoming.playWhenReady = true
        fadeJob = scope.launch {
            // Loaded ahead, it starts in a moment; if it is still loading, the
            // song playing goes on at full volume until it does.
            while (phase == Phase.FADING && !incoming.isPlaying) delay(WAIT_STEP_MS)
            if (phase != Phase.FADING) return@launch
            val span = remainingOf(outgoing).coerceAtLeast(1)
            while (phase == Phase.FADING) {
                // Follows actual playback, so speeds above or below 1x fade in step.
                val progress = (1f - remainingOf(outgoing).toFloat() / span).coerceIn(0f, 1f)
                val gain = (1f - cos(progress * PI.toFloat())) / 2f
                outgoing.volume = (1f - gain) * userVolume
                incoming.volume = gain * userVolume
                if (incoming.playbackParameters != outgoing.playbackParameters) {
                    incoming.playbackParameters = outgoing.playbackParameters
                }
                delay(FADE_STEP_MS)
            }
        }
    }

    private fun remainingOf(player: ExoPlayer): Long =
        (player.duration - player.currentPosition).coerceAtLeast(0)

    /** Whether the song loaded in the other player is still the one that comes next. */
    private fun targetStillNext(): Boolean {
        val player = active
        val next = player.nextMediaItemIndex
        return next != C.INDEX_UNSET && next < player.mediaItemCount &&
            next == standby.currentMediaItemIndex && player.getMediaItemAt(next) == loadedItem
    }

    /** The outgoing song has ended: the incoming player, already playing, takes over. */
    private fun takeTurn() {
        if (phase != Phase.FADING) return
        val outgoing = active
        val incoming = standby
        fadeJob?.cancel()
        fadeJob = null
        phase = Phase.IDLE
        loadedItem = null
        failedItem = null
        val oldPosition = positionInfo(outgoing)
        incoming.volume = userVolume
        twin.active = incoming
        // From here on the outgoing player's own events go nowhere.
        outgoing.volume = 0f
        outgoing.setPauseAtEndOfMediaItems(false)
        // Audio focus belongs to whichever plays: let go of it first, so asking
        // for it again from the other player can't make the first one lose it.
        if (handlesAudioFocus) {
            outgoing.setAudioAttributes(audioAttributes, false)
            incoming.setAudioAttributes(audioAttributes, true)
        }
        outgoing.playWhenReady = false
        outgoing.stop()
        onActiveChanged?.invoke(incoming)
        announceTurn(oldPosition)
    }

    /** Back to one player: the other stops and the active one plays at full volume. */
    private fun cancel() {
        if (phase == Phase.IDLE) return
        fadeJob?.cancel()
        fadeJob = null
        phase = Phase.IDLE
        loadedItem = null
        val waiting = standby
        waiting.volume = 0f
        waiting.playWhenReady = false
        waiting.stop()
        active.setPauseAtEndOfMediaItems(false)
        active.volume = userVolume
    }

    /** Tells listeners the song moved on, as one player going into the next song would. */
    private fun announceTurn(oldPosition: Player.PositionInfo) {
        val player = active
        val newPosition = positionInfo(player)
        val timeline = player.currentTimeline
        val item = player.currentMediaItem
        val tracks = player.currentTracks
        val metadata = player.mediaMetadata
        val commands = player.availableCommands
        val state = player.playbackState
        val playWhenReady = player.playWhenReady
        val playing = player.isPlaying
        val flags = FlagSet.Builder().addAll(
            Player.EVENT_TIMELINE_CHANGED,
            Player.EVENT_MEDIA_ITEM_TRANSITION,
            Player.EVENT_POSITION_DISCONTINUITY,
            Player.EVENT_TRACKS_CHANGED,
            Player.EVENT_MEDIA_METADATA_CHANGED,
            Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
        )
        val stateChanged = state != heardState
        val playWhenReadyChanged = playWhenReady != heardPlayWhenReady
        val playingChanged = playing != heardPlaying
        if (stateChanged) flags.add(Player.EVENT_PLAYBACK_STATE_CHANGED)
        if (playWhenReadyChanged) flags.add(Player.EVENT_PLAY_WHEN_READY_CHANGED)
        if (playingChanged) flags.add(Player.EVENT_IS_PLAYING_CHANGED)
        heardState = state
        heardPlayWhenReady = playWhenReady
        heardPlaying = playing
        val events = Player.Events(flags.build())
        for (listener in listeners) {
            listener.onTimelineChanged(timeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
            listener.onPositionDiscontinuity(oldPosition, newPosition, Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
            @Suppress("DEPRECATION")
            listener.onPositionDiscontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
            listener.onMediaItemTransition(item, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            listener.onTracksChanged(tracks)
            listener.onMediaMetadataChanged(metadata)
            listener.onAvailableCommandsChanged(commands)
            if (stateChanged) listener.onPlaybackStateChanged(state)
            if (playWhenReadyChanged) {
                listener.onPlayWhenReadyChanged(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            }
            if (playingChanged) listener.onIsPlayingChanged(playing)
            listener.onEvents(this, events)
        }
    }

    private fun positionInfo(player: ExoPlayer): Player.PositionInfo {
        val timeline = player.currentTimeline
        val index = player.currentMediaItemIndex
        val periodIndex = player.currentPeriodIndex
        var windowUid: Any? = null
        var periodUid: Any? = null
        if (!timeline.isEmpty && index < timeline.windowCount && periodIndex < timeline.periodCount) {
            windowUid = timeline.getWindow(index, Timeline.Window()).uid
            periodUid = timeline.getPeriod(periodIndex, Timeline.Period(), true).uid
        }
        val position = player.currentPosition
        return Player.PositionInfo(
            windowUid, index, player.currentMediaItem, periodUid, periodIndex,
            position, player.contentPosition, C.INDEX_UNSET, C.INDEX_UNSET,
        )
    }

    // ---- The queue, kept the same in both ---------------------------------------------

    /**
     * Applies a queue change to both players, the waiting one first, so that
     * when the active one reports it the two already agree. Then, if a song
     * was loaded for a fade and it is no longer the next one, the fade is off.
     */
    private fun mirror(
        order: (ShuffleOrder) -> ShuffleOrder,
        replacesQueue: Boolean = false,
        change: (ExoPlayer) -> Unit,
    ) {
        // A new queue: whatever was loaded is moot, and the waiting player shouldn't start on it.
        if (replacesQueue) cancel()
        mutating++
        try {
            change(standby)
            change(active)
            val count = active.mediaItemCount
            val before = shuffleOrder
            shuffleOrder = order(before).takeIf { it.length == count }
                ?: ShuffleOrder.DefaultShuffleOrder(count, random.nextLong())
            // Each player just changed its own order its own way: give them ours again.
            if (shuffleOrder !== before && active.shuffleModeEnabled) shareShuffleOrder()
        } finally {
            mutating--
        }
        recheckTarget()
    }

    private fun shareShuffleOrder() {
        if (shuffleOrder.length != active.mediaItemCount) {
            shuffleOrder = ShuffleOrder.DefaultShuffleOrder(active.mediaItemCount, random.nextLong())
        }
        for (player in listOf(standby, active)) {
            if (player.mediaItemCount == shuffleOrder.length) player.setShuffleOrder(shuffleOrder)
        }
    }

    private fun recheckTarget() {
        if (mutating == 0 && phase != Phase.IDLE && !targetStillNext()) cancel()
    }

    private fun fresh(count: Int): (ShuffleOrder) -> ShuffleOrder =
        { ShuffleOrder.DefaultShuffleOrder(count, random.nextLong()) }

    private fun inserted(index: Int, count: Int): (ShuffleOrder) -> ShuffleOrder =
        { it.cloneAndInsert(index.coerceIn(0, it.length), count) }

    private fun removed(from: Int, to: Int): (ShuffleOrder) -> ShuffleOrder = {
        val end = to.coerceAtMost(it.length)
        if (from in 0 until end) it.cloneAndRemove(from, end) else it
    }

    /** As ExoPlayer replaces: the new items go in after the old ones, which then go. */
    private fun replaced(from: Int, to: Int, count: Int): (ShuffleOrder) -> ShuffleOrder = { order ->
        if (from > order.length) {
            order
        } else {
            val end = to.coerceAtMost(order.length)
            removed(from, end)(inserted(end, count)(order))
        }
    }

    private val unchanged: (ShuffleOrder) -> ShuffleOrder = { it }

    override fun setMediaItems(mediaItems: List<MediaItem>) =
        mirror(fresh(mediaItems.size), replacesQueue = true) { it.setMediaItems(mediaItems) }

    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) =
        mirror(fresh(mediaItems.size), replacesQueue = true) { it.setMediaItems(mediaItems, resetPosition) }

    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) =
        mirror(fresh(mediaItems.size), replacesQueue = true) { it.setMediaItems(mediaItems, startIndex, startPositionMs) }

    override fun setMediaItem(mediaItem: MediaItem) =
        mirror(fresh(1), replacesQueue = true) { it.setMediaItem(mediaItem) }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) =
        mirror(fresh(1), replacesQueue = true) { it.setMediaItem(mediaItem, startPositionMs) }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) =
        mirror(fresh(1), replacesQueue = true) { it.setMediaItem(mediaItem, resetPosition) }

    override fun addMediaItem(mediaItem: MediaItem) =
        mirror(inserted(Int.MAX_VALUE, 1)) { it.addMediaItem(mediaItem) }

    override fun addMediaItem(index: Int, mediaItem: MediaItem) =
        mirror(inserted(index, 1)) { it.addMediaItem(index, mediaItem) }

    override fun addMediaItems(mediaItems: List<MediaItem>) =
        mirror(inserted(Int.MAX_VALUE, mediaItems.size)) { it.addMediaItems(mediaItems) }

    override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) =
        mirror(inserted(index, mediaItems.size)) { it.addMediaItems(index, mediaItems) }

    override fun moveMediaItem(currentIndex: Int, newIndex: Int) =
        mirror(unchanged) { it.moveMediaItem(currentIndex, newIndex) }

    override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) =
        mirror(unchanged) { it.moveMediaItems(fromIndex, toIndex, newIndex) }

    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) =
        mirror(replaced(index, index + 1, 1)) { it.replaceMediaItem(index, mediaItem) }

    override fun replaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>) =
        mirror(replaced(fromIndex, toIndex, mediaItems.size)) { it.replaceMediaItems(fromIndex, toIndex, mediaItems) }

    override fun removeMediaItem(index: Int) = mirror(removed(index, index + 1)) { it.removeMediaItem(index) }

    override fun removeMediaItems(fromIndex: Int, toIndex: Int) =
        mirror(removed(fromIndex, toIndex)) { it.removeMediaItems(fromIndex, toIndex) }

    override fun clearMediaItems() = mirror({ it.cloneAndClear() }, replacesQueue = true) { it.clearMediaItems() }

    override fun setRepeatMode(repeatMode: Int) = mirror(unchanged) { it.repeatMode = repeatMode }

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        mutating++
        try {
            // The shared order first, so that turning shuffle on uses it in both.
            if (shuffleModeEnabled) shareShuffleOrder()
            standby.shuffleModeEnabled = shuffleModeEnabled
            active.shuffleModeEnabled = shuffleModeEnabled
        } finally {
            mutating--
        }
        recheckTarget()
    }

    // ---- Settings both players share --------------------------------------------------

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        standby.playbackParameters = playbackParameters
        active.playbackParameters = playbackParameters
    }

    override fun setPlaybackSpeed(speed: Float) {
        standby.setPlaybackSpeed(speed)
        active.setPlaybackSpeed(speed)
    }

    override fun setPlaylistMetadata(mediaMetadata: MediaMetadata) {
        standby.playlistMetadata = mediaMetadata
        active.playlistMetadata = mediaMetadata
    }

    override fun setTrackSelectionParameters(parameters: TrackSelectionParameters) {
        standby.trackSelectionParameters = parameters
        active.trackSelectionParameters = parameters
    }

    override fun setAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean) {
        this.audioAttributes = audioAttributes
        handlesAudioFocus = handleAudioFocus
        standby.setAudioAttributes(audioAttributes, false)
        active.setAudioAttributes(audioAttributes, handleAudioFocus)
    }

    override fun getVolume(): Float = userVolume

    override fun setVolume(volume: Float) {
        val value = volume.coerceIn(0f, 1f)
        if (value == userVolume) return
        userVolume = value
        // During a fade the next step applies it, under the fade.
        if (phase != Phase.FADING) active.volume = value
        val events = Player.Events(FlagSet.Builder().add(Player.EVENT_VOLUME_CHANGED).build())
        for (listener in listeners) {
            listener.onVolumeChanged(value)
            listener.onEvents(this, events)
        }
    }

    // ---- Playing, stopping -----------------------------------------------------------

    override fun pause() {
        cancel()
        active.pause()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (!playWhenReady) cancel()
        active.playWhenReady = playWhenReady
    }

    override fun stop() {
        cancel()
        active.stop()
    }

    override fun release() {
        scope.cancel()
        fadeJob = null
        phase = Phase.IDLE
        listeners.clear()
        twin.first.release()
        twin.second.release()
    }

    override fun addListener(listener: Player.Listener) {
        if (listener !in listeners) listeners += listener
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
    }

    // ---- Events ------------------------------------------------------------------------

    /**
     * Hears one of the two players. What the active one reports first decides
     * the crossfade (its song ending hands the turn over; a seek, a pause or
     * another song calls the fade off), then goes on to listeners as this
     * player's own. What the waiting one reports goes nowhere.
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private inner class Relay(private val source: ExoPlayer) : Player.Listener {
        private val isActive: Boolean get() = source === twin.active

        private inline fun forward(event: (Player.Listener) -> Unit) {
            if (source === twin.active) for (listener in listeners) event(listener)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (isActive && playbackState == Player.STATE_ENDED) {
                if (phase == Phase.FADING) takeTurn() else cancel()
            }
            if (isActive) heardState = playbackState
            forward { it.onPlaybackStateChanged(playbackState) }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (isActive && !playWhenReady) {
                if (phase == Phase.FADING && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                    takeTurn()
                } else {
                    cancel()
                }
            }
            if (isActive) heardPlayWhenReady = playWhenReady
            forward { it.onPlayWhenReadyChanged(playWhenReady, reason) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Includes buffering, a call taking audio focus and headphones coming out.
            if (isActive && !isPlaying) cancel()
            if (isActive) heardPlaying = isPlaying
            forward { it.onIsPlayingChanged(isPlaying) }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (isActive) {
                failedItem = null
                // Went on into the next song by itself (too late to stop it there): that's the turn too.
                if (phase == Phase.FADING && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                    mediaItem == loadedItem
                ) {
                    takeTurn()
                } else {
                    cancel()
                }
            }
            forward { it.onMediaItemTransition(mediaItem, reason) }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (isActive && (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT)) {
                cancel()
            }
            forward { it.onPositionDiscontinuity(oldPosition, newPosition, reason) }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (isActive) recheckTarget()
            forward { it.onTimelineChanged(timeline, reason) }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (isActive) {
                cancel()
            } else if (phase != Phase.IDLE) {
                // The next song wouldn't load: it ends without a fade, and isn't tried again.
                failedItem = loadedItem
                cancel()
            }
            forward { it.onPlayerError(error) }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (!isActive) return
            // Volume is this player's own, set from outside; the players' volumes are the fade.
            if (events.size() == 1 && events.contains(Player.EVENT_VOLUME_CHANGED)) return
            for (listener in listeners) listener.onEvents(this@TwinPlayer, events)
        }

        override fun onVolumeChanged(volume: Float) = Unit

        override fun onPlayerErrorChanged(error: PlaybackException?) = forward { it.onPlayerErrorChanged(error) }
        override fun onIsLoadingChanged(isLoading: Boolean) = forward { it.onIsLoadingChanged(isLoading) }
        override fun onLoadingChanged(isLoading: Boolean) = forward { it.onLoadingChanged(isLoading) }
        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) =
            forward { it.onPlayerStateChanged(playWhenReady, playbackState) }
        override fun onPlaybackSuppressionReasonChanged(reason: Int) = forward { it.onPlaybackSuppressionReasonChanged(reason) }
        override fun onTracksChanged(tracks: Tracks) = forward { it.onTracksChanged(tracks) }
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = forward { it.onMediaMetadataChanged(mediaMetadata) }
        override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) = forward { it.onPlaylistMetadataChanged(mediaMetadata) }
        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) =
            forward { it.onAvailableCommandsChanged(availableCommands) }
        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) =
            forward { it.onTrackSelectionParametersChanged(parameters) }
        override fun onRepeatModeChanged(repeatMode: Int) = forward { it.onRepeatModeChanged(repeatMode) }
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) =
            forward { it.onShuffleModeEnabledChanged(shuffleModeEnabled) }
        override fun onPositionDiscontinuity(reason: Int) = forward { it.onPositionDiscontinuity(reason) }
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) =
            forward { it.onPlaybackParametersChanged(playbackParameters) }
        override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) = forward { it.onSeekBackIncrementChanged(seekBackIncrementMs) }
        override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) =
            forward { it.onSeekForwardIncrementChanged(seekForwardIncrementMs) }
        override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) =
            forward { it.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs) }
        override fun onAudioSessionIdChanged(audioSessionId: Int) = forward { it.onAudioSessionIdChanged(audioSessionId) }
        override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) = forward { it.onAudioAttributesChanged(audioAttributes) }
        override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = forward { it.onSkipSilenceEnabledChanged(skipSilenceEnabled) }
        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = forward { it.onDeviceInfoChanged(deviceInfo) }
        override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = forward { it.onDeviceVolumeChanged(volume, muted) }
        override fun onVideoSizeChanged(videoSize: VideoSize) = forward { it.onVideoSizeChanged(videoSize) }
        override fun onSurfaceSizeChanged(width: Int, height: Int) = forward { it.onSurfaceSizeChanged(width, height) }
        override fun onRenderedFirstFrame() = forward { it.onRenderedFirstFrame() }
        override fun onCues(cues: List<Cue>) = forward { it.onCues(cues) }
        override fun onCues(cueGroup: CueGroup) = forward { it.onCues(cueGroup) }
        override fun onMetadata(metadata: Metadata) = forward { it.onMetadata(metadata) }
    }

    private companion object {
        const val MAX_CROSSFADE_SECONDS = 12
        /** How long before the fade the next song is loaded. */
        const val PRELOAD_MS = 6_000L
        const val FAR_POLL_MS = 250L
        const val NEAR_POLL_MS = 20L
        /** Start polling often a little before loading is due. */
        const val LOOK_AHEAD_MS = 500L
        const val WAIT_STEP_MS = 10L
        const val FADE_STEP_MS = 50L
    }
}
