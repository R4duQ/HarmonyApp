package com.harmony.core.eq

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The current output: its kind, the device behind it, and the highest rate it reports (0 = any). */
data class OutputRoute(
    val type: OutputDeviceType,
    val name: String,
    val deviceId: Int,
    val maxSampleRate: Int,
)

/** What [AudioOutputMonitor.setBitPerfectUsbMode] achieved. */
sealed interface BitPerfectResult {
    /** The DAC now receives the app's PCM untouched, in [format]. */
    data class Enabled(val format: AudioFormat) : BitPerfectResult
    data object Disabled : BitPerfectResult
    data object NoUsbDac : BitPerfectResult
    /** The DAC (or this Android build) offers no bit-perfect mixer. */
    data object NotSupported : BitPerfectResult
    /** Android refused the preferred mixer attributes. */
    data object Rejected : BitPerfectResult
}

/**
 * Follows the audio output (phone speaker, wired, Bluetooth, USB DAC) and tells
 * the EQ engine whenever it changes, through [AudioManager.registerAudioDeviceCallback].
 *
 * Bind it to the lifecycle of whatever plays the music (the playback
 * service, not an Activity, or routing stops being followed when the screen
 * goes off): it starts in onCreate and stops in onDestroy. Or call
 * [start]/[stop] yourself. All of it runs on the main thread.
 *
 * Android 13+ answers which device media is actually routed to
 * ([AudioManager.getAudioDevicesForAttributes]); earlier versions are read by
 * priority (USB, then wired, then Bluetooth, then the speaker), which is the
 * order Android itself routes media in.
 */
@OptIn(UnstableApi::class)
class AudioOutputMonitor(
    context: Context,
    private val equalizer: CustomEqAudioProcessor? = null,
    /** Called on the main thread when bit-perfect mode turns on or off: re-prepare playback to apply it. */
    private val onBitPerfectChanged: (Boolean) -> Unit = {},
    /** Called on the main thread with every new route. */
    private val onRouteChanged: (OutputRoute) -> Unit = {},
) : DefaultLifecycleObserver {

    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var started = false

    private val mutableRoute = MutableStateFlow(SPEAKER_ROUTE)
    val route: StateFlow<OutputRoute> = mutableRoute.asStateFlow()

    private val mutableBitPerfect = MutableStateFlow(false)
    val bitPerfectActive: StateFlow<Boolean> = mutableBitPerfect.asStateFlow()
    private var bitPerfectDeviceId = NO_DEVICE

    /** The attributes the player uses; bit-perfect preferences are keyed on them. */
    var mediaAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    @MainThread
    fun start() {
        if (started) return
        started = true
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        refresh()  // the callback reports devices present at registration too, but don't rely on it
        equalizer?.setOutputDeviceType(mutableRoute.value.type)
    }

    @MainThread
    fun stop() {
        if (!started) return
        started = false
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }

    override fun onCreate(owner: LifecycleOwner) = start()

    override fun onDestroy(owner: LifecycleOwner) = stop()

    /**
     * Re-reads the route. Device add/remove events call this; call it too when
     * routing may have moved without a device appearing or leaving (for example
     * from Player.Listener.onDeviceInfoChanged, or after the user picks an
     * output in the system's media panel).
     */
    @MainThread
    fun refresh() {
        val device = activeOutput()
        val next = device?.let(::routeOf) ?: SPEAKER_ROUTE
        if (bitPerfectDeviceId != NO_DEVICE && audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .none { it.id == bitPerfectDeviceId }
        ) {
            // The DAC was unplugged: nothing to be bit-perfect to any more.
            bitPerfectDeviceId = NO_DEVICE
            equalizer?.bitPerfectPassthrough = false
            mutableBitPerfect.value = false
            onBitPerfectChanged(false)
        }
        if (next == mutableRoute.value) return
        mutableRoute.value = next
        equalizer?.setOutputDeviceType(next.type)
        onRouteChanged(next)
    }

    private fun activeOutput(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { audioManager.getAudioDevicesForAttributes(mediaAttributes).firstOrNull() }
                .getOrNull()?.let { return it }
        }
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outputs.minByOrNull { priorityOf(it.type) }
    }

    private fun routeOf(device: AudioDeviceInfo): OutputRoute = OutputRoute(
        type = classify(device.type),
        name = device.productName?.toString().orEmpty(),
        deviceId = device.id,
        maxSampleRate = device.sampleRates.maxOrNull() ?: 0,
    )

    // ---- Bit-perfect USB (Android 14+) ----------------------------------------------------

    /**
     * Asks Android 14+ to hand a connected USB DAC the app's PCM untouched
     * ([AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT]): no system mixing,
     * resampling or volume. Turning it on also puts the EQ in passthrough, as
     * any processing would defeat it; the user chooses between the two.
     *
     * Pass the rate and encoding the player's AudioTrack will use, to pick
     * the matching mixer format. With [HiResEqAudioSink], 24- and 32-bit
     * sources reach the AudioTrack as [AudioFormat.ENCODING_PCM_FLOAT] (an
     * exact conversion) and 16-bit ones as [AudioFormat.ENCODING_PCM_16BIT].
     * Without them, the DAC's highest-resolution bit-perfect format is chosen.
     *
     * The change applies from the next AudioTrack: re-prepare playback in
     * [onBitPerfectChanged].
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @MainThread
    fun setBitPerfectUsbMode(
        enabled: Boolean,
        sampleRate: Int = 0,
        encoding: Int = AudioFormat.ENCODING_INVALID,
    ): BitPerfectResult {
        val usb = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { isUsb(it.type) }
        if (!enabled) {
            val device = usb ?: audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.id == bitPerfectDeviceId }
            if (device != null) audioManager.clearPreferredMixerAttributes(mediaAttributes, device)
            setBitPerfect(NO_DEVICE)
            return BitPerfectResult.Disabled
        }
        if (usb == null) return BitPerfectResult.NoUsbDac
        val candidates = audioManager.getSupportedMixerAttributes(usb)
            .filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
        if (candidates.isEmpty()) return BitPerfectResult.NotSupported
        val chosen = candidates.firstOrNull {
            (sampleRate == 0 || it.format.sampleRate == sampleRate) &&
                (encoding == AudioFormat.ENCODING_INVALID || it.format.encoding == encoding)
        } ?: candidates.maxWith(compareBy({ it.format.sampleRate }, { bitDepthOf(it.format.encoding) }))
        if (!audioManager.setPreferredMixerAttributes(mediaAttributes, usb, chosen)) return BitPerfectResult.Rejected
        setBitPerfect(usb.id)
        return BitPerfectResult.Enabled(chosen.format)
    }

    private fun setBitPerfect(deviceId: Int) {
        val active = deviceId != NO_DEVICE
        bitPerfectDeviceId = deviceId
        equalizer?.bitPerfectPassthrough = active
        if (mutableBitPerfect.value != active) {
            mutableBitPerfect.value = active
            onBitPerfectChanged(active)
        }
    }

    companion object {
        private const val NO_DEVICE = -1
        private val SPEAKER_ROUTE = OutputRoute(OutputDeviceType.PHONE_SPEAKER, "", NO_DEVICE, 0)

        /** The engine's category for an [AudioDeviceInfo] type. */
        @JvmStatic
        fun classify(deviceType: Int): OutputDeviceType = when {
            isUsb(deviceType) -> OutputDeviceType.USB_DAC_HIRES
            isBluetooth(deviceType) -> OutputDeviceType.BLUETOOTH_A2DP
            isWired(deviceType) -> OutputDeviceType.WIRED_HEADPHONES
            else -> OutputDeviceType.PHONE_SPEAKER
        }

        private fun isUsb(type: Int) = type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            type == AudioDeviceInfo.TYPE_USB_ACCESSORY

        private fun isBluetooth(type: Int) = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
            type == AudioDeviceInfo.TYPE_BLE_BROADCAST ||
            type == AudioDeviceInfo.TYPE_HEARING_AID

        // Line outputs, docks and HDMI feed full-range equipment: treat them as wired.
        private fun isWired(type: Int) = type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            type == AudioDeviceInfo.TYPE_LINE_ANALOG ||
            type == AudioDeviceInfo.TYPE_LINE_DIGITAL ||
            type == AudioDeviceInfo.TYPE_AUX_LINE ||
            type == AudioDeviceInfo.TYPE_DOCK ||
            type == AudioDeviceInfo.TYPE_HDMI ||
            type == AudioDeviceInfo.TYPE_HDMI_ARC ||
            type == AudioDeviceInfo.TYPE_HDMI_EARC

        /** Lower wins: the order Android routes media in when several outputs are connected. */
        private fun priorityOf(type: Int): Int = when {
            isUsb(type) -> 0
            isWired(type) -> 1
            isBluetooth(type) -> 2
            type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 3
            else -> 4  // earpiece, telephony, buses: never the media route
        }

        private fun bitDepthOf(encoding: Int): Int = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 32
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
            AudioFormat.ENCODING_PCM_16BIT -> 16
            else -> 0
        }
    }
}
