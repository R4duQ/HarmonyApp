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
 * resumable, up to the limit chosen in Play on ([limitBytes], 1 GB at
 * first; Universal Queue stage 10) and never more than a tenth of the free
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
            limits = { CacheLimits(maxBytes = limitBytes) },
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

    /** The most the copies may take (before the tenth-of-free-space cap). */
    val limitBytes: Long get() = prefs.getLong(KEY_LIMIT, DEFAULT_LIMIT_BYTES).takeIf { it in LIMIT_CHOICES } ?: DEFAULT_LIMIT_BYTES

    /** Bytes the copies take now (whole songs and partial ones). Blocking (briefly). */
    fun usedBytes(): Long = cache.usage()

    /** A new limit (one of [LIMIT_CHOICES]); a lower one frees the space at once. Blocking. */
    fun setLimit(bytes: Long) {
        if (bytes !in LIMIT_CHOICES) return
        prefs.edit().putLong(KEY_LIMIT, bytes).apply()
        cache.trim()
        ConnectDiagnostics.log("cache: limit ${bytes / MB} MB, using ${cache.usage() / MB} MB")
    }

    /** Removes every copy (the songs still play, from the computer). Blocking. */
    fun clear() {
        cache.clear()
        ConnectDiagnostics.log("cache: emptied")
    }

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

    companion object {
        private const val PREFS = "harmony_remote_media"
        private const val KEY_URLS = "urls"
        private const val KEY_LIMIT = "limit_bytes"
        private const val MAX_KNOWN = 500
        private const val MB = 1024L * 1024
        const val DEFAULT_LIMIT_BYTES = 1024 * MB

        /** What Play on offers: 500 MB, 1, 2, 5 GB. */
        val LIMIT_CHOICES = listOf(512 * MB, 1024 * MB, 2048 * MB, 5120 * MB)
    }
}
