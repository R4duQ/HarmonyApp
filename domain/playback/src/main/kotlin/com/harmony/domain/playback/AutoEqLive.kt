package com.harmony.domain.playback

import com.harmony.core.model.AutoEqReadout
import com.harmony.core.model.ClarityReadout
import com.harmony.core.model.ClaritySettings
import com.harmony.core.model.EqSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The automatic equalizer's shared state, for the same reason [AudioLevels]
 * is a plain object: the equalizer processors live inside audio sinks, out
 * of Hilt's reach, and the crossfade player builds its own.
 *
 * Inputs are written by the playback service (which correction applies to
 * the device playing, what the microphone hears) and read by every
 * processor at the start of each buffer. The readout goes the other way, to
 * the Equalizer screen.
 */
object AutoEqLive {
    /** The automatic equalizer runs: the equalizer is on and at least one automatic part is. */
    @Volatile var active: Boolean = false

    /** Whether each song's tone is evened out. */
    @Volatile var toneEnabled: Boolean = false

    /** Whether Clarity runs, and how it is set. */
    @Volatile var clarityEnabled: Boolean = false
    @Volatile var clarity: ClaritySettings = ClaritySettings()

    /** Correction for the speaker and room playing now, dB per band; zeros when none. */
    @Volatile var roomDb: FloatArray = FloatArray(EqSettings.BAND_COUNT)
        private set

    /** Lift for the noise around the listener, dB per band; zeros when not listening. */
    @Volatile var noiseDb: FloatArray = FloatArray(EqSettings.BAND_COUNT)
        private set

    @Volatile private var ambientDb: Float? = null
    @Volatile private var toneDb: FloatArray = FloatArray(EqSettings.BAND_COUNT)
    @Volatile private var heardSeconds = 0f
    @Volatile private var clarityDb: FloatArray = FloatArray(EqSettings.BAND_COUNT)

    private val _readout = MutableStateFlow(AutoEqReadout())
    val readout: StateFlow<AutoEqReadout> = _readout.asStateFlow()

    private val _clarityLive = MutableStateFlow(ClarityReadout())

    /** What Clarity hears and does, about 20 times a second while the screen shows it. */
    val clarityLive: StateFlow<ClarityReadout> = _clarityLive.asStateFlow()

    /** Someone is looking at [clarityLive]; until then the audio thread doesn't build it. */
    val clarityWanted: Boolean get() = _clarityLive.subscriptionCount.value > 0

    fun publishClarity(readout: ClarityReadout) {
        _clarityLive.value = readout
    }

    fun setRoom(db: FloatArray) {
        roomDb = db.copyOf(EqSettings.BAND_COUNT)
        publish()
    }

    fun setNoise(db: FloatArray, ambient: Float?) {
        noiseDb = db.copyOf(EqSettings.BAND_COUNT)
        ambientDb = ambient
        publish()
    }

    /** From the main player's equalizer, a couple of times a second; [clarity] is Clarity's curve on the octaves. */
    fun publishTone(db: FloatArray, heard: Float, clarity: FloatArray? = null) {
        toneDb = db.copyOf(EqSettings.BAND_COUNT)
        heardSeconds = heard
        clarityDb = clarity?.copyOf(EqSettings.BAND_COUNT) ?: FloatArray(EqSettings.BAND_COUNT)
        publish()
    }

    private fun publish() {
        val on = active
        _readout.value = AutoEqReadout(
            toneDb = if (on && toneEnabled) toneDb.toList() else ZERO,
            roomDb = if (on) roomDb.toList() else ZERO,
            noiseDb = if (on) noiseDb.toList() else ZERO,
            songHeardSeconds = heardSeconds,
            ambientDb = ambientDb,
            clarityDb = if (on && clarityEnabled) clarityDb.toList() else ZERO,
        )
        if (!(on && clarityEnabled)) _clarityLive.value = ClarityReadout()
    }

    /** Re-publishes after [active], [toneEnabled] or [clarityEnabled] changed. */
    fun refresh() = publish()

    private val ZERO = List(EqSettings.BAND_COUNT) { 0f }
}
