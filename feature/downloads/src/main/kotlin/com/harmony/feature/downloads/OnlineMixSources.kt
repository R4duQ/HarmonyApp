package com.harmony.feature.downloads

import com.harmony.domain.library.discovery.TrackIdentity
import com.harmony.domain.online.CatalogTrack
import com.harmony.domain.online.PrepareOutcome
import java.io.File
import kotlin.math.abs

/** Which provider result is the same recording as a catalog track, and what an engine failure means. */
internal object OnlineSourceMatch {
    const val LENGTH_TOLERANCE_MS = 3_000L

    /**
     * The provider result that is [track]'s recording: same lead artist,
     * base title and versions, and a length within [LENGTH_TOLERANCE_MS]
     * when both are known. An ISRC and a higher search score break ties.
     */
    fun pick(track: CatalogTrack, listed: List<SpotiFlacSearchCandidate>): SpotiFlacSearchCandidate? {
        val want = TrackIdentity.of(track.title, track.artists.firstOrNull().orEmpty())
        if (want.artist.isBlank() || want.title.isBlank()) return null
        return listed.filter { c ->
            val have = TrackIdentity.of(c.title, c.artist)
            have.artist == want.artist && have.title == want.title && have.versions == want.versions &&
                lengthAgrees(c.durationMs.takeIf { it > 0 }, track.durationMs)
        }.sortedWith(compareByDescending<SpotiFlacSearchCandidate> { it.isrc != null }.thenByDescending { it.matchScore })
            .firstOrNull()
    }

    fun lengthAgrees(actual: Long?, expected: Long?): Boolean =
        actual == null || expected == null || actual <= 0 || expected <= 0 || abs(actual - expected) <= LENGTH_TOLERANCE_MS

    fun outcomeOf(e: SpotiFlacException): PrepareOutcome = when (SpotiFlacFailures.kind(e.errorType, e.message)) {
        SpotiFlacFailureKind.VERIFICATION -> PrepareOutcome.NotVerified("${e.provider ?: "the provider"} needs sign-in or verification")
        SpotiFlacFailureKind.NOT_FOUND -> PrepareOutcome.NotAvailable("no lossless copy at the providers")
        SpotiFlacFailureKind.RATE_LIMITED -> PrepareOutcome.TemporaryError("rate limited", 120_000)
        SpotiFlacFailureKind.TIMEOUT, SpotiFlacFailureKind.NO_NETWORK -> PrepareOutcome.TemporaryError("network")
        SpotiFlacFailureKind.INTEGRITY -> PrepareOutcome.RejectedAudio("the file failed its integrity check")
        SpotiFlacFailureKind.LOCAL -> PrepareOutcome.Held("storage on the phone")
        else -> PrepareOutcome.TemporaryError("download failed")
    }
}

/**
 * The online cache: prepared files under their video id, at most
 * [LIMIT_BYTES]. Room is made by deleting the least recently used files,
 * never one the queue holds.
 */
internal class OnlineCache(private val dir: File) {
    fun makeRoom(incoming: Long, inUse: Set<String>): Boolean {
        require(incoming >= 0)
        if (incoming > LIMIT_BYTES) return false
        dir.mkdirs()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".flac") }?.sortedBy { it.lastModified() } ?: return false
        var used = files.sumOf { it.length() }
        for (f in files) {
            if (used <= LIMIT_BYTES - incoming) break
            if (f.nameWithoutExtension in inUse) continue
            val size = f.length()
            if (f.delete()) used -= size
        }
        return used <= LIMIT_BYTES - incoming
    }

    /** Admission and publication share the lock across replaced/cancelled mix sessions. */
    @Synchronized
    fun store(staged: File, target: File, inUse: Set<String>): Boolean {
        if (!makeRoom(staged.length(), inUse)) return false
        place(staged, target)
        return true
    }

    /** Moves [staged] to [target] (a copy when they are on different file systems). */
    fun place(staged: File, target: File) {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        if (!staged.renameTo(part)) {
            staged.copyTo(part, overwrite = true)
        }
        if (!part.renameTo(target)) {
            target.delete()
            check(part.renameTo(target)) { "could not place the prepared file" }
        }
    }

    companion object {
        const val LIMIT_BYTES = 1L * 1024 * 1024 * 1024
    }
}
