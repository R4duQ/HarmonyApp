# core:eq-engine

Harmony's float equalizer engine: a 10-band EQ whose Auto mode and every
other stage react to live frequency analysis of the music as it plays (never
to tags or genres), output protection that does not clip, a 60 fps spectrum
and telemetry feed, output-device routing and hi-res USB DAC support. The DSP
is C++ (NDK); Media3 and Android integration is Kotlin.

| File | What it is |
| --- | --- |
| `src/main/cpp/MobileEqEngine.{h,cpp}` | The 4-stage DSP chain, the live 10-band analyser and auto-calibrator, SPSC ring, 2048-point FFT, log bars and ballistics |
| `src/main/cpp/eq_jni.cpp` | Garbage-free JNI bridge (direct buffers, `GetPrimitiveArrayCritical`, registered natives) |
| `CustomEqAudioProcessor.kt` | Media3 `AudioProcessor`: 16/24/32-bit or float PCM in, float out, 8–384 kHz |
| `HiResEqAudioSink.kt`, `EqSinkStage.kt`, `GaplessTrimProcessor.kt` | Runs the processor ahead of a float-output `DefaultAudioSink` (see below) |
| `AudioOutputMonitor.kt`, `OutputDeviceType.kt` | Routing detection, USB DAC inspection and Android 14+ bit-perfect USB |

The 60 fps UI is `feature/equalizer`'s `WinampLiveEqualizerScreen.kt`.

## Signal chain

```
input ─┬─▶ device high-pass ─▶ live preamp ─▶ 10 biquads (RBJ, DF2T) ─▶ soft-knee saturator ─▶ 2 ms lookahead limiter ─▶ output ─▶ SPSC ring ─▶ FFT (UI thread)
       │                          ▲                  ▲  ▲
       └─▶ live analysis bank: 10 octave band-passes │  │
             ├─ band energy, 50 ms ──────────────────┘  │   (frequency-weighted headroom)
             ├─ tonal envelope, 1.2 s ─▶ live offsets ──┤   (±4.5 dB against the output's target curve)
             ├─ broadband RMS, 100 ms ─▶ freeze below −42 dBFS
             └─ sub-bass and 4–8 kHz, 8 ms / 120 ms ────┘   (bass and harshness tamer)
```

| Output | High-pass | Pad | Ceiling | Auto target |
| --- | --- | --- | --- | --- |
| Phone speaker | 90 Hz | – | −0.15 dBFS | midrange-focused, no sub-bass lift |
| Wired | 18 Hz | – | −0.15 dBFS | warm −4.5 dB/octave |
| Bluetooth | 24 Hz | −0.3 dB | −0.4 dBFS | warm −4.5 dB/octave |
| USB DAC | 10 Hz | – | −0.05 dBFS | warm −4.5 dB/octave |

- **Live frequency-weighted preamp:** the headroom taken is the boost of the effective curve weighted by the energy each band carries right now (`10·log10(Σ Eᵢ·10^(gᵢ/10) / Σ Eᵢ)`, 50 ms band energies): +10 dB at 62 Hz costs 10 dB of preamp on a bass line and almost nothing on a voice. Taken in 5 ms, given back over 300 ms.
- **Auto (live auto-calibration):** each band's slow tonal envelope (about 1.2 s of RMS) relative to the broadband level is compared with the output's target curve. The live offsets glide (about 1 s) towards three-quarters of the difference, by rule: 31/62 Hz and 8/16 kHz may only be lifted (up to +4.5 dB, never sub-bass on the phone speaker, never a band that is absent altogether), 125/250 Hz and 2/4 kHz may only be cut (down to −4.5 dB), 500 Hz and 1 kHz hold. `effectiveBandGain[i] = clamp(slider[i] + liveOffset[i], −12, +12)`.
- **Freeze gate:** below −42 dBFS of broadband RMS (silence, a quiet intro) the tonal envelopes and the offsets hold still, so noise is never lifted.
- **Tamer:** fast envelopes (8 ms attack, 120 ms release) on the sub-bass and on 4–8 kHz pull the 31/62 Hz boost back by up to 4 dB when sub-bass peaks pass −5 dBFS, and the 4/8 kHz boost when sibilance passes −18 dBFS. They only ever reduce a boost.
- **Bands:** a low shelf at 31 Hz and a high shelf at 16 kHz (Q 0.707, corners on the band frequencies), and peaks from 62 Hz to 8 kHz with proportional Q (1.2 at 0 dB to 2.0 at ±12 dB).
- **De-zippering:** coefficients and the preamp glide every sample, with one-pole smoothing (8 ms and 15 ms), for slider drags and live offsets alike.
- **Denormals:** flush-to-zero is set per process call (FPCR.FZ on ARM, MXCSR FTZ|DAZ on x86) and restored afterwards.
- **Telemetry:** `nativePollDspTelemetry(effectiveGains10, fftMagnitudes32, fftPeaks32)` returns the live preamp and fills the ten effective gains and the FFT in one call, through `GetPrimitiveArrayCritical`.

## Robustness: playback never stops at a bad spot

- **Every input buffer is taken in full.** `CustomEqAudioProcessor.queueInput` always moves the buffer's position to its limit. A trailing partial frame is carried over to the next buffer. Before, it was left behind, and that froze playback at that timestamp until a seek.
- **A wedged pipeline is flushed.** If the EQ pipeline takes no input and produces nothing for three calls in a row while the sink has room, `EqSinkStage` flushes it, as a seek would, and carries on.
- **Input is bounded.** NaN, ±Inf and anything beyond ±16.0 (+24 dBFS) are repaired before processing.
- **Every power has a floor.** Each logarithm and ratio is floored at `1e-12`.
- **Self-heal.** After every 32-frame block the engine checks its filter states, the limiter gain and its output. Anything non-finite is zeroed in place, with the frame count kept, so the output stays sample-aligned. Playback continues.
- **Diagnostics.** These events go to Logcat under the tag `EQ_DEBUG` (`EqDebug`), rate-limited to one line per kind per second:
  - `NAN_HEAL`
  - `CORRUPT_INPUT`
  - `PARTIAL_FRAME`
  - `PIPELINE_WEDGED`
  - `DECODER_ERROR`
  - `DECODER_SKIP`
  - `PLAYBACK_STALL`

## Using it

```kotlin
val equalizer = CustomEqAudioProcessor()

val renderersFactory = object : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean): AudioSink =
        HiResEqAudioSink.create(context, equalizer)
}
val player = ExoPlayer.Builder(context, renderersFactory).build()

// In the playback service (not an Activity: routing must be followed with the screen off).
val monitor = AudioOutputMonitor(context, equalizer, onBitPerfectChanged = { player.prepare() })
lifecycle.addObserver(monitor)

equalizer.setBandGains(floatArrayOf(6f, 4f, 2f, 0f, 0f, 0f, 1f, 2f, 3f, 4f))
equalizer.setAutoMode(true)                        // live offsets on top of the sliders
equalizer.setLevelGainDb(replayGainDb)            // loudness levelling, ahead of the limiter

// UI, every frame (Choreographer.FrameCallback). Allocate the arrays once.
val gains = FloatArray(10); val bars = FloatArray(32); val peaks = FloatArray(32)
val preampDb = equalizer.pollDspTelemetry(gains, bars, peaks)  // live gains, FFT 0..1, live preamp

// On player release:
equalizer.release()
```

## In Harmony

`playback/service`'s `HarmonyPlayer` builds one engine per player (two, for the
crossfade) inside a `HiResEqAudioSink`. It feeds them the settings and ReplayGain
as the level gain, and follows the output with `AudioOutputMonitor`. Auto needs
nothing from it: it listens to whatever plays. The screens poll the playing
engine's telemetry once per frame through `AudioLevels`.

## Where this departs from the brief, and why

0. **`nativeSetSampleRate` runs from the processor's flush, right after `configure()`.** Media3 calls `configure()` on a processor while it may still hold audio of the previous format, and only switches formats on the `flush()` that follows. Re-tuning the engine inside `configure()` would process that tail at the new rate.

1. **A sink, not only a processor.** Media3 1.5's `DefaultAudioSink` cannot run a custom processor on hi-res PCM:
   - With float output enabled, it skips custom `AudioProcessor`s entirely.
   - With float output disabled, it puts `ToInt16PcmAudioProcessor` *before* them, so 24-bit sources lose their low 8 bits before the EQ sees them.

   `HiResEqAudioSink` therefore runs gapless trimming and the processor itself, then hands float to a float-output `DefaultAudioSink`. The processor alone still works in a plain chain, on 16-bit input.

   On that float path, speed changes go through AudioTrack's playback parameters (enabled by `create`), as DefaultAudioSink's float path does.
2. **Biquads run in double.** At 192–384 kHz the 10 Hz high-pass and the 31 Hz shelf have poles within 1e-4 of the unit circle, where float coefficients shift the corner measurably. That was caught by the tests at 384 kHz. Audio in and out stays 32-bit float, and scalar double costs the same as float on ARMv8.
3. **The Padé `tanh` is clamped at |x| = 3.** `x(27+x²)/(27+9x²)` reaches exactly 1 there and turns back down beyond it, which would fold loud peaks over.
4. **The spectrum reads audio at the playback rate.** The audio thread works hundreds of milliseconds ahead of what is audible (the AudioTrack buffer) and in bursts. The poller therefore queues what it receives and advances through it in real time, so the bars move with the sound rather than before it and don't flicker between bursts.
5. **The limiter keeps samples aligned.** It holds back its 2 ms lookahead after each flush and releases it at the end of the stream, so nothing is inserted or lost and gapless playback stays gapless.

## Tests

- **C++ host tests** (`src/test/cpp`, run in CI) cover:
  - response accuracy at 44.1–384 kHz;
  - never exceeding the ceiling at +12 dB on every band with full-scale noise;
  - sample alignment;
  - click-free slider moves;
  - device high-passes;
  - Auto morph and ducking;
  - every input format, including NaN/Inf input;
  - sample-rate reconfiguration;
  - spectrum accuracy;
  - concurrent audio and UI threads;
  - speed (about 90× real time at 192 kHz stereo on a desktop core).

  They are also clean under ASan, UBSan and TSan.
- **JVM tests** (`src/test/kotlin`) cover:
  - the processor, through JNI against a host build of the engine;
  - the trimming processor;
  - the sink state machine, against a sink that takes random-sized bites and enforces AudioSink's contract.
