import androidx.media3.common.*
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.ExoPlayer
import com.harmony.playback.service.player.*
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

private class Engine {
    val listeners = mutableListOf<Player.Listener>()
    var items = listOf("a", "b", "c").map { MediaItem.Builder().setMediaId(it).setUri("file:///$it.flac").build() }
    var position = 0L
    var duration = 20_000L
    var index = 0
    var next = 1
    var playing = true
    var ready = true
    var repeat = Player.REPEAT_MODE_OFF
    var state = Player.STATE_READY
    var volume = 1f
    var hold = false
    var released = false
    var playCalls = 0
    var parameters = PlaybackParameters.DEFAULT
    var selected: MediaItem? = null
    var sought: Pair<Int,Long>? = null
    val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, args ->
        when(method.name) {
            "getDuration" -> duration
            "getCurrentPosition" -> position
            "getCurrentMediaItemIndex" -> index
            "getNextMediaItemIndex" -> next
            "getMediaItemCount" -> items.size
            "getMediaItemAt" -> items[args[0] as Int]
            "getRepeatMode" -> repeat
            "isCurrentMediaItemLive" -> false
            "isPlaying" -> playing && ready
            "getPlaybackState" -> state
            "getPlaybackParameters" -> parameters
            "setPlaybackParameters" -> { parameters = args[0] as PlaybackParameters; null }
            "setVolume" -> { volume = args[0] as Float; null }
            "setPauseAtEndOfMediaItems" -> { hold = args[0] as Boolean; null }
            "addListener" -> { listeners += args[0] as Player.Listener; null }
            "removeListener" -> { listeners -= args[0] as Player.Listener; null }
            "setMediaItem" -> { selected = args[0] as MediaItem; null }
            "setPlayWhenReady" -> { playing = args[0] as Boolean; null }
            "prepare" -> null
            "release" -> { released = true; playing = false; null }
            "seekTo" -> { sought = (args[0] as Int) to (args[1] as Long); null }
            "play" -> { playCalls++; playing = true; null }
            "toString" -> "Engine"
            "hashCode" -> System.identityHashCode(this)
            "equals" -> false
            else -> error("Unexpected player method: ${method.name}")
        }
    } as ExoPlayer
}

@OptIn(ExperimentalCoroutinesApi::class)
fun main() = runTest {
    fun controller(main: Engine, preview: Engine) = CrossfadeController(android.content.Context(), main.player, backgroundScope) { preview.player }
    run {
        val main = Engine(); val preview = Engine(); main.position = 15_000; main.next = 2
        val fade = controller(main,preview); fade.crossfadeSeconds = 12; fade.start(); runCurrent()
        check(preview.selected?.mediaId == "c" && main.hold)
        main.playing = false
        main.listeners.toList().forEach { it.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
        check(preview.released && main.volume == 1f && !main.hold && main.playCalls == 0)
        fade.stop()
    }
    run {
        val main = Engine(); val preview = Engine(); main.position = 8_000
        val fade = controller(main,preview); fade.crossfadeSeconds = 12; fade.start(); runCurrent()
        // Advance audio and wall time together: after six seconds a 12s fade is only halfway.
        repeat(6) { main.position += 1_000; advanceTimeBy(1_000); runCurrent() }
        check(main.volume in 0.49f..0.51f)
        fade.crossfadeSeconds = 0
        check(preview.released && main.volume == 1f && !main.hold)
        fade.stop()
    }
    run {
        val main = Engine(); val preview = Engine(); main.index = 2; main.next = 0; main.repeat = Player.REPEAT_MODE_ALL; main.position = 18_000
        val fade = controller(main,preview); fade.crossfadeSeconds = 4; fade.start(); runCurrent()
        check(preview.selected?.mediaId == "a")
        preview.position = 1_900
        main.listeners.toList().forEach { it.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) }
        check(main.sought == (0 to 1_900L) && main.playCalls == 1 && preview.released && !main.hold)
        fade.stop()
    }
    for (kind in 0..2) {
        val main = Engine(); val preview = Engine(); main.position = 18_000
        if (kind == 0) main.next = C.INDEX_UNSET
        if (kind == 1) main.repeat = Player.REPEAT_MODE_ONE
        if (kind == 2) preview.ready = false
        val fade = controller(main,preview); fade.crossfadeSeconds = 4; fade.start(); runCurrent()
        check(!main.hold && main.volume == 1f)
        main.state = Player.STATE_ENDED; fade.stop()
        check(main.playCalls == 0)
    }
    println("PASS: crossfade (shuffle target, pause, full 12-second ramp, disable, repeat-all handoff, final track, repeat-one, slow preview, stop)")
    val gain = ReplayGainAudioProcessor()
    gain.configure(AudioProcessor.AudioFormat(44_100,2,C.ENCODING_PCM_16BIT)); gain.flush()
    check(gain.isActive)
    val source = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
    listOf(-32768,-1000,1000,32767).forEach { source.putShort(it.toShort()) }; source.flip()
    gain.queueInput(source)
    val unity = gain.output.order(ByteOrder.nativeOrder())
    check(List(4){unity.short.toInt()} == listOf(-32768,-1000,1000,32767))
    gain.setGainDb(6.0206f)
    source.clear(); listOf(-20_000,-1000,1000,20_000).forEach { source.putShort(it.toShort()) }; source.flip()
    gain.queueInput(source)
    val amplified = gain.output.order(ByteOrder.nativeOrder())
    check(List(4){amplified.short.toInt()} == listOf(-32768,-2000,2000,32767))
    gain.setGainDb(Float.NaN); source.clear(); source.putShort(1234); source.flip(); gain.queueInput(source)
    check(gain.output.order(ByteOrder.nativeOrder()).short.toInt() == 1234)
    println("PASS: ReplayGain PCM (active at unity, byte-exact bypass, mid-track gain, clipping, invalid metadata)")
}
