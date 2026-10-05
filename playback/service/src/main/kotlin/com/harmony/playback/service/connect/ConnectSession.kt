package com.harmony.playback.service.connect

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.harmony.core.media.artwork.ArtworkCache
import com.harmony.core.remote.Connect
import com.harmony.core.remote.ConnectClient
import com.harmony.core.remote.ConnectRefused
import com.harmony.core.remote.ControlAction
import com.harmony.core.remote.ControlRequest
import com.harmony.core.remote.Discovery
import com.harmony.core.remote.PlayRequest
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
import kotlinx.coroutines.flow.MutableStateFlow
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
) : ConnectController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val net = Executors.newSingleThreadExecutor { Thread(it, "harmony-connect").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val files = PhoneFileServer(context)

    private val _state = MutableStateFlow(ConnectState(computers = loadPaired().map { it.computer }))
    override val state: StateFlow<ConnectState> = _state

    private var exo: ExoPlayer? = null
    private var player: ConnectPlayer? = null

    private var client: ConnectClient? = null
    private var computer: ConnectComputer? = null

    /** The song the computer was last told to play. */
    private var sentId: String? = null
    private var endHandledFor: String? = null

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
    private var pollJob: Job? = null
    private var searchJob: Job? = null

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    val isActive: Boolean get() = client != null

    private val exoListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!isActive || mediaItem == null) return
            // Another song on the phone's queue (skip, a tap in the queue, a new
            // album): the computer plays that one now.
            anchor(exo?.currentPosition ?: 0)
            sendCurrent()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!isActive || !playWhenReady) return
            // Something told the phone's own player to play (the app picking a
            // new queue, Android Auto): the computer plays instead.
            exo?.playWhenReady = false
            remotePlay()
        }
    }

    // ---- Wiring ------------------------------------------------------------------

    /** Called by the playback service once its player exists. */
    fun attach(exo: ExoPlayer, player: ConnectPlayer) {
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
        if (isActive) end(error = null, tellComputer = true)
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
        val previous = client
        val item = exo.currentMediaItem
        val position = if (previous != null) remotePosition() else exo.currentPosition.coerceAtLeast(0)
        val playing = if (previous != null) wantPlay else exo.playWhenReady
        val upNext = upNextItems(exo)
        val duration = exo.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        _state.update { it.copy(busyWith = target.id, error = null, needsCode = null) }
        scope.launch {
            val fresh = ConnectClient(target.host, target.port, token)
            val result = withContext(net) {
                runCatching {
                    previous?.disconnect()
                    files.start()
                    if (item != null) fresh.play(playRequest(fresh, item, position, playing, duration, upNext)) else fresh.status()
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
            computer = target
            sentId = item?.mediaId
            endHandledFor = null
            remoteState = result.getOrNull()?.state ?: RemoteState.LOADING
            wantPlay = playing && item != null
            failures = 0
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
    private fun end(error: String?, tellComputer: Boolean) {
        val old = client ?: return
        val position = remotePosition()
        val same = exo?.currentMediaItem?.mediaId == sentId
        pollJob?.cancel()
        pollJob = null
        client = null
        computer = null
        sentId = null
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

    // ---- What the computer is told -------------------------------------------------

    private fun sendCurrent(playing: Boolean = wantPlay) {
        val exo = exo ?: return
        val c = client ?: return
        val item = exo.currentMediaItem ?: return
        val position = exo.currentPosition.coerceAtLeast(0)
        val duration = exo.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        val upNext = upNextItems(exo)
        sentId = item.mediaId
        endHandledFor = null
        remoteState = RemoteState.LOADING
        wantPlay = playing
        anchor(position)
        trustLocalUntil = SystemClock.elapsedRealtime() + TRUST_LOCAL_MS
        player?.notifyPlayState()
        updateLocks()
        send { c.play(playRequest(c, item, position, playing, duration, upNext)) }
    }

    /** Runs on the network thread. */
    private fun playRequest(c: ConnectClient, item: MediaItem, positionMs: Long, playing: Boolean, durationMs: Long, upNext: List<MediaItem>): PlayRequest {
        val uri = item.localConfiguration?.uri ?: throw IOException("nothing to play")
        val host = localAddressToward(c.host)
        val (url, art) = files.share(item.mediaId, uri, coverFor(item), host)
        val md = item.mediaMetadata
        val track = RemoteTrack(
            id = item.mediaId,
            title = md.title?.toString() ?: uri.lastPathSegment ?: "Unknown",
            artist = md.artist?.toString() ?: "",
            album = md.albumTitle?.toString() ?: "",
            durationMs = durationMs,
            url = url,
            artUrl = art,
        )
        val next = upNext.map {
            val m = it.mediaMetadata
            RemoteTrack(it.mediaId, m.title?.toString() ?: "", m.artist?.toString() ?: "", m.albumTitle?.toString() ?: "", 0, "")
        }
        return PlayRequest(track, positionMs, playing, phoneName(), next)
    }

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

    private fun upNextItems(exo: ExoPlayer): List<MediaItem> {
        val timeline = exo.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val out = ArrayList<MediaItem>()
        var index = exo.currentMediaItemIndex
        val seen = HashSet<Int>().apply { add(index) }
        while (out.size < UP_NEXT) {
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, exo.shuffleModeEnabled)
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
                delay(POLL_MS)
                val result = withContext(net) { runCatching { c.status() } }
                if (client !== c) break
                result.onSuccess { onStatus(it) }.onFailure { onFailure(it) }
            }
        }
    }

    private fun onFailure(e: Throwable) {
        val name = computer?.name ?: "the computer"
        if (e is ConnectRefused) {
            computer?.let { pc -> savePaired(loadPaired().filter { it.computer.id != pc.id }) }
            end(error = "$name doesn't know this phone any more. Connect again to pair.", tellComputer = false)
            return
        }
        failures++
        if (failures >= MAX_FAILURES) end(error = "Lost the connection to $name. The music is back on this phone.", tellComputer = false)
    }

    private fun onStatus(status: RemoteStatus) {
        failures = 0
        val exo = exo ?: return
        val player = player ?: return
        val now = SystemClock.elapsedRealtime()

        // Next or previous pressed on the computer: the phone's queue decides what that is.
        for (request in status.requests) when (request) {
            RemoteRequest.NEXT -> if (exo.hasNextMediaItem()) player.seekToNextMediaItem()
            RemoteRequest.PREVIOUS -> player.seekToPrevious()
        }
        if (status.requests.isNotEmpty()) return

        if (kotlin.math.abs(status.volume - remoteVolume) > 0.005f && now >= trustLocalUntil) {
            remoteVolume = status.volume
            player.notifyVolume()
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

    /** Held while the computer plays: it pulls the song from this phone as it goes. */
    private fun updateLocks() {
        val hold = isActive && wantPlay
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
        const val SEARCH_MS = 1_800
        const val POLL_MS = 500L
        const val TRUST_LOCAL_MS = 1_500L
        const val DRIFT_MS = 1_500L
        const val MAX_FAILURES = 8
        const val UP_NEXT = 3
        const val VOLUME_STEPS = 25
        const val MAX_COVER_BYTES = 2_000_000
        const val LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
    }
}
