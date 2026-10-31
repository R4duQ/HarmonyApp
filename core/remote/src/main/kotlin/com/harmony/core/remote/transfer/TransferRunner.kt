package com.harmony.core.remote.transfer

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Why an item couldn't be finished, in words for the person and as a [reason]. */
class TransferFailure(val reason: FailReason, message: String, cause: Throwable? = null) : Exception(message, cause)

/** The phone's library and storage, as a transfer needs them (Android's MediaStore in the app). */
interface PhoneLibrary {
    /** Bytes free where files are received and saved. */
    fun freeBytes(): Long

    /** A song already in the library with this content (the library's partial hash and size), if any. */
    fun findInLibrary(item: TransferItem): String?

    /** Whether a file saved earlier at [address] is still there. */
    fun stillThere(address: String): Boolean

    /**
     * Saves the verified [file] for good, as [item]; returns its address. If an
     * identical copy was already saved there (a save cut short before it was
     * recorded) that one is returned, not a second. Throws [TransferFailure].
     */
    fun publish(file: File, item: TransferItem): String
}

/** Where a run is, for a notification. */
data class RunProgress(
    val offer: TransferOffer,
    /** The item being taken, its position (0-based), and how much of it is here. */
    val item: TransferItem?,
    val index: Int,
    val itemBytes: Long,
    val done: Int,
    val failed: Int,
)

/** How a run ended. */
enum class RunOutcome {
    /** Every item is done or failed (see the statuses). */
    FINISHED,
    /** Cancelled here or on the computer: partial files are gone. */
    CANCELLED,
    /** The computer couldn't be reached for a while: try again later, from where it stopped. */
    RETRY_LATER,
}

data class RunResult(val outcome: RunOutcome, val statuses: Map<String, ItemStatus>, val offer: TransferOffer?)

/**
 * Takes one offer's files onto the phone, one at a time.
 *
 * Each file goes to [staging] as `<item>.part` (with its validator beside it),
 * hashed as it comes; after a cut it carries on from where it was if the file
 * on the computer is still the same version (If-Range), else from the start.
 * Only a file of the right size and SHA-256 is saved ([PhoneLibrary.publish]),
 * recorded in the [ledger] before its staging copy goes. Every change is
 * reported to the computer with the attempt it belongs to.
 *
 * Bounded: network errors are retried [maxNetworkRetries] times with backoff
 * (then [RunOutcome.RETRY_LATER], the part kept), a waiting computer
 * (converting, re-hashing) at most [maxBusyWaitMs], a bad checksum once. The
 * pace is [bytesPerSecond] (0: no limit), read each time so it can change
 * while playing. [cancelled] is checked between every block.
 */
class TransferRunner(
    private val client: TransferClient,
    private val staging: File,
    private val ledger: TransferLedger,
    private val library: PhoneLibrary,
    private val bytesPerSecond: () -> Long = { 8L * 1024 * 1024 },
    private val cancelled: () -> Boolean = { false },
    private val onProgress: (RunProgress) -> Unit = {},
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val log: (String) -> Unit = {},
    private val maxNetworkRetries: Int = 6,
    private val maxBusyWaitMs: Long = 60_000,
) {
    private class Cancelled : Exception()
    private class Gone : Exception()
    private class Changed : Exception()

    private val statuses = LinkedHashMap<String, ItemStatus>()
    private var offer: TransferOffer? = null
    private var lastReportAt = 0L

    fun run(offerId: String): RunResult {
        val first = try {
            client.offer(offerId)
        } catch (e: IOException) {
            log("transfer ${offerId.take(8)}: computer not reachable (${e.message})")
            return RunResult(RunOutcome.RETRY_LATER, statuses, null)
        }
        if (first == null || first.cancelled) {
            clean(offerId)
            return RunResult(RunOutcome.CANCELLED, statuses, first)
        }
        offer = first
        first.items.forEach { statuses[it.id] = first.statusOf(it.id) }
        for ((index, original) in first.items.withIndex()) {
            val now = offer!!
            val status = now.statusOf(original.id)
            if (status.state.final) continue
            try {
                runItem(index, now.items.first { it.id == original.id }, status.attempt)
            } catch (_: Cancelled) {
                cancelEverything(offerId, tellComputer = true)
                return RunResult(RunOutcome.CANCELLED, statuses, offer)
            } catch (_: Gone) {
                cancelEverything(offerId, tellComputer = false)
                return RunResult(RunOutcome.CANCELLED, statuses, offer)
            } catch (e: Interrupted) {
                return RunResult(RunOutcome.RETRY_LATER, statuses, offer)
            }
        }
        progress(null, first.items.size, 0)
        return RunResult(RunOutcome.FINISHED, statuses, offer)
    }

    private class Interrupted : Exception()

    private fun runItem(index: Int, start: TransferItem, attempt: Int) {
        var item = start
        fun set(state: ItemState, bytes: Long = 0, reason: FailReason? = null, note: ItemNote = ItemNote.NONE, message: String? = null, force: Boolean = true) {
            val s = ItemStatus(state, bytes, attempt, reason, note, message)
            statuses[item.id] = s
            report(item, s, force)
        }
        checkCancelled()

        // Already here: this very item (a run repeated after a restart), the same file sent before, or the library has it.
        ledger.savedItem(offerId(), item.id)?.let { address ->
            if (library.stillThere(address)) return set(ItemState.DONE, item.sizeBytes) else ledger.forget(address)
        }
        ledger.savedHash(item.sha256)?.let { address ->
            if (library.stillThere(address)) return set(ItemState.DONE, item.sizeBytes, note = ItemNote.ALREADY_ON_PHONE) else ledger.forget(address)
        }
        library.findInLibrary(item)?.let { return set(ItemState.DONE, item.sizeBytes, note = ItemNote.IN_LIBRARY) }

        var changes = 0
        var badChecksums = 0
        while (true) {
            val part = partFile(item)
            val meta = metaFile(item)
            // A part of another version is no use.
            if (part.exists() && readMeta(meta) != item.etag) { part.delete(); meta.delete() }
            val have = if (part.isFile) part.length().coerceAtMost(item.sizeBytes) else 0L
            val need = (item.sizeBytes - have) + item.sizeBytes + SPACE_MARGIN
            if (library.freeBytes() < need) {
                part.delete(); meta.delete()
                return set(ItemState.FAILED, reason = FailReason.NO_SPACE, message = "Not enough space on the phone (${mb(need)} MB needed).")
            }
            set(ItemState.TRANSFERRING, have)
            val sha = try {
                download(index, item, attempt)
            } catch (_: Changed) {
                // The file changed on the computer: its new version, from the start, at most twice.
                part.delete(); meta.delete()
                if (++changes > 2) return set(ItemState.FAILED, reason = FailReason.SOURCE_CHANGED, message = "The file kept changing on the computer.")
                val fresh = try { client.offer(offerId()) } catch (e: IOException) { null }
                    ?: return set(ItemState.FAILED, reason = FailReason.SOURCE_MISSING, message = "The computer no longer offers this file.")
                offer = fresh
                item = fresh.items.firstOrNull { it.id == item.id }
                    ?: return set(ItemState.FAILED, reason = FailReason.SOURCE_MISSING, message = "The computer no longer offers this file.")
                continue
            } catch (e: TransferFailure) {
                if (e.reason == FailReason.NO_SPACE || e.reason == FailReason.SOURCE_MISSING) { part.delete(); meta.delete() }
                return set(ItemState.FAILED, reason = e.reason, message = e.message)
            }
            set(ItemState.VERIFYING, item.sizeBytes)
            if (part.length() != item.sizeBytes || sha != item.sha256) {
                part.delete(); meta.delete()
                if (++badChecksums > 1) return set(ItemState.FAILED, reason = FailReason.CHECKSUM, message = "The file arrived damaged twice.")
                log("transfer: file ${item.id.take(8)} arrived damaged, taking it again")
                continue
            }
            checkCancelled()
            set(ItemState.IMPORTING, item.sizeBytes)
            val address = try {
                library.publish(part, item)
            } catch (e: TransferFailure) {
                return set(ItemState.FAILED, item.sizeBytes, reason = e.reason, message = e.message)
            } catch (e: Exception) {
                return set(ItemState.FAILED, item.sizeBytes, reason = FailReason.OTHER, message = "Couldn't save the file: ${e.message}")
            }
            ledger.record(offerId(), item.id, item.sha256, address)
            part.delete(); meta.delete()
            return set(ItemState.DONE, item.sizeBytes, note = if (item.playableOnPhone) ItemNote.NONE else ItemNote.UNSUPPORTED_FORMAT)
        }
    }

    /** The item's bytes onto its part file; returns their SHA-256. Network trouble is retried here. */
    private fun download(index: Int, item: TransferItem, attempt: Int): String {
        var failures = 0
        var busySince = 0L
        while (true) {
            checkCancelled()
            try {
                return downloadOnce(index, item, attempt)
            } catch (e: Busy) {
                val now = System.currentTimeMillis()
                if (busySince == 0L) busySince = now
                if (now - busySince > maxBusyWaitMs) throw TransferFailure(FailReason.OTHER, "The computer took too long to get the file ready.")
                sleep(e.waitMs)
            } catch (e: IOException) {
                if (isNoSpace(e)) throw TransferFailure(FailReason.NO_SPACE, "The phone's storage filled up.", e)
                failures++
                val have = partFile(item).let { if (it.isFile) it.length() else 0L }
                if (failures > maxNetworkRetries) {
                    statuses[item.id] = ItemStatus(ItemState.INTERRUPTED, have, attempt, FailReason.DISCONNECTED, message = "The computer can't be reached.")
                    runCatching { client.report(offerId(), listOf(ItemReport(item.id, statuses.getValue(item.id)))) }
                    throw Interrupted()
                }
                log("transfer: file ${item.id.take(8)} cut at ${have / 1024} KB (${e.message}); again in ${backoff(failures)} ms")
                sleep(backoff(failures))
            }
        }
    }

    private class Busy(val waitMs: Long) : IOException("busy")

    private fun downloadOnce(index: Int, item: TransferItem, attempt: Int): String {
        val part = partFile(item)
        val meta = metaFile(item)
        part.parentFile?.mkdirs()
        var have = if (part.isFile && readMeta(meta) == item.etag) part.length() else 0L
        if (have > item.sizeBytes) { part.delete(); have = 0 }
        val digest = MessageDigest.getInstance("SHA-256")
        if (have > 0) part.inputStream().use { input -> input.copyToDigest(digest, have) }
        if (have == item.sizeBytes && item.sizeBytes > 0) return hex(digest)

        client.open(offerId(), item.id, have, if (have > 0) item.etag else null).use { r ->
            when (r.status) {
                200, 206 -> Unit
                503 -> throw Busy((r.headers["retry-after"]?.toLongOrNull() ?: 2).coerceIn(1, 10) * 1000)
                404 -> throw TransferFailure(FailReason.SOURCE_MISSING, "The file is no longer on the computer.")
                410 -> throw Gone()
                403 -> throw TransferFailure(FailReason.OTHER, "The computer no longer knows this phone. Pair them again.")
                else -> throw IOException("the computer answered ${r.status}")
            }
            if (r.headers["etag"] != item.etag) throw Changed()
            if (r.status == 200 && have > 0) {
                // The whole file came, not the rest: from the start.
                have = 0
                digest.reset()
            }
            if (r.status == 206) {
                val from = r.headers["content-range"]?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull()
                if (from != have) throw IOException("the computer sent another range")
            }
            writeMeta(meta, item.etag)
            RandomAccessFile(part, "rw").use { out ->
                out.setLength(have)
                out.seek(have)
                val buf = ByteArray(BUFFER)
                val started = System.nanoTime()
                var sent = 0L
                var lastProgress = 0L
                while (have < item.sizeBytes) {
                    checkCancelled()
                    val n = r.body.read(buf, 0, minOf(BUFFER.toLong(), item.sizeBytes - have).toInt())
                    if (n < 0) throw IOException("the transfer was cut")
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    have += n
                    sent += n
                    val now = System.nanoTime()
                    if (now - lastProgress > PROGRESS_NS) {
                        lastProgress = now
                        progress(item, index, have)
                        report(item, ItemStatus(ItemState.TRANSFERRING, have, attempt), force = false)
                    }
                    pace(sent, started)
                }
            }
        }
        progress(item, index, have)
        return hex(digest)
    }

    private fun pace(sent: Long, startedNanos: Long) {
        val rate = bytesPerSecond()
        if (rate <= 0) return
        val due = sent * 1_000_000_000L / rate
        val ahead = (due - (System.nanoTime() - startedNanos)) / 1_000_000
        if (ahead > 5) sleep(ahead)
    }

    private fun report(item: TransferItem, status: ItemStatus, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastReportAt < REPORT_EVERY_MS) return
        lastReportAt = now
        val kept = try {
            client.report(offerId(), listOf(ItemReport(item.id, status)))
        } catch (e: IOException) {
            // The computer will hear at the next report; the file itself carries on.
            return
        }
        if (!kept) throw Gone()
        if (force) progress(item, offer?.items?.indexOfFirst { it.id == item.id } ?: 0, status.bytes)
    }

    private fun progress(item: TransferItem?, index: Int, bytes: Long) {
        val o = offer ?: return
        onProgress(
            RunProgress(
                o, item, index, bytes,
                done = statuses.values.count { it.state == ItemState.DONE },
                failed = statuses.values.count { it.state == ItemState.FAILED },
            ),
        )
    }

    private fun checkCancelled() {
        if (cancelled()) throw Cancelled()
    }

    /** Stopped: no partial file is left, and (when it was the phone's doing) the computer is told. */
    private fun cancelEverything(offerId: String, tellComputer: Boolean) {
        val o = offer
        val open = o?.items?.filter { statuses[it.id]?.state?.final != true }.orEmpty()
        open.forEach { statuses[it.id] = ItemStatus(ItemState.CANCELLED, 0, statuses[it.id]?.attempt ?: 1) }
        if (tellComputer && open.isNotEmpty()) {
            runCatching { client.report(offerId, open.map { ItemReport(it.id, statuses.getValue(it.id)) }) }
        }
        clean(offerId)
    }

    private fun clean(offerId: String) {
        File(staging, safeDir(offerId)).deleteRecursively()
    }

    private fun offerId() = offer!!.id
    private fun partFile(item: TransferItem) = File(File(staging, safeDir(offerId())), safeDir(item.id) + ".part")
    private fun metaFile(item: TransferItem) = File(File(staging, safeDir(offerId())), safeDir(item.id) + ".json")
    private fun readMeta(f: File): String? = runCatching { JSONObject(f.readText()).getString("etag") }.getOrNull()
    private fun writeMeta(f: File, etag: String) = f.writeText(JSONObject().put("etag", etag).toString())

    companion object {
        private const val BUFFER = 64 * 1024
        private const val PROGRESS_NS = 250_000_000L
        private const val REPORT_EVERY_MS = 2_000L
        /** Room kept free besides the file twice over (its part, then its saved copy). */
        const val SPACE_MARGIN = 50L * 1024 * 1024

        private fun backoff(failures: Int) = (1_000L shl (failures - 1).coerceIn(0, 4)).coerceAtMost(16_000)
        private fun mb(bytes: Long) = (bytes + (1 shl 20) - 1) / (1 shl 20)
        private fun hex(d: MessageDigest) = d.digest().joinToString("") { "%02x".format(it) }
        private fun safeDir(id: String) = id.filter { it.isLetterOrDigit() || it == '-' }.take(64).ifEmpty { "x" }

        /** ENOSPC, as the JVM and Android word it. */
        fun isNoSpace(e: Throwable): Boolean {
            var t: Throwable? = e
            while (t != null) {
                val m = t.message.orEmpty()
                if ("ENOSPC" in m || "No space left" in m || "not enough space" in m.lowercase()) return true
                t = t.cause
            }
            return false
        }

        /**
         * Removes what interrupted transfers left in [staging]: offers not in
         * [keep] whose files are older than [maxAgeMs].
         */
        fun cleanStaging(staging: File, keep: Set<String>, maxAgeMs: Long, now: Long = System.currentTimeMillis()) {
            val keepDirs = keep.map(::safeDir).toSet()
            staging.listFiles()?.forEach { dir ->
                if (dir.name in keepDirs) return@forEach
                val newest = dir.walk().filter { it.isFile }.maxOfOrNull { it.lastModified() } ?: dir.lastModified()
                if (now - newest > maxAgeMs) dir.deleteRecursively()
            }
        }
    }
}

private fun java.io.InputStream.copyToDigest(digest: MessageDigest, limit: Long) {
    val buf = ByteArray(64 * 1024)
    var left = limit
    while (left > 0) {
        val n = read(buf, 0, minOf(buf.size.toLong(), left).toInt())
        if (n < 0) break
        digest.update(buf, 0, n)
        left -= n
    }
}
