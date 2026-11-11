package com.harmony.domain.online

import com.harmony.core.model.MixSource

/**
 * One running mix, as the queue manager started it. Results of a request
 * made for another key (an earlier mix, a mix the user stopped, a new list)
 * are never applied.
 */
data class MixSessionKey(val seedSongId: Long, val startedAtMillis: Long, val generation: Long)

/**
 * Where one recommendation stands. The stages keep four facts apart:
 * the catalog listing it as a song (every candidate), a provider confirming
 * it ([Availability]), a file being there ([CandidateStage.READY]) and that
 * file being checked as real lossless audio of the right length
 * ([PreparedAudio]).
 */
enum class CandidateStage {
    /** Listed by the catalog as a song and accepted by the planner. Nothing else checked yet. */
    FOUND,

    /** The library has the same recording: it plays from there, nothing is downloaded. */
    IN_LIBRARY,

    /** Being looked up and downloaded through Mornye. */
    PREPARING,

    /** Downloaded and checked: ready for the queue. */
    READY,

    /** Put in the queue. */
    QUEUED,

    /** A provider looked and has no lossless copy of this recording. */
    NOT_AVAILABLE,

    /** No provider could look (sign-in, verification, none installed): not known, not absent. */
    NOT_VERIFIED,

    /** A temporary failure (network, a busy engine, a rate limit): tried again later. */
    RETRY_LATER,

    /** A file came, but not lossless audio of this recording (codec, length). Deleted. */
    REJECTED_AUDIO,

    /** Held back by a setting or a limit: mobile data, free space, the cache. */
    HELD,

    /** Dropped for this session: the listener skipped its artist. */
    DROPPED,
}

/** What a provider has said about having the recording. */
enum class Availability {
    /** Nothing confirmed either way (Mornye 5.3.1 checks only as part of a download). */
    UNKNOWN,

    /** A provider delivered it: confirmed by the download itself. */
    CONFIRMED,

    /** A provider looked and said it does not have it. */
    ABSENT,
}

/** A prepared file whose audio was read and checked, not just named. */
data class PreparedAudio(
    val path: String,
    /** The codec read from the file's own header ("FLAC"), never from its name or the provider's label. */
    val codec: String,
    val bitDepth: Int?,
    val sampleRateHz: Int?,
    val durationMs: Long,
    val sizeBytes: Long,
    val provider: String?,
)

data class OnlineCandidate(
    val track: CatalogTrack,
    val identityKey: String,
    val score: Int,
    val reasons: List<String>,
    val stage: CandidateStage = CandidateStage.FOUND,
    val availability: Availability = Availability.UNKNOWN,
    /** A short, user-facing note on the stage ("needs Tidal sign-in", "retry in 30 s"). No titles in logs. */
    val detail: String? = null,
    val localSongId: Long? = null,
    val audio: PreparedAudio? = null,
    /** Progress of a preparation, 0..1, when the engine reports it. */
    val progress: Float? = null,
    /** When a [CandidateStage.RETRY_LATER] or [CandidateStage.HELD] candidate may be tried again. */
    val retryAtMillis: Long? = null,
    val attempts: Int = 0,
) {
    val videoId: String get() = track.videoId

    /** Playable now: the library's copy, or a checked file. */
    val ready: Boolean get() = stage == CandidateStage.IN_LIBRARY || stage == CandidateStage.READY
}

/** A recommendation the queue can take now. */
sealed interface ReadyPick {
    val candidate: OnlineCandidate

    /** The library has this recording: play the library's song [songId]. */
    data class Local(override val candidate: OnlineCandidate, val songId: Long) : ReadyPick

    /** A prepared file in the online cache. */
    data class Prepared(override val candidate: OnlineCandidate, val audio: PreparedAudio) : ReadyPick
}

/** Getting a recommendation ready through the configured download engine. */
fun interface CandidatePreparer {
    suspend fun prepare(track: CatalogTrack, onProgress: (Float?) -> Unit): PrepareOutcome
}

sealed interface PrepareOutcome {
    data class Ready(val audio: PreparedAudio) : PrepareOutcome

    /** A provider looked and has no lossless copy. */
    data class NotAvailable(val reason: String) : PrepareOutcome

    /** No provider could look: sign-in, verification, nothing installed. */
    data class NotVerified(val reason: String) : PrepareOutcome

    /** Try again after [retryAfterMs] (null: the default backoff). */
    data class TemporaryError(val reason: String, val retryAfterMs: Long? = null) : PrepareOutcome

    /** A file came but it is not lossless audio of this recording; it was deleted. */
    data class RejectedAudio(val reason: String) : PrepareOutcome

    /** A rule says not now (mobile data, free space, cache limit). */
    data class Held(val reason: String, val retryAfterMs: Long? = null) : PrepareOutcome
}

/** The library's song for the same recording as [track], when it has one that plays. */
fun interface LocalCopies {
    suspend fun find(track: CatalogTrack): Long?

    companion object {
        val NONE = LocalCopies { null }
    }
}

/** How the seed stands with the catalog. */
sealed interface SeedStatus {
    data object Idle : SeedStatus
    data object Identifying : SeedStatus
    data class Identified(val videoId: String, val score: Int) : SeedStatus

    /** The catalog was asked and no recording was confirmed ([verdict] says why). */
    data class NotIdentified(val verdict: MatchVerdict, val resultsSeen: Int, val songsSeen: Int) : SeedStatus

    /** The catalog could not be asked or did not answer usefully. */
    data class Failed(val failure: CatalogFailure) : SeedStatus
}

data class OnlineMixState(
    val session: MixSessionKey? = null,
    val source: MixSource = MixSource.OFFLINE,
    val seed: SeedStatus = SeedStatus.Idle,
    val candidates: List<OnlineCandidate> = emptyList(),
    /** Radio tracks turned away by the planner in the last batch. */
    val turnedAway: Int = 0,
)
