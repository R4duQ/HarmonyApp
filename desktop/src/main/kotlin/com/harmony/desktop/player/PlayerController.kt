package com.harmony.desktop.player

import com.harmony.core.remote.ControlAction
import com.harmony.core.remote.ControlRequest
import com.harmony.core.remote.PlayRequest
import com.harmony.core.remote.RemoteRenderer
import com.harmony.core.remote.RemoteRepeat
import com.harmony.core.remote.RemoteRequest
import com.harmony.core.remote.RemoteState
import com.harmony.core.remote.RemoteStatus
import com.harmony.core.remote.RemoteTrack
import com.harmony.desktop.connect.RemoteCache
import com.harmony.desktop.engine.AudioEngine
import com.harmony.desktop.engine.EngineStatus
import com.harmony.desktop.engine.Upcoming
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
 * With the phone in charge the queue lives on the phone. A phone that allows
 * it ([PlayRequest.autoAdvance]) lets the computer go straight on to the
 * song it said comes next, at the end of a song or when next is pressed
 * here, and then follows; otherwise next and previous are passed to the
 * phone as requests and the end of a song is only reported.
 *
 * The phone's songs are fetched whole in the background ([RemoteCache]), so
 * seeking and the next song don't wait on the Wi-Fi.
 */
class PlayerController(
    private val engine: AudioEngine,
    private val cache: RemoteCache? = null,
) : RemoteRenderer {
    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _queue = MutableStateFlow<List<LocalTrack>>(emptyList())
    val queue: StateFlow<List<LocalTrack>> = _queue.asStateFlow()
    private val _index = MutableStateFlow(-1)
    val index: StateFlow<Int> = _index.asStateFlow()

    /**
     * What the shuffle and repeat buttons show: the computer's own modes, or
     * the phone's while it plays here (it owns that queue).
     */
    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()
    private val _repeat = MutableStateFlow(Repeat.OFF)
    val repeat: StateFlow<Repeat> = _repeat.asStateFlow()

    /** The computer's own modes, for its own music. */
    private var localShuffle = false
    private var localRepeat = Repeat.OFF

    /** The phone's modes as it last said (null: a phone that doesn't say). */
    private var remoteShuffle: Boolean? = null
    private var remoteRepeat: RemoteRepeat? = null

    /** The phone playing here now, if one is. */
    private val _phone = MutableStateFlow<String?>(null)
    val phone: StateFlow<String?> = _phone.asStateFlow()

    val engineState get() = engine.state

    private var remoteTrack: RemoteTrack? = null
    private var remoteUpNext: List<RemoteTrack> = emptyList()
    private var autoAdvance = false

    /**
     * The phone's song when the computer's own music took over: the phone
     * still sees it, paused where it was, and play on the phone brings it back.
     */
    private data class SetAside(val phone: String, val track: RemoteTrack, val upNext: List<RemoteTrack>, val autoAdvance: Boolean, val positionMs: Long)
    private var setAside: SetAside? = null

    /** Tries left to get a broken-off phone song going again. */
    private var recoveries = 0
    private val pending = mutableListOf<RemoteRequest>()
    /** The order songs play in when shuffling: positions in the queue. */
    private var order: List<Int> = emptyList()

    /** Where the engine goes on to by itself: a place in the local queue, or the phone's next song. */
    private data class LocalNext(val index: Int)
    private data class RemoteNext(val track: RemoteTrack)

    init {
        engine.onEnded = { onEnded() }
        engine.upcoming = { upcoming() }
        engine.onAdvanced = { onAdvanced(it) }
        engine.onError = { source, at, _ -> onEngineError(source, at) }
    }

    fun positionMs(): Long = engine.positionMs()

    // ---- The computer's own music -------------------------------------------

    @Synchronized
    fun playLocal(tracks: List<LocalTrack>, startIndex: Int) {
        if (tracks.isEmpty()) return
        leaveRemote()
        _queue.value = tracks
        order = makeOrder(tracks.size, startIndex.coerceIn(tracks.indices))
        startLocal(startIndex.coerceIn(tracks.indices), 0, play = true)
    }

    @Synchronized
    fun playQueueItem(i: Int) {
        if (_phone.value != null) return
        if (i in _queue.value.indices) startLocal(i, 0, play = true)
    }

    @Synchronized
    fun togglePlay() {
        if (_phone.value != null) {
            if (resumeFromCopy()) return
            // Broken off and not recovered: try again from where it stopped.
            val t = remoteTrack
            if (t != null && engine.state.value.status == EngineStatus.ERROR) {
                return engine.load(sourceFor(t), engine.positionMs(), play = true, t.durationMs)
            }
        }
        when (engine.state.value.status) {
            EngineStatus.PLAYING, EngineStatus.LOADING -> engine.pause()
            EngineStatus.IDLE -> if (_index.value >= 0) startLocal(_index.value, 0, play = true)
            else -> engine.play()
        }
    }

    /** Stops: paused, back at the start of the song. */
    @Synchronized
    fun stop() {
        engine.pause()
        seek(0)
    }

    @Synchronized
    fun next() {
        if (_phone.value != null) {
            // Straight on to the phone's next song when it allows it; the phone follows.
            val up = remoteUpNext.firstOrNull()?.takeIf { autoAdvance && it.url.isNotBlank() }
            if (up != null) return startRemote(up, remoteUpNext.drop(1), 0, play = true)
            return request(RemoteRequest.NEXT)
        }
        val i = step(+1, wrap = localRepeat != Repeat.OFF) ?: return
        startLocal(i, 0, play = true)
    }

    @Synchronized
    fun previous() {
        // A few seconds in, previous goes back to the start of the song, as on the phone.
        if (engine.positionMs() > RESTART_AFTER_MS) return seek(0)
        if (_phone.value != null) return request(RemoteRequest.PREVIOUS)
        val i = step(-1, wrap = localRepeat != Repeat.OFF) ?: return engine.seek(0)
        startLocal(i, 0, play = true)
    }

    @Synchronized
    fun seek(positionMs: Long) {
        val pos = positionMs.coerceAtLeast(0)
        val t = remoteTrack
        if (_phone.value != null && t != null) {
            // From the fetched copy once there is one: no waiting on the phone.
            val s = engine.state.value
            val playing = s.status == EngineStatus.PLAYING || s.status == EngineStatus.LOADING
            engine.load(sourceFor(t), pos, playing, s.durationMs.takeIf { it > 0 } ?: t.durationMs)
        } else {
            engine.seek(pos)
        }
    }

    val volume: Float get() = engine.volume

    @Synchronized
    fun toggleShuffle() {
        if (_phone.value != null) {
            // The phone's queue: the phone shuffles it and sends the new "up next".
            remoteShuffle = remoteShuffle?.not()
            request(RemoteRequest.SHUFFLE)
            return publishModes()
        }
        localShuffle = !localShuffle
        order = makeOrder(_queue.value.size, _index.value.coerceAtLeast(0))
        publishModes()
        refreshUpNext()
    }

    @Synchronized
    fun cycleRepeat() {
        if (_phone.value != null) {
            remoteRepeat = remoteRepeat?.let { RemoteRepeat.entries[(it.ordinal + 1) % RemoteRepeat.entries.size] }
            request(RemoteRequest.REPEAT)
            return publishModes()
        }
        localRepeat = when (localRepeat) {
            Repeat.OFF -> Repeat.ALL
            Repeat.ALL -> Repeat.ONE
            Repeat.ONE -> Repeat.OFF
        }
        publishModes()
        refreshUpNext()
    }

    private fun publishModes() {
        val phone = _phone.value != null
        _shuffle.value = remoteShuffle?.takeIf { phone } ?: localShuffle
        _repeat.value = remoteRepeat?.takeIf { phone }?.let {
            when (it) {
                RemoteRepeat.OFF -> Repeat.OFF
                RemoteRepeat.ALL -> Repeat.ALL
                RemoteRepeat.ONE -> Repeat.ONE
            }
        } ?: localRepeat
    }

    private fun startLocal(i: Int, startMs: Long, play: Boolean) {
        val t = _queue.value.getOrNull(i) ?: return
        _index.value = i
        engine.load(t.path, startMs, play, t.durationMs)
        _nowPlaying.value = localNowPlaying(t, i)
    }

    private fun makeOrder(size: Int, first: Int): List<Int> {
        if (size == 0) return emptyList()
        return if (localShuffle) listOf(first) + (0 until size).filter { it != first }.shuffled() else (0 until size).toList()
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
        val after = order.drop(at + 1) + if (localRepeat == Repeat.ALL) order.take(at) else emptyList()
        return after.take(UP_NEXT).map { qi -> UpNext(q[qi].title, q[qi].artist, Art.Local(q[qi]), qi) }
    }

    private fun refreshUpNext() {
        val np = _nowPlaying.value ?: return
        if (np.fromPhone == null && _index.value >= 0) _nowPlaying.value = np.copy(upNext = localUpNext(_index.value))
    }

    @Synchronized
    private fun onEnded() {
        if (_phone.value != null) return // the phone decides what's next
        // Normally the engine has gone on by itself ([upcoming]); this is when that couldn't start.
        if (localRepeat == Repeat.ONE) return startLocal(_index.value, 0, play = true)
        val i = step(+1, wrap = localRepeat == Repeat.ALL) ?: return
        startLocal(i, 0, play = true)
    }

    /** What the engine plays after the current song, without a gap. */
    @Synchronized
    private fun upcoming(): Upcoming? {
        if (_phone.value != null) {
            val up = remoteUpNext.firstOrNull()?.takeIf { autoAdvance && it.url.isNotBlank() } ?: return null
            return Upcoming(sourceFor(up), up.durationMs, RemoteNext(up))
        }
        if (_index.value < 0) return null
        val i = if (localRepeat == Repeat.ONE) _index.value else step(+1, wrap = localRepeat == Repeat.ALL) ?: return null
        val t = _queue.value.getOrNull(i) ?: return null
        return Upcoming(t.path, t.durationMs, LocalNext(i))
    }

    @Synchronized
    private fun onAdvanced(up: Upcoming) {
        when (val tag = up.tag) {
            is LocalNext -> {
                val t = _queue.value.getOrNull(tag.index) ?: return
                _index.value = tag.index
                _nowPlaying.value = localNowPlaying(t, tag.index)
            }
            is RemoteNext -> {
                if (_phone.value == null) return
                remoteTrack = tag.track
                remoteUpNext = remoteUpNext.drop(1)
                showRemote(tag.track)
                fetch()
                learnAbout(tag.track)
            }
        }
    }

    private fun localNowPlaying(t: LocalTrack, i: Int) = NowPlaying(
        key = t.path, title = t.title, artist = t.artist, album = t.album, durationMs = t.durationMs,
        art = Art.Local(t), quality = t.quality, fromPhone = null, upNext = localUpNext(i),
    )

    // ---- The phone's music (Harmony Connect) ----------------------------------

    @Synchronized
    override fun play(request: PlayRequest) {
        val t = request.track
        val same = _phone.value != null && remoteTrack?.id == t.id &&
            engine.state.value.status.let { it == EngineStatus.PLAYING || it == EngineStatus.PAUSED || it == EngineStatus.LOADING }
        _phone.value = request.phoneName
        autoAdvance = request.autoAdvance
        remoteShuffle = request.shuffle
        remoteRepeat = request.repeat
        publishModes()
        if (request.followUp && same) {
            // The phone catching up with what plays here, or a new "up next": carry on as is,
            // keeping what was learned about the song here.
            val known = remoteTrack
            remoteTrack = t.copy(
                durationMs = t.durationMs.takeIf { it > 0 } ?: known?.durationMs ?: 0,
                quality = t.quality ?: known?.quality,
            )
            remoteUpNext = request.upNext
            if (request.playing) engine.play() else engine.pause()
            remoteTrack?.let(::showRemote)
            fetch()
            return
        }
        if (request.followUp && remoteTrack?.id == t.id && engine.state.value.status == EngineStatus.ENDED) {
            // Only a new "up next" for a song that has finished here: nothing to restart.
            remoteUpNext = request.upNext
            showRemote(t)
            fetch()
            return
        }
        pending.clear()
        startRemote(t, request.upNext, request.positionMs, request.playing)
    }

    /**
     * Playing (or paused) from the phone over the network while the whole
     * song is already here: carry on from the copy, which no Wi-Fi drop or
     * sleeping phone can interrupt. True when it did.
     */
    private fun resumeFromCopy(): Boolean {
        val t = remoteTrack ?: return false
        val local = cache?.localFor(t.url) ?: return false
        val s = engine.state.value
        if (s.source == local || s.status != EngineStatus.PAUSED) return false
        engine.load(local, engine.positionMs(), play = true, s.durationMs.takeIf { it > 0 } ?: t.durationMs)
        return true
    }

    /** A phone song broke off: start it again where it stopped, from the copy if there is one. */
    @Synchronized
    private fun onEngineError(source: String, at: Long) {
        val t = remoteTrack ?: return
        if (_phone.value == null || (source != t.url && source != cache?.localFor(t.url))) return
        if (recoveries >= MAX_RECOVERIES) return
        recoveries++
        val attempt = recoveries
        Thread({
            Thread.sleep(RECOVERY_DELAY_MS * attempt)
            synchronized(this) {
                // Only if nothing else happened meanwhile.
                if (remoteTrack?.id != t.id || engine.state.value.status != EngineStatus.ERROR) return@synchronized
                engine.load(sourceFor(t), at, play = true, t.durationMs)
            }
        }, "harmony-recover").apply { isDaemon = true }.start()
    }

    private fun startRemote(t: RemoteTrack, upNext: List<RemoteTrack>, positionMs: Long, play: Boolean) {
        if (remoteTrack?.id != t.id) recoveries = 0
        setAside = null
        remoteTrack = t
        remoteUpNext = upNext
        engine.load(sourceFor(t), positionMs, play, t.durationMs)
        showRemote(t)
        fetch()
        learnAbout(t)
    }

    /**
     * A phone song sent without its length (or quality): ask ffprobe, from the
     * copy if there is one, and fill them in, so the progress bar works and the
     * next song can be made ready in time.
     */
    private fun learnAbout(t: RemoteTrack) {
        if (t.durationMs > 0 && t.quality != null) return
        val source = sourceFor(t)
        Thread({
            val probed = runCatching { com.harmony.desktop.library.Ffprobe.probe(source) }.getOrNull() ?: return@Thread
            synchronized(this) {
                val now = remoteTrack ?: return@synchronized
                if (now.id != t.id) return@synchronized
                val better = now.copy(
                    durationMs = now.durationMs.takeIf { it > 0 } ?: probed.durationMs,
                    quality = now.quality ?: probed.quality,
                )
                remoteTrack = better
                engine.setDuration(engine.state.value.source ?: source, better.durationMs)
                showRemote(better)
            }
        }, "harmony-probe").apply { isDaemon = true }.start()
    }

    private fun showRemote(t: RemoteTrack) {
        val phone = _phone.value ?: return
        _nowPlaying.value = NowPlaying(
            key = "phone:" + t.id, title = t.title, artist = t.artist, album = t.album, durationMs = t.durationMs,
            art = t.artUrl?.let { Art.Url(it) }, quality = t.quality, fromPhone = phone,
            upNext = remoteUpNext.take(UP_NEXT).map { UpNext(it.title, it.artist, it.artUrl?.let { u -> Art.Url(u) }, null) },
        )
    }

    /** The fetched copy when it's complete, else straight from the phone. */
    private fun sourceFor(t: RemoteTrack): String = cache?.localFor(t.url) ?: t.url

    /** Fetches the song playing, then the next, and forgets the rest. */
    private fun fetch() {
        val c = cache ?: return
        c.keep(listOfNotNull(remoteTrack?.url, remoteUpNext.firstOrNull()?.url?.takeIf { autoAdvance }))
    }

    @Synchronized
    override fun control(request: ControlRequest) {
        if (_phone.value == null) {
            // The computer's own music took over; play on the phone brings its song back.
            val aside = setAside ?: return
            when (request.action) {
                ControlAction.PLAY -> {
                    _phone.value = aside.phone
                    autoAdvance = aside.autoAdvance
                    startRemote(aside.track, aside.upNext, request.positionMs ?: aside.positionMs, play = true)
                }
                ControlAction.SEEK -> request.positionMs?.let { setAside = aside.copy(positionMs = it) }
                else -> {}
            }
            return
        }
        when (request.action) {
            ControlAction.PLAY -> {
                request.positionMs?.let { seek(it) }
                if (!resumeFromCopy()) engine.play()
            }
            ControlAction.PAUSE -> {
                engine.pause()
                request.positionMs?.let { seek(it) }
            }
            ControlAction.SEEK -> request.positionMs?.let { seek(it) }
            ControlAction.STOP -> {
                leaveRemote()
                setAside = null
            }
        }
    }

    override fun setVolume(volume: Float) {
        engine.volume = volume
    }

    @Synchronized
    override fun status(): RemoteStatus {
        val track = remoteTrack
        if (_phone.value == null || track == null) {
            // The computer's own music took over: to the phone its song is paused, where it was.
            val aside = setAside
            if (aside != null) return RemoteStatus(RemoteState.PAUSED, aside.track.id, aside.positionMs, aside.track.durationMs, engine.volume)
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
        // The phone let go: nothing to bring back.
        setAside = null
    }

    @Synchronized
    private fun request(r: RemoteRequest) {
        pending += r
    }

    private fun leaveRemote() {
        val phone = _phone.value ?: return
        remoteTrack?.let { setAside = SetAside(phone, it, remoteUpNext, autoAdvance, engine.positionMs()) }
        engine.stop()
        remoteTrack = null
        remoteUpNext = emptyList()
        autoAdvance = false
        _phone.value = null
        remoteShuffle = null
        remoteRepeat = null
        publishModes()
        pending.clear()
        cache?.clear()
        _nowPlaying.value = _queue.value.getOrNull(_index.value)?.let { t -> localNowPlaying(t, _index.value) }
    }

    private companion object {
        const val UP_NEXT = 8
        const val RESTART_AFTER_MS = 3_000L
        const val MAX_RECOVERIES = 3
        const val RECOVERY_DELAY_MS = 1_500L
    }
}
