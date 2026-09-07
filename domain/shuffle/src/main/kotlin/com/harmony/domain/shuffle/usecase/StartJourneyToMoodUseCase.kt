package com.harmony.domain.shuffle.usecase

import com.harmony.core.model.MoodFilter
import com.harmony.domain.shuffle.SmartQueueCoordinator
import com.harmony.domain.shuffle.repository.MoodRepository
import javax.inject.Inject

/**
 * "Take me to Workout over the next 15 songs": resolves the mood to its
 * strongest song in THIS library and starts a journey there. Anchoring the
 * destination to a real library track (not a synthetic mood vector) keeps
 * the endpoint reachable by definition.
 */
class StartJourneyToMoodUseCase @Inject constructor(
    private val coordinator: SmartQueueCoordinator,
    private val moodRepository: MoodRepository,
) {
    suspend operator fun invoke(mood: MoodFilter, steps: Int = DEFAULT_STEPS): Boolean {
        val destination = moodRepository.topSongForMood(mood) ?: return false
        return coordinator.startJourney(destination.id, steps, mood)
    }

    private companion object {
        const val DEFAULT_STEPS = 15
    }
}