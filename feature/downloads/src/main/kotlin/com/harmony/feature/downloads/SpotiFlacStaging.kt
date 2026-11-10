package com.harmony.feature.downloads

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * The staging folders of downloads.
 *
 * A folder is named after the track and format, not after the attempt, so a
 * later attempt (Resume after Pause, a retry after the connection dropped, a
 * restart of the app) finds what the earlier one left. The runtime keeps a
 * partial transfer next to its target as `<file>.partial` plus checkpoint
 * files and continues from it when the provider allows a validated resume
 * (same resource, same validator, the range honoured; otherwise it starts
 * clean by itself). Everything else in the folder belongs to a finished
 * attempt and is removed.
 */
internal object SpotiFlacStaging {
    /** A stable folder name for [identity]: the ids, names and format that make two requests the same download. */
    fun key(identity: List<String>): String {
        val text = identity.joinToString("\u0001") { it.trim().lowercase(Locale.ROOT) }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }.take(32)
    }

    fun isResumeFile(file: File) = file.isFile && file.name.contains(".partial")

    /** The bytes an earlier attempt left to continue from. */
    fun resumableBytes(directory: File): Long =
        directory.listFiles()?.filter { isResumeFile(it) && it.name.endsWith(".partial") }?.sumOf { it.length() } ?: 0L

    /** At the start of an attempt: leaves in [directory] only what this attempt can resume from. */
    fun keepResumableIn(directory: File) {
        directory.listFiles()?.forEach { child ->
            if (!isResumeFile(child)) runCatching { child.deleteRecursively() }
        }
    }

    /** After a recoverable failure: the same, and the folder goes too when nothing is left to resume. */
    fun keepResumable(directory: File) {
        keepResumableIn(directory)
        if (directory.listFiles().isNullOrEmpty()) directory.delete()
    }

    /** After a success: nothing is left to resume. */
    fun dropResumable(directory: File) {
        directory.listFiles()?.forEach { child -> if (isResumeFile(child)) child.delete() }
    }

    /**
     * Keeps the staging area within bounds: folders nobody touched for
     * [olderThanMs] go, and if what is left is still more than [budgetBytes],
     * the least recently touched go until it fits. [keep] (folders in use)
     * are never removed.
     */
    fun sweep(root: File, olderThanMs: Long, now: Long, keep: Set<String> = emptySet(), budgetBytes: Long = Long.MAX_VALUE) {
        class Folder(val file: File, val touched: Long, val bytes: Long)
        val folders = root.listFiles()?.filter { it.name !in keep }?.map { child ->
            val files = child.walkTopDown().filter { it.isFile }.toList()
            Folder(child, maxOf(child.lastModified(), files.maxOfOrNull { it.lastModified() } ?: 0L), files.sumOf { it.length() })
        } ?: return
        val (old, recent) = folders.partition { now - it.touched >= olderThanMs }
        old.forEach { runCatching { it.file.deleteRecursively() } }
        var total = recent.sumOf { it.bytes }
        for (folder in recent.sortedBy { it.touched }) {
            if (total <= budgetBytes) break
            runCatching { folder.file.deleteRecursively() }
            total -= folder.bytes
        }
    }
}

/**
 * Who holds which staging folder.
 *
 * A job's folder is its own from [open] until the job is closed AND the
 * native call it started has returned. A cancelled call can still be writing
 * for a moment; until it is done nobody else gets that folder (a new job for
 * the same track is given one of its own), and the folder is tidied only
 * then. So a result that arrives late lands in a folder no other job reads,
 * and is removed with it.
 */
internal class StagingLeases(private val root: File) {
    /** What to do with a job's folder once nothing can write to it any more. */
    enum class Disposition {
        /** The download finished: the output stays for the caller to import; partial files go. */
        KEEP_OUTPUT,
        /** It can be continued later: only the partial transfer stays. */
        KEEP_RESUMABLE,
        /** Cancelled by hand or not resumable: everything goes. */
        DELETE,
    }

    inner class Lease internal constructor(val jobId: String, val folder: File) {
        private var nativeCalls = 0
        private var closedWith: Disposition? = null
        private var tidied = false

        /** A native call that may write into the folder starts. */
        fun nativeStarted() = synchronized(this@StagingLeases) { nativeCalls++ }

        /** That call returned (however late). */
        fun nativeFinished() {
            val pending = synchronized(this@StagingLeases) {
                nativeCalls = (nativeCalls - 1).coerceAtLeast(0)
                takeTidy()
            }
            pending?.let(::tidy)
        }

        /** The job is over; the folder is tidied now, or as soon as the native call lets go. */
        fun close(disposition: Disposition) {
            val pending = synchronized(this@StagingLeases) {
                if (closedWith == null) closedWith = disposition
                takeTidy()
            }
            pending?.let(::tidy)
        }

        /** True while a native call may still write here. */
        val writing: Boolean get() = synchronized(this@StagingLeases) { nativeCalls > 0 }

        private fun takeTidy(): Disposition? {
            val disposition = closedWith ?: return null
            if (nativeCalls > 0 || tidied) return null
            tidied = true
            held.remove(folder.name)
            return disposition
        }

        private fun tidy(disposition: Disposition) {
            runCatching {
                when (disposition) {
                    Disposition.KEEP_OUTPUT -> SpotiFlacStaging.dropResumable(folder)
                    Disposition.KEEP_RESUMABLE -> SpotiFlacStaging.keepResumable(folder)
                    Disposition.DELETE -> folder.deleteRecursively()
                }
            }
        }
    }

    private val held = HashMap<String, Lease>()

    /** The folders in use right now. */
    val heldNames: Set<String> get() = synchronized(this) { held.keys.toSet() }

    /**
     * A folder for [jobId]: the track's own ([key]) when nobody holds it, so a
     * partial transfer can be continued; otherwise one only this job has.
     */
    fun open(key: String, jobId: String): Lease = synchronized(this) {
        val name = if (key in held) "$key-${jobId.takeLast(8)}" else key
        val folder = File(root, name)
        check(folder.mkdirs() || folder.isDirectory) { "Could not create the download's staging folder." }
        SpotiFlacStaging.keepResumableIn(folder)
        Lease(jobId, folder).also { held[name] = it }
    }
}
