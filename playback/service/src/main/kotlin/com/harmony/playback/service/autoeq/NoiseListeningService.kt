package com.harmony.playback.service.autoeq

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.harmony.core.datastore.SettingsRepository
import com.harmony.core.media.audio.MicrophoneReader
import com.harmony.core.model.EqSettings
import com.harmony.core.model.NoiseTracker
import com.harmony.core.model.OctaveBandMeter
import com.harmony.domain.playback.AutoEqLive
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Listens to the noise around the listener for the automatic equalizer.
 *
 * Android only lets an app use the microphone in the background from a
 * foreground service of the microphone type that was started while the app
 * was on screen. So the app starts this when it comes to the front with
 * noise adaptation switched on ([startIfWanted]), and it stays until the
 * setting is switched off or nothing has played for a while.
 *
 * The microphone itself is open only while [AutoEqCoordinator.listen] says
 * so: music playing, on headphones. The rest of the time this service holds
 * no microphone and Android's microphone indicator is off.
 */
@AndroidEntryPoint
class NoiseListeningService : Service() {
    @Inject lateinit var coordinator: AutoEqCoordinator
    @Inject lateinit var settings: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        watch()
        return START_NOT_STICKY
    }

    private fun goForeground(): Boolean = try {
        val notification = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (e: RuntimeException) {
        // No microphone permission, or started from the background: Android refuses, and that's that.
        false
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private fun watch() {
        // Switched off in the app: go.
        scope.launch {
            settings.settings.map { it.eq.enabled && it.autoEq.noise }.distinctUntilChanged().collect { wanted ->
                if (!wanted) stopSelf()
            }
        }
        // Listen while the coordinator says so. A short gap (a crossfade's
        // handover, a skip) doesn't close the microphone.
        var listening: Job? = null
        var idleSince = System.currentTimeMillis()
        scope.launch {
            coordinator.listen.collectLatest { listen ->
                if (listen) {
                    if (listening?.isActive != true) listening = launch { listen() }
                } else {
                    delay(GRACE_MS)
                    listening?.cancel()
                    listening = null
                    idleSince = System.currentTimeMillis()
                }
            }
        }
        // Nothing playing on headphones for a long while: give the notification back.
        scope.launch {
            while (true) {
                delay(60_000)
                if (listening == null && System.currentTimeMillis() - idleSince > IDLE_STOP_MS) stopSelf()
            }
        }
    }

    private suspend fun listen() = withContext(Dispatchers.IO) {
        val mic = try {
            MicrophoneReader.open(this@NoiseListeningService)
        } catch (e: SecurityException) {
            return@withContext
        } catch (e: IllegalStateException) {
            return@withContext
        }
        val tracker = NoiseTracker()
        try {
            val meter = OctaveBandMeter(mic.sampleRate)
            val block = ShortArray(mic.sampleRate / 2) // half a second
            mic.start()
            // The first moment of a recording often carries the input's own switch-on thump.
            mic.read(block, mic.sampleRate / 10)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = mic.read(block)
                if (n <= 0) break
                meter.reset()
                meter.add(block, 0, n)
                val lift = tracker.add(meter.levelsDb(), n.toFloat() / mic.sampleRate)
                AutoEqLive.setNoise(lift, tracker.ambientDb)
            }
        } finally {
            mic.close()
            AutoEqLive.setNoise(FloatArray(EqSettings.BAND_COUNT), null)
        }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Automatic equalizer", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Harmony listens to the noise around you to adjust the sound."
                setShowBadge(false)
            },
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, NoiseListeningService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Adapting to the noise around you")
            .setContentText("The microphone is used only while music plays on headphones. Nothing is recorded or sent.")
            .setStyle(Notification.BigTextStyle())
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        AutoEqLive.setNoise(FloatArray(EqSettings.BAND_COUNT), null)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "auto_eq_noise"
        private const val NOTIFICATION_ID = 0x4E01
        private const val ACTION_STOP = "com.harmony.autoeq.STOP_NOISE"
        private const val GRACE_MS = 3_000L
        private const val IDLE_STOP_MS = 10 * 60_000L

        /**
         * Starts listening if noise adaptation is on and the microphone is
         * allowed. Call while the app is on screen: Android won't allow it later.
         */
        suspend fun startIfWanted(context: Context, settings: SettingsRepository) {
            val s = settings.settings.first()
            if (!(s.eq.enabled && s.autoEq.noise)) return
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            runCatching { context.startForegroundService(Intent(context, NoiseListeningService::class.java)) }
        }
    }
}
