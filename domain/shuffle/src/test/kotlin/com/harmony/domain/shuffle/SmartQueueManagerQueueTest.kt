package com.harmony.domain.shuffle

import com.harmony.core.model.AuditCheckKind
import com.harmony.core.model.AuditCheckResult
import com.harmony.core.model.AuditStatus
import com.harmony.core.model.BrainAuditReport
import com.harmony.core.model.EqSettings
import com.harmony.core.model.PlayerState
import com.harmony.core.model.QueueOrigin
import com.harmony.core.model.ReasonTag
import com.harmony.core.model.RepeatMode
import com.harmony.core.model.ReplayGainMode
import com.harmony.core.model.SessionEvent
import com.harmony.core.model.ShuffleMode
import com.harmony.core.model.Song
import com.harmony.core.model.TrackDecisionTrace
import com.harmony.core.model.VectorQuality
import com.harmony.domain.library.repository.LibraryRepository
import com.harmony.domain.library.repository.PlaybackHistoryRepository
import com.harmony.domain.playback.PlayOutcome
import com.harmony.domain.playback.PlaybackController
import com.harmony.domain.playback.QueueMoves
import com.harmony.domain.playback.QueueShadow
import com.harmony.domain.shuffle.brain.QueuePlan
import com.harmony.domain.shuffle.brain.QueueRequest
import com.harmony.domain.shuffle.brain.ShuffleBrain
import com.harmony.domain.shuffle.brain.ShuffleBrainLog
import com.harmony.domain.shuffle.model.ShuffleConfig
import com.harmony.domain.shuffle.model.SmartShuffleStyle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

private fun song(id: Long) = Song(id, "u$id", "t$id", "a${id % 7}", "al", 1, null, null, null, null, null, null,
    1000, null, null, null, null, null, null, null, null)

/**
 * Minimal ExoPlayer-like queue: index-based, play-next run anchored to the
 * current song, each entry's provenance kept as PlaybackConnection keeps it.
 */
private class FakePlayback : PlaybackController {
    val q = mutableListOf<Song>()
    val shadow = QueueShadow()
    var idx = 0
    var mode = ShuffleMode.OFF
    var generation = 0
    /** Every queue write, to prove which operations were used. */
    val writes = mutableListOf<String>()
    private var anchor: Long? = null
    private var run = 0
    private val _s = MutableStateFlow(PlayerState())
    override val playerState: StateFlow<PlayerState> = _s
    val outcomes = MutableSharedFlow<PlayOutcome>(extraBufferCapacity = 8)
    override val playOutcomes: Flow<PlayOutcome> = outcomes

    fun sync(automatic: Boolean = false) {
        val cur = q.getOrNull(idx)
        val pn = if (cur?.id == anchor) run.coerceIn(0, (q.size - idx - 1).coerceAtLeast(0)) else 0
        _s.value = PlayerState(currentSong = cur, queue = q.toList(), queueIndex = idx, shuffleMode = mode,
            playNextCount = pn, advancedAutomatically = automatic, queueGeneration = generation,
            entries = shadow.align(q.map { it.id.toString() }))
    }

    /** Skips to the next entry after [heardFraction] of the song, as the real player reports it. */
    suspend fun skipAfter(heardFraction: Float) {
        val left = q[idx]
        idx++
        if (anchor == left.id && run > 0) { anchor = q.getOrNull(idx)?.id; run-- }
        sync()
        outcomes.emit(PlayOutcome(left.id, (heardFraction * 1000).toLong(), 1000))
    }

    override suspend fun setQueue(songs: List<Song>, startIndex: Int, playWhenReady: Boolean) {
        writes += "set"
        val start = if (songs.isEmpty()) 0 else startIndex.coerceIn(songs.indices)
        val kept = shadow.startList(songs.map { it.id.toString() }, start, idx).map { q[it] }
        val cut = if (songs.isEmpty()) 0 else start + 1
        q.clear(); q += songs.subList(0, cut) + kept + songs.subList(cut, songs.size)
        idx = start; anchor = null; run = 0; generation++; sync()
    }
    override suspend fun addToQueue(song: Song, origin: QueueOrigin) {
        writes += "add"; shadow.insert(q.size, listOf(song.id.toString()), origin); q += song; sync()
    }
    override suspend fun addToQueueAll(songs: List<Song>, origin: QueueOrigin) {
        writes += "add"; shadow.insert(q.size, songs.map { it.id.toString() }, origin); q += songs; sync()
    }
    override suspend fun replaceQueueRange(fromIndex: Int, toIndexExclusive: Int, songs: List<Song>, origin: QueueOrigin) {
        writes += "replace $fromIndex"
        val from = fromIndex.coerceIn(0, q.size); val to = toIndexExclusive.coerceIn(from, q.size)
        shadow.replace(from, to, songs.map { it.id.toString() }, origin)
        repeat(to - from) { q.removeAt(from) }
        q.addAll(from, songs); sync()
    }
    override suspend fun addNext(song: Song) {
        val cur = q[idx].id
        if (cur != anchor) { anchor = cur; run = 0 }
        run++
        val at = (idx + run).coerceAtMost(q.size)
        shadow.insert(at, listOf(song.id.toString()), QueueOrigin.MANUAL)
        q.add(at, song); sync()
    }
    override suspend fun removeFromQueue(index: Int) {
        writes += "remove1"; shadow.remove(index, index + 1); q.removeAt(index); sync()
    }
    override suspend fun removeQueueRange(fromIndex: Int, toIndexExclusive: Int) {
        writes += "remove $fromIndex"
        val from = fromIndex.coerceIn(0, q.size); val to = toIndexExclusive.coerceIn(from, q.size)
        if (to <= idx) idx -= to - from
        shadow.remove(from, to)
        repeat(to - from) { q.removeAt(from) }; sync()
    }
    /** What the screen shows of each song still to come: its origin, and P when the user moved it. */
    fun origins(): List<String> = playerState.value.entries.drop(idx + 1).map { it.origin.name[0] + if (it.pinned) "P" else "" }
    /** As PlaybackConnection: checked by QueueMoves, the play-next run following its entries. */
    override suspend fun moveQueueItem(from: Int, to: Int, expectedSongId: Long?): Boolean {
        val move = QueueMoves.resolve(q.map { it.id.toString() }, idx, from, to, expectedSongId?.toString()) ?: return false
        if (q.getOrNull(idx)?.id == anchor) {
            run = QueueMoves.runAfterMove(idx, run.coerceIn(0, (q.size - idx - 1).coerceAtLeast(0)), move.from, move.to)
        }
        writes += "move"
        shadow.move(move.from, move.to)
        q.add(move.to, q.removeAt(move.from)); sync()
        return true
    }
    override fun play() {}
    override fun pause() {}
    override fun seekTo(positionMs: Long) {}
    override fun skipToNext() {}
    /** seekToPrevious: back one entry, reported as moving backward. */
    override fun skipToPrevious() {
        val left = q[idx]
        if (idx > 0) idx--
        anchor = null; run = 0
        sync()
        outcomes.tryEmit(PlayOutcome(left.id, 20, 1000, movedBackward = true))
    }
    /** seekTo(index, 0) after 5% of the song: a jump forward reads as a fast skip, as on the real player. */
    override fun skipToQueueItem(index: Int) {
        val left = q[idx]
        val back = index < idx
        idx = index
        anchor = null; run = 0
        sync()
        outcomes.tryEmit(PlayOutcome(left.id, 50, 1000, movedBackward = back))
    }
    override fun setRepeatMode(mode: RepeatMode) {}
    override fun setShuffleMode(mode: ShuffleMode) { this.mode = mode; sync() }
    override fun setPlaybackSpeed(speed: Float) {}
    override fun setPitchCorrection(enabled: Boolean) {}
    override fun setCrossfade(seconds: Int) {}
    override fun setReplayGainMode(mode: ReplayGainMode) {}
    override fun setEqualizer(settings: EqSettings) {}
    override fun setSleepTimer(durationMs: Long?, finishTrack: Boolean) {}
}

/** Picks the lowest free ids of its pool, and records what it was told. */
private class FakeBrain(private val pool: LongRange, override val available: Boolean = true) : ShuffleBrain {
    val requests = mutableListOf<QueueRequest>()
    val outcomes = mutableListOf<Pair<Long, Float>>()
    val manual = mutableListOf<Long>()
    val started = mutableListOf<Long>()
    var resets = 0
    var configs = mutableListOf<ShuffleConfig>()
    var journeyCalls = 0
    var journeyEnds = Long.MAX_VALUE
    private var session: FloatArray? = null

    override suspend fun prepare() {}
    override suspend fun applyConfig(config: ShuffleConfig) { configs += config }
    override suspend fun onTrackStarted(songId: Long): SessionEvent {
        started += songId
        if (session == null) { session = FloatArray(24) { if (it == 1) 1f else 0f }; return SessionEvent.SEEDED }
        return SessionEvent.NONE
    }
    override suspend fun onPlaybackOutcome(songId: Long, fraction: Float, atMillis: Long, shufflePick: Boolean): SessionEvent {
        outcomes += songId to fraction
        return when {
            fraction > 0.5f -> SessionEvent.PLAY_EMA
            fraction < 0.2f -> SessionEvent.FAST_SKIP
            else -> SessionEvent.NEUTRAL
        }
    }
    override suspend fun onManualSelect(songId: Long): SessionEvent { manual += songId; return SessionEvent.MANUAL_ANCHOR }
    override suspend fun resetSession() { resets++; session = null }
    override suspend fun generate(request: QueueRequest): QueuePlan {
        requests += request
        val ids = pool.filter { it !in request.excludeIds && it !in request.prefixIds }.take(request.count)
        return QueuePlan(ids, ids.map { trace(it, ReasonTag.VIBE_MATCH, "90% Bass/Vibe Match") }, audit(requests.size))
    }
    override suspend fun journeyStep(fromId: Long, toId: Long, t: Float, previousId: Long, exclude: Set<Long>): QueuePlan {
        journeyCalls++
        if (journeyCalls > journeyEnds) return QueuePlan.EMPTY
        val id = pool.first { it !in exclude }
        return QueuePlan(listOf(id), listOf(trace(id, ReasonTag.SONIC_ADVENTURE, "Sonic Adventure")), audit(journeyCalls))
    }
    override suspend fun sessionVector(): FloatArray? = session
    override suspend fun librarySize(): Int = pool.count()
    override suspend fun runSelfTest(): String = "[PASS] fake\nRESULT: PASS (1/1 checks)"

    private fun trace(id: Long, reason: ReasonTag, tag: String) = TrackDecisionTrace(
        id, 1f, 70f, 10f, 10f, 10f, 1f, 0.9f, 0.8f, 1, VectorQuality.REAL, reason, tag,
    )

    private fun audit(generation: Int) = BrainAuditReport(
        generation = generation,
        checks = AuditCheckKind.entries.map { AuditCheckResult(it, AuditStatus.PASS, 1f, 1f, 0, "ok") },
        afterFastSkip = false, averagePairwiseSimilarity = 0.4f, artistEntropyBits = 2f, zoneEntropyBits = 1f,
        sessionNorm = 1f, nonFiniteValues = 0, realVectorPct = 100f, realTracks = 100, fallbackTracks = 0,
        placeholderTracks = 0, exploreBoost = 1f, elapsedMs = 0.2f,
    )
}

@Suppress("UNCHECKED_CAST")
private inline fun <reified T> fake(noinline h: (String, Array<Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, args ->
        h(m.name, args ?: emptyArray())
    } as T

/**
 * Queue behaviour of Smart Shuffle, driven through the real SmartQueueManager
 * against an in-memory player and a fake brain.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmartQueueManagerQueueTest {
    private val pickPool = (100L..199L)
    private val randomPool = (300L..399L)
    private val logged = mutableListOf<String>()

    private fun build(scope: TestScope, brain: FakeBrain = FakeBrain(pickPool)): Triple<FakePlayback, SmartQueueManager, FakeBrain> {
        val playback = FakePlayback()
        val library = fake<LibraryRepository> { name, a ->
            when (name) {
                "randomSongId" -> { val ex = a[0] as Set<*>; randomPool.first { it !in ex } }
                "songById" -> song(a[0] as Long)
                "songsByIds" -> (a[0] as List<*>).map { song(it as Long) }
                else -> error("unexpected $name")
            }
        }
        val history = fake<PlaybackHistoryRepository> { name, _ ->
            when (name) { "recentSongIds" -> emptyList<Long>(); else -> error("unexpected $name") }
        }
        val manager = SmartQueueManager(playback, brain, library, history, ShuffleBrainLog { logged += it })
        manager.start(scope.backgroundScope)
        return Triple(playback, manager, brain)
    }

    private fun ids(p: FakePlayback) = p.q.map { it.id }

    /** What PlayerViewModel.setShuffleMode does. */
    @Suppress("UNUSED_PARAMETER")
    private fun toggle(p: FakePlayback, c: SmartQueueManager, mode: ShuffleMode) = c.chooseShuffleMode(mode)

    /**
     * Lets anything delayed run (a re-rank would, if one were scheduled).
     * advanceUntilIdle alone doesn't move the clock for work in
     * backgroundScope, where the manager runs.
     */
    private fun TestScope.settle() {
        advanceTimeBy(2_000)
        runCurrent()
        advanceUntilIdle()
    }

    @Test
    fun turningShuffleOnReplacesAlbumTailWithABrainBatch() = runTest(UnconfinedTestDispatcher()) {
        val (p, c, brain) = build(this)
        p.setQueue((1L..10L).map(::song), 0, true)
        advanceUntilIdle()
        toggle(p, c, ShuffleMode.SMART)
        advanceUntilIdle()
        val q = ids(p)
        assertEquals(1L, q[0])
        assertEquals("a rolling batch of ${SmartQueueManager.QUEUE_TARGET}: $q", 1 + SmartQueueManager.QUEUE_TARGET, q.size)
        assertTrue("album tail replaced by brain picks: $q", q.drop(1).all { it in pickPool })
        assertTrue("the session restarted from the song playing", brain.resets >= 1 && brain.started.last() == 1L)
        assertEquals("one batch, not one call per song", 1, brain.requests.size)
    }

    @Test
    fun theRollingQueueRefillsInBatches() = runTest(UnconfinedTestDispatcher()) {
        val (p, c, brain) = build(this)
        p.setQueue(listOf(song(1)), 0, true)
        toggle(p, c, ShuffleMode.SMART); advanceUntilIdle()
        // Play through until fewer than REFILL_BELOW remain ahead.
        repeat(SmartQueueManager.QUEUE_TARGET - SmartQueueManager.REFILL_BELOW + 1) { p.skipAfter(0.9f); advanceUntilIdle() }
        assertEquals(2, brain.requests.size)
        val second = brain.requests.last()
        assertEquals("the new batch follows what is still queued", SmartQueueManager.REFILL_BELOW - 1, second.prefixIds.size)
        assertEquals(SmartQueueManager.QUEUE_TARGET - SmartQueueManager.REFILL_BELOW + 1, second.count)
        assertEquals(SmartQueueManager.QUEUE_TARGET, p.q.size - p.idx - 1)
    }

    @Test
    fun aFastSkipTeachesTheBrainButLeavesTheQueueAlone() = runTest(UnconfinedTestDispatcher()) {
        val (p, c, brain) = build(this)
        p.setQueue(listOf(song(1)), 0, true)
        toggle(p, c, ShuffleMode.SMART); advanceUntilIdle()
        p.skipAfter(0.9f); advanceUntilIdle()           // song 1 played
        val queue = ids(p)
        val requestsBefore = brain.requests.size
        val writes = p.writes.size
        p.skipAfter(0.05f); settle()                    // a fast skip
        assertEquals("the queue is exactly as it was", queue, ids(p))
        assertEquals("no batch generated", requestsBefore, brain.requests.size)
        assertEquals("no queue write at all", writes, p.writes.size)
        assertTrue("the outcome reached the brain", brain.outcomes.any { it.second < 0.2f })
        assertEquals(SessionEvent.FAST_SKIP, c.telemetry.value.lastEvent)
    }

    @Test
    fun steppingBackTeachesNothing() = runTest(UnconfinedTestDispatcher()) {
        val (p, c, brain) = build(this)
        p.setQueue(listOf(song(1), song(2)), 1, true)
        toggle(p, c, ShuffleMode.SMART); advanceUntilIdle()
        p.outcomes.emit(PlayOutcome(2, 10, 1000, movedBackward = true))
        advanceUntilIdle()
        assertTrue(brain.outcomes.isEmpty())
    }

    @Test
    fun handPickedSongsAnchorTheSession() = runTest(UnconfinedTestDispatcher()) {
        val (p, c, brain) = build(this)
        p.setQueue(listOf(song(1)), 0, true)
        toggle(p, c, ShuffleMode.SMART); advanceUntilIdle()
        p.setQueue((40L..45L).map(::song), 2, true)     // the user starts song 42 from an album
        advanceUntilIdle()
        assertEquals(listOf(42L), brain.manual)
        assertTrue("album tail replaced: ${ids(p)}", ids(p).drop(3).all { it in pickPool })
    }

    @Test
    fun aSwipedPlayNextSongAnchorsTheVibeWithoutRewritingTheQueue() = runTest(UnconfinedTestDispatcher()) {
        val (p, c, brain) = build(this)
        p.setQueue(listOf(song(1)), 0, true)
        toggle(p, c, ShuffleMode.SMART); advanceUntilIdle()
        val before = ids(p)
        val requests = brain.requests.size
        p.addNext(song(77)); settle()
        assertEquals(listOf(77L), brain.manual)
        assertEquals("swiped song plays next, the rest is unchanged", listOf(1L, 77L) + before.drop(1), ids(p))
        assertEquals("no batch generated", requests, brain.requests.size)
    }

    @Test
    fun playNextSongsSurviveActivationAndOrderIsRestoredOnOff() = runTest(UnconfinedTestDispatcher()) {
        val (p, c, brain) = build(this)
        p.setQueue((1L..10L).map(::song), 2, true)       // playing song 3
        p.addNext(song(50)); p.addNext(song(51))
        advanceUntilIdle()
        toggle(p, c, ShuffleMode.SMART)
        advanceUntilIdle()
        val on = ids(p)
        assertEquals(listOf(1L, 2L, 3L, 50L, 51L), on.take(5))
        assertTrue("shuffle picks after the play-next run: $on", on.drop(5).isNotEmpty() && on.drop(5).all { it in pickPool })

        toggle(p, c, ShuffleMode.OFF)
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L, 3L, 50L, 51L, 4L, 5L, 6L, 7L, 8L, 9L, 10L), ids(p))
        assertTrue("session ended with Smart Shuffle", brain.resets >= 2)
    }

    @Test
    fun aSongMovedAbovePlayNextSongsDoesNotPushThemOut() = runTest(UnconfinedTestDispatcher()) {
        val (p, c, _) = build(this)
        p.setQueue((1L..10L).map(::song), 2, true)       // playing song 3
        p.addNext(song(50)); p.addNext(song(51))         // 1 2 3 [50 51] 4 .. 10
        advanceUntilIdle()
        // Song 9 dragged to the top, above the two queued songs.
        assertTrue(p.moveQueueItem(10, 3, 9L))
        advanceUntilIdle()
        assertEquals(listOf(1L, 2L, 3L, 9L, 50L, 51L, 4L), ids(p).take(7))
        assertEquals("the run covers the queued songs and the one placed among them", 3, p.playerState.value.playNextCount)
        toggle(p, c, ShuffleMode.SMART)
        advanceUntilIdle()
        val on = ids(p)
        assertEquals("hand-placed songs kept, in the order set: $on", listOf(1L, 2L, 3L, 9L, 50L, 51L), on.take(6))
        assertTrue("shuffle picks after them: $on", on.drop(6).isNotEmpty() && on.drop(6).all { it in pickPool })
    }

    @Test
    fun reorderedPlayNextSongsKeepTheirOrderThroughShuffle() = runTest(UnconfinedTestDispatcher()) {
        val (p, c, _) = build(this)
        p.setQueue((1L..10L).map(::song), 2, true)
        p.addNext(song(50)); p.addNext(song(51)); p.addNext(song(52))
        advanceUntilIdle()
        assertTrue(p.moveQueueItem(5, 3, 52L))           // 52 first, within the run
        assertTrue(p.moveQueueItem(4, 5, 50L))           // 50 after 51, still within it
        advanceUntilIdle()
        assertEquals(listOf(3L, 52L, 51L, 50L, 4L), ids(p).subList(2, 7))
        assertEquals(3, p.playerState.value.playNextCount)
        toggle(p, c, ShuffleMode.SMART); advanceUntilIdle()
        assertEquals(listOf(1L, 2L, 3L, 52L, 51L, 50L), ids(p).take(6))
        toggle(p, c, ShuffleMode.OFF); advanceUntilIdle()
        assertEquals(listOf(1L, 2L, 3L, 52L, 51L, 50L, 4L, 5L, 6L, 7L, 8L, 9L, 10L), ids(p))
    }

    @Test
    fun aMoveUnderSmartShuffleStaysAndNextFollowsIt() = runTest(UnconfinedTestDispatcher()) {
        val (p, c, brain) = build(this)
        p.setQueue(listOf(song(1)), 0, true)
        toggle(p, c, ShuffleMode.SMART); advanceUntilIdle()
        val before = ids(p)
        val last = before.last()
        val requests = brain.requests.size
        // The last pick dragged up to play next, as the current song nears its end.
        assertTrue(p.moveQueueItem(before.lastIndex, 1, last))
        settle()
        val moved = before.toMutableList().apply { add(1, removeAt(lastIndex)) }
        assertEquals("the new order is not rewritten", moved, ids(p))
        assertEquals("no batch, no rewrite", requests, brain.requests.size)
        assertEquals("move", p.writes.last())
        p.skipAfter(0.95f); advanceUntilIdle()
        assertEquals("Next plays the song moved there", last, p.playerState.value.currentSong?.id)
        assertEquals(moved, ids(p).take(moved.size))
    }

    @Test
    fun aMoveFromAStaleScreenIsRefused() = runTest(UnconfinedTestDispatcher()) {
        val (p, _, _) = build(this)
        p.setQueue((1L..6L).map(::song), 0, true)
        advanceUntilIdle()
        // The screen saw song 4 at index 3; it was removed meanwhile.
        p.removeFromQueue(3)
        assertFalse(p.moveQueueItem(3, 1, 4L))
        assertEquals(listOf(1L, 2L, 3L, 5L, 6L), ids(p))
        // And a move can't take the song playing or reach behind it.
        assertFalse(p.moveQueueItem(0, 3, 1L))
        assertTrue(p.moveQueueItem(4, 0, 6L))
        assertEquals(listOf(1L, 6L, 2L, 3L, 5L), ids(p))
    }

    @Test
    fun secondActivationAfterOffStillShuffles() = runTest(UnconfinedTestDispatcher()) {
        val (p, c, _) = build(this)
        p.setQueue((1L..10L).map(::song), 0, true)
        toggle(p, c, ShuffleMode.SMART); advanceUntilIdle()
        toggle(p, c, ShuffleMode.OFF); advanceUntilIdle()
        assertEquals((1L..10L).toList(), ids(p))
        toggle(p, c, ShuffleMode.SMART); advanceUntilIdle()
        assertTrue(ids(p).drop(1).all { it in pickPool })
        toggle(p, c, ShuffleMode.OFF); advanceUntilIdle()
        assertEquals((1L..10L).toList(), ids(p))
    }

    @Test
    fun choosingAStyleWhileOffTurnsShuffleOnAndReachesTheBrain() = runTest(UnconfinedTestDispatcher()) {
        val (p, c, brain) = build(this)
        p.setQueue((1L..10L).map(::song), 0, true)
        c.setSmartStyle(SmartShuffleStyle.FLOW)
        advanceUntilIdle()
        assertEquals(ShuffleMode.SMART, p.mode)
        assertEquals(1 + SmartQueueManager.QUEUE_TARGET, p.q.size)
        assertTrue(ids(p).drop(1).all { it in pickPool })
        assertEquals(SmartShuffleStyle.FLOW, brain.configs.last().style)
        toggle(p, c, ShuffleMode.OFF); advanceUntilIdle()
        assertEquals((1L..10L).toList(), ids(p))
    }

    @Test
    fun withoutTheNativeEngineRandomPicksKeepPlaying() = runTest(UnconfinedTestDispatcher()) {
        val (p, c, brain) = build(this, FakeBrain(pickPool, available = false))
        p.setQueue(listOf(song(1)), 0, true)
        toggle(p, c, ShuffleMode.SMART); advanceUntilIdle()
        assertTrue(brain.requests.isEmpty())
        assertEquals(1 + SmartQueueManager.QUEUE_TARGET, p.q.size)
        assertTrue(ids(p).drop(1).all { it in randomPool })
        assertFalse(c.telemetry.value.engineAvailable)
    }

    @Test
    fun telemetryExplainsEveryUpcomingPick() = runTest(UnconfinedTestDispatcher()) {
        val (p, c, _) = build(this)
        p.setQueue(listOf(song(1)), 0, true)
        toggle(p, c, ShuffleMode.SMART); advanceUntilIdle()
        val t = c.telemetry.value
        assertEquals(SmartQueueManager.QUEUE_TARGET, t.upcoming.size)
        assertEquals(ids(p).drop(1), t.upcoming.map { it.trace.trackId })
        assertEquals("90% Bass/Vibe Match", t.upcoming.first().trace.primaryReasonTag)
        assertEquals("PASS", t.audit?.verdict)
        assertTrue(t.hasSession)
        assertTrue("table logged", logged.any { it.startsWith("+-- SHUFFLE BRAIN") })
        assertTrue("top 5 rows", logged.count { it.startsWith("| ") && "Vibe Match" in it } >= 5)
        assertNull("the seed song was not a pick", c.telemetry.value.nowPlaying)
        p.skipAfter(0.9f); advanceUntilIdle()
        assertEquals("the playing song leaves the list", ids(p).drop(p.idx + 1), c.telemetry.value.upcoming.map { it.trace.trackId })
        assertEquals("and explains itself as now playing", p.q[p.idx].id, c.telemetry.value.nowPlaying?.trace?.trackId)
        assertEquals(90, c.telemetry.value.nowPlaying?.vibeMatchPct)
    }

    @Test
    fun selfTestReportsThroughTelemetryAndLog() = runTest(UnconfinedTestDispatcher()) {
        val (_, c, _) = build(this)
        val report = c.runBrainSelfTest()
        assertTrue(report.contains("RESULT: PASS"))
        assertEquals(report, c.telemetry.value.selfTestReport)
        assertTrue(logged.any { "synthetic self-test" in it })
    }

    // -- Queue stability: moving around never rebuilds the queue ---------------------------

    /** Smart Shuffle running from song 5 of an album, two picks already heard. */
    private suspend fun TestScope.smartSessionFromAlbum(p: FakePlayback, c: SmartQueueManager) {
        p.setQueue((1L..10L).map(::song), 4, true)       // the user starts song 5
        toggle(p, c, ShuffleMode.SMART); advanceUntilIdle()
        p.skipAfter(0.9f); advanceUntilIdle()
        p.skipAfter(0.9f); advanceUntilIdle()
    }

    @Test
    fun previousIntoTheHistoryNeverRebuildsTheQueue() = runTest(UnconfinedTestDispatcher()) {
        val (p, c, brain) = build(this)
        smartSessionFromAlbum(p, c)
        val queue = ids(p)
        val requests = brain.requests.size
        // Back through two picks, the song the session started from, and into the album before it.
        repeat(4) { c.skipToPrevious(); advanceUntilIdle() }
        assertEquals("playing song 3 of the album", 3L, p.q[p.idx].id)
        assertEquals("the queue is exactly as it was", queue, ids(p))
        assertEquals("no batch was generated", requests, brain.requests.size)
        assertTrue("a step back is not a hand-picked song", brain.manual.isEmpty())
        // And forward again over the same songs.
        repeat(4) { p.skipAfter(0.9f); advanceUntilIdle() }
        assertEquals("history and order intact", queue, ids(p).take(queue.size))
    }

    @Test
    fun tappingAQueuedSongOnlySeeks() = runTest(UnconfinedTestDispatcher()) {
        val (p, c, brain) = build(this)
        smartSessionFromAlbum(p, c)
        val queue = ids(p)
        val target = p.idx + 3
        val targetId = queue[target]
        val writes = p.writes.size
        c.playQueueItem(target); advanceUntilIdle()
        assertEquals(targetId, p.q[p.idx].id)
        assertEquals("nothing before or after was rewritten", queue, ids(p).take(queue.size))
        assertTrue("only appends may follow (the lookahead): ${p.writes.drop(writes)}", p.writes.drop(writes).all { it == "add" })
        assertTrue("no re-rank after a tap", brain.requests.none { it.replacedTail.isNotEmpty() })
        // A tap back into the original album anchors the vibe, still without a queue change.
        val before = ids(p)
        c.playQueueItem(2); advanceUntilIdle()
        assertEquals(before, ids(p))
        assertEquals("only the album song anchors", listOf(3L), brain.manual)
    }

    @Test
    fun skippingSongAfterSongNeverRewritesTheQueue() = runTest(UnconfinedTestDispatcher()) {
        val (p, c, brain) = build(this)
        p.setQueue(listOf(song(1)), 0, true)
        toggle(p, c, ShuffleMode.SMART); advanceUntilIdle()
        val writes = p.writes.size
        // Ten fast Nexts, some in a burst, some with time between them.
        repeat(10) { i ->
            val before = ids(p)
            val upcoming = before.drop(p.idx + 1)
            p.skipAfter(0.05f)
            if (i % 3 == 0) settle() else advanceUntilIdle()
            assertEquals("skip ${i + 1}: nothing already queued changed", before, ids(p).take(before.size))
            assertEquals("skip ${i + 1}: the next song is the one that was queued", upcoming[0], p.q[p.idx].id)
        }
        settle()
        assertTrue("only appends at the end: ${p.writes.drop(writes)}", p.writes.drop(writes).all { it == "add" })
        assertTrue("no re-rank", brain.requests.none { it.replacedTail.isNotEmpty() })
        assertTrue(p.q.size - p.idx - 1 >= SmartQueueManager.REFILL_BELOW)
    }

    @Test
    fun aNaturalEndOnlyAppendsAtTheEnd() = runTest(UnconfinedTestDispatcher()) {
        val (p, c, _) = build(this)
        p.setQueue(listOf(song(1)), 0, true)
        toggle(p, c, ShuffleMode.SMART); advanceUntilIdle()
        val writes = p.writes.size
        repeat(6) {
            val before = ids(p)
            p.skipAfter(1f); advanceUntilIdle()
            assertEquals("nothing rewritten, only appended", before, ids(p).take(before.size))
        }
        assertTrue("only appends while listening", p.writes.drop(writes).all { it == "add" })
        assertTrue(p.q.size - p.idx - 1 >= SmartQueueManager.REFILL_BELOW)
    }

    @Test
    fun randomShufflesTheQueueItselfAndPlayNextStaysNext() = runTest(UnconfinedTestDispatcher()) {
        val (p, c, _) = build(this)
        p.setQueue((1L..10L).map(::song), 0, true)
        c.chooseShuffleMode(ShuffleMode.RANDOM); advanceUntilIdle()
        assertEquals(1L, p.q[0].id)
        assertEquals("the same songs, physically reordered", (2L..10L).toSet(), ids(p).drop(1).toSet())
        p.addNext(song(50)); advanceUntilIdle()
        assertEquals("play next plays next, not at a random place", 50L, p.q[1].id)
        val shuffled = ids(p)
        p.skipAfter(0.9f); c.skipToPrevious(); advanceUntilIdle()
        assertEquals("moving around keeps the order", shuffled, ids(p))
        c.chooseShuffleMode(ShuffleMode.OFF); advanceUntilIdle()
        assertEquals("off restores the running order", listOf(1L, 50L) + (2L..10L), ids(p))
    }

    @Test
    fun startingANewListInRandomPlaysItFirstThenTheRestShuffled() = runTest(UnconfinedTestDispatcher()) {
        val (p, c, _) = build(this)
        p.setQueue(listOf(song(1)), 0, true)
        c.chooseShuffleMode(ShuffleMode.RANDOM); advanceUntilIdle()
        p.setQueue((20L..29L).map(::song), 4, true)       // tap song 24 of an album
        advanceUntilIdle()
        assertEquals(24L, p.q[p.idx].id)
        assertEquals(0, p.idx)
        assertEquals((20L..29L).toSet() - 24L, ids(p).drop(1).toSet())
    }

    @Test
    fun externalShuffleButtonsGoThroughTheManager() = runTest(UnconfinedTestDispatcher()) {
        val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val playback = object : PlaybackController by FakePlayback() {
            override val externalShuffleRequests = requests
        }
        val brain = FakeBrain(pickPool)
        val library = fake<LibraryRepository> { name, a ->
            when (name) {
                "randomSongId" -> { val ex = a[0] as Set<*>; randomPool.first { it !in ex } }
                "songById" -> song(a[0] as Long)
                "songsByIds" -> (a[0] as List<*>).map { song(it as Long) }
                else -> error("unexpected $name")
            }
        }
        val history = fake<PlaybackHistoryRepository> { _, _ -> emptyList<Long>() }
        val manager = SmartQueueManager(playback, brain, library, history, ShuffleBrainLog { })
        manager.start(backgroundScope)
        playback.setQueue((1L..5L).map(::song), 0, true)
        requests.emit(Unit); advanceUntilIdle()
        assertEquals(ShuffleMode.SMART, playback.playerState.value.shuffleMode)
    }

    @Test
    fun aJourneyStepsAlongThenHandsBackToSmart() = runTest(UnconfinedTestDispatcher()) {
        val brain = FakeBrain(pickPool).apply { journeyEnds = 6 }
        val (p, c, _) = build(this, brain)
        p.setQueue(listOf(song(1)), 0, true)
        advanceUntilIdle()
        assertTrue(c.startJourney(destinationSongId = 150, steps = 10))
        advanceUntilIdle()
        assertEquals(ShuffleMode.JOURNEY, p.mode)
        assertEquals(SmartQueueManager.JOURNEY_LOOKAHEAD, p.q.size - 1)
        assertEquals(0.4f, c.journey.value!!.progress, 1e-4f)
        // Keep listening: when the brain finds no more path, the journey ends in Smart Shuffle.
        repeat(4) { p.skipAfter(0.9f); advanceUntilIdle() }
        assertNull(c.journey.value)
        assertEquals(ShuffleMode.SMART, p.mode)
    }

    // -- Provenance: the user's songs are protected wherever they sit (Universal Queue, stage 5) --

    @Test
    fun aSongAddedToTheEndSurvivesSmartShuffleStarting() = runTest(UnconfinedTestDispatcher()) {
        val (p, c, _) = build(this)
        p.setQueue((1L..10L).map(::song), 0, true)
        p.addToQueue(song(60))                            // "Add to queue": after the whole album
        advanceUntilIdle()
        toggle(p, c, ShuffleMode.SMART); advanceUntilIdle()
        val on = ids(p)
        assertEquals("the album makes way, the user's song stays: $on", listOf(1L, 60L), on.take(2))
        assertTrue("then picks: $on", on.drop(2).isNotEmpty() && on.drop(2).all { it in pickPool })
        assertEquals("M", p.origins().first())
        assertTrue("picks are automatic: ${p.origins()}", p.origins().drop(1).all { it == "A" })
        toggle(p, c, ShuffleMode.OFF); advanceUntilIdle()
        assertEquals("off: the album back, the user's song in front", listOf(1L, 60L) + (2L..10L), ids(p))
    }

    @Test
    fun aQueuedSongDraggedOutOfNextUpIsStillTheUsers() = runTest(UnconfinedTestDispatcher()) {
        val (p, c, _) = build(this)
        p.setQueue((1L..10L).map(::song), 0, true)
        p.addNext(song(50))                               // 1 [50] 2 .. 10
        advanceUntilIdle()
        assertTrue(p.moveQueueItem(1, 10, 50L))           // dragged to the very end: no longer in the run
        advanceUntilIdle()
        assertEquals(0, p.playerState.value.playNextCount)
        toggle(p, c, ShuffleMode.SMART); advanceUntilIdle()
        val on = ids(p)
        assertEquals("kept, though not in the Play next run: $on", listOf(1L, 50L), on.take(2))
        assertTrue(on.drop(2).all { it in pickPool })
    }

    @Test
    fun aPickTheUserMovedIsNeverReRankedAndOutlivesShuffle() = runTest(UnconfinedTestDispatcher()) {
        val (p, c, brain) = build(this)
        p.setQueue((1L..5L).map(::song), 0, true)
        toggle(p, c, ShuffleMode.SMART); advanceUntilIdle()
        val before = ids(p)
        val moved = before.last()
        assertTrue(p.moveQueueItem(before.lastIndex, 3, moved))
        advanceUntilIdle()
        val requests = brain.requests.size
        c.regenerateSmartQueue(); settle()
        assertTrue("a re-rank ran", brain.requests.size > requests)
        val after = ids(p)
        assertEquals("the head stays: $after", before.take(2), after.take(2))
        assertEquals("the moved pick kept, first after the head: $after", moved, after[2])
        assertEquals(1, after.count { it == moved })
        assertTrue("the other picks were replaced: $before -> $after",
            after.drop(3).none { it in before.drop(2) })
        assertEquals("AP", p.origins()[1])
        toggle(p, c, ShuffleMode.OFF); advanceUntilIdle()
        // It sat after one other song: it comes after the album's first.
        assertEquals("off: the moved pick kept, the untouched picks gone, the album back", listOf(1L, 2L, moved, 3L, 4L, 5L), ids(p))
    }

    @Test
    fun randomShufflesOnlyTheFreeSongsAndLeavesTheUsersInPlace() = runTest(UnconfinedTestDispatcher()) {
        val (p, c, _) = build(this)
        p.setQueue((1L..10L).map(::song), 0, true)
        p.addToQueue(song(60))                            // the user's, at the end
        p.addNext(song(50))                               // the user's, next
        assertTrue(p.moveQueueItem(5, 3, 5L))             // song 5 moved up by hand: 1 50 2 5 3 4 6 .. 10 60
        advanceUntilIdle()
        val before = ids(p)
        repeat(5) {
            toggle(p, c, ShuffleMode.RANDOM); advanceUntilIdle()
            val now = ids(p)
            assertEquals("same songs: $now", before.sorted(), now.sorted())
            assertEquals(1L, now[0])
            assertEquals("play next stays next: $now", 50L, now[1])
            assertEquals("the moved song stays put: $now", 5L, now[3])
            assertEquals("the song added at the end stays last: $now", 60L, now.last())
            toggle(p, c, ShuffleMode.OFF); advanceUntilIdle()
            assertEquals("off restores the order", before, ids(p))
        }
    }

    @Test
    fun startingANewListKeepsTheUsersSongs() = runTest(UnconfinedTestDispatcher()) {
        val (p, _, _) = build(this)
        p.setQueue((1L..5L).map(::song), 0, true)
        p.addNext(song(50))
        p.addToQueue(song(60))
        advanceUntilIdle()
        p.setQueue((20L..25L).map(::song), 2, true)       // song 22 of an album
        advanceUntilIdle()
        assertEquals(listOf(20L, 21L, 22L, 50L, 60L, 23L, 24L, 25L), ids(p))
        assertEquals(22L, p.playerState.value.currentSong?.id)
        assertEquals(listOf("M", "M", "L", "L", "L"), p.origins())
    }

    @Test
    fun startingANewListUnderSmartShuffleKeepsTheUsersSongsBeforeThePicks() = runTest(UnconfinedTestDispatcher()) {
        val (p, c, brain) = build(this)
        p.setQueue(listOf(song(1)), 0, true)
        toggle(p, c, ShuffleMode.SMART); advanceUntilIdle()
        p.addToQueue(song(60))                            // the user's, after the picks
        advanceUntilIdle()
        p.setQueue((40L..45L).map(::song), 2, true)
        advanceUntilIdle()
        val q = ids(p)
        assertEquals("$q", listOf(40L, 41L, 42L, 60L), q.take(4))
        assertTrue("the old picks went, new ones follow: $q", q.drop(4).isNotEmpty() && q.drop(4).all { it in pickPool })
        assertEquals(listOf(42L), brain.manual)
    }

    @Test
    fun startingANewListInRandomKeepsTheUsersSongsNext() = runTest(UnconfinedTestDispatcher()) {
        val (p, c, _) = build(this)
        p.setQueue(listOf(song(1)), 0, true)
        c.chooseShuffleMode(ShuffleMode.RANDOM); advanceUntilIdle()
        p.addNext(song(50))
        advanceUntilIdle()
        p.setQueue((20L..29L).map(::song), 4, true)
        advanceUntilIdle()
        val q = ids(p)
        assertEquals("$q", listOf(24L, 50L), q.take(2))
        assertEquals((20L..29L).toSet() - 24L, q.drop(2).toSet())
    }

    @Test
    fun aComputersSongInTheQueueIsKeptAndNeverReachesTheBrain() = runTest(UnconfinedTestDispatcher()) {
        val (p, c, brain) = build(this)
        val pcSong = com.harmony.domain.playback.RemoteSongs.song("pc:pc-1:abc", "From the PC", "Someone", "", 200_000)
        p.setQueue((1L..5L).map(::song), 0, true)
        p.insertQueueItem(1, pcSong)                      // put in from the computer: the user's
        advanceUntilIdle()
        toggle(p, c, ShuffleMode.SMART); advanceUntilIdle()
        assertEquals("kept, the album made way: ${ids(p)}", listOf(1L, pcSong.id), ids(p).take(2))
        p.skipAfter(0.9f); advanceUntilIdle()              // the computer's song plays
        assertEquals(pcSong.id, p.playerState.value.currentSong?.id)
        assertTrue("the brain never hears of it: ${brain.started}", brain.started.none { it < 0 })
        assertTrue("nor is it asked to follow it", brain.requests.none { it.currentSongId < 0 || it.prefixIds.any { id -> id < 0 } })
        // Playing on from it: the next batch follows the last library song instead.
        repeat(4) { p.skipAfter(0.9f); advanceUntilIdle() }
        assertTrue(brain.requests.size >= 2)
        assertTrue(brain.requests.all { it.currentSongId >= 0 })
    }
}
