import androidx.media3.common.*
import androidx.media3.common.audio.AudioProcessor
import com.harmony.playback.service.player.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

// The crossfade is covered by playback/service's TwinPlayerTest, which runs in CI.

fun main() {
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
