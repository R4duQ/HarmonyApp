// MobileEqEngine: Harmony's 10-band equalizer, live spectral Auto, output
// protection and spectrum analyzer, in one real-time-safe C++ object. Every
// stage reacts to what the music sounds like right now; nothing reads tags.
//
// Signal path, per frame, in 32-bit float:
//
//   input ─┬─▶ [1] device high-pass ─▶ live preamp ─▶ [3] 10 biquads ─▶
//          │     ─▶ [4] soft-knee saturator ─▶ lookahead limiter ─▶ output ─▶ analyzer ring (FFT)
//          │                  ▲                       ▲
//          └─▶ [2] live analysis bank (10 octave band-passes)
//                ├─ fast band energy (50 ms)   ─▶ the frequency-weighted preamp
//                ├─ slow tonal envelope (1.2 s) ─▶ Auto's live offsets (±4.5 dB vs the output's target curve)
//                ├─ broadband RMS (100 ms)      ─▶ freeze below -42 dBFS
//                └─ sub-bass / sibilance (8 ms / 120 ms) ─▶ bass and harshness tamer
//
// Threads:
//  - One audio thread calls configure(), reset(), process*() and drain().
//    Those calls never allocate, lock or make system calls.
//  - Any thread may call the set*() controls. They only store atomics; the
//    audio thread picks them up at the start of its next 32-frame block.
//  - One UI thread calls pollDspTelemetry() at 60 fps. The audio thread hands
//    it samples through a lock-free single-producer/single-consumer ring and
//    never waits for it; the FFT and the bar ballistics run on the polling thread.

#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>

namespace harmony::eq {

/** What the sound is playing on. It decides the high-pass, a codec pad and the ceiling. */
enum class OutputDevice : int32_t {
    kPhoneSpeaker = 0,
    kWiredHeadphones = 1,
    kBluetoothA2dp = 2,
    kUsbDacHiRes = 3,
};

/** Interleaved input sample formats process() converts from. Little-endian, as Android's. */
enum class SampleFormat : int32_t {
    kPcm16 = 1,
    kPcm24Packed = 2,
    kPcm32 = 3,
    kFloat = 4,
};

constexpr int kNumBands = 10;
constexpr float kBandFrequencies[kNumBands] = {31.f, 62.f, 125.f, 250.f, 500.f,
                                               1000.f, 2000.f, 4000.f, 8000.f, 16000.f};
constexpr float kMinGainDb = -12.f;
constexpr float kMaxGainDb = 12.f;
constexpr int kMaxChannels = 8;
constexpr int kMinSampleRate = 8000;
constexpr int kMaxSampleRate = 384000;
constexpr int kFftSize = 2048;
constexpr int kMinBars = 8;
constexpr int kMaxBars = 64;

/**
 * Biquad coefficients, normalised so a0 = 1 (Direct Form II Transposed).
 *
 * Double precision on purpose: at 192-384 kHz the 10 Hz high-pass and the
 * 31 Hz shelf put their poles within 1e-4 of the unit circle, where float
 * coefficients and state shift the corner and add noise. Scalar double costs
 * the same as float on ARMv8. Audio in and out stays 32-bit float.
 */
struct Biquad {
    double b0 = 1.0, b1 = 0.0, b2 = 0.0, a1 = 0.0, a2 = 0.0;
};

/** Filter designs (RBJ Audio EQ Cookbook). */
Biquad designPeaking(double sampleRate, double frequency, double gainDb, double q);
Biquad designLowShelf(double sampleRate, double frequency, double gainDb, double q);
Biquad designHighShelf(double sampleRate, double frequency, double gainDb, double q);
Biquad designHighPass(double sampleRate, double frequency, double q);
Biquad designLowPass(double sampleRate, double frequency, double q);

/** |H(e^jw)| of one biquad at [frequency]. */
double magnitudeAt(const Biquad& filter, double sampleRate, double frequency);

/** Q of peaking band [band] at [gainDb]: narrower as the gain grows, so neighbours don't pile up. */
double proportionalQ(double gainDb);

/** The design of EQ band [band] at [gainDb] (shelves at the ends, peaks between). */
Biquad designBand(int band, double sampleRate, double gainDb);

/** Padé approximation of tanh, exact at 0 and reaching 1 at |x| = 3, where it is clamped. */
inline float padeTanh(float x) {
    if (x > 3.f) return 1.f;
    if (x < -3.f) return -1.f;
    const float x2 = x * x;
    return x * (27.f + x2) / (27.f + 9.f * x2);
}

/**
 * Lock-free single-producer / single-consumer ring of mono samples.
 * The producer (audio thread) never blocks: when the consumer falls behind
 * (screen off, nothing polling), new samples are dropped instead.
 */
class SpscRing {
public:
    static constexpr uint32_t kCapacity = 16384;  // power of two
    /** Producer side. Returns how many samples were stored. */
    uint32_t push(const float* samples, uint32_t count);
    /** Consumer side. Returns how many samples were copied out. */
    uint32_t pop(float* out, uint32_t maxCount);
    /** Consumer side: drop everything currently stored. */
    void discard();

private:
    alignas(64) std::atomic<uint32_t> write_{0};
    alignas(64) std::atomic<uint32_t> read_{0};
    alignas(64) float data_[kCapacity] = {};
};

/** In-place iterative radix-2 FFT of a fixed size, with tables built once. */
class Fft {
public:
    explicit Fft(int size);
    ~Fft();
    Fft(const Fft&) = delete;
    Fft& operator=(const Fft&) = delete;
    /** Transforms re/im (each [size()] long) in place. No allocation. */
    void forward(float* re, float* im) const;
    int size() const { return size_; }

private:
    int size_;
    int log2Size_;
    float* cos_;      // size/2
    float* sin_;      // size/2
    uint32_t* bitReverse_;  // size
};

class MobileEqEngine {
public:
    MobileEqEngine();
    ~MobileEqEngine();
    MobileEqEngine(const MobileEqEngine&) = delete;
    MobileEqEngine& operator=(const MobileEqEngine&) = delete;

    // ---- Audio thread ------------------------------------------------------------

    /**
     * Prepares for interleaved audio at [sampleRate] with [channels]. Recomputes
     * every coefficient, time constant and the lookahead length for the new rate
     * and clears all state. Allocation-free (buffers are sized for 384 kHz), so
     * it may run on the audio thread. Returns false for an unsupported format.
     */
    bool configure(int sampleRate, int channels);

    /** Clears filter, envelope and limiter state, as after a seek. */
    void reset();

    /**
     * Converts [frames] interleaved frames of [format] from [input] to float,
     * processes them and writes the result to [output] (which may alias
     * [input] for kFloat). Returns the number of frames written. Right after
     * configure() or reset() the limiter's lookahead holds back
     * latencyFrames() frames, so the first calls return fewer frames than they
     * take; drain() returns them at the end of the stream. Output stays
     * sample-aligned with input: nothing is inserted or lost.
     */
    int process(const void* input, SampleFormat format, int frames, float* output);

    /** Writes up to [maxFrames] of the audio still held by the lookahead; returns the count. */
    int drain(float* output, int maxFrames);

    /** Frames the lookahead holds back (2 ms at the current rate, minus one). */
    int latencyFrames() const { return lookaheadDelay_; }

    int sampleRate() const { return sampleRate_; }
    int channels() const { return channels_; }

    // ---- Controls (any thread, lock-free) ------------------------------------------

    void setBandGainDb(int band, float gainDb);
    void setBandGainsDb(const float* gainsDb);  // kNumBands values
    /** EQ on (the sliders apply) or off (flat); the protection stages stay on either way. */
    void setEqEnabled(bool enabled);
    /**
     * Auto: live spectral auto-calibration. The analysis bank measures the
     * music's balance (slow envelope, about 1.2 s) against the output's
     * target curve and the live offsets lift thin sub-bass and air and take
     * out mud and harshness, within ±4.5 dB, on top of the sliders.
     */
    void setAutoMode(bool enabled);
    /** Extra user preamp, on top of the live automatic headroom, -24..+12 dB. */
    void setUserPreampDb(float preampDb);
    /**
     * Loudness levelling (ReplayGain), -30..+15 dB, on top of the preamps. It
     * runs even with the EQ off; the limiter catches what a lift pushes over.
     */
    void setLevelGainDb(float gainDb);
    /** The output: switches the high-pass, the limiter ceiling and Auto's target curve. */
    void setOutputDeviceType(OutputDevice device);
    /** Stops feeding the analyzer (saves the copy while nothing is on screen). */
    void setAnalyzerEnabled(bool enabled);
    /** 8 to 64 logarithmic bars from 20 Hz to 20 kHz. */
    void setSpectrumBarCount(int bars);

    // ---- Telemetry and spectrum (one polling thread, 60 fps) -------------------------

    /**
     * One snapshot for a 60 fps UI: the effective band gains (sliders plus the
     * live Auto offsets, ducking included) into [effectiveGainsDb] (kNumBands),
     * and the Hann-windowed 2048-point FFT as 0..1 bars and peak-hold dots into
     * [bars] and [peaks] (any may be null; at most [capacity] bars). Returns the
     * live automatic preamp in dB (headroom, Bluetooth pad and user preamp).
     * Never blocks the audio thread.
     */
    float pollDspTelemetry(float* effectiveGainsDb, float* bars, float* peaks, int capacity);
    float pollDspTelemetryAt(int64_t nowNanos, float* effectiveGainsDb, float* bars, float* peaks, int capacity);

    /**
     * The FFT part of pollDspTelemetry: 0..1 bar heights to [bars] and
     * peak-hold dots to [peaks] (either may be null), ballistics applied.
     * Returns the number of bars written (at most [capacity]).
     */
    int pollSpectrum(float* bars, float* peaks, int capacity);

    /** pollSpectrum() at a given steady-clock time, in nanoseconds (for tests). */
    int pollSpectrumAt(int64_t nowNanos, float* bars, float* peaks, int capacity);

    /** How many bars the spectrum has (setSpectrumBarCount). */
    int spectrumBarCount() const { return barCount_.load(std::memory_order_relaxed); }
    /** The effective gain of each band right now, dB. */
    void effectiveGainsDb(float* out) const;
    /** Auto's live offset for each band right now, dB (±4.5). */
    void liveAutoOffsetsDb(float* out) const;

    // ---- Introspection (tests, diagnostics) -----------------------------------------------

    /** The whole preamp the engine is converging on, in dB (live headroom, pad, user, level). */
    float targetPreampDb() const { return targetPreampDb_.load(std::memory_order_relaxed); }
    /** The current sub-bass ducking, in dB (0..4). */
    float bassDuckDb() const { return bassDuckDb_.load(std::memory_order_relaxed); }
    /** The current harshness tamer on 4/8 kHz, in dB (0..4). */
    float harshDuckDb() const { return harshDuckOutDb_.load(std::memory_order_relaxed); }
    /** Whether the tonal adaptation is frozen (the music is below -42 dBFS). */
    bool tonalFrozen() const { return frozenOut_.load(std::memory_order_relaxed); }
    /** Ceiling of the limiter for the current device, in dBFS. */
    float ceilingDb() const;
    /** Auto's target balance for [device], dB per band relative to the broadband level. */
    static void targetCurveDb(OutputDevice device, float* out);
    /**
     * The live offsets Auto aims for, for octave-band powers [bandPower] heard
     * on [device]: thin sub-bass and air lifted, mud and harshness cut, within
     * ±4.5 dB. Pure.
     */
    static void autoOffsetTargetDb(OutputDevice device, const double* bandPower, float* out);

    /** Gain (dB) band [band] is currently designed at, ducking included. */
    float designedBandGainDb(int band) const { return designedDb_[band]; }

private:
    static constexpr int kBlock = 32;  // frames between control updates
    static constexpr int kMaxLookahead = kMaxSampleRate * 2 / 1000 + 1;

    struct Smoothed {
        Biquad current;
        Biquad target;
        bool settling = false;
    };

    void updateControls();
    void designHighPassForDevice();
    int processBlock(float* io, int frames);
    float limitFrame(float* frame, float* out);
    void pushAnalyzer(const float* io, int frames);
    void rebuildBarMap(int sampleRate, int bars);

    // Shared controls.
    std::atomic<float> userGainDb_[kNumBands];
    std::atomic<uint32_t> controlGeneration_{1};
    std::atomic<bool> eqEnabled_{true};
    std::atomic<bool> autoMode_{false};
    std::atomic<float> userPreampDb_{0.f};
    std::atomic<float> levelGainDb_{0.f};
    std::atomic<int32_t> device_{static_cast<int32_t>(OutputDevice::kPhoneSpeaker)};
    std::atomic<bool> analyzerEnabled_{true};
    std::atomic<int32_t> barCount_{32};
    std::atomic<int32_t> analyzerSampleRate_{48000};
    std::atomic<float> targetPreampDb_{0.f};
    std::atomic<float> bassDuckDb_{0.f};
    // Telemetry out.
    std::atomic<float> effectiveOutDb_[kNumBands];
    std::atomic<float> offsetOutDb_[kNumBands];
    std::atomic<float> liveAutoPreampOutDb_{0.f};
    std::atomic<float> harshDuckOutDb_{0.f};
    std::atomic<bool> frozenOut_{true};

    // Audio-thread state.
    int sampleRate_ = 48000;
    int channels_ = 2;
    uint32_t seenControlGeneration_ = 0;
    OutputDevice activeDevice_ = OutputDevice::kPhoneSpeaker;
    double coefAlpha_ = 0.0;  // per-sample coefficient smoothing
    float gainAlpha_ = 0.f;   // per-sample preamp smoothing

    Smoothed highPass_;
    Smoothed bands_[kNumBands];
    double hpState_[kMaxChannels][2] = {};
    double bandState_[kNumBands][kMaxChannels][2] = {};
    float designedDb_[kNumBands] = {};       // what each band is designed at now
    float designedPower_[kNumBands] = {1.f, 1.f, 1.f, 1.f, 1.f, 1.f, 1.f, 1.f, 1.f, 1.f};  // the same, as power
    float preampLinear_ = 1.f;
    float preampTargetLinear_ = 1.f;
    float devicePadDb_ = 0.f;

    // Stage 2: the live analysis bank (one octave band-pass per EQ band, on the input).
    Biquad analysisFilter_[kNumBands];
    double analysisState_[kNumBands][2] = {};
    double blockPower_[kNumBands] = {};   // summed over the current 32-frame block
    int blockFrames_ = 0;
    double bandEnergy_[kNumBands] = {};   // ~50 ms, weights the live preamp
    double tonalPower_[kNumBands] = {};   // ~1.2 s, the slow tonal envelope
    bool tonalWarm_ = false;
    float broadbandPower_ = 0.f;          // ~100 ms, the silence gate
    bool frozen_ = true;
    float liveOffsetDb_[kNumBands] = {};
    float bandEnergyAlpha_ = 0.f;
    float gateAlpha_ = 0.f;
    double tonalAlpha_ = 0.0;
    float offsetAlpha_ = 0.f;
    // Stage 1: live headroom.
    float autoHeadroomDb_ = 0.f;
    float preampAttack_ = 0.f;
    float preampRelease_ = 0.f;
    // Stage 2c: fast transient envelopes (8 ms attack, 120 ms release).
    Biquad subBassLowPass_;
    double subBassState_[2] = {};
    float subBassEnvelope_ = 0.f;
    Biquad sibilanceBandPass_;
    double sibilanceState_[2] = {};
    float sibilanceEnvelope_ = 0.f;
    float envAttack_ = 0.f;
    float envRelease_ = 0.f;
    float duckRelease_ = 0.f;
    float duckDb_ = 0.f;
    float harshDuckDb_ = 0.f;

    // Saturator and limiter.
    float saturatorKnee_ = 0.f;
    float ceiling_ = 1.f;
    int lookahead_ = 1;       // window length L
    int lookaheadDelay_ = 0;  // L - 1
    float delay_[kMaxLookahead * kMaxChannels] = {};
    int delayPos_ = 0;
    float minValue_[kMaxLookahead] = {};
    int64_t minIndex_[kMaxLookahead] = {};
    int minHead_ = 0;
    int minCount_ = 0;
    float box_[kMaxLookahead] = {};
    double boxSum_ = 0.0;
    int boxPos_ = 0;
    float releaseGain_ = 1.f;
    float releaseAlpha_ = 0.f;
    int64_t frameIndex_ = 0;
    int primeFrames_ = 0;  // outputs still to skip after a reset (the lookahead filling up)

    // Analyzer producer side.
    SpscRing ring_;
    std::atomic<uint32_t> analyzerEpoch_{0};

    // Analyzer consumer side (polling thread only).
    Fft fft_{kFftSize};
    float window_[kFftSize];
    float history_[kFftSize] = {};
    int historyPos_ = 0;
    float fftRe_[kFftSize];
    float fftIm_[kFftSize];
    float popScratch_[SpscRing::kCapacity];
    // The audio thread runs ahead of what is audible (the sink's buffer) and
    // delivers in bursts. The poller keeps what it took in a FIFO and reads it
    // at the playback rate, so the bars move with the sound, not before it.
    static constexpr uint32_t kPendingCapacity = 65536;  // power of two
    float pending_[kPendingCapacity];
    uint32_t pendingHead_ = 0;
    uint32_t pendingCount_ = 0;
    float staleSeconds_ = 0.f;
    uint32_t seenAnalyzerEpoch_ = 0;
    int mappedSampleRate_ = 0;
    int mappedBars_ = 0;
    int barLowBin_[kMaxBars] = {};
    int barHighBin_[kMaxBars] = {};
    float barCenterBin_[kMaxBars] = {};
    float barLevel_[kMaxBars] = {};
    float peakLevel_[kMaxBars] = {};
    float peakHold_[kMaxBars] = {};
    float peakVelocity_[kMaxBars] = {};
    int64_t lastPollNanos_ = 0;
};

}  // namespace harmony::eq
