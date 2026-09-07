package com.harmony.core.media.watcher

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Something changed" signal source powering automatic library updates.
 *
 * Two triggers:
 *  1. ContentObserver on the MediaStore audio collection — fires for file
 *     additions/removals/renames on MediaStore-indexed volumes as soon as the
 *     system scanner notices them.
 *  2. Volume mount/unmount broadcasts — an SD card or USB drive appearing is
 *     a change even before individual file events arrive.
 *
 * Signals are debounced (copying an album folder fires hundreds of observer
 * calls; we want one rescan at the end). The debounce window is a tradeoff
 * between reactivity and wasted scans; 2s handles bulk copies well because
 * each new file resets the window.
 *
 * FileObserver (inotify) was considered and rejected: it doesn't work across
 * SAF/USB, silently drops events under load, and needs a watch per directory.
 * The observer+debounce+cheap-diff design gives the same user-visible result
 * ("library updates itself") with far fewer platform sharp edges.
 */
@Singleton
class MediaChangeWatcher @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    @kotlinx.coroutines.FlowPreview
    fun changes(): Flow<Unit> = callbackFlow {
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        context.contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            /* notifyForDescendants = */ true,
            observer,
        )

       val mountReceiver = object : android.content.BroadcastReceiver() {
    override fun onReceive(c: Context?, intent: android.content.Intent?) {
        trySend(Unit)
    }
}
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_MEDIA_MOUNTED)
            addAction(android.content.Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(android.content.Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        }
        context.registerReceiver(mountReceiver, filter)

        awaitClose {
            context.contentResolver.unregisterContentObserver(observer)
            context.unregisterReceiver(mountReceiver)
        }
    }.debounce(DEBOUNCE_MS)

    private companion object {
        const val DEBOUNCE_MS = 2_000L
    }
}
