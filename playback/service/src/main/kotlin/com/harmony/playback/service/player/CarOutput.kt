package com.harmony.playback.service.player

import com.harmony.core.model.AudioOutput
import com.harmony.core.model.AudioOutputType

/**
 * Android Auto shows up as a media controller, not as an audio device:
 * over USB or Bluetooth the phone only sees a generic output. So the output
 * is reported as the car while Android Auto's controller is connected.
 */
internal object CarOutput {
    /** Android Auto's projection host on the phone. */
    private val CAR_CONTROLLERS = setOf("com.google.android.projection.gearhead")

    fun isCarController(packageName: String): Boolean = packageName in CAR_CONTROLLERS

    /** Keeps the car's Bluetooth name when audio goes over Bluetooth; any other name isn't the car's. */
    fun resolve(device: AudioOutput, carConnections: Int): AudioOutput =
        if (carConnections <= 0) device
        else AudioOutput(AudioOutputType.CAR, device.name.takeIf { device.type == AudioOutputType.BLUETOOTH })
}
