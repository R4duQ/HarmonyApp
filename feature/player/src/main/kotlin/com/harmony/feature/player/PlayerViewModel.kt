package com.harmony.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.harmony.core.datastore.SettingsRepository
import com.harmony.core.model.MoodFilter
import com.harmony.core.model.PlayerState
import com.harmony.core.model.RepeatMode
import com.harmony.core.model.ShuffleMode
import com.harmony.core.model.Song
import com.harmony.domain.library.repository.FavoritesRepository
import com.harmony.domain.playback.PlaybackController
import com.harmony.domain.playback.usecase.TogglePlayPauseUseCase
import com.harmony.domain.shuffle.SmartQueueManager
import com.harmony.domain.shuffle.model.SmartShuffleStyle
import com.harmony.domain.shuffle.usecase.StartJourneyToMoodUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One ViewModel for now-playing, mini player, queue sheet, and the Smart
 * Shuffle sheet — they all observe the same PlayerState and it keeps their
 * interactions (e.g. energy slider while the queue sheet is open) trivially
 * consistent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playback: PlaybackController,
    private val togglePlayPause: TogglePlayPauseUseCase,
    private val queueManager: SmartQueueManager,
    private val startJourneyToMood: StartJourneyToMoodUseCase,
    private val favorites: FavoritesRepository,
    private val settings: SettingsRepository,
    private val connect: com.harmony.domain.playback.ConnectController,
) : ViewModel() {

    val playerState: StateFlow<PlayerState> = playback.playerState

    val isCurrentFavorite: StateFlow<Boolean> = playback.playerState
        .map { it.currentSong?.id }
        .flatMapLatest { id -> if (id == null) flowOf(false) else favorites.observeIsFavorite(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Device kinds the listener picked, keyed by device name; see [OutputForms]. */
    val outputForms: StateFlow<Map<String, com.harmony.core.model.OutputForm>> = settings.outputForms
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Harmony Connect: playing on a computer, the phone as the remote. */
    val connectState: StateFlow<com.harmony.domain.playback.ConnectState> = connect.state

    internal val playOnActions = PlayOnActions(
        search = connect::search,
        connect = connect::connect,
        pair = connect::pair,
        addByAddress = connect::addByAddress,
        playHere = connect::disconnect,
        forget = connect::forget,
        dismissError = connect::dismissError,
    )

    /** Remembers what kind of device [name] is; null returns to the automatic guess. */
    fun setOutputForm(name: String, form: com.harmony.core.model.OutputForm?) {
        viewModelScope.launch { settings.setOutputForm(name, form) }
    }

    val journeyProgress: StateFlow<Float?> = queueManager.journey
        .map { it?.progress }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val energySliderValue: StateFlow<Float?> = queueManager.config
        .map { it.energyTarget }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val smartShuffleStyle: StateFlow<SmartShuffleStyle> = queueManager.config
        .map { it.style }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SmartShuffleStyle.BALANCED)

    val smartFamiliarity: StateFlow<Float> = queueManager.config
        .map { it.familiarity }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.55f)

    val smartDiscovery: StateFlow<Float> = queueManager.config
        .map { it.discovery }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.45f)

    val smartVariety: StateFlow<Float> = queueManager.config
        .map { it.variety }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.45f)

    /** Live preview of what the actual playback queue will play next. */
    val smartQueuePreview: StateFlow<List<Song>> = playback.playerState
        .map { state ->
            if (state.shuffleMode == ShuffleMode.SMART || state.shuffleMode == ShuffleMode.JOURNEY) {
                state.queue.drop((state.queueIndex + 1).coerceAtLeast(0)).take(4)
            } else {
                emptyList()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Persisted source of truth for the mood chips — survives the sheet closing/reopening. */
    val moodFilter: StateFlow<MoodFilter?> = queueManager.config
        .map { it.moodFilter }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Which mood the active journey (if any) is heading toward, for chip highlighting. */
    val activeJourneyMood: StateFlow<MoodFilter?> = queueManager.journeyMood
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // -- Smart Shuffle's Diagnostic Brain -------------------------------------

    /** S_t, the five audits and why each upcoming song was picked. */
    val brainTelemetry: StateFlow<com.harmony.core.model.ShuffleBrainTelemetry> = queueManager.telemetry

    val brainHudVisible: StateFlow<Boolean> = queueManager.debugOverlayVisible

    /** The equalizer's AUTO LIVE is calibrating to the music (the mini player says so). */
    val liveSpectralEq: StateFlow<Boolean> = settings.settings
        .map { it.eq.enabled && it.eq.auto }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setBrainHudVisible(visible: Boolean) = queueManager.setDebugOverlayVisible(visible)

    private val _selfTestRunning = kotlinx.coroutines.flow.MutableStateFlow(false)
    val brainSelfTestRunning: StateFlow<Boolean> = _selfTestRunning

    /** Runs the engine's synthetic 100-track self-test; the report lands in [brainTelemetry] and Logcat. */
    fun runBrainSelfTest() {
        if (_selfTestRunning.value) return
        _selfTestRunning.value = true
        viewModelScope.launch {
            try {
                queueManager.runBrainSelfTest()
            } finally {
                _selfTestRunning.value = false
            }
        }
    }

    // -- Transport ----------------------------------------------------------
    fun onPlayPause() = togglePlayPause()
    /**
     * Transport goes through SmartQueueManager, which owns the queue's order:
     * Next and Previous only move within it (seekToNextMediaItem /
     * seekToPrevious), never changing it; a fast skip only teaches the
     * brain, which shapes the songs appended at the end later.
     */
    fun onNext() = queueManager.skipToNext()
    fun onPrevious() = queueManager.skipToPrevious()
    fun onSeek(positionMs: Long) = playback.seekTo(positionMs)

    fun onToggleFavorite() {
        val id = playerState.value.currentSong?.id ?: return
        viewModelScope.launch { favorites.toggle(id) }
    }

    // -- Modes --------------------------------------------------------------
    fun cycleRepeatMode() {
        val next = when (playerState.value.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        playback.setRepeatMode(next)
    }

    /**
     * The shuffle button is a two-state toggle: OFF <-> SMART.
     *
     * It used to cycle OFF -> SMART -> RANDOM -> OFF, which made the second
     * press land on RANDOM instead of turning shuffle off. Worse, by that
     * point Smart Shuffle had trimmed the queue to a couple of tracks, so
     * RANDOM had nothing meaningful to shuffle and appeared to do nothing —
     * and the expected "restore my queue" only happened on the *third*
     * press. Plain random is still available, but as a deliberate choice in
     * the Smart Shuffle sheet rather than a surprise stop on the way to off.
     */
    fun cycleShuffleMode() = queueManager.toggleShuffle()

    /** Explicit mode selection from the Smart Shuffle sheet. */
    fun setShuffleMode(mode: ShuffleMode) = queueManager.chooseShuffleMode(mode)

    fun setPlaybackSpeed(speed: Float) = playback.setPlaybackSpeed(speed)
    fun setSleepTimerMinutes(minutes: Int?, finishTrack: Boolean) =
        playback.setSleepTimer(minutes?.let { it * 60_000L }, finishTrack)

    // -- Smart Shuffle sheet ------------------------------------------------
    fun onSmartStyleSelected(style: SmartShuffleStyle) {
        queueManager.setSmartStyle(style)
        val c = queueManager.config.value
        viewModelScope.launch {
            settings.setSmartShuffleProfile(
                style = style.name,
                familiarity = c.familiarity,
                discovery = c.discovery,
                variety = c.variety,
            )
        }
    }

    fun onSmartFamiliarityChange(value: Float) {
        queueManager.setFamiliarity(value)
        viewModelScope.launch { settings.setSmartShuffleFamiliarity(value) }
    }

    fun onSmartDiscoveryChange(value: Float) {
        queueManager.setDiscovery(value)
        viewModelScope.launch { settings.setSmartShuffleDiscovery(value) }
    }

    fun onSmartVarietyChange(value: Float) {
        queueManager.setVariety(value)
        viewModelScope.launch { settings.setSmartShuffleVariety(value) }
    }

    fun onRegenerateSmartQueue() = queueManager.regenerateSmartQueue()

    /** value null = slider "off"; persisted so it survives restarts. */
    fun onEnergySliderChange(value: Float?) {
        queueManager.setEnergyTarget(value)
        viewModelScope.launch { settings.setEnergySlider(value) }
    }

    /** Selecting a mood also turns Smart Shuffle on if it wasn't already active. */
    fun onMoodFilterSelected(mood: MoodFilter?) = queueManager.setMoodFilter(mood)

    fun onStartJourneyToMood(mood: MoodFilter, steps: Int) {
        viewModelScope.launch { startJourneyToMood(mood, steps) }
    }

    fun onCancelJourney() = queueManager.cancelJourney()

    // -- Queue --------------------------------------------------------------
    /** A tap on a queued song: seekTo(index, 0), the queue unchanged. */
    fun onQueueItemClick(index: Int) = queueManager.playQueueItem(index)
    fun onRemoveQueueItem(index: Int) {
        viewModelScope.launch { playback.removeFromQueue(index) }
    }

    fun onMoveQueueItem(from: Int, to: Int) {
        viewModelScope.launch { playback.moveQueueItem(from, to) }
    }
}
