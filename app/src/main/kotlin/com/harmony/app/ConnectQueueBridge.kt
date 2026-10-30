package com.harmony.app

import android.util.Log
import com.harmony.core.model.Song
import com.harmony.core.remote.ConnectClient
import com.harmony.core.remote.queue.MediaRef
import com.harmony.core.remote.queue.MirrorItem
import com.harmony.core.remote.queue.Origin
import com.harmony.core.remote.queue.QueueLink
import com.harmony.core.remote.queue.QueueMirror
import com.harmony.core.remote.queue.QueueReplica
import com.harmony.domain.playback.PlaybackController
import com.harmony.domain.shuffle.SmartQueueManager
import com.harmony.playback.service.connect.ConnectSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Universal Queue, stage 3b: while the phone plays on a computer, its queue is
 * mirrored into the queue the computer shares ([QueueLink], [QueueMirror]), so
 * the computer shows all of it, what the user queued and what Smart Shuffle
 * picked marked as such.
 *
 * Read-only towards the phone: nothing here changes the phone's player or its
 * Connect session. A computer without the shared queue answers 404 and the
 * link stops quietly; a lost link only stops the mirror (the music goes on as
 * it does without it). Edits made on the computer reach the phone in a later
 * stage.
 */
@Singleton
class ConnectQueueBridge @Inject constructor(
    private val connect: ConnectSession,
    private val playback: PlaybackController,
    private val queueManager: SmartQueueManager,
) {
    private data class Player(val queue: List<Song>, val index: Int, val playNext: Int)

    fun start(scope: CoroutineScope) {
        scope.launch {
            connect.queueClient.collectLatest { client -> if (client != null) mirror(client) }
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun mirror(client: ConnectClient) {
        val device = connect.deviceId
        // Bumped by every change of the shared queue as this phone sees it, so the
        // mirror looks again (its first copy arriving, an answer coming back).
        val sharedChanged = MutableStateFlow(0)
        val link = QueueLink(
            client,
            QueueReplica(device),
            onChange = { sharedChanged.update { it + 1 } },
            log = { Log.i(TAG, it) },
        ).start()
        val mirror = QueueMirror(device)
        try {
            val player = playback.playerState
                .map { Player(it.queue, it.queueIndex, it.playNextCount) }
                .distinctUntilChanged()
            combine(player, sharedChanged) { p, _ -> p }
                .debounce(MIRROR_DELAY_MS)
                .collect { p ->
                    if (link.state == QueueLink.State.ENDED || link.state == QueueLink.State.UNSUPPORTED) return@collect
                    // Not in step with the computer yet: its first answer brings us back here.
                    if (link.replica.revision < 0) return@collect
                    val items = items(p, device)
                    val ops = withContext(Dispatchers.Default) { mirror.ops(link.replica.view, items, p.index) }
                    ops.forEach(link::issue)
                }
        } finally {
            link.close()
        }
    }

    /** The phone's queue as the mirror sees it: the Play next run as the user's, Smart Shuffle's picks as automatic. */
    private fun items(p: Player, device: String): List<MirrorItem> {
        val runEnd = p.index + p.playNext
        return p.queue.mapIndexed { i, song ->
            val queued = i in (p.index + 1)..runEnd
            val origin = when {
                queued -> Origin.MANUAL
                queueManager.isPick(song.id) -> Origin.AUTO
                else -> Origin.LIST
            }
            MirrorItem(
                MediaRef(
                    contentKey = "dev:$device:${song.id}",
                    home = device,
                    localId = song.id.toString(),
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    durationMs = song.durationMs,
                ),
                origin,
                priority = queued,
            )
        }
    }

    private companion object {
        const val TAG = "HarmonyQueue"
        /** Gathers a burst of changes (a drag, Smart Shuffle appending) into one look. */
        const val MIRROR_DELAY_MS = 300L
    }
}
