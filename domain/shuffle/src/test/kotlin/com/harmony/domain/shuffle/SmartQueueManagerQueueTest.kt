package com.harmony.domain.shuffle

import com.harmony.core.model.AuditCheckKind
import com.harmony.core.model.AuditCheckResult
import com.harmony.core.model.AuditStatus
import com.harmony.core.model.BrainAuditReport
import com.harmony.core.model.EqSettings
import com.harmony.core.model.PlayerState
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

private fun song(id: Long) = Song(id, "u$id", "t$id", "a${id % 7}", "al", 1, null, null, null, null, null, null,
    1000, null, null, null, null, null, null, null, null)

/** Minimal ExoPlayer-like queue: index-based, play-next run anchored to the current song. */
private class FakePlayback : PlaybackController {
    val q = mutableListOf<Song>()
    var idx = 0
    var mode = ShuffleMode.OFF
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
            playNextCount = pn, advancedAutomatically = automatic)
    }

    /** Skips to the next entry after [heardFraction] of the song, as the real player reports it. */
    suspend fun skipAfter(heardFraction: Float) {
        val left = q[idx]
        idx++
        if (anchor == left.id && run > 0) { anchor = q.getOrNull(idx)?.id; run-- }
        sync()
        outcomes.emit(PlayOutcome(left.id, (heardFraction * 1000).toLong(), 1000))
    }

    override suspend fun setQueue(songs: List<Song>, startIndex: Int, playWhenReady: Boolean) { q.clear(); q += songs; idx = startIndex; sync() }
    override suspend fun addToQueue(song: Song) { q += song; sync() }
    override suspend fun addToQueueAll(songs: List<Song>) { q += songs; sync() }
    override suspend fun addNext(song: Song) {
        val cur = q[idx].id
        if (cur != anchor) { anchor = cur; run = 0 }
        run++
        q.add((idx + run).coerceAtMost(q.size), song); sync()
    }
    override suspend fun removeFromQueue(index: Int) { q.removeAt(index); sync() }
    override suspend fun removeQueueRange(fromIndex: Int, toIndexExclusive: Int) {
        val from = fromIndex.coerceIn(0, q.size); val to = toIndexExclusive.coerceIn(from, q.size)
        repeat(to - from) { q.removeAt(from) }; sync()
    }
    override suspend fun moveQueueItem(from: Int, to: Int) {}
    override fun play() {}
    override fun pause() {}
    override fun seekTo(positionMs: Long) {}
    override fun skipToNext() {}
    override fun skipToPrevious() {}
    override fun skipToQueueItem(index: Int) {}
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
    private fun toggle(p: FakePlayback, c: SmartQueueManager, mode: ShuffleMode) {
        val wasSmart = p.mode == ShuffleMode.SMART || p.mode == ShuffleMode.JOURNEY
        c.onShuffleModeChosen(mode)
        p.setShuffleMode(mode)
        if (mode == ShuffleMode.SMART && !wasSmart) c.onShuffleActivated()
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
    fun aFastSkipReRanksTheGeneratedTail() = runTest(UnconfinedTestDispatcher()) {
        val (p, c, brain) = build(this)
        p.setQueue(listOf(song(1)), 0, true)
        toggle(p, c, ShuffleMode.SMART); advanceUntilIdle()
        p.skipAfter(0.9f); advanceUntilIdle()           // song 1 played: no re-rank
        val before = ids(p).drop(p.idx + 1)
        val requestsBefore = brain.requests.size
        p.skipAfter(0.05f); advanceUntilIdle()          // a fast skip
        val after = ids(p).drop(p.idx + 1)
        assertEquals(requestsBefore + 1, brain.requests.size)
        val rerank = brain.requests.last()
        assertEquals("the brain is told what it replaced", before.drop(1), rerank.replacedTail)
        assertTrue("the tail was rebuilt: $before -> $after", after.toSet() != before.drop(1).toSet())
        assertEquals(SmartQueueManager.QUEUE_TARGET, after.size)
        assertTrue("the outcome reached the brain", brain.outcomes.any { it.second < 0.2f })
        assertEquals(SessionEvent.FAST_SKIP, c.telemetry.value.lastEvent)
        assertTrue("re-rank logged", logged.any { "re-rank" in it })
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
    fun aSwipedPlayNextSongBoostsAndReRanks() = runTest(UnconfinedTestDispatcher()) {
        val (p, c, brain) = build(this)
        p.setQueue(listOf(song(1)), 0, true)
        toggle(p, c, ShuffleMode.SMART); advanceUntilIdle()
        val requests = brain.requests.size
        p.addNext(song(77)); advanceUntilIdle()
        assertEquals(listOf(77L), brain.manual)
        assertEquals("swiped song plays next", 77L, p.q[1].id)
        assertTrue("tail regenerated around it", brain.requests.size > requests)
        assertTrue(ids(p).drop(2).all { it in pickPool })
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
}
