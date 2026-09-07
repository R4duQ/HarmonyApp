package com.harmony.domain.shuffle.engine

import com.harmony.core.model.Song
import com.harmony.domain.library.repository.FavoritesRepository
import com.harmony.domain.library.repository.LibraryRepository
import com.harmony.domain.library.repository.PlaybackBehaviorStats
import com.harmony.domain.library.repository.PlaybackHistoryRepository
import com.harmony.domain.shuffle.model.ShuffleConfig
import com.harmony.domain.shuffle.model.SmartShuffleStyle
import com.harmony.domain.similarity.model.Neighbor
import com.harmony.domain.similarity.model.SimilarityOptions
import com.harmony.domain.similarity.repository.SimilarityRepository
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Smart Shuffle v2.1.
 *
 * The acoustic-neighbour search is still the foundation, but the final pick
 * also understands listening behaviour:
 *
 *   similarity / mood / energy
 * + completed-play affinity and favourites
 * + discovery (never/rarely/long-ago played)
 * + session flow (energy + tempo + genre continuity)
 * - skip tendency
 * - recent-artist repetition
 *
 * The engine never chooses argmax directly. It scores a pool, keeps the best
 * candidates, then performs weighted random sampling. That preserves the
 * feeling of shuffle while making the randomness intentional.
 *
 * ## What v2.1 changed and why
 *
 * **Signed contributions.** Familiarity, discovery and flow are centred on a
 * neutral 0.5 and contribute in [-1, +1] rather than [0, +1]. In v2.0 every
 * behavioural term could only ever add score, so FAMILIAR and DISCOVER both
 * merely rewarded different songs without demoting the ones they were supposed
 * to avoid — and on BALANCED the two nearly cancelled, leaving recency as the
 * only real signal. Signed terms let a style push both ways, which is what the
 * style names promise.
 *
 * **Evidence shrinkage.** Behavioural rates are shrunk toward neutral when
 * they rest on very few play events. One accidental skip used to produce
 * skipRate = 1.0 and the second-largest penalty in the system, burying a track
 * the listener had barely heard.
 *
 * **Missing metadata is neutral, never a penalty.** Unknown energy and tempo
 * already fell back to 0.5; unknown genre fell to 0.0, i.e. a silent penalty
 * for untagged files.
 *
 * **Recency counted once.** Discovery's staleness ramp starts where the
 * short-term recency penalty has decayed away, so the two no longer stack on
 * the same axis. The penalty is a smooth exponential instead of four step
 * cliffs.
 *
 * **Bounded exploration.** Re-ranking 48 acoustic neighbours can only surface
 * the most-unheard *nearby* track, so DISCOVER was structurally incapable of
 * real discovery. A small style-scaled epsilon takes a uniform library sample
 * instead.
 *
 * All behavioural data is local to Harmony. No network model or account is
 * required.
 *
 * Note on style: nullable properties belonging to other modules' public APIs
 * are read into local values before use. Kotlin will not smart-cast them, and
 * relying on it is what broke the v2.0.0 build.
 */
class SmartShuffleEngine constructor(
    private val similarityRepository: SimilarityRepository,
    private val libraryRepository: LibraryRepository?,
    private val historyRepository: PlaybackHistoryRepository?,
    private val favoritesRepository: FavoritesRepository?,
    private val random: Random,
    /** Injectable so scoring is testable without freezing the machine clock. */
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    @Inject
    constructor(
        similarityRepository: SimilarityRepository,
        libraryRepository: LibraryRepository,
        historyRepository: PlaybackHistoryRepository,
        favoritesRepository: FavoritesRepository,
    ) : this(
        similarityRepository,
        libraryRepository,
        historyRepository,
        favoritesRepository,
        Random.Default,
    )

    /** Lightweight constructors keep pure engine tests independent of data modules. */
    constructor(similarityRepository: SimilarityRepository) :
        this(similarityRepository, null, null, null, Random.Default)

    constructor(similarityRepository: SimilarityRepository, random: Random) :
        this(similarityRepository, null, null, null, random)

    suspend fun pickNext(
        currentSongId: Long,
        config: ShuffleConfig,
        excludeSongIds: Set<Long>,
        /** Most recent first. Order matters: the last artist heard cools down hardest. */
        recentArtists: List<String> = emptyList(),
        sessionGenres: Set<String> = emptySet(),
        /**
         * Rolling centroid of the embeddings of what the session has actually
         * played. Null falls back to anchoring on the current song alone.
         */
        sessionAnchor: FloatArray? = null,
    ): Long? {
        val library = libraryRepository
        val weights = StyleWeights.forStyle(config.style)

        // Bounded exploration. Everything below re-ranks the acoustic
        // neighbourhood, which by construction cannot leave it; this is the
        // only path that can. Kept small, and off entirely on FAMILIAR.
        if (library != null) {
            val explorationChance = weights.exploration * config.discovery
            if (explorationChance > 0f && random.nextFloat() < explorationChance) {
                val wildcard = library.randomSongId(excludeSongIds)
                if (wildcard != null) return wildcard
            }
        }

        val neighbors = findCandidates(
            currentSongId = currentSongId,
            excludeSongIds = excludeSongIds,
            sessionAnchor = sessionAnchor,
            anchorPull = weights.anchorPull,
        )
        if (neighbors.isEmpty()) return null

        // When instantiated by pure tests/Journey there is intentionally no
        // data layer. Keep the original acoustic-only path exactly usable.
        if (library == null) return sample(neighbors, config)

        val ids = neighbors.map { it.songId }
        val songs = library.songsByIds(ids).associateBy(Song::id)
        val stats = historyRepository?.behaviorStats(ids).orEmpty()
        val favorites = favoritesRepository?.favoriteIds(ids).orEmpty()
        val currentSong = library.songById(currentSongId)
        val currentEnergy = similarityRepository.energyOf(currentSongId)
        val currentBpm = similarityRepository.bpmOf(currentSongId)
        val addedAt = library.dateAddedByIds(ids)

        val anchorGenres = buildSet {
            sessionGenres.forEach { addAll(genreTokens(it)) }
            addAll(genreTokens(currentSong?.genre))
        }

        val context = PersonalizationContext(
            songs = songs,
            stats = stats,
            favorites = favorites,
            currentEnergy = currentEnergy,
            currentBpm = currentBpm,
            addedAt = addedAt,
            artistCooldown = artistCooldownMap(recentArtists),
            anchorGenres = anchorGenres,
            nowMillis = clock(),
        )
        return samplePersonalized(neighbors, config, weights, context)
    }

    /**
     * Chooses what the candidate pool is anchored to.
     *
     * v2.0 always searched the neighbours of the song currently playing. That
     * is a random walk: every individual step is "similar to the last one",
     * but similarity is not transitive, so four hops of 0.85 can land a long
     * way from where the session started without any single step looking
     * wrong. That is the drift people describe as Smart Shuffle "jumping
     * genre", and no amount of genre-tag work fixes it, because the tags were
     * never what was steering.
     *
     * So the search vector is a blend of the current song and the session
     * centroid. The current song still dominates — the next track should
     * follow what is playing — but the centroid stops the walk from wandering
     * off, and it works entirely on analysis, with no tags involved.
     */
    private suspend fun findCandidates(
        currentSongId: Long,
        excludeSongIds: Set<Long>,
        sessionAnchor: FloatArray?,
        anchorPull: Float,
    ): List<Neighbor> {
        val options = SimilarityOptions(k = CANDIDATE_POOL, excludeSongIds = excludeSongIds)
        if (sessionAnchor == null || anchorPull <= 0f) {
            return similarityRepository.findSimilar(currentSongId, options)
        }
        val currentVector = similarityRepository.embeddingOf(currentSongId)
            ?: return similarityRepository.findSimilar(currentSongId, options)
        val blended = blendAnchor(currentVector, sessionAnchor, anchorPull)
            ?: return similarityRepository.findSimilar(currentSongId, options)
        // findSimilar knows to skip its own seed song; findSimilarToVector has
        // no seed to skip, so the playing song has to be excluded by hand or
        // it comes back as its own nearest neighbour.
        return similarityRepository.findSimilarToVector(
            blended,
            options.copy(excludeSongIds = excludeSongIds + currentSongId),
        )
    }

    /**
     * Folds the song that just started playing into the session centroid.
     * Returns the previous anchor unchanged when the song has not been
     * analyzed yet, so an unanalyzed track does not wipe the session's sense
     * of direction.
     */
    suspend fun advanceSessionAnchor(
        anchor: FloatArray?,
        songId: Long,
        decay: Float,
    ): FloatArray? {
        val vector = similarityRepository.embeddingOf(songId) ?: return anchor
        return advanceAnchor(anchor, vector, decay)
    }

    /** Shared by JourneyEngine, which supplies its own candidate list. */
    fun sample(neighbors: List<Neighbor>, config: ShuffleConfig): Long? {
        if (neighbors.isEmpty()) return null

        val scored = neighbors.map { n ->
            var score = n.similarity
            config.energyTarget?.let { target ->
                score -= ENERGY_WEIGHT * abs(n.energy - target)
            }
            config.moodFilter?.let { mood ->
                val membership = MoodProfiles.score(mood, n.perceptual)
                score -= MOOD_WEIGHT * (1f - membership)
            }
            n.songId to score
        }.sortedByDescending { it.second }
            .take(SELECTION_POOL)

        return weightedPick(scored, config.temperature)
    }

    private fun samplePersonalized(
        neighbors: List<Neighbor>,
        config: ShuffleConfig,
        weights: StyleWeights,
        context: PersonalizationContext,
    ): Long? {
        val scored = neighbors
            .map { n -> n.songId to scoreCandidate(n, config, weights, context) }
            .sortedByDescending { it.second }
            .take(SELECTION_POOL_V2)

        return weightedPick(scored, config.temperature)
    }

    internal fun scoreCandidate(
        n: Neighbor,
        config: ShuffleConfig,
        weights: StyleWeights,
        context: PersonalizationContext,
    ): Float {
        val song = context.songs[n.songId]
        val behavior = context.stats[n.songId]
        var score = n.similarity

        // Explicit musical controls remain authoritative.
        config.energyTarget?.let { target ->
            score -= ENERGY_WEIGHT * abs(n.energy - target)
        }
        config.moodFilter?.let { mood ->
            val membership = MoodProfiles.score(mood, n.perceptual)
            score -= MOOD_WEIGHT * (1f - membership)
        }

        // Signed in [-1, +1]: a style can now demote as well as promote.
        score += weights.familiarity * config.familiarity * centered(familiarityScore(behavior))
        score += weights.discovery * config.discovery *
            centered(discoveryScore(behavior, context.nowMillis))
        score += weights.newLibrary * config.discovery *
            newLibraryScore(context.addedAt[n.songId], context.nowMillis)
        score -= SKIP_PENALTY * shrunkSkipRate(behavior)
        score -= weights.recency * recencyPenalty(behavior, context.nowMillis)

        if (n.songId in context.favorites) {
            score += FAVORITE_BONUS * (0.55f + config.familiarity * 0.45f)
        }

        if (song != null) {
            val artist = normalizeArtist(song.artist)
            val cooldown = context.artistCooldown[artist]
            if (artist.isNotBlank() && cooldown != null) {
                score -= weights.artistCooldown * cooldown
            }
            // Flow is centred too, so a track that fights the session's tempo
            // and genre is actively demoted on FLOW rather than merely
            // un-rewarded — and a track we know nothing about lands on neutral
            // instead of being quietly punished for missing tags.
            score += weights.flow * centered(flowScore(n, song, context))
        }

        return score
    }

    private fun weightedPick(scored: List<Pair<Long, Float>>, temperature: Float): Long? {
        if (scored.isEmpty()) return null
        val t = temperature.coerceIn(0.03f, 0.90f)
        val maxScore = scored.first().second
        val weights = scored.map { exp(((it.second - maxScore) / t).toDouble()) }
        val total = weights.sum()
        if (total <= 0.0 || total.isNaN()) return scored.first().first
        var roll = random.nextDouble() * total
        for (i in scored.indices) {
            roll -= weights[i]
            if (roll <= 0) return scored[i].first
        }
        return scored.last().first
    }

    internal fun flowScore(n: Neighbor, song: Song, context: PersonalizationContext): Float {
        val anchorEnergy = context.currentEnergy
        val energyFlow = if (anchorEnergy != null) {
            (1f - abs(n.energy - anchorEnergy)).coerceIn(0f, 1f)
        } else {
            NEUTRAL
        }

        // Do not smart-cast nullable public API properties from other modules.
        val candidateBpm = n.bpm
        val anchorBpm = context.currentBpm
        val tempoFlow = if (anchorBpm != null && candidateBpm != null) {
            (1f - abs(candidateBpm - anchorBpm) / BPM_FULL_PENALTY_DELTA).coerceIn(0f, 1f)
        } else {
            NEUTRAL
        }

        val genreFlow = genreFlow(genreTokens(song.genre), context.anchorGenres)

        return (0.50f * energyFlow + 0.26f * tempoFlow + 0.24f * genreFlow).coerceIn(0f, 1f)
    }

    internal data class PersonalizationContext(
        val songs: Map<Long, Song>,
        val stats: Map<Long, PlaybackBehaviorStats>,
        val favorites: Set<Long>,
        val currentEnergy: Float?,
        val currentBpm: Float?,
        val addedAt: Map<Long, Long>,
        /** artist -> cooldown strength in (0, 1]; 1 = heard most recently. */
        val artistCooldown: Map<String, Float>,
        val anchorGenres: Set<String>,
        val nowMillis: Long,
    )

    internal data class StyleWeights(
        val familiarity: Float,
        val discovery: Float,
        val flow: Float,
        val artistCooldown: Float,
        val newLibrary: Float,
        val recency: Float,
        val exploration: Float,
        /**
         * Share of the search vector taken from the session centroid rather
         * than the song currently playing. Higher = the session holds its
         * ground harder.
         */
        val anchorPull: Float,
    ) {
        companion object {
            //                                          fam    disc   flow   artist newLib recency explore anchor
            fun forStyle(style: SmartShuffleStyle) = when (style) {
                SmartShuffleStyle.BALANCED ->
                    StyleWeights(0.26f, 0.22f, 0.20f, 0.34f, 0.10f, 1.00f, 0.06f, 0.30f)
                SmartShuffleStyle.FAMILIAR ->
                    StyleWeights(0.42f, 0.10f, 0.16f, 0.30f, 0.05f, 0.70f, 0.00f, 0.30f)
                SmartShuffleStyle.DISCOVER ->
                    StyleWeights(0.14f, 0.44f, 0.14f, 0.38f, 0.18f, 1.25f, 0.22f, 0.15f)
                SmartShuffleStyle.FLOW ->
                    StyleWeights(0.18f, 0.14f, 0.44f, 0.42f, 0.06f, 1.00f, 0.03f, 0.45f)
            }
        }
    }

    companion object {
        const val CANDIDATE_POOL = 48
        const val SELECTION_POOL = 12
        const val SELECTION_POOL_V2 = 18
        const val ENERGY_WEIGHT = 0.5f
        const val MOOD_WEIGHT = 0.6f

        /** The "we have no opinion" point for every 0..1 signal in the scorer. */
        internal const val NEUTRAL = 0.5f

        private const val FAVORITE_BONUS = 0.16f
        private const val SKIP_PENALTY = 0.28f
        private const val DAY_MS = 24L * 60L * 60L * 1000L

        /**
         * Pseudo-events added to the denominator when turning play counts into
         * rates. With 1 event a rate is worth 25% of its face value; by 12
         * events it is worth 80%. This is what stops a single skip from
         * burying a track.
         */
        internal const val EVIDENCE_PRIOR = 3f

        /** Discovery staleness only starts once the recency penalty has faded. */
        internal const val RECENCY_TAIL_DAYS = 7f
        internal const val DISCOVERY_FULL_AFTER_DAYS = 45f
        internal const val NEW_LIBRARY_DAYS = 14f
        internal const val RECENCY_MAX = 0.26f
        internal const val RECENCY_DECAY_DAYS = 2.2f
        internal const val ARTIST_COOLDOWN_DECAY = 0.62f
        private const val BPM_FULL_PENALTY_DELTA = 60f

        /** Tags that carry no information and should not anchor genre flow. */
        private val EMPTY_GENRE_TOKENS = setOf("unknown", "other", "misc", "n/a", "none", "genre")

        /** Maps a 0..1 signal onto [-1, +1] so it can push a score both ways. */
        internal fun centered(value: Float): Float = ((value - NEUTRAL) * 2f).coerceIn(-1f, 1f)

        /** L2-normalizes in place-free fashion; null for a zero or empty vector. */
        internal fun normalize(vector: FloatArray): FloatArray? {
            if (vector.isEmpty()) return null
            var sumOfSquares = 0.0
            for (component in vector) sumOfSquares += component.toDouble() * component
            if (sumOfSquares <= 1e-12) return null
            val inverseNorm = (1.0 / sqrt(sumOfSquares)).toFloat()
            return FloatArray(vector.size) { vector[it] * inverseNorm }
        }

        /**
         * Search vector: mostly the song playing now, partly where the session
         * has been. [pull] is the share taken from the anchor.
         *
         * Returns null on a dimension mismatch — which happens if the index is
         * rebuilt with a different embedding size mid-session — so the caller
         * can fall back to the plain neighbour search instead of searching a
         * garbage vector.
         */
        internal fun blendAnchor(current: FloatArray, anchor: FloatArray, pull: Float): FloatArray? {
            if (current.isEmpty() || anchor.size != current.size) return null
            val p = pull.coerceIn(0f, 1f)
            return normalize(FloatArray(current.size) { i -> (1f - p) * current[i] + p * anchor[i] })
        }

        /**
         * Exponential moving average of the session's embeddings. [decay] is
         * how much of the existing anchor survives each new song, so it spans
         * the whole range of useful behaviour with one number: see
         * SmartQueueCoordinator.SESSION_ANCHOR_DECAY.
         */
        internal fun advanceAnchor(
            anchor: FloatArray?,
            songVector: FloatArray,
            decay: Float,
        ): FloatArray? {
            if (anchor == null || anchor.size != songVector.size) return normalize(songVector)
            val d = decay.coerceIn(0f, 1f)
            return normalize(FloatArray(anchor.size) { i -> d * anchor[i] + (1f - d) * songVector[i] })
        }

        /** Confidence in a rate computed from [events] observations, in [0, 1). */
        internal fun evidenceWeight(events: Int): Float =
            if (events <= 0) 0f else events / (events + EVIDENCE_PRIOR)

        /**
         * Turns an ordered "most recent first" artist list into decaying
         * cooldown strengths, so the artist just heard is discouraged much
         * harder than one from four tracks ago. v2.0 treated the whole window
         * as one flat set, which made the fourth-last artist as unwelcome as
         * the current one.
         */
        internal fun artistCooldownMap(recentArtists: List<String>): Map<String, Float> {
            val cooldown = LinkedHashMap<String, Float>()
            recentArtists.forEachIndexed { index, raw ->
                val artist = normalizeArtist(raw)
                if (artist.isBlank()) return@forEachIndexed
                val decayed = ARTIST_COOLDOWN_DECAY.toDouble().pow(index).toFloat()
                val existing = cooldown[artist]
                cooldown[artist] = if (existing == null) decayed else maxOf(existing, decayed)
            }
            return cooldown
        }

        internal fun familiarityScore(stats: PlaybackBehaviorStats?): Float {
            val s = stats ?: return NEUTRAL
            if (s.totalEvents == 0) return NEUTRAL
            val playStrength = (ln(1.0 + s.completedPlays) / ln(11.0)).toFloat().coerceIn(0f, 1f)
            val raw = (0.62f * s.completionRate + 0.38f * playStrength).coerceIn(0f, 1f)
            // Shrink toward neutral until there is enough evidence to trust it.
            return NEUTRAL + (raw - NEUTRAL) * evidenceWeight(s.totalEvents)
        }

        internal fun discoveryScore(stats: PlaybackBehaviorStats?, nowMillis: Long): Float {
            val s = stats ?: return 1f
            if (s.totalEvents == 0) return 1f
            val lastPlayedAt = s.lastPlayedAt ?: return 1f
            val daysSince = ((nowMillis - lastPlayedAt).coerceAtLeast(0L) / DAY_MS.toDouble()).toFloat()
            // Starts at RECENCY_TAIL_DAYS so this does not double-count the
            // short-term recency penalty, which has decayed to ~0 by then.
            val stale = ((daysSince - RECENCY_TAIL_DAYS) /
                (DISCOVERY_FULL_AFTER_DAYS - RECENCY_TAIL_DAYS)).coerceIn(0f, 1f)
            val rarity = (1f / (1f + s.completedPlays * 0.35f)).coerceIn(0f, 1f)
            return (0.55f * stale + 0.45f * rarity).coerceIn(0f, 1f)
        }

        internal fun newLibraryScore(addedAt: Long?, nowMillis: Long): Float {
            val added = addedAt ?: return 0f
            val ageDays = ((nowMillis - added).coerceAtLeast(0L) / DAY_MS.toDouble()).toFloat()
            return (1f - ageDays / NEW_LIBRARY_DAYS).coerceIn(0f, 1f)
        }

        /**
         * Smooth exponential decay rather than step cliffs, so a track does not
         * jump in desirability the instant a threshold is crossed.
         * ~0.26 now, 0.17 after a day, 0.07 after three, ~0.01 after a week.
         */
        internal fun recencyPenalty(stats: PlaybackBehaviorStats?, nowMillis: Long): Float {
            val last = stats?.lastPlayedAt ?: return 0f
            val days = ((nowMillis - last).coerceAtLeast(0L) / DAY_MS.toDouble()).toFloat()
            return RECENCY_MAX * exp(-days / RECENCY_DECAY_DAYS)
        }

        /**
         * Skip rate discounted by how much evidence supports it. One skip out
         * of one play is an accident, not a verdict.
         */
        internal fun shrunkSkipRate(stats: PlaybackBehaviorStats?): Float {
            val s = stats ?: return 0f
            return s.skipRate * evidenceWeight(s.totalEvents)
        }

        /**
         * Jaccard-flavoured overlap. Unknown tags on either side are neutral,
         * never a penalty — a large share of a local library arrives untagged
         * and should not be quietly demoted for it. Any overlap clears 0.55;
         * an exact tag match reaches 1.0.
         */
        internal fun genreFlow(candidate: Set<String>, anchors: Set<String>): Float {
            if (candidate.isEmpty() || anchors.isEmpty()) return NEUTRAL
            val intersection = candidate.intersect(anchors).size
            if (intersection == 0) return 0f
            val union = (candidate + anchors).size
            return (0.55f + 0.45f * (intersection.toFloat() / union)).coerceIn(0f, 1f)
        }

        fun normalizeArtist(value: String?): String = value.orEmpty().trim().lowercase()

        fun genreTokens(value: String?): Set<String> = value.orEmpty()
            .lowercase()
            .split(',', ';', '/', '|')
            .map { it.trim() }
            .filter { it.length >= 2 && it !in EMPTY_GENRE_TOKENS }
            .toSet()
    }
}
