package com.harmony.playback.service.connect

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.harmony.core.media.artwork.ArtworkCache
import com.harmony.core.remote.Connect
import com.harmony.core.remote.ConnectClient
import com.harmony.core.remote.ConnectRefused
import com.harmony.core.remote.ConnectSessionEnded
import com.harmony.core.remote.ControlAction
import com.harmony.core.remote.ControlRequest
import com.harmony.core.remote.Discovery
import com.harmony.core.remote.PlayRequest
import com.harmony.core.remote.RemoteRepeat
import com.harmony.core.remote.RemoteRequest
import com.harmony.core.remote.RemoteState
import com.harmony.core.remote.RemoteStatus
import com.harmony.core.remote.RemoteTrack
import com.harmony.domain.playback.ConnectComputer
import com.harmony.domain.playback.ConnectController
import com.harmony.domain.playback.ConnectState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Harmony Connect, the phone's half: plays the queue on a computer running
 * Harmony for Windows while the phone stays the remote.
 *
 * The phone keeps everything it always had — the queue, shuffle, repeat,
 * the notification — and its ExoPlayer simply stays paused. The computer is
 * told which song to play and pulls it from the phone over the Wi-Fi
 * ([PhoneFileServer]); [ConnectPlayer] answers the media session for the
 * computer. Every half second the computer is asked how it's doing: where in
 * the song, whether it finished one (the phone then moves its queue on and
 * sends the next) and whether someone pressed next or previous there.
 *
 * Everything here runs on the main thread, as the ExoPlayer requires; the
 * network calls go one at a time on their own thread, so they arrive in the
 * order they were made.
 */
@OptIn(UnstableApi::class)
@Singleton
class ConnectSession @Inject constructor(
    @ApplicationContext private val context: Context,
    private val artworkCache: ArtworkCache,
    private val remoteMedia: RemoteMedia,
) : ConnectController, com.harmony.domain.playback.PairedComputers {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val net = Executors.newSingleThreadExecutor { Thread(it, "harmony-connect").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val files = PhoneFileServer(context)

    private val _state = MutableStateFlow(ConnectState(computers = loadPaired().map { it.computer }))
    override val state: StateFlow<ConnectState> = _state

    private var exo: Player? = null
    private var player: ConnectPlayer? = null

    private var client: ConnectClient? = null
    private var computer: ConnectComputer? = null

    private val _queueClient = MutableStateFlow<ConnectClient?>(null)

    /**
     * The client of the session playing on a computer now (null: none), for
     * the shared queue (Universal Queue), which talks to the same computer in
     * the same session: when the computer ends the session, the queue link ends too.
     */
    val queueClient: StateFlow<ConnectClient?> = _queueClient

    /** The song the computer was last told to play. */
    private var sentId: String? = null
    private var endHandledFor: String? = null
    /** The song the computer may go on to by itself (the first of "up next" it was given). */
    private var advanceId: String? = null
    /** The computer went on by itself; the next send only catches up with it. */
    private var following = false
    private var refreshJob: Job? = null

    /**
     * Shuffle as the app has it: Smart Shuffle re-orders the queue itself and
     * leaves the player's own shuffle off, so the player alone can't tell.
     */
    private var appShuffle = false
    private val _shuffleRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /** Shuffle pressed on the computer: the app does what its own shuffle button does. */
    val shuffleRequests: SharedFlow<Unit> = _shuffleRequests

    /** What the listener wants: playing or paused. */
    private var wantPlay = false
    private var remoteState: RemoteState? = null
    private var remoteVolume = 1f

    /** The position at [anchorAt], moving on from there while the computer plays. */
    private var anchorMs = 0L
    private var anchorAt = 0L

    /** After a command, the computer's answers lag; don't let them undo what was just asked. */
    private var trustLocalUntil = 0L
    private var failures = 0
    /** When the computer stopped answering; 0 while it answers. */
    private var failingSince = 0L
    /** When the computer was last paused from here or there; the locks are kept a while after. */
    private var pausedAt = 0L
    private var lockJob: Job? = null
    private var pollJob: Job? = null
    private var searchJob: Job? = null

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    val isActive: Boolean get() = client != null

    private val exoListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!isActive || mediaItem == null) return
            // Another song on the phone's queue (skip, a tap in the queue, a new
            // album): the computer plays that one now. Or the phone following
            // the computer, which already plays it.
            if (!following) anchor(exo?.currentPosition ?: 0)
            sendCurrent()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!isActive || !playWhenReady) return
            // Something told the phone's own player to play (the app picking a
            // new queue, Android Auto): the computer plays instead.
            exo?.playWhenReady = false
            remotePlay()
        }

        // What comes next changed: tell the computer, which may go on to it by itself.
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = refreshUpNext()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = refreshUpNext()
        override fun onRepeatModeChanged(repeatMode: Int) = refreshUpNext()
    }

    /** The app's shuffle changed: the computer's shuffle button shows it. */
    fun setAppShuffle(on: Boolean) {
        if (appShuffle == on) return
        appShuffle = on
        refreshUpNext()
    }

    private fun shuffleOn(exo: Player) = appShuffle || exo.shuffleModeEnabled

    private fun repeatOf(exo: Player) = when (exo.repeatMode) {
        Player.REPEAT_MODE_ALL -> RemoteRepeat.ALL
        Player.REPEAT_MODE_ONE -> RemoteRepeat.ONE
        else -> RemoteRepeat.OFF
    }

    private fun refreshUpNext() {
        if (!isActive) return
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay(REFRESH_DELAY_MS)
            if (isActive && exo?.currentMediaItem?.mediaId == sentId) sendCurrent(followUp = true)
        }
    }

    // ---- Wiring ------------------------------------------------------------------

    /** Called by the playback service once its player exists. */
    fun attach(exo: Player, player: ConnectPlayer) {
        this.exo = exo
        this.player = player
        exo.addListener(exoListener)
    }

    fun detach() {
        if (isActive) end(error = null, tellComputer = true)
        exo?.removeListener(exoListener)
        exo = null
        player = null
    }

    // ---- ConnectController ---------------------------------------------------------

    override fun search() {
        if (searchJob?.isActive == true) return
        _state.update { it.copy(searching = true) }
        searchJob = scope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { Discovery.find(SEARCH_MS) }.getOrDefault(emptyList()) }
            val saved = loadPaired()
            // A paired computer may have a new address since; keep it current.
            var changed = false
            val updated = saved.map { s ->
                val now = found.firstOrNull { it.id == s.computer.id } ?: return@map s
                if (now.host == s.computer.host && now.port == s.computer.port && now.name == s.computer.name) s
                else s.copy(computer = s.computer.copy(host = now.host, port = now.port, name = now.name)).also { changed = true }
            }
            if (changed) savePaired(updated)
            val pairedIds = updated.map { it.computer.id }.toSet()
            val nearbyIds = found.map { it.id }.toSet()
            val list = updated.map { it.computer.copy(nearby = it.computer.id in nearbyIds) } +
                found.filter { it.id !in pairedIds }.map { ConnectComputer(it.id, it.name, it.host, it.port, paired = false, nearby = true) }
            _state.update { it.copy(searching = false, computers = list.sortedWith(compareByDescending<ConnectComputer> { it.nearby }.thenBy { it.name.lowercase() })) }
        }
    }

    override fun connect(computer: ConnectComputer) {
        if (_state.value.busyWith != null) return
        if (this.computer?.id == computer.id && isActive) return
        val token = loadPaired().firstOrNull { it.computer.id == computer.id }?.token
        if (token == null) {
            _state.update { it.copy(needsCode = computer, error = null) }
            return
        }
        start(computer, token)
    }

    override fun pair(computer: ConnectComputer, code: String) {
        if (_state.value.busyWith != null) return
        _state.update { it.copy(busyWith = computer.id, error = null) }
        scope.launch {
            val token = withContext(Dispatchers.IO) {
                runCatching { ConnectClient(computer.host, computer.port).pair(code.trim(), phoneName()) }
            }
            token.onSuccess { t ->
                val paired = computer.copy(paired = true)
                savePaired(loadPaired().filter { it.computer.id != computer.id } + Saved(paired, t))
                _state.update { s ->
                    s.copy(
                        needsCode = null, busyWith = null,
                        computers = s.computers.filter { it.id != computer.id } + paired.copy(nearby = true),
                    )
                }
                start(paired, t)
            }.onFailure { e ->
                _state.update {
                    it.copy(
                        busyWith = null,
                        error = if (e is ConnectRefused) "That code didn't match. Check the code shown on ${computer.name}."
                        else "Couldn't reach ${computer.name}. Is Harmony open on it, on the same Wi-Fi?",
                    )
                }
            }
        }
    }

    override fun addByAddress(host: String) {
        val text = host.trim()
        if (text.isEmpty()) return
        val colon = text.lastIndexOf(':')
        val (h, p) = if (colon > 0 && text.indexOf(':') == colon) {
            text.substring(0, colon) to (text.substring(colon + 1).toIntOrNull() ?: Connect.HTTP_PORT)
        } else {
            text to Connect.HTTP_PORT
        }
        _state.update { it.copy(busyWith = text, error = null) }
        scope.launch {
            val info = withContext(Dispatchers.IO) { runCatching { ConnectClient(h, p).info() }.getOrNull() }
            _state.update { it.copy(busyWith = null) }
            if (info == null) {
                _state.update { it.copy(error = "No Harmony computer answered at $text.") }
                return@launch
            }
            val known = loadPaired().firstOrNull { it.computer.id == info.id }
            val found = ConnectComputer(info.id, info.name, h, p, paired = known != null, nearby = true)
            if (known != null) savePaired(loadPaired().map { if (it.computer.id == info.id) it.copy(computer = found) else it })
            _state.update { s -> s.copy(computers = s.computers.filter { it.id != info.id } + found) }
            connect(found)
        }
    }

    override fun disconnect() {
        if (!isActive || handoffJob?.isActive == true) return
        val exo = exo
        val c = client
        // Playing on the computer: it carries on here, from the same place, without the two ever
        // playing at once (stage 7). Paused, or anything unusual: back here paused, as before.
        if (exo != null && c != null && wantPlay && remoteState == RemoteState.PLAYING && exo.currentMediaItem?.mediaId == sentId) {
            handOffToPhone(c, exo)
            return
        }
        end(error = null, tellComputer = true)
    }

    override fun forget(computer: ConnectComputer) {
        if (this.computer?.id == computer.id && isActive) end(error = null, tellComputer = true)
        savePaired(loadPaired().filter { it.computer.id != computer.id })
        _state.update { s ->
            s.copy(computers = s.computers.mapNotNull { if (it.id != computer.id) it else if (it.nearby) it.copy(paired = false) else null })
        }
    }

    override fun dismissError() {
        _state.update { it.copy(error = null, needsCode = null) }
    }

    // ---- Starting and ending -------------------------------------------------------

    private fun start(target: ConnectComputer, token: String) {
        val exo = exo
        if (exo == null) {
            _state.update { it.copy(error = "Start playing something first.") }
            return
        }
        if (handoffJob?.isActive == true) return
        // Playing here: the computer gets the song ready first and takes over at a set point
        // (Universal Queue, stage 7), instead of the phone going silent while it loads.
        if (client == null && exo.playWhenReady && exo.currentMediaItem != null) {
            handOffToComputer(target, token)
            return
        }
        startDirect(target, token)
    }

    /** The music moves as it always did: the phone falls silent and the computer starts from where it was. */
    private fun startDirect(target: ConnectComputer, token: String) {
        val exo = exo ?: return
        val previous = client
        val item = exo.currentMediaItem
        val position = if (previous != null) remotePosition() else exo.currentPosition.coerceAtLeast(0)
        val playing = if (previous != null) wantPlay else exo.playWhenReady
        val upNext = upNextItems(exo)
        val canAdvance = exo.repeatMode != Player.REPEAT_MODE_ONE
        val duration = knownDuration(exo)
        val shuffle = shuffleOn(exo)
        val repeat = repeatOf(exo)
        _state.update { it.copy(busyWith = target.id, error = null, needsCode = null) }
        scope.launch {
            // A new id for every session, so the computer can refuse what a late
            // request from an earlier one would do.
            val fresh = ConnectClient(target.host, target.port, token, session = java.util.UUID.randomUUID().toString())
            val result = withContext(net) {
                runCatching {
                    previous?.disconnect()
                    files.start()
                    if (item != null) fresh.play(playRequest(fresh, item, position, playing, duration, upNext, canAdvance, followUp = false, shuffle, repeat)) else fresh.status()
                }
            }
            result.onFailure { e ->
                if (previous != null) end(error = null, tellComputer = false)
                if (e is ConnectRefused) {
                    savePaired(loadPaired().filter { it.computer.id != target.id })
                    _state.update { s ->
                        s.copy(
                            busyWith = null, needsCode = target.copy(paired = false),
                            computers = s.computers.map { if (it.id == target.id) it.copy(paired = false) else it },
                            error = "${target.name} doesn't know this phone any more. Type its code to pair again.",
                        )
                    }
                } else {
                    if (previous == null) withContext(net) { files.stop() }
                    _state.update { it.copy(busyWith = null, error = "Couldn't reach ${target.name}. Is Harmony open on it, on the same Wi-Fi?") }
                }
                return@launch
            }
            val wasActive = isActive
            client = fresh
            _queueClient.value = fresh
            computer = target
            sentId = item?.mediaId
            advanceId = upNext.firstOrNull()?.mediaId?.takeIf { canAdvance }
            following = false
            endHandledFor = null
            remoteState = result.getOrNull()?.state ?: RemoteState.LOADING
            wantPlay = playing && item != null
            failures = 0
            failingSince = 0L
            anchor(position)
            trustLocalUntil = SystemClock.elapsedRealtime() + TRUST_LOCAL_MS
            // The phone falls silent; from here on it is the remote.
            exo.playWhenReady = false
            _state.update { it.copy(active = target, busyWith = null) }
            holdLocks()
            if (!wasActive) player?.notifyRemoteChanged()
            player?.notifyPlayState()
            startPolling()
        }
    }

    /** Back to the phone, paused where the computer was. */
    private fun end(error: String?, tellComputer: Boolean, seekBack: Boolean = true) {
        val old = client ?: return
        val position = remotePosition()
        val same = seekBack && exo?.currentMediaItem?.mediaId == sentId
        pollJob?.cancel()
        pollJob = null
        client = null
        _queueClient.value = null
        computer = null
        sentId = null
        advanceId = null
        following = false
        refreshJob?.cancel()
        wantPlay = false
        remoteState = null
        releaseLocks()
        scope.launch(net) {
            if (tellComputer) old.disconnect()
            files.stop()
        }
        exo?.let { e ->
            e.playWhenReady = false
            if (same) e.seekTo(position)
        }
        _state.update { it.copy(active = null, busyWith = null, error = error) }
        player?.notifyRemoteChanged()
        player?.notifyPlayState()
    }

    // ---- Handoff (Universal Queue, stage 7) -----------------------------------------

    private var handoffJob: Job? = null

    /**
     * Phone to computer, while playing: the computer loads the song paused a
     * moment ahead; when the phone reaches that point it stops and the
     * computer starts. If the computer can't, the phone plays on; if it stops
     * answering after it was told to play, the phone stays silent and the
     * session goes on as usual (it gives up after half a minute of silence).
     */
    private fun handOffToComputer(target: ConnectComputer, token: String) {
        val exo = exo ?: return
        val item = exo.currentMediaItem ?: return
        _state.update { it.copy(busyWith = target.id, error = null, needsCode = null) }
        val fresh = ConnectClient(target.host, target.port, token, session = java.util.UUID.randomUUID().toString())
        val pc = ComputerSide(fresh, item.mediaId) { position ->
            val upNext = upNextItems(exo)
            val canAdvance = exo.repeatMode != Player.REPEAT_MODE_ONE
            val duration = knownDuration(exo)
            val shuffle = shuffleOn(exo)
            val repeat = repeatOf(exo)
            withContext(net) {
                files.start()
                fresh.play(playRequest(fresh, item, position, false, duration, upNext, canAdvance, followUp = false, shuffle, repeat))
            }
        }
        val phone = PhoneSide(exo)
        val handoff = com.harmony.core.remote.handoff.Handoff(phone, pc, SystemClock.elapsedRealtime(), leadMs = HANDOFF_LEAD_MS)
        handoffJob = scope.launch {
            pc.watch(this)
            var result: com.harmony.core.remote.handoff.HandoffResult?
            while (true) {
                result = handoff.tick(SystemClock.elapsedRealtime())
                if (result != null) break
                delay(HANDOFF_TICK_MS)
            }
            pc.close()
            when (result) {
                is com.harmony.core.remote.handoff.HandoffResult.Moved,
                is com.harmony.core.remote.handoff.HandoffResult.Unsure -> {
                    val at = (result as? com.harmony.core.remote.handoff.HandoffResult.Moved)?.atMs
                        ?: (result as com.harmony.core.remote.handoff.HandoffResult.Unsure).atMs
                    Log.i(TAG, "handoff to ${target.name}: $result")
                    becomeActive(fresh, target, item, at, playing = true, state = RemoteState.PLAYING)
                }
                is com.harmony.core.remote.handoff.HandoffResult.Stayed -> {
                    Log.i(TAG, "handoff to ${target.name} stayed: ${result.why}")
                    val reached = pc.reached
                    scope.launch(net) { runCatching { fresh.disconnect() } }
                    if (!reached || result.why != com.harmony.core.remote.handoff.HandoffResult.Why.NOT_READY &&
                        result.why != com.harmony.core.remote.handoff.HandoffResult.Why.TARGET_FAILED
                    ) {
                        // Not reached (or the moment passed): the usual way, with its own messages.
                        startDirect(target, token)
                    } else {
                        withContext(net) { files.stop() }
                        _state.update { it.copy(busyWith = null, error = "${target.name} couldn't play this song. It carries on on this phone.") }
                    }
                }
            }
        }
    }

    /**
     * Computer to phone ("This phone"), while playing: the phone loads the
     * song a moment ahead of the computer; at that point the computer is
     * paused (and must say so), then the phone plays on. If the computer
     * doesn't confirm the pause, the phone doesn't start (never two at once):
     * it is back here paused, as before.
     */
    private fun handOffToPhone(c: ConnectClient, exo: Player) {
        val pc = ComputerSource(c)
        val phone = PhoneTarget(exo) {
            // The computer is paused: the session ends here, then the phone plays.
            end(error = null, tellComputer = true, seekBack = false)
            exo.playWhenReady = true
        }
        val handoff = com.harmony.core.remote.handoff.Handoff(pc, phone, SystemClock.elapsedRealtime(), leadMs = HANDOFF_LEAD_MS)
        handoffJob = scope.launch {
            var result: com.harmony.core.remote.handoff.HandoffResult?
            while (true) {
                result = handoff.tick(SystemClock.elapsedRealtime())
                if (result != null) break
                delay(HANDOFF_TICK_MS)
            }
            Log.i(TAG, "handoff to the phone: $result")
            if (result !is com.harmony.core.remote.handoff.HandoffResult.Moved && this@ConnectSession.isActive) {
                val unreachable = (result as? com.harmony.core.remote.handoff.HandoffResult.Stayed)?.why ==
                    com.harmony.core.remote.handoff.HandoffResult.Why.SOURCE_UNREACHABLE
                end(
                    error = if (unreachable) "${computer?.name ?: "The computer"} didn't answer, so it may still be playing. The music is paused on this phone." else null,
                    tellComputer = true,
                )
            }
        }
    }

    /** The phone's own player, as a handoff source (phone to computer). */
    private inner class PhoneSide(private val exo: Player) : com.harmony.core.remote.handoff.HandoffPlayer {
        override fun songId() = exo.currentMediaItem?.mediaId
        override fun positionMs() = exo.currentPosition.coerceAtLeast(0)
        override fun isPlaying() = exo.playWhenReady
        override fun busy() = (exo as? com.harmony.playback.service.player.TwinPlayer)?.crossfadeNear(HANDOFF_LEAD_MS * 3) == true
        override fun prepare(positionMs: Long) = Unit
        override fun readiness() = if (exo.isPlaying) com.harmony.core.remote.handoff.Readiness.PLAYING else com.harmony.core.remote.handoff.Readiness.READY
        override fun start() { exo.playWhenReady = true }
        override fun stop() { exo.playWhenReady = false }
        override fun stopped(): Boolean = !exo.playWhenReady
    }

    /** The phone's own player, as a handoff target (computer to phone); [begin] ends the session and plays. */
    private inner class PhoneTarget(private val exo: Player, private val begin: () -> Unit) : com.harmony.core.remote.handoff.HandoffPlayer {
        private var preparedAt = -1L
        private var askedAt = 0L
        private var started = false
        override fun songId() = exo.currentMediaItem?.mediaId
        override fun positionMs() = exo.currentPosition.coerceAtLeast(0)
        override fun isPlaying() = exo.isPlaying
        override fun busy() = false
        override fun prepare(positionMs: Long) {
            preparedAt = positionMs
            askedAt = SystemClock.elapsedRealtime()
            exo.playWhenReady = false
            exo.seekTo(positionMs)
            if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
        }
        override fun readiness(): com.harmony.core.remote.handoff.Readiness = when {
            exo.playerError != null -> com.harmony.core.remote.handoff.Readiness.FAILED
            started && exo.isPlaying -> com.harmony.core.remote.handoff.Readiness.PLAYING
            started -> com.harmony.core.remote.handoff.Readiness.NOT_YET
            // A moment for the seek to show in the player's state before "ready" means the new place.
            SystemClock.elapsedRealtime() - askedAt > SEEK_SETTLE_MS && exo.playbackState == Player.STATE_READY &&
                kotlin.math.abs(exo.currentPosition - preparedAt) < 500 -> com.harmony.core.remote.handoff.Readiness.READY
            else -> com.harmony.core.remote.handoff.Readiness.NOT_YET
        }
        override fun start() {
            started = true
            begin()
        }
        override fun stop() {
            if (!started) exo.playWhenReady = false
        }
        override fun stopped(): Boolean = !exo.playWhenReady
    }

    /**
     * The computer, as a handoff target (phone to computer): [send] asks it to
     * load the song paused at a position; its state is read every 100 ms.
     */
    private inner class ComputerSide(
        private val c: ConnectClient,
        private val mediaId: String,
        private val send: suspend (Long) -> Unit,
    ) : com.harmony.core.remote.handoff.HandoffPlayer {
        @Volatile private var ready = com.harmony.core.remote.handoff.Readiness.NOT_YET
        @Volatile private var askedFor = -1L
        @Volatile private var loaded = false
        @Volatile private var misses = 0
        @Volatile private var position = 0L
        private var job: Job? = null

        /** Whether the computer answered at all (else the usual start handles the error). */
        @Volatile var reached = false
            private set

        fun watch(scope: CoroutineScope) {
            job = scope.launch {
                while (true) {
                    delay(HANDOFF_POLL_MS)
                    if (!loaded) continue
                    val r = withContext(net) { runCatching { c.status() } }
                    r.onSuccess { s ->
                        misses = 0
                        position = s.positionMs
                        if (s.trackId != mediaId) return@onSuccess
                        ready = when (s.state) {
                            RemoteState.PLAYING -> com.harmony.core.remote.handoff.Readiness.PLAYING
                            RemoteState.ERROR -> com.harmony.core.remote.handoff.Readiness.FAILED
                            RemoteState.PAUSED -> if (kotlin.math.abs(s.positionMs - askedFor) < 400) com.harmony.core.remote.handoff.Readiness.READY else ready
                            else -> ready
                        }
                    }.onFailure { e ->
                        ready = if (e is ConnectRefused || e is ConnectSessionEnded) com.harmony.core.remote.handoff.Readiness.FAILED
                        else if (++misses >= 3) com.harmony.core.remote.handoff.Readiness.UNREACHABLE else ready
                    }
                }
            }
        }

        fun close() { job?.cancel() }

        override fun songId() = mediaId
        override fun positionMs() = position
        override fun isPlaying() = ready == com.harmony.core.remote.handoff.Readiness.PLAYING
        override fun busy() = false
        override fun prepare(positionMs: Long) {
            askedFor = positionMs
            ready = com.harmony.core.remote.handoff.Readiness.NOT_YET
            loaded = false
            scope.launch {
                runCatching { send(positionMs) }
                    .onSuccess { reached = true; loaded = true }
                    .onFailure { e ->
                        ready = if (e is ConnectRefused || e is ConnectSessionEnded) com.harmony.core.remote.handoff.Readiness.FAILED
                        else com.harmony.core.remote.handoff.Readiness.UNREACHABLE
                    }
            }
        }
        override fun readiness() = ready
        override fun start() {
            scope.launch(net) { runCatching { c.control(ControlRequest(ControlAction.PLAY)) } }
        }
        override fun stop() {
            scope.launch(net) { runCatching { c.control(ControlRequest(ControlAction.PAUSE)) } }
        }
        override fun stopped(): Boolean? = ready != com.harmony.core.remote.handoff.Readiness.PLAYING
    }

    /** The computer, as a handoff source (computer to phone): its pause must be confirmed. */
    private inner class ComputerSource(private val c: ConnectClient) : com.harmony.core.remote.handoff.HandoffPlayer {
        @Volatile private var stopState: Boolean? = false
        override fun songId() = sentId
        override fun positionMs() = remotePosition()
        override fun isPlaying() = wantPlay && remoteState == RemoteState.PLAYING
        override fun busy() = false
        override fun prepare(positionMs: Long) = Unit
        override fun readiness() = if (isPlaying()) com.harmony.core.remote.handoff.Readiness.PLAYING else com.harmony.core.remote.handoff.Readiness.READY
        override fun start() {
            // Handing back after the phone failed: the computer plays on.
            scope.launch(net) { runCatching { c.control(ControlRequest(ControlAction.PLAY)) } }
        }
        override fun stop() {
            stopState = false
            scope.launch {
                val r = withContext(net) { runCatching { c.control(ControlRequest(ControlAction.PAUSE)) } }
                stopState = r.fold(
                    onSuccess = { s -> s == null || s.state != RemoteState.PLAYING },
                    // A session the computer already ended isn't playing there.
                    onFailure = { e -> if (e is ConnectSessionEnded) true else null },
                )
            }
        }
        override fun stopped(): Boolean? = stopState
    }

    /** The session is the computer's from here: what the start of a session always sets. */
    private fun becomeActive(fresh: ConnectClient, target: ConnectComputer, item: MediaItem, positionMs: Long, playing: Boolean, state: RemoteState) {
        val exo = exo ?: return
        val upNext = upNextItems(exo)
        val canAdvance = exo.repeatMode != Player.REPEAT_MODE_ONE
        val wasActive = isActive
        client = fresh
        _queueClient.value = fresh
        computer = target
        sentId = item.mediaId
        advanceId = upNext.firstOrNull()?.mediaId?.takeIf { canAdvance }
        following = false
        endHandledFor = null
        remoteState = state
        wantPlay = playing
        failures = 0
        failingSince = 0L
        anchor(positionMs)
        trustLocalUntil = SystemClock.elapsedRealtime() + TRUST_LOCAL_MS
        exo.playWhenReady = false
        _state.update { it.copy(active = target, busyWith = null) }
        holdLocks()
        if (!wasActive) player?.notifyRemoteChanged()
        player?.notifyPlayState()
        startPolling()
    }

    // ---- What the computer is told -------------------------------------------------

    /**
     * Tells the computer to play the current song. With [followUp] (or right
     * after the computer went on by itself) it only brings the computer up to
     * date: the song carries on there as it is, with the new "up next".
     */
    private fun sendCurrent(playing: Boolean = wantPlay, followUp: Boolean = false) {
        val exo = exo ?: return
        val c = client ?: return
        val item = exo.currentMediaItem ?: return
        val catchUp = followUp || following
        following = false
        val position = exo.currentPosition.coerceAtLeast(0)
        val duration = knownDuration(exo)
        val upNext = upNextItems(exo)
        val canAdvance = exo.repeatMode != Player.REPEAT_MODE_ONE
        sentId = item.mediaId
        advanceId = upNext.firstOrNull()?.mediaId?.takeIf { canAdvance }
        if (!catchUp) {
            endHandledFor = null
            remoteState = RemoteState.LOADING
            wantPlay = playing
            anchor(position)
            trustLocalUntil = SystemClock.elapsedRealtime() + TRUST_LOCAL_MS
            player?.notifyPlayState()
            updateLocks()
        }
        val shuffle = shuffleOn(exo)
        val repeat = repeatOf(exo)
        send { c.play(playRequest(c, item, position, playing, duration, upNext, canAdvance, catchUp, shuffle, repeat)) }
    }

    /** The computer went on to [id] by itself: move the phone's queue there too, without restarting it. */
    private fun followComputer(id: String, status: RemoteStatus) {
        val exo = exo ?: return
        val next = exo.nextMediaItemIndex
        // The queue may have been reordered since the computer was told what
        // comes next: find the song after the one playing first (the same
        // song can also be in the history), anywhere else only if it isn't.
        val ahead = (exo.currentMediaItemIndex + 1 until exo.mediaItemCount)
        val target = if (next != C.INDEX_UNSET && exo.getMediaItemAt(next).mediaId == id) next
        else ahead.firstOrNull { exo.getMediaItemAt(it).mediaId == id }
            ?: (0 until exo.mediaItemCount).firstOrNull { exo.getMediaItemAt(it).mediaId == id } ?: return
        sentId = id
        advanceId = null
        endHandledFor = null
        remoteState = status.state
        anchor(status.positionMs)
        following = true
        // The transition sends the catch-up (see the listener).
        exo.seekTo(target, 0)
        if (following) {
            // No transition came (the same item): catch up now.
            sendCurrent(followUp = true)
        }
        player?.notifyPlayState()
    }

    /** Runs on the network thread. */
    private fun playRequest(
        c: ConnectClient,
        item: MediaItem,
        positionMs: Long,
        playing: Boolean,
        durationMs: Long,
        upNext: List<MediaItem>,
        autoAdvance: Boolean,
        followUp: Boolean,
        shuffle: Boolean,
        repeat: RemoteRepeat,
    ): PlayRequest {
        val uri = item.localConfiguration?.uri ?: throw IOException("nothing to play")
        val host = localAddressToward(c.host)
        val (url, art) = share(item, uri, host)
        val md = item.mediaMetadata
        val track = RemoteTrack(
            id = item.mediaId,
            title = md.title?.toString() ?: uri.lastPathSegment ?: "Unknown",
            artist = md.artist?.toString() ?: "",
            album = md.albumTitle?.toString() ?: "",
            durationMs = durationMs.takeIf { it > 0 } ?: md.durationMs ?: 0L,
            url = url,
            artUrl = art,
            key = contentKey(item.mediaId),
        )
        val next = upNext.mapIndexed { i, it ->
            val m = it.mediaMetadata
            // The first one can be played straight on; the computer fetches it and the
            // one or two after it ahead of time, so a skip there doesn't wait on the Wi-Fi.
            val nextUri = it.localConfiguration?.uri
            val (nextUrl, nextArt) = when {
                i < FETCHED_AHEAD && autoAdvance && nextUri != null -> share(it, nextUri, host)
                // Covers for the first few, so the computer's queue has pictures.
                i < COVERED_UP_NEXT -> "" to files.shareCover(it.mediaId, coverFor(it), host)
                else -> "" to null
            }
            RemoteTrack(it.mediaId, m.title?.toString() ?: "", m.artist?.toString() ?: "", m.albumTitle?.toString() ?: "", m.durationMs ?: 0L, nextUrl, nextArt, key = contentKey(it.mediaId))
        }
        // The phone's crossfade, for the computer to fade between the phone's songs as the phone would (stage 8).
        val crossfade = (exo as? com.harmony.playback.service.player.TwinPlayer)?.crossfadeSeconds
        return PlayRequest(track, positionMs, playing, phoneName(), next, autoAdvance = autoAdvance, followUp = followUp, shuffle = shuffle, repeat = repeat, crossfadeSeconds = crossfade)
    }

    /**
     * The address of [item]'s audio and cover for the computer: served by the
     * phone, or for a computer's own song (Universal Queue, stage 6) that
     * computer's address for it (the computer it belongs to plays its own file).
     */
    private fun share(item: MediaItem, uri: android.net.Uri, host: String): Pair<String, String?> =
        if (com.harmony.domain.playback.RemoteSongs.isRemoteKey(item.mediaId)) (remoteMedia.urlFor(item.mediaId) ?: "") to null
        else files.share(item.mediaId, uri, coverFor(item), host)

    /**
     * The current song's length: the player's once it has read the file, else
     * the library's. Right after a skip the player doesn't know it yet, and a
     * computer told "0" can't draw its progress bar.
     */
    private fun knownDuration(exo: Player): Long =
        exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: exo.currentMediaItem?.mediaMetadata?.durationMs ?: 0L

    private fun coverFor(item: MediaItem): ByteArray? {
        val md = item.mediaMetadata
        md.artworkData?.let { return it }
        val art = md.artworkUri ?: return null
        val id = item.mediaId.toLongOrNull()
        if (id != null && art.authority == com.harmony.core.media.artwork.ArtworkProvider.AUTHORITY) {
            artworkCache.bytes(id, maxSize = 640, userArtwork = art.getQueryParameter("user") == "true")?.let { return it }
        }
        return runCatching { context.contentResolver.openInputStream(art)?.use { it.readBytes() } }.getOrNull()
            ?.takeIf { it.size in 1..MAX_COVER_BYTES }
    }

    private fun upNextItems(exo: Player): List<MediaItem> {
        val timeline = exo.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val out = ArrayList<MediaItem>()
        var index = exo.currentMediaItemIndex
        val seen = HashSet<Int>().apply { add(index) }
        // Repeat all wraps round to the start, as the phone itself would.
        val repeat = if (exo.repeatMode == Player.REPEAT_MODE_ALL) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        while (out.size < UP_NEXT) {
            index = timeline.getNextWindowIndex(index, repeat, exo.shuffleModeEnabled)
            if (index == C.INDEX_UNSET || !seen.add(index)) break
            out += exo.getMediaItemAt(index)
        }
        return out
    }

    private fun send(call: (ConnectClient) -> Any?) {
        val c = client ?: return
        scope.launch {
            val failure = withContext(net) { runCatching { call(c) }.exceptionOrNull() }
            if (failure != null && client === c) onFailure(failure)
        }
    }

    // ---- Called by ConnectPlayer ---------------------------------------------------

    fun remoteWantsPlay(): Boolean = wantPlay

    fun remotePlaybackState(): Int = when {
        exo?.currentMediaItem == null -> Player.STATE_IDLE
        else -> when (remoteState) {
            RemoteState.LOADING, null -> Player.STATE_BUFFERING
            RemoteState.ENDED -> if (wantPlay) Player.STATE_BUFFERING else Player.STATE_READY
            else -> Player.STATE_READY
        }
    }

    fun remoteIsPlaying(): Boolean = wantPlay && remotePlaybackState() == Player.STATE_READY

    fun remotePosition(): Long {
        val moving = wantPlay && remoteState == RemoteState.PLAYING
        val pos = if (moving) anchorMs + (SystemClock.elapsedRealtime() - anchorAt) else anchorMs
        val duration = exo?.duration?.takeIf { it != C.TIME_UNSET && it > 0 }
        return if (duration != null) pos.coerceIn(0, duration) else pos.coerceAtLeast(0)
    }

    fun remotePlay() {
        if (!isActive) return
        val state = remoteState
        val exo = exo ?: return
        if (exo.currentMediaItem?.mediaId != sentId) {
            sendCurrent(playing = true)
            return
        }
        if (state == RemoteState.ENDED || state == RemoteState.ERROR || state == RemoteState.IDLE) {
            // Nothing (or something finished) is loaded there: send the song again.
            exo.seekTo(if (state == RemoteState.ENDED) 0 else remotePosition())
            sendCurrent(playing = true)
            return
        }
        anchor(remotePosition())
        wantPlay = true
        trustLocalUntil = SystemClock.elapsedRealtime() + TRUST_LOCAL_MS
        player?.notifyPlayState()
        updateLocks()
        send { it.control(ControlRequest(ControlAction.PLAY)) }
    }

    fun remotePause() {
        if (!isActive) return
        anchor(remotePosition())
        wantPlay = false
        trustLocalUntil = SystemClock.elapsedRealtime() + TRUST_LOCAL_MS
        player?.notifyPlayState()
        updateLocks()
        send { it.control(ControlRequest(ControlAction.PAUSE)) }
    }

    fun remoteSeek(positionMs: Long) {
        if (!isActive) return
        val from = remotePosition()
        anchor(positionMs.coerceAtLeast(0))
        trustLocalUntil = SystemClock.elapsedRealtime() + TRUST_LOCAL_MS
        player?.notifyPositionJump(from, anchorMs)
        if (remoteState == RemoteState.ENDED) {
            sendCurrent(playing = wantPlay)
            return
        }
        send { it.control(ControlRequest(ControlAction.SEEK, anchorMs)) }
    }

    fun remoteVolumeSteps(): Int = Math.round(remoteVolume * VOLUME_STEPS)

    fun setRemoteVolumeSteps(steps: Int) {
        if (!isActive) return
        remoteVolume = steps.coerceIn(0, VOLUME_STEPS) / VOLUME_STEPS.toFloat()
        trustLocalUntil = SystemClock.elapsedRealtime() + TRUST_LOCAL_MS
        player?.notifyVolume()
        val v = remoteVolume
        send { it.volume(v) }
    }

    fun activeName(): String? = computer?.name

    // ---- Listening to the computer -------------------------------------------------

    private fun startPolling() {
        pollJob?.cancel()
        val c = client ?: return
        pollJob = scope.launch {
            while (isActive && client === c) {
                delay(nextPollDelay())
                val result = withContext(net) { runCatching { c.status() } }
                if (client !== c) break
                result.onSuccess { onStatus(it) }.onFailure { onFailure(it) }
            }
        }
    }

    /**
     * Twice a second while the computer answers. While it doesn't, the reads
     * back off (1, 2, then every 2 to 2.5 s, with a little jitter): no point
     * knocking twice a second on a computer that isn't there, and the phone's
     * radio gets to rest. Each failed read can also take up to the client's
     * 4 s timeout on its own.
     */
    private fun nextPollDelay(): Long {
        if (failures == 0) return POLL_MS
        val base = (POLL_MS shl failures.coerceAtMost(2)).coerceAtMost(MAX_POLL_BACKOFF_MS)
        return base + kotlin.random.Random.nextLong(base / 4 + 1)
    }

    private fun onFailure(e: Throwable) {
        val name = computer?.name ?: "the computer"
        if (e is ConnectSessionEnded) {
            // The computer let go of this session; carrying on would only fight it.
            end(
                error = when (e.reason) {
                    "replaced" -> "Another phone is playing on $name now. The music is back on this phone."
                    "stopped_on_computer" -> "Stopped on $name. The music is back on this phone."
                    else -> "Lost the connection to $name. The music is back on this phone."
                },
                tellComputer = false,
            )
            return
        }
        if (e is ConnectRefused) {
            computer?.let { pc -> savePaired(loadPaired().filter { it.computer.id != pc.id }) }
            end(error = "$name doesn't know this phone any more. Connect again to pair.", tellComputer = false)
            return
        }
        // A Wi-Fi hiccup is not the end: the computer keeps playing meanwhile. Only a
        // computer that stays silent for a while (switched off, out of range) ends it.
        failures++
        val now = SystemClock.elapsedRealtime()
        if (failingSince == 0L) failingSince = now
        if (failures >= MIN_FAILURES && now - failingSince >= GIVE_UP_AFTER_MS) {
            end(error = "Lost the connection to $name. The music is back on this phone.", tellComputer = false)
        }
    }

    private fun onStatus(status: RemoteStatus) {
        failures = 0
        failingSince = 0L
        val exo = exo ?: return
        val player = player ?: return
        val now = SystemClock.elapsedRealtime()

        // Gone on by itself to the song it was told comes next: the phone follows first,
        // so a next pressed after that on the computer moves on from there.
        val id = status.trackId
        if (id != null && id != sentId && id == advanceId) followComputer(id, status)

        // Next or previous pressed on the computer: the phone's queue decides what that is.
        for (request in status.requests) when (request) {
            RemoteRequest.NEXT -> if (exo.hasNextMediaItem()) player.seekToNextMediaItem()
            RemoteRequest.PREVIOUS -> player.seekToPrevious()
            // The new "up next" goes back to the computer by itself (see the listener).
            RemoteRequest.SHUFFLE -> _shuffleRequests.tryEmit(Unit)
            RemoteRequest.REPEAT -> player.repeatMode = when (exo.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
        if (status.requests.isNotEmpty()) return

        if (kotlin.math.abs(status.volume - remoteVolume) > 0.005f && now >= trustLocalUntil) {
            remoteVolume = status.volume
            player.notifyVolume()
        }

        // The computer let go of this phone's song (its own music took over, or it
        // restarted): it's paused as far as the phone goes, and play sends it again.
        if (status.state == RemoteState.IDLE && status.trackId == null && sentId != null && now >= trustLocalUntil) {
            if (remoteState != RemoteState.IDLE || wantPlay) {
                remoteState = RemoteState.IDLE
                wantPlay = false
                player.notifyPlayState()
                updateLocks()
            }
            return
        }

        // Still about the song before; the new one is on its way.
        if (status.trackId != sentId) return

        val before = remoteState
        when (status.state) {
            RemoteState.ENDED -> {
                remoteState = RemoteState.ENDED
                if (endHandledFor == sentId) return
                endHandledFor = sentId
                when {
                    exo.repeatMode == Player.REPEAT_MODE_ONE -> { exo.seekTo(0); sendCurrent(playing = true) }
                    exo.hasNextMediaItem() -> exo.seekToNextMediaItem()
                    else -> {
                        wantPlay = false
                        anchor(0)
                        exo.seekTo(0)
                        player.notifyPlayState()
                        updateLocks()
                    }
                }
                return
            }
            RemoteState.ERROR -> {
                remoteState = RemoteState.ERROR
                if (wantPlay) {
                    wantPlay = false
                    _state.update { it.copy(error = "${computer?.name ?: "The computer"} couldn't play this song${status.error?.let { e -> ": $e" } ?: "."}") }
                    player.notifyPlayState()
                    updateLocks()
                }
                return
            }
            else -> Unit
        }

        if (now < trustLocalUntil) {
            // A command is still on its way; only learn that the song has loaded.
            if (status.state != RemoteState.LOADING && before == RemoteState.LOADING) {
                remoteState = status.state
                player.notifyPlayState()
            }
            return
        }

        remoteState = status.state
        val remotePlaying = status.state == RemoteState.PLAYING || status.state == RemoteState.LOADING
        var changed = before != status.state
        if (status.state != RemoteState.LOADING && status.state != RemoteState.IDLE && remotePlaying != wantPlay) {
            // Paused or played on the computer itself.
            wantPlay = remotePlaying
            changed = true
            updateLocks()
        }
        if (status.state != RemoteState.LOADING) {
            val guess = remotePosition()
            anchor(status.positionMs)
            if (kotlin.math.abs(guess - status.positionMs) > DRIFT_MS) player.notifyPositionJump(guess, status.positionMs)
        }
        if (changed) player.notifyPlayState()
    }

    private fun anchor(positionMs: Long) {
        anchorMs = positionMs
        anchorAt = SystemClock.elapsedRealtime()
    }

    // ---- Keeping the phone awake while it serves the music --------------------------

    private fun holdLocks() {
        if (wifiLock == null) {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wifi?.createWifiLock(mode, "harmony:connect")?.apply { setReferenceCounted(false) }
        }
        if (wakeLock == null) {
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "harmony:connect")?.apply { setReferenceCounted(false) }
        }
        updateLocks()
    }

    /**
     * Held while the computer plays (it pulls the song from this phone as it
     * goes) and for a while after a pause, so a sleeping phone doesn't drop
     * off the Wi-Fi and miss the play that follows.
     */
    private fun updateLocks() {
        val now = SystemClock.elapsedRealtime()
        if (wantPlay || pausedAt == 0L) pausedAt = if (wantPlay) 0L else now
        val hold = isActive && (wantPlay || now - pausedAt < PAUSED_HOLD_MS)
        lockJob?.cancel()
        if (hold && !wantPlay) {
            // Let go once the pause has lasted long enough.
            lockJob = scope.launch {
                delay(PAUSED_HOLD_MS - (now - pausedAt) + 1_000)
                updateLocks()
            }
        }
        runCatching {
            if (hold) {
                wifiLock?.acquire()
                wakeLock?.acquire(LOCK_TIMEOUT_MS)
            } else {
                if (wifiLock?.isHeld == true) wifiLock?.release()
                if (wakeLock?.isHeld == true) wakeLock?.release()
            }
        }
    }

    private fun releaseLocks() {
        lockJob?.cancel()
        pausedAt = 0L
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
    }

    // ---- Paired computers ----------------------------------------------------------

    private data class Saved(val computer: ConnectComputer, val token: String)

    private fun loadPaired(): List<Saved> = runCatching {
        val array = JSONArray(prefs.getString(KEY_COMPUTERS, "[]"))
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            Saved(
                ConnectComputer(o.getString("id"), o.getString("name"), o.getString("host"), o.getInt("port"), paired = true, nearby = false),
                o.getString("token"),
            )
        }
    }.getOrDefault(emptyList())

    private fun savePaired(list: List<Saved>) {
        val array = JSONArray()
        list.forEach { s ->
            array.put(
                JSONObject().put("id", s.computer.id).put("name", s.computer.name).put("host", s.computer.host)
                    .put("port", s.computer.port).put("token", s.token),
            )
        }
        prefs.edit().putString(KEY_COMPUTERS, array.toString()).apply()
    }

    // ---- Paired computers, for "Send to phone" -------------------------------------

    override fun paired(): List<com.harmony.domain.playback.PairedComputer> = synchronized(pairedLock) {
        loadPaired().map { com.harmony.domain.playback.PairedComputer(it.computer.id, it.computer.name, it.computer.host, it.computer.port, it.token) }
    }

    override fun relocate(id: String): com.harmony.domain.playback.PairedComputer? {
        val found = runCatching { Discovery.find(SEARCH_MS) }.getOrDefault(emptyList()).firstOrNull { it.id == id } ?: return null
        return synchronized(pairedLock) {
            val saved = loadPaired()
            val updated = saved.map { s ->
                if (s.computer.id == id) s.copy(computer = s.computer.copy(host = found.host, port = found.port)) else s
            }
            if (updated != saved) savePaired(updated)
            updated.firstOrNull { it.computer.id == id }?.let {
                com.harmony.domain.playback.PairedComputer(it.computer.id, it.computer.name, it.computer.host, it.computer.port, it.token)
            }
        }
    }

    override val playingOnComputer: Boolean get() = isActive

    private val pairedLock = Any()

    /** The audio of [mediaId] on this phone, as the shared queue and the computer's cache name it. */
    private fun contentKey(mediaId: String) =
        if (com.harmony.domain.playback.RemoteSongs.isRemoteKey(mediaId)) mediaId else "dev:$deviceId:$mediaId"

    /** This phone, as the shared queue names the device a song lives on: random, kept for good. */
    val deviceId: String by lazy {
        prefs.getString(KEY_DEVICE_ID, null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }
    }

    private fun phoneName(): String {
        val model = Build.MODEL.orEmpty()
        val maker = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        return when {
            model.isBlank() -> "Phone"
            model.startsWith(maker, ignoreCase = true) || maker.isBlank() -> model
            else -> "$maker $model"
        }
    }

    /** This phone's address on the network that reaches [host]. Nothing is sent. */
    private fun localAddressToward(host: String): String = DatagramSocket().use { s ->
        s.connect(InetAddress.getByName(host), Connect.HTTP_PORT)
        s.localAddress?.hostAddress?.takeIf { it != "0.0.0.0" && it != "::" } ?: throw IOException("no route to $host")
    }

    private companion object {
        const val PREFS = "harmony_connect"
        const val KEY_COMPUTERS = "computers"
        const val KEY_DEVICE_ID = "device_id"
        const val SEARCH_MS = 1_800
        const val POLL_MS = 500L
        /** The longest pause between reads while the computer doesn't answer (plus up to a quarter of jitter). */
        const val MAX_POLL_BACKOFF_MS = 2_000L
        const val TRUST_LOCAL_MS = 1_500L
        const val DRIFT_MS = 1_500L
        const val MIN_FAILURES = 6
        /** How long the computer may stay silent before the music comes back to the phone. */
        const val GIVE_UP_AFTER_MS = 30_000L
        /** Locks kept this long into a pause. */
        const val PAUSED_HOLD_MS = 10 * 60 * 1000L
        /** What comes next, for the computer's queue (only the first can be played straight on). */
        const val UP_NEXT = 20
        const val COVERED_UP_NEXT = 8
        /** How many of "up next" the computer may fetch ahead of time (the next song and two more). */
        const val FETCHED_AHEAD = 3
        const val REFRESH_DELAY_MS = 400L
        /** Stage 7: how far ahead the taking-over player gets the song ready, and how often the handoff looks. */
        const val HANDOFF_LEAD_MS = 1_500L
        const val HANDOFF_TICK_MS = 20L
        const val HANDOFF_POLL_MS = 100L
        const val SEEK_SETTLE_MS = 150L
        const val TAG = "HarmonyConnect"
        const val VOLUME_STEPS = 25
        const val MAX_COVER_BYTES = 2_000_000
        const val LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
    }
}
