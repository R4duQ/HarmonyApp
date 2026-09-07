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
import com.harmony.domain.shuffle.SmartQueueCoordinator
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
    private val coordinator: SmartQueueCoordinator,
    private val startJourneyToMood: StartJourneyToMoodUseCase,
    private val favorites: FavoritesRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    val playerState: StateFlow<PlayerState> = playback.playerState

    val isCurrentFavorite: StateFlow<Boolean> = playback.playerState
        .map { it.currentSong?.id }
        .flatMapLatest { id -> if (id == null) flowOf(false) else favorites.observeIsFavorite(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val journeyProgress: StateFlow<Float?> = coordinator.journey
        .map { it?.progress }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val energySliderValue: StateFlow<Float?> = coordinator.config
        .map { it.energyTarget }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val smartShuffleStyle: StateFlow<SmartShuffleStyle> = coordinator.config
        .map { it.style }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SmartShuffleStyle.BALANCED)

    val smartFamiliarity: StateFlow<Float> = coordinator.config
        .map { it.familiarity }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.55f)

    val smartDiscovery: StateFlow<Float> = coordinator.config
        .map { it.discovery }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.45f)

    val smartVariety: StateFlow<Float> = coordinator.config
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
    val moodFilter: StateFlow<MoodFilter?> = coordinator.config
        .map { it.moodFilter }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Which mood the active journey (if any) is heading toward, for chip highlighting. */
    val activeJourneyMood: StateFlow<MoodFilter?> = coordinator.journeyMood
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // -- Transport ----------------------------------------------------------
    fun onPlayPause() = togglePlayPause()
    fun onNext() = playback.skipToNext()
    fun onPrevious() = playback.skipToPrevious()
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
    fun cycleShuffleMode() {
        val current = playerState.value.shuffleMode
        val next = if (current == ShuffleMode.OFF) ShuffleMode.SMART else ShuffleMode.OFF
        coordinator.onShuffleModeChosen(next)
        playback.setShuffleMode(next)
        if (next == ShuffleMode.SMART) coordinator.onShuffleActivated()
    }

    /**
     * Explicit mode selection from the Smart Shuffle sheet. Ends any running
     * journey FIRST, so the journey's own teardown can't flip shuffle back
     * on immediately after the user asked for Off.
     */
    fun setShuffleMode(mode: ShuffleMode) {
        coordinator.onShuffleModeChosen(mode)
        playback.setShuffleMode(mode)
        if (mode == ShuffleMode.SMART) coordinator.onShuffleActivated()
    }

    fun setPlaybackSpeed(speed: Float) = playback.setPlaybackSpeed(speed)
    fun setSleepTimerMinutes(minutes: Int?, finishTrack: Boolean) =
        playback.setSleepTimer(minutes?.let { it * 60_000L }, finishTrack)

    // -- Smart Shuffle sheet ------------------------------------------------
    fun onSmartStyleSelected(style: SmartShuffleStyle) {
        coordinator.setSmartStyle(style)
        val c = coordinator.config.value
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
        coordinator.setFamiliarity(value)
        viewModelScope.launch { settings.setSmartShuffleFamiliarity(value) }
    }

    fun onSmartDiscoveryChange(value: Float) {
        coordinator.setDiscovery(value)
        viewModelScope.launch { settings.setSmartShuffleDiscovery(value) }
    }

    fun onSmartVarietyChange(value: Float) {
        coordinator.setVariety(value)
        viewModelScope.launch { settings.setSmartShuffleVariety(value) }
    }

    fun onRegenerateSmartQueue() = coordinator.regenerateSmartQueue()

    /** value null = slider "off"; persisted so it survives restarts. */
    fun onEnergySliderChange(value: Float?) {
        coordinator.setEnergyTarget(value)
        viewModelScope.launch { settings.setEnergySlider(value) }
    }

    /** Selecting a mood also turns Smart Shuffle on if it wasn't already active. */
    fun onMoodFilterSelected(mood: MoodFilter?) = coordinator.setMoodFilter(mood)

    fun onStartJourneyToMood(mood: MoodFilter, steps: Int) {
        viewModelScope.launch { startJourneyToMood(mood, steps) }
    }

    fun onCancelJourney() = coordinator.cancelJourney()

    // -- Queue --------------------------------------------------------------
    fun onQueueItemClick(index: Int) = playback.skipToQueueItem(index)
    fun onRemoveQueueItem(index: Int) {
        viewModelScope.launch { playback.removeFromQueue(index) }
    }

    fun onMoveQueueItem(from: Int, to: Int) {
        viewModelScope.launch { playback.moveQueueItem(from, to) }
    }
}
