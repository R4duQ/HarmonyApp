package com.harmony.domain.online

import com.harmony.core.model.MixSource

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private class FakeCatalog(
    var search: suspend (String) -> CatalogResult<List<CatalogTrack>>,
    var radio: suspend (String) -> CatalogResult<List<CatalogTrack>>,
) : OnlineCatalog {
    var searches = 0
    var radios = 0
    override suspend fun searchSongs(query: String, limit: Int): CatalogResult<List<CatalogTrack>> { searches++; return search(query) }
    override suspend fun radio(videoId: String, limit: Int): CatalogResult<List<CatalogTrack>> { radios++; return radio.invoke(videoId) }
}

private class FakePreparer(var outcome: suspend (CatalogTrack) -> PrepareOutcome = { ready(it) }) : CandidatePreparer {
    val calls = mutableListOf<String>()
    override suspend fun prepare(track: CatalogTrack, onProgress: (Float?) -> Unit): PrepareOutcome {
        calls += track.videoId
        onProgress(0.5f)
        return outcome(track)
    }

    companion object {
        fun ready(t: CatalogTrack) = PrepareOutcome.Ready(PreparedAudio("/cache/${t.videoId}.flac", "FLAC", 16, 44_100, t.durationMs ?: 0, 30_000_000, "tidal"))
    }
}

private val SEED = SeedTrack("Pe Drum", "Subcarpați", "Underground Folclor", 222_000)
private val SEED_SONG = song("seedsong", "Pe Drum", "Subcarpați", ms = 222_000, album = "Underground Folclor")
private val RADIO = listOf(SEED_SONG) + listOf(
    song("r1", "Scrisoare din trecut", "Subcarpați"),
    song("r2", "Flori Ilegale", "Guess Who"),
    song("r3", "da-i volum!", "raku"),
    song("r4", "Azi", "Deliric"),
    song("r5", "Lovesong", "Adele", kind = CatalogKind.PODCAST_EPISODE),
)

@OptIn(ExperimentalCoroutinesApi::class)
class OnlineMixCoordinatorTest {
    private val key = MixSessionKey(seedSongId = 7, startedAtMillis = 1, generation = 1)

    private fun TestScope.coordinator(
        catalog: FakeCatalog = FakeCatalog({ CatalogResult.Ok(listOf(SEED_SONG)) }, { CatalogResult.Ok(RADIO) }),
        preparer: FakePreparer = FakePreparer(),
        local: LocalCopies = LocalCopies.NONE,
    ): Triple<OnlineMixCoordinator, FakeCatalog, FakePreparer> {
        // Its own scope on the test scheduler (backgroundScope work is not run by advanceUntilIdle).
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        scopes += scope
        return Triple(OnlineMixCoordinator(catalog, preparer, local, scope, clock = { testScheduler.currentTime }), catalog, preparer)
    }

    private val scopes = mutableListOf<CoroutineScope>()

    @After fun cancelScopes() = scopes.forEach { it.cancel() }

    @Test
    fun offlineMakesNoRequestAtAll() = runTest {
        val (c, catalog, preparer) = coordinator()
        c.start(key, MixSource.OFFLINE, SEED) { emptySet() }
        advanceUntilIdle()
        assertEquals(0, catalog.searches + catalog.radios)
        assertTrue(preparer.calls.isEmpty())
        assertNull(c.state.value.session)
    }

    @Test
    fun fromTheSeedToAReadyRecommendation() = runTest {
        val (c, catalog, preparer) = coordinator(local = LocalCopies { t -> if (t.videoId.startsWith("r2")) 42L else null })
        c.start(key, MixSource.COMBINED, SEED) { emptySet() }
        advanceUntilIdle()
        val s = c.state.value
        assertEquals(SeedStatus.Identified(SEED_SONG.videoId, 100), s.seed)
        assertEquals(1, catalog.searches)
        assertEquals(1, catalog.radios)
        // Songs only; the seed and the episode turned away.
        assertEquals(listOf("r1", "r2", "r3", "r4"), s.candidates.map { it.videoId.take(2) })
        // The library's copy needs nothing; one online song is prepared, and only one (lookahead).
        assertEquals(CandidateStage.IN_LIBRARY, s.candidates[1].stage)
        assertEquals(listOf("r1000000000"), preparer.calls)
        assertEquals(CandidateStage.READY, s.candidates[0].stage)
        assertEquals(Availability.CONFIRMED, s.candidates[0].availability)
        assertEquals("FLAC", s.candidates[0].audio?.codec)

        val picks = c.take(key, 5)
        assertEquals(2, picks.size)
        assertTrue(picks[0] is ReadyPick.Prepared)
        assertEquals(42L, (picks[1] as ReadyPick.Local).songId)
        // Taken once: a second take has nothing until the next one is ready.
        assertTrue(c.take(key, 5).isEmpty())
        advanceUntilIdle()
        assertEquals(listOf("r1000000000", "r3000000000"), preparer.calls)
        assertEquals(1, c.take(key, 5).size)
    }

    @Test
    fun aLateResultOfAnEndedMixIsDropped() = runTest {
        val gate = CompletableDeferred<Unit>()
        val preparer = FakePreparer { gate.await(); FakePreparer.ready(it) }
        val (c, _, _) = coordinator(preparer = preparer)
        c.start(key, MixSource.ONLINE, SEED) { emptySet() }
        advanceUntilIdle()
        assertEquals(CandidateStage.PREPARING, c.state.value.candidates.first().stage)
        // The user starts another list: the mix ends, then a new one starts.
        c.stop()
        val next = key.copy(generation = 2, startedAtMillis = 2)
        c.start(next, MixSource.ONLINE, SEED.copy(title = "Scrisoare din trecut")) { emptySet() }
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue("the old session's picks are never handed out", c.take(key, 5).isEmpty())
        assertEquals(next, c.state.value.session)
    }

    @Test
    fun notASongMeansNoRadioAndNoGuess() = runTest {
        val video = SEED_SONG.copy(kind = CatalogKind.USER_VIDEO)
        val (c, catalog, _) = coordinator(FakeCatalog({ CatalogResult.Ok(listOf(video)) }, { CatalogResult.Ok(RADIO) }))
        c.start(key, MixSource.ONLINE, SEED) { emptySet() }
        advanceUntilIdle()
        assertEquals(SeedStatus.NotIdentified(MatchVerdict.NO_MATCH, 1, 0), c.state.value.seed)
        assertEquals(0, catalog.radios)
    }

    @Test
    fun theSameSeedIsIdentifiedOnceAndARadioAskedOnce() = runTest {
        val (c, catalog, _) = coordinator()
        repeat(3) { i ->
            c.start(key.copy(generation = i.toLong()), MixSource.ONLINE, SEED) { emptySet() }
            advanceUntilIdle()
        }
        assertEquals(1, catalog.searches)
        assertEquals(1, catalog.radios)
        // After the radio's TTL, the radio is asked again (the identification is kept longer).
        advanceTimeBy(OnlineMixCoordinator.RADIO_TTL_MS + 1)
        c.start(key.copy(generation = 9), MixSource.ONLINE, SEED) { emptySet() }
        advanceUntilIdle()
        assertEquals(1, catalog.searches)
        assertEquals(2, catalog.radios)
    }

    @Test
    fun rateLimitedPausesEveryCatalogRequest() = runTest {
        val catalog = FakeCatalog({ CatalogResult.Failed(CatalogFailure.RateLimited(30_000)) }, { CatalogResult.Ok(RADIO) })
        val (c, _, _) = coordinator(catalog)
        c.start(key, MixSource.ONLINE, SEED) { emptySet() }
        advanceUntilIdle()
        assertEquals(SeedStatus.Failed(CatalogFailure.RateLimited(30_000)), c.state.value.seed)
        assertEquals(1, catalog.searches)
        c.start(key.copy(generation = 2), MixSource.ONLINE, SEED) { emptySet() }
        advanceUntilIdle()
        assertEquals("nobody asks while paused", 1, catalog.searches)
        advanceTimeBy(30_001)
        catalog.search = { CatalogResult.Ok(listOf(SEED_SONG)) }
        c.start(key.copy(generation = 3), MixSource.ONLINE, SEED) { emptySet() }
        advanceUntilIdle()
        assertTrue(c.state.value.seed is SeedStatus.Identified)
    }

    @Test
    fun timeoutsAreRetriedWithBackoffThenGivenUp() = runTest {
        var calls = 0
        val catalog = FakeCatalog({ calls++; if (calls < 3) CatalogResult.Failed(CatalogFailure.Timeout) else CatalogResult.Ok(listOf(SEED_SONG)) }, { CatalogResult.Ok(RADIO) })
        val (c, _, _) = coordinator(catalog)
        c.start(key, MixSource.ONLINE, SEED) { emptySet() }
        advanceUntilIdle()
        assertEquals(3, calls)
        assertTrue(testScheduler.currentTime >= 4_000) // 1 s, then 3 s
        assertTrue(c.state.value.seed is SeedStatus.Identified)

        val failing = FakeCatalog({ CatalogResult.Failed(CatalogFailure.Http(503)) }, { CatalogResult.Ok(RADIO) })
        val (c2, _, _) = coordinator(failing)
        c2.start(key, MixSource.ONLINE, SEED.copy(title = "Other")) { emptySet() }
        advanceUntilIdle()
        assertEquals(3, failing.searches) // the first try and two retries
        assertEquals(SeedStatus.Failed(CatalogFailure.Http(503)), c2.state.value.seed)

        val schema = FakeCatalog({ CatalogResult.Failed(CatalogFailure.SchemaChanged("x")) }, { CatalogResult.Ok(RADIO) })
        val (c3, _, _) = coordinator(schema)
        c3.start(key, MixSource.ONLINE, SEED.copy(title = "Third")) { emptySet() }
        advanceUntilIdle()
        assertEquals("a changed schema is not retried", 1, schema.searches)
    }

    @Test
    fun eachOutcomeKeepsItsOwnMeaning() = runTest {
        val outcomes = ArrayDeque<PrepareOutcome>(listOf(
            PrepareOutcome.NotAvailable("no lossless copy"),
            PrepareOutcome.NotVerified("Tidal needs sign-in"),
            PrepareOutcome.RejectedAudio("not lossless: AAC"),
            PrepareOutcome.Held("mobile data"),
        ))
        val (c, _, _) = coordinator(preparer = FakePreparer { outcomes.removeFirstOrNull() ?: FakePreparer.ready(it) })
        c.start(key, MixSource.ONLINE, SEED) { emptySet() }
        // Without moving the clock: a held song is tried again only after its pause.
        runCurrent()
        val byId = c.state.value.candidates.associateBy { it.videoId.take(2) }
        assertEquals(CandidateStage.NOT_AVAILABLE, byId["r1"]!!.stage)
        assertEquals(Availability.ABSENT, byId["r1"]!!.availability)
        assertEquals(CandidateStage.NOT_VERIFIED, byId["r2"]!!.stage)
        assertEquals("not confirmed absent", Availability.UNKNOWN, byId["r2"]!!.availability)
        assertEquals(CandidateStage.REJECTED_AUDIO, byId["r3"]!!.stage)
        assertEquals(CandidateStage.HELD, byId["r4"]!!.stage)
        assertEquals("mobile data", byId["r4"]!!.detail)
        assertTrue(c.take(key, 5).isEmpty())
        // The pause over (mobile data allowed again, say), the held one is tried again.
        advanceTimeBy(OnlineMixCoordinator.HELD_RETRY_MS + 1)
        runCurrent()
        assertEquals(CandidateStage.READY, c.state.value.candidates.first { it.videoId.startsWith("r4") }.stage)
        // The final outcomes stay final.
        assertEquals(CandidateStage.NOT_AVAILABLE, c.state.value.candidates.first().stage)
    }

    @Test
    fun aDroppedConnectionIsRetriedAndImportedOnce() = runTest {
        var attempt = 0
        val preparer = FakePreparer { t -> attempt++; if (attempt == 1) throw IOException("connection reset") else FakePreparer.ready(t) }
        val (c, _, _) = coordinator(preparer = preparer)
        c.start(key, MixSource.ONLINE, SEED) { emptySet() }
        advanceUntilIdle()
        // r1 failed and waits; r2 was prepared meanwhile (one ahead).
        val first = c.state.value.candidates.first()
        assertEquals(CandidateStage.RETRY_LATER, first.stage)
        assertEquals("IOException", first.detail)
        assertEquals(listOf("r2000000000"), c.take(key, 5).map { it.candidate.videoId })
        runCurrent()
        assertEquals(listOf("r3000000000"), c.take(key, 5).map { it.candidate.videoId })
        runCurrent()
        // One ready ahead at most: r4 holds that place until it is taken.
        assertEquals(listOf("r4000000000"), c.take(key, 5).map { it.candidate.videoId })
        // Its backoff over, r1 is tried again and comes through.
        advanceTimeBy(OnlineMixCoordinator.RETRY_BASE_MS + 1)
        runCurrent()
        assertEquals(CandidateStage.READY, c.state.value.candidates.first().stage)
        assertEquals(listOf("r1000000000"), c.take(key, 5).map { it.candidate.videoId })
        assertEquals("r1 was prepared twice (one failure)", 2, preparer.calls.count { it == "r1000000000" })
        assertTrue("and handed out once", c.take(key, 5).none { it.candidate.videoId == "r1000000000" })
    }

    @Test
    fun nothingIsPreparedWhilePlaybackIsBusy() = runTest {
        val (c, _, preparer) = coordinator()
        c.setPlaybackBusy(true)
        c.start(key, MixSource.ONLINE, SEED) { emptySet() }
        advanceUntilIdle()
        assertTrue(preparer.calls.isEmpty())
        c.setPlaybackBusy(false)
        advanceUntilIdle()
        assertEquals(1, preparer.calls.size)
    }

    @Test
    fun aFastSkipDropsThatArtistsWaitingSongs() = runTest {
        val radio = listOf(SEED_SONG, song("a1", "One", "Alpha"), song("b1", "Two", "Beta"), song("a2", "Three", "Alpha"))
        val (c, _, _) = coordinator(FakeCatalog({ CatalogResult.Ok(listOf(SEED_SONG)) }, { CatalogResult.Ok(radio) }))
        c.start(key, MixSource.ONLINE, SEED) { emptySet() }
        advanceUntilIdle()
        val picked = c.take(key, 1).single()
        c.onFastSkip(key, picked.candidate.videoId)
        advanceUntilIdle()
        val stages = c.state.value.candidates.associate { it.videoId.take(2) to it.stage }
        assertEquals(CandidateStage.DROPPED, stages["a2"])
    }

    @Test
    fun whatTheQueueHasIsNotRecommended() = runTest {
        val queued = com.harmony.domain.library.discovery.TrackIdentity.of("Flori Ilegale", "Guess Who", 0).key
        val (c, _, _) = coordinator()
        c.start(key, MixSource.ONLINE, SEED) { setOf(queued) }
        advanceUntilIdle()
        assertTrue(c.state.value.candidates.none { it.videoId.startsWith("r2") })
    }
}

/** Whatever the platform throws, the online side reports it and the app goes on. */
@OptIn(ExperimentalCoroutinesApi::class)
class OnlineMixCoordinatorFailureTest {
    private val key = MixSessionKey(seedSongId = 7, startedAtMillis = 1, generation = 1)

    @Test
    fun anUnexpectedErrorIsReportedNotThrown() = runTest {
        val uncaught = mutableListOf<Throwable>()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob() +
            kotlinx.coroutines.CoroutineExceptionHandler { _, t -> uncaught += t })
        try {
            val catalog = FakeCatalog({ throw IllegalStateException("platform HTTP stack") }, { CatalogResult.Ok(RADIO) })
            val c = OnlineMixCoordinator(catalog, FakePreparer(), LocalCopies.NONE, scope, clock = { testScheduler.currentTime })
            c.start(key, MixSource.COMBINED, SEED) { emptySet() }
            advanceUntilIdle()
            assertEquals(SeedStatus.Failed(CatalogFailure.Unexpected("IllegalStateException")), c.state.value.seed)
            assertTrue("nothing escaped to the app", uncaught.isEmpty())

            // An Error while preparing (a missing class, a native library) is a failed attempt, not a crash.
            val preparer = FakePreparer { throw NoClassDefFoundError("x") }
            val c2 = OnlineMixCoordinator(FakeCatalog({ CatalogResult.Ok(listOf(SEED_SONG)) }, { CatalogResult.Ok(RADIO) }), preparer, LocalCopies.NONE, scope, clock = { testScheduler.currentTime })
            c2.start(key, MixSource.ONLINE, SEED) { emptySet() }
            runCurrent()
            assertEquals(CandidateStage.RETRY_LATER, c2.state.value.candidates.first().stage)
            assertEquals("NoClassDefFoundError", c2.state.value.candidates.first().detail)
            assertTrue(uncaught.isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
