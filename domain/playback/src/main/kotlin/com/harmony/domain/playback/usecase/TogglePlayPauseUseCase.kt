package com.harmony.domain.playback.usecase

import com.harmony.domain.playback.PlaybackController
import javax.inject.Inject

class TogglePlayPauseUseCase @Inject constructor(
    private val controller: PlaybackController,
) {
    operator fun invoke() {
        if (controller.playerState.value.isPlaying) controller.pause() else controller.play()
    }
}
