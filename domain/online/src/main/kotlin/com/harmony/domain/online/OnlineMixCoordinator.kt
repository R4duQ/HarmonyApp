package com.harmony.domain.online

import com.harmony.core.model.MixSource

import com.harmony.domain.library.discovery.TrackIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * What the queue manager needs from the online side of a mix: start it from
 * a seed, take what is ready, say what the listener skipped, stop it.
 * [NONE] is the feed of a build or a setting without online mixes.
 */
interface OnlineMixFeed {
    val state: StateFlow<OnlineMixState>

    /** The session whose picks just became ready (the manager may top the queue up). */
    val readyChanged: Flow<MixSessionKey>

    /**
     * Starts the online side of the mix [session] from [seed]. [avoid] gives
     * the identity keys ([TrackIdentity.key]) that must not come again: the
     * queue, the recent plays. With [MixSource.OFFLINE] nothing starts and
     * no request is made.
     */
    fun start(session: MixSessionKey, source: MixSource, seed: SeedTrack, avoid: suspend () -> Set<String>)

    /** Ends the session: whatever is still running is cancelled, and its late results are dropped. */
    fun stop()

    /** Up to [max] picks ready now for [session] (none for any other), marked as queued. */
    fun take(session: MixSessionKey, max: Int): List<ReadyPick>

    /** The listener skipped [videoId] fast: its artist is avoided for the rest of the session. */
    fun onFastSkip(session: MixSessionKey, videoId: String)

    /** While true (buffering, a Cast handoff), nothing is prepared: playback comes first. */
    fun setPlaybackBusy(busy: Boolean)

    companion object {
        val NONE: OnlineMixFeed = object : OnlineMixFeed {
            override val state: StateFlow<OnlineMixState> = MutableStateFlow(OnlineMixState())
            override val readyChanged: Flow<MixSessionKey> = emptyFlow()
            override fun start(session: MixSessionKey, source: MixSource, seed: SeedTrack, avoid: suspend () -> Set<String>) = Unit
            override fun stop() = Unit
            override fun take(session: MixSessionKey, max: Int): List<ReadyPick> = emptyList()
            override fun onFastSkip(session: MixSessionKey, videoId: String) = Unit
            override fun setPlaybackBusy(busy: Boolean) = Unit
        }
    }
}

/**
 * The online side of "Mix din piesa asta": from the song playing to
 * recommendations that play.
 *
 *  1. **Identify** the seed: a songs search with its artist and title,
 *     decided by [SongMatcher]. Kept [IDENTIFY_TTL_MS] (a "not found" for
 *     [NOT_FOUND_TTL_MS]); one request per seed at a time.
 *  2. **Radio** from the confirmed id, always the seed's (the session's
 *     landmark, so the mix does not drift along the recommendations). Kept
 *     [RADIO_TTL_MS]; one request per id at a time.
 *  3. **Plan** with [OnlineMixPlanner]: songs only, no repeats of the queue,
 *     the recent plays or this session, a smaller batch when few pass.
 *  4. **Local copy**: a recording the library has plays from the library.
 *  5. **Prepare** the rest through [CandidatePreparer] (Mornye), one at a
 *     time, at most [LOOKAHEAD] ready and not yet queued, never while
 *     playback is busy. A temporary failure is tried again with backoff,
 *     at most [MAX_ATTEMPTS] times.
 *
 * Every result is applied only if its session and generation are still the
 * current ones: a mix stopped, restarted or replaced never receives the
 * answers of the one before.
 *
 * Catalog failures: a timeout, no network or a 5xx is retried twice (1 s,
 * 3 s); a 429 pauses every catalog request for its Retry-After (or
 * [RATE_LIMIT_PAUSE_MS]); a changed schema is not retried.
 */
class OnlineMixCoordinator(
    private val catalog: OnlineCatalog,
    private val preparer: CandidatePreparer,
    private val local: LocalCopies,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Ids, counts and timings only: never titles, URLs or keys. */
    private val log: (String) -> Unit = {},
    /** Something unexpected was caught (the app goes on): for the diagnostics log, with its stack. */
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
    /** Recognises a file by its sound when its tags don't find it (step 2). */
    private val fingerprinter: SongFingerprinter = SongFingerprinter.NONE,
    /** Told of each seed's radio, for the offline mix's taste memory. */
    private val radioObserver: RadioObserver = RadioObserver.NONE,
) : OnlineMixFeed {
    private val _state = MutableStateFlow(OnlineMixState())
    override val state: StateFlow<OnlineMixState> = _state.asStateFlow()

    private val _ready = MutableSharedFlow<MixSessionKey>(extraBufferCapacity = 8)
    override val readyChanged: Flow<MixSessionKey> = _ready.asSharedFlow()

    private val generation = AtomicLong(0)
    @Volatile private var job: Job? = null
    @Volatile private var wake = Channel<Unit>(Channel.CONFLATED)
    private val busy = MutableStateFlow(false)
    private val skippedArtists = HashSet<String>()

    private val identified = ExpiringCache<String, MatchOutcome>(200, clock)
    private val radios = ExpiringCache<String, CatalogResult<List<CatalogTrack>>>(50, clock)
    private val fingerprints = ExpiringCache<String, FingerprintResult>(200, clock)
    @Volatile private var pausedUntil = 0L

    /** The result of identifying a seed: a confirmed id, or why not. */
    private data class MatchOutcome(val result: MatchResult?, val failure: CatalogFailure?, val results: Int)

    override fun start(session: MixSessionKey, source: MixSource, seed: SeedTrack, avoid: suspend () -> Set<String>) {
        stop()
        if (source == MixSource.OFFLINE) return
        val gen = generation.incrementAndGet()
        synchronized(skippedArtists) { skippedArtists.clear() }
        wake = Channel(Channel.CONFLATED)
        _state.value = OnlineMixState(session = session, source = source, seed = SeedStatus.Identifying)
        job = scope.launch {
            try {
                run(gen, session, seed, avoid)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Whatever went wrong stays in the online side: the mix plays on from the library.
                apply(gen) { it.copy(seed = SeedStatus.Failed(CatalogFailure.Unexpected(t.javaClass.simpleName))) }
                log("ONLINE_MIX seed=${session.seedSongId} stopped=${t.javaClass.simpleName}")
                onError("session of seed ${session.seedSongId} stopped", t)
            }
        }
    }

    override fun stop() {
        generation.incrementAndGet()
        job?.cancel()
        job = null
        wake.trySend(Unit)
        _state.value = OnlineMixState()
    }

    override fun take(session: MixSessionKey, max: Int): List<ReadyPick> {
        if (max <= 0) return emptyList()
        var picks: List<ReadyPick> = emptyList()
        _state.update { s ->
            if (s.session != session) { picks = emptyList(); return@update s }
            val chosen = mutableListOf<ReadyPick>()
            val next = s.candidates.map { c ->
                if (chosen.size >= max || !c.ready) return@map c
                val pick = when {
                    c.stage == CandidateStage.IN_LIBRARY && c.localSongId != null -> ReadyPick.Local(c, c.localSongId)
                    c.stage == CandidateStage.READY && c.audio != null -> ReadyPick.Prepared(c, c.audio)
                    else -> null
                } ?: return@map c
                chosen += pick
                c.copy(stage = CandidateStage.QUEUED)
            }
            picks = chosen
            s.copy(candidates = next)
        }
        if (picks.isNotEmpty()) wake.trySend(Unit)
        return picks
    }

    override fun onFastSkip(session: MixSessionKey, videoId: String) {
        val s = _state.value
        if (s.session != session) return
        val skipped = s.candidates.firstOrNull { it.videoId == videoId } ?: return
        val artist = skipped.identityKey.substringBefore('|')
        synchronized(skippedArtists) { skippedArtists += artist }
        _state.update { st ->
            if (st.session != session) st else st.copy(candidates = st.candidates.map { c ->
                val waiting = c.stage == CandidateStage.FOUND || c.stage == CandidateStage.RETRY_LATER || c.stage == CandidateStage.HELD
                if (waiting && c.identityKey.substringBefore('|') == artist) c.copy(stage = CandidateStage.DROPPED, detail = "artist skipped") else c
            })
        }
        log("ONLINE_MIX skip video=$videoId")
    }

    override fun setPlaybackBusy(busy: Boolean) {
        this.busy.value = busy
        if (!busy) wake.trySend(Unit)
    }

    // -- the session ------------------------------------------------------------------------

    private fun current(gen: Long) = generation.get() == gen

    /** Applies [change] only while [gen] is the current generation. */
    private fun apply(gen: Long, change: (OnlineMixState) -> OnlineMixState) {
        _state.update { s -> if (current(gen)) change(s) else s }
    }

    private suspend fun run(gen: Long, session: MixSessionKey, seed: SeedTrack, avoid: suspend () -> Set<String>) {
        val started = clock()
        val known = seed.videoId?.takeIf(CatalogTrack::isVideoId)
        if (known != null) {
            // A song an online mix prepared: its id is the catalog's own, nothing to search for.
            apply(gen) { it.copy(seed = SeedStatus.Identified(known, 100, SeedBasis.KNOWN)) }
            log("ONLINE_MIX seed=${session.seedSongId} identify=known video=$known")
            follow(gen, session, seed, known, avoid)
            return
        }
        // Step 1: the tags as they are, then cleaned readings of them (the file's name split into
        // artist and title, a rip's notes taken off). A tag that names no artist can't be
        // matched, so then only the readings are searched.
        val readings = SeedCleaner.readings(seed)
        val tries = if (SeedCleaner.isUnknownArtist(seed.artist) && readings.isNotEmpty()) readings else listOf(seed) + readings
        var outcome: MatchOutcome? = null
        for (reading in tries) {
            val attempt = identify(reading)
            if (!current(gen)) return
            if (outcome == null) outcome = attempt
            val found = attempt.result?.takeIf { it.confirmed }
            if (found != null) {
                val seedId = found.track!!.videoId
                val basis = if (reading === seed) SeedBasis.TAGS else SeedBasis.NAME
                apply(gen) { it.copy(seed = SeedStatus.Identified(seedId, found.score, basis)) }
                log("ONLINE_MIX seed=${session.seedSongId} identify=${if (basis == SeedBasis.TAGS) "ok" else "name"} video=$seedId score=${found.score} ms=${clock() - started}")
                follow(gen, session, reading, seedId, avoid)
                return
            }
            if (attempt.failure != null) {
                // The catalog could not be asked: nothing below would fare better.
                apply(gen) { it.copy(seed = SeedStatus.Failed(attempt.failure)) }
                log("ONLINE_MIX seed=${session.seedSongId} identify=Failed ${attempt.failure.javaClass.simpleName} ms=${clock() - started}")
                return
            }
        }
        val first = outcome!!
        val match = first.result
        log("ONLINE_MIX seed=${session.seedSongId} identify=${match?.verdict ?: MatchVerdict.NO_MATCH} by tags results=${first.results} readings=${tries.size}")

        // Step 2: the tags were not enough; recognise the file by its sound.
        val heard = seed.uri?.let { recognise(it) }
        if (!current(gen)) return
        if (heard != null) {
            val byName = SeedTrack(heard.title, heard.artist, heard.album, seed.knownDurationMs, explicit = seed.explicit)
            val second = identify(byName)
            if (!current(gen)) return
            val found = second.result?.takeIf { it.confirmed }
            if (found != null) {
                val seedId = found.track!!.videoId
                apply(gen) { it.copy(seed = SeedStatus.Identified(seedId, found.score, SeedBasis.FINGERPRINT)) }
                log("ONLINE_MIX seed=${session.seedSongId} identify=fingerprint video=$seedId score=${found.score} ms=${clock() - started}")
                follow(gen, session, byName, seedId, avoid)
                return
            }
        }

        // Step 3: not the song itself; a radio from its artist, else from its genre.
        // Never "<unknown>": that would start the radio of whoever is called "Unknown".
        val artist = heard?.artist ?: SeedCleaner.artistOf(seed).orEmpty()
        if (artist.isNotBlank()) {
            val anchor = (ask { catalog.searchSongs(artist, SEARCH_LIMIT) } as? CatalogResult.Ok)?.value
                ?.let { LooseAnchors.byArtist(artist, it) }
            if (!current(gen)) return
            if (anchor != null) {
                apply(gen) { it.copy(seed = SeedStatus.Identified(anchor.videoId, 0, SeedBasis.ARTIST)) }
                log("ONLINE_MIX seed=${session.seedSongId} identify=artist video=${anchor.videoId} ms=${clock() - started}")
                follow(gen, session, seed, anchor.videoId, avoid)
                return
            }
        }
        val genre = LooseAnchors.usableGenre(seed.genre)
        if (genre != null) {
            val anchor = (ask { catalog.searchSongs(genre, SEARCH_LIMIT) } as? CatalogResult.Ok)?.value?.let(LooseAnchors::byGenre)
            if (!current(gen)) return
            if (anchor != null) {
                apply(gen) { it.copy(seed = SeedStatus.Identified(anchor.videoId, 0, SeedBasis.GENRE)) }
                log("ONLINE_MIX seed=${session.seedSongId} identify=genre video=${anchor.videoId} ms=${clock() - started}")
                follow(gen, session, seed, anchor.videoId, avoid)
                return
            }
        }
        val status = SeedStatus.NotIdentified(match?.verdict ?: MatchVerdict.NO_MATCH, first.results,
            match?.assessments?.count { it.track.kind == CatalogKind.SONG } ?: 0)
        apply(gen) { it.copy(seed = status) }
        log("ONLINE_MIX seed=${session.seedSongId} identify=none ms=${clock() - started}")
    }

    /** What the file's sound says it is (kept per file), or null. A failure is logged, never thrown. */
    private suspend fun recognise(uri: String): FingerprintResult.Found? {
        val result = fingerprints.getOrLoad(uri) {
            val r = try {
                fingerprinter.identify(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                onError("recognising a file by its sound failed", t)
                FingerprintResult.Failed(t.javaClass.simpleName)
            }
            r to when (r) {
                is FingerprintResult.Found -> IDENTIFY_TTL_MS
                FingerprintResult.NotFound -> NOT_FOUND_TTL_MS
                is FingerprintResult.Failed -> 0L
            }
        }
        log("ONLINE_MIX fingerprint=" + when (result) {
            is FingerprintResult.Found -> "found"
            FingerprintResult.NotFound -> "not-found"
            is FingerprintResult.Failed -> "failed (${result.reason})"
        })
        return result as? FingerprintResult.Found
    }

    /** From the seed's confirmed id: its radio, the plan, then preparing the candidates one by one. */
    private suspend fun follow(gen: Long, session: MixSessionKey, seed: SeedTrack, seedId: String, avoid: suspend () -> Set<String>) {
        var radioLimit = RADIO_LIMIT
        var expansions = 0
        if (!plan(gen, session, seed, seedId, radioLimit, avoid)) return
        while (current(gen)) {
            busy.first { !it }
            if (!current(gen)) return
            val s = _state.value
            val now = clock()
            val readyAhead = s.candidates.count { it.stage == CandidateStage.READY }
            if (readyAhead >= LOOKAHEAD) { wake.receive(); continue }
            val next = s.candidates.firstOrNull { c ->
                c.stage == CandidateStage.FOUND ||
                    ((c.stage == CandidateStage.RETRY_LATER || c.stage == CandidateStage.HELD) && c.retryAtMillis != null && c.retryAtMillis <= now)
            }
            if (next == null) {
                val soonest = s.candidates.mapNotNull { c ->
                    c.retryAtMillis.takeIf { c.stage == CandidateStage.RETRY_LATER || c.stage == CandidateStage.HELD }
                }.minOrNull()
                val nothingLeft = soonest == null && s.candidates.none { it.ready }
                if (nothingLeft && expansions < MAX_EXPANSIONS) {
                    expansions++
                    radioLimit *= 2
                    if (!plan(gen, session, seed, seedId, radioLimit, avoid)) return
                    continue
                }
                if (soonest != null) withTimeoutOrNull((soonest - now).coerceAtLeast(1)) { wake.receive() } else wake.receive()
                continue
            }
            prepareOne(gen, next)
        }
    }

    /** Plans a batch from the seed's radio. False when the session should end its online side. */
    private suspend fun plan(gen: Long, session: MixSessionKey, seed: SeedTrack, seedId: String, limit: Int, avoid: suspend () -> Set<String>): Boolean {
        val started = clock()
        val radio = radios.getOrLoad("$seedId:$limit") {
            val r = ask { catalog.radio(seedId, limit) }
            r to (if (r is CatalogResult.Ok) RADIO_TTL_MS else 0L)
        }
        if (!current(gen)) return false
        val tracks = when (radio) {
            is CatalogResult.Ok -> radio.value
            is CatalogResult.Failed -> {
                log("ONLINE_MIX seed=${session.seedSongId} radio=failed ${radio.failure.javaClass.simpleName}")
                // Nothing new to plan from; what is already there stays.
                return _state.value.candidates.isNotEmpty()
            }
        }
        if (limit == RADIO_LIMIT) remember(session, seed, tracks)
        val existing = _state.value.candidates
        val context = PlannerContext(
            seed = seed,
            seedVideoId = seedId,
            avoidKeys = avoid() + existing.map { it.identityKey },
            avoidVideoIds = existing.map { it.videoId }.toSet(),
            skippedArtists = synchronized(skippedArtists) { skippedArtists.toSet() },
        )
        val batch = OnlineMixPlanner.plan(tracks, context, want = BATCH)
        val fresh = batch.accepted.map { p -> OnlineCandidate(p.track, p.identityKey, p.score, p.reasons) }
        // The library's copy first: nothing to download for a recording it has.
        val withLocal = fresh.map { c ->
            val songId = try {
                local.find(c.track)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                onError("library lookup of video ${c.videoId} failed", t)
                null
            }
            if (songId != null) c.copy(stage = CandidateStage.IN_LIBRARY, localSongId = songId, detail = "in your library") else c
        }
        // Which recommendations the library has (ids only), to tell from the log why one was downloaded.
        log("ONLINE_MIX seed=${session.seedSongId} library " + withLocal.joinToString(" ") { "${it.videoId}=${if (it.localSongId != null) "yes" else "no"}" })
        if (!current(gen)) return false
        apply(gen) { it.copy(candidates = it.candidates + withLocal, turnedAway = batch.turnedAway.size) }
        log("ONLINE_MIX seed=${session.seedSongId} radio=${tracks.size} accepted=${fresh.size} local=${withLocal.count { it.localSongId != null }} " +
            "turned=${batch.turnedAway.size} ms=${clock() - started}")
        if (withLocal.any { it.ready }) _ready.tryEmit(session)
        return true
    }

    /** The seed's radio, for the taste memory: the song's own radio, or its artist's (never a genre's). */
    private suspend fun remember(session: MixSessionKey, seed: SeedTrack, tracks: List<CatalogTrack>) {
        val basis = (_state.value.seed as? SeedStatus.Identified)?.basis ?: return
        if (basis == SeedBasis.GENRE) return
        val songs = tracks.filter { it.kind == CatalogKind.SONG }
        if (songs.isEmpty()) return
        try {
            radioObserver.onRadio(session.seedSongId, seed, basis.exact, songs)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            onError("remembering a radio failed", t)
        }
    }

    private suspend fun prepareOne(gen: Long, candidate: OnlineCandidate) {
        val id = candidate.videoId
        val session = _state.value.session ?: return
        apply(gen) { s -> s.copy(candidates = s.candidates.map { if (it.videoId == id) it.copy(stage = CandidateStage.PREPARING, detail = null, progress = null) else it }) }
        val started = clock()
        val outcome = try {
            prepareWhileIdle(candidate.track) { p ->
                apply(gen) { s -> s.copy(candidates = s.candidates.map { if (it.videoId == id) it.copy(progress = p) else it }) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            onError("preparing video $id failed", t)
            PrepareOutcome.TemporaryError(t.javaClass.simpleName)
        }
        if (!current(gen)) return
        val attempts = candidate.attempts + 1
        val now = clock()
        val updated: (OnlineCandidate) -> OnlineCandidate = { c ->
            when (outcome) {
                is PrepareOutcome.Ready -> c.copy(stage = CandidateStage.READY, audio = outcome.audio, availability = Availability.CONFIRMED,
                    detail = outcome.audio.let { a -> listOfNotNull(a.codec, a.bitDepth?.let { "$it-bit" }, a.sampleRateHz?.let { "${it / 1000.0} kHz" }).joinToString(" · ") })
                is PrepareOutcome.NotAvailable -> c.copy(stage = CandidateStage.NOT_AVAILABLE, availability = Availability.ABSENT, detail = outcome.reason)
                is PrepareOutcome.NotVerified -> c.copy(stage = CandidateStage.NOT_VERIFIED, detail = outcome.reason)
                is PrepareOutcome.RejectedAudio -> c.copy(stage = CandidateStage.REJECTED_AUDIO, detail = outcome.reason)
                is PrepareOutcome.TemporaryError -> c.copy(stage = CandidateStage.RETRY_LATER, detail = outcome.reason,
                    retryAtMillis = if (attempts >= MAX_ATTEMPTS) null else now + (outcome.retryAfterMs ?: (RETRY_BASE_MS shl (attempts - 1))))
                is PrepareOutcome.Held -> c.copy(stage = CandidateStage.HELD, detail = outcome.reason,
                    retryAtMillis = now + (outcome.retryAfterMs ?: HELD_RETRY_MS))
            }.copy(attempts = attempts, progress = null)
        }
        apply(gen) { s -> s.copy(candidates = s.candidates.map { if (it.videoId == id) updated(it) else it }) }
        val why = when (outcome) {
            is PrepareOutcome.Ready -> outcome.audio.let { "${it.codec} ${it.bitDepth}/${it.sampleRateHz}" }
            is PrepareOutcome.NotAvailable -> outcome.reason
            is PrepareOutcome.NotVerified -> outcome.reason
            is PrepareOutcome.TemporaryError -> outcome.reason
            is PrepareOutcome.RejectedAudio -> outcome.reason
            is PrepareOutcome.Held -> outcome.reason
        }
        log("ONLINE_MIX seed=${session.seedSongId} prepare video=$id outcome=${outcome.javaClass.simpleName} ($why) attempt=$attempts ms=${clock() - started}")
        if (outcome is PrepareOutcome.Ready) _ready.tryEmit(session)
    }

    /** Cancellation waits for the native job's cleanup before another preparation can start. */
    private suspend fun prepareWhileIdle(track: CatalogTrack, progress: (Float?) -> Unit): PrepareOutcome = coroutineScope {
        val preparation = async { preparer.prepare(track, progress) }
        val playbackWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            busy.first { it }
            preparation.cancel(PlaybackBusyCancellation())
        }
        try {
            preparation.await()
        } catch (e: PlaybackBusyCancellation) {
            PrepareOutcome.Held("waiting for playback", retryAfterMs = 0)
        } finally {
            playbackWatcher.cancel()
        }
    }

    private class PlaybackBusyCancellation : CancellationException("playback needs the connection")

    // -- the catalog --------------------------------------------------------------------------

    private suspend fun identify(seed: SeedTrack): MatchOutcome {
        val key = TrackIdentity.of(seed.title, seed.artist, seed.knownDurationMs ?: 0).key + "|" + (SongMatcher.album(seed.album) ?: "")
        return identified.getOrLoad(key) {
            when (val r = ask { catalog.searchSongs(seed.query, SEARCH_LIMIT) }) {
                is CatalogResult.Failed -> MatchOutcome(null, r.failure, 0) to 0L
                is CatalogResult.Ok -> {
                    val match = SongMatcher.match(seed, r.value)
                    MatchOutcome(match, null, r.value.size) to (if (match.confirmed) IDENTIFY_TTL_MS else NOT_FOUND_TTL_MS)
                }
            }
        }
    }

    /** A catalog call with the retry and rate-limit rules. */
    private suspend fun <T> ask(call: suspend () -> CatalogResult<T>): CatalogResult<T> {
        var attempt = 0
        while (true) {
            val wait = pausedUntil - clock()
            if (wait > 0) return CatalogResult.Failed(CatalogFailure.RateLimited(wait))
            val result = call()
            val failure = (result as? CatalogResult.Failed)?.failure ?: return result
            if (failure is CatalogFailure.RateLimited) {
                pausedUntil = clock() + (failure.retryAfterMs ?: RATE_LIMIT_PAUSE_MS).coerceAtMost(MAX_RATE_LIMIT_PAUSE_MS)
                return result
            }
            val retryable = failure is CatalogFailure.Timeout || failure is CatalogFailure.Offline ||
                (failure is CatalogFailure.Http && failure.retryable)
            if (!retryable || attempt >= CATALOG_RETRIES.size) return result
            delay(CATALOG_RETRIES[attempt++])
        }
    }

    companion object {
        /** Prepared songs ready and not yet queued: the next one only, so nothing is downloaded far ahead. */
        const val LOOKAHEAD = 1
        const val BATCH = 12
        const val RADIO_LIMIT = 25
        const val MAX_EXPANSIONS = 1
        const val SEARCH_LIMIT = 10
        const val MAX_ATTEMPTS = 3
        const val RETRY_BASE_MS = 30_000L
        const val HELD_RETRY_MS = 60_000L
        const val IDENTIFY_TTL_MS = 7 * 24 * 3_600_000L
        const val NOT_FOUND_TTL_MS = 24 * 3_600_000L
        const val RADIO_TTL_MS = 30 * 60_000L
        const val RATE_LIMIT_PAUSE_MS = 60_000L
        const val MAX_RATE_LIMIT_PAUSE_MS = 10 * 60_000L
        val CATALOG_RETRIES = listOf(1_000L, 3_000L)
    }
}
