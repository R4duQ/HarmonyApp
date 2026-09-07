package com.harmony.data.similarity

import com.harmony.core.common.coroutines.DispatcherProvider
import com.harmony.core.database.dao.AnalysisDao
import com.harmony.domain.analysis.embedding.EmbeddingBuilder
import com.harmony.domain.analysis.embedding.EmbeddingCodec
import com.harmony.domain.similarity.index.FlatEmbeddingIndex
import com.harmony.domain.similarity.model.IndexEntry
import com.harmony.domain.similarity.model.Neighbor
import com.harmony.domain.similarity.model.SimilarityOptions
import com.harmony.domain.similarity.repository.SimilarityRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the in-memory [FlatEmbeddingIndex] and keeps it synced with Room.
 *
 * Sync strategy: one observable join query re-emits on any analysis write or
 * song deletion; emissions are debounced (batch analysis writes one row per
 * song — hundreds of emissions during a first-run analysis) and the index is
 * diffed, not rebuilt: rows whose songId is new/changed get upserted, ids
 * that vanished get removed. A full rebuild would also be affordable (~4 MB),
 * but diffing keeps steady-state emissions O(changes) instead of O(library).
 *
 * Startup: the first emission of the query IS the initial load — no separate
 * warm-up path to maintain.
 */
@Singleton
class SimilarityRepositoryImpl @Inject constructor(
    private val analysisDao: AnalysisDao,
    dispatchers: DispatcherProvider,
) : SimilarityRepository {

    private val index = FlatEmbeddingIndex(EmbeddingBuilder.EMBEDDING_DIM)
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
    private val ioDispatcher = dispatchers.io
    private val syncMutex = Mutex()

    /** songIds currently in the index; kept for diffing. */
    private var knownIds = HashSet<Long>()

    private val _indexedCount = MutableStateFlow(0)
    override val indexedCount: StateFlow<Int> = _indexedCount

    init {
        @OptIn(FlowPreview::class)
        scope.launch {
            analysisDao.observeIndexRows()
                .debounce(SYNC_DEBOUNCE_MS)
                .collect { rows -> sync(rows) }
        }
    }

    private suspend fun sync(rows: List<AnalysisDao.IndexRow>) = syncMutex.withLock {
        val incomingIds = HashSet<Long>(rows.size)
        rows.forEach { row ->
            incomingIds += row.songId
            // Upsert unconditionally: it is idempotent, and re-upserting an
            // unchanged row costs a memcpy of 48 floats. Tracking per-row
            // content hashes to skip those would cost more than it saves.
            val vector = EmbeddingCodec.decode(row.embeddingBlob)
            if (vector.size == EmbeddingBuilder.EMBEDDING_DIM) {
                index.upsert(
                    IndexEntry(
                        songId = row.songId,
                        albumId = row.albumId,
                        energy = row.energy,
                        perceptual = row.perceptualArray(),
                        vector = vector,
                        bpm = row.bpm,
                    ),
                )
            }
        }
        (knownIds - incomingIds).forEach(index::remove)
        knownIds = incomingIds
        _indexedCount.value = index.size
    }

    override suspend fun findSimilar(songId: Long, options: SimilarityOptions): List<Neighbor> =
        withContext(ioDispatcher) {
            index.findNearest(
                querySongId = songId,
                k = options.k,
                exclude = options.excludeSongIds,
                dedupeThreshold = options.dedupeThreshold,
                maxPerAlbum = options.maxPerAlbum,
            )
        }

    override suspend fun findSimilarToVector(
        vector: FloatArray,
        options: SimilarityOptions,
    ): List<Neighbor> = withContext(ioDispatcher) {
        index.findNearestVector(
            query = vector,
            k = options.k,
            exclude = options.excludeSongIds,
            maxPerAlbum = options.maxPerAlbum,
        )
    }

    override suspend fun embeddingOf(songId: Long): FloatArray? = withContext(ioDispatcher) {
        analysisDao.bySongId(songId)?.embeddingBlob?.let(EmbeddingCodec::decode)
    }

    override suspend fun energyOf(songId: Long): Float? = index.energyOf(songId)

    override suspend fun bpmOf(songId: Long): Float? = index.bpmOf(songId)

    private companion object {
        const val SYNC_DEBOUNCE_MS = 3_000L
    }
}
