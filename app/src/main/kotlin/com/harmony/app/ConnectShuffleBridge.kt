package com.harmony.app

import com.harmony.core.model.ShuffleMode
import com.harmony.domain.playback.PlaybackController
import com.harmony.domain.shuffle.SmartQueueCoordinator
import com.harmony.playback.service.connect.ConnectSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shuffle between the phone and a Harmony Connect computer. Shuffle pressed
 * on the computer does what the phone's own shuffle button does (Smart
 * Shuffle re-orders the queue, and the new "up next" goes back to the
 * computer); and the computer's shuffle button shows whether the phone
 * shuffles.
 */
@Singleton
class ConnectShuffleBridge @Inject constructor(
    private val connect: ConnectSession,
    private val playback: PlaybackController,
    private val coordinator: SmartQueueCoordinator,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            connect.shuffleRequests.collect { coordinator.toggleShuffle() }
        }
        scope.launch {
            playback.playerState
                .map { it.shuffleMode != ShuffleMode.OFF }
                .distinctUntilChanged()
                .collect { connect.setAppShuffle(it) }
        }
    }
}
