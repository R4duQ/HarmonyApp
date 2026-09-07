package com.harmony.core.model

/** Where audio is currently going. */
enum class AudioOutputType { SPEAKER, WIRED, BLUETOOTH, USB, HDMI, OTHER }

/**
 * The active audio output route.
 *
 * [name] is the device's own reported product name — for Bluetooth that's
 * the headphone/speaker name the user knows it by. It can be null or a
 * generic string on some devices/OEMs, so the UI must always be able to
 * fall back to the type label alone.
 */
data class AudioOutput(
    val type: AudioOutputType = AudioOutputType.SPEAKER,
    val name: String? = null,
) {
    /** Human-readable label: prefers the real device name when we have one. */
    val label: String
        get() = when {
            !name.isNullOrBlank() && type == AudioOutputType.BLUETOOTH -> name
            !name.isNullOrBlank() && type == AudioOutputType.USB -> name
            else -> when (type) {
                AudioOutputType.SPEAKER -> "Phone speaker"
                AudioOutputType.WIRED -> "Headphones"
                AudioOutputType.BLUETOOTH -> "Bluetooth"
                AudioOutputType.USB -> "USB audio"
                AudioOutputType.HDMI -> "HDMI"
                AudioOutputType.OTHER -> "External audio"
            }
        }
}
