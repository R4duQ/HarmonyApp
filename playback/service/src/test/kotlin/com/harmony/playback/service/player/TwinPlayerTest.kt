package com.harmony.playback.service.player

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.FlagSet
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(UnstableApi::class, ExperimentalCoroutinesApi::class)
class TwinPlayerTest {

    private val songs = listOf("a", "b", "c", "d").map { MediaItem.Builder().setMediaId(it).build() }

    /** Everything a listener of the twin heard, in order. */
    private class Heard : Player.Listener {
        val log = mutableListOf<String>()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            log += "transition ${mediaItem?.mediaId} $reason"
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            log += "playing $isPlaying"
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            log += "pwr $playWhenReady $reason"
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            log += "state $playbackState"
        }
        override fun onVolumeChanged(volume: Float) {
            log += "volume $volume"
        }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            log += "discontinuity $reason"
        }
        fun transitions() = log.filter { it.startsWith("transition") }
    }

    private class Rig(val scope: TestScope, val twin: TwinPlayer, val a: FakeExo, val b: FakeExo, val heard: Heard)

    private fun rig(
        crossfade: Int = 4,
        durations: Map<String, Long> = mapOf("a" to 20_000L, "b" to 20_000L, "c" to 20_000L, "d" to 20_000L),
        /** How long the second player takes to load a song. */
        loadMs: Long = 300,
        test: suspend Rig.() -> Unit,
    ) = runTest {
        val a = FakeExo("A", durations, 300)
        val b = FakeExo("B", durations, loadMs)
        a.focus = true
        val twin = TwinPlayer(a.player, b.player, StandardTestDispatcher(testScheduler))
        val heard = Heard()
        twin.addListener(heard)
        twin.crossfadeSeconds = crossfade
        // The players' clocks run on the test's virtual time.
        val clock = backgroundScope.launch {
            while (true) {
                delay(TICK_MS)
                a.advance(TICK_MS)
                b.advance(TICK_MS)
            }
        }
        try {
            Rig(this, twin, a, b, heard).test()
        } finally {
            // Its clock would otherwise keep the test's scheduler busy forever.
            twin.release()
            clock.cancel()
        }
    }

    private suspend fun Rig.start(index: Int = 0) {
        twin.setMediaItems(songs, index, 0)
        twin.prepare()
        twin.play()
        scope.advanceTimeBy(1_000)
        scope.runCurrent()
    }

    /** Moves virtual time on to [ms] into the active song. */
    private fun Rig.at(ms: Long) {
        val active = if (twin.active === a.player) a else b
        val left = ms - active.position
        if (left > 0) scope.advanceTimeBy(left + 1)
        scope.runCurrent()
    }

    @Test
    fun `the next song is loaded ahead, faded in, and simply takes over`() = rig {
        start()
        assertEquals(1f, twin.volume)
        // 4 s fade + 6 s of loading ahead: nothing before 10 s from the end.
        at(9_000)
        assertEquals(0, b.prepares)
        at(10_500)
        assertEquals(1, b.prepares)
        assertEquals(1, b.index)
        assertEquals(0f, b.volume)
        assertFalse(b.playWhenReady)
        // The fade: both play, the incoming one rising as the outgoing one falls.
        at(18_000)
        assertTrue(b.isPlaying)
        assertTrue(b.volume in 0.3f..0.7f)
        assertEquals(1f, a.volume + b.volume, 0.05f)
        // The turn: no seek, no reload, the incoming player just goes on.
        at(20_100)
        assertSame(b.player, twin.active)
        assertEquals(1f, b.volume)
        assertTrue(b.isPlaying)
        assertEquals(1, b.seeks)
        assertEquals(1, b.prepares)
        assertEquals(Player.STATE_IDLE, a.state)
        assertFalse(a.focus)
        assertTrue(b.focus)
        assertEquals(1, twin.currentMediaItemIndex)
        // Listeners heard one song follow another, and never a pause.
        assertEquals(listOf("transition a 3", "transition b ${Player.MEDIA_ITEM_TRANSITION_REASON_AUTO}"), heard.transitions())
        assertFalse(heard.log.contains("playing false"))
        assertTrue(heard.log.contains("discontinuity ${Player.DISCONTINUITY_REASON_AUTO_TRANSITION}"))
        assertFalse(heard.log.any { it.startsWith("volume") })
    }

    @Test
    fun `turns alternate between the two players`() = rig {
        start()
        at(20_100)
        assertSame(b.player, twin.active)
        at(20_100)
        assertSame(a.player, twin.active)
        assertEquals(2, a.index)
        assertEquals(2, twin.currentMediaItemIndex)
        assertEquals(1f, a.volume)
        assertEquals(2, a.prepares)
        assertEquals(Player.STATE_IDLE, b.state)
    }

    @Test
    fun `the waiting player's events never reach listeners`() = rig {
        start()
        val before = heard.log.size
        at(10_500)
        // Loaded: its seek, state changes and transition were its own business.
        assertEquals(before, heard.log.size)
    }

    @Test
    fun `pausing during the fade calls it off, and playing again fades again`() = rig {
        start()
        at(18_000)
        assertTrue(b.isPlaying)
        twin.pause()
        scope.runCurrent()
        assertEquals(Player.STATE_IDLE, b.state)
        assertEquals(1f, a.volume)
        assertFalse(a.pauseAtEnd)
        twin.play()
        scope.advanceTimeBy(300)
        scope.runCurrent()
        assertEquals(2, b.prepares)
        at(20_100)
        assertSame(b.player, twin.active)
        assertEquals(1f, b.volume)
    }

    @Test
    fun `a pause the player makes itself, for a call or unplugged headphones, calls the fade off too`() = rig {
        start()
        at(18_000)
        assertTrue(b.isPlaying)
        // Not through the twin: ExoPlayer pausing on its own.
        a.player.pause()
        scope.runCurrent()
        assertEquals(Player.STATE_IDLE, b.state)
        assertFalse(b.playWhenReady)
        assertEquals(1f, a.volume)
        assertFalse(a.pauseAtEnd)
    }

    @Test
    fun `a stall in the outgoing song calls the fade off`() = rig {
        start()
        at(18_000)
        assertTrue(b.isPlaying)
        // Still meant to play, but not playing: a brief loss of audio focus, or a slow read.
        a.stall(500)
        scope.runCurrent()
        assertTrue(a.playWhenReady)
        assertEquals(Player.STATE_IDLE, b.state)
        assertEquals(1f, a.volume)
    }

    @Test
    fun `a seek calls the fade off and the song is loaded again when due`() = rig {
        start()
        at(17_000)
        twin.seekTo(2_000)
        scope.runCurrent()
        assertEquals(Player.STATE_IDLE, b.state)
        assertEquals(1f, a.volume)
        at(10_500)
        assertEquals(2, b.prepares)
    }

    @Test
    fun `no second player for repeat one, the last song, or crossfade off`() = rig(crossfade = 0) {
        start()
        at(19_000)
        assertEquals(0, b.prepares)
        twin.crossfadeSeconds = 4
        twin.repeatMode = Player.REPEAT_MODE_ONE
        assertEquals(Player.REPEAT_MODE_ONE, b.repeat)
        at(20_100)
        at(19_000)
        assertEquals(0, b.prepares)
        twin.repeatMode = Player.REPEAT_MODE_OFF
        twin.seekTo(3, 0)
        scope.runCurrent()
        at(19_500)
        assertEquals(0, b.prepares)
    }

    @Test
    fun `a song that won't load ends without a fade, and isn't tried again`() = rig {
        b.failing += "b"
        start()
        at(12_000)
        assertEquals(1, b.prepares)
        assertEquals(Player.STATE_IDLE, b.state)
        at(19_000)
        assertEquals(1, b.prepares)
        assertEquals(1f, a.volume)
        // A plays on into it by itself, as one player always did.
        at(20_100)
        assertSame(a.player, twin.active)
        assertEquals(1, a.index)
    }

    @Test
    fun `the queue changes in both, and an append mid-fade keeps the fade`() = rig {
        start()
        at(17_000)
        val extra = MediaItem.Builder().setMediaId("e").build()
        twin.addMediaItem(extra)
        assertEquals(5, a.items.size)
        assertEquals(a.items, b.items)
        assertTrue(b.isPlaying)
        twin.removeMediaItem(3)
        assertEquals(a.items, b.items)
        assertTrue(b.isPlaying)
        at(20_100)
        assertSame(b.player, twin.active)
        // Removing the song coming next calls the fade off.
        at(17_000)
        assertTrue(a.isPlaying)
        twin.removeMediaItem(2)
        scope.runCurrent()
        assertEquals(Player.STATE_IDLE, a.state)
        assertEquals(1f, b.volume)
        assertEquals(a.items, b.items)
    }

    @Test
    fun `both players shuffle in the same order`() = rig {
        start()
        twin.shuffleModeEnabled = true
        assertTrue(a.shuffle && b.shuffle)
        assertSame(a.order, b.order)
        assertEquals(4, a.order!!.length)
        twin.addMediaItems(listOf(MediaItem.Builder().setMediaId("e").build(), MediaItem.Builder().setMediaId("f").build()))
        assertSame(a.order, b.order)
        assertEquals(6, a.order!!.length)
        val order = a.order
        twin.repeatMode = Player.REPEAT_MODE_ALL
        assertSame(order, a.order)
        twin.removeMediaItems(4, 6)
        assertSame(a.order, b.order)
        assertEquals(4, b.order!!.length)
        twin.setMediaItems(songs)
        assertNotSame(order, a.order)
        assertSame(a.order, b.order)
    }

    @Test
    fun `a volume set from outside stays under the fade`() = rig {
        start()
        twin.volume = 0.5f
        assertEquals(0.5f, a.volume)
        assertEquals(listOf("volume 0.5"), heard.log.filter { it.startsWith("volume") })
        at(18_000)
        assertTrue(a.volume + b.volume in 0.45f..0.55f)
        at(20_100)
        assertEquals(0.5f, b.volume)
        assertEquals(0.5f, twin.volume)
    }

    @Test
    fun `a slow load still hands over, with the outgoing song at full volume until it starts`() =
        rig(loadMs = 12_000) {
            start()
            at(17_000)
            assertFalse(b.isPlaying)
            assertEquals(1f, a.volume)
            at(20_100)
            assertSame(b.player, twin.active)
            assertEquals(1f, b.volume)
            assertTrue(b.playWhenReady)
            assertFalse(heard.log.contains("pwr false ${Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM}"))
        }

    /** A stand-in ExoPlayer: one queue, a position that moves with the test's clock, and loading that takes a while. */
    private class FakeExo(val name: String, val durations: Map<String, Long>, val loadMs: Long) {
        val items = mutableListOf<MediaItem>()
        val failing = mutableSetOf<String>()
        var index = 0
        var position = 0L
        var playWhenReady = false
        var state = Player.STATE_IDLE
        var volume = 1f
        var repeat = Player.REPEAT_MODE_OFF
        var shuffle = false
        var order: ShuffleOrder? = null
        var pauseAtEnd = false
        var focus = false
        var params = PlaybackParameters.DEFAULT
        var loadLeft = 0L
        var prepares = 0
        var seeks = 0
        val listeners = mutableListOf<Player.Listener>()

        val isPlaying get() = playWhenReady && state == Player.STATE_READY

        val player: ExoPlayer = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java),
        ) { proxy, method, args -> call(proxy as Player, method.name, args ?: emptyArray(), method.returnType) } as ExoPlayer

        fun duration() = items.getOrNull(index)?.let { durations[it.mediaId] } ?: C.TIME_UNSET

        fun nextIndex(): Int {
            if (items.isEmpty()) return C.INDEX_UNSET
            if (repeat == Player.REPEAT_MODE_ONE) return index
            val o = order
            val next = if (shuffle && o != null) o.getNextIndex(index) else (index + 1).takeIf { it < items.size } ?: C.INDEX_UNSET
            if (next != C.INDEX_UNSET) return next
            return if (repeat == Player.REPEAT_MODE_ALL) (if (shuffle && o != null) o.firstIndex else 0) else C.INDEX_UNSET
        }

        private fun emit(vararg flags: Int, each: (Player.Listener) -> Unit) {
            val events = Player.Events(FlagSet.Builder().addAll(*flags).build())
            for (l in listeners.toList()) {
                each(l)
                l.onEvents(player, events)
            }
        }

        private fun setPlaying(pwr: Boolean, reason: Int) {
            if (pwr == playWhenReady) return
            val was = isPlaying
            playWhenReady = pwr
            emit(Player.EVENT_PLAY_WHEN_READY_CHANGED) { it.onPlayWhenReadyChanged(pwr, reason) }
            if (was != isPlaying) emit(Player.EVENT_IS_PLAYING_CHANGED) { it.onIsPlayingChanged(isPlaying) }
        }

        private fun changeState(new: Int) {
            if (new == state) return
            val was = isPlaying
            state = new
            emit(Player.EVENT_PLAYBACK_STATE_CHANGED) { it.onPlaybackStateChanged(new) }
            if (was != isPlaying) emit(Player.EVENT_IS_PLAYING_CHANGED) { it.onIsPlayingChanged(isPlaying) }
        }

        private fun moveTo(newIndex: Int, newPosition: Long, reason: Int) {
            val changed = newIndex != index
            index = newIndex
            position = newPosition
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                seeks++
                emit(Player.EVENT_POSITION_DISCONTINUITY) {
                    it.onPositionDiscontinuity(info(), info(), Player.DISCONTINUITY_REASON_SEEK)
                }
            }
            if (changed || reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                emit(Player.EVENT_MEDIA_ITEM_TRANSITION) { it.onMediaItemTransition(items.getOrNull(index), reason) }
            }
        }

        private fun info() = Player.PositionInfo(null, index, items.getOrNull(index), null, 0, position, position, -1, -1)

        fun stall(ms: Long) {
            loadLeft = ms
            changeState(Player.STATE_BUFFERING)
        }

        fun advance(ms: Long) {
            if (state == Player.STATE_BUFFERING) {
                loadLeft -= ms
                if (loadLeft <= 0) {
                    val item = items.getOrNull(index)
                    if (item != null && item.mediaId in failing) {
                        state = Player.STATE_IDLE
                        emit(Player.EVENT_PLAYER_ERROR) {
                            it.onPlayerError(PlaybackException("unreadable", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
                        }
                    } else {
                        changeState(Player.STATE_READY)
                    }
                }
                return
            }
            if (!isPlaying) return
            position += (ms * params.speed).toLong()
            val duration = duration()
            if (duration <= 0 || position < duration) return
            if (pauseAtEnd) {
                position = duration
                setPlaying(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
                return
            }
            val next = nextIndex()
            if (next == C.INDEX_UNSET) {
                position = duration
                changeState(Player.STATE_ENDED)
            } else {
                moveTo(next, position - duration, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun call(self: Player, name: String, args: Array<Any?>, returns: Class<*>): Any? = when (name) {
            "addListener" -> { listeners += args[0] as Player.Listener; null }
            "removeListener" -> { listeners.remove(args[0]); null }
            "getCurrentPosition", "getContentPosition" -> position
            "getDuration", "getContentDuration" -> if (state == Player.STATE_IDLE && prepares == 0) C.TIME_UNSET else duration()
            "isPlaying" -> isPlaying
            "getPlayWhenReady" -> playWhenReady
            "getPlaybackState" -> state
            "getVolume" -> volume
            "setVolume" -> {
                volume = args[0] as Float
                emit(Player.EVENT_VOLUME_CHANGED) { it.onVolumeChanged(volume) }
                null
            }
            "getRepeatMode" -> repeat
            "setRepeatMode" -> { repeat = args[0] as Int; emit(Player.EVENT_REPEAT_MODE_CHANGED) { it.onRepeatModeChanged(repeat) }; null }
            "getShuffleModeEnabled" -> shuffle
            "setShuffleModeEnabled" -> {
                shuffle = args[0] as Boolean
                emit(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED) { it.onShuffleModeEnabledChanged(shuffle) }
                null
            }
            "setShuffleOrder" -> {
                val o = args[0] as ShuffleOrder
                check(o.length == items.size) { "$name: order of ${o.length} for ${items.size} items" }
                order = o
                emit(Player.EVENT_TIMELINE_CHANGED) { it.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) }
                null
            }
            "getMediaItemCount" -> items.size
            "getMediaItemAt" -> items[args[0] as Int]
            "getCurrentMediaItemIndex", "getCurrentWindowIndex" -> index
            "getCurrentPeriodIndex" -> 0
            "getCurrentMediaItem" -> items.getOrNull(index)
            "getNextMediaItemIndex" -> nextIndex()
            "hasNextMediaItem" -> nextIndex() != C.INDEX_UNSET
            "isCurrentMediaItemLive" -> false
            "getPlaybackParameters" -> params
            "setPlaybackParameters" -> { params = args[0] as PlaybackParameters; null }
            "setPlaybackSpeed" -> { params = PlaybackParameters(args[0] as Float); null }
            "setPauseAtEndOfMediaItems" -> { pauseAtEnd = args[0] as Boolean; null }
            "getPauseAtEndOfMediaItems" -> pauseAtEnd
            "setPlayWhenReady" -> { setPlaying(args[0] as Boolean, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST); null }
            "play" -> { setPlaying(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST); null }
            "pause" -> { setPlaying(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST); null }
            "prepare" -> {
                if (state == Player.STATE_IDLE && items.isNotEmpty()) {
                    prepares++
                    loadLeft = loadMs
                    changeState(Player.STATE_BUFFERING)
                }
                null
            }
            "stop" -> { changeState(Player.STATE_IDLE); null }
            "seekTo" -> {
                if (args.size == 1) moveTo(index, args[0] as Long, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
                else moveTo(args[0] as Int, args[1] as Long, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
                if (state != Player.STATE_IDLE && state != Player.STATE_BUFFERING) {
                    loadLeft = 50
                    changeState(Player.STATE_BUFFERING)
                }
                null
            }
            "setMediaItems" -> {
                items.clear()
                items += args[0] as List<MediaItem>
                order = null
                val start = if (args.size == 3) args[1] as Int else 0
                emit(Player.EVENT_TIMELINE_CHANGED) { it.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) }
                index = start
                position = if (args.size == 3) (args[2] as Long).coerceAtLeast(0) else 0
                emit(Player.EVENT_MEDIA_ITEM_TRANSITION) {
                    it.onMediaItemTransition(items.getOrNull(index), Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
                }
                null
            }
            "addMediaItem", "addMediaItems" -> {
                val at = if (args.size == 2) args[0] as Int else items.size
                val added = args.last().let { if (it is MediaItem) listOf(it) else it as List<MediaItem> }
                items.addAll(at, added)
                if (at <= index && items.size > added.size) index += added.size
                // Each real ExoPlayer inserts into its own order at random.
                order = order?.cloneAndInsert(at, added.size)
                emit(Player.EVENT_TIMELINE_CHANGED) { it.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) }
                null
            }
            "removeMediaItem", "removeMediaItems" -> {
                val from = args[0] as Int
                val to = if (args.size == 2) args[1] as Int else from + 1
                val removingCurrent = index in from until to
                repeat(to - from) { items.removeAt(from) }
                order = order?.cloneAndRemove(from, to)
                if (index >= to) index -= to - from
                emit(Player.EVENT_TIMELINE_CHANGED) { it.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) }
                if (removingCurrent) {
                    index = from.coerceAtMost(items.size - 1)
                    position = 0
                    emit(Player.EVENT_MEDIA_ITEM_TRANSITION) {
                        it.onMediaItemTransition(items.getOrNull(index), Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
                    }
                }
                null
            }
            "getCurrentTimeline" -> Timeline.EMPTY
            "getCurrentTracks" -> Tracks.EMPTY
            "getMediaMetadata" -> MediaMetadata.EMPTY
            "getAvailableCommands" -> Player.Commands.EMPTY
            "getAudioAttributes" -> AudioAttributes.DEFAULT
            "setAudioAttributes" -> { focus = args[1] as Boolean; null }
            "release" -> null
            "toString" -> "FakeExo($name)"
            "hashCode" -> System.identityHashCode(self)
            "equals" -> self === args[0]
            else -> when (returns) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                else -> null
            }
        }
    }

    private companion object {
        const val TICK_MS = 10L
    }
}
