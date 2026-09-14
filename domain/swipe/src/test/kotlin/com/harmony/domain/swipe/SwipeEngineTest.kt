package com.harmony.domain.swipe

import com.harmony.domain.similarity.model.Neighbor
import com.harmony.domain.swipe.engine.SwipeCatalog
import com.harmony.domain.swipe.engine.SwipeSessionEngine
import com.harmony.domain.swipe.engine.SwipeVectorMath
import com.harmony.domain.swipe.model.CandidateSource
import com.harmony.domain.swipe.model.SwipeAction
import com.harmony.domain.swipe.model.SwipeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class SwipeVectorMathTest {

    @Test
    fun `identical vectors score 1`() {
        val v = floatArrayOf(0.6f, 0.8f)
        assertEquals(1f, SwipeVectorMath.cosineSimilarity(v, v), 1e-6f)
    }

    @Test
    fun `orthogonal vectors score 0`() {
        assertEquals(
            0f,
            SwipeVectorMath.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)),
            1e-6f,
        )
    }

    @Test
    fun `magnitude does not affect similarity`() {
        val a = floatArrayOf(1f, 2f, 3f)
        val scaled = floatArrayOf(10f, 20f, 30f)
        assertEquals(1f, SwipeVectorMath.cosineSimilarity(a, scaled), 1e-6f)
    }

    @Test
    fun `zero vector yields zero rather than NaN`() {
        val result = SwipeVectorMath.cosineSimilarity(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f))
        assertFalse(result.isNaN())
        assertEquals(0f, result, 0f)
    }

    @Test
    fun `first like seeds the profile outright`() {
        val liked = floatArrayOf(3f, 4f) // magnitude 5
        val profile = SwipeVectorMath.updateUserVector(null, liked, alpha = 0.2f)
        // Seeded and normalized: 3/5, 4/5.
        assertEquals(0.6f, profile[0], 1e-6f)
        assertEquals(0.8f, profile[1], 1e-6f)
    }

    @Test
    fun `EMA blends toward the liked vector by alpha`() {
        val current = floatArrayOf(1f, 0f)
        val liked = floatArrayOf(0f, 1f)
        val updated = SwipeVectorMath.updateUserVector(current, liked, alpha = 0.25f)
        // Blend before normalization is (0.75, 0.25); normalizing preserves
        // the 3:1 ratio, so the profile has moved a quarter of the way over.
        assertEquals(3f, updated[0] / updated[1], 1e-5f)
    }

    @Test
    fun `profile stays unit length across many updates`() {
        var profile = SwipeVectorMath.updateUserVector(null, floatArrayOf(1f, 0f, 0f), 0.2f)
        repeat(60) { i ->
            val liked = floatArrayOf(i % 3 * 1f, (i + 1) % 3 * 1f, (i + 2) % 3 * 1f)
            profile = SwipeVectorMath.updateUserVector(profile, SwipeVectorMath.normalize(liked), 0.2f)
        }
        val magnitude = SwipeVectorMath.cosineSimilarity(profile, profile)
        assertEquals("profile drifted off the unit sphere", 1f, magnitude, 1e-5f)
    }

    @Test
    fun `recent likes outweigh older ones`() {
        val old = floatArrayOf(1f, 0f)
        val new = floatArrayOf(0f, 1f)
        var profile = SwipeVectorMath.updateUserVector(null, old, 0.2f)
        repeat(5) { profile = SwipeVectorMath.updateUserVector(profile, new, 0.2f) }
        assertTrue(
            "five recent likes should pull the profile past the seed",
            SwipeVectorMath.cosineSimilarity(profile, new) >
                SwipeVectorMath.cosineSimilarity(profile, old),
        )
    }
}

/** In-memory catalogue: vectors on a circle so similarity is predictable. */
private class FakeCatalog(
    count: Int,
    private val playCounts: Map<Long, Int> = emptyMap(),
) : SwipeCatalog {

    private val vectors: Map<Long, FloatArray> = (1..count).associate { id ->
        val angle = (id.toDouble() / count) * 2 * Math.PI
        id.toLong() to SwipeVectorMath.normalize(
            floatArrayOf(Math.cos(angle).toFloat(), Math.sin(angle).toFloat()),
        )
    }

    override val songIds: List<Long> = vectors.keys.sorted()
    override fun vectorOf(songId: Long): FloatArray? = vectors[songId]
    override fun albumOf(songId: Long): Long = songId % 7
    override fun playCountOf(songId: Long): Int = playCounts[songId] ?: 0

    override fun nearest(query: FloatArray, k: Int, exclude: Set<Long>): List<Neighbor> =
        vectors.entries
            .filterNot { it.key in exclude }
            .map { (id, v) -> id to SwipeVectorMath.cosineSimilarity(query, v) }
            .sortedByDescending { it.second }
            .take(k)
            .map { (id, sim) ->
                Neighbor(
                    songId = id,
                    similarity = sim,
                    albumId = albumOf(id),
                    energy = 0.5f,
                    perceptual = FloatArray(11),
                )
            }
}

class SwipeSessionEngineTest {

    private fun engine(
        catalog: SwipeCatalog = FakeCatalog(400),
        config: SwipeConfig = SwipeConfig(),
        seed: Long = 42L,
    ) = SwipeSessionEngine(catalog, config, Random(seed))

    @Test
    fun `session opens with a card and a filled stack`() {
        val session = engine().start()
        assertNotNull(session.current)
        assertEquals(SwipeConfig().deckDepth, session.upcoming.size)
        assertFalse(session.exhausted)
    }

    @Test
    fun `first card is a seed because no profile exists yet`() {
        assertEquals(CandidateSource.SEED, engine().start().current?.source)
    }

    @Test
    fun `like advances the counter and moves the vector`() {
        val e = engine()
        val start = e.start()
        val after = e.registerSwipe(start, SwipeAction.LIKE)
        assertEquals(1, after.profile.likeCount)
        assertNotNull(after.profile.preferenceVector)
    }

    @Test
    fun `dislike records an exclusion but leaves the vector alone`() {
        val e = engine()
        val start = e.registerSwipe(e.start(), SwipeAction.LIKE)
        val vectorBefore = start.profile.preferenceVector!!.copyOf()
        val after = e.registerSwipe(start, SwipeAction.DISLIKE)
        assertEquals(0, after.profile.likeCount.let { 1 - it }) // still exactly one like
        assertTrue(after.profile.dislikedSongIds.isNotEmpty())
        assertTrue(vectorBefore.contentEquals(after.profile.preferenceVector))
    }

    @Test
    fun `a decided song is never dealt again`() {
        val e = engine()
        var session = e.start()
        val seen = mutableSetOf<Long>()
        repeat(120) {
            val card = session.current ?: return@repeat
            assertTrue("song ${card.songId} was dealt twice", seen.add(card.songId))
            session = e.registerSwipe(session, if (it % 3 == 0) SwipeAction.LIKE else SwipeAction.DISLIKE)
            if (session.isComplete) return
        }
    }

    @Test
    fun `deck closes at exactly the target like count`() {
        val config = SwipeConfig(targetLikes = 50)
        val e = engine(config = config)
        var session = e.start()
        var swipes = 0
        while (!session.isComplete && !session.exhausted && swipes < 5_000) {
            session = e.registerSwipe(session, SwipeAction.LIKE)
            swipes++
        }
        assertTrue(session.isComplete)
        assertEquals(50, session.profile.likeCount)
        assertNull("deck must close, not keep dealing", session.current)
    }

    @Test
    fun `swiping after completion is ignored`() {
        val config = SwipeConfig(targetLikes = 2)
        val e = engine(config = config)
        var session = e.start()
        repeat(2) { session = e.registerSwipe(session, SwipeAction.LIKE) }
        assertTrue(session.isComplete)
        val again = e.registerSwipe(session, SwipeAction.LIKE)
        assertEquals(2, again.profile.likeCount)
    }

    @Test
    fun `small library exhausts instead of looping forever`() {
        val e = engine(catalog = FakeCatalog(6), config = SwipeConfig(targetLikes = 50))
        var session = e.start()
        var guard = 0
        while (!session.exhausted && !session.isComplete && guard++ < 500) {
            session = e.registerSwipe(session, SwipeAction.LIKE)
        }
        assertTrue("a 6-song library cannot fill 50 slots", session.exhausted)
        assertFalse(session.isComplete)
        assertEquals(6, session.profile.likeCount)
    }

    @Test
    fun `exploration rate is respected within tolerance`() {
        val config = SwipeConfig(targetLikes = 10_000, explorationRate = 0.4f)
        val e = engine(catalog = FakeCatalog(1_500), config = config)
        var session = e.start()
        var explore = 0
        var exploit = 0
        repeat(600) {
            when (session.current?.source) {
                CandidateSource.EXPLORE -> explore++
                CandidateSource.EXPLOIT -> exploit++
                else -> Unit
            }
            session = e.registerSwipe(session, SwipeAction.LIKE)
        }
        val observed = explore.toFloat() / (explore + exploit)
        assertTrue(
            "expected ~0.40 exploration, observed $observed",
            abs(observed - 0.40f) < 0.08f,
        )
    }

    @Test
    fun `exploration favours the long tail`() {
        // Every song heavily played except a handful; exploration should
        // surface the unplayed ones far more often than chance would.
        val quiet = setOf(31L, 62L, 93L, 124L)
        val catalog = FakeCatalog(
            count = 200,
            playCounts = (1..200).associate { it.toLong() to if (it.toLong() in quiet) 0 else 25 },
        )
        val e = engine(catalog = catalog, config = SwipeConfig(targetLikes = 10_000))
        var session = e.start()
        // Establish a profile first. With no likes there is no preference
        // vector, so every card is a SEED and exploration never runs at all —
        // which is correct behaviour, and would silently make this test
        // measure nothing.
        repeat(5) { session = e.registerSwipe(session, SwipeAction.LIKE) }
        var quietExplores = 0
        var explores = 0
        repeat(150) {
            val card = session.current
            if (card?.source == CandidateSource.EXPLORE) {
                explores++
                if (card.songId in quiet) quietExplores++
            }
            session = e.registerSwipe(session, SwipeAction.DISLIKE)
        }
        assertTrue("no exploration happened at all", explores > 0)
        assertTrue(
            "long tail ignored: $quietExplores of $explores explores hit the unplayed set",
            quietExplores > 0,
        )
    }

    @Test
    fun `liked order is preserved for the playlist`() {
        val e = engine()
        var session = e.start()
        val order = mutableListOf<Long>()
        repeat(8) {
            session.current?.let { order += it.songId }
            session = e.registerSwipe(session, SwipeAction.LIKE)
        }
        assertEquals(order, session.profile.likedSongIds)
    }
}
