package com.harmony.desktop.player

import com.harmony.core.remote.ControlAction
import com.harmony.core.remote.ControlRequest
import com.harmony.core.remote.PlayRequest
import com.harmony.core.remote.RemoteRenderer
import com.harmony.core.remote.RemoteRequest
import com.harmony.core.remote.RemoteState
import com.harmony.core.remote.RemoteStatus
import com.harmony.core.remote.RemoteTrack
import com.harmony.desktop.engine.AudioEngine
import com.harmony.desktop.engine.EngineStatus
import com.harmony.desktop.library.LocalTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What's playing, wherever it came from, for the screen. */
data class NowPlaying(
    val key: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    /** A cover: a local file path for the library, a URL for the phone. */
    val art: Art?,
    val quality: String?,
    /** The phone it's coming from; null when it's the computer's own music. */
    val fromPhone: String? = null,
    val upNext: List<UpNext> = emptyList(),
)

sealed interface Art {
    data class Local(val track: LocalTrack) : Art
    data class Url(val url: String) : Art
}

data class UpNext(val title: String, val artist: String, val art: Art?, val queueIndex: Int?)

enum class Repeat { OFF, ALL, ONE }

/**
 * The computer's player: its own music from the library, or the phone's
 * through Harmony Connect ([RemoteRenderer]). One [AudioEngine] serves both.
 *
 * With the phone in charge the queue lives on the phone: next and previous
 * pressed here are passed to it as requests, and the end of a song is only
 * reported; the phone sends the next one.
 */
class PlayerController(private val engine: AudioEngine) : RemoteRenderer {
    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _queue = MutableStateFlow<List<LocalTrack>>(emptyList())
    val queue: StateFlow<List<LocalTrack>> = _queue.asStateFlow()
    private val _index = MutableStateFlow(-1)
    val index: StateFlow<Int> = _index.asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()
    private val _repeat = MutableStateFlow(Repeat.OFF)
    val repeat: StateFlow<Repeat> = _repeat.asStateFlow()

    /** The phone playing here now, if one is. */
    private val _phone = MutableStateFlow<String?>(null)
    val phone: StateFlow<String?> = _phone.asStateFlow()

    val engineState get() = engine.state

    private var remoteTrack: RemoteTrack? = null
    private val pending = mutableListOf<RemoteRequest>()
    /** The order songs play in when shuffling: positions in the queue. */
    private var order: List<Int> = emptyList()

    init {
        engine.onEnded = { onEnded() }
    }

    fun positionMs(): Long = engine.positionMs()

    // ---- The computer's own music -------------------------------------------

    fun playLocal(tracks: List<LocalTrack>, startIndex: Int) {
        if (tracks.isEmpty()) return
        leaveRemote()
        _queue.value = tracks
        order = makeOrder(tracks.size, startIndex.coerceIn(tracks.indices))
        startLocal(startIndex.coerceIn(tracks.indices), 0, play = true)
    }

    fun playQueueItem(i: Int) {
        if (_phone.value != null) return
        if (i in _queue.value.indices) startLocal(i, 0, play = true)
    }

    fun togglePlay() {
        when (engine.state.value.status) {
            EngineStatus.PLAYING, EngineStatus.LOADING -> engine.pause()
            EngineStatus.IDLE -> if (_index.value >= 0) startLocal(_index.value, 0, play = true)
            else -> engine.play()
        }
    }

    fun next() {
        if (_phone.value != null) return request(RemoteRequest.NEXT)
        val i = step(+1, wrap = _repeat.value != Repeat.OFF) ?: return
        startLocal(i, 0, play = true)
    }

    fun previous() {
        if (_phone.value != null) return request(RemoteRequest.PREVIOUS)
        // A few seconds in, previous goes back to the start of the song, as on the phone.
        if (engine.positionMs() > RESTART_AFTER_MS) return engine.seek(0)
        val i = step(-1, wrap = _repeat.value != Repeat.OFF) ?: return engine.seek(0)
        startLocal(i, 0, play = true)
    }

    fun seek(positionMs: Long) = engine.seek(positionMs.coerceAtLeast(0))

    val volume: Float get() = engine.volume

    fun toggleShuffle() {
        _shuffle.value = !_shuffle.value
        order = makeOrder(_queue.value.size, _index.value.coerceAtLeast(0))
        refreshUpNext()
    }

    fun cycleRepeat() {
        _repeat.value = when (_repeat.value) {
            Repeat.OFF -> Repeat.ALL
            Repeat.ALL -> Repeat.ONE
            Repeat.ONE -> Repeat.OFF
        }
        refreshUpNext()
    }

    private fun startLocal(i: Int, startMs: Long, play: Boolean) {
        val t = _queue.value.getOrNull(i) ?: return
        _index.value = i
        engine.load(t.path, startMs, play, t.durationMs)
        _nowPlaying.value = NowPlaying(
            key = t.path, title = t.title, artist = t.artist, album = t.album, durationMs = t.durationMs,
            art = Art.Local(t), quality = t.quality, fromPhone = null, upNext = localUpNext(i),
        )
    }

    private fun makeOrder(size: Int, first: Int): List<Int> {
        if (size == 0) return emptyList()
        return if (_shuffle.value) listOf(first) + (0 until size).filter { it != first }.shuffled() else (0 until size).toList()
    }

    /** The queue position [delta] steps along the play order from now, or null at an end. */
    private fun step(delta: Int, wrap: Boolean): Int? {
        val size = _queue.value.size
        if (size == 0) return null
        if (order.size != size) order = makeOrder(size, _index.value.coerceAtLeast(0))
        val at = order.indexOf(_index.value).coerceAtLeast(0)
        var next = at + delta
        if (next !in order.indices) {
            if (!wrap) return null
            next = (next + size) % size
        }
        return order[next]
    }

    private fun localUpNext(i: Int): List<UpNext> {
        val q = _queue.value
        if (order.size != q.size) order = makeOrder(q.size, i)
        val at = order.indexOf(i).coerceAtLeast(0)
        val after = order.drop(at + 1) + if (_repeat.value == Repeat.ALL) order.take(at) else emptyList()
        return after.take(UP_NEXT).map { qi -> UpNext(q[qi].title, q[qi].artist, Art.Local(q[qi]), qi) }
    }

    private fun refreshUpNext() {
        val np = _nowPlaying.value ?: return
        if (np.fromPhone == null && _index.value >= 0) _nowPlaying.value = np.copy(upNext = localUpNext(_index.value))
    }

    private fun onEnded() {
        if (_phone.value != null) return // the phone decides what's next
        if (_repeat.value == Repeat.ONE) return startLocal(_index.value, 0, play = true)
        val i = step(+1, wrap = _repeat.value == Repeat.ALL) ?: return
        startLocal(i, 0, play = true)
    }

    // ---- The phone's music (Harmony Connect) ----------------------------------

    @Synchronized
    override fun play(request: PlayRequest) {
        val t = request.track
        remoteTrack = t
        _phone.value = request.phoneName
        pending.clear()
        engine.load(t.url, request.positionMs, request.playing, t.durationMs)
        _nowPlaying.value = NowPlaying(
            key = "phone:" + t.id, title = t.title, artist = t.artist, album = t.album, durationMs = t.durationMs,
            art = t.artUrl?.let { Art.Url(it) }, quality = t.quality, fromPhone = request.phoneName,
            upNext = request.upNext.take(UP_NEXT).map { UpNext(it.title, it.artist, it.artUrl?.let { u -> Art.Url(u) }, null) },
        )
    }

    @Synchronized
    override fun control(request: ControlRequest) {
        if (_phone.value == null) return
        when (request.action) {
            ControlAction.PLAY -> {
                request.positionMs?.let { engine.seek(it) }
                engine.play()
            }
            ControlAction.PAUSE -> {
                engine.pause()
                request.positionMs?.let { engine.seek(it) }
            }
            ControlAction.SEEK -> request.positionMs?.let { engine.seek(it) }
            ControlAction.STOP -> leaveRemote()
        }
    }

    override fun setVolume(volume: Float) {
        engine.volume = volume
    }

    @Synchronized
    override fun status(): RemoteStatus {
        val track = remoteTrack
        if (_phone.value == null || track == null) {
            // The computer's own music took over (or nothing plays): the phone should let go.
            return RemoteStatus(RemoteState.IDLE, volume = engine.volume)
        }
        val s = engine.state.value
        val state = when (s.status) {
            EngineStatus.IDLE -> RemoteState.IDLE
            EngineStatus.LOADING -> RemoteState.LOADING
            EngineStatus.PLAYING -> RemoteState.PLAYING
            EngineStatus.PAUSED -> RemoteState.PAUSED
            EngineStatus.ENDED -> RemoteState.ENDED
            EngineStatus.ERROR -> RemoteState.ERROR
        }
        val requests = pending.toList()
        pending.clear()
        return RemoteStatus(state, track.id, engine.positionMs(), s.durationMs.takeIf { it > 0 } ?: track.durationMs, engine.volume, s.error, requests)
    }

    @Synchronized
    override fun disconnect(phoneName: String?) {
        if (_phone.value != null) leaveRemote()
    }

    @Synchronized
    private fun request(r: RemoteRequest) {
        pending += r
    }

    private fun leaveRemote() {
        if (_phone.value == null) return
        engine.stop()
        remoteTrack = null
        _phone.value = null
        pending.clear()
        _nowPlaying.value = _queue.value.getOrNull(_index.value)?.let { t ->
            NowPlaying(t.path, t.title, t.artist, t.album, t.durationMs, Art.Local(t), t.quality, null, localUpNext(_index.value))
        }
    }

    private companion object {
        const val UP_NEXT = 8
        const val RESTART_AFTER_MS = 3_000L
    }
}
