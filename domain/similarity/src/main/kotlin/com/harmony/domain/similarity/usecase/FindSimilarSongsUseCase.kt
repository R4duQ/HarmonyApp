package com.harmony.domain.similarity.usecase

import com.harmony.core.model.Song
import com.harmony.domain.library.repository.LibraryRepository
import com.harmony.domain.similarity.model.SimilarityOptions
import com.harmony.domain.similarity.repository.SimilarityRepository
import javax.inject.Inject

/**
 * "Songs like this" — resolves neighbor ids to full Song models in
 * similarity order. Feeds the player screen's suggestions and the
 * long-press "Play similar" action.
 */
class FindSimilarSongsUseCase @Inject constructor(
    private val similarityRepository: SimilarityRepository,
    private val libraryRepository: LibraryRepository,
) {
    suspend operator fun invoke(songId: Long, k: Int = 20): List<Song> {
        val neighbors = similarityRepository.findSimilar(songId, SimilarityOptions(k = k))
        if (neighbors.isEmpty()) return emptyList()
        return libraryRepository.songsByIds(neighbors.map { it.songId })
    }
}
