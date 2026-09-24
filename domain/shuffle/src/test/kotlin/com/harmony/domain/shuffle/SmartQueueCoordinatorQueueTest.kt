package com.harmony.domain.shuffle

import com.harmony.core.model.EqSettings
import com.harmony.core.model.PlayerState
import com.harmony.core.model.RepeatMode
import com.harmony.core.model.ReplayGainMode
import com.harmony.core.model.ShuffleMode
import com.harmony.core.model.Song
import com.harmony.domain.library.repository.LibraryRepository
import com.harmony.domain.library.repository.PlaybackHistoryRepository
import com.harmony.domain.playback.PlaybackController
import com.harmony.domain.shuffle.engine.JourneyEngine
import com.harmony.domain.shuffle.engine.SmartShuffleEngine
import com.harmony.domain.similarity.repository.SimilarityRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.random.Random

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
    fun sync() {
        val cur = q.getOrNull(idx)
        val pn = if (cur?.id == anchor) run.coerceIn(0, (q.size - idx - 1).coerceAtLeast(0)) else 0
        _s.value = PlayerState(currentSong = cur, queue = q.toList(), queueIndex = idx, shuffleMode = mode, playNextCount = pn)
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

@Suppress("UNCHECKED_CAST")
private inline fun <reified T> fake(noinline h: (String, Array<Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, args ->
        h(m.name, args ?: emptyArray())
    } as T

@OptIn(ExperimentalCoroutinesApi::class)
/**
 * Queue behaviour of Smart Shuffle's on/off toggle, driven through the real
 * coordinator against an in-memory player.
 *
 * Regression: switching Smart Shuffle on left the album / playlist the song
 * was started from sitting in the queue, so no shuffle picks ever appeared.
 */
class SmartQueueCoordinatorQueueTest {
    private val pickPool = (100L..199L)

    private fun build(scope: TestScope): Pair<FakePlayback, SmartQueueCoordinator> {
        val playback = FakePlayback()
        val similarity = fake<SimilarityRepository> { name, _ ->
            when (name) { "findSimilar", "findSimilarToVector" -> emptyList<Any>(); else -> null }
        }
        val library = fake<LibraryRepository> { name, a ->
            when (name) {
                "randomSongId" -> { val ex = a[0] as Set<*>; pickPool.first { it !in ex } }
                "songById" -> song(a[0] as Long)
                "songsByIds" -> (a[0] as List<*>).map { song(it as Long) }
                else -> error("unexpected $name")
            }
        }
        val history = fake<PlaybackHistoryRepository> { name, _ ->
            when (name) { "recentSongIds" -> emptyList<Long>(); else -> error("unexpected $name") }
        }
        val engine = SmartShuffleEngine(similarity, null, null, null, Random(1))
        val coordinator = SmartQueueCoordinator(playback, engine, JourneyEngine(similarity, engine), library, history)
        coordinator.start(scope.backgroundScope)
        return playback to coordinator
    }

    private fun ids(p: FakePlayback) = p.q.map { it.id }

    /** What PlayerViewModel.setShuffleMode does. */
    private fun toggle(p: FakePlayback, c: SmartQueueCoordinator, mode: ShuffleMode) {
        val wasSmart = p.mode == ShuffleMode.SMART || p.mode == ShuffleMode.JOURNEY
        c.onShuffleModeChosen(mode)
        p.setShuffleMode(mode)
        if (mode == ShuffleMode.SMART && !wasSmart) c.onShuffleActivated()
    }

    @Test
    fun turningShuffleOnReplacesAlbumTailWithShufflePicks() = runTest(UnconfinedTestDispatcher()) {
        val (p, c) = build(this)
        p.setQueue((1L..10L).map(::song), 0, true)
        advanceUntilIdle()
        toggle(p, c, ShuffleMode.SMART)
        advanceUntilIdle()
        val q = ids(p)
        assertEquals(1L, q[0])
        assertEquals("lookahead of 4 picks: $q", 5, q.size)
        assertTrue("album tail replaced by picks: $q", q.drop(1).all { it in pickPool })
    }

    @Test
    fun playNextSongsSurviveActivationAndOrderIsRestoredOnOff() = runTest(UnconfinedTestDispatcher()) {
        val (p, c) = build(this)
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
    }

    @Test
    fun secondActivationAfterOffStillShuffles() = runTest(UnconfinedTestDispatcher()) {
        val (p, c) = build(this)
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
    fun choosingAStyleWhileOffTurnsShuffleOnAndShuffles() = runTest(UnconfinedTestDispatcher()) {
        val (p, c) = build(this)
        p.setQueue((1L..10L).map(::song), 0, true)
        c.setSmartStyle(com.harmony.domain.shuffle.model.SmartShuffleStyle.FLOW)
        advanceUntilIdle()
        assertEquals(ShuffleMode.SMART, p.mode)
        assertEquals(5, p.q.size)
        assertTrue(ids(p).drop(1).all { it in pickPool })
        toggle(p, c, ShuffleMode.OFF); advanceUntilIdle()
        assertEquals((1L..10L).toList(), ids(p))
    }
}
