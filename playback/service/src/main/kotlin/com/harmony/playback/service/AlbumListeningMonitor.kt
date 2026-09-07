package com.harmony.playback.service

import android.os.SystemClock
import androidx.media3.common.Player
import com.harmony.domain.library.repository.AlbumJourneyRepository
import com.harmony.domain.library.repository.ListenedRange
import com.harmony.domain.library.repository.ListeningClock
import com.harmony.domain.library.repository.ListeningCoverage
import kotlinx.coroutines.*

/** Attached to the service's player, so screen-off and notification playback count too. */
internal class AlbumListeningMonitor(private val player: Player, private val albums: AlbumJourneyRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clock = ListeningClock()
    private val pending = mutableMapOf<String, Pair<Long, List<ListenedRange>>>()
    private var writeJob: Job? = null
    private var ticks = 0
    private var durationUri: String? = null
    private var lastDurationMs = 0L

    private val listener = object : Player.Listener {
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            val oldUri = oldPosition.mediaItem?.localConfiguration?.uri?.toString()
            if (oldUri != null) sample(oldUri, oldPosition.positionMs, pending[oldUri]?.first ?: 0)
            clock.reset() // Includes small seeks, repeat-one, crossfade and skipped tracks.
            flush()
            tick()
        }
        override fun onEvents(player: Player, events: Player.Events) {
            tick()
            if (!player.isPlaying) flush()
        }
    }

    fun start() {
        player.addListener(listener)
        tick()
        scope.launch {
            while (isActive) {
                delay(1_000)
                if (player.isPlaying) tick()
                if (++ticks % 10 == 0) flush()
            }
        }
    }
    private fun tick() {
        val uri = player.currentMediaItem?.localConfiguration?.uri?.toString() ?: run { clock.reset(); return }
        sample(uri, player.currentPosition.coerceAtLeast(0), player.duration.coerceAtLeast(0))
    }
    private fun sample(uri: String, position: Long, duration: Long) {
        if (albums.journeys.value.none { a -> !a.reviewed && a.tracks.any { it.uri == uri && !it.heard } }) {
            clock.reset(); return
        }
        if (duration > 0) { durationUri = uri; lastDurationMs = duration }
        val range = clock.sample(uri, position, SystemClock.elapsedRealtime(), player.isPlaying, player.playbackParameters.speed) ?: return
        // A checkpoint may have just emptied pending. Retain the old duration through
        // its final transition so a short intro does not lose its last second.
        val validDuration = duration.takeIf { it > 0 } ?: lastDurationMs.takeIf { durationUri == uri && it > 0 } ?: return
        val ranges = ListeningCoverage.merge(pending[uri]?.second.orEmpty(), range, validDuration)
        pending[uri] = validDuration to ranges
    }
    private fun flush() {
        if (pending.isEmpty()) return
        val snapshot = pending.toMap()
        pending.clear()
        val previous = writeJob
        writeJob = writes.launch {
            previous?.join()
            for ((uri, value) in snapshot) {
                // A storage failure cannot interrupt audio playback; the next pass remains conservative.
                runCatching { albums.recordListening(uri, value.second, value.first) }
            }
        }
    }
    fun stop() {
        tick(); flush()
        player.removeListener(listener)
        scope.cancel()
        runBlocking { withTimeoutOrNull(2_000) { writeJob?.join() } }
        writes.cancel()
    }
}
