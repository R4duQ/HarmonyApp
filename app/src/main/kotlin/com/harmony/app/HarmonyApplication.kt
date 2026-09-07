package com.harmony.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.harmony.domain.shuffle.SmartQueueCoordinator
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
    @Inject lateinit var smartQueueCoordinator: SmartQueueCoordinator
    @Inject lateinit var settingsApplier: SettingsApplier

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

   override fun onCreate() {
        super.onCreate()
        androidx.work.WorkManager.initialize(this, workManagerConfiguration)

        // Kicks off: periodic maintenance + immediate scan/analyze + change watching.
        // Runtime permission gating happens in MainActivity (Phase 8); until
        // granted, MediaStore queries legally return empty and the first real
        // scan happens right after the grant fires the content observer.
        libraryAutoUpdater.start()
        smartQueueCoordinator.start(appScope)
        settingsApplier.start(appScope)
    }
}
