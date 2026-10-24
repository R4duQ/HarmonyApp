package com.harmony.data.shuffle

import com.harmony.core.common.coroutines.DispatcherProvider
import com.harmony.core.database.dao.AnalysisDao
import com.harmony.core.database.dao.HistoryDao
import com.harmony.core.database.dao.SongDao
import com.harmony.core.dsp.NativeShuffleEngine
import com.harmony.core.dsp.SmartShuffleNative
import com.harmony.core.model.SessionEvent
import com.harmony.core.model.VectorQuality
import com.harmony.domain.analysis.repository.AcousticVectorStore
import com.harmony.domain.shuffle.brain.QueuePlan
import com.harmony.domain.shuffle.brain.QueueRequest
import com.harmony.domain.shuffle.brain.ShuffleBrain
import com.harmony.domain.shuffle.engine.MoodProfiles
import com.harmony.domain.shuffle.model.ShuffleConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ShuffleBrain] on the native SmartShuffleEngine.
 *
 * The engine holds the whole library in memory as 24-D unit vectors (about
 * 100 bytes a song) plus the listening stats; this class keeps it in step
 * with the databases:
 *
 *  - the library snapshot comes from harmony.db (songs, artists after tag
 *    edits, play history, favourites) and smart_shuffle.db (24-D vectors,
 *    Markov habits, the zone bandit); songs not measured yet borrow a
 *    cold-start vector from the older analysis;
 *  - it is reloaded when the number of songs, vectors or analyses changes,
 *    or after [RELOAD_AFTER_MS], whichever comes first;
 *  - each outcome's learning (a Markov transition, a bandit reward) is
 *    written back to smart_shuffle.db as it happens; play counts are already
 *    recorded by the playback history.
 *
 * Native calls run on the default dispatcher, database work on IO.
 */
@Singleton
class NativeShuffleBrain @Inject constructor(
    private val database: SmartShuffleDatabase,
    private val songDao: SongDao,
    private val historyDao: HistoryDao,
    private val analysisDao: AnalysisDao,
    private val dispatchers: DispatcherProvider,
) : ShuffleBrain {

    private val engine: NativeShuffleEngine? =
        if (SmartShuffleNative.available) runCatching { NativeShuffleEngine() }.getOrNull() else null

    override val available: Boolean get() = engine != null

    private val loadLock = Mutex()
    private var snapshot: SnapshotKey? = null
    private var loadedAt = 0L
    private var librarySize = 0
    private var config = ShuffleConfig()
    private var perceptual: Map<Long, FloatArray> = emptyMap()
    private var rowIds: LongArray = LongArray(0)

    private data class SnapshotKey(val songs: Int, val vectors: Int, val analyses: Int)

    override suspend fun prepare() {
        val e = engine ?: return
        loadLock.withLock {
            val key = withContext(dispatchers.io) {
                SnapshotKey(songDao.songCount(), database.acousticVectors().count(), analysisDao.count())
            }
            val fresh = key == snapshot && System.currentTimeMillis() - loadedAt < RELOAD_AFTER_MS
            if (!fresh) load(e, key)
        }
    }

    private suspend fun load(e: NativeShuffleEngine, key: SnapshotKey) {
        val data = withContext(dispatchers.io) {
            LibraryData(
                songs = songDao.shuffleRows(),
                vectors = database.acousticVectors().measured().associateBy { it.songId },
                coldStart = analysisDao.coldStartRows().associateBy { it.songId },
                stats = historyDao.allBehaviorStats().associateBy { it.songId },
                favorites = historyDao.allFavoriteIds().toHashSet(),
                transitions = database.markov().all(),
                bandit = database.bandit().all(),
                perceptual = analysisDao.perceptualRows().associate { row ->
                    row.songId to floatArrayOf(
                        row.energy, row.danceability, row.acousticness, row.instrumentalness, row.brightness,
                        row.warmth, row.aggressiveness, row.calmness, row.happiness, row.sadness, row.tension,
                    )
                },
            )
        }
        withContext(dispatchers.default) {
            val n = data.songs.size
            val ids = LongArray(n)
            val raw = FloatArray(n * DIMS) { Float.NaN }
            val quality = ByteArray(n)
            val artists = IntArray(n)
            val albums = IntArray(n)
            val artistIds = HashMap<String, Int>()
            val albumIds = HashMap<Long, Int>()
            data.songs.forEachIndexed { i, song ->
                ids[i] = song.id
                val measured = AcousticVectorEntity.decode(data.vectors[song.id]?.vector)
                val values = measured ?: data.coldStart[song.id]?.let {
                    ColdStartVectors.fromAnalysis(it.bassEnergy, it.midEnergy, it.trebleEnergy, it.rms, it.peakLevel, it.bpm, it.beatConfidence)
                }
                values?.copyInto(raw, i * DIMS)
                quality[i] = when {
                    measured != null -> VectorQuality.REAL
                    values != null -> VectorQuality.FALLBACK
                    else -> VectorQuality.NONE
                }.ordinal.toByte()
                val artist = song.artist.trim().lowercase()
                // Untagged files ("<unknown>" from MediaStore) are nobody: never clumped together.
                artists[i] = if (artist.isEmpty() || artist in UNKNOWN_ARTISTS) -1 else artistIds.getOrPut(artist) { artistIds.size }
                albums[i] = albumIds.getOrPut(song.albumId) { albumIds.size }
            }
            librarySize = e.setLibrary(ids, raw, quality, artists, albums)
            rowIds = ids

            val completed = IntArray(n)
            val skipped = IntArray(n)
            val lastPlayed = LongArray(n)
            val favorite = ByteArray(n)
            for (i in 0 until n) {
                data.stats[ids[i]]?.let {
                    completed[i] = it.completedPlays
                    skipped[i] = it.skippedPlays
                    lastPlayed[i] = it.lastPlayedAt ?: 0L
                }
                if (ids[i] in data.favorites) favorite[i] = 1
            }
            e.setTrackStats(completed, skipped, lastPlayed, favorite)

            e.clearTransitions()
            if (data.transitions.isNotEmpty()) {
                e.addTransitions(
                    LongArray(data.transitions.size) { data.transitions[it].fromSongId },
                    LongArray(data.transitions.size) { data.transitions[it].toSongId },
                    FloatArray(data.transitions.size) { data.transitions[it].weight },
                )
            }
            val pulls = FloatArray(ZONES * ZONES)
            val rewards = FloatArray(ZONES * ZONES)
            data.bandit.forEach {
                if (it.context in 0 until ZONES && it.arm in 0 until ZONES) {
                    pulls[it.context * ZONES + it.arm] = it.pulls
                    rewards[it.context * ZONES + it.arm] = it.rewards
                }
            }
            e.setZoneBandit(pulls, rewards)
            perceptual = data.perceptual
            applySteering(e)
        }
        snapshot = key
        loadedAt = System.currentTimeMillis()
    }

    override suspend fun applyConfig(config: ShuffleConfig) {
        val e = engine ?: return
        loadLock.withLock {
            this.config = config
            withContext(dispatchers.default) {
                e.setWeights(BrainWeights.forConfig(config))
                applySteering(e)
            }
        }
    }

    /** Energy target and mood, as a 0..1 score per library row (setLibrary clears it). */
    private fun applySteering(e: NativeShuffleEngine) {
        val target = config.energyTarget
        val mood = config.moodFilter
        if ((target == null && mood == null) || rowIds.isEmpty()) {
            e.setSteering(null, 0f)
            return
        }
        val scores = FloatArray(rowIds.size) { i ->
            val p = perceptual[rowIds[i]] ?: return@FloatArray 0.5f
            var sum = 0f
            var parts = 0
            if (target != null) {
                sum += 1f - kotlin.math.abs(p[0] - target)
                parts++
            }
            if (mood != null) {
                sum += MoodProfiles.score(mood, p)
                parts++
            }
            sum / parts
        }
        val weight = when {
            target != null && mood != null -> (BrainWeights.ENERGY_STEERING + BrainWeights.MOOD_STEERING) / 2f
            mood != null -> BrainWeights.MOOD_STEERING
            else -> BrainWeights.ENERGY_STEERING
        }
        e.setSteering(scores, weight)
    }

    override suspend fun onTrackStarted(songId: Long): SessionEvent {
        val e = engine ?: return SessionEvent.NONE
        return withContext(dispatchers.default) { e.onTrackStarted(songId) }
    }

    override suspend fun onPlaybackOutcome(songId: Long, fraction: Float, atMillis: Long, shufflePick: Boolean): SessionEvent {
        val e = engine ?: return SessionEvent.NONE
        val (event, learned, bandit) = withContext(dispatchers.default) {
            Triple(e.onPlaybackOutcome(songId, fraction, atMillis, shufflePick), e.lastLearnedTransition(), e.lastBanditUpdate())
        }
        if (learned != null || bandit != null) {
            withContext(dispatchers.io) {
                runCatching {
                    learned?.let { (from, to, delta) -> database.markov().add(from, to, delta, atMillis) }
                    bandit?.let { (context, arm, reward) -> database.bandit().add(context, arm, 1f, reward) }
                    if (learned != null && database.markov().count() > MAX_TRANSITIONS) {
                        database.markov().pruneWeakest(MAX_TRANSITIONS / 10)
                    }
                }
            }
        }
        return event
    }

    override suspend fun onManualSelect(songId: Long): SessionEvent {
        val e = engine ?: return SessionEvent.NONE
        return withContext(dispatchers.default) { e.onManualSelect(songId) }
    }

    override suspend fun resetSession() {
        val e = engine ?: return
        withContext(dispatchers.default) { e.resetSession() }
    }

    override suspend fun generate(request: QueueRequest): QueuePlan {
        val e = engine ?: return QueuePlan.EMPTY
        return withContext(dispatchers.default) {
            val ids = e.generateQueue(
                currentId = request.currentSongId,
                prefix = request.prefixIds.toLongArray(),
                exclude = request.excludeIds.toLongArray(),
                replacedTail = request.replacedTail.toLongArray(),
                count = request.count,
                nowMs = System.currentTimeMillis(),
                seed = request.seed,
            )
            QueuePlan(ids.toList(), e.lastTraces(), e.lastAudit())
        }
    }

    override suspend fun journeyStep(fromId: Long, toId: Long, t: Float, previousId: Long, exclude: Set<Long>): QueuePlan {
        val e = engine ?: return QueuePlan.EMPTY
        return withContext(dispatchers.default) {
            val id = e.journeyStep(fromId, toId, t, previousId, exclude.toLongArray(), System.currentTimeMillis(), System.nanoTime())
            if (id == null) QueuePlan(emptyList(), emptyList(), e.lastAudit())
            else QueuePlan(listOf(id), e.lastTraces(), e.lastAudit())
        }
    }

    override suspend fun sessionVector(): FloatArray? = engine?.sessionVector()

    override suspend fun librarySize(): Int = librarySize

    override suspend fun runSelfTest(): String {
        if (engine == null) return "RESULT: FAIL (the native Smart Shuffle engine is not available)"
        return withContext(dispatchers.default) { NativeShuffleEngine.runSyntheticBrainSimulation() }
    }

    private class LibraryData(
        val songs: List<SongDao.ShuffleSongRow>,
        val vectors: Map<Long, AcousticVectorEntity>,
        val coldStart: Map<Long, AnalysisDao.ColdStartRow>,
        val stats: Map<Long, HistoryDao.BehaviorStatsRow>,
        val favorites: Set<Long>,
        val transitions: List<MarkovTransitionEntity>,
        val bandit: List<ZoneBanditEntity>,
        val perceptual: Map<Long, FloatArray>,
    )

    private companion object {
        const val DIMS = SmartShuffleNative.DIMS
        const val ZONES = SmartShuffleNative.ZONES
        const val RELOAD_AFTER_MS = 30 * 60 * 1000L
        const val MAX_TRANSITIONS = 50_000
        val UNKNOWN_ARTISTS = setOf("<unknown>", "unknown")
    }
}

/** The indexer's side of smart_shuffle.db. */
@Singleton
class AcousticVectorStoreImpl @Inject constructor(
    private val database: SmartShuffleDatabase,
) : AcousticVectorStore {
    override suspend fun keys(): Map<Long, Pair<String, Int>> =
        database.acousticVectors().keys().associate { it.songId to (it.fileHash to it.extractorVersion) }

    override suspend fun put(songId: Long, fileHash: String, raw: FloatArray?) {
        val clean = raw?.takeIf { it.size == AcousticVectorEntity.DIMS && it.all(Float::isFinite) }
        database.acousticVectors().upsert(
            AcousticVectorEntity(
                songId = songId,
                fileHash = fileHash,
                extractorVersion = AcousticVectorStore.ACOUSTIC_VERSION,
                vector = clean?.let(AcousticVectorEntity::encode),
                measuredAt = System.currentTimeMillis(),
            ),
        )
    }
}
