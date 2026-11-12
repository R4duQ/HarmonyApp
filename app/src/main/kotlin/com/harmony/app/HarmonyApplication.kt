package com.harmony.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.harmony.domain.shuffle.SmartQueueManager
import com.harmony.sync.analysis.LibraryAutoUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class HarmonyApplication : Application(), Configuration.Provider, coil.ImageLoaderFactory {

    /**
     * Artwork loader policy for large libraries:
     *  - RGB_565: halves per-bitmap memory; artwork thumbnails don't need alpha
     *    or 8-bit channels at list sizes.
     *  - 25% memory cache: scrolling 20k rows must not evict-thrash.
     *  - Small disk cache: sources are LOCAL files already (ArtworkCache),
     *    so the disk layer only holds downsampled variants.
     */
    override fun newImageLoader(): coil.ImageLoader =
        coil.ImageLoader.Builder(this)
            .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
            .memoryCache {
                coil.memory.MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(cacheDir.resolve("artwork_coil"))
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()


    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var libraryAutoUpdater: LibraryAutoUpdater
    @Inject lateinit var smartQueueManager: SmartQueueManager
    @Inject lateinit var settingsApplier: SettingsApplier
    @Inject lateinit var connectShuffleBridge: ConnectShuffleBridge
    @Inject lateinit var connectQueueBridge: ConnectQueueBridge

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun versionLabel(): String = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("unknown")

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

   override fun onCreate() {
        super.onCreate()
        // Crashes and caught errors go to a log the listener can open, copy and share
        // (Settings › Diagnostics log): Logcat is out of reach without a computer.
        com.harmony.core.common.crash.DiagnosticsLog.install(filesDir, versionLabel())
        androidx.work.WorkManager.initialize(this, workManagerConfiguration)

        // Kicks off: periodic maintenance + immediate scan/analyze + change watching.
        // Runtime permission gating happens in MainActivity (Phase 8); until
        // granted, MediaStore queries legally return empty and the first real
        // scan happens right after the grant fires the content observer.
        libraryAutoUpdater.start()
        smartQueueManager.start(appScope)
        settingsApplier.start(appScope)
        connectShuffleBridge.start(appScope)
        connectQueueBridge.start(appScope)
    }
}
