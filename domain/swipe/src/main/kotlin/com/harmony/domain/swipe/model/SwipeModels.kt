package com.harmony.domain.swipe.model

/** Right or left. Nothing else is a swipe. */
enum class SwipeAction { LIKE, DISLIKE }

/**
 * Why a card was dealt.
 *
 * Surfaced in the UI (a small badge) and asserted in tests, because an
 * exploration/exploitation split you cannot observe is a split you cannot
 * tune. Without this, a broken epsilon would look identical to a working one.
 */
enum class CandidateSource {
    /** Nearest neighbours of the current preference vector. */
    EXPLOIT,

    /** Acoustically compatible but off the beaten path — see SwipeSelector. */
    EXPLORE,

    /** Dealt before the first LIKE exists, when there is no vector to steer by. */
    SEED,
}

/**
 * One card in the deck.
 *
 * [vector] is carried on the candidate rather than looked up again at swipe
 * time so the profile update cannot silently use a different vector than the
 * one the recommendation was scored against.
 */
data class SwipeCandidate(
    val songId: Long,
    val albumId: Long,
    /** L2-normalized, same space as FlatEmbeddingIndex. */
    val vector: FloatArray,
    val source: CandidateSource,
    /** Cosine similarity to the profile when dealt; 0 for SEED cards. */
    val similarityToProfile: Float,
) {
    override fun equals(other: Any?) = other is SwipeCandidate && other.songId == songId
    override fun hashCode() = songId.hashCode()
}

/**
 * The listener's taste, as accumulated this session.
 *
 * Deliberately in-memory and per-session: the point of the deck is to catch
 * what someone wants *right now*, and a persisted vector would drag last
 * month's mood into tonight's playlist. Smart Shuffle already owns the
 * long-lived taste model.
 */
data class SwipeProfile(
    /**
     * L2-normalized preference vector, or null until the first LIKE.
     *
     * Null rather than a zero vector on purpose: cosine similarity against a
     * zero vector is undefined (0/0), and seeding with a neutral "average of
     * everything" vector would bias the first real recommendations toward
     * whatever the library's centroid happens to be.
     */
    val preferenceVector: FloatArray? = null,
    /** Ordered — this is the playlist running order. */
    val likedSongIds: List<Long> = emptyList(),
    val dislikedSongIds: Set<Long> = emptySet(),
) {
    val likeCount: Int get() = likedSongIds.size

    /** Every song already decided on, in the shape the index wants for exclusion. */
    val decidedSongIds: Set<Long> get() = likedSongIds.toSet() + dislikedSongIds
}

/** Immutable snapshot the UI renders and the engine advances. */
data class SwipeSession(
    val profile: SwipeProfile = SwipeProfile(),
    /** Top card. Null only when [exhausted] or [isComplete]. */
    val current: SwipeCandidate? = null,
    /** Pre-dealt cards below the top one, so the stack renders with depth. */
    val upcoming: List<SwipeCandidate> = emptyList(),
    /** Target reached; the deck is closed and export is offered. */
    val isComplete: Boolean = false,
    /**
     * The library ran out of undecided, analyzed songs before the target.
     *
     * A real end state, not an error: a 300-song library genuinely cannot
     * fill a 50-track playlist without repeats. The UI offers export of
     * whatever was collected.
     */
    val exhausted: Boolean = false,
)

/**
 * Tuning knobs.
 *
 * @param targetLikes how many LIKEs close the deck.
 * @param explorationRate epsilon in the epsilon-greedy sense: the share of
 *   cards drawn from the long tail rather than the nearest neighbours.
 * @param learningRate alpha for the EMA profile update. Below ~0.1 the vector
 *   barely moves within 50 swipes; above ~0.35 it chases the last song and
 *   the deck thrashes between styles.
 * @param deckDepth how many cards to keep pre-dealt below the top one.
 * @param exploitPoolSize neighbours fetched per exploit draw. Larger than 1 so
 *   the deck is not deterministic — the same profile twice should not always
 *   produce the same card.
 * @param explorePoolSize neighbours fetched per explore draw. Wide, because
 *   exploration picks from the FAR end of this list.
 * @param exploreFloor minimum cosine similarity for an exploration pick.
 *   Exploration means "something else you would plausibly like", not noise.
 */
data class SwipeConfig(
    val targetLikes: Int = 50,
    val explorationRate: Float = 0.40f,
    val learningRate: Float = 0.20f,
    val deckDepth: Int = 3,
    val exploitPoolSize: Int = 12,
    val explorePoolSize: Int = 200,
    val exploreFloor: Float = 0.35f,
) {
    init {
        require(targetLikes > 0) { "targetLikes must be positive" }
        require(explorationRate in 0f..1f) { "explorationRate must be a probability" }
        require(learningRate > 0f && learningRate <= 1f) { "learningRate must be in (0, 1]" }
        require(deckDepth >= 0) { "deckDepth cannot be negative" }
    }
}
