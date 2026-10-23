# core:eq-engine

Harmony's float equalizer engine: a 10-band EQ with Winamp-style Auto, output
protection that does not clip, a 60 fps spectrum analyzer, output-device routing
and hi-res USB DAC support. The DSP is C++ (NDK); Media3 and Android integration
is Kotlin.

| File | What it is |
| --- | --- |
| `src/main/cpp/MobileEqEngine.{h,cpp}` | The 4-stage DSP chain, SPSC ring, 2048-point FFT, log bars and ballistics |
| `src/main/cpp/eq_jni.cpp` | Garbage-free JNI bridge (direct buffers, registered natives) |
| `CustomEqAudioProcessor.kt` | Media3 `AudioProcessor`: 16/24/32-bit or float PCM in, float out, 8–384 kHz |
| `HiResEqAudioSink.kt`, `EqSinkStage.kt`, `GaplessTrimProcessor.kt` | Runs the processor ahead of a float-output `DefaultAudioSink` (see below) |
| `AudioOutputMonitor.kt`, `OutputDeviceType.kt` | Routing detection and Android 14+ bit-perfect USB |

## Signal chain

```
input ─▶ device high-pass ─▶ auto-preamp ─▶ 10 biquads (RBJ, DF2T) ─▶ soft-knee saturator ─▶ 2 ms lookahead limiter ─▶ output
            │                                                                                      │
            └─▶ sub-bass envelope (Auto: ducks 31/62 Hz by ≤4 dB above −6 dBFS)                     └─▶ SPSC ring ─▶ FFT (UI thread)
```

| Output | High-pass | Pad | Ceiling |
| --- | --- | --- | --- |
| Phone speaker | 90 Hz | – | −0.15 dBFS |
| Wired | 18 Hz | – | −0.15 dBFS |
| Bluetooth | 24 Hz | −0.3 dB | −0.15 dBFS |
| USB DAC | 10 Hz | – | −0.05 dBFS |

- **Bands:** a low shelf at 31 Hz and a high shelf at 16 kHz (Q 0.707, corners on the band frequencies), and peaks from 62 Hz to 8 kHz with proportional Q (1.2 at 0 dB to 2.0 at ±12 dB).
- **Auto-preamp:** −0.65 × the peak of the *whole* response curve, evaluated at 74 frequencies. Two neighbouring +8 dB bands peak above +8 dB between them, so the peak of the curve is what matters, not the loudest slider.
- **Auto listens:** octave band-passes on the input (before the high-pass, so a phone speaker's 90 Hz cut doesn't read as a song short of bass) average each song's tone over 100 ms blocks, skipping silence. Twice a second the song is compared with a typical well-mastered record and half the difference is evened out (at most +5/−6 dB, smoothed across neighbouring bands, missing bands such as an MP3's cut-off top left alone), fading in over 8 s and added to the listener's curve. Every new target morphs in over 400 ms.
- **De-zippering:** coefficients and the preamp glide every sample, with one-pole smoothing (8 ms and 15 ms).
- **Denormals:** flush-to-zero is set per process call (FPCR.FZ on ARM, MXCSR FTZ|DAZ on x86) and restored afterwards.

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
equalizer.setAutoMode(true)
equalizer.setAutoTargetGains(listenersCurve)      // Auto adds what it hears on top
equalizer.startTrack(knownLevels)                 // on every track change (null for a new song)
equalizer.setLevelGainDb(replayGainDb)            // loudness levelling, ahead of the limiter

// UI, every frame (Choreographer / withFrameNanos). Allocate the arrays once.
val bars = FloatArray(64); val peaks = FloatArray(64)
val count = equalizer.pollSpectrum(bars, peaks)    // 0..1, ballistics applied

// On player release:
equalizer.release()
```

## In Harmony

`playback/service`'s `HarmonyPlayer` builds one engine per player (two, for the
crossfade) inside a `HiResEqAudioSink`. It feeds them the settings and ReplayGain
as the level gain, starts Auto listening at every track change (with what it
heard of the song before, if it was played recently), and follows the output
with `AudioOutputMonitor`. The screens poll the playing engine's analyser once
per frame through `AudioLevels`.

## Where this departs from the brief, and why

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
