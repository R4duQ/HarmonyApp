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
) : PlaybackController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _playerState = MutableStateFlow(PlayerState())
    override val playerState: StateFlow<PlayerState> = _playerState

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

    init {
        ensureConnected()
        // Output route changes (headphones plugged in, Bluetooth connected)
        // arrive independently of any player event, so they need their own
        // trigger to reach the UI.
        scope.launch {
            audioOutputMonitor.output.collect { syncState() }
        }
    }

    /** Reconnect on an explicit user action or Activity return, not after a task dismissal. */
    fun ensureConnected() {
        if (controller?.isConnected == true || connectionJob?.isActive == true) return
        connectionJob = scope.launch {
            kotlinx.coroutines.yield()
            var built: MediaController? = null
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
                        }).buildAsync().await()
                }
                built = connected
                check(connected.isConnected)
                controller = connected
                connected.addListener(playerListener)
                onTrackTransition(connected.currentMediaItem?.mediaId)
                tickerJob?.cancel()
                tickerJob = startPositionTicker()
                if (connected.mediaItemCount == 0) restoreLastPlaybackState(connected)
                connected.shuffleModeEnabled = localShuffleMode == ShuffleMode.RANDOM
                persistentCommands.forEach { (action, args) ->
                    connected.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
                }
                syncState()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                if (controller === built) controller = null
                tickerJob?.cancel()
                built?.release()
                android.util.Log.w("HarmonyPlayback", "Could not connect to playback service", error)
                android.widget.Toast.makeText(context, "Playback could not connect. Tap Play to retry.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
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
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                onTrackTransition(player.currentMediaItem?.mediaId)
            }
            tickListenTime()
            syncState()
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
        trackingSongId = newMediaId
        trackingListenedMs = 0
        listeningClock.reset()
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
            audioOutput = audioOutputMonitor.output.value,
        )
    }

    // -- Queue ---------------------------------------------------------------

    override suspend fun setQueue(songs: List<Song>, startIndex: Int, playWhenReady: Boolean) {
        val c = awaitController()
        songCache.clear()
        songs.forEach { songCache[it.id.toString()] = it }
        c.setMediaItems(songs.map(::toMediaItem), if (songs.isEmpty()) 0 else startIndex.coerceIn(songs.indices), 0L)
        c.playWhenReady = playWhenReady
        c.prepare()
    }

    override suspend fun addToQueue(song: Song) {
        songCache[song.id.toString()] = song
        awaitController().addMediaItem(toMediaItem(song))
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
        awaitController().addMediaItems(songs.map(::toMediaItem))
    }

    override suspend fun addNext(song: Song) {
        val c = awaitController()
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
        val c = awaitController()
        val from = fromIndex.coerceIn(0, c.mediaItemCount)
        val to = toIndexExclusive.coerceIn(from, c.mediaItemCount)
        if (to > from) c.removeMediaItems(from, to)
    }

    override suspend fun removeFromQueue(index: Int) {
        val c = awaitController()
        if (index in 0 until c.mediaItemCount) c.removeMediaItem(index)
    }

    override suspend fun removeSongsByUri(uris: Set<String>) {
        val c = awaitController()
        for (index in c.mediaItemCount - 1 downTo 0) {
            val item = c.getMediaItemAt(index)
            if (item.localConfiguration?.uri?.toString() in uris || songCache[item.mediaId]?.uri in uris) c.removeMediaItem(index)
        }
        songCache.entries.removeAll { it.value.uri in uris }
        syncState()
        val ids = (0 until c.mediaItemCount).mapNotNull { c.getMediaItemAt(it).mediaId.toLongOrNull() }
        settingsRepository.saveLastPlaybackState(ids, c.currentMediaItemIndex.coerceAtLeast(0), c.currentPosition.coerceAtLeast(0))
    }

    override suspend fun moveQueueItem(from: Int, to: Int) {
        val c = awaitController()
        if (from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount) c.moveMediaItem(from, to)
    }

    // -- Transport ----------------------------------------------------------

    override fun play() { scope.launch { awaitController().play() } }
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
        // RANDOM maps to ExoPlayer's shuffle order; SMART/JOURNEY are queue
        // strategies driven by domain-shuffle in Phase 7 and keep native
        // shuffle off (successors are chosen explicitly).
        controller?.shuffleModeEnabled = mode == ShuffleMode.RANDOM
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
                putFloat(HarmonyMediaLibraryCallback.ARG_EQ_BASS, settings.bassBoostDb)
                putFloat(HarmonyMediaLibraryCallback.ARG_EQ_TREBLE, settings.trebleBoostDb)
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

    private suspend fun awaitController(): MediaController {
        if (controller?.isConnected != true) {
            ensureConnected()
            connectionJob?.join()
        }
        return controller?.takeIf { it.isConnected }
            ?: throw kotlinx.coroutines.CancellationException("Playback connection unavailable")
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
            awaitController().sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
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
