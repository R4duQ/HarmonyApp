package com.harmony.feature.downloads

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.harmony.domain.online.FingerprintResult
import com.harmony.domain.online.SongFingerprinter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recognises a library file by its sound, for an online mix whose seed the
 * tags didn't find: [FileAudioWindow.SECONDS] of the song, decoded on the
 * phone, turned into a Shazam fingerprint ([ShazamSignature]) and asked
 * about ([ShazamClient]), as the Recognize screen does with the microphone.
 * Only the fingerprint leaves the phone.
 */
@Singleton
class FileFingerprinter @Inject constructor(
    @ApplicationContext private val context: Context,
) : SongFingerprinter {
    override suspend fun identify(uri: String): FingerprintResult {
        val pcm = try {
            withContext(Dispatchers.IO) { FileAudioWindow.read(context, Uri.parse(uri)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return FingerprintResult.Failed("could not decode the file (${e.javaClass.simpleName})")
        } ?: return FingerprintResult.Failed("no audio track")
        val signature = withContext(Dispatchers.Default) {
            ShazamSignature.of(Pcm.normalize(Pcm.resample(pcm.samples, pcm.count, pcm.rate, ShazamSignature.SAMPLE_RATE)))
        }
        if (signature.peakCount == 0) return FingerprintResult.Failed("silent")
        return when (val outcome = ShazamClient.ask(signature)) {
            is RecognitionOutcome.Match -> outcome.song.let { s ->
                FingerprintResult.Found(s.title, s.artist, s.album.ifBlank { null }, s.isrc.ifBlank { null })
            }
            RecognitionOutcome.NoMatch -> FingerprintResult.NotFound
            is RecognitionOutcome.Failed -> FingerprintResult.Failed(outcome.message)
        }
    }
}

/** A stretch of a file's audio as mono 16-bit samples at [rate]. */
internal class PcmWindow(val samples: ShortArray, val count: Int, val rate: Int)

/**
 * Decodes [SECONDS] of a song, from a third of the way in (past an intro or
 * silence, as a listener would hold the phone up to the chorus), downmixed to
 * mono. Plain [MediaExtractor]/[MediaCodec]: whatever the phone plays, it decodes.
 */
internal object FileAudioWindow {
    const val SECONDS = 12

    /** Where the window starts: a third in, but never so late that it runs off the end. */
    fun startUs(durationUs: Long, windowUs: Long = SECONDS * 1_000_000L): Long =
        if (durationUs <= 0) 0 else minOf(durationUs / 3, (durationUs - windowUs).coerceAtLeast(0))

    fun read(context: Context, uri: Uri): PcmWindow? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(track)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            extractor.seekTo(startUs(durationUs), MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val decoder = MediaCodec.createDecoderByType(mime).also { codec = it }
            decoder.configure(format, null, null, 0)
            decoder.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var float = false
            var out = ShortArray(rate * SECONDS)
            var filled = 0
            var inputDone = false
            val info = MediaCodec.BufferInfo()
            var idle = 0
            while (filled < out.size && idle < MAX_IDLE_POLLS) {
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(POLL_US)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, POLL_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = decoder.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        if (filled == 0) out = ShortArray(rate * SECONDS)
                    }
                    outIndex >= 0 -> {
                        idle = 0
                        val buffer = decoder.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        if (float) {
                            val samples = buffer.asFloatBuffer()
                            while (samples.remaining() >= channels && filled < out.size) {
                                var sum = 0f
                                repeat(channels) { sum += samples.get() }
                                out[filled++] = ((sum / channels).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                            }
                        } else {
                            val samples = buffer.asShortBuffer()
                            while (samples.remaining() >= channels && filled < out.size) {
                                var sum = 0
                                repeat(channels) { sum += samples.get() }
                                out[filled++] = (sum / channels).toShort()
                            }
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    else -> idle++
                }
            }
            return if (filled > rate) PcmWindow(out, filled, rate) else null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private const val POLL_US = 10_000L
    /** About two seconds without output: the decoder is stuck, give up. */
    private const val MAX_IDLE_POLLS = 200
}
