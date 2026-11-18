package com.harmony.domain.online

/**
 * What the online side of a running mix is doing for the music, in one
 * answer: what the queue sheet says in its single status line, and what
 * the queue manager reads to decide whether an Online mix may let the
 * library fill in ([OnlineMixState.canDeliver]). One rule for both, so what
 * is said and what is done never disagree.
 */
sealed interface OnlineProgress {
    /** The song playing is being looked up on YouTube Music. */
    data object Finding : OnlineProgress

    /** [track] is being downloaded; [progress] 0..1 when known. */
    data class Preparing(val track: CatalogTrack, val progress: Float?) : OnlineProgress

    /** [track] is ready and joins the queue as soon as there is room. */
    data class Ready(val track: CatalogTrack) : OnlineProgress

    /** More recommendations are on their way (waiting their turn, or to be tried again soon). */
    data object Working : OnlineProgress

    /** Nothing online can come for now, and why: the library fills in. */
    data class Stopped(val reason: StopReason, val detail: String? = null) : OnlineProgress
}

enum class StopReason {
    /** The song playing isn't on YouTube Music (or not surely: several or no close matches). */
    NOT_FOUND,

    /** YouTube Music could not be reached. */
    NO_CONNECTION,

    /** YouTube Music answered, but not usefully (an error, a changed answer). */
    CATALOG_ERROR,

    /** A download service asks to sign in or verify (Downloads). */
    NEEDS_SIGN_IN,

    /** Downloads wait for Wi-Fi (mobile data is not allowed for online mixes). */
    WAITING_FOR_WIFI,

    /** Downloads are held for another reason (no space, the online cache is full): see the detail. */
    HELD,

    /** Every recommendation was turned down: no lossless copy, the wrong recording. */
    NONE_LEFT,
}

/** What the online side is doing now (see [OnlineProgress]). */
fun OnlineMixState.progress(): OnlineProgress = when (val s = seed) {
    SeedStatus.Idle, SeedStatus.Identifying -> OnlineProgress.Finding
    is SeedStatus.NotIdentified -> OnlineProgress.Stopped(StopReason.NOT_FOUND, s.verdict.name)
    is SeedStatus.Failed -> when (s.failure) {
        CatalogFailure.Offline, CatalogFailure.Timeout -> OnlineProgress.Stopped(StopReason.NO_CONNECTION)
        else -> OnlineProgress.Stopped(StopReason.CATALOG_ERROR, s.failure.javaClass.simpleName)
    }
    is SeedStatus.Identified -> {
        val preparing = candidates.firstOrNull { it.stage == CandidateStage.PREPARING }
        val ready = candidates.firstOrNull { it.ready }
        when {
            preparing != null -> OnlineProgress.Preparing(preparing.track, preparing.progress)
            ready != null -> OnlineProgress.Ready(ready.track)
            candidates.isEmpty() || candidates.any {
                it.stage == CandidateStage.FOUND || (it.stage == CandidateStage.RETRY_LATER && it.retryAtMillis != null)
            } -> OnlineProgress.Working
            candidates.any { it.stage == CandidateStage.NOT_VERIFIED } -> OnlineProgress.Stopped(StopReason.NEEDS_SIGN_IN)
            candidates.any { it.stage == CandidateStage.HELD } -> {
                val detail = candidates.first { it.stage == CandidateStage.HELD }.detail
                if (detail.orEmpty().contains("wi-fi", ignoreCase = true)) OnlineProgress.Stopped(StopReason.WAITING_FOR_WIFI)
                else OnlineProgress.Stopped(StopReason.HELD, detail)
            }
            else -> OnlineProgress.Stopped(StopReason.NONE_LEFT)
        }
    }
}

/** Online songs can still come: an Online mix waits for them instead of letting the library fill in. */
fun OnlineMixState.canDeliver(): Boolean = progress() !is OnlineProgress.Stopped
