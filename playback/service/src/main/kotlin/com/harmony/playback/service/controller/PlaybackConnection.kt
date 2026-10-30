package com.harmony.playback.service.controller

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.harmony.core.model.PlayerState
import com.harmony.core.model.RepeatMode
import com.harmony.core.model.ReplayGainMode
import com.harmony.core.model.ShuffleMode
import com.harmony.core.model.Song
import com.harmony.domain.playback.PlaybackController
import com.harmony.domain.playback.QueueRestoration
import com.harmony.domain.library.repository.ListeningClock
import com.harmony.playback.service.PlaybackService
import com.harmony.playback.service.session.HarmonyMediaLibraryCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app-process implementation of [PlaybackController], backed by a
 * [MediaController] connected to [PlaybackService]'s session.
 *
 * Why go through MediaController instead of injecting the ExoPlayer directly:
 * the session is the single source of truth that also serves Auto, Bluetooth,
 * and the notification. Driving playback through the same session pipe means
 * in-app UI and external controllers can never disagree about state, and the
 * service can safely outlive (or predate) any activity.
 *
 * State strategy: Media3's Player callbacks don't cover position ticks, so we
 * combine listener events with a lightweight 500ms position poll that runs
 * only while playing. The UI observes exactly one StateFlow<PlayerState>.
 *
 * Song mapping: MediaItems flowing back from the controller carry metadata but
 * not our full domain model, so this class keeps an id->Song cache populated
 * by [setQueue]/[addToQueue]. Phase 4 replaces the cache with a repository
 * lookup so state survives process death cleanly.
 */
@OptIn(UnstableApi::class)
@Singleton
class PlaybackConnection @Inject constructor(
    private val context: Context,
    private val libraryRepository: com.harmony.domain.library.repository.LibraryRepository,
    private val historyRepository: com.harmony.domain.library.repository.PlaybackHistoryRepository,
    private val settingsRepository: com.harmony.core.datastore.SettingsRepository,
    private val audioOutputMonitor: com.harmony.playback.service.player.AudioOutputMonitor,
    private val connect: com.harmony.playback.service.connect.ConnectSession,
) : PlaybackController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _playerState = MutableStateFlow(PlayerState())
    override val playerState: StateFlow<PlayerState> = _playerState

    private val _playOutcomes = kotlinx.coroutines.flow.MutableSharedFlow<com.harmony.domain.playback.PlayOutcome>(
        extraBufferCapacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    override val playOutcomes: kotlinx.coroutines.flow.Flow<com.harmony.domain.playback.PlayOutcome> = _playOutcomes

    /** Queue index of [trackingSongId], to tell a step back from a skip. */
    private var trackingIndex = -1

    private var controller: MediaController? = null
    private var connectionJob: kotlinx.coroutines.Job? = null
    private var tickerJob: kotlinx.coroutines.Job? = null
    private val persistentCommands = mutableMapOf<String, Bundle>()

    /**
     * Fast-path cache in front of [libraryRepository]. Since Phase 4 the
     * repository is the source of truth: ids present in the session but not in
     * the cache (e.g. a queue restored by Media3 playback resumption after
     * process death) are resolved from Room asynchronously and the state
     * re-synced — so the queue survives process death without serializing
     * Song objects anywhere.
     */
    private val songCache = mutableMapOf<String, Song>()

    /**
     * Which track the current "play next" run is anchored to, and how many
     * songs are in that run. Together these let consecutive play-next
     * insertions stack in order (see [addNext]) instead of each one jumping
     * ahead of the last.
     */
    /** See PlayerState.advancedAutomatically. */
    private var lastAdvanceWasAutomatic = false

    /** See PlayerState.queueGeneration: bumped by [setQueue] only. */
    private var queueGeneration = 0

    private val _externalShuffleRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    override val externalShuffleRequests: kotlinx.coroutines.flow.Flow<Unit> = _externalShuffleRequests

    private var playNextAnchorId: String? = null
    private var playNextRunLength = 0
    private val pendingLookups = mutableSetOf<String>()

    // -- Play-count tracking -------------------------------------------------
    private var trackingSongId: String? = null
    private var trackingListenedMs: Long = 0
    private val listeningClock = ListeningClock()

    private var localShuffleMode: ShuffleMode = ShuffleMode.OFF
    private var localCrossfadeSeconds: Int = 0
    private var localReplayGain: ReplayGainMode = ReplayGainMode.OFF
    private var localPitchCorrection: Boolean = true

    /**
     * Harmony's own screen has asked for the player in this process
     * (MainActivity.onStart). Restoring the last queue is for someone
     * opening the app: done without one (WorkManager's library analysis, a
     * service restart), it left a paused session holding a queue, which
     * Media3 shows as a playback notification nobody asked for.
     */
    @Volatile private var uiPresent = false

    /**
     * False once the app has been swiped away: from then on nothing in this
     * process reconnects to the service on its own (a queue update, a
     * settings command), only the app being opened again does. A reconnect
     * rebuilt the session, restored the queue and brought the notification
     * straight back.
     */
    @Volatile private var reconnectAllowed = true

    /** The last queue was restored on the current connection (at most once per connection). */
    private var restoredThisConnection = false

    init {
        // Connect early so the player's state is known in the background
        // (the analysis waits for playback to stop; Android Auto may be the
        // only client) — but without restoring a queue, see [uiPresent].
        connect()
        // Output route changes (headphones plugged in, Bluetooth connected)
        // arrive independently of any player event, so they need their own
        // trigger to reach the UI.
        scope.launch {
            audioOutputMonitor.output.collect {
                syncState()
                // A car that attaches finds the last queue, as it did before.
                controller?.takeIf { audioOutputMonitor.carConnected }?.let { maybeRestore(it) }
            }
        }
        // Likewise moving the music to a computer and back (Harmony Connect).
        scope.launch {
            connect.state
                .map { it.active }
                .distinctUntilChanged()
                .collect { syncState() }
        }
    }

    /**
     * The app's screen wants the player (MainActivity.onStart): connect if
     * needed, restore the last queue (paused) if the player is empty, and
     * allow reconnecting again after a swipe-away.
     */
    fun ensureConnected() {
        uiPresent = true
        reconnectAllowed = true
        val c = controller
        if (c != null && c.isConnected) {
            scope.launch { maybeRestore(c) }
            return
        }
        connect()
    }

    private fun connect() {
        if (controller?.isConnected == true || connectionJob?.isActive == true) return
        connectionJob = scope.launch {
            kotlinx.coroutines.yield()
            var built: MediaController? = null
            var pending: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
            try {
                val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
                val connected = kotlinx.coroutines.withTimeout(10_000) {
                    MediaController.Builder(context, token)
                        .setListener(object : MediaController.Listener {
                            override fun onDisconnected(disconnected: MediaController) {
                                if (controller === disconnected) {
                                    onTrackTransition(null)
                                    controller = null
                                    tickerJob?.cancel()
                                    _playerState.value = _playerState.value.copy(isPlaying = false, isBuffering = false)
                                }
                            }
                        }).buildAsync().also { pending = it }.await()
                }
                built = connected
                check(connected.isConnected)
                controller = connected
                connected.addListener(playerListener)
                onTrackTransition(connected.currentMediaItem?.mediaId)
                tickerJob?.cancel()
                tickerJob = startPositionTicker()
                restoredThisConnection = false
                maybeRestore(connected)
                // Never ExoPlayer's shuffle: Harmony orders the queue itself (see setShuffleMode).
                connected.shuffleModeEnabled = false
                persistentCommands.forEach { (action, args) ->
                    connected.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
                }
                syncState()
            } catch (error: Exception) {
                // A connection given up on (cancelled by a swipe-away, or timed
                // out) must not stay bound: releaseFuture releases the
                // controller if it connected, or cancels the attempt if not.
                // Otherwise the binding outlives it and keeps the service up.
                if (controller === built) controller = null
                pending?.let(MediaController::releaseFuture)
                if (error is kotlinx.coroutines.CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                tickerJob?.cancel()
                built?.release()
                android.util.Log.w("HarmonyPlayback", "Could not connect to playback service", error)
                if (uiPresent) {
                    android.widget.Toast.makeText(context, "Playback could not connect. Tap Play to retry.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Drops this process's binding to the session. Called by PlaybackService
     * when the app is swiped away with no car attached: a service can't be
     * destroyed while clients are bound, and this controller is otherwise
     * bound for the life of the process, so without this the service was
     * left stopped-but-alive instead of ending. [ensureConnected] rebuilds
     * the connection the next time the Activity starts.
     */
    fun releaseConnection() {
        // First, so nothing still running in this process (a connection being
        // built, a queue update, a settings command) can bind the service
        // again a moment after it was told to stop.
        reconnectAllowed = false
        uiPresent = false
        connectionJob?.cancel()
        connectionJob = null
        val c = controller ?: return
        controller = null
        onTrackTransition(null)
        tickerJob?.cancel()
        _playerState.value = _playerState.value.copy(isPlaying = false, isBuffering = false)
        c.release()
    }

    /** Restores the last queue into an empty player, when someone is there for it (see [uiPresent]). */
    private suspend fun maybeRestore(c: MediaController) {
        if (restoredThisConnection || c.mediaItemCount != 0) return
        if (!uiPresent && !audioOutputMonitor.carConnected) return
        restoredThisConnection = true
        restoreLastPlaybackState(c)
    }

    private suspend fun restoreLastPlaybackState(c: MediaController) {
        val last = settingsRepository.getLastPlaybackState() ?: return
        val restored = QueueRestoration.restore(last.songIds, last.index, last.positionMs,
            libraryRepository.songsByIds(last.songIds)) ?: return
        val songs = restored.songs
        // Re-check emptiness: resolving songs above hits the DB and suspends,
        // and the user can tap a song during that window. Without this, a
        // slow restore could stomp on a queue they just started — which
        // would look exactly like "it keeps playing the old song."
        if (c.mediaItemCount != 0) return
        songs.forEach { songCache[it.id.toString()] = it }
        c.setMediaItems(songs.map(::toMediaItem), restored.index, restored.positionMs)
        c.playWhenReady = false
        c.prepare()
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(
            mediaItem: androidx.media3.common.MediaItem?,
            reason: Int,
        ) {
            // Captured here rather than in onEvents because only this
            // callback carries the reason, and AUTO vs. everything else is
            // exactly the distinction Smart Shuffle's handover needs.
            lastAdvanceWasAutomatic =
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                onTrackTransition(player.currentMediaItem?.mediaId)
            }
            tickListenTime()
            syncState()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            // Someone outside the app (a Bluetooth or notification shuffle
            // button, a watch) switched on the player's own shuffle. It would
            // play a hidden order behind the queue Harmony shows, so it goes
            // straight back off and the request is handed to Smart Shuffle.
            if (!shuffleModeEnabled) return
            controller?.shuffleModeEnabled = false
            _externalShuffleRequests.tryEmit(Unit)
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            android.util.Log.e("HarmonyDiag", "onPlayerError code=${error.errorCode} ${error.errorCodeName}", error)
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            oldPosition.mediaItem?.mediaId?.let { id ->
                val range = listeningClock.sample(id, oldPosition.positionMs, SystemClock.elapsedRealtime(), false,
                    controller?.playbackParameters?.speed ?: 1f)
                if (id == trackingSongId && range != null) trackingListenedMs += range.endMs - range.startMs
            }
            listeningClock.reset()
        }
    }

    /**
     * Position ticker gated on BOTH playback and UI presence
     * (subscriptionCount > 0). With the screen off and no collectors, the
     * app-process ticker slows to five seconds, maintaining play history
     * without repeatedly recomposing an invisible UI.
     */
    private fun startPositionTicker() = scope.launch {
        while (isActive) {
            val hasSubscribers = _playerState.subscriptionCount.value > 0
            if (_playerState.value.isPlaying) {
                tickListenTime()
                if (hasSubscribers) syncState()
            }
            delay(if (hasSubscribers) POSITION_TICK_MS else IDLE_TICK_MS)
        }
    }

    /**
     * Play counting: accumulate actually-listened time (position deltas while
     * playing, so seeking doesn't inflate it). On transition, record the play;
     * completed = listened >= 50% of duration or >= 4 minutes, matching
     * long-standing scrobble conventions so Most Played reflects listening,
     * not skipping.
     */
    private fun tickListenTime() {
        val c = controller ?: return
        val id = c.currentMediaItem?.mediaId ?: return
        val range = listeningClock.sample(id, c.currentPosition, SystemClock.elapsedRealtime(), c.isPlaying, c.playbackParameters.speed)
        if (id == trackingSongId && range != null) trackingListenedMs += range.endMs - range.startMs
    }

    private fun onTrackTransition(newMediaId: String?) {
        val previousId = trackingSongId
        val listened = trackingListenedMs
        val duration = _playerState.value.durationMs
        if (previousId != null && listened > MIN_TRACKED_MS) {
            val completed = duration > 0 &&
                (listened >= duration / 2 || listened >= COMPLETED_ABSOLUTE_MS)
            scope.launch {
                previousId.toLongOrNull()?.let { historyRepository.recordPlay(it, completed) }
            }
        }
        // Smart Shuffle hears every move to another song, a two-second skip
        // included (that is the fastest of fast skips). A dropped connection
        // (newMediaId null) is not a verdict on the song, so it is not reported.
        val newIndex = controller?.currentMediaItemIndex ?: -1
        val songId = previousId?.toLongOrNull()
        if (songId != null && newMediaId != null && newMediaId != previousId) {
            _playOutcomes.tryEmit(
                com.harmony.domain.playback.PlayOutcome(
                    songId = songId,
                    listenedMs = listened,
                    durationMs = duration,
                    movedBackward = trackingIndex >= 0 && newIndex in 0 until trackingIndex,
                    endedNaturally = lastAdvanceWasAutomatic,
                ),
            )
        }
        val previousIndex = trackingIndex
        trackingIndex = newIndex
        trackingSongId = newMediaId
        trackingListenedMs = 0
        listeningClock.reset()
        carryPlayNextRun(newMediaId, previousIndex)
    }

    /**
     * Moves the play-next run's anchor onto the track that just started, if
     * that track was itself the head of the run.
     *
     * Without this the run evaporates the moment the first queued song
     * begins: the anchor still names the song that was playing when you
     * queued, so [playerState]'s playNextCount validates to 0. That number
     * is what SmartQueueManager's handover uses to decide how much of
     * the tail to keep, so under Smart Shuffle it would keep nothing, delete
     * the rest of the run, and carry on with its own picks — i.e. queue four
     * songs by hand, hear the first, and shuffle takes back over. Re-anchoring
     * keeps the remaining run addressable for exactly as long as it lasts.
     */
    private fun carryPlayNextRun(newMediaId: String?, previousIndex: Int) {
        if (playNextRunLength <= 0 || newMediaId == null) return
        val c = controller ?: return
        // Only advance when playback moved FORWARD into the run's first
        // entry. Any other transition — a skip back, or the user picking
        // something else out of the queue — means the run no longer
        // describes what's coming, so it ends here rather than being
        // re-pointed at an unrelated track.
        // The entry that was playing, by its index: the same song can also sit
        // earlier in the queue, and that occurrence is not the anchor.
        val anchorIndex = previousIndex
            .takeIf { it in 0 until c.mediaItemCount && c.getMediaItemAt(it).mediaId == playNextAnchorId }
            ?: (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == playNextAnchorId }
        if (anchorIndex == null || c.currentMediaItemIndex != anchorIndex + 1) {
            playNextAnchorId = null
            playNextRunLength = 0
            return
        }
        playNextAnchorId = newMediaId
        playNextRunLength -= 1
        if (playNextRunLength <= 0) playNextAnchorId = null
    }

    private fun resolveMissing(ids: List<String>) {
        val missing = ids.filter { it !in songCache && it !in pendingLookups }
        if (missing.isEmpty()) return
        pendingLookups += missing
        scope.launch {
            val found = libraryRepository.songsByIds(missing.mapNotNull { it.toLongOrNull() })
            found.forEach { songCache[it.id.toString()] = it }
            pendingLookups -= missing.toSet()
            if (found.isNotEmpty()) syncState()
        }
    }

    private fun syncState() {
        val c = controller ?: return
        val ids = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
        resolveMissing(ids)
        val queue = ids.mapNotNull(songCache::get)
        val positionMs = c.currentPosition.coerceAtLeast(0)
        val durationMs = c.duration.coerceAtLeast(0)
        val queueIndex = c.currentMediaItemIndex
        // Purely a display prediction: once we're within the crossfade
        // window of the current track's end, show what's coming next. Uses
        // only values already being computed here, so it stays in step with
        // the session-side crossfade engine without any extra plumbing.
        // The play-next run is only meaningful while the track it was
        // anchored to is still playing, and it can't outlive songs the user
        // removed by hand — so validate the anchor and clamp to what's
        // actually still queued ahead before publishing it.
        val remainingAhead = (queue.size - queueIndex - 1).coerceAtLeast(0)
        val playNextCount = if (c.currentMediaItem?.mediaId == playNextAnchorId) {
            playNextRunLength.coerceIn(0, remainingAhead)
        } else {
            0
        }
        val upcomingSong = if (
            localCrossfadeSeconds > 0 &&
            durationMs > 0 &&
            (durationMs - positionMs) <= localCrossfadeSeconds * 1000L &&
            c.repeatMode != Player.REPEAT_MODE_ONE &&
            c.nextMediaItemIndex in 0 until c.mediaItemCount &&
            c.nextMediaItemIndex != queueIndex
        ) {
            songCache[c.getMediaItemAt(c.nextMediaItemIndex).mediaId]
        } else null
        _playerState.value = PlayerState(
            currentSong = c.currentMediaItem?.let { songCache[it.mediaId] },
            queue = queue,
            queueIndex = queueIndex,
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            positionMs = positionMs,
            durationMs = durationMs,
            repeatMode = when (c.repeatMode) {
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                else -> RepeatMode.OFF
            },
            shuffleMode = localShuffleMode,
            playbackSpeed = c.playbackParameters.speed,
            pitchCorrection = localPitchCorrection,
            crossfadeSeconds = localCrossfadeSeconds,
            replayGainMode = localReplayGain,
            upcomingSong = upcomingSong,
            playNextCount = playNextCount,
            advancedAutomatically = lastAdvanceWasAutomatic,
            queueGeneration = queueGeneration,
            audioOutput = connect.state.value.active
                ?.let { com.harmony.core.model.AudioOutput(com.harmony.core.model.AudioOutputType.COMPUTER, it.name) }
                ?: audioOutputMonitor.output.value,
        )
    }

    // -- Queue ---------------------------------------------------------------

    override suspend fun setQueue(songs: List<Song>, startIndex: Int, playWhenReady: Boolean) {
        val c = controllerOrNull() ?: return
        songCache.clear()
        songs.forEach { songCache[it.id.toString()] = it }
        // A new list: the one and only place the generation moves (Smart Shuffle hands over on it).
        queueGeneration++
        c.setMediaItems(songs.map(::toMediaItem), if (songs.isEmpty()) 0 else startIndex.coerceIn(songs.indices), 0L)
        c.playWhenReady = playWhenReady
        c.prepare()
    }

    override suspend fun addToQueue(song: Song) {
        songCache[song.id.toString()] = song
        controllerOrNull()?.addMediaItem(toMediaItem(song))
    }

    /**
     * "Play next", stacking in the order chosen. A naive implementation
     * inserts at currentIndex + 1 every time, which makes each new song cut
     * IN FRONT of the previously queued one (swipe A then B and you hear
     * B, A) — the opposite of what picking two songs in a row means.
     *
     * So we track how many songs have been play-next'd since the current
     * track started, and append to the end of that run. The counter resets
     * whenever the playing track changes, so the "next up" group is always
     * anchored to what's actually playing now, not a stale position.
     */
    override suspend fun addToQueueAll(songs: List<Song>) {
        if (songs.isEmpty()) return
        songs.forEach { songCache[it.id.toString()] = it }
        controllerOrNull()?.addMediaItems(songs.map(::toMediaItem))
    }

    override suspend fun addNext(song: Song) {
        val c = controllerOrNull() ?: return
        songCache[song.id.toString()] = song
        val currentId = c.currentMediaItem?.mediaId
        if (currentId != playNextAnchorId) {
            playNextAnchorId = currentId
            playNextRunLength = 0
        }
        playNextRunLength += 1
        val index = (c.currentMediaItemIndex + playNextRunLength)
            .coerceIn(0, c.mediaItemCount)
        c.addMediaItem(index, toMediaItem(song))
    }

    override suspend fun removeQueueRange(fromIndex: Int, toIndexExclusive: Int) {
        val c = controllerOrNull() ?: return
        val from = fromIndex.coerceIn(0, c.mediaItemCount)
        val to = toIndexExclusive.coerceIn(from, c.mediaItemCount)
        if (to > from) c.removeMediaItems(from, to)
    }

    override suspend fun replaceQueueRange(fromIndex: Int, toIndexExclusive: Int, songs: List<Song>) {
        val c = controllerOrNull() ?: return
        songs.forEach { songCache[it.id.toString()] = it }
        val from = fromIndex.coerceIn(0, c.mediaItemCount)
        val to = toIndexExclusive.coerceIn(from, c.mediaItemCount)
        // One Media3 operation: the timeline changes once, the song playing
        // (always before [from]) never stops, and there is no window in which
        // the queue is shorter than it should be.
        when {
            to > from && songs.isNotEmpty() -> c.replaceMediaItems(from, to, songs.map(::toMediaItem))
            to > from -> c.removeMediaItems(from, to)
            songs.isNotEmpty() -> c.addMediaItems(from, songs.map(::toMediaItem))
        }
    }

    override suspend fun removeFromQueue(index: Int) {
        val c = controllerOrNull() ?: return
        if (index in 0 until c.mediaItemCount) c.removeMediaItem(index)
    }

    override suspend fun removeQueueItem(index: Int, expectedSongId: Long): Boolean {
        val c = controllerOrNull() ?: return false
        val current = c.currentMediaItemIndex
        // Checked against the player as it is now, not as the screen last saw it.
        if (index !in 0 until c.mediaItemCount || index == current) return false
        if (c.getMediaItemAt(index).mediaId != expectedSongId.toString()) return false
        // A song of the Play next run leaving it: the run is one shorter, not shifted onto the next song.
        if (c.currentMediaItem?.mediaId == playNextAnchorId && index in (current + 1)..(current + playNextRunLength)) {
            playNextRunLength -= 1
        }
        c.removeMediaItem(index)
        return true
    }

    override suspend fun removeSongsByUri(uris: Set<String>) {
        val c = controllerOrNull() ?: return
        for (index in c.mediaItemCount - 1 downTo 0) {
            val item = c.getMediaItemAt(index)
            if (item.localConfiguration?.uri?.toString() in uris || songCache[item.mediaId]?.uri in uris) c.removeMediaItem(index)
        }
        songCache.entries.removeAll { it.value.uri in uris }
        syncState()
        val ids = (0 until c.mediaItemCount).mapNotNull { c.getMediaItemAt(it).mediaId.toLongOrNull() }
        settingsRepository.saveLastPlaybackState(ids, c.currentMediaItemIndex.coerceAtLeast(0), c.currentPosition.coerceAtLeast(0))
    }

    override suspend fun moveQueueItem(from: Int, to: Int, expectedSongId: Long?): Boolean {
        val c = controllerOrNull() ?: return false
        val ids = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
        val current = c.currentMediaItemIndex
        val move = com.harmony.domain.playback.QueueMoves
            .resolve(ids, current, from, to, expectedSongId?.toString()) ?: return false
        // The Play next run is a length after the song playing: keep it on the
        // same entries, or a move across its edge would swap which songs it
        // protects (see QueueMoves.runAfterMove).
        if (c.currentMediaItem?.mediaId == playNextAnchorId) {
            val run = playNextRunLength.coerceIn(0, (ids.size - current - 1).coerceAtLeast(0))
            playNextRunLength = com.harmony.domain.playback.QueueMoves.runAfterMove(current, run, move.from, move.to)
        }
        // Moving upcoming entries never touches the one playing: no restart,
        // no seek. Media3 updates the timeline at once (the next state and
        // the session's own saving see the new order).
        c.moveMediaItem(move.from, move.to)
        return true
    }

    // -- Transport ----------------------------------------------------------

    override fun play() { scope.launch { controllerOrNull()?.play() } }
    override fun pause() { controller?.pause() }
    override fun seekTo(positionMs: Long) { controller?.seekTo(positionMs) }
    override fun skipToNext() { controller?.seekToNextMediaItem() }
    override fun skipToPrevious() { controller?.seekToPrevious() }
    override fun skipToQueueItem(index: Int) { controller?.seekTo(index, 0L) }

    // -- Modes --------------------------------------------------------------

    override fun setRepeatMode(mode: RepeatMode) {
        controller?.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        }
        syncState()
    }

    override fun setShuffleMode(mode: ShuffleMode) {
        localShuffleMode = mode
        // Every mode keeps ExoPlayer's own shuffle OFF. SmartQueueManager
        // physically orders the queue for RANDOM as for SMART and JOURNEY, so
        // the player plays exactly the order the queue screen shows. With
        // native shuffle on, Media3's DefaultShuffleOrder placed every added
        // song (play next included) at a random position, and every new list
        // got a new hidden order.
        controller?.shuffleModeEnabled = false
        syncState()
    }

    override fun setPlaybackSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
    }

    override fun setPitchCorrection(enabled: Boolean) {
        localPitchCorrection = enabled
        sendCustom(
            HarmonyMediaLibraryCallback.CMD_SET_PITCH_CORRECTION,
            Bundle().apply { putBoolean(HarmonyMediaLibraryCallback.ARG_ENABLED, enabled) },
        )
        syncState()
    }

    override fun setCrossfade(seconds: Int) {
        localCrossfadeSeconds = seconds
        sendCustom(
            HarmonyMediaLibraryCallback.CMD_SET_CROSSFADE,
            Bundle().apply { putInt(HarmonyMediaLibraryCallback.ARG_SECONDS, seconds) },
        )
        syncState()
    }

    override fun setReplayGainMode(mode: ReplayGainMode) {
        localReplayGain = mode
        sendCustom(
            HarmonyMediaLibraryCallback.CMD_SET_REPLAYGAIN,
            Bundle().apply { putInt(HarmonyMediaLibraryCallback.ARG_MODE, mode.ordinal) },
        )
        syncState()
    }

    override fun setEqualizer(settings: com.harmony.core.model.EqSettings) {
        sendCustom(
            HarmonyMediaLibraryCallback.CMD_SET_EQ,
            Bundle().apply {
                putBoolean(HarmonyMediaLibraryCallback.ARG_ENABLED, settings.enabled)
                putFloatArray(HarmonyMediaLibraryCallback.ARG_EQ_BANDS, settings.bandGainsDb.toFloatArray())
                putFloat(HarmonyMediaLibraryCallback.ARG_EQ_PREAMP, settings.preampDb)
                putBoolean(HarmonyMediaLibraryCallback.ARG_EQ_AUTO, settings.auto)
                putBoolean(HarmonyMediaLibraryCallback.ARG_EQ_BIT_PERFECT, settings.bitPerfectUsb)
            },
        )
    }

    override fun setSleepTimer(durationMs: Long?, finishTrack: Boolean) {
        sendCustom(
            HarmonyMediaLibraryCallback.CMD_SET_SLEEP_TIMER,
            Bundle().apply {
                putLong(HarmonyMediaLibraryCallback.ARG_DURATION_MS, durationMs ?: -1L)
                putBoolean(HarmonyMediaLibraryCallback.ARG_FINISH_TRACK, finishTrack)
            },
        )
    }

    // -- Helpers ------------------------------------------------------------

    /**
     * The connected controller, connecting first if that is allowed; null
     * when playback is closed (after a swipe-away, until the app is opened
     * again) or the service could not be reached. Callers then do nothing,
     * rather than throwing into whoever asked (a CancellationException here
     * used to end the coroutine that called, Smart Shuffle's collector
     * included).
     */
    private suspend fun controllerOrNull(): MediaController? {
        controller?.takeIf { it.isConnected }?.let { return it }
        if (!reconnectAllowed) return null
        connect()
        connectionJob?.join()
        return controller?.takeIf { it.isConnected }
    }

    private fun sendCustom(action: String, args: Bundle) {
        if (action != HarmonyMediaLibraryCallback.CMD_SET_SLEEP_TIMER) persistentCommands[action] = args
        // Fire on the connection scope, awaiting readiness, rather than a
        // synchronous null-check: settings (crossfade, ReplayGain, EQ) are
        // re-applied from persisted storage the instant the app starts, which
        // is routinely BEFORE the MediaController finishes connecting to the
        // service. A plain `controller?.sendCustomCommand(...)` would just
        // silently drop the command in that window — the setting looked
        // "saved" but never actually reached the live audio engine, only
        // taking effect later if the user happened to touch that setting
        // again in the same session.
        scope.launch {
            // Kept in persistentCommands either way: a later connection replays it.
            controllerOrNull()?.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
        }
    }

    private fun toMediaItem(song: Song): MediaItem = MediaItem.Builder()
        .setMediaId(song.id.toString())
        .setUri(song.uri)
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
                .setExtras(com.harmony.playback.service.player.ReplayGainMetadata.from(song))
                .setTitle(song.title)
                .setArtist(song.artist)
                .setAlbumTitle(song.album)
                // Its length, known before the player has read the file (Connect sends it on).
                .setDurationMs(song.durationMs.takeIf { it > 0 })
                // A content:// URI, not the raw file:// path: these items
                // reach out-of-process controllers (the Auto host, the
                // notification), which cannot read this app's private
                // files dir. See HarmonyPlayer.toMediaItem.
                .setArtworkUri(
                    when {
                        song.artworkUri != null -> android.net.Uri.parse(
                            com.harmony.core.media.artwork.ArtworkProvider.artUri(song.id, song.artworkUri)
                        )
                        song.albumId > 0 -> android.net.Uri.parse(
                            "content://media/external/audio/albumart/${song.albumId}"
                        )
                        else -> null
                    }
                )
                .build(),
        )
        .build()

    private companion object {
        const val POSITION_TICK_MS = 500L
        const val IDLE_TICK_MS = 5_000L // listen-time bookkeeping only, UI absent
        const val MIN_TRACKED_MS = 5_000L          // ignore accidental taps
        const val COMPLETED_ABSOLUTE_MS = 240_000L // 4 min, scrobble convention
        const val CONNECT_POLL_MS = 50L
    }
}
