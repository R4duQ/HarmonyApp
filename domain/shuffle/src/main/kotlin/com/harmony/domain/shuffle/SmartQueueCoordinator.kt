package com.harmony.domain.shuffle

import com.harmony.core.model.MoodFilter
import com.harmony.core.model.ShuffleMode
import com.harmony.domain.library.repository.LibraryRepository
import com.harmony.domain.library.repository.PlaybackHistoryRepository
import com.harmony.domain.playback.PlaybackController
import com.harmony.domain.shuffle.engine.JourneyEngine
import com.harmony.domain.shuffle.engine.SmartShuffleEngine
import com.harmony.domain.shuffle.model.JourneyState
import com.harmony.domain.shuffle.model.ShuffleConfig
import com.harmony.domain.shuffle.model.SmartShuffleStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The runtime that makes SMART and JOURNEY modes actually play: observes the
 * player, and whenever the queue is about to run out in one of those modes,
 * computes the successor and appends it.
 *
 * Append-ahead design (vs. intercepting "next"): the player's queue always
 * physically contains the next song BEFORE it's needed, which preserves
 * gapless playback (ExoPlayer pre-buffers the next item), works when a
 * Bluetooth "next" arrives with the app UI dead, and keeps the queue screen
 * honest — the user sees what's coming and can veto it by editing the queue.
 * We top up whenever fewer than [LOOKAHEAD] unplayed items remain.
 *
 * Exclusion set per pick = recently-played window (history) + everything in
 * the current queue. Two protections against pathological loops:
 *  - a session floor: never fewer than the last [SESSION_MEMORY] picks are
 *    excluded even if the history window is short;
 *  - fallback to uniform random over the library when the engine returns
 *    null (unanalyzed current song, tiny library) — Smart Shuffle degrades
 *    to plain shuffle instead of stalling playback.
 *
 * Lifecycle: started once from the Application; a Mutex serializes top-ups so
 * a burst of state emissions can't double-append.
 */
@Singleton
class SmartQueueCoordinator @Inject constructor(
    private val playback: PlaybackController,
    private val smartShuffle: SmartShuffleEngine,
    private val journeyEngine: JourneyEngine,
    private val library: LibraryRepository,
    private val history: PlaybackHistoryRepository,
) {

    private val _config = MutableStateFlow(ShuffleConfig())
    val config: StateFlow<ShuffleConfig> = _config

    private val _journey = MutableStateFlow<JourneyState?>(null)
    val journey: StateFlow<JourneyState?> = _journey

    /** Which mood the current journey is heading toward, if any — UI-only, for chip highlighting. */
    private val _journeyMood = MutableStateFlow<MoodFilter?>(null)
    val journeyMood: StateFlow<MoodFilter?> = _journeyMood

    private val topUpMutex = Mutex()
    private val sessionPicks = ArrayDeque<Long>()
    private var coordinatorScope: CoroutineScope? = null
    private var configRefreshJob: Job? = null

    /**
     * The song a running journey departed from. Together with [sessionPicks]
     * (everything this coordinator queued), it defines the set of tracks that
     * legitimately belong to the journey — anything else playing means the
     * user navigated away from it. See [endJourneyIfUserNavigatedAway].
     */
    private var journeyAnchorSongId: Long? = null

    /** Last user-selected song already handed over to Smart Shuffle. */
    private var lastHandoverSongId: Long? = null

    /**
     * The queue exactly as the user chose it, captured before Smart Shuffle
     * trimmed the linear tail. Kept so switching shuffle OFF can put the
     * original running order back rather than leaving the user stranded in
     * a queue containing only whatever shuffle happened to pick.
     */
    private var preShuffleQueue: List<com.harmony.core.model.Song>? = null

    /** Previous shuffle mode, to detect the ON -> OFF transition. */
    private var lastShuffleMode: ShuffleMode? = null

    /**
     * Rolling centroid of the embeddings of the songs this session has
     * actually played, L2-normalized. Null until the first analyzed song
     * starts, and reset whenever the user makes a deliberate choice.
     *
     * This is what stops Smart Shuffle from wandering: see
     * SmartShuffleEngine.findCandidates.
     */
    private var sessionAnchor: FloatArray? = null

    /** Last song already folded into [sessionAnchor], so it is counted once. */
    private var lastAnchoredSongId: Long? = null

    private data class QueueSignal(
        val mode: ShuffleMode,
        val index: Int,
        val size: Int,
        val currentSongId: Long?,
        val playNextCount: Int,
    )

    fun start(scope: CoroutineScope) {
        coordinatorScope = scope
        scope.launch {
            playback.playerState
                .map {
                    QueueSignal(
                        it.shuffleMode,
                        it.queueIndex,
                        it.queue.size,
                        it.currentSong?.id,
                        it.playNextCount,
                    )
                }
                .distinctUntilChanged()
                .collect { signal ->
                    endJourneyIfUserNavigatedAway(signal.currentSongId)
                    restoreOriginalOrderIfLeavingSmart(signal.mode)
                    handOverToSmartShuffle(signal)
                    // After the handover, so a manual song choice reseeds the
                    // anchor from that song rather than folding it into the
                    // vibe the user just walked away from.
                    updateSessionAnchor(signal.mode, signal.currentSongId)
                    if (signal.mode == ShuffleMode.SMART || signal.mode == ShuffleMode.JOURNEY) {
                        maybeTopUp(signal.mode, signal.index, signal.size)
                    }
                }
        }
    }

    /**
     * Leaving Smart Shuffle (for OFF or RANDOM) puts the user's original
     * running order back.
     *
     * Playback itself is left completely undisturbed: the currently playing
     * song is never re-set (so it does not restart or skip), only the
     * upcoming portion of the queue is rewritten. If the current song exists
     * in the snapshot, the queue continues from its position in that list —
     * i.e. exactly where it would have been had shuffle never been on. If
     * it doesn't (it was a Smart Shuffle pick from elsewhere in the
     * library), the original list simply follows it.
     */
    private suspend fun restoreOriginalOrderIfLeavingSmart(mode: ShuffleMode) {
        val previous = lastShuffleMode
        lastShuffleMode = mode
        val wasSmart = previous == ShuffleMode.SMART || previous == ShuffleMode.JOURNEY
        if (!wasSmart) return
        // Restore when leaving Smart for OFF *or* RANDOM: plain random
        // shuffles the queue that exists, and Smart Shuffle has trimmed
        // that queue down to a couple of tracks — so without putting the
        // real queue back first, RANDOM would have nothing to shuffle.
        if (mode != ShuffleMode.OFF && mode != ShuffleMode.RANDOM) return

        val original = preShuffleQueue ?: return
        val state = playback.playerState.value
        val currentId = state.currentSong?.id
        val position = original.indexOfFirst { it.id == currentId }
        val tail = if (position >= 0) original.drop(position + 1) else original
        preShuffleQueue = null
        lastHandoverSongId = null
        sessionPicks.clear()
        if (tail.isEmpty()) return
        playback.removeQueueRange(state.queueIndex + 1, state.queue.size)
        playback.addToQueueAll(tail)
    }

    /**
     * When Smart Shuffle is ON and the user starts a song from a list
     * (library, album, artist, playlist), [PlaySongsUseCase] replaces the
     * queue with that entire surrounding list — often hundreds of tracks.
     * [maybeTopUp] only ever appends once fewer than [LOOKAHEAD] songs
     * remain, so with a long linear tail sitting there it never gets a
     * turn: playback marches straight down the list and Smart Shuffle looks
     * broken even though the toggle says it is on. This is that bug.
     *
     * So: when a NEW, non-coordinator song starts under SMART, drop the
     * linear tail so Smart Shuffle picks up from the chosen song. Two things
     * are deliberately preserved:
     *  - the chosen song itself (obviously), and
     *  - any "play next" songs the user explicitly queued by swiping, which
     *    sit immediately after it — deleting those would throw away an
     *    explicit user instruction, which is far worse than the bug.
     *
     * Runs once per newly-selected song ([lastHandoverSongId]), so it can't
     * fight the user if they then re-queue something manually.
     */
    private suspend fun handOverToSmartShuffle(signal: QueueSignal) {
        if (signal.mode != ShuffleMode.SMART) return
        val id = signal.currentSongId ?: return
        if (id in sessionPicks) return          // we queued it: normal smart flow
        if (id == lastHandoverSongId) return    // already handled this selection
        // A manual song choice is an intentional vibe change. Snapshot the
        // user's current order, then drop every non-Play-Next tail item even
        // when that tail is short. That lets Smart Shuffle pivot immediately
        // around the new song rather than dragging the previous session vibe
        // through another 2-4 tracks.
        val keepThrough = signal.index + signal.playNextCount
        val lastIndex = signal.size - 1
        lastHandoverSongId = id
        preShuffleQueue = playback.playerState.value.queue
        sessionPicks.clear()
        // A hand-picked song is an explicit direction change, so the session
        // anchor starts over from it. Keeping the old centroid here would drag
        // the previous vibe into the next few picks, which is exactly what the
        // user just said they did not want.
        resetSessionAnchor()
        if (lastIndex > keepThrough) {
            playback.removeQueueRange(keepThrough + 1, signal.size)
        }
    }

    /**
     * Folds each newly started song into the session anchor, once.
     *
     * Only maintained while Smart Shuffle or a journey is running; leaving
     * either drops the anchor so the next session starts fresh instead of
     * inheriting a vibe from an hour ago. Journey does not read the anchor —
     * it steers itself — but it is kept warm so that handing back to Smart
     * Shuffle at the end of a journey lands somewhere sensible rather than
     * re-seeding from a single track.
     */
    private suspend fun updateSessionAnchor(mode: ShuffleMode, currentSongId: Long?) {
        if (mode != ShuffleMode.SMART && mode != ShuffleMode.JOURNEY) {
            resetSessionAnchor()
            return
        }
        if (currentSongId == null || currentSongId == lastAnchoredSongId) return
        lastAnchoredSongId = currentSongId
        sessionAnchor = smartShuffle.advanceSessionAnchor(
            anchor = sessionAnchor,
            songId = currentSongId,
            decay = SESSION_ANCHOR_DECAY,
        )
    }

    private fun resetSessionAnchor() {
        sessionAnchor = null
        lastAnchoredSongId = null
    }

    /**
     * Ends a running journey the moment the user starts playing something
     * that isn't part of it (tapping a song in the library, an album, a
     * playlist...).
     *
     * Without this, a journey could only ever end by completing, and
     * completion is only ever checked while topping up the queue — so
     * picking a fresh album (which fills the queue and stops top-ups from
     * running) left the journey permanently half-finished: the "Journey X%"
     * label stayed on screen forever, frozen at whatever step it had
     * reached, and the mode stayed JOURNEY even though nothing about the
     * journey was still happening. That is the "stuck" behaviour.
     */
    private fun endJourneyIfUserNavigatedAway(currentSongId: Long?) {
        if (_journey.value == null || currentSongId == null) return
        val belongsToJourney = currentSongId == journeyAnchorSongId ||
            currentSongId in sessionPicks
        if (!belongsToJourney) finishJourney()
    }

    fun setEnergyTarget(target: Float?) {
        val normalized = target?.coerceIn(0f, 1f)
        if (_config.value.energyTarget == normalized) return
        _config.value = _config.value.copy(energyTarget = normalized)
        scheduleSmartRefresh()
    }

    /** Apply a human-readable Smart Shuffle personality preset. */
    fun setSmartStyle(style: SmartShuffleStyle) {
        ensureShuffleActive()
        val preset = SmartPreset.forStyle(style)
        _config.value = _config.value.copy(
            style = style,
            familiarity = preset.familiarity,
            discovery = preset.discovery,
            variety = preset.variety,
            temperature = temperatureForVariety(preset.variety),
        )
        refreshUpcomingQueue()
    }

    fun setFamiliarity(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        if (_config.value.familiarity == clamped) return
        _config.value = _config.value.copy(familiarity = clamped)
        scheduleSmartRefresh()
    }

    fun setDiscovery(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        if (_config.value.discovery == clamped) return
        _config.value = _config.value.copy(discovery = clamped)
        scheduleSmartRefresh()
    }

    fun setVariety(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        if (_config.value.variety == clamped) return
        _config.value = _config.value.copy(
            variety = clamped,
            temperature = temperatureForVariety(clamped),
        )
        scheduleSmartRefresh()
    }

    /** Restore DataStore values without turning shuffle on as a side effect. */
    fun applyPersistedSmartSettings(
        style: SmartShuffleStyle,
        familiarity: Float,
        discovery: Float,
        variety: Float,
    ) {
        val v = variety.coerceIn(0f, 1f)
        _config.value = _config.value.copy(
            style = style,
            familiarity = familiarity.coerceIn(0f, 1f),
            discovery = discovery.coerceIn(0f, 1f),
            variety = v,
            temperature = temperatureForVariety(v),
        )
    }

    /**
     * Selecting a mood is a request for it to take effect NOW, so unlike the
     * raw config setter this also turns Smart Shuffle on if playback isn't
     * already in SMART or JOURNEY mode — otherwise choosing a mood while
     * shuffle is off silently does nothing (the mood only ever biases picks
     * that this coordinator makes, and it doesn't make any while inactive).
     * Activation happens BEFORE the config write so the subsequent
     * [refreshUpcomingQueue] call sees the new mode and actually clears the
     * stale queue instead of no-op'ing on the old OFF state.
     */
    fun setMoodFilter(mood: MoodFilter?) {
        if (mood != null) ensureShuffleActive()
        _config.value = _config.value.copy(moodFilter = mood)
        refreshUpcomingQueue()
    }

    private fun ensureShuffleActive() {
        val current = playback.playerState.value.shuffleMode
        if (current != ShuffleMode.SMART && current != ShuffleMode.JOURNEY) {
            playback.setShuffleMode(ShuffleMode.SMART)
        }
    }

    /**
     * Called when the user explicitly turns Smart Shuffle (or Journey) on
     * from the transport controls. Without this, activating shuffle has no
     * visible effect if the queue was already full of straight-through
     * tracks (e.g. a whole album just queued) — [maybeTopUp] only adds
     * beyond the existing lookahead, it never replaces songs that are
     * already sitting there. This drops everything not yet played,
     * regardless of who queued it, since turning shuffle on is an explicit
     * "shuffle starting now." The vacated slots are refilled automatically
     * by the same reactive pipeline [start] sets up (queue size changes ->
     * maybeTopUp runs). Deliberately NOT called for RANDOM: that mode relies
     * on ExoPlayer's own native shuffle order over the existing timeline, and
     * this coordinator never refills for RANDOM, so clearing here would just
     * leave the queue empty.
     */
    fun onShuffleActivated() {
        // Switching Smart Shuffle on starts a new session: the anchor reseeds
        // from whatever is playing rather than carrying over a stale centroid
        // from the last time it was on.
        resetSessionAnchor()
        val scope = coordinatorScope ?: return
        val state = playback.playerState.value
        val toRemove = (state.queueIndex + 1 until state.queue.size).sortedDescending()
        if (toRemove.isEmpty()) return
        scope.launch {
            toRemove.forEach { playback.removeFromQueue(it) }
        }
    }

    /**
     * Drops not-yet-played songs that this coordinator itself queued, so a
     * changed Energy Slider or mood filter is audible on the very next track
     * instead of only once the existing lookahead runs out on its own —
     * which, from a few songs deep, could be many minutes away and reads to
     * the user as "the mood picker doesn't do anything." Only songs tracked
     * in [sessionPicks] are removed; anything the user queued manually is
     * left untouched. Removing them shrinks the live queue, which the
     * existing playerState collector in [start] picks up automatically and
     * refills under the new config — no separate re-pick logic needed here.
     */
    /** Debounce continuous sliders so one finger drag causes one queue refresh, not dozens. */
    private fun scheduleSmartRefresh() {
        val scope = coordinatorScope ?: return
        configRefreshJob?.cancel()
        configRefreshJob = scope.launch {
            delay(CONFIG_REFRESH_DEBOUNCE_MS)
            refreshUpcomingQueue()
        }
    }

    private fun refreshUpcomingQueue() {
        dropGeneratedTail(ShuffleMode.SMART, ShuffleMode.JOURNEY)
    }

    /**
     * Removes the not-yet-played songs THIS coordinator queued, leaving
     * anything the user queued by hand alone. The vacated slots are refilled
     * automatically: a shorter queue is a playerState change, which re-enters
     * [maybeTopUp] under the new config.
     *
     * Ids stay in [sessionPicks] on purpose, so they remain in the exclude set
     * and a re-generated queue is actually different rather than the same
     * songs in a new order.
     */
    private fun dropGeneratedTail(vararg activeIn: ShuffleMode) {
        val scope = coordinatorScope ?: return
        val state = playback.playerState.value
        if (state.shuffleMode !in activeIn) return
        val autoAddedAhead = state.queue.indices
            .filter { it > state.queueIndex && state.queue[it].id in sessionPicks }
            .sortedDescending() // remove from the end first so earlier indices stay valid
        if (autoAddedAhead.isEmpty()) return
        scope.launch {
            autoAddedAhead.forEach { playback.removeFromQueue(it) }
        }
    }

    /**
     * Begin a journey from the current song to [destinationSongId] over
     * [steps] songs. [mood] is stored only for UI highlighting (which chip
     * is "active"); the actual trajectory is driven purely by embeddings.
     */
    suspend fun startJourney(destinationSongId: Long, steps: Int, mood: MoodFilter? = null): Boolean {
        val current = playback.playerState.value.currentSong ?: return false
        val state = journeyEngine.start(current.id, destinationSongId, steps) ?: return false
        _journey.value = state
        _journeyMood.value = mood
        journeyAnchorSongId = current.id
        playback.setShuffleMode(ShuffleMode.JOURNEY)
        onShuffleActivated() // clear any stale queued tail so the journey starts immediately
        return true
    }

    /** User pressed Cancel in the Smart Shuffle sheet. */
    fun cancelJourney() = finishJourney()

    /**
     * One teardown path for every way a journey can end (cancelled,
     * completed, or navigated away from), so none of them can leave half
     * the state behind.
     *
     * [revertToSmart] exists because the correct follow-up mode depends on
     * WHY the journey ended. A journey that completes or is abandoned should
     * hand back to Smart Shuffle. But when the user explicitly asks for a
     * mode — including "Off" — that request must win: reverting to SMART
     * there would immediately switch shuffle back on, which is exactly the
     * bug where pressing Off in the sheet left the shuffle button lit.
     */
    private fun finishJourney(revertToSmart: Boolean = true) {
        _journey.value = null
        _journeyMood.value = null
        journeyAnchorSongId = null
        if (revertToSmart && playback.playerState.value.shuffleMode == ShuffleMode.JOURNEY) {
            playback.setShuffleMode(ShuffleMode.SMART)
        }
    }

    /**
     * Called when the user picks a shuffle mode explicitly (sheet chips or
     * the transport toggle). Any running journey ends without overriding
     * the mode they just chose.
     */
    fun onShuffleModeChosen(mode: ShuffleMode) {
        if (_journey.value != null && mode != ShuffleMode.JOURNEY) {
            finishJourney(revertToSmart = false)
        }
    }

    private suspend fun maybeTopUp(mode: ShuffleMode, queueIndex: Int, queueSize: Int) {
        if (queueSize - queueIndex - 1 >= LOOKAHEAD) return
        topUpMutex.withLock {
            // Re-check under the lock; state may have moved.
            val state = playback.playerState.value
            val current = state.currentSong ?: return

            // Fill the whole lookahead in ONE pass. Previously this appended a
            // single song and relied on the resulting playerState emission to
            // re-enter — so refilling four slots ran the entire personalization
            // pipeline four times (songsByIds + behaviorStats + favoriteIds +
            // dateAddedByIds + recentSongIds, per slot). The recently-played
            // window is queried once here and the growing set is carried
            // forward locally.
            val exclude = buildExcludeSet(state.queue.map { it.id }).toMutableSet()
            var ahead = state.queue.size - state.queueIndex - 1

            var attempts = 0
            while (ahead < LOOKAHEAD && attempts < MAX_TOPUP_ATTEMPTS) {
                attempts++
                val picked = when (mode) {
                    ShuffleMode.JOURNEY -> {
                        val id = pickJourney(exclude)
                        // A journey that just arrived hands back to SMART, and
                        // the mode change re-enters here. Falling through to
                        // the random valve instead would pad the rest of this
                        // pass with unrelated songs.
                        if (id == null && _journey.value == null) return
                        id
                    }
                    else -> {
                        val context = buildSessionContext(current.id)
                        smartShuffle.pickNext(
                            currentSongId = current.id,
                            config = _config.value,
                            excludeSongIds = exclude,
                            recentArtists = context.recentArtists,
                            sessionGenres = context.sessionGenres,
                            sessionAnchor = sessionAnchor,
                        )
                    }
                }
                val nextId = picked ?: randomFallback(exclude) ?: return

                // A pick whose row cannot be loaded must not silently end the
                // pass: excluding it and continuing keeps the queue filling
                // instead of stalling until the next state change.
                exclude.add(nextId)
                val song = library.songById(nextId)
                if (song == null) continue

                playback.addToQueue(song)
                sessionPicks.add(nextId)
                while (sessionPicks.size > SESSION_MEMORY) sessionPicks.removeFirst()
                ahead++
            }
        }
    }

    private suspend fun pickJourney(exclude: Set<Long>): Long? {
        val state = _journey.value ?: return null
        if (state.isComplete) {
            // Arrived: hand over to Smart Shuffle from wherever we landed.
            finishJourney()
            return null
        }
        val result = journeyEngine.pickNext(state, _config.value, exclude) ?: return null
        _journey.value = result.second
        return result.first
    }

    /**
     * Regenerate only the auto-generated Smart Shuffle tail. Manually queued
     * songs are preserved. Removed picks remain in session memory so pressing
     * Regenerate actually produces a meaningfully different preview.
     */
    fun regenerateSmartQueue() {
        dropGeneratedTail(ShuffleMode.SMART)
    }

    private data class SessionContext(
        /** Most recent first — the engine decays the cooldown along this order. */
        val recentArtists: List<String>,
        val sessionGenres: Set<String>,
    )

    /**
     * The engine now decays artist cooldown by position, so this has to hand
     * back an ORDERED list, most recent first. [library.songsByIds] returns
     * rows in whatever order the query produces, so the ids are re-indexed
     * explicitly rather than trusting the result order.
     */
    private suspend fun buildSessionContext(currentSongId: Long): SessionContext {
        val ids = (listOf(currentSongId) + sessionPicks.toList().asReversed())
            .distinct()
            .take(ARTIST_COOLDOWN_SONGS + 1)
        val byId = library.songsByIds(ids).associateBy { it.id }
        val ordered = ids.mapNotNull { byId[it] }
        return SessionContext(
            recentArtists = ordered
                .map { SmartShuffleEngine.normalizeArtist(it.artist) }
                .filter { it.isNotBlank() },
            sessionGenres = ordered.flatMap { SmartShuffleEngine.genreTokens(it.genre) }.toSet(),
        )
    }

    private suspend fun buildExcludeSet(queueIds: List<Long>): Set<Long> =
        history.recentSongIds(_config.value.recentWindowMillis).toHashSet()
            .apply {
                addAll(queueIds)
                addAll(sessionPicks)
            }

    /**
     * Random fallback: correctness valve guaranteeing playback continues
     * before analysis covers the library. Phase 9: now a DB-side RANDOM()
     * sample instead of materializing all 20k songs into memory per pick.
     */
    private suspend fun randomFallback(exclude: Set<Long>): Long? =
        library.randomSongId(exclude)

    private data class SmartPreset(
        val familiarity: Float,
        val discovery: Float,
        val variety: Float,
    ) {
        companion object {
            fun forStyle(style: SmartShuffleStyle) = when (style) {
                SmartShuffleStyle.BALANCED -> SmartPreset(0.55f, 0.45f, 0.45f)
                SmartShuffleStyle.FAMILIAR -> SmartPreset(0.85f, 0.15f, 0.30f)
                SmartShuffleStyle.DISCOVER -> SmartPreset(0.30f, 0.85f, 0.65f)
                SmartShuffleStyle.FLOW -> SmartPreset(0.50f, 0.35f, 0.28f)
            }
        }
    }

    private companion object {
        // Four upcoming tracks makes the Smart Queue Preview useful while
        // still keeping enough freedom to react quickly to slider changes.
        const val LOOKAHEAD = 4
        const val SESSION_MEMORY = 50
        const val ARTIST_COOLDOWN_SONGS = 4

        /**
         * How much of the session anchor survives each newly played song.
         * One knob covering the whole useful range:
         *
         *   0.00f — no session memory. Every pick is anchored only to the
         *           track playing now: this is exactly the v2.0 random walk.
         *   0.82f — default. Roughly a five-song memory, so a set can still
         *           evolve over twenty tracks without lurching.
         *   0.95f — the session holds its ground hard; expect a set to stay
         *           recognisably where it started for an hour.
         *   1.00f — frozen. The anchor is whatever song the session began on
         *           and never moves until the user picks a song by hand.
         *
         * Raise it if Smart Shuffle still drifts; lower it if a set feels
         * stuck and refuses to go anywhere.
         */
        const val SESSION_ANCHOR_DECAY = 0.82f

        /**
         * Safety valve for the batched top-up: a pick whose library row fails
         * to load is skipped and retried, and this bounds that retry so a
         * pathological library cannot spin inside the mutex.
         */
        const val MAX_TOPUP_ATTEMPTS = LOOKAHEAD * 3
        const val CONFIG_REFRESH_DEBOUNCE_MS = 220L

        fun temperatureForVariety(variety: Float): Float =
            (0.06f + variety.coerceIn(0f, 1f) * 0.58f).coerceIn(0.06f, 0.64f)
    }
}
