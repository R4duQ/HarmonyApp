package com.harmony.data.analysis

import com.harmony.core.database.dao.AnalysisDao
import com.harmony.core.model.MoodFilter
import com.harmony.core.model.Song
import com.harmony.domain.library.repository.LibraryRepository
import com.harmony.domain.shuffle.engine.MoodProfiles
import com.harmony.domain.shuffle.repository.MoodRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rows come from Room (live query — mood lists grow as analysis progresses),
 * scoring is pure Kotlin in MoodProfiles, and song resolution preserves score
 * order. Scoring ~20k rows is a few hundred thousand float ops per emission;
 * cheap, and emissions are rare after the initial analysis wave.
 */
@Singleton
class MoodRepositoryImpl @Inject constructor(
    private val analysisDao: AnalysisDao,
    private val libraryRepository: LibraryRepository,
) : MoodRepository {

    override fun observeSongsForMood(mood: MoodFilter, limit: Int): Flow<List<Song>> =
        analysisDao.observePerceptualRows().map { rows ->
            val ranked = rows.asSequence()
                .map { it.songId to MoodProfiles.score(mood, it.toAxes()) }
                .filter { it.second >= MoodProfiles.MEMBERSHIP_THRESHOLD }
                .sortedByDescending { it.second }
                .take(limit)
                .map { it.first }
                .toList()
            libraryRepository.songsByIds(ranked)
        }

    override suspend fun topSongForMood(mood: MoodFilter): Song? {
        val rows = analysisDao.observePerceptualRows().first()
        val best = rows.maxByOrNull { MoodProfiles.score(mood, it.toAxes()) } ?: return null
        return libraryRepository.songById(best.songId)
    }

    private fun AnalysisDao.PerceptualRow.toAxes() = floatArrayOf(
        energy, danceability, acousticness, instrumentalness, brightness,
        warmth, aggressiveness, calmness, happiness, sadness, tension,
    )
}
