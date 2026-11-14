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
import com.harmony.domain.library.discovery.TrackIdentity
import com.harmony.domain.shuffle.brain.MixRequest
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
 *  - it is loaded in full at first, when the songs change (their number),
 *    and every [RELOAD_AFTER_MS] (which also picks up tag edits, favourites
 *    and stats from the databases); in between, new 24-D vectors and new
 *    analyses are sent alone ([refresh]): while the background indexer
 *    measures the library, a queue no longer reloads all of it;
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
    private var perceptual: HashMap<Long, FloatArray> = HashMap()
    private var rowIds: LongArray = LongArray(0)
    private var rowOf: HashMap<Long, Int> = HashMap()
    private var rowQuality: ByteArray = ByteArray(0)

    /** For the taste memory: library song ids by recording ([TrackIdentity] key), engine artist ids by lead credit. */
    private var idsByRecording: HashMap<String, LongArray> = HashMap()
    private var artistIdsByLead: HashMap<String, IntArray> = HashMap()

    /** How often the library was loaded in full and refreshed incrementally (tests). */
    internal var fullLoads = 0
    internal var refreshes = 0

    /** Song and artist pairs of the taste memory last handed to the engine (tests). */
    internal var tastePairs = 0 to 0

    /** Tests: the next [prepare] loads everything, as after [RELOAD_AFTER_MS]. */
    internal suspend fun forceFullReload() = loadLock.withLock { snapshot = null }

    private data class SnapshotKey(val songs: Int, val vectors: AcousticVectorDao.Stamp, val analyses: Int, val taste: TasteDao.Stamp)

    override suspend fun prepare() {
        val e = engine ?: return
        loadLock.withLock {
            val key = withContext(dispatchers.io) {
                SnapshotKey(songDao.songCount(), database.acousticVectors().stamp(), analysisDao.count(), database.taste().stamp())
            }
            val previous = snapshot
            when {
                previous == null ||
                    System.currentTimeMillis() - loadedAt >= RELOAD_AFTER_MS ||
                    key.songs != previous.songs ||
                    key.vectors.count < previous.vectors.count ||
                    key.analyses < previous.analyses -> load(e, key)
                key != previous -> refresh(e, previous, key)
            }
        }
    }

    /**
     * Sends only what the indexer added since [previous]: the vectors written
     * since then, and the analyses of songs that had none. The engine keeps
     * its statistics, steering and session, and its vectors come out as a
     * full [load] of the same data would give them.
     */
    private suspend fun refresh(e: NativeShuffleEngine, previous: SnapshotKey, key: SnapshotKey) {
        val written = if (key.vectors != previous.vectors) {
            withContext(dispatchers.io) { database.acousticVectors().writtenSince(previous.vectors.latest) }
                .filter { it.songId in rowOf }
        } else {
            emptyList()
        }
        // New analyses can only be for songs that had none: those without perceptual values.
        val unanalysed = if (key.analyses != previous.analyses) rowIds.filter { it !in perceptual } else emptyList()
        val failed = written.filter { AcousticVectorEntity.decode(it.vector) == null }.map { it.songId }
        val lookup = (unanalysed + failed).distinct()
        val (coldStart, newPerceptual) = withContext(dispatchers.io) {
            val cold = HashMap<Long, AnalysisDao.ColdStartRow>()
            val moods = HashMap<Long, FloatArray>()
            for (chunk in lookup.chunked(IN_CHUNK)) {
                analysisDao.coldStartRowsFor(chunk).forEach { cold[it.songId] = it }
            }
            for (chunk in unanalysed.chunked(IN_CHUNK)) {
                analysisDao.perceptualRowsFor(chunk).forEach { moods[it.songId] = it.toArray() }
            }
            cold to moods
        }
        withContext(dispatchers.default) {
            val updates = LinkedHashMap<Long, Pair<FloatArray?, VectorQuality>>()
            for (row in written) {
                val measured = AcousticVectorEntity.decode(row.vector)
                val values = measured ?: coldStart[row.songId]?.toColdStart()
                updates[row.songId] = values to qualityOf(measured, values)
            }
            for (id in unanalysed) {
                if (id in updates) continue
                val cold = coldStart[id] ?: continue
                // A measured vector outranks the older analysis.
                if (rowQuality[rowOf.getValue(id)] == VectorQuality.REAL.ordinal.toByte()) continue
                updates[id] = cold.toColdStart() to VectorQuality.FALLBACK
            }
            if (updates.isNotEmpty()) {
                val ids = updates.keys.toLongArray()
                val raw = FloatArray(ids.size * DIMS) { Float.NaN }
                val quality = ByteArray(ids.size)
                ids.forEachIndexed { k, id ->
                    val (values, q) = updates.getValue(id)
                    values?.copyInto(raw, k * DIMS)
                    quality[k] = q.ordinal.toByte()
                    rowQuality[rowOf.getValue(id)] = quality[k]
                }
                e.updateTracks(ids, raw, quality)
            }
            if (newPerceptual.isNotEmpty()) {
                perceptual.putAll(newPerceptual)
                applySteering(e)
            }
        }
        if (key.taste != previous.taste) applyTaste(e)
        snapshot = key
        refreshes++
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
                perceptual = analysisDao.perceptualRows().associateTo(HashMap()) { it.songId to it.toArray() },
                names = songDao.tasteRows(),
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
                val values = measured ?: data.coldStart[song.id]?.toColdStart()
                values?.copyInto(raw, i * DIMS)
                quality[i] = qualityOf(measured, values).ordinal.toByte()
                val artist = song.artist.trim().lowercase()
                // Untagged files ("<unknown>" from MediaStore) are nobody: never clumped together.
                artists[i] = if (artist.isEmpty() || artist in UNKNOWN_ARTISTS) -1 else artistIds.getOrPut(artist) { artistIds.size }
                albums[i] = albumIds.getOrPut(song.albumId) { albumIds.size }
            }
            librarySize = e.setLibrary(ids, raw, quality, artists, albums)
            rowIds = ids
            rowOf = HashMap<Long, Int>(n * 2).apply { ids.forEachIndexed { i, id -> put(id, i) } }
            rowQuality = quality

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

            val byRecording = HashMap<String, MutableList<Long>>()
            data.names.forEach { byRecording.getOrPut(TrackIdentity.of(it.title, it.artist).key) { ArrayList(1) } += it.id }
            idsByRecording = byRecording.mapValuesTo(HashMap()) { it.value.toLongArray() }
            val byLead = HashMap<String, MutableList<Int>>()
            artistIds.forEach { (name, id) -> byLead.getOrPut(TrackIdentity.primaryArtist(name)) { ArrayList(1) } += id }
            artistIdsByLead = byLead.mapValuesTo(HashMap()) { it.value.toIntArray() }
        }
        applyTaste(e)
        snapshot = key
        loadedAt = System.currentTimeMillis()
        fullLoads++
    }

    /**
     * The taste memory ([TasteMemory]) in the engine's terms: a seed song's
     * links to the library's recordings of what its radios played, and the
     * artist links between the library's artists. What the library doesn't
     * have is left out; it counts the day it does (a song kept, say).
     */
    private suspend fun applyTaste(e: NativeShuffleEngine) {
        val edges = withContext(dispatchers.io) { database.taste().all() }
        withContext(dispatchers.default) {
            val from = ArrayList<Long>()
            val to = ArrayList<Long>()
            val weight = ArrayList<Float>()
            val artistFrom = ArrayList<Int>()
            val artistTo = ArrayList<Int>()
            val artistWeight = ArrayList<Float>()
            for (edge in edges) {
                when (edge.kind) {
                    TasteEdgeEntity.SONG -> {
                        val seed = edge.fromKey.removePrefix("id:").toLongOrNull() ?: continue
                        idsByRecording[edge.toKey]?.forEach { id -> from += seed; to += id; weight += edge.weight }
                    }
                    TasteEdgeEntity.ARTIST -> {
                        val a = artistIdsByLead[edge.fromKey] ?: continue
                        val b = artistIdsByLead[edge.toKey] ?: continue
                        for (x in a) for (y in b) { artistFrom += x; artistTo += y; artistWeight += edge.weight }
                    }
                }
            }
            tastePairs = from.size to artistFrom.size
            e.setTaste(from.toLongArray(), to.toLongArray(), weight.toFloatArray(),
                artistFrom.toIntArray(), artistTo.toIntArray(), artistWeight.toFloatArray())
        }
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

    override suspend fun mix(request: MixRequest): QueuePlan {
        val e = engine ?: return QueuePlan.EMPTY
        return withContext(dispatchers.default) {
            val ids = e.generateRadio(
                seedId = request.seedSongId,
                currentId = request.currentSongId,
                prefix = request.prefixIds.toLongArray(),
                exclude = request.excludeIds.toLongArray(),
                count = request.count,
                nowMs = System.currentTimeMillis(),
                seed = request.seed,
            )
            QueuePlan(ids.toList(), e.lastTraces(), e.lastAudit(), e.lastRadioReport())
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
        val perceptual: HashMap<Long, FloatArray>,
        val names: List<SongDao.TasteSongRow>,
    )

    private fun AnalysisDao.ColdStartRow.toColdStart(): FloatArray =
        ColdStartVectors.fromAnalysis(bassEnergy, midEnergy, trebleEnergy, rms, peakLevel, bpm, beatConfidence)

    private fun AnalysisDao.PerceptualRow.toArray(): FloatArray = floatArrayOf(
        energy, danceability, acousticness, instrumentalness, brightness,
        warmth, aggressiveness, calmness, happiness, sadness, tension,
    )

    private fun qualityOf(measured: FloatArray?, values: FloatArray?): VectorQuality = when {
        measured != null -> VectorQuality.REAL
        values != null -> VectorQuality.FALLBACK
        else -> VectorQuality.NONE
    }

    private companion object {
        const val DIMS = SmartShuffleNative.DIMS
        const val ZONES = SmartShuffleNative.ZONES
        const val RELOAD_AFTER_MS = 30 * 60 * 1000L
        const val MAX_TRANSITIONS = 50_000
        /** Ids per `IN (...)` query: under SQLite's 999-variable limit. */
        const val IN_CHUNK = 900
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
