package com.harmony.domain.swipe.engine

import com.harmony.domain.swipe.model.SwipeAction
import com.harmony.domain.swipe.model.SwipeCandidate
import com.harmony.domain.swipe.model.SwipeConfig
import com.harmony.domain.swipe.model.SwipeProfile
import com.harmony.domain.swipe.model.SwipeSession
import kotlin.random.Random

/**
 * Drives one swipe-to-playlist session.
 *
 * Pure and synchronous: every function takes a [SwipeSession] and returns the
 * next one. No coroutines, no flows, no Android. The ViewModel owns the
 * StateFlow and the audio player; this owns the rules. That split is what
 * lets the whole policy — epsilon split, EMA decay, the 50-like boundary,
 * the exhausted fallback — be tested without a device.
 */
class SwipeSessionEngine(
    // Not @Inject: the catalogue is a snapshot of the library taken when a
    // session opens, so it cannot come from the object graph. Dagger also
    // cannot see Kotlin default arguments — it would demand bindings for
    // SwipeConfig and Random that nothing provides. The ViewModel builds this
    // by hand, which is correct for something this session-scoped.
    private val catalog: SwipeCatalog,
    private val config: SwipeConfig = SwipeConfig(),
    random: Random = Random.Default,
) {
    private val selector = SwipeSelector(catalog, config, random)

    val targetLikes: Int get() = config.targetLikes

    /** Opens a session and fills the stack. */
    fun start(): SwipeSession = refill(SwipeSession())

    /**
     * Records a decision on the top card and advances.
     *
     * A LIKE moves the preference vector before the next card is dealt, so
     * the very next recommendation already reflects it — that immediacy is
     * the whole feel of the interaction. A DISLIKE deliberately does NOT move
     * the vector: pushing it away from a rejected song sounds symmetric but
     * behaves badly, because "not this one" carries far less information than
     * "yes, this one", and a run of lefts would send the vector somewhere no
     * song lives. Dislikes are recorded purely as exclusions.
     */
    fun registerSwipe(session: SwipeSession, action: SwipeAction): SwipeSession {
        // Guard rather than throw: a fast double-swipe can deliver two
        // gestures for one card, and losing the second is correct.
        if (session.isComplete) return session
        val card = session.current ?: return session

        val profile = when (action) {
            SwipeAction.LIKE -> session.profile.copy(
                preferenceVector = SwipeVectorMath.updateUserVector(
                    current = session.profile.preferenceVector,
                    likedVector = card.vector,
                    alpha = config.learningRate,
                ),
                likedSongIds = session.profile.likedSongIds + card.songId,
            )

            SwipeAction.DISLIKE -> session.profile.copy(
                dislikedSongIds = session.profile.dislikedSongIds + card.songId,
            )
        }

        if (profile.likeCount >= config.targetLikes) {
            return session.copy(profile = profile, current = null, upcoming = emptyList(), isComplete = true)
        }

        // Promote from the pre-dealt stack, then top it back up. Promoting
        // first keeps the interaction instant: the next card is already in
        // hand, and refilling only has to replace the one at the bottom.
        val promoted = session.upcoming.firstOrNull()
        return refill(
            session.copy(
                profile = profile,
                current = promoted,
                upcoming = session.upcoming.drop(1),
            )
        )
    }

    /**
     * Puts the top card back and undoes its effect.
     *
     * Only a DISLIKE is undoable. Reversing a LIKE would mean reversing an
     * EMA step, and that is not recoverable — the blend is lossy, so the
     * previous vector cannot be reconstructed from the current one without
     * keeping a full history. Rather than store that history for a rarely
     * used button, the UI simply does not offer undo on a like.
     */
    fun undoDislike(session: SwipeSession, songId: Long): SwipeSession {
        if (songId !in session.profile.dislikedSongIds) return session
        val restored = catalog.vectorOf(songId) ?: return session
        val card = SwipeCandidate(
            songId = songId,
            albumId = catalog.albumOf(songId),
            vector = restored,
            source = session.current?.source ?: com.harmony.domain.swipe.model.CandidateSource.SEED,
            similarityToProfile = session.profile.preferenceVector
                ?.let { SwipeVectorMath.cosineSimilarity(it, restored) }
                ?: 0f,
        )
        return session.copy(
            profile = session.profile.copy(
                dislikedSongIds = session.profile.dislikedSongIds - songId,
            ),
            current = card,
            upcoming = listOfNotNull(session.current) + session.upcoming,
            exhausted = false,
        )
    }

    /**
     * Tops the stack back up to [SwipeConfig.deckDepth] cards below the top.
     *
     * Sets [SwipeSession.exhausted] when no card can be dealt at all and the
     * top slot is empty. That is a real end state for a small library, not a
     * failure — the caller offers export of whatever was collected.
     */
    private fun refill(session: SwipeSession): SwipeSession {
        var current = session.current
        val upcoming = session.upcoming.toMutableList()

        // Everything off-limits: decided songs plus whatever is already dealt.
        val excluded = session.profile.decidedSongIds.toMutableSet()
        current?.let { excluded += it.songId }
        upcoming.forEach { excluded += it.songId }

        if (current == null) {
            current = selector.next(session.profile, excluded)
            current?.let { excluded += it.songId }
        }

        while (upcoming.size < config.deckDepth) {
            val next = selector.next(session.profile, excluded) ?: break
            excluded += next.songId
            upcoming += next
        }

        return session.copy(
            current = current,
            upcoming = upcoming,
            exhausted = current == null,
        )
    }
}

/** Progress for the counter, kept out of the UI so the arithmetic is testable. */
data class SwipeProgress(val liked: Int, val target: Int) {
    val fraction: Float get() = if (target == 0) 0f else (liked.toFloat() / target).coerceIn(0f, 1f)
    val remaining: Int get() = (target - liked).coerceAtLeast(0)
}

fun SwipeProfile.progressTowards(target: Int) = SwipeProgress(likeCount, target)
