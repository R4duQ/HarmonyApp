package com.harmony.feature.equalizer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.harmony.core.media.audio.MicrophoneReader
import com.harmony.core.model.AutoEqDesign
import com.harmony.core.model.OctaveBandMeter
import com.harmony.core.model.PinkNoise
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Measures a speaker in its room with the phone's microphone.
 *
 * Two seconds of silence first, to learn the room's own noise; then about
 * eight seconds of pink noise through whatever media is playing on, while
 * the microphone listens. The first two seconds of the noise are skipped
 * (it fades in, and Bluetooth speakers start late), the rest is measured in
 * octave bands and compared with what was sent: see AutoEqDesign.roomCorrection.
 *
 * Blocking audio runs on the IO dispatcher; cancelling the coroutine stops
 * the sound and the microphone.
 */
class RoomCalibrator(private val context: Context) {

    suspend fun run(onStep: (CalibrationStep) -> Unit): AutoEqDesign.RoomResult = withContext(Dispatchers.IO) {
        MicrophoneReader.open(context).use { mic ->
            val rate = mic.sampleRate
            val block = ShortArray(rate / 10)
            mic.start()

            // 1. The room in silence (the first fifth of a second is the input switching on).
            mic.read(block, rate / 5)
            val ambient = OctaveBandMeter(rate)
            val silenceBlocks = (SILENCE_SECONDS * 10).roundToInt()
            for (b in 0 until silenceBlocks) {
                currentCoroutineContext().ensureActive()
                val n = mic.read(block)
                if (n <= 0) error("The microphone stopped unexpectedly.")
                ambient.add(block, 0, n)
                onStep(CalibrationStep.Silence((b + 1f) / silenceBlocks))
            }

            // 2. The test sound, and the microphone listening to it.
            coroutineScope {
                val played = async(Dispatchers.IO) { play() }
                val heard = OctaveBandMeter(rate)
                var clippedSamples = 0
                var measured = 0
                val totalBlocks = (PLAY_SECONDS * 10).roundToInt()
                val skipBlocks = (SKIP_SECONDS * 10).roundToInt()
                for (b in 0 until totalBlocks) {
                    currentCoroutineContext().ensureActive()
                    val n = mic.read(block)
                    if (n <= 0) error("The microphone stopped unexpectedly.")
                    var peak = 0
                    for (k in 0 until n) peak = maxOf(peak, abs(block[k].toInt()))
                    if (b >= skipBlocks) {
                        heard.add(block, 0, n)
                        for (k in 0 until n) if (abs(block[k].toInt()) >= CLIP) clippedSamples++
                        measured += n
                    }
                    onStep(CalibrationStep.Playing((b + 1f) / totalBlocks, peak / 32768f))
                }
                val playedDb = played.await()
                val clipped = measured > 0 && clippedSamples > measured / 1000
                AutoEqDesign.roomCorrection(playedDb, heard.levelsDb(), ambient.levelsDb(), clipped)
            }
        }
    }

    /** Plays the pink noise and returns its own octave-band levels over the measured part. */
    private suspend fun play(): FloatArray {
        val rate = PLAY_RATE
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(min, rate / 5 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val noise = PinkNoise(seed = 2024)
        val meter = OctaveBandMeter(rate)
        val total = ((PLAY_SECONDS + TAIL_SECONDS) * rate).roundToInt()
        val fadeIn = (FADE_IN_SECONDS * rate).roundToInt()
        val fadeOut = rate / 10
        val measureFrom = (SKIP_SECONDS * rate).roundToInt()
        val chunk = ShortArray(rate / 20)
        try {
            track.play()
            var n = 0
            while (n < total) {
                currentCoroutineContext().ensureActive()
                val count = minOf(chunk.size, total - n)
                for (k in 0 until count) {
                    val i = n + k
                    val gain = when {
                        i < fadeIn -> i.toFloat() / fadeIn
                        i > total - fadeOut -> (total - i).toFloat() / fadeOut
                        else -> 1f
                    }
                    val x = noise.next()
                    if (i >= measureFrom && i < total - fadeOut) meter.add(x)
                    chunk[k] = (x * gain * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
                }
                if (track.write(chunk, 0, count) < 0) error("The test sound couldn't be played.")
                n += count
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
        return meter.levelsDb()
    }

    private companion object {
        const val PLAY_RATE = 48_000
        const val SILENCE_SECONDS = 2f
        const val PLAY_SECONDS = 8f
        const val SKIP_SECONDS = 2f
        const val FADE_IN_SECONDS = 0.5f
        /** A little extra sound so the microphone's last block still hears it over a Bluetooth delay. */
        const val TAIL_SECONDS = 0.5f
        const val CLIP = 32_000
    }
}
