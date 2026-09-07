package com.harmony.data.analysis.decode

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Decodes any platform-supported audio file to mono float PCM chunks and
 * feeds them to [onPcm] as they are produced.
 *
 * Decoding stays in Kotlin (per the agreed split): MediaCodec gives us every
 * format the device can play — including hardware-assisted decode — for free,
 * which a native decoder stack (FFmpeg) would re-implement at 10x the binary
 * size. The native side only ever sees clean mono float PCM.
 *
 * Downmix: channels are averaged. For analysis this is correct — we want the
 * overall sonic character, and mid-channel content dominates perception.
 *
 * Handles both PCM_16 and PCM_FLOAT decoder output (OEM decoders differ).
 * Synchronous MediaCodec loop on the caller's thread: the analysis worker
 * runs each song on one worker thread, so callback-mode complexity buys
 * nothing here.
 */
@Singleton
class AudioDecoder @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    class DecodeException(message: String) : Exception(message)

    /**
     * @param maxDurationUs analyze at most this much audio (0 = all). Capping
     *  at ~4 minutes keeps very long tracks (DJ mixes, audiobooks) from
     *  monopolizing the battery budget while remaining representative.
     * @param onStart invoked exactly once, with the stream sample rate,
     *  before the first [onPcm] call — callers create their analyzer here.
     */
    suspend fun decode(
        uriString: String,
        maxDurationUs: Long = 0L,
        onStart: (sampleRate: Int) -> Unit,
        onPcm: (samples: FloatArray, count: Int) -> Unit,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(uriString), null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: throw DecodeException("No audio track")

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var startedRate: Int? = null

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var reusable = FloatArray(0)
            var lastOutputAt = System.nanoTime()

            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (System.nanoTime() - lastOutputAt > STALL_TIMEOUT_NS) {
                    throw DecodeException("The audio decoder stopped producing data")
                }
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        val reachedCap = maxDurationUs > 0 && extractor.sampleTime > maxDurationUs
                        if (size < 0 || reachedCap) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outIndex >= 0 -> {
                        if (bufferInfo.size > 0) lastOutputAt = System.nanoTime()
                        val outBuf = codec.getOutputBuffer(outIndex)!!
                        val outFormat = codec.outputFormat
                        // Some decoders report channel changes on the output format.
                        channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (channels !in 1..32) throw DecodeException("Invalid audio channel count")
                        val rate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (rate <= 0) throw DecodeException("Invalid sample rate")
                        if (bufferInfo.size > 0 && startedRate == null) {
                            startedRate = rate
                            onStart(rate)
                        } else if (startedRate != null && startedRate != rate) {
                            throw DecodeException("Sample rate changed during decoding")
                        }
                        val encoding = if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else android.media.AudioFormat.ENCODING_PCM_16BIT
                        if (encoding != android.media.AudioFormat.ENCODING_PCM_16BIT && encoding != android.media.AudioFormat.ENCODING_PCM_FLOAT) {
                            throw DecodeException("Unsupported decoded PCM encoding: $encoding")
                        }
                        val isFloat = encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT

                        val frames: Int
                        if (isFloat) {
                            val fb = outBuf.order(ByteOrder.nativeOrder()).asFloatBuffer()
                            frames = fb.remaining() / channels
                            if (reusable.size < frames) reusable = FloatArray(frames)
                            for (f in 0 until frames) {
                                var acc = 0f
                                for (c in 0 until channels) acc += fb.get(f * channels + c)
                                reusable[f] = acc / channels
                            }
                        } else {
                            val sb = outBuf.order(ByteOrder.nativeOrder()).asShortBuffer()
                            frames = sb.remaining() / channels
                            if (reusable.size < frames) reusable = FloatArray(frames)
                            for (f in 0 until frames) {
                                var acc = 0
                                for (c in 0 until channels) acc += sb.get(f * channels + c).toInt()
                                reusable[f] = acc.toFloat() / channels / 32768f
                            }
                        }
                        if (frames > 0) onPcm(reusable, frames)
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone -> {
                        // Draining; keep looping until EOS flag arrives.
                    }
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val STALL_TIMEOUT_NS = 15_000_000_000L
    }
}
