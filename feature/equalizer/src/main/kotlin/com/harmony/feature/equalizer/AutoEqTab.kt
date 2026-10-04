package com.harmony.feature.equalizer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.harmony.core.datastore.SettingsRepository
import com.harmony.core.model.AutoEqDesign
import com.harmony.core.model.AutoEqSettings
import com.harmony.core.model.OutputForms
import com.harmony.core.model.RoomCorrection
import com.harmony.core.ui.component.EditorialPalette
import com.harmony.domain.playback.AutoEqLive
import com.harmony.domain.playback.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Auto tab's state. Settings go to the DataStore like every other
 * equalizer setting; the playback service picks them up from there (see
 * AutoEqCoordinator). Calibration runs here, with the music paused.
 */
@HiltViewModel
class AutoEqViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val playback: PlaybackController,
) : ViewModel() {

    private val calibration = MutableStateFlow<CalibrationUi>(CalibrationUi.Idle)
    private var calibrating: Job? = null

    val state: StateFlow<AutoEqUiState> = combine(
        settings.settings,
        settings.outputForms,
        playback.playerState,
        AutoEqLive.readout,
        calibration,
    ) { s, forms, player, readout, cal ->
        val output = player.audioOutput
        AutoEqUiState(
            eqOn = s.eq.enabled,
            auto = s.autoEq,
            readout = readout,
            outputLabel = output.label,
            kind = AutoEqDesign.kind(output, OutputForms.effective(output, forms)),
            correction = s.roomCorrections[AutoEqDesign.outputKey(output)],
            calibration = cal,
            playing = player.isPlaying,
            nowPlaying = player.currentSong?.let { "${it.title} · ${it.artist}" },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutoEqUiState())

    fun setTone(on: Boolean) = setAuto { it.copy(tone = on) }
    fun setRoom(on: Boolean) = setAuto { it.copy(room = on) }
    fun setNoise(on: Boolean) = setAuto { it.copy(noise = on) }

    /** Switching a part on also switches the equalizer on: Auto runs inside it. */
    private fun setAuto(change: (AutoEqSettings) -> AutoEqSettings) {
        viewModelScope.launch {
            val s = settings.settings.first()
            val next = change(s.autoEq)
            settings.setAutoEq(next)
            if (next.any && !s.eq.enabled) settings.setEq(s.eq.copy(enabled = true))
        }
    }

    fun forgetCorrection() {
        val output = playback.playerState.value.audioOutput
        viewModelScope.launch { settings.deleteRoomCorrection(AutoEqDesign.outputKey(output)) }
        calibration.value = CalibrationUi.Idle
    }

    fun calibrate() {
        if (calibrating?.isActive == true) return
        val output = playback.playerState.value.audioOutput
        val wasPlaying = playback.playerState.value.isPlaying
        calibrating = viewModelScope.launch {
            calibration.value = CalibrationUi.Running(CalibrationStep.Silence(0f))
            try {
                if (wasPlaying) {
                    playback.pause()
                    delay(PAUSE_SETTLE_MS) // let the music's tail and any fade-out die away
                }
                val result = RoomCalibrator(context).run { step -> calibration.value = CalibrationUi.Running(step) }
                calibration.value = when (result) {
                    is AutoEqDesign.RoomResult.Measured -> {
                        val gains = result.gainsDb.toList()
                        settings.saveRoomCorrection(
                            AutoEqDesign.outputKey(output),
                            RoomCorrection(gains, System.currentTimeMillis(), output.label),
                        )
                        setRoom(true)
                        CalibrationUi.Done(gains)
                    }
                    AutoEqDesign.RoomResult.TooQuiet -> CalibrationUi.Failed(
                        "The test sound was too quiet to hear over the room. Turn the volume up a little, keep the " +
                            "room quiet, and measure again.",
                    )
                    AutoEqDesign.RoomResult.TooLoud -> CalibrationUi.Failed(
                        "The test sound overloaded the microphone. Turn the volume down a little and measure again.",
                    )
                }
            } catch (e: CancellationException) {
                calibration.value = CalibrationUi.Idle
                throw e
            } catch (e: SecurityException) {
                calibration.value = CalibrationUi.Failed("Harmony needs the microphone to measure. Allow it and try again.")
            } catch (e: IllegalStateException) {
                calibration.value = CalibrationUi.Failed(e.message ?: "The measurement stopped. Try again.")
            } finally {
                if (wasPlaying) playback.play()
            }
        }
    }

    fun cancelCalibration() {
        calibrating?.cancel()
        calibration.value = CalibrationUi.Idle
    }

    private companion object {
        const val PAUSE_SETTLE_MS = 600L
    }
}

/** The Auto tab with its ViewModel, asking for the microphone where a part needs it. */
@Composable
fun AutoTab(palette: EditorialPalette, viewModel: AutoEqViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // What to do once the microphone is allowed.
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pending?.invoke()
        pending = null
    }
    fun withMicrophone(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pending = action
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val actions = remember(viewModel) {
        AutoEqActions(
            onTone = viewModel::setTone,
            onRoom = viewModel::setRoom,
            onNoise = { on -> if (on) withMicrophone { viewModel.setNoise(true) } else viewModel.setNoise(false) },
            onCalibrate = { withMicrophone(viewModel::calibrate) },
            onCancelCalibration = viewModel::cancelCalibration,
            onForgetCorrection = viewModel::forgetCorrection,
        )
    }
    AutoEqContent(state, actions, palette)
}
