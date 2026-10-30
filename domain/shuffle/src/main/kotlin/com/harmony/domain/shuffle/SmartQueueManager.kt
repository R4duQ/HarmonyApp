package com.harmony.domain.shuffle

import com.harmony.core.model.MoodFilter
import com.harmony.core.model.PlayerState
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Orders the player's queue for every shuffle mode, with the [ShuffleBrain]
 * (the native SmartShuffleEngine and its Diagnostic Brain) choosing the
 * Smart Shuffle and Journey songs.
 *
 * **One order, played as shown.** ExoPlayer's own shuffle is never on (see
 * PlaybackConnection.setShuffleMode): RANDOM is a physical shuffle of the
 * upcoming songs done here, SMART and JOURNEY append the brain's picks, and
 * the player always plays the queue's linear order, the order the queue
 * screen shows.
 *
 * **The queue is stable.** Indices 0..current (what was played and what
 * plays) are never rewritten, so Previous always goes back to the exact
 * songs just heard. Moving around never rebuilds anything:
 *
 *  - a song ending, or Next (however early): the queue stays exactly as it
 *    is; when fewer than [REFILL_BELOW] songs are left ahead, a few picks
 *    are appended at the very end, up to [QUEUE_TARGET];
 *  - Previous: seekToPrevious into the history, nothing else;
 *  - a tap on a queued song: seekTo(index), nothing else (a song the brain
 *    didn't pick anchors the session vibe on it, without touching the queue).
 *
 * A fast skip still teaches the brain (S_t moves away from the skipped
 * song), so the NEXT batch appended at the end reflects it; the songs
 * already in the queue are never swapped out because of a skip.
 *
 * Only a NEW list (an album or playlist started, [PlayerState.queueGeneration])
 * hands over: the session anchors on the chosen song and Smart Shuffle
 * continues after it. Songs the user queued (the Play next run, songs added
 * to the end) are never replaced; switching shuffle off puts the original
 * running order back.
 *
 * **Re-entrancy.** The manager's own queue writes change the player state,
 * which this manager listens to. While a write is in progress
 * ([isMutatingQueue]) those changes are not acted on; once it ends, the
 * latest state is looked at once.
 *
 * **Telemetry.** [telemetry] carries S_t, the last audit and the reason for
 * every upcoming pick, for ShuffleBrainDebugOverlay; each queue update is
 * also written to Logcat as a table (tag SHUFFLE_BRAIN).
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

    /** Serializes brain calls and queue writes, so a burst of events can't double-append. Not reentrant. */
    private val mutex = Mutex()
    private var managerScope: CoroutineScope? = null
    private var configRefreshJob: Job? = null
    private val random = Random(System.nanoTime())

    /**
     * The re-entrancy guard: depth of queue writes this manager has in
     * progress. Read and written on the manager's scope (the main thread).
     */
    private var mutationDepth = 0

    /** Whether [songId] was put in the current queue by this manager (a Smart Shuffle pick). Main thread. */
    fun isPick(songId: Long): Boolean = songId in generated

    /** True while this manager is writing the queue; its own changes are not reacted to. */
    val isMutatingQueue: Boolean get() = mutationDepth > 0

    /**
     * Ids of the songs this manager put into the CURRENT queue: the only ones
     * a re-rank may replace. Cleared when the user starts a new list.
     */
    private val generated = LinkedHashSet<Long>()

    /** Recent picks across queues, so a new batch doesn't repeat them (bounded by [SESSION_MEMORY]). */
    private val sessionPicks = ArrayDeque<Long>()

    /** Why each song this manager queued was picked, by song id (bounded). */
    private val decisions = object : LinkedHashMap<Long, QueuedDecision>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, QueuedDecision>?) = size > 200
    }

    /** The pending re-rank (new Smart Shuffle settings, or the Regenerate button). */
    private var reRankJob: Job? = null

    private var journeyAnchorSongId: Long? = null

    /**
     * The queue exactly as the user chose it, captured before shuffling
     * changed the upcoming part, so switching shuffle OFF can put the
     * original running order back.
     */
    private var preShuffleQueue: List<Song>? = null

    /** Shuffle mode as of the previous signal (null before the first). */
    private var lastShuffleMode: ShuffleMode? = null

    /** Queue generation as of the previous signal (null before the first: that one is the baseline). */
    private var lastGeneration: Int? = null

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
        val generation: Int,
    )

    private fun PlayerState.toSignal() =
        QueueSignal(shuffleMode, queueIndex, queue.size, currentSong?.id, playNextCount, queueGeneration)

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
                .map { it.toSignal() }
                .distinctUntilChanged()
                .collect { signal ->
                    // Our own writes: looked at once, when they are done (see mutateQueue).
                    if (isMutatingQueue) {
                        publishUpcoming()
                        return@collect
                    }
                    react(signal)
                }
        }
        scope.launch {
            playback.playOutcomes.collect { onPlayOutcome(it) }
        }
        scope.launch {
            _config.collect { config -> mutex.withLock { runCatching { brain.applyConfig(config) } } }
        }
        scope.launch {
            // A Bluetooth/notification shuffle button: the same as the app's own.
            playback.externalShuffleRequests.collect { toggleShuffle() }
        }
    }

    /** Everything a change of the player's state can call for, in order. */
    private suspend fun react(signal: QueueSignal) {
        endJourneyIfUserNavigatedAway(signal.currentSongId)
        onModeTransition(signal)
        handOverOnNewQueue(signal)
        boostOnSwipe(signal)
        noteTrackStarted(signal.currentSongId)
        val mode = playback.playerState.value.shuffleMode
        if (mode == ShuffleMode.SMART || mode == ShuffleMode.JOURNEY) {
            maybeTopUp(mode, signal.index, signal.size)
        }
        publishUpcoming()
    }

    /**
     * Runs one of this manager's queue writes with the re-entrancy guard up:
     * the state changes it causes are not reacted to while it runs, and the
     * resulting state is looked at once afterwards (launched, so it never
     * runs inside a caller's [mutex]).
     */
    private suspend fun <T> mutateQueue(block: suspend () -> T): T {
        mutationDepth++
        try {
            return block()
        } finally {
            mutationDepth--
            if (mutationDepth == 0) {
                managerScope?.launch { if (!isMutatingQueue) react(playback.playerState.value.toSignal()) }
            }
        }
    }

    /** A queue write from outside the [mutex]. */
    private suspend fun writeQueue(block: suspend () -> Unit) = mutex.withLock { mutateQueue(block) }

    // -- Transport (PlayerViewModel's buttons and the queue sheet) ------------------------

    /** Next: a seek, never a queue change (a fast skip only teaches the brain, see [onPlayOutcome]). */
    fun skipToNext() = playback.skipToNext()

    /** Previous: back into the history (or to the start of the song), never a queue change. */
    fun skipToPrevious() = playback.skipToPrevious()

    /**
     * A tap on a song in the queue: a seek to it, nothing else. A song the
     * brain didn't pick (one the user queued, or one of the original list)
     * anchors the session vibe on it; the queue itself is not touched.
     */
    fun playQueueItem(index: Int) {
        val state = playback.playerState.value
        val target = state.queue.getOrNull(index) ?: return
        if (index == state.queueIndex) {
            playback.seekTo(0)
            return
        }
        playback.skipToQueueItem(index)
        if (state.shuffleMode == ShuffleMode.SMART && target.id !in generated) {
            val scope = managerScope ?: return
            scope.launch {
                val event = mutex.withLock { runCatching { brain.onManualSelect(target.id) }.getOrDefault(SessionEvent.NONE) }
                publish(lastEvent = event)
            }
        }
    }

    // -- Feedback -----------------------------------------------------------------------

    /**
     * A song was left. Past half: S_t moves toward it. Under 20% (a fast
     * skip): S_t is pushed away. Either way only the brain learns: the queue
     * is not touched, and the next batch appended at the end follows the new
     * S_t. Stepping back teaches nothing.
     */
    private suspend fun onPlayOutcome(outcome: PlayOutcome) {
        if (outcome.movedBackward) return
        val event = mutex.withLock {
            runCatching {
                brain.onPlaybackOutcome(
                    songId = outcome.songId,
                    fraction = outcome.fraction,
                    atMillis = outcome.atMillis,
                    shufflePick = outcome.songId in generated,
                )
            }.getOrDefault(SessionEvent.NONE)
        }
        publish(lastEvent = event)
    }

    /**
     * Runs a re-rank the user asked for (the Smart Shuffle settings or the
     * Regenerate button; never moving around the queue), once at a time.
     * A re-rank scheduled before the user started a new list is dropped.
     */
    private fun scheduleReRank() {
        val scope = managerScope ?: return
        val generation = playback.playerState.value.queueGeneration
        reRankJob?.cancel()
        reRankJob = scope.launch {
            if (playback.playerState.value.queueGeneration != generation) return@launch
            reRankTail()
        }
    }

    /**
     * Replaces the generated picks after the protected head with a new brain
     * batch, in one replaceMediaItems. The protected head: the song playing,
     * the next one (loaded for gapless/crossfade) and the whole Play next run.
     * Songs the user queued that sat among the picks are kept, in front.
     */
    private suspend fun reRankTail() = mutex.withLock {
        val state = playback.playerState.value
        if (state.shuffleMode != ShuffleMode.SMART) return@withLock
        val current = state.currentSong ?: return@withLock
        val index = state.queueIndex
        val from = (index + 1 + maxOf(1, state.playNextCount)).coerceAtMost(state.queue.size)
        val tail = state.queue.subList(from, state.queue.size)
        val replaced = tail.filter { it.id in generated }.map { it.id }
        if (replaced.isEmpty()) return@withLock
        val kept = tail.filter { it.id !in generated }
        val prefixIds = state.queue.subList(index + 1, from).map { it.id } + kept.map { it.id }
        val need = (QUEUE_TARGET - prefixIds.size).coerceAtLeast(0)
        val exclude = buildExcludeSet(state.queue.subList(0, from).map { it.id } + kept.map { it.id }).toMutableSet()
        val (picks, plan) = pickSongs(current, prefixIds, need, exclude, replacedTail = emptyList())
        replaced.forEach(generated::remove)
        picks.forEach { generated.add(it.id) }
        mutateQueue { playback.replaceQueueRange(from, state.queue.size, kept + picks) }
        publish(lastEvent = null, plan = plan)
        brainLog.write(ShuffleBrainLogFormatter.format(_telemetry.value, "refresh"))
    }

    /**
     * The swipe boost: a song swiped in as Play next under Smart Shuffle is
     * an explicit choice, so S_t anchors on it. The songs already queued stay
     * as they are; the next batch appended at the end follows the new S_t.
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
    }

    /** Each newly started song goes to the brain once (Markov habits, S_t seeding). */
    private suspend fun noteTrackStarted(currentSongId: Long?) {
        if (currentSongId == null || currentSongId == lastStartedSongId) return
        lastStartedSongId = currentSongId
        val event = mutex.withLock { runCatching { brain.onTrackStarted(currentSongId) }.getOrDefault(SessionEvent.NONE) }
        if (event != SessionEvent.NONE) publish(lastEvent = event)
    }

    // -- A new list -------------------------------------------------------------------------

    /**
     * The user started a new list (the queue generation moved). In Smart
     * Shuffle the session anchors on the chosen song and the list's linear
     * tail (after the Play next run) makes way for the brain's picks; in
     * RANDOM the list is shuffled physically, the chosen song first.
     * Nothing else (next, previous, a tap in the queue) ever gets here.
     */
    private suspend fun handOverOnNewQueue(signal: QueueSignal) {
        val previous = lastGeneration
        lastGeneration = signal.generation
        if (previous == null || previous == signal.generation) return
        // The picks belonged to the old queue.
        generated.clear()
        reRankJob?.cancel()
        val state = playback.playerState.value
        when (state.shuffleMode) {
            ShuffleMode.OFF -> preShuffleQueue = null
            ShuffleMode.RANDOM -> {
                preShuffleQueue = state.queue
                shuffleNewList(state)
            }
            ShuffleMode.SMART, ShuffleMode.JOURNEY -> {
                if (state.shuffleMode == ShuffleMode.JOURNEY) finishJourney()
                preShuffleQueue = state.queue
                val id = state.currentSong?.id ?: return
                // The manual pick boost: S_t = normalize(0.85 v + 0.15 S_t-1).
                val event = mutex.withLock { runCatching { brain.onManualSelect(id) }.getOrDefault(SessionEvent.NONE) }
                publish(lastEvent = event)
                val keepThrough = state.queueIndex + state.playNextCount
                if (state.queue.size - 1 > keepThrough) {
                    writeQueue { playback.removeQueueRange(keepThrough + 1, state.queue.size) }
                }
            }
        }
    }

    /** RANDOM, new list: the chosen song, then every other song of the list in a random order. */
    private suspend fun shuffleNewList(state: PlayerState) {
        val index = state.queueIndex
        if (index < 0 || state.queue.size < 2) return
        val others = state.queue.filterIndexed { i, _ -> i != index }.shuffled(random)
        writeQueue {
            // A fresh list has no history: the songs before the chosen one join the shuffle.
            if (index > 0) playback.removeQueueRange(0, index)
            playback.replaceQueueRange(1, Int.MAX_VALUE, others)
        }
    }

    /** Ends a running journey the moment the user plays something that isn't part of it. */
    private fun endJourneyIfUserNavigatedAway(currentSongId: Long?) {
        if (_journey.value == null || currentSongId == null) return
        val belongsToJourney = currentSongId == journeyAnchorSongId || currentSongId in generated
        if (!belongsToJourney) finishJourney()
    }

    // -- Mode changes ---------------------------------------------------------------------

    /**
     * Shuffle mode transitions that rewrite the upcoming songs (never the
     * song playing or the history):
     *
     *  - leaving Smart Shuffle/Journey or RANDOM for OFF: the original running
     *    order comes back;
     *  - Smart Shuffle/Journey to RANDOM: the original songs come back, shuffled;
     *  - OFF to RANDOM: the upcoming songs are shuffled in place.
     *
     * Turning Smart Shuffle on is [onShuffleActivated] (called by [chooseShuffleMode]).
     */
    private suspend fun onModeTransition(signal: QueueSignal) {
        val mode = playback.playerState.value.shuffleMode
        val previous = lastShuffleMode
        lastShuffleMode = mode
        if (previous == null || previous == mode) {
            if (previous == null && mode == ShuffleMode.OFF) preShuffleQueue = null
            return
        }
        val wasSmart = previous == ShuffleMode.SMART || previous == ShuffleMode.JOURNEY
        val isSmart = mode == ShuffleMode.SMART || mode == ShuffleMode.JOURNEY
        if (wasSmart && !isSmart) {
            // The session ends with Smart Shuffle; habits stay.
            reRankJob?.cancel()
            mutex.withLock { runCatching { brain.resetSession() } }
            publish(lastEvent = SessionEvent.NONE)
        }
        when {
            mode == ShuffleMode.OFF && (wasSmart || previous == ShuffleMode.RANDOM) -> restoreOriginalOrder(signal, shuffle = false)
            mode == ShuffleMode.RANDOM && wasSmart -> restoreOriginalOrder(signal, shuffle = true)
            mode == ShuffleMode.RANDOM && previous == ShuffleMode.OFF -> shuffleUpcomingInPlace()
        }
    }

    /**
     * Puts the user's original running order back after the song playing
     * ([shuffle]: in a random order, for RANDOM). Songs the user queued
     * while shuffling (the Play next run, songs added to the end) are kept,
     * at the front of what's coming.
     */
    private suspend fun restoreOriginalOrder(signal: QueueSignal, shuffle: Boolean) {
        val original = preShuffleQueue
        if (!shuffle) preShuffleQueue = null
        if (original == null) {
            generated.clear()
            return
        }
        val state = playback.playerState.value
        val currentId = state.currentSong?.id
        val position = original.indexOfFirst { it.id == currentId }
        if (position < 0) {
            // Another list plays now (started from a car, say): nothing of the old one to restore.
            generated.clear()
            if (shuffle) preShuffleQueue = null
            return
        }
        val tail = original.drop(position + 1)

        // User-queued songs: the Play next run (counted) and songs added to
        // the end (recognised by elimination: not in the snapshot, not a pick).
        val originalIds = original.mapTo(HashSet()) { it.id }
        val currentTail = state.queue.drop(state.queueIndex + 1)
        val queuedByUser = currentTail.take(signal.playNextCount) +
            currentTail.drop(signal.playNextCount).filter { it.id !in originalIds && it.id !in generated }

        // A kept song that also sits in the restored order plays where the
        // user put it, not twice: one occurrence is dropped from the tail.
        val restored = tail.toMutableList()
        queuedByUser.forEach { queued ->
            val duplicate = restored.indexOfFirst { it.id == queued.id }
            if (duplicate >= 0) restored.removeAt(duplicate)
        }
        val ordered = if (shuffle) restored.shuffled(random) else restored
        generated.clear()
        if (ordered.isEmpty() && queuedByUser.isEmpty() && currentTail.isEmpty()) return
        writeQueue { playback.replaceQueueRange(state.queueIndex + 1, state.queue.size, queuedByUser + ordered) }
    }

    /** OFF to RANDOM: the songs after the Play next run, shuffled where they are. */
    private suspend fun shuffleUpcomingInPlace() {
        val state = playback.playerState.value
        preShuffleQueue = state.queue
        val from = (state.queueIndex + 1 + state.playNextCount).coerceAtMost(state.queue.size)
        val tail = state.queue.drop(from)
        if (tail.size < 2) return
        writeQueue { playback.replaceQueueRange(from, state.queue.size, tail.shuffled(random)) }
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
        activate(ShuffleMode.SMART)
        return true
    }

    /**
     * Turns Smart Shuffle on (or restarts it: a fresh session from the song
     * playing), as "Start Mix" on Home does.
     */
    fun activateSmartShuffle() {
        onShuffleModeChosen(ShuffleMode.SMART)
        activate(ShuffleMode.SMART)
    }

    /**
     * A real transition INTO Smart Shuffle or Journey. The re-entrancy guard
     * goes up BEFORE the mode changes, so the top-up can't append to the old
     * linear queue in between. Then a new session starts (S_t reseeds from
     * the song playing), the queue is snapshotted for the restore, and the
     * linear tail after the Play next run is dropped so the rolling top-up
     * takes over. The song playing and the history are untouched.
     */
    private fun activate(mode: ShuffleMode) {
        val scope = managerScope
        if (scope == null) {
            playback.setShuffleMode(mode)
            return
        }
        mutationDepth++
        playback.setShuffleMode(mode)
        val state = playback.playerState.value
        if (preShuffleQueue == null) preShuffleQueue = state.queue
        val current = state.currentSong?.id
        // The linear tail runs from after the Play next run up to the first
        // song this manager queued (a batch already appended stays).
        val firstFree = (state.queueIndex + 1 + state.playNextCount).coerceAtMost(state.queue.size)
        val firstPick = (firstFree until state.queue.size).firstOrNull { state.queue[it].id in generated }
            ?: state.queue.size
        scope.launch {
            try {
                mutex.withLock {
                    runCatching {
                        brain.resetSession()
                        if (current != null) brain.onTrackStarted(current)
                    }
                    if (firstFree < firstPick) mutateQueue { playback.removeQueueRange(firstFree, firstPick) }
                }
                publish(lastEvent = SessionEvent.SEEDED)
            } finally {
                mutationDepth--
                if (mutationDepth == 0) react(playback.playerState.value.toSignal())
            }
        }
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

    /** New settings: the generated tail is re-ranked under them (the protected head stays). */
    private fun refreshUpcomingQueue() {
        if (playback.playerState.value.shuffleMode != ShuffleMode.SMART) return
        scheduleReRank()
    }

    /** Regenerate only the generated Smart Shuffle tail (the Regenerate button). */
    fun regenerateSmartQueue() = refreshUpcomingQueue()

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
        activate(ShuffleMode.JOURNEY)
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
     * switch INTO Smart Shuffle clears the linear queue. The upcoming-order
     * rewrites of the other transitions follow from the state change
     * ([onModeTransition]).
     */
    fun chooseShuffleMode(mode: ShuffleMode) {
        val wasSmart = playback.playerState.value.shuffleMode.let {
            it == ShuffleMode.SMART || it == ShuffleMode.JOURNEY
        }
        onShuffleModeChosen(mode)
        if (mode == ShuffleMode.SMART && !wasSmart) activate(mode) else playback.setShuffleMode(mode)
    }

    fun onShuffleModeChosen(mode: ShuffleMode) {
        if (_journey.value != null && mode != ShuffleMode.JOURNEY) finishJourney(revertToSmart = false)
    }

    // -- Filling the queue ----------------------------------------------------------------

    /** Appends picks at the very end when fewer than [REFILL_BELOW] are left ahead. Never reorders. */
    private suspend fun maybeTopUp(mode: ShuffleMode, queueIndex: Int, queueSize: Int) {
        val refillBelow = if (mode == ShuffleMode.JOURNEY) JOURNEY_LOOKAHEAD else REFILL_BELOW
        if (queueSize - queueIndex - 1 >= refillBelow) return
        mutex.withLock {
            // Re-check under the lock; state (the mode included) may have moved while waiting.
            val state = playback.playerState.value
            if (state.shuffleMode != mode) return
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

    /** One brain batch topping the queue up to [QUEUE_TARGET], appended at the end. */
    private suspend fun fillSmart(current: Song, prefix: List<Long>, ahead: Int, exclude: MutableSet<Long>) {
        val need = QUEUE_TARGET - ahead
        if (need <= 0) return
        val (songs, plan) = pickSongs(current, prefix, need, exclude, replacedTail = emptyList())
        if (songs.isEmpty()) return
        songs.forEach { generated.add(it.id) }
        mutateQueue { playback.addToQueueAll(songs) }
        publish(lastEvent = null, plan = plan)
        brainLog.write(ShuffleBrainLogFormatter.format(_telemetry.value, "queue"))
    }

    /**
     * Up to [need] songs from the brain after [prefixIds], random library
     * picks for whatever it could not supply, so playback never stalls.
     * Records why each was picked and remembers them as session picks.
     */
    private suspend fun pickSongs(
        current: Song,
        prefixIds: List<Long>,
        need: Int,
        exclude: MutableSet<Long>,
        replacedTail: List<Long>,
    ): Pair<List<Song>, QueuePlan> {
        if (need <= 0) return emptyList<Song>() to QueuePlan.EMPTY
        val plan = if (brain.available) {
            runCatching {
                brain.prepare()
                brain.generate(
                    QueueRequest(
                        currentSongId = current.id,
                        prefixIds = prefixIds,
                        excludeIds = exclude,
                        replacedTail = replacedTail,
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
        val songs = plan.songIds.distinct().mapNotNull { byId[it] }.filter { it.id !in prefixIds }.toMutableList()
        songs.forEach { exclude.add(it.id) }
        var attempts = 0
        while (songs.size < need && attempts < MAX_FALLBACK_ATTEMPTS) {
            attempts++
            val id = library.randomSongId(exclude) ?: break
            exclude.add(id)
            library.songById(id)?.let { songs += it }
        }
        val traces = plan.traces.associateBy { it.trackId }
        songs.forEach { song ->
            traces[song.id]?.let { decisions[song.id] = QueuedDecision(it, song.title, song.artist) }
            sessionPicks.addLast(song.id)
        }
        while (sessionPicks.size > SESSION_MEMORY) sessionPicks.removeFirst()
        return songs.take(need) to plan
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
            generated.add(id)
            mutateQueue { playback.addToQueue(song) }
            sessionPicks.addLast(id)
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
        playback.playerState.value.currentSong?.id?.takeIf { it in generated }?.let { decisions[it] }

    private fun upcomingDecisions(): List<QueuedDecision> {
        val state = playback.playerState.value
        return state.queue.drop(state.queueIndex + 1).filter { it.id in generated }.mapNotNull { decisions[it.id] }
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
        /**
         * The rolling lookahead: when fewer than [REFILL_BELOW] songs are left
         * ahead, picks are appended at the end up to [QUEUE_TARGET] (so two or
         * three at a time while listening, never a rebuild).
         */
        const val QUEUE_TARGET = 12
        const val REFILL_BELOW = 10

        /** A journey is queued a step at a time, so it can end the moment the user leaves it. */
        const val JOURNEY_LOOKAHEAD = 4
        const val SESSION_MEMORY = 50

        private const val MAX_FALLBACK_ATTEMPTS = QUEUE_TARGET * 3
        private const val CONFIG_REFRESH_DEBOUNCE_MS = 220L

        fun temperatureForVariety(variety: Float): Float =
            (0.06f + variety.coerceIn(0f, 1f) * 0.58f).coerceIn(0.06f, 0.64f)
    }
}
