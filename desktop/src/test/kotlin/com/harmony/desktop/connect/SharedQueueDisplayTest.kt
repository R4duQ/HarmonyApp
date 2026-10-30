package com.harmony.desktop.connect

import com.harmony.core.remote.PlayRequest
import com.harmony.core.remote.RemoteTrack
import com.harmony.core.remote.queue.MediaRef
import com.harmony.core.remote.queue.Origin
import com.harmony.core.remote.queue.QueueEntry
import com.harmony.core.remote.queue.QueueState
import com.harmony.desktop.engine.AudioEngine
import com.harmony.desktop.engine.CountingSink
import com.harmony.desktop.engine.DecodeStream
import com.harmony.desktop.engine.Decoder
import com.harmony.desktop.player.Art
import com.harmony.desktop.player.PlayerController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Universal Queue, stage 3b: the computer's queue panel shows the queue the phone shares. */
class SharedQueueDisplayTest {
    private class SilentDecoder : Decoder {
        override fun open(source: String, startMs: Long, sampleRate: Int, channels: Int) = object : DecodeStream {
            override val input = java.io.ByteArrayInputStream(ByteArray(sampleRate * channels * 2 * 30))
            override fun finish(): String? = null
            override fun close() {}
        }
    }

    private val engine = AudioEngine({ CountingSink() }, SilentDecoder())
    private val player = PlayerController(engine)

    @After fun tearDown() = engine.stop()

    private fun track(id: String, art: String? = null) = RemoteTrack(id, "Song $id", "Artist", "Album", 60_000, "http://192.0.2.1/$id", art)

    private fun entry(id: String, origin: Origin, priority: Boolean = false, pinned: Boolean = false) =
        QueueEntry("e$id", MediaRef("dev:phone:$id", "phone", id, "Song $id", "Artist"), origin, pinned, priority)

    private fun shared(current: String) = QueueState(
        entries = listOf(
            entry("9", Origin.LIST), entry(current, Origin.LIST), entry("11", Origin.MANUAL, priority = true),
            entry("12", Origin.LIST), entry("14", Origin.MANUAL), entry("13", Origin.AUTO), entry("15", Origin.AUTO, pinned = true),
        ),
        currentId = "e$current",
        owner = "phone",
    )

    private fun upNext() = player.nowPlaying.value!!.upNext

    @Test fun theWholeSharedQueueShowsWithWhereEachSongCameFrom() {
        player.play(PlayRequest(track("10"), 0, playing = false, phoneName = "Phone", upNext = listOf(track("11", art = "http://192.0.2.1/art/11"))))
        assertEquals(listOf("Song 11"), upNext().map { it.title })

        player.sharedQueue(shared(current = "10"))
        assertEquals(listOf("Song 11", "Song 12", "Song 14", "Song 13", "Song 15"), upNext().map { it.title })
        assertEquals(listOf("Next up", null, "Queued", "Recommended", null), upNext().map { it.label })
        assertEquals("the cover the phone shared", Art.Url("http://192.0.2.1/art/11"), upNext()[0].art)
    }

    @Test fun aSharedQueueOutOfStepWithTheSongPlayingIsNotShown() {
        player.play(PlayRequest(track("10"), 0, playing = false, phoneName = "Phone", upNext = listOf(track("11"))))
        player.sharedQueue(shared(current = "12"))
        assertEquals("the phone's own up next until the shared queue catches up", listOf("Song 11"), upNext().map { it.title })
        player.sharedQueue(shared(current = "10"))
        assertEquals(5, upNext().size)
        player.sharedQueue(null)
        assertEquals("switched off: back to the phone's up next", listOf("Song 11"), upNext().map { it.title })
    }

    @Test fun editsInThePanelBecomeSharedQueueOps() {
        val sent = mutableListOf<com.harmony.core.remote.queue.QueueOp>()
        player.onSharedEdit = { sent += it }
        player.play(PlayRequest(track("10"), 0, playing = false, phoneName = "Phone"))
        player.sharedQueue(shared(current = "10"))
        assertEquals(listOf("e11", "e12", "e14", "e13", "e15"), upNext().map { it.entryId })
        player.sharedPlayNext("e13")
        player.sharedMoveUp("e14") // third: after the first
        player.sharedMoveUp("e12") // second: right after the song playing
        player.sharedMoveUp("e11") // first already
        player.sharedMoveDown("e12")
        player.sharedMoveDown("e15") // last already
        player.sharedRemove("e14")
        player.sharedPlay("e13")
        assertEquals(
            listOf(
                com.harmony.core.remote.queue.QueueOp.Move("e13", null),
                com.harmony.core.remote.queue.QueueOp.Move("e14", "e11"),
                com.harmony.core.remote.queue.QueueOp.Move("e12", null),
                com.harmony.core.remote.queue.QueueOp.Move("e12", "e14"),
                com.harmony.core.remote.queue.QueueOp.Remove(listOf("e14")),
                com.harmony.core.remote.queue.QueueOp.SetCurrent("e13"),
            ),
            sent,
        )
    }

    @Test fun noEditsWhileTheSharedQueueIsOutOfStep() {
        val sent = mutableListOf<com.harmony.core.remote.queue.QueueOp>()
        player.onSharedEdit = { sent += it }
        player.play(PlayRequest(track("10"), 0, playing = false, phoneName = "Phone"))
        player.sharedQueue(shared(current = "12"))
        player.sharedPlayNext("e13")
        player.sharedQueue(null)
        player.sharedRemove("e13")
        assertEquals(emptyList<com.harmony.core.remote.queue.QueueOp>(), sent)
    }
}
