package com.harmony.core.media.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor

/**
 * The phone's microphone as plainly as Android will give it: the unprocessed
 * input where the phone offers one, else voice recognition, else the plain
 * microphone, with any noise suppression, gain control or echo cancelling
 * the phone attaches switched off. Measuring sound needs the sound as it is,
 * not tidied up for a phone call.
 *
 * Needs RECORD_AUDIO; [open] throws [SecurityException] without it.
 */
class MicrophoneReader private constructor(
    private val record: AudioRecord,
    val sampleRate: Int,
    private val effects: List<AudioEffect>,
) : AutoCloseable {

    fun start() = record.startRecording()

    /** Blocks until [buffer] is filled (or the input stops); returns the samples read, negative on error. */
    fun read(buffer: ShortArray, size: Int = buffer.size): Int {
        var filled = 0
        while (filled < size) {
            val n = record.read(buffer, filled, size - filled)
            if (n < 0) return n
            if (n == 0) break
            filled += n
        }
        return filled
    }

    override fun close() {
        runCatching { record.stop() }
        record.release()
        effects.forEach { runCatching { it.release() } }
    }

    companion object {
        private val RATES = intArrayOf(48_000, 44_100, 16_000)

        @SuppressLint("MissingPermission")
        fun open(context: Context): MicrophoneReader {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val unprocessed = runCatching {
                audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
            }.getOrDefault(false)
            val sources = buildList {
                if (unprocessed) add(MediaRecorder.AudioSource.UNPROCESSED)
                add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                add(MediaRecorder.AudioSource.MIC)
            }
            for (rate in RATES) {
                val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (min <= 0) continue
                for (source in sources) {
                    val record = runCatching {
                        AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 5 * 2))
                    }.getOrElse { if (it is SecurityException) throw it else null } ?: continue
                    if (record.state != AudioRecord.STATE_INITIALIZED) {
                        record.release()
                        continue
                    }
                    return MicrophoneReader(record, rate, withoutVoiceProcessing(record.audioSessionId))
                }
            }
            throw IllegalStateException("The microphone is busy. Close other apps that record and try again.")
        }

        private fun withoutVoiceProcessing(session: Int): List<AudioEffect> = buildList {
            if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(session) }.getOrNull()?.let(::add)
            if (AutomaticGainControl.isAvailable()) runCatching { AutomaticGainControl.create(session) }.getOrNull()?.let(::add)
            if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(session) }.getOrNull()?.let(::add)
            forEach { effect -> runCatching { effect.enabled = false } }
        }
    }
}
