package com.harmony.desktop.connect

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque

/**
 * The phone's songs, fetched whole over the Wi-Fi while they play, so seeking
 * is instant and the next song starts at once. Only the song playing and the
 * one after it are kept, in a temporary folder that is emptied when the phone
 * lets go and when Harmony starts.
 */
class RemoteCache(private val dir: File = File(System.getProperty("java.io.tmpdir"), "harmony-connect")) {
    private class Entry(val url: String, val file: File) {
        @Volatile var done = false
        @Volatile var failed = false
        @Volatile var cancelled = false
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val queue = LinkedBlockingDeque<Entry>()
    private var counter = 0

    init {
        dir.deleteRecursively()
        dir.mkdirs()
        Thread(::work, "harmony-connect-cache").apply { isDaemon = true }.start()
    }

    /** The local copy of [url], once it is complete. */
    fun localFor(url: String): String? = entries[url]?.takeIf { it.done }?.file?.absolutePath

    /**
     * Fetches [urls] in that order (those not fetched yet), and forgets every
     * other song.
     */
    @Synchronized
    fun keep(urls: List<String>) {
        val wanted = urls.filter { it.isNotBlank() }.distinct()
        for ((url, e) in entries) {
            if (url !in wanted) {
                e.cancelled = true
                entries.remove(url)
                queue.remove(e)
                e.file.delete()
                File(e.file.path + ".part").delete()
            }
        }
        // Newest wishes first: the song playing before the one after it.
        for (url in wanted.asReversed()) {
            val e = entries[url]
            if (e == null) {
                val fresh = Entry(url, File(dir, "song-${counter++}"))
                entries[url] = fresh
                queue.addFirst(fresh)
            } else if (!e.done && !e.failed && queue.remove(e)) {
                queue.addFirst(e)
            }
        }
    }

    /** Forgets everything (the phone let go). */
    fun clear() = keep(emptyList())

    private fun work() {
        while (true) {
            val e = queue.take()
            if (e.cancelled) continue
            runCatching { download(e) }.onFailure {
                e.failed = true
                File(e.file.path + ".part").delete()
            }
        }
    }

    private fun download(e: Entry) {
        val part = File(e.file.path + ".part")
        val c = URL(e.url).openConnection() as HttpURLConnection
        c.connectTimeout = 5_000
        c.readTimeout = 15_000
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            val length = c.contentLengthLong
            if (length > MAX_BYTES) throw IllegalStateException("too big to keep")
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (e.cancelled) return
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
            if (e.cancelled) {
                part.delete()
                return
            }
            if (length >= 0 && part.length() != length) throw IllegalStateException("cut short")
            if (!part.renameTo(e.file)) throw IllegalStateException("couldn't keep it")
            e.done = true
        } finally {
            c.disconnect()
            if (e.cancelled) {
                part.delete()
                e.file.delete()
            }
        }
    }

    private companion object {
        /** A 24/192 FLAC album side is about this; anything bigger plays from the phone. */
        const val MAX_BYTES = 600L * 1024 * 1024
    }
}
