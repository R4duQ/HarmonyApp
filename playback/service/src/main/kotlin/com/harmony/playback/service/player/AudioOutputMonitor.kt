package com.harmony.playback.service.player

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter
import android.os.Build
import android.provider.Settings
import com.harmony.core.model.AudioOutput
import com.harmony.core.model.AudioOutputType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks which output device audio is actually going to, and re-evaluates
 * whenever devices connect or disconnect.
 *
 * Android has no single "what is media routed to right now?" API that works
 * across versions, so this infers it the way the platform itself routes:
 * from the set of connected output devices, the highest-priority one wins
 * (Bluetooth > USB > wired > HDMI > speaker). That ordering mirrors
 * Android's own media routing rules, so it matches reality in practice —
 * plug in headphones while Bluetooth is connected and audio stays on
 * Bluetooth, which is what this reports.
 *
 * Naming the device is the fiddly part. [AudioDeviceInfo.productName] is
 * documented as the device's own name, but for Bluetooth a lot of OEMs
 * return the PHONE's name instead of the headphones' — which is how you end
 * up with a player claiming to be playing through "Pixel 7" while you're
 * wearing earbuds. So the name is resolved in three steps:
 *
 *  1. [MediaRouter]'s selected live-audio route, which reports the remote
 *     device's advertised name and needs no permission;
 *  2. [AudioDeviceInfo.productName], but only if it isn't one of this
 *     phone's own names (see [localDeviceNames]);
 *  3. nothing — callers fall back to the generic type label.
 *
 * The permission-free path matters: reading the name off BluetoothAdapter
 * would be more direct but needs BLUETOOTH_CONNECT at runtime on Android 12+,
 * which is a lot to ask a local music player for a caption.
 */
@Singleton
class AudioOutputMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * Names belonging to THIS phone. Anything matching these is a
     * productName the OEM filled in wrongly, not the headphones.
     * Read once: none of them change while the process lives.
     */
    private val localDeviceNames: Set<String> by lazy {
        buildSet {
            runCatching {
                Settings.Global.getString(context.contentResolver, "device_name")
            }.getOrNull()?.let(::add)
            runCatching {
                Settings.Secure.getString(context.contentResolver, "bluetooth_name")
            }.getOrNull()?.let(::add)
            add(Build.MODEL)
            add(Build.DEVICE)
            add(Build.PRODUCT)
            add(Build.MANUFACTURER)
        }.filter { it.isNotBlank() }.map { it.trim().lowercase() }.toSet()
    }

    private val _output = MutableStateFlow(currentOutput())
    val output: StateFlow<AudioOutput> = _output

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            _output.value = currentOutput()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            _output.value = currentOutput()
        }
    }

    init {
        // null handler = callbacks on the main looper, which is fine: this
        // is a rare event and the work is a short array scan.
        audioManager.registerAudioDeviceCallback(callback, null)
    }

    private fun currentOutput(): AudioOutput {
        val devices = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        }.getOrNull() ?: return AudioOutput()

        // Highest priority connected device wins, mirroring platform routing.
        val best = devices
            .mapNotNull { device -> classify(device)?.let { it to device } }
            .minByOrNull { (type, _) -> PRIORITY.indexOf(type).let { if (it < 0) 99 else it } }
            ?: return AudioOutput()

        val (type, device) = best
        return AudioOutput(type = type, name = deviceName(device))
    }

    /** See the class doc for why this isn't just productName. */
    private fun deviceName(device: AudioDeviceInfo): String? {
        selectedRouteName()?.let { return it }
        val product = runCatching { device.productName?.toString() }.getOrNull()
        return product?.takeIf { it.isNotBlank() && !isLocalName(it) }
    }

    private fun selectedRouteName(): String? = runCatching {
        val router = context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as? MediaRouter
        router?.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)?.name?.toString()
    }.getOrNull()?.takeIf { it.isNotBlank() && !isLocalName(it) && !isGenericName(it) }

    private fun isLocalName(name: String) = name.trim().lowercase() in localDeviceNames

    /**
     * MediaRouter names the built-in output something like "Phone" or
     * "Speaker". That's true but useless as a device name, and letting it
     * through would shadow the type label we'd otherwise show.
     */
    private fun isGenericName(name: String) =
        name.trim().lowercase() in setOf("phone", "speaker", "phone speaker", "default")

    private fun classify(device: AudioDeviceInfo): AudioOutputType? = when (device.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        -> AudioOutputType.BLUETOOTH

        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        -> AudioOutputType.USB

        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        -> AudioOutputType.WIRED

        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        -> AudioOutputType.HDMI

        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioOutputType.SPEAKER

        // Telephony earpiece, remote submix, unknown virtual sinks: ignore,
        // they are never where the user's music is going.
        else -> null
    }

    private companion object {
        val PRIORITY = listOf(
            AudioOutputType.BLUETOOTH,
            AudioOutputType.USB,
            AudioOutputType.WIRED,
            AudioOutputType.HDMI,
            AudioOutputType.SPEAKER,
        )
    }
}
