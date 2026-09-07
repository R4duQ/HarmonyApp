package com.harmony.app

import com.harmony.core.datastore.SettingsRepository
import com.harmony.domain.playback.PlaybackController
import com.harmony.domain.shuffle.SmartQueueCoordinator
import com.harmony.domain.shuffle.model.SmartShuffleStyle
import com.harmony.sync.analysis.AnalysisScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-way bridge: persisted settings -> live playback stack. UI writes ONLY
 * to the DataStore; this applier is the single component that talks to the
 * controller, so persisted state and live state cannot diverge (and settings
 * survive process death by construction).
 */
@Singleton
class SettingsApplier @Inject constructor(
    private val settings: SettingsRepository,
    private val playback: PlaybackController,
    private val coordinator: SmartQueueCoordinator,
    private val analysisScheduler: AnalysisScheduler,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            settings.settings.distinctUntilChanged().collect { s ->
                playback.setCrossfade(s.crossfadeSeconds)
                playback.setReplayGainMode(s.replayGainMode)
                playback.setEqualizer(s.eq)
                coordinator.setEnergyTarget(s.energySliderValue)
                val smartStyle = runCatching { SmartShuffleStyle.valueOf(s.smartShuffleStyle) }
                    .getOrDefault(SmartShuffleStyle.BALANCED)
                coordinator.applyPersistedSmartSettings(
                    style = smartStyle,
                    familiarity = s.smartShuffleFamiliarity,
                    discovery = s.smartShuffleDiscovery,
                    variety = s.smartShuffleVariety,
                )
                // Fixes a Phase 5 gap: the toggle existed but never reached
                // WorkManager. UPDATE re-applies constraints to the periodic job.
                if (analysisScheduler.chargingOnly != s.chargingOnlyAnalysis) {
                    analysisScheduler.chargingOnly = s.chargingOnlyAnalysis
                    analysisScheduler.reschedulePeriodic()
                }
            }
        }
    }
}
