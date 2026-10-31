package com.harmony.playback.service.connect

import android.content.Context
import com.harmony.core.remote.cache.CacheLimits
import com.harmony.core.remote.cache.CacheWant
import com.harmony.core.remote.cache.MediaCache
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The computer's songs in the phone's queue (Universal Queue, stage 6): where
 * each comes from, and the phone's own copies of them.
 *
 * The copies live in the app's cache folder, never in the music library: no
 * MediaStore entry, no 24-D analysis, Android may clear them when space runs
 * short. The song playing and the next three are fetched, most urgent first,
 * resumable, up to [LIMIT_BYTES] and never more than a tenth of the free
 * space ([MediaCache], the same code as on the computer); without a copy the
 * player reads straight from the computer.
 */
@Singleton
class RemoteMedia @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Content key -> the computer's address for it, oldest first, kept so a
     * restored queue still knows where its songs are. Guarded by itself.
     */
    private val urls = LinkedHashMap<String, String>()

    private val cache: MediaCache by lazy {
        MediaCache(
            File(context.cacheDir, "connect-media").apply { mkdirs() },
            limits = { CacheLimits(maxBytes = LIMIT_BYTES) },
            log = { ConnectDiagnostics.log(it) },
        )
    }

    @Volatile private var focused: List<String> = emptyList()

    init {
        runCatching {
            val o = JSONObject(prefs.getString(KEY_URLS, "{}") ?: "{}")
            o.keys().forEach { k -> urls[k] = o.getString(k) }
        }
    }

    /** The computer's song [key] can be had at [url] (its media address, with this phone's token). */
    fun register(key: String, url: String) {
        val saved = synchronized(urls) {
            if (urls[key] == url) return
            urls.remove(key)
            urls[key] = url
            // Bounded: the most recent ones (and never one in the queue's window).
            while (urls.size > MAX_KNOWN) urls.keys.firstOrNull { it !in focused }?.let(urls::remove) ?: break
            JSONObject(urls as Map<*, *>).toString()
        }
        prefs.edit().putString(KEY_URLS, saved).apply()
    }

    fun urlFor(key: String): String? = synchronized(urls) { urls[key] }

    /** The whole copy of [key] on the phone, if there is one. */
    fun localFor(key: String): String? = cache.localFor(key)

    /** A copy that wouldn't play: dropped, fetched once more. */
    fun corrupt(key: String) = cache.corrupt(key)

    /**
     * The computer's songs among the song playing and the next ones, in
     * order: fetched in that order; anything else being fetched stops.
     */
    fun focus(keys: List<String>) {
        focused = keys
        val wants = keys.mapIndexedNotNull { i, k -> urlFor(k)?.let { CacheWant(k, it, i) } }
        if (wants == lastWants) return
        lastWants = wants
        cache.want(wants)
    }

    @Volatile private var lastWants: List<CacheWant> = emptyList()

    private companion object {
        const val TAG = "HarmonyRemoteMedia"
        const val PREFS = "harmony_remote_media"
        const val KEY_URLS = "urls"
        const val MAX_KNOWN = 500
        const val LIMIT_BYTES = 1L * 1024 * 1024 * 1024
    }
}
