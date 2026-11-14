package com.harmony.app

import com.harmony.core.datastore.SettingsRepository
import com.harmony.core.model.MixSource
import com.harmony.domain.playback.PlaybackController
import com.harmony.domain.shuffle.SmartQueueManager
import com.harmony.domain.shuffle.model.SmartShuffleStyle
import com.harmony.sync.analysis.AnalysisScheduler
import kotlinx.coroutines.CoroutineScope
import com.harmony.domain.online.OnlineMixFeed
import com.harmony.domain.playback.ConnectController
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    private val queueManager: SmartQueueManager,
    private val analysisScheduler: AnalysisScheduler,
    private val onlineMix: OnlineMixFeed,
    private val connect: ConnectController,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            // Playback and Connect first: an online mix prepares nothing while the player
            // is buffering or a computer is being connected to (a handoff).
            combine(
                playback.playerState.map { it.isBuffering },
                connect.state.map { it.busyWith != null },
            ) { buffering, connecting -> buffering || connecting }
                .distinctUntilChanged()
                .collect { onlineMix.setPlaybackBusy(it) }
        }
        scope.launch {
            settings.settings.distinctUntilChanged().collect { s ->
                playback.setCrossfade(s.crossfadeSeconds)
                playback.setReplayGainMode(s.replayGainMode)
                playback.setEqualizer(s.eq)
                queueManager.setEnergyTarget(s.energySliderValue)
                // Online mixes are a feature flag: off, a mix is the library's only.
                queueManager.preferredMixSource = if (!s.onlineMixEnabled) MixSource.OFFLINE
                else runCatching { MixSource.valueOf(s.onlineMixSource) }.getOrDefault(MixSource.COMBINED)
                queueManager.autoKeep = s.onlineMixEnabled && s.onlineMixAutoKeep
                val smartStyle = runCatching { SmartShuffleStyle.valueOf(s.smartShuffleStyle) }
                    .getOrDefault(SmartShuffleStyle.BALANCED)
                queueManager.applyPersistedSmartSettings(
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
