package com.harmony.domain.shuffle

import com.harmony.core.model.MoodFilter
import com.harmony.core.model.QueuedDecision
import com.harmony.core.model.SessionEvent
import com.harmony.core.model.ShuffleBrainTelemetry
import com.harmony.core.model.ShuffleMode
import com.harmony.core.model.Song
import com.harmony.domain.library.repository.LibraryRepository
import com.harmony.domain.library.repository.PlaybackHistoryRepository
import com.harmony.domain.playback.PlayOutcome
import com.harmony.domain.playback.PlaybackController
import com.harmony.domain.shuffle.brain.QueuePlan
import com.harmony.domain.shuffle.brain.QueueRequest
import com.harmony.domain.shuffle.brain.ShuffleBrain
import com.harmony.domain.shuffle.brain.ShuffleBrainLog
import com.harmony.domain.shuffle.brain.ShuffleBrainLogFormatter
import com.harmony.domain.shuffle.model.JourneyState
import com.harmony.domain.shuffle.model.ShuffleConfig
import com.harmony.domain.shuffle.model.SmartShuffleStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Runs Smart Shuffle and Journey on the player's queue, with the
 * [ShuffleBrain] (the native SmartShuffleEngine and its Diagnostic Brain)
 * choosing the songs.
 *
 * **Rolling queue.** The player's queue always holds the next songs before
 * they are needed, so gapless playback, a Bluetooth "next" with the app's UI
 * gone and the queue screen all keep working. When fewer than [REFILL_BELOW]
 * songs are left ahead, the brain builds a batch up to [QUEUE_TARGET] in one
 * pass: scored, sampled (Efraimidis-Spirakis), dithered against artist
 * clumps, chained for smooth transitions and audited.
 *
 * **Live feedback.** Every move to another song arrives as a [PlayOutcome]:
 * a play past half pulls the session vibe vector S_t toward the song, a fast
 * skip under 20% pushes it away and re-ranks the generated tail at once, a
 * song picked by hand (or swiped in as Play next) anchors S_t on itself.
 *
 * **What it never touches.** Songs the user queued (the Play next run, songs
 * added to the end) are kept through every re-rank; switching Smart Shuffle
 * off puts the original running order back.
 *
 * **Telemetry.** [telemetry] carries S_t, the last audit and the reason for
 * every upcoming pick, for ShuffleBrainDebugOverlay; each queue update is
 * also written to Logcat as a table (tag SHUFFLE_BRAIN).
 *
 * Exclusion per batch: the recently-played window, everything in the queue and
 * the last [SESSION_MEMORY] picks. If the brain is unavailable or returns too
 * few songs, random library picks fill the gap, so playback never stalls.
 */
@Singleton
class SmartQueueManager @Inject constructor(
    private val playback: PlaybackController,
    private val brain: ShuffleBrain,
    private val library: LibraryRepository,
    private val history: PlaybackHistoryRepository,
    private val brainLog: ShuffleBrainLog,
) {
    private val _config = MutableStateFlow(ShuffleConfig())
    val config: StateFlow<ShuffleConfig> = _config

    private val _journey = MutableStateFlow<JourneyState?>(null)
    val journey: StateFlow<JourneyState?> = _journey

    /** Which mood the current journey is heading toward, if any: UI only, for chip highlighting. */
    private val _journeyMood = MutableStateFlow<MoodFilter?>(null)
    val journeyMood: StateFlow<MoodFilter?> = _journeyMood

    private val _telemetry = MutableStateFlow(ShuffleBrainTelemetry(engineAvailable = brain.available))

    /** S_t, the last self-audit and why each upcoming song was picked. */
    val telemetry: StateFlow<ShuffleBrainTelemetry> = _telemetry.asStateFlow()

    private val _debugOverlayVisible = MutableStateFlow(false)

    /** Whether ShuffleBrainDebugOverlay is open over the player (a developer view; not persisted). */
    val debugOverlayVisible: StateFlow<Boolean> = _debugOverlayVisible.asStateFlow()

    fun setDebugOverlayVisible(visible: Boolean) {
        _debugOverlayVisible.value = visible
    }

    /** Serializes brain calls and queue writes, so a burst of events can't double-append. */
    private val mutex = Mutex()
    private val sessionPicks = ArrayDeque<Long>()
    private var managerScope: CoroutineScope? = null
    private var configRefreshJob: Job? = null
    private val random = Random(System.nanoTime())

    /** Why each song this manager queued was picked, by song id (bounded). */
    private val decisions = object : LinkedHashMap<Long, QueuedDecision>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, QueuedDecision>?) = size > 200
    }

    /** After a fast skip: the generated tail that was dropped, for the brain's Check 2. */
    private var pendingReplacedTail: List<Long>? = null

    private var journeyAnchorSongId: Long? = null

    /** Last user-selected song already handed over to Smart Shuffle. */
    private var lastHandoverSongId: Long? = null

    /**
     * The queue exactly as the user chose it, captured before Smart Shuffle
     * trimmed the linear tail, so switching shuffle OFF can put the original
     * running order back.
     */
    private var preShuffleQueue: List<Song>? = null

    /** Previous shuffle mode, to detect the ON -> OFF transition. */
    private var lastShuffleMode: ShuffleMode? = null

    /** Shuffle mode as of the previous signal, for the handover (see [handOverToSmartShuffle]). */
    private var lastHandoverMode: ShuffleMode? = null

    /** Last song reported to the brain as started, so each start is counted once. */
    private var lastStartedSongId: Long? = null

    /** For the swipe boost: the Play next run as of the previous signal. */
    private var lastPlayNextSongId: Long? = null
    private var lastPlayNextCount = 0

    private data class QueueSignal(
        val mode: ShuffleMode,
        val index: Int,
        val size: Int,
        val currentSongId: Long?,
        val playNextCount: Int,
        val advancedAutomatically: Boolean,
    )

    fun start(scope: CoroutineScope) {
        managerScope = scope
        scope.launch {
            mutex.withLock {
                runCatching {
                    brain.prepare()
                    brain.applyConfig(_config.value)
                }
            }
            publish(lastEvent = SessionEvent.NONE)
        }
        scope.launch {
            playback.playerState
                .map {
                    QueueSignal(
                        it.shuffleMode,
                        it.queueIndex,
                        it.queue.size,
                        it.currentSong?.id,
                        it.playNextCount,
                        it.advancedAutomatically,
                    )
                }
                .distinctUntilChanged()
                .collect { signal ->
                    endJourneyIfUserNavigatedAway(signal.currentSongId)
                    restoreOriginalOrderIfLeavingSmart(signal)
                    handOverToSmartShuffle(signal)
                    boostOnSwipe(signal)
                    noteTrackStarted(signal.currentSongId)
                    if (signal.mode == ShuffleMode.SMART || signal.mode == ShuffleMode.JOURNEY) {
                        maybeTopUp(signal.mode, signal.index, signal.size)
                    }
                    publishUpcoming()
                }
        }
        scope.launch {
            playback.playOutcomes.collect { onPlayOutcome(it) }
        }
        scope.launch {
            _config.collect { config -> mutex.withLock { runCatching { brain.applyConfig(config) } } }
        }
    }

    // -- Feedback -----------------------------------------------------------------------

    /**
     * A song was left. Past half: S_t moves toward it. Under 20% (a fast
     * skip): S_t is pushed away and the generated tail re-ranked, so the next
     * songs reflect the skip right away instead of four songs later.
     * Stepping back is not a verdict on the song and teaches nothing.
     */
    private suspend fun onPlayOutcome(outcome: PlayOutcome) {
        if (outcome.movedBackward) return
        val event = mutex.withLock {
            runCatching {
                brain.onPlaybackOutcome(
                    songId = outcome.songId,
                    fraction = outcome.fraction,
                    atMillis = outcome.atMillis,
                    shufflePick = outcome.songId in sessionPicks,
                )
            }.getOrDefault(SessionEvent.NONE)
        }
        if (event == SessionEvent.FAST_SKIP && playback.playerState.value.shuffleMode == ShuffleMode.SMART) {
            reRankAfterSkip(outcome.songId)
        }
        publish(lastEvent = event)
    }

    /**
     * Drops the generated tail behind the song now playing (never the user's
     * own queued songs), remembering it for the brain's Check 2; the queue
     * change refills it under the repelled S_t.
     */
    private suspend fun reRankAfterSkip(skippedId: Long) {
        // The outcome can arrive a beat before the player state shows the next song.
        withTimeoutOrNull(SKIP_SETTLE_MS) {
            playback.playerState.first { it.currentSong?.id != skippedId }
        } ?: return
        mutex.withLock {
            val state = playback.playerState.value
            // Only a skip to the next entry re-ranks; a song picked from
            // elsewhere is the handover's job (it anchors S_t on the pick).
            if (state.queue.getOrNull(state.queueIndex - 1)?.id != skippedId) return
            val generated = state.queue.indices
                .filter { it > state.queueIndex && state.queue[it].id in sessionPicks }
            if (generated.isEmpty()) return
            pendingReplacedTail = generated.map { state.queue[it].id }
            generated.sortedDescending().forEach { playback.removeFromQueue(it) }
        }
    }

    /**
     * The swipe boost: a song swiped in as Play next under Smart Shuffle is
     * an explicit choice, so S_t anchors on it and the generated tail behind
     * the Play next run is re-ranked around it.
     */
    private suspend fun boostOnSwipe(signal: QueueSignal) {
        val grew = signal.currentSongId != null &&
            signal.currentSongId == lastPlayNextSongId &&
            signal.playNextCount > lastPlayNextCount
        lastPlayNextSongId = signal.currentSongId
        lastPlayNextCount = signal.playNextCount
        if (!grew || signal.mode != ShuffleMode.SMART) return
        val state = playback.playerState.value
        val newest = state.queue.getOrNull(state.queueIndex + signal.playNextCount) ?: return
        val event = mutex.withLock { runCatching { brain.onManualSelect(newest.id) }.getOrDefault(SessionEvent.NONE) }
        publish(lastEvent = event)
        dropGeneratedTail(ShuffleMode.SMART)
    }

    /** Each newly started song goes to the brain once (Markov habits, S_t seeding). */
    private suspend fun noteTrackStarted(currentSongId: Long?) {
        if (currentSongId == null || currentSongId == lastStartedSongId) return
        lastStartedSongId = currentSongId
        val event = mutex.withLock { runCatching { brain.onTrackStarted(currentSongId) }.getOrDefault(SessionEvent.NONE) }
        if (event != SessionEvent.NONE) publish(lastEvent = event)
    }

    // -- Mode changes ---------------------------------------------------------------------

    /**
     * Leaving Smart Shuffle (for OFF or RANDOM) puts the user's original
     * running order back, without touching the song playing: only the
     * upcoming part of the queue is rewritten. Songs the user queued while
     * Smart Shuffle was on (the Play next run, songs added to the end) are
     * kept, at the front of what's coming.
     */
    private suspend fun restoreOriginalOrderIfLeavingSmart(signal: QueueSignal) {
        val mode = signal.mode
        val previous = lastShuffleMode
        lastShuffleMode = mode
        val wasSmart = previous == ShuffleMode.SMART || previous == ShuffleMode.JOURNEY
        if (!wasSmart) {
            // Shuffle was already off: any snapshot still held is stale.
            if (mode == ShuffleMode.OFF || mode == ShuffleMode.RANDOM) preShuffleQueue = null
            return
        }
        if (mode != ShuffleMode.OFF && mode != ShuffleMode.RANDOM) return

        // The session ends with Smart Shuffle; habits stay.
        mutex.withLock { runCatching { brain.resetSession() } }
        publish(lastEvent = SessionEvent.NONE)

        val original = preShuffleQueue ?: return
        val state = playback.playerState.value
        val currentId = state.currentSong?.id
        val position = original.indexOfFirst { it.id == currentId }
        val tail = if (position >= 0) original.drop(position + 1) else original

        // User-queued songs: the Play next run (counted) and songs added to
        // the end (recognised by elimination: not in the snapshot, not a pick).
        val originalIds = original.mapTo(HashSet()) { it.id }
        val currentTail = state.queue.drop(state.queueIndex + 1)
        val queuedByUser = currentTail.take(signal.playNextCount) +
            currentTail.drop(signal.playNextCount).filter {
                it.id !in originalIds && it.id !in sessionPicks
            }

        // A kept song that also sits in the restored order plays where the
        // user put it, not twice: one occurrence is dropped from the tail.
        val restored = tail.toMutableList()
        queuedByUser.forEach { queued ->
            val duplicate = restored.indexOfFirst { it.id == queued.id }
            if (duplicate >= 0) restored.removeAt(duplicate)
        }

        preShuffleQueue = null
        lastHandoverSongId = null
        sessionPicks.clear()
        if (restored.isEmpty() && queuedByUser.isEmpty()) return
        playback.removeQueueRange(state.queueIndex + 1, state.queue.size)
        if (queuedByUser.isNotEmpty()) playback.addToQueueAll(queuedByUser)
        if (restored.isNotEmpty()) playback.addToQueueAll(restored)
    }

    /**
     * When Smart Shuffle is on and the user starts a song from a list (an
     * album, a playlist), the queue becomes that whole list, and the
     * rolling top-up would never get a turn. So a NEW song chosen by hand
     * drops the linear tail (keeping the Play next run), anchors S_t on the
     * chosen song, and Smart Shuffle continues from there.
     *
     * Not for: switching shuffle on (a mode change, handled by
     * [onShuffleActivated]), our own picks, or rolling into the next entry.
     */
    private suspend fun handOverToSmartShuffle(signal: QueueSignal) {
        val previousMode = lastHandoverMode
        lastHandoverMode = signal.mode
        if (signal.mode != ShuffleMode.SMART) return
        val id = signal.currentSongId ?: return
        if (previousMode != ShuffleMode.SMART) {
            lastHandoverSongId = id
            if (preShuffleQueue == null) preShuffleQueue = playback.playerState.value.queue
            return
        }
        if (id in sessionPicks) return
        if (id == lastHandoverSongId) return
        if (signal.advancedAutomatically) {
            lastHandoverSongId = id
            return
        }
        val keepThrough = signal.index + signal.playNextCount
        val lastIndex = signal.size - 1
        lastHandoverSongId = id
        preShuffleQueue = playback.playerState.value.queue
        sessionPicks.clear()
        // The manual pick boost: S_t = normalize(0.85 v + 0.15 S_t-1).
        val event = mutex.withLock { runCatching { brain.onManualSelect(id) }.getOrDefault(SessionEvent.NONE) }
        publish(lastEvent = event)
        if (lastIndex > keepThrough) {
            playback.removeQueueRange(keepThrough + 1, signal.size)
        }
    }

    /** Ends a running journey the moment the user plays something that isn't part of it. */
    private fun endJourneyIfUserNavigatedAway(currentSongId: Long?) {
        if (_journey.value == null || currentSongId == null) return
        val belongsToJourney = currentSongId == journeyAnchorSongId || currentSongId in sessionPicks
        if (!belongsToJourney) finishJourney()
    }

    // -- Controls (the Smart Shuffle sheet) ---------------------------------------------

    fun setEnergyTarget(target: Float?) {
        val normalized = target?.coerceIn(0f, 1f)
        if (_config.value.energyTarget == normalized) return
        _config.value = _config.value.copy(energyTarget = normalized)
        scheduleSmartRefresh()
    }

    /** Apply a human-readable Smart Shuffle personality preset. */
    fun setSmartStyle(style: SmartShuffleStyle) {
        val activated = ensureShuffleActive()
        val preset = SmartPreset.forStyle(style)
        _config.value = _config.value.copy(
            style = style,
            familiarity = preset.familiarity,
            discovery = preset.discovery,
            variety = preset.variety,
            temperature = temperatureForVariety(preset.variety),
        )
        if (!activated) refreshUpcomingQueue()
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
        _config.value = _config.value.copy(variety = clamped, temperature = temperatureForVariety(clamped))
        scheduleSmartRefresh()
    }

    /** Restore DataStore values without turning shuffle on as a side effect. */
    fun applyPersistedSmartSettings(style: SmartShuffleStyle, familiarity: Float, discovery: Float, variety: Float) {
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
     * Choosing a mood is a request for it to take effect now, so this also
     * turns Smart Shuffle on when it is off (before the config write, so the
     * refresh sees the new mode).
     */
    fun setMoodFilter(mood: MoodFilter?) {
        val activated = mood != null && ensureShuffleActive()
        _config.value = _config.value.copy(moodFilter = mood)
        if (!activated) refreshUpcomingQueue()
    }

    /** @return true when this call switched Smart Shuffle on. */
    private fun ensureShuffleActive(): Boolean {
        val current = playback.playerState.value.shuffleMode
        if (current == ShuffleMode.SMART || current == ShuffleMode.JOURNEY) return false
        playback.setShuffleMode(ShuffleMode.SMART)
        onShuffleActivated()
        return true
    }

    /**
     * Called on a real transition INTO Smart Shuffle or Journey. A new
     * session starts (S_t reseeds from the song playing), the queue is
     * snapshotted for the restore, and the linear tail after the Play next
     * run is dropped so the rolling top-up takes over.
     */
    fun onShuffleActivated() {
        val state = playback.playerState.value
        if (preShuffleQueue == null) preShuffleQueue = state.queue
        state.currentSong?.id?.let { lastHandoverSongId = it }
        val scope = managerScope ?: return
        val current = state.currentSong?.id
        scope.launch {
            mutex.withLock {
                runCatching {
                    brain.resetSession()
                    if (current != null) brain.onTrackStarted(current)
                }
            }
            publish(lastEvent = SessionEvent.SEEDED)
        }
        // The linear tail runs from after the Play next run up to the first
        // song this manager queued: the top-up may already have appended a
        // batch by the time this runs, and that batch stays.
        val firstFree = (state.queueIndex + 1 + state.playNextCount).coerceAtMost(state.queue.size)
        val firstPick = (firstFree until state.queue.size).firstOrNull { state.queue[it].id in sessionPicks }
            ?: state.queue.size
        if (firstFree >= firstPick) return
        scope.launch { playback.removeQueueRange(firstFree, firstPick) }
    }

    /** Debounce continuous sliders so one finger drag causes one queue refresh, not dozens. */
    private fun scheduleSmartRefresh() {
        val scope = managerScope ?: return
        configRefreshJob?.cancel()
        configRefreshJob = scope.launch {
            delay(CONFIG_REFRESH_DEBOUNCE_MS)
            refreshUpcomingQueue()
        }
    }

    private fun refreshUpcomingQueue() = dropGeneratedTail(ShuffleMode.SMART, ShuffleMode.JOURNEY)

    /**
     * Removes the not-yet-played songs THIS manager queued, leaving the
     * user's own alone; the shorter queue refills under the new settings.
     * Ids stay in [sessionPicks], so the new picks really are different.
     */
    private fun dropGeneratedTail(vararg activeIn: ShuffleMode) {
        val scope = managerScope ?: return
        val state = playback.playerState.value
        if (state.shuffleMode !in activeIn) return
        val autoAddedAhead = state.queue.indices
            .filter { it > state.queueIndex && state.queue[it].id in sessionPicks }
            .sortedDescending()
        if (autoAddedAhead.isEmpty()) return
        scope.launch { autoAddedAhead.forEach { playback.removeFromQueue(it) } }
    }

    /** Regenerate only the generated Smart Shuffle tail (the Regenerate button). */
    fun regenerateSmartQueue() = dropGeneratedTail(ShuffleMode.SMART)

    // -- Journey (Sonic Adventure) ------------------------------------------------------

    /**
     * Begin a journey from the current song to [destinationSongId] over
     * [steps] songs: each step plays a real song near a point sliding along
     * the straight line between the two in the 24-D space.
     */
    suspend fun startJourney(destinationSongId: Long, steps: Int, mood: MoodFilter? = null): Boolean {
        val current = playback.playerState.value.currentSong ?: return false
        if (!brain.available || current.id == destinationSongId) return false
        _journey.value = JourneyState(
            fromSongId = current.id,
            toSongId = destinationSongId,
            totalSteps = steps.coerceIn(JourneyState.MIN_STEPS, JourneyState.MAX_STEPS),
        )
        _journeyMood.value = mood
        journeyAnchorSongId = current.id
        playback.setShuffleMode(ShuffleMode.JOURNEY)
        onShuffleActivated()
        return true
    }

    /** User pressed Cancel in the Smart Shuffle sheet. */
    fun cancelJourney() = finishJourney()

    /**
     * One teardown for every way a journey ends. [revertToSmart] is false
     * when the user explicitly chose a mode, which must win.
     */
    private fun finishJourney(revertToSmart: Boolean = true) {
        _journey.value = null
        _journeyMood.value = null
        journeyAnchorSongId = null
        if (revertToSmart && playback.playerState.value.shuffleMode == ShuffleMode.JOURNEY) {
            playback.setShuffleMode(ShuffleMode.SMART)
        }
    }

    /** The shuffle button: off turns Smart Shuffle on, anything else turns shuffle off. */
    fun toggleShuffle() {
        val current = playback.playerState.value.shuffleMode
        chooseShuffleMode(if (current == ShuffleMode.OFF) ShuffleMode.SMART else ShuffleMode.OFF)
    }

    /**
     * Picks a shuffle mode. Ends any running journey FIRST, and only a real
     * switch INTO Smart Shuffle clears the linear queue.
     */
    fun chooseShuffleMode(mode: ShuffleMode) {
        val wasSmart = playback.playerState.value.shuffleMode.let {
            it == ShuffleMode.SMART || it == ShuffleMode.JOURNEY
        }
        onShuffleModeChosen(mode)
        playback.setShuffleMode(mode)
        if (mode == ShuffleMode.SMART && !wasSmart) onShuffleActivated()
    }

    fun onShuffleModeChosen(mode: ShuffleMode) {
        if (_journey.value != null && mode != ShuffleMode.JOURNEY) finishJourney(revertToSmart = false)
    }

    // -- Filling the queue ----------------------------------------------------------------

    private suspend fun maybeTopUp(mode: ShuffleMode, queueIndex: Int, queueSize: Int) {
        val refillBelow = if (mode == ShuffleMode.JOURNEY) JOURNEY_LOOKAHEAD else REFILL_BELOW
        if (queueSize - queueIndex - 1 >= refillBelow) return
        mutex.withLock {
            // Re-check under the lock; state may have moved.
            val state = playback.playerState.value
            val current = state.currentSong ?: return
            val ahead = state.queue.size - state.queueIndex - 1
            if (ahead >= refillBelow) return
            val exclude = buildExcludeSet(state.queue.map { it.id }).toMutableSet()
            if (mode == ShuffleMode.JOURNEY) {
                fillJourney(current, ahead, exclude)
            } else {
                fillSmart(current, state.queue.drop(state.queueIndex + 1).map { it.id }, ahead, exclude)
            }
        }
    }

    /** One brain batch up to [QUEUE_TARGET], random picks for whatever it could not supply. */
    private suspend fun fillSmart(current: Song, prefix: List<Long>, ahead: Int, exclude: MutableSet<Long>) {
        val need = QUEUE_TARGET - ahead
        if (need <= 0) return
        val replaced = pendingReplacedTail.orEmpty()
        pendingReplacedTail = null
        val plan = if (brain.available) {
            runCatching {
                brain.prepare()
                brain.generate(
                    QueueRequest(
                        currentSongId = current.id,
                        prefixIds = prefix,
                        excludeIds = exclude,
                        replacedTail = replaced,
                        count = need,
                        seed = random.nextLong(),
                    ),
                )
            }.getOrDefault(QueuePlan.EMPTY)
        } else {
            QueuePlan.EMPTY
        }

        val byId = library.songsByIds(plan.songIds).associateBy { it.id }
        // A pick whose row cannot be loaded is skipped; the random valve below fills its slot.
        val songs = plan.songIds.distinct().mapNotNull { byId[it] }.toMutableList()
        songs.forEach { exclude.add(it.id) }
        var attempts = 0
        while (songs.size < need && attempts < MAX_FALLBACK_ATTEMPTS) {
            attempts++
            val id = library.randomSongId(exclude) ?: break
            exclude.add(id)
            library.songById(id)?.let { songs += it }
        }
        if (songs.isEmpty()) return

        val traces = plan.traces.associateBy { it.trackId }
        songs.forEach { song ->
            traces[song.id]?.let { decisions[song.id] = QueuedDecision(it, song.title, song.artist) }
            sessionPicks.add(song.id)
        }
        while (sessionPicks.size > SESSION_MEMORY) sessionPicks.removeFirst()
        playback.addToQueueAll(songs)

        publish(lastEvent = null, plan = plan)
        brainLog.write(ShuffleBrainLogFormatter.format(_telemetry.value, if (replaced.isEmpty()) "queue" else "re-rank"))
    }

    /** Journey steps, one song at a time; arriving hands back to Smart Shuffle. */
    private suspend fun fillJourney(current: Song, alreadyAhead: Int, exclude: MutableSet<Long>) {
        var ahead = alreadyAhead
        var previous = playback.playerState.value.queue.lastOrNull()?.id ?: current.id
        var attempts = 0
        while (ahead < JOURNEY_LOOKAHEAD && attempts < MAX_FALLBACK_ATTEMPTS) {
            attempts++
            val state = _journey.value ?: return
            if (state.isComplete) {
                finishJourney()
                return
            }
            val t = ((state.step + 1).toFloat() / state.totalSteps).coerceIn(0f, 1f)
            val plan = runCatching {
                brain.journeyStep(state.fromSongId, state.toSongId, t, previous, exclude)
            }.getOrDefault(QueuePlan.EMPTY)
            // No path (an endpoint not indexed yet, nothing left near the
            // line): hand back to Smart Shuffle rather than wander at random.
            val id = plan.songIds.firstOrNull() ?: run {
                finishJourney()
                return
            }
            exclude.add(id)
            val song = library.songById(id) ?: continue
            _journey.value = state.copy(step = state.step + 1)
            plan.traces.firstOrNull()?.let { decisions[id] = QueuedDecision(it, song.title, song.artist) }
            playback.addToQueue(song)
            sessionPicks.add(id)
            while (sessionPicks.size > SESSION_MEMORY) sessionPicks.removeFirst()
            previous = id
            ahead++
            publish(lastEvent = null, plan = plan)
            brainLog.write(ShuffleBrainLogFormatter.format(_telemetry.value, "journey step ${state.step + 1}/${state.totalSteps}"))
        }
    }

    private suspend fun buildExcludeSet(queueIds: List<Long>): Set<Long> =
        history.recentSongIds(_config.value.recentWindowMillis).toHashSet().apply {
            addAll(queueIds)
            addAll(sessionPicks)
        }

    // -- Telemetry and the self-test -------------------------------------------------------

    /** Runs the brain's synthetic self-test, logs it and puts the report in [telemetry]. */
    suspend fun runBrainSelfTest(): String {
        val report = mutex.withLock {
            runCatching { brain.runSelfTest() }.getOrElse { "RESULT: FAIL (self-test could not run: ${it.message})" }
        }
        brainLog.write(ShuffleBrainLogFormatter.formatSelfTest(report))
        _telemetry.value = _telemetry.value.copy(selfTestReport = report, updatedAtMillis = System.currentTimeMillis())
        return report
    }

    /** Refreshes S_t (and the audit, when a plan is given) in [telemetry]. */
    private suspend fun publish(lastEvent: SessionEvent?, plan: QueuePlan? = null) {
        val vector = runCatching { brain.sessionVector() }.getOrNull()
        val size = runCatching { brain.librarySize() }.getOrDefault(_telemetry.value.librarySize)
        val previous = _telemetry.value
        _telemetry.value = previous.copy(
            sessionVector = vector?.toList() ?: emptyList(),
            hasSession = vector != null,
            lastEvent = lastEvent ?: previous.lastEvent,
            audit = plan?.audit ?: previous.audit,
            upcoming = upcomingDecisions(),
            nowPlaying = playingDecision(),
            librarySize = size,
            engineAvailable = brain.available,
            updatedAtMillis = System.currentTimeMillis(),
        )
    }

    private fun publishUpcoming() {
        val upcoming = upcomingDecisions()
        val playing = playingDecision()
        val current = _telemetry.value
        if (upcoming != current.upcoming || playing != current.nowPlaying) {
            _telemetry.value = current.copy(upcoming = upcoming, nowPlaying = playing)
        }
    }

    private fun playingDecision(): QueuedDecision? =
        playback.playerState.value.currentSong?.id?.let { decisions[it] }

    private fun upcomingDecisions(): List<QueuedDecision> {
        val state = playback.playerState.value
        return state.queue.drop(state.queueIndex + 1).mapNotNull { decisions[it.id] }
    }

    private data class SmartPreset(val familiarity: Float, val discovery: Float, val variety: Float) {
        companion object {
            fun forStyle(style: SmartShuffleStyle) = when (style) {
                SmartShuffleStyle.BALANCED -> SmartPreset(0.55f, 0.45f, 0.45f)
                SmartShuffleStyle.FAMILIAR -> SmartPreset(0.85f, 0.15f, 0.30f)
                SmartShuffleStyle.DISCOVER -> SmartPreset(0.30f, 0.85f, 0.65f)
                SmartShuffleStyle.FLOW -> SmartPreset(0.50f, 0.35f, 0.28f)
            }
        }
    }

    companion object {
        /** The rolling queue: refill when fewer than [REFILL_BELOW] songs are left, up to [QUEUE_TARGET]. */
        const val QUEUE_TARGET = 8
        const val REFILL_BELOW = 4

        /** A journey is queued a step at a time, so it can end the moment the user leaves it. */
        const val JOURNEY_LOOKAHEAD = 4
        const val SESSION_MEMORY = 50
        private const val MAX_FALLBACK_ATTEMPTS = QUEUE_TARGET * 3
        private const val CONFIG_REFRESH_DEBOUNCE_MS = 220L
        private const val SKIP_SETTLE_MS = 1_500L

        fun temperatureForVariety(variety: Float): Float =
            (0.06f + variety.coerceIn(0f, 1f) * 0.58f).coerceIn(0.06f, 0.64f)
    }
}
