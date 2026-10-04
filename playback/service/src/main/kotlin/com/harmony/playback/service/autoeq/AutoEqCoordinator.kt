package com.harmony.playback.service.autoeq

import com.harmony.core.datastore.SettingsRepository
import com.harmony.core.model.AutoEqDesign
import com.harmony.core.model.ClaritySettings
import com.harmony.core.model.EqSettings
import com.harmony.core.model.ListeningKind
import com.harmony.core.model.OutputForms
import com.harmony.domain.playback.AutoEqLive
import com.harmony.playback.service.player.AudioOutputMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides what the automatic equalizer does from the settings and the
 * device playing, and hands it to the audio chain through [AutoEqLive]:
 *
 *  - whether it runs at all (equalizer on, at least one automatic part on);
 *  - whether Clarity runs, and how it is set;
 *  - the room correction filed for this output, if room correction is on;
 *  - whether the microphone should be listening for noise: only while music
 *    plays on headphones, where it hears the room and not the music.
 */
@Singleton
class AutoEqCoordinator @Inject constructor(
    private val settings: SettingsRepository,
    private val audioOutput: AudioOutputMonitor,
) {
    private val playing = MutableStateFlow(false)
    private val _listen = MutableStateFlow(false)

    /** True while the noise listener should have the microphone open. */
    val listen: StateFlow<Boolean> = _listen.asStateFlow()

    private var job: Job? = null

    fun setPlaying(isPlaying: Boolean) {
        playing.value = isPlaying
    }

    fun start(scope: CoroutineScope) {
        job?.cancel()
        job = scope.launch {
            combine(settings.settings, settings.outputForms, audioOutput.output, playing) { s, forms, output, isPlaying ->
                val on = s.eq.enabled && s.autoEq.any
                val form = OutputForms.effective(output, forms)
                val kind = AutoEqDesign.kind(output, form)
                val room = s.roomCorrections[AutoEqDesign.outputKey(output)]
                    ?.takeIf { s.autoEq.room && kind != ListeningKind.HEADPHONES }
                    ?.gainsDb?.toFloatArray()
                    ?: FloatArray(EqSettings.BAND_COUNT)
                Decision(
                    on, s.autoEq.tone, room, on && s.autoEq.noise && kind == ListeningKind.HEADPHONES && isPlaying,
                    s.autoEq.clarity, s.clarity,
                )
            }.collect { d ->
                AutoEqLive.active = d.on
                AutoEqLive.toneEnabled = d.tone
                AutoEqLive.clarityEnabled = d.clarity
                AutoEqLive.clarity = d.claritySettings
                AutoEqLive.setRoom(d.room)
                _listen.value = d.listen
                AutoEqLive.refresh()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        playing.value = false
        _listen.value = false
    }

    private class Decision(
        val on: Boolean,
        val tone: Boolean,
        val room: FloatArray,
        val listen: Boolean,
        val clarity: Boolean,
        val claritySettings: ClaritySettings,
    )
}
