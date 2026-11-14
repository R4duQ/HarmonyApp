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
import org.junit.Assert.assertFalse
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
    fun aSeedWhoseIdIsKnownIsNotSearchedFor() = runTest {
        val radioAsked = mutableListOf<String>()
        val (c, catalog, _) = coordinator(FakeCatalog({ CatalogResult.Ok(listOf(SEED_SONG)) }, { id -> radioAsked += id; CatalogResult.Ok(RADIO) }))
        c.start(key, MixSource.ONLINE, SEED.copy(videoId = "knownVid001")) { emptySet() }
        advanceUntilIdle()
        assertEquals(SeedStatus.Identified("knownVid001", 100, SeedBasis.KNOWN), c.state.value.seed)
        assertEquals("no search for a song whose id is known", 0, catalog.searches)
        assertEquals(listOf("knownVid001"), radioAsked)
        assertTrue(c.state.value.candidates.isNotEmpty())
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
    fun bufferingInterruptsAnActivePreparationAndRetriesTheSameSong() = runTest {
        var cancelled = false
        val blocking = CompletableDeferred<Unit>()
        val preparer = FakePreparer { track ->
            if (!cancelled) {
                try { blocking.await() } finally { cancelled = true }
            }
            FakePreparer.ready(track)
        }
        val (c, _, _) = coordinator(preparer = preparer)
        c.start(key, MixSource.ONLINE, SEED) { emptySet() }
        runCurrent()
        assertEquals(CandidateStage.PREPARING, c.state.value.candidates.first().stage)

        c.setPlaybackBusy(true)
        runCurrent()
        assertTrue("the active transfer was cancelled", cancelled)
        assertEquals(CandidateStage.HELD, c.state.value.candidates.first().stage)
        assertTrue(c.take(key, 1).isEmpty())
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals("nothing else starts during buffering", 1, preparer.calls.size)

        c.setPlaybackBusy(false)
        runCurrent()
        assertEquals(CandidateStage.READY, c.state.value.candidates.first().stage)
        assertEquals(listOf("r1000000000", "r1000000000"), preparer.calls)
        assertEquals("r1000000000", c.take(key, 1).single().candidate.videoId)
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


/** Step 2 (the file's sound) and step 3 (the artist, the genre) when the tags don't find the song. */
@OptIn(ExperimentalCoroutinesApi::class)
class OnlineMixSeedFallbackTest {
    private val key = MixSessionKey(seedSongId = 7, startedAtMillis = 1, generation = 1)
    private val scopes = mutableListOf<CoroutineScope>()

    @After fun cancelScopes() = scopes.forEach { it.cancel() }

    /** A file whose tags say nothing useful. */
    private val badTags = SeedTrack("Track 05", "Unknown artist", null, 216_000, uri = "content://media/external/audio/media/5", genre = "Rock")
    private val inTheEnd = song("itheend", "In the End", "Linkin Park", ms = 216_000, album = "Hybrid Theory")
    private val byLp = song("numb00", "Numb", "Linkin Park", ms = 185_000)
    private val rockSong = song("rock01", "Some Rock Song", "Some Band", ms = 200_000)

    private fun TestScope.make(
        search: (String) -> List<CatalogTrack>,
        fingerprinter: SongFingerprinter,
    ): Pair<OnlineMixCoordinator, MutableList<String>> {
        val radios = mutableListOf<String>()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }
        val catalog = FakeCatalog({ q -> CatalogResult.Ok(search(q)) }, { id -> radios += id; CatalogResult.Ok(RADIO) })
        val c = OnlineMixCoordinator(catalog, FakePreparer(), LocalCopies.NONE, scope,
            clock = { testScheduler.currentTime }, fingerprinter = fingerprinter)
        return c to radios
    }

    @Test
    fun theFilesSoundFindsWhatItsTagsCouldNot() = runTest {
        var asked = 0
        val (c, radios) = make(
            search = { q -> if (q == "Linkin Park In the End") listOf(inTheEnd) else emptyList() },
            fingerprinter = { asked++; FingerprintResult.Found("In the End", "Linkin Park", "Hybrid Theory") },
        )
        c.start(key, MixSource.ONLINE, badTags) { emptySet() }
        advanceUntilIdle()
        assertEquals(SeedStatus.Identified(inTheEnd.videoId, 100, SeedBasis.FINGERPRINT), c.state.value.seed)
        assertEquals(listOf(inTheEnd.videoId), radios)
        // The same file again: the fingerprint is not asked twice.
        c.start(key.copy(generation = 2), MixSource.ONLINE, badTags) { emptySet() }
        advanceUntilIdle()
        assertEquals(1, asked)
    }

    @Test
    fun notRecognisedThenTheArtistsRadio() = runTest {
        val tagged = badTags.copy(title = "In the End (my rip)", artist = "Linkin Park")
        val (c, radios) = make(
            search = { q -> if (q == "Linkin Park") listOf(rockSong, byLp) else emptyList() },
            fingerprinter = { FingerprintResult.NotFound },
        )
        c.start(key, MixSource.ONLINE, tagged) { emptySet() }
        advanceUntilIdle()
        assertEquals(SeedStatus.Identified(byLp.videoId, 0, SeedBasis.ARTIST), c.state.value.seed)
        assertEquals("a song by the same artist, not the first result", listOf(byLp.videoId), radios)
        assertFalse(SeedBasis.ARTIST.exact)
    }

    @Test
    fun noArtistThenTheGenresRadioAndAMeaninglessGenreIsNotUsed() = runTest {
        val (c, radios) = make(search = { q -> if (q == "Rock") listOf(rockSong) else emptyList() }, fingerprinter = { FingerprintResult.Failed("offline") })
        c.start(key, MixSource.ONLINE, badTags) { emptySet() }
        advanceUntilIdle()
        assertEquals(SeedStatus.Identified(rockSong.videoId, 0, SeedBasis.GENRE), c.state.value.seed)
        assertEquals(listOf(rockSong.videoId), radios)

        val (c2, radios2) = make(search = { emptyList() }, fingerprinter = { FingerprintResult.NotFound })
        c2.start(key, MixSource.ONLINE, badTags.copy(genre = "(13)")) { emptySet() }
        advanceUntilIdle()
        assertTrue(c2.state.value.seed is SeedStatus.NotIdentified)
        assertTrue(radios2.isEmpty())
        assertEquals(null, LooseAnchors.usableGenre("Other"))
        assertEquals(null, LooseAnchors.usableGenre("  "))
        assertEquals("Hip-Hop", LooseAnchors.usableGenre("Hip-Hop"))
    }

    @Test
    fun aFingerprinterThatThrowsIsAFailedStepNotACrash() = runTest {
        val errors = mutableListOf<String>()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }
        val c = OnlineMixCoordinator(FakeCatalog({ CatalogResult.Ok(emptyList()) }, { CatalogResult.Ok(RADIO) }), FakePreparer(), LocalCopies.NONE, scope,
            clock = { testScheduler.currentTime }, onError = { what, _ -> errors += what },
            fingerprinter = { throw IllegalStateException("decoder") })
        c.start(key, MixSource.ONLINE, badTags.copy(genre = null)) { emptySet() }
        advanceUntilIdle()
        assertTrue(c.state.value.seed is SeedStatus.NotIdentified)
        assertEquals(listOf("recognising a file by its sound failed"), errors)
    }

    @Test
    fun aSongFoundByItsTagsIsNeverFingerprinted() = runTest {
        var asked = 0
        val (c, _) = make(search = { listOf(SEED_SONG) }, fingerprinter = { asked++; FingerprintResult.NotFound })
        c.start(key, MixSource.ONLINE, SEED.copy(uri = "content://x/1")) { emptySet() }
        advanceUntilIdle()
        assertEquals(SeedBasis.TAGS, (c.state.value.seed as SeedStatus.Identified).basis)
        assertEquals(0, asked)
    }
}

/** Step 1 again with the file's name: a song without tags is found by what its name says. */
@OptIn(ExperimentalCoroutinesApi::class)
class OnlineMixSeedReadingTest {
    private val key = MixSessionKey(seedSongId = 7, startedAtMillis = 1, generation = 1)
    private val scopes = mutableListOf<CoroutineScope>()

    @After fun cancelScopes() = scopes.forEach { it.cancel() }

    private val inTheEnd = song("itheend", "In the End", "Linkin Park", ms = 216_000, album = "Hybrid Theory")

    @Test
    fun aFileNamedArtistDashTitleIsFoundWithoutItsSound() = runTest {
        val queries = mutableListOf<String>()
        var fingerprinted = 0
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }
        val catalog = FakeCatalog({ q -> queries += q; CatalogResult.Ok(if (q == "Linkin Park In the End") listOf(inTheEnd) else emptyList()) },
            { CatalogResult.Ok(RADIO) })
        val c = OnlineMixCoordinator(catalog, FakePreparer(), LocalCopies.NONE, scope, clock = { testScheduler.currentTime },
            fingerprinter = { fingerprinted++; FingerprintResult.NotFound })
        val untagged = SeedTrack("02 - Linkin Park - In the End (Official Video)", "<unknown>", "<unknown>", 216_500, uri = "content://x/2")
        c.start(key, MixSource.ONLINE, untagged) { emptySet() }
        advanceUntilIdle()
        val seed = c.state.value.seed as SeedStatus.Identified
        assertEquals(inTheEnd.videoId, seed.videoId)
        assertEquals(SeedBasis.NAME, seed.basis)
        assertTrue(SeedBasis.NAME.exact)
        assertEquals("the raw tags, naming no artist, are not searched", listOf("Linkin Park In the End"), queries)
        assertEquals(0, fingerprinted)
    }

    @Test
    fun anUnknownArtistNeverStartsAnArtistRadio() = runTest {
        val queries = mutableListOf<String>()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }
        val catalog = FakeCatalog({ q -> queries += q; CatalogResult.Ok(listOf(song("unk00000001", "Whatever", "Unknown"))) }, { CatalogResult.Ok(RADIO) })
        val c = OnlineMixCoordinator(catalog, FakePreparer(), LocalCopies.NONE, scope, clock = { testScheduler.currentTime },
            fingerprinter = { FingerprintResult.NotFound })
        c.start(key, MixSource.ONLINE, SeedTrack("Track 05", "<unknown>", null, 200_000, uri = "content://x/5")) { emptySet() }
        advanceUntilIdle()
        assertTrue(c.state.value.seed is SeedStatus.NotIdentified)
        assertFalse("<unknown>" in queries)
    }
}

/** Each seed's radio is told to the taste memory: the song's own, the artist's, never a genre's. */
@OptIn(ExperimentalCoroutinesApi::class)
class OnlineMixRadioObserverTest {
    private val key = MixSessionKey(seedSongId = 7, startedAtMillis = 1, generation = 1)
    private val scopes = mutableListOf<CoroutineScope>()

    @After fun cancelScopes() = scopes.forEach { it.cancel() }

    private fun TestScope.run(seed: SeedTrack, search: (String) -> List<CatalogTrack>): List<Triple<Long, Boolean, Int>> {
        val heard = mutableListOf<Triple<Long, Boolean, Int>>()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()).also { scopes += it }
        val c = OnlineMixCoordinator(FakeCatalog({ q -> CatalogResult.Ok(search(q)) }, { CatalogResult.Ok(RADIO) }), FakePreparer(),
            LocalCopies.NONE, scope, clock = { testScheduler.currentTime }, fingerprinter = { FingerprintResult.NotFound },
            radioObserver = { id, _, same, tracks -> heard += Triple(id, same, tracks.size) })
        c.start(key, MixSource.ONLINE, seed) { emptySet() }
        advanceUntilIdle()
        return heard
    }

    @Test
    fun theSeedsOwnRadioIsRememberedSongsOnly() = runTest {
        val heard = run(SEED) { listOf(SEED_SONG) }
        assertEquals("one radio, the podcast episode left out", listOf(Triple(7L, true, RADIO.size - 1)), heard)
    }

    @Test
    fun anArtistsRadioTeachesArtistsOnlyAndAGenresNothing() = runTest {
        val byArtist = run(SeedTrack("Unfindable", "Subcarpați", null, 100_000)) { q ->
            if (q == "Subcarpați") listOf(song("other00001", "Other", "Subcarpați")) else emptyList()
        }
        assertEquals(listOf(Triple(7L, false, RADIO.size - 1)), byArtist)
        val byGenre = run(SeedTrack("Unfindable", "<unknown>", null, 100_000, genre = "Rock")) { q ->
            if (q == "Rock") listOf(song("rock000001", "Rock Song", "Band")) else emptyList()
        }
        assertTrue(byGenre.isEmpty())
    }
}
