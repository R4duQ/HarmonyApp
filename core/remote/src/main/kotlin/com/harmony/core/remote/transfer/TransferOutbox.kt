package com.harmony.core.remote.transfer

import com.harmony.core.model.PartialHash
import com.harmony.core.remote.HttpResponse
import com.harmony.core.remote.RangeSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** A file the computer is asked to send. */
data class OutgoingFile(
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val mime: String,
    val playableOnPhone: Boolean,
)

/** One send as the computer's screen shows it. */
data class OutboxEntry(
    val offer: TransferOffer,
    val phoneName: String,
    /** Items whose hash is known; the offer reaches the phone once all are. */
    val prepared: Int,
    val preparing: Boolean,
    /** Bytes of each item the phone has taken from here (live progress, before its own reports). */
    val served: Map<String, Long>,
    /** Why preparing failed (a file gone), if it did. */
    val error: String? = null,
)

/**
 * The computer's side of "Send to phone": the offers it has made, the files
 * behind them, and what each phone has said.
 *
 * - An offer belongs to one phone (its pairing token): another phone, or one
 *   not paired, gets nothing, not even word that it exists.
 * - Only the files put in an offer are served, by offer and item id, never by
 *   path; the path never leaves the computer.
 * - Each file is hashed (SHA-256, and the library's partial hash) before the
 *   offer is shown, on one low-priority thread. A file that changed since
 *   (size or time) is hashed again before it is served (503 meanwhile) and
 *   gets a new version, so the phone never glues two versions together.
 * - A report for an older attempt (before a retry) or a cancelled item is
 *   ignored; a cancelled offer answers 410, which tells the phone to stop.
 *
 * Kept in [store] (JSON) so a send survives the computer restarting. Thread
 * safe: requests come on the HTTP server's threads, the screen calls from its own.
 */
class TransferOutbox(
    private val store: File? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) : AutoCloseable {
    private class Entry(
        var offer: TransferOffer,
        val token: String,
        val phoneName: String,
        /** item id -> the file here. */
        val paths: Map<String, String>,
        /** item id -> (size, modified) when it was hashed. */
        val stamps: MutableMap<String, Pair<Long, Long>> = HashMap(),
        val served: MutableMap<String, Long> = HashMap(),
        var preparing: Boolean = true,
        var error: String? = null,
        val rehashing: MutableSet<String> = HashSet(),
    )

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()
    private val hasher: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "harmony-transfer-hash").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    /** Called after any change, off the caller's lock: the screen reads [entries]. */
    @Volatile var onChange: () -> Unit = {}

    init {
        load()
    }

    /** Every send, newest last. */
    fun entries(): List<OutboxEntry> = synchronized(lock) {
        entries.values.map { e ->
            OutboxEntry(e.offer, e.phoneName, e.stamps.size, e.preparing, HashMap(e.served), e.error)
        }
    }

    /**
     * Offers [files] to the phone of [token] ([phoneName] for the screen),
     * from this computer ([fromId], [fromName]). The phone sees it once every
     * file is hashed. Returns the offer id.
     */
    fun create(token: String, phoneName: String, fromId: String, fromName: String, files: List<OutgoingFile>): String {
        require(files.isNotEmpty()) { "nothing to send" }
        val id = UUID.randomUUID().toString()
        val names = HashSet<String>()
        val items = files.map { f ->
            val file = File(f.path)
            TransferItem(
                id = UUID.randomUUID().toString(),
                name = uniqueName(safeName(file.name), names),
                sizeBytes = file.length(),
                sha256 = "",
                libraryHash = "",
                mime = f.mime,
                title = f.title,
                artist = f.artist,
                album = f.album,
                durationMs = f.durationMs,
                playableOnPhone = f.playableOnPhone,
            )
        }
        val entry = Entry(
            TransferOffer(id, fromId, fromName, clock(), items),
            token, phoneName,
            paths = items.zip(files).associate { (item, f) -> item.id to f.path },
        )
        synchronized(lock) { entries[id] = entry }
        changed(save = false)
        hasher.execute { prepare(id) }
        return id
    }

    private fun prepare(offerId: String) {
        val entry = synchronized(lock) { entries[offerId] } ?: return
        for (item in entry.offer.items) {
            if (synchronized(lock) { entry.offer.cancelled || entries[offerId] == null }) return
            val path = entry.paths.getValue(item.id)
            val result = runCatching { hash(File(path)) }
            val hashed = result.getOrNull()
            if (hashed == null) {
                synchronized(lock) {
                    entry.error = "${File(path).name}: ${result.exceptionOrNull()?.message ?: "can't be read"}"
                    entry.offer = entry.offer.withStatus(item.id) { s -> s.copy(state = ItemState.FAILED, reason = FailReason.SOURCE_MISSING, message = "The file can't be read on the computer.") }
                    entry.stamps[item.id] = -1L to -1L
                }
                changed()
                continue
            }
            synchronized(lock) {
                entry.offer = entry.offer.withItem(item.id) { it.copy(sha256 = hashed.sha256, libraryHash = hashed.partial, sizeBytes = hashed.size) }
                entry.stamps[item.id] = hashed.size to hashed.modified
            }
            changed(save = false)
        }
        synchronized(lock) { entry.preparing = false }
        log("transfer ${offerId.take(8)}: ${entry.offer.items.size} file(s) ready for ${entry.phoneName}")
        changed()
    }

    private class Hashed(val sha256: String, val partial: String, val size: Long, val modified: Long)

    /** SHA-256 of the whole file and the library's partial hash, in one read. */
    private fun hash(file: File): Hashed {
        val modified = file.lastModified()
        val size = file.length()
        if (!file.isFile) throw java.io.FileNotFoundException("gone")
        val full = MessageDigest.getInstance("SHA-256")
        val head = java.io.ByteArrayOutputStream(PartialHash.HEAD_BYTES)
        file.inputStream().buffered(256 * 1024).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                full.update(buf, 0, n)
                if (head.size() < PartialHash.HEAD_BYTES) head.write(buf, 0, minOf(n, PartialHash.HEAD_BYTES - head.size()))
            }
        }
        val partial = PartialHash.of(head.toByteArray().inputStream(), size)
        return Hashed(full.digest().joinToString("") { "%02x".format(it) }, partial, size, modified)
    }

    // ---- What the phone asks ------------------------------------------------------

    /** The offers ready for the phone of [token] with something left to do. */
    fun offersFor(token: String): List<TransferOffer> = synchronized(lock) {
        entries.values.filter { it.token == token && !it.preparing && it.offer.open.isNotEmpty() }.map { it.offer }
    }

    /** One offer of the phone of [token] (a cancelled one too, so the phone learns of it). */
    fun offer(token: String, offerId: String): TransferOffer? = synchronized(lock) {
        entries[offerId]?.takeIf { it.token == token && !it.preparing }?.offer
    }

    /** The bytes of an item for the phone of [token]. */
    fun serve(token: String, offerId: String, itemId: String, range: String?, ifRange: String?): HttpResponse {
        val (entry, item) = synchronized(lock) {
            val e = entries[offerId]?.takeIf { it.token == token && !it.preparing } ?: return notFound()
            if (e.offer.cancelled) return gone()
            val item = e.offer.items.firstOrNull { it.id == itemId } ?: return notFound()
            if (e.offer.statusOf(itemId).state == ItemState.CANCELLED) return gone()
            if (itemId in e.rehashing) return busy()
            e to item
        }
        val file = File(entry.paths.getValue(itemId))
        if (!file.isFile) return HttpResponse.text(404, "the file is no longer on the computer")
        val stamp = synchronized(lock) { entry.stamps[itemId] }
        if (stamp == null || stamp.first != file.length() || stamp.second != file.lastModified()) {
            rehash(entry, itemId)
            return busy()
        }
        val source = object : RangeSource {
            override val length: Long get() = item.sizeBytes
            override val etag: String get() = item.etag
            override fun open(offset: Long): InputStream {
                val raf = java.io.RandomAccessFile(file, "r")
                raf.seek(offset)
                val input = java.nio.channels.Channels.newInputStream(raf.channel)
                return object : InputStream() {
                    var at = offset
                    override fun read(): Int = throw UnsupportedOperationException()
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = input.read(b, off, len)
                        if (n > 0) {
                            at += n
                            synchronized(lock) { entry.served[itemId] = at }
                        }
                        return n
                    }
                    override fun close() = input.close()
                }
            }
        }
        return HttpResponse.ranged(source, range, item.mime.ifEmpty { "application/octet-stream" }, ifRange)
    }

    /** The file of [itemId] changed here: hashed again, a new version; the phone starts it over. */
    private fun rehash(entry: Entry, itemId: String) {
        synchronized(lock) { if (!entry.rehashing.add(itemId)) return }
        hasher.execute {
            val path = entry.paths.getValue(itemId)
            val hashed = runCatching { hash(File(path)) }.getOrNull()
            synchronized(lock) {
                entry.rehashing -= itemId
                if (hashed == null) {
                    entry.offer = entry.offer.withStatus(itemId) { it.copy(state = ItemState.FAILED, reason = FailReason.SOURCE_MISSING, message = "The file can't be read on the computer.") }
                } else {
                    entry.offer = entry.offer
                        .withItem(itemId) { it.copy(sha256 = hashed.sha256, libraryHash = hashed.partial, sizeBytes = hashed.size, version = it.version + 1) }
                        .withStatus(itemId) { s -> if (s.state.final) s else s.copy(bytes = 0) }
                    entry.stamps[itemId] = hashed.size to hashed.modified
                    entry.served.remove(itemId)
                    log("transfer ${entry.offer.id.take(8)}: a file changed on the computer, sending its new version")
                }
            }
            changed()
        }
    }

    /** The phone's reports for an offer of its own: 200, 404 (not its own), 410 (cancelled). */
    fun report(token: String, offerId: String, reports: List<ItemReport>): HttpResponse {
        var stateChanged = false
        val cancelled = synchronized(lock) {
            val e = entries[offerId]?.takeIf { it.token == token } ?: return notFound()
            for (r in reports) {
                val now = e.offer.status[r.itemId] ?: if (e.offer.items.any { it.id == r.itemId }) ItemStatus() else continue
                // An older attempt (before a retry from here), or an item cancelled here: not news.
                if (r.status.attempt != now.attempt) continue
                if (now.state == ItemState.CANCELLED && r.status.state != ItemState.CANCELLED) continue
                if (now.state == ItemState.DONE && r.status.state != ItemState.DONE) continue
                if (r.status.state != now.state || r.status.reason != now.reason || r.status.note != now.note) stateChanged = true
                e.offer = e.offer.withStatus(r.itemId) { r.status }
            }
            e.offer.cancelled
        }
        changed(save = stateChanged)
        return if (cancelled) gone() else HttpResponse.json("{\"ok\":true}")
    }

    // ---- What the computer's screen does ------------------------------------------

    /** Stops a send: the phone is told at its next request and drops what it was doing. */
    fun cancel(offerId: String) {
        synchronized(lock) {
            val e = entries[offerId] ?: return
            e.offer = e.offer.copy(cancelled = true).let { o ->
                o.copy(status = o.items.associate { i -> i.id to o.statusOf(i.id).let { s -> if (s.state.final) s else s.copy(state = ItemState.CANCELLED, reason = null) } })
            }
        }
        changed()
    }

    /** Sends the items that failed, were interrupted or cancelled once more (a new attempt each). */
    fun retry(offerId: String) {
        synchronized(lock) {
            val e = entries[offerId] ?: return
            val retried = e.offer.status.mapValues { (_, s) ->
                if (s.state == ItemState.FAILED || s.state == ItemState.CANCELLED || s.state == ItemState.INTERRUPTED) ItemStatus(attempt = s.attempt + 1) else s
            }
            e.offer = e.offer.copy(cancelled = false, status = retried)
            e.error = null
        }
        changed()
    }

    /** Forgets a send (its files stay where they are, here and on the phone). */
    fun remove(offerId: String) {
        synchronized(lock) { entries.remove(offerId) }
        changed()
    }

    // ---- Keeping it -------------------------------------------------------------------

    private fun changed(save: Boolean = true) {
        if (save) save()
        runCatching { onChange() }
    }

    private fun save() {
        val f = store ?: return
        val text = synchronized(lock) {
            JSONObject().put("offers", JSONArray().apply {
                entries.values.filter { !it.preparing }.forEach { e ->
                    put(
                        JSONObject().put("offer", e.offer.toJson()).put("token", e.token).put("phone", e.phoneName)
                            .put("paths", JSONObject(e.paths as Map<*, *>))
                            .put("stamps", JSONObject().apply { e.stamps.forEach { (k, v) -> put(k, JSONArray().put(v.first).put(v.second)) } }),
                    )
                }
            }).toString()
        }
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { log("transfer: couldn't save the sends (${it.message})") }
    }

    private fun load() {
        val f = store?.takeIf { it.isFile } ?: return
        runCatching {
            val a = JSONObject(f.readText()).getJSONArray("offers")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val offer = TransferOffer.fromJson(o.getJSONObject("offer"))
                val paths = o.getJSONObject("paths").let { p -> p.keys().asSequence().associateWith { p.getString(it) } }
                val stamps = o.optJSONObject("stamps") ?: JSONObject()
                entries[offer.id] = Entry(offer, o.getString("token"), o.optString("phone"), paths, preparing = false).apply {
                    stamps.keys().forEach { k -> stamps.getJSONArray(k).let { v -> this.stamps[k] = v.getLong(0) to v.getLong(1) } }
                }
            }
        }.onFailure { log("transfer: couldn't read the saved sends (${it.message})") }
    }

    override fun close() {
        hasher.shutdownNow()
    }

    companion object {
        /** A name safe on any phone: no folders, no characters file systems refuse, not too long. */
        fun safeName(name: String): String {
            val cleaned = name.map { c -> if (c in "/\\:*?\"<>|" || c.code < 32) '_' else c }.joinToString("").trim()
            val dot = cleaned.lastIndexOf('.')
            val ext = if (dot >= 0) cleaned.substring(dot + 1).filter { it.isLetterOrDigit() }.take(8) else ""
            val base = (if (dot >= 0) cleaned.substring(0, dot) else cleaned).trim().trim('.').trim()
                .ifBlank { "song" }.take(MAX_NAME - ext.length - 1)
            return if (ext.isEmpty()) base else "$base.$ext"
        }

        private fun uniqueName(name: String, taken: MutableSet<String>): String {
            var candidate = name
            var n = 2
            val ext = name.substringAfterLast('.', "")
            val base = if (ext.isEmpty()) name else name.removeSuffix(".$ext")
            while (!taken.add(candidate.lowercase())) {
                candidate = "$base ($n)" + if (ext.isEmpty()) "" else ".$ext"
                n++
            }
            return candidate
        }

        private const val MAX_NAME = 150
        private fun notFound() = HttpResponse.text(404, "no such transfer")
        private fun gone() = HttpResponse.text(410, "cancelled")
        private fun busy() = HttpResponse(503, "text/plain; charset=utf-8", 0, mapOf("Retry-After" to "2")) {}
    }
}

private fun TransferOffer.withItem(itemId: String, change: (TransferItem) -> TransferItem) =
    copy(items = items.map { if (it.id == itemId) change(it) else it })

private fun TransferOffer.withStatus(itemId: String, change: (ItemStatus) -> ItemStatus) =
    copy(status = status + (itemId to change(statusOf(itemId))))
