package com.harmony.data.analysis

import com.harmony.core.common.coroutines.DispatcherProvider
import com.harmony.core.database.dao.AnalysisDao
import com.harmony.core.database.dao.SongDao
import com.harmony.core.database.entity.AnalysisResultEntity
import com.harmony.core.dsp.NativeAnalyzer
import com.harmony.data.analysis.decode.AudioDecoder
import com.harmony.domain.analysis.embedding.EmbeddingBuilder
import com.harmony.domain.analysis.embedding.EmbeddingCodec
import com.harmony.domain.analysis.model.AnalysisResult
import com.harmony.domain.analysis.model.FeatureLayout
import com.harmony.domain.analysis.model.RawFeatures
import com.harmony.domain.analysis.perceptual.PerceptualMapper
import com.harmony.domain.analysis.repository.AnalysisEvent
import com.harmony.domain.analysis.repository.AnalysisRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ties the whole Phase 5 pipeline together:
 *
 *   Room (which songs need work?) -> AudioDecoder (Kotlin/MediaCodec)
 *     -> NativeAnalyzer (C++) -> PerceptualMapper + EmbeddingBuilder (Kotlin)
 *     -> Room (store result)
 *
 * "Analyze once": pendingSongIds() compares stored (fileHash, version)
 * against the current song row and FeatureLayout.ANALYSIS_VERSION. Unchanged
 * files are never re-decoded; a changed file or a pipeline bump re-queues
 * exactly the affected songs.
 *
 * Batch analysis is SEQUENTIAL by design: decode+FFT already saturates 1-2
 * big cores; parallel songs would mostly fight over memory bandwidth and
 * thermal headroom inside a background job that has no deadline. (The scan
 * pipeline parallelizes because it is I/O-bound; this one is CPU-bound.)
 */
@Singleton
class AnalysisRepositoryImpl @Inject constructor(
    private val songDao: SongDao,
    private val analysisDao: AnalysisDao,
    private val decoder: AudioDecoder,
    private val dispatchers: DispatcherProvider,
    private val playback: com.harmony.domain.playback.PlaybackController,
) : AnalysisRepository {

    override suspend fun pendingSongIds(): List<Long> {
        val analyzed = analysisDao.analysisKeys().associateBy { it.songId }
        return songDao.allIdsAndHashes().filter { row ->
            val existing = analyzed[row.id]
            existing == null ||
                existing.analyzedAtFileHash != row.fileHash ||
                existing.analysisVersion != FeatureLayout.ANALYSIS_VERSION
        }.map { it.id }
    }

    override suspend fun analyzeSong(songId: Long): Result<AnalysisResult> = runCatching {
        val song = songDao.byId(songId) ?: error("Song $songId not in library")

        var analyzer: NativeAnalyzer? = null
        val raw = try {
            decoder.decode(
                uriString = song.uri,
                maxDurationUs = MAX_ANALYZED_US,
                onStart = { sampleRate -> analyzer = NativeAnalyzer(sampleRate) },
                onPcm = { samples, count -> analyzer?.process(samples, count) },
            )
            analyzer?.finish() ?: error("Empty or undecodable audio stream")
        } finally {
            analyzer?.close()
        }

        val features = RawFeatures(raw)
        val perceptual = PerceptualMapper.map(features)
        val embedding = EmbeddingBuilder.build(features, perceptual)

        val result = AnalysisResult(
            songId = songId,
            fileHash = song.fileHash,
            raw = features,
            perceptual = perceptual,
            embedding = embedding,
        )
        analysisDao.upsert(result.toEntity())
        result
    }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }

    override fun analyzeAllPending(): Flow<AnalysisEvent> = flow {
        val pending = pendingSongIds()
        emit(AnalysisEvent.Started(pending.size))
        var analyzed = 0
        var failed = 0
        pending.forEachIndexed { index, songId ->
            awaitPlaybackIdle()
            analyzeSong(songId)
                .onSuccess {
                    analyzed++
                    emit(AnalysisEvent.SongAnalyzed(songId, index, pending.size))
                }
                .onFailure { e ->
                    failed++
                    emit(AnalysisEvent.SongFailed(songId, e.message ?: "unknown"))
                }
        }
        emit(AnalysisEvent.Completed(analyzed, failed))
    }.flowOn(BackgroundPriorityDispatchers.analysis)

    private suspend fun awaitPlaybackIdle() {
        while (playback.playerState.value.isPlaying) {
            kotlinx.coroutines.delay(PLAYBACK_IDLE_POLL_MS)
        }
    }

    private fun AnalysisResult.toEntity() = AnalysisResultEntity(
        songId = songId,
        analyzedAtFileHash = fileHash,
        analysisVersion = FeatureLayout.ANALYSIS_VERSION,
        bpm = raw.bpm.takeIf { it > 0f },
        beatConfidence = raw.beatConfidence.takeIf { raw.bpm > 0f },
        musicalKey = raw.keyIndex.takeIf { it >= 0 }?.let { KEY_NAMES[it] },
        isMajor = raw.isMajor,
        rms = raw.rms,
        lufs = raw.lufs,
        peakLevel = raw.peak,
        dynamicRange = raw.dynamicRange,
        bassEnergy = raw.bass,
        midEnergy = raw.mid,
        trebleEnergy = raw.treble,
        energy = perceptual.energy,
        danceability = perceptual.danceability,
        acousticness = perceptual.acousticness,
        instrumentalness = perceptual.instrumentalness,
        brightness = perceptual.brightness,
        warmth = perceptual.warmth,
        aggressiveness = perceptual.aggressiveness,
        calmness = perceptual.calmness,
        happiness = perceptual.happiness,
        sadness = perceptual.sadness,
        tension = perceptual.tension,
        embeddingBlob = EmbeddingCodec.encode(embedding),
    )

   private companion object {
        val KEY_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        const val MAX_ANALYZED_US = 4L * 60 * 1_000_000 // 4 minutes
        const val PLAYBACK_IDLE_POLL_MS = 3_000L
    }
}
