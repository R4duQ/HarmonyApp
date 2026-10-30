package com.harmony.core.remote.cache

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/** A song wanted in the cache: [priority] 0 is the one playing, 1 the next, then further ahead. */
data class CacheWant(val key: String, val url: String, val priority: Int)

/**
 * How much the cache may hold: [maxBytes] (the user's setting), and never more
 * than [freeShare] of the disk space it could use. Downloads ahead of time
 * (priority > 0) go no faster than [prefetchBytesPerSecond], so they leave
 * room on the Wi-Fi for the song playing and for play, pause and the queue.
 */
data class CacheLimits(
    val maxBytes: Long,
    val freeShare: Double = 0.10,
    val prefetchBytesPerSecond: Long = 8L * 1024 * 1024,
)

/**
 * Songs from another device, kept on disk so they play without waiting on
 * the network and keep playing when it drops (Universal Queue, stage 4).
 *
 *  - **Keyed by content** ([CacheWant.key]), not by URL: the same song queued
 *    twice, or played again in a later session, is one file fetched once.
 *  - **Separate from the library**: its own folder, never scanned or indexed.
 *  - **One download at a time, most urgent first** ([want]): the song playing,
 *    then the next, then one or two more. A more urgent song takes over; what
 *    was being fetched stays as a partial file and resumes later.
 *  - **Resumable**: a cut transfer continues from where it stopped (`Range`),
 *    with `If-Range` so a file changed meanwhile comes whole instead of two
 *    versions glued together. Written to `.part`, renamed only when complete
 *    and of the size the server announced.
 *  - **Bounded** ([CacheLimits]): least recently used songs go first; the song
 *    playing and the next one are never removed. A song that doesn't fit is
 *    simply not kept (it plays from the network).
 *  - **Bounded memory**: one 256 KB buffer, whatever the file size.
 *
 * Thread-safe; downloads run on one background thread.
 */
class MediaCache(
    private val dir: File,
    private val limits: () -> CacheLimits,
    private val usableSpace: () -> Long = { dir.usableSpace },
    private val fetcher: Fetcher = SocketFetcher(),
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private class Entry(val key: String, val name: String) {
        var total = -1L
        var etag: String? = null
        var complete = false
        var lastUsed = 0L
        var corruptions = 0
        // Not kept on disk:
        var failures = 0
        var retryAt = 0L
        var noRoom = false
    }

    private val lock = Object()
    private val entries = LinkedHashMap<String, Entry>()
    private var wants: List<CacheWant> = emptyList()
    private var active: Pair<Entry, CacheWant>? = null
    @Volatile private var cancelActive = false
    @Volatile private var open = true
    private var dirty = false
    private val worker = Thread(::work, "harmony-media-cache").apply { isDaemon = true }

    /** Bytes downloaded, for measuring (each byte counted once per transfer). */
    @Volatile var downloadedBytes = 0L
        private set

    init {
        dir.mkdirs()
        load()
        worker.start()
    }

    /**
     * What should be cached now, most urgent first (lowest priority number).
     * Replaces the previous wishes: a download no longer wanted stops (its
     * partial file stays, to resume if it is wanted again).
     */
    fun want(list: List<CacheWant>) = synchronized(lock) {
        val next = list.filter { it.url.isNotBlank() }.sortedBy { it.priority }.distinctBy { it.key }
        wants = next
        // A new wish deserves a new try: "no room" and earlier failures are looked at again.
        next.forEach { w -> entries[w.key]?.let { it.noRoom = false; it.failures = 0; it.retryAt = 0 } }
        active?.let { (e, w) ->
            val still = next.firstOrNull { it.key == e.key }
            val urgent = next.firstOrNull { it.priority < (still?.priority ?: Int.MAX_VALUE) && !isComplete(it.key) && !blocked(it.key) }
            if (still == null || urgent != null) cancelActive = true
        }
        lock.notifyAll()
    }

    /** The complete local copy of [key], or null (then play it from the network). */
    fun localFor(key: String): String? = synchronized(lock) {
        val e = entries[key]?.takeIf { it.complete } ?: return null
        val file = finalFile(e)
        if (!file.isFile || file.length() != e.total) {
            drop(e)
            return null
        }
        e.lastUsed = clock()
        dirty = true
        file.absolutePath
    }

    /**
     * The local copy of [key] couldn't be played (the decoder failed on it):
     * it goes, and is fetched once more; if that copy fails too, the song is
     * played from the network only.
     */
    fun corrupt(key: String) = synchronized(lock) {
        val e = entries[key] ?: return
        log("cache: copy of $key unplayable (${e.corruptions + 1}), dropped")
        finalFile(e).delete()
        partFile(e).delete()
        e.complete = false
        e.corruptions++
        e.total = -1
        e.etag = null
        save()
        lock.notifyAll()
    }

    /** Bytes on disk (complete songs and partial ones). */
    fun usage(): Long = synchronized(lock) { entries.values.sumOf { sizeOf(it) } }

    /** Empties the cache (the user asked); downloads wanted now start over. */
    fun clear() = synchronized(lock) {
        cancelActive = active != null
        entries.values.forEach { finalFile(it).delete(); partFile(it).delete() }
        entries.clear()
        save()
        lock.notifyAll()
    }

    override fun close() {
        open = false
        cancelActive = true
        synchronized(lock) {
            if (dirty) save()
            lock.notifyAll()
        }
    }

    // ---- The download thread -------------------------------------------------------

    private fun work() {
        while (open) {
            val job = synchronized(lock) {
                if (dirty) save()
                val now = clock()
                val ready = wants.firstOrNull { w ->
                    val e = entries[w.key]
                    e == null || (!e.complete && !e.noRoom && e.corruptions < MAX_CORRUPTIONS && e.retryAt <= now)
                }
                if (ready == null) {
                    val wake = wants.mapNotNull { entries[it.key]?.retryAt?.takeIf { t -> t > now } }.minOrNull()
                    lock.wait(((wake ?: (now + IDLE_MS)) - now).coerceIn(10, IDLE_MS))
                    return@synchronized null
                }
                val e = entries.getOrPut(ready.key) { Entry(ready.key, nameFor(ready.key)).also { dirty = true } }
                cancelActive = false
                active = e to ready
                e to ready
            } ?: continue
            val (entry, want) = job
            try {
                download(entry, want)
            } catch (e: Exception) {
                synchronized(lock) {
                    entry.failures++
                    entry.retryAt = clock() + backoff(entry.failures)
                    log("cache: ${want.key} failed (${e.message}); retry in ${entry.retryAt - clock()} ms")
                }
            } finally {
                synchronized(lock) {
                    active = null
                    save()
                }
            }
        }
    }

    private fun download(e: Entry, want: CacheWant) {
        val part = partFile(e)
        var offset = if (part.isFile) part.length() else 0L
        if (e.total > 0 && offset >= e.total) offset = 0
        val response = fetcher.get(want.url, offset, e.etag.takeIf { offset > 0 })
        response.use { r ->
            val etag = r.headers["etag"]
            when (r.status) {
                206 -> {
                    val start = r.headers["content-range"]?.removePrefix("bytes ")?.substringBefore('-')?.toLongOrNull()
                    if (start != offset) throw IOException("range starts at $start, asked $offset")
                }
                200 -> offset = 0
                416 -> {
                    // Asked beyond the end: what we have is no use. Start over next time.
                    part.delete()
                    synchronized(lock) { e.etag = null; e.total = -1 }
                    throw IOException("range not satisfiable")
                }
                else -> throw IOException("HTTP ${r.status}")
            }
            val total = r.headers["content-range"]?.substringAfter('/')?.toLongOrNull()
                ?: r.headers["content-length"]?.toLongOrNull()?.plus(offset)
                ?: throw IOException("no length")
            val ok = synchronized(lock) {
                if (offset == 0L) part.delete()
                e.etag = etag
                e.total = total
                makeRoom(total - offset, e, want.priority)
            }
            if (!ok) {
                synchronized(lock) {
                    // Remembered, so it isn't tried again until it is wanted anew.
                    e.noRoom = true
                    part.delete()
                    dirty = true
                    log("cache: no room for ${want.key} ($total bytes); it plays from the network")
                }
                return
            }
            RandomAccessFile(part, "rw").use { out ->
                out.setLength(offset)
                out.seek(offset)
                val buf = ByteArray(BUFFER)
                val started = System.nanoTime()
                var got = 0L
                val throttle = want.priority > 0
                while (true) {
                    if (cancelActive || !open) {
                        log("cache: ${want.key} paused at ${offset + got}/$total")
                        return
                    }
                    val n = r.body.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    got += n
                    downloadedBytes += n
                    if (throttle) pace(got, started)
                }
            }
            val size = part.length()
            if (size != total) throw IOException("cut short at $size of $total")
            synchronized(lock) {
                val final = finalFile(e)
                final.delete()
                if (!part.renameTo(final)) throw IOException("couldn't keep ${want.key}")
                e.complete = true
                e.failures = 0
                e.lastUsed = clock()
                log("cache: ${want.key} complete ($total bytes)")
            }
        }
    }

    /** Sleeps as needed to keep a download ahead of time under the rate limit. */
    private fun pace(bytes: Long, startedNanos: Long) {
        val rate = limits().prefetchBytesPerSecond
        if (rate <= 0) return
        val due = bytes * 1_000_000_000L / rate
        val ahead = due - (System.nanoTime() - startedNanos)
        if (ahead > 1_000_000) Thread.sleep(ahead / 1_000_000)
    }

    /**
     * Makes room for [bytes] more, removing least recently used songs. Never
     * the song playing, the next one, or [keep]; wanted songs further ahead only
     * for a more urgent one ([priority]). False when it can't be done.
     */
    private fun makeRoom(bytes: Long, keep: Entry, priority: Int): Boolean {
        val budget = budget()
        if (bytes > budget) return false
        val wantedPriority = wants.associate { it.key to it.priority }
        fun over() = entries.values.sumOf { sizeOf(it) } + bytes > budget
        if (!over()) return true
        val candidates = entries.values
            .filter { it !== keep && (wantedPriority[it.key] ?: Int.MAX_VALUE) > maxOf(1, priority) }
            .sortedWith(compareBy({ -(wantedPriority[it.key] ?: Int.MAX_VALUE).toLong() }, { it.lastUsed }))
        for (c in candidates) {
            if (!over()) break
            log("cache: removing ${c.key} to make room")
            drop(c)
        }
        return !over()
    }

    private fun budget(): Long {
        val l = limits()
        val own = entries.values.sumOf { sizeOf(it) }
        return minOf(l.maxBytes, ((usableSpace() + own) * l.freeShare).toLong())
    }

    private fun drop(e: Entry) {
        finalFile(e).delete()
        partFile(e).delete()
        entries.remove(e.key)
        dirty = true
    }

    private fun isComplete(key: String) = entries[key]?.complete == true
    private fun blocked(key: String) = (entries[key]?.corruptions ?: 0) >= MAX_CORRUPTIONS

    private fun sizeOf(e: Entry): Long = if (e.complete) e.total.coerceAtLeast(0) else partFile(e).length()

    private fun finalFile(e: Entry) = File(dir, e.name + ".audio")
    private fun partFile(e: Entry) = File(dir, e.name + ".part")

    private fun nameFor(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(16).joinToString("") { "%02x".format(it) }

    private fun backoff(failures: Int): Long = (1_000L shl (failures - 1).coerceIn(0, 5)).coerceAtMost(30_000)

    // ---- The index ---------------------------------------------------------------

    private val indexFile get() = File(dir, "index.json")

    private fun load() {
        val known = runCatching {
            val a = JSONObject(indexFile.readText()).getJSONArray("songs")
            List(a.length()) { a.getJSONObject(it) }
        }.getOrDefault(emptyList())
        for (o in known) {
            val e = Entry(o.getString("key"), o.getString("name")).apply {
                total = o.optLong("total", -1)
                etag = o.optString("etag").takeIf { it.isNotEmpty() }
                complete = o.optBoolean("complete")
                lastUsed = o.optLong("lastUsed")
                corruptions = o.optInt("corruptions")
            }
            // Trust the disk, not the index: a complete song must be there, whole.
            if (e.complete && finalFile(e).length() != e.total) e.complete = false
            if (e.complete || partFile(e).isFile) entries[e.key] = e
        }
        // Anything else in the folder (a crash between writes, an older cache) goes.
        val names = entries.values.flatMap { listOf(finalFile(it).name, partFile(it).name) }.toSet() + indexFile.name
        dir.listFiles()?.filter { it.name !in names }?.forEach { it.delete() }
        save()
    }

    private fun save() {
        val a = JSONArray()
        entries.values.forEach { e ->
            a.put(
                JSONObject().put("key", e.key).put("name", e.name).put("total", e.total).putOpt("etag", e.etag)
                    .put("complete", e.complete).put("lastUsed", e.lastUsed).put("corruptions", e.corruptions),
            )
        }
        val tmp = File(dir, "index.json.tmp")
        runCatching {
            tmp.writeText(JSONObject().put("songs", a).toString())
            if (!tmp.renameTo(indexFile)) {
                indexFile.delete()
                tmp.renameTo(indexFile)
            }
            dirty = false
        }.onFailure { log("cache: couldn't write the index: ${it.message}") }
    }

    private companion object {
        const val BUFFER = 256 * 1024
        const val IDLE_MS = 5_000L
        const val MAX_CORRUPTIONS = 2
    }
}
