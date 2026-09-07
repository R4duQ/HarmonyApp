package com.harmony.domain.playback.usecase

import com.harmony.core.model.Song
import com.harmony.domain.playback.PlaybackController
import javax.inject.Inject

/**
 * Entry point for "user tapped a song in a list": sets the surrounding list
 * as the queue and starts at the tapped item. Later phases route Smart
 * Shuffle through here as well (the controller decides successor tracks).
 */
class PlaySongsUseCase @Inject constructor(
    private val controller: PlaybackController,
) {
    suspend operator fun invoke(songs: List<Song>, startIndex: Int) {
        require(startIndex in songs.indices) { "startIndex out of bounds" }
        controller.setQueue(songs, startIndex)
    }
}
