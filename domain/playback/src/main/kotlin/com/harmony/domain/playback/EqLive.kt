package com.harmony.domain.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the equalizer engine is doing right now, for the Equalizer screen. */
data class EqStatus(
    /** The rate the playing engine runs at, the source's own (0 before the first song). */
    val sampleRate: Int = 0,
    /** Where the sound goes, as the engine sees it. */
    val output: EqOutput = EqOutput.PHONE_SPEAKER,
    /** The output device's own name, when it has one. */
    val outputName: String = "",
    /** A USB DAC receives the file's PCM untouched (Android 14+), so the EQ is bypassed. */
    val bitPerfect: Boolean = false,
    /** Whether bit-perfect output is possible here at all (Android 14+ and a USB DAC). */
    val bitPerfectAvailable: Boolean = false,
)

/** The engine's output categories and what it does for each. */
enum class EqOutput(val label: String, val highPassHz: Int, val ceilingDb: Float) {
    PHONE_SPEAKER("Phone speaker", 90, -0.15f),
    WIRED_HEADPHONES("Wired", 18, -0.15f),
    BLUETOOTH("Bluetooth", 24, -0.4f),
    USB_DAC("USB DAC", 10, -0.05f),
}

/**
 * Shared like [AudioLevels]: written by the playback service, read by the
 * Equalizer screen in the same process.
 */
object EqLive {
    private val _status = MutableStateFlow(EqStatus())
    val status: StateFlow<EqStatus> = _status.asStateFlow()

    fun update(transform: (EqStatus) -> EqStatus) {
        while (true) {
            val before = _status.value
            if (_status.compareAndSet(before, transform(before))) return
        }
    }
}
