// MobileEqEngine: Harmony's 10-band equalizer, output protection and spectrum
// analyzer, in one real-time-safe C++ object.
//
// Signal path, per frame, in 32-bit float:
//
//   input ─▶ [1] device high-pass ─▶ auto-preamp ─▶ [2] 10 biquads ─▶
//         ─▶ [4] soft-knee saturator ─▶ lookahead limiter ─▶ output
//                 │                                            │
//                 └─▶ [3] sub-bass envelope (Auto ducking)      └─▶ analyzer ring
//
// Threads:
//  - One audio thread calls configure(), reset(), process*() and drain().
//    Those calls never allocate, lock or make system calls.
//  - Any thread may call the set*() controls. They only store atomics; the
//    audio thread picks them up at the start of its next 32-frame block.
//  - One UI thread calls pollSpectrum(). The audio thread hands it samples
//    through a lock-free single-producer/single-consumer ring and never waits
//    for it; the FFT and the bar ballistics run on the polling thread.

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
    /** Winamp-style Auto: the curve follows [setAutoTargetGainsDb] and the sub-bass ducks on kicks. */
    void setAutoMode(bool enabled);
    /**
     * The listener's own curve under Auto. Auto plays it plus what it works
     * out by listening to the song (see startTrack), morphing to every new
     * target over 400 ms.
     */
    void setAutoTargetGainsDb(const float* gainsDb);
    /**
     * A new song starts: Auto listens to it afresh. [knownLevelsDb] (kNumBands
     * values, or null) is what songLevelsDb() reported for this song when it
     * was heard before; its correction then applies at once instead of
     * fading in as the song is heard.
     */
    void startTrack(const float* knownLevelsDb);
    /** Extra user preamp, on top of the automatic compensation. */
    void setUserPreampDb(float preampDb);
    /**
     * Loudness levelling (ReplayGain), -30..+15 dB, on top of the preamps. It
     * runs even with the EQ off; the limiter catches what a lift pushes over.
     */
    void setLevelGainDb(float gainDb);
    void setOutputDevice(OutputDevice device);
    /** Stops feeding the analyzer (saves the copy while nothing is on screen). */
    void setAnalyzerEnabled(bool enabled);
    /** 8 to 64 logarithmic bars from 20 Hz to 20 kHz. */
    void setSpectrumBarCount(int bars);

    // ---- Spectrum (one polling thread) ------------------------------------------------

    /**
     * Runs a Hann-windowed 2048-point FFT over the newest audio and writes
     * 0..1 bar heights to [bars] and peak-hold dots to [peaks] (either may be
     * null), with attack/decay ballistics already applied. Returns the number
     * of bars written (at most [capacity]). Never blocks the audio thread.
     */
    int pollSpectrum(float* bars, float* peaks, int capacity);

    /** pollSpectrum() at a given steady-clock time, in nanoseconds (for tests). */
    int pollSpectrumAt(int64_t nowNanos, float* bars, float* peaks, int capacity);

    // ---- Introspection (tests, diagnostics) -----------------------------------------------

    /** The automatic preamp the engine is converging on, in dB (negative). */
    float targetPreampDb() const { return targetPreampDb_.load(std::memory_order_relaxed); }
    /** The current Auto sub-bass ducking, in dB (0..4). */
    float bassDuckDb() const { return bassDuckDb_.load(std::memory_order_relaxed); }
    /** Ceiling of the limiter for the current device, in dBFS. */
    float ceilingDb() const;
    /**
     * The current song's octave-band levels as Auto has heard them so far (dB,
     * NaN where nothing yet), for the caller to remember; returns the seconds
     * of music heard (silence and fades don't count).
     */
    float songLevelsDb(float* out) const;
    /** What Auto adds to the listener's curve from listening, dB per band. */
    void listenCorrectionDb(float* out) const;
    /** The curve Auto plays right now (the morph included, before ducking), dB per band. */
    void autoCurveDb(float* out) const;
    /**
     * Auto's tone correction for a song with octave-band [levelsDb]: how far it
     * sits from a typical well-mastered record, partly evened out. Pure.
     */
    static void toneCorrectionDb(const float* levelsDb, float* outDb);

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
    void recomputePreamp(const float* gainsDb);
    int processBlock(float* io, int frames);
    float limitFrame(float* frame, float* out);
    void pushAnalyzer(const float* io, int frames);
    void finishListenBlock();
    void updateListenCurve();
    void beginTrack();
    void rebuildBarMap(int sampleRate, int bars);

    // Shared controls.
    std::atomic<float> userGainDb_[kNumBands];
    std::atomic<float> autoTargetDb_[kNumBands];
    std::atomic<uint32_t> controlGeneration_{1};
    std::atomic<uint32_t> autoGeneration_{0};
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
    // Auto listening: requests in, readings out.
    std::atomic<uint32_t> trackGeneration_{0};
    std::atomic<bool> knownValid_{false};
    std::atomic<float> knownLevelDb_[kNumBands];
    std::atomic<float> songLevelOutDb_[kNumBands];
    std::atomic<float> listenOutDb_[kNumBands];
    std::atomic<float> autoCurveOutDb_[kNumBands];
    std::atomic<float> heardSecondsOut_{0.f};

    // Audio-thread state.
    int sampleRate_ = 48000;
    int channels_ = 2;
    uint32_t seenControlGeneration_ = 0;
    uint32_t seenAutoGeneration_ = 0;
    OutputDevice activeDevice_ = OutputDevice::kPhoneSpeaker;
    double coefAlpha_ = 0.0;  // per-sample coefficient smoothing
    float gainAlpha_ = 0.f;   // per-sample preamp smoothing

    Smoothed highPass_;
    Smoothed bands_[kNumBands];
    double hpState_[kMaxChannels][2] = {};
    double bandState_[kNumBands][kMaxChannels][2] = {};
    float designedDb_[kNumBands] = {};       // what each band is designed at now
    float preampSourceDb_[kNumBands] = {};   // what the preamp was computed from
    long preampAgeFrames_ = 0;
    bool preampDirty_ = true;
    float preampLinear_ = 1.f;
    float preampTargetLinear_ = 1.f;
    float devicePadDb_ = 0.f;

    // Auto: 400 ms morph and the sub-bass ducker.
    float autoFromDb_[kNumBands] = {};
    float autoToDb_[kNumBands] = {};
    float autoNowDb_[kNumBands] = {};
    int autoRampFrames_ = 0;
    int autoRampPos_ = 0;
    bool autoWasOn_ = false;
    Biquad subBassLowPass_;
    double subBassState_[2] = {};
    float subBassEnvelope_ = 0.f;
    float envAttack_ = 0.f;
    float envRelease_ = 0.f;
    float duckDb_ = 0.f;

    // Auto listening: octave band-passes on the input, averaged over the song.
    bool listening_ = false;
    Biquad listenFilter_[kNumBands];
    double listenState_[kNumBands][2] = {};
    double listenBlockPower_[kNumBands] = {};
    double songPower_[kNumBands] = {};
    int listenBlockFrames_ = 4800;
    int listenFrameInBlock_ = 0;
    int songBlocks_ = 0;
    int blocksSinceCurve_ = 0;
    bool hasKnown_ = false;
    float knownLevels_[kNumBands] = {};
    float listenDb_[kNumBands] = {};
    uint32_t listenVersion_ = 0;
    uint32_t seenListenVersion_ = 0;
    uint32_t seenTrackGeneration_ = 0;

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
