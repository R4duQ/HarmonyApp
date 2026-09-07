package com.harmony.domain.shuffle.usecase

import com.harmony.core.model.ShuffleMode
import com.harmony.domain.library.repository.LibraryRepository
import com.harmony.domain.playback.PlaybackController
import com.harmony.domain.shuffle.SmartQueueCoordinator
import javax.inject.Inject

/**
 * "Start Mix" from the Home dashboard.
 *
 * Smart Shuffle is an append-ahead system: [SmartQueueCoordinator] tops the
 * queue up from whatever is currently playing. That means turning the mode on
 * with nothing playing does nothing visible — there is no seed to be similar
 * to. So this seeds playback first when the player is idle, then switches
 * mode, then tells the coordinator an explicit activation happened so it
 * clears the un-played tail and reseeds its session anchor.
 *
 * Deliberately does NOT construct a queue itself: picking a random seed and
 * handing it to the existing controller keeps one queue and one engine, which
 * is exactly what the coordinator already knows how to extend.
 */
class StartSmartMixUseCase @Inject constructor(
    private val playback: PlaybackController,
    private val library: LibraryRepository,
    private val coordinator: SmartQueueCoordinator,
) {
    /** @return false when the library is empty and there is nothing to seed from. */
    suspend operator fun invoke(): Boolean {
        val state = playback.playerState.value
        if (state.currentSong == null) {
            // DB-side random pick; never materialises the song table.
            val seedId = library.randomSongId(emptySet()) ?: return false
            val seed = library.songById(seedId) ?: return false
            playback.setQueue(listOf(seed), startIndex = 0, playWhenReady = true)
        }
        playback.setShuffleMode(ShuffleMode.SMART)
        coordinator.onShuffleActivated()
        return true
    }
}
