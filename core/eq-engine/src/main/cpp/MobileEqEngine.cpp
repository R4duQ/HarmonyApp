#include "MobileEqEngine.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>

#if defined(__x86_64__) || defined(__i386__)
#include <xmmintrin.h>
#endif

namespace harmony::eq {
namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr double kButterworthQ = 0.70710678118654752;

// Stage 1.
constexpr float kPreampCompensation = 0.65f;   // share of the boost peak taken back by the preamp
constexpr float kBluetoothPadDb = -0.3f;       // room for lossy codecs' inter-sample peaks
constexpr float kMaxUserPreampDb = 12.f;
constexpr float kMaxLevelGainDb = 15.f;     // ReplayGain lifts quiet records up to this
constexpr float kMinLevelGainDb = -30.f;
constexpr float kMinUserPreampDb = -24.f;
constexpr float kCoefficientSmoothingSeconds = 0.008f;
constexpr float kGainSmoothingSeconds = 0.015f;
constexpr float kPreampRecomputeSeconds = 0.005f;

// Stage 2. Shelf corners sit half an octave inside the band, so the band's own
// frequency gets most of the slider's gain rather than the shelf's half-way point.
constexpr double kShelfQ = kButterworthQ;
constexpr double kLowShelfCornerRatio = 1.5;
constexpr double kHighShelfCornerRatio = 1.0 / 1.5;
constexpr double kPeakQAtFlat = 1.2;
constexpr double kPeakQGrowth = 0.8;   // Q at +-12 dB = 1.2 + 0.8

// Stage 3.
constexpr float kAutoMorphSeconds = 0.4f;
constexpr float kSubBassCutoffHz = 80.f;
constexpr float kDuckThresholdDb = -6.f;
constexpr float kDuckRangeDb = 6.f;     // full ducking 6 dB above the threshold
constexpr float kMaxDuckDb = 4.f;
constexpr float kEnvelopeAttackSeconds = 0.003f;
constexpr float kEnvelopeReleaseSeconds = 0.15f;
constexpr float kDuckReleaseSeconds = 0.12f;

// Stage 4.
constexpr float kSaturatorKneeDb = -1.8f;
constexpr float kCeilingDb = -0.15f;
constexpr float kUsbCeilingDb = -0.05f;
constexpr float kLookaheadSeconds = 0.002f;
constexpr float kLimiterReleaseSeconds = 0.08f;

// Analyzer.
constexpr float kMinBarHz = 20.f;
constexpr float kMaxBarHz = 20000.f;
constexpr float kFloorDb = -72.f;
constexpr float kAttackSeconds = 0.010f;
constexpr float kDecayDbPerSecond = 90.f;
constexpr float kPeakHoldSeconds = 0.5f;
constexpr float kPeakGravity = 2.5f;   // bar heights per second squared
constexpr float kStaleSeconds = 0.25f;  // no audio for this long: the bars fall
constexpr float kMaxLagSeconds = 0.75f; // never trail the audio thread by more

constexpr int kPreampGridPoints = 64;

inline float dbToLinear(float db) { return std::pow(10.f, db / 20.f); }

inline float clampGainDb(float db) {
    if (!std::isfinite(db)) return 0.f;
    return std::clamp(db, kMinGainDb, kMaxGainDb);
}

inline float smoothingAlpha(float seconds, double rate) {
    return static_cast<float>(1.0 - std::exp(-1.0 / (seconds * rate)));
}

inline double nyquistSafe(double sampleRate, double frequency) {
    return std::min(frequency, sampleRate * 0.45);
}

Biquad normalised(double b0, double b1, double b2, double a0, double a1, double a2) {
    Biquad f;
    f.b0 = b0 / a0;
    f.b1 = b1 / a0;
    f.b2 = b2 / a0;
    f.a1 = a1 / a0;
    f.a2 = a2 / a0;
    return f;
}

// Direct Form II Transposed: two state variables, the best-behaved direct form.
inline double runBiquad(const Biquad& f, double* s, double x) {
    const double y = f.b0 * x + s[0];
    s[0] = f.b1 * x - f.a1 * y + s[1];
    s[1] = f.b2 * x - f.a2 * y;
    return y;
}

inline void approach(Biquad& current, const Biquad& target, double alpha) {
    current.b0 += (target.b0 - current.b0) * alpha;
    current.b1 += (target.b1 - current.b1) * alpha;
    current.b2 += (target.b2 - current.b2) * alpha;
    current.a1 += (target.a1 - current.a1) * alpha;
    current.a2 += (target.a2 - current.a2) * alpha;
}

inline bool settled(const Biquad& a, const Biquad& b) {
    constexpr double kEpsilon = 1e-12;
    return std::fabs(a.b0 - b.b0) < kEpsilon && std::fabs(a.b1 - b.b1) < kEpsilon &&
           std::fabs(a.b2 - b.b2) < kEpsilon && std::fabs(a.a1 - b.a1) < kEpsilon &&
           std::fabs(a.a2 - b.a2) < kEpsilon;
}

// Flush-to-zero / denormals-are-zero for the duration of one process call.
// Decaying IIR tails otherwise fall into denormals, which ARM and x86 handle
// up to a hundred times slower: exactly when the screen is off and the track
// fades out. Restored afterwards, as the thread is shared with Java code.
class ScopedFlushDenormals {
public:
    ScopedFlushDenormals() {
#if defined(__aarch64__)
        asm volatile("mrs %0, fpcr" : "=r"(saved_));
        const uint64_t flushed = saved_ | (1ULL << 24);  // FZ
        asm volatile("msr fpcr, %0" : : "r"(flushed));
#elif defined(__arm__) && defined(__ARM_FP)
        asm volatile("vmrs %0, fpscr" : "=r"(saved_));
        const uint32_t flushed = saved_ | (1U << 24);  // FZ
        asm volatile("vmsr fpscr, %0" : : "r"(flushed));
#elif defined(__x86_64__) || defined(__i386__)
        saved_ = _mm_getcsr();
        _mm_setcsr(saved_ | 0x8040);  // FTZ | DAZ
#endif
    }
    ~ScopedFlushDenormals() {
#if defined(__aarch64__)
        asm volatile("msr fpcr, %0" : : "r"(saved_));
#elif defined(__arm__) && defined(__ARM_FP)
        asm volatile("vmsr fpscr, %0" : : "r"(saved_));
#elif defined(__x86_64__) || defined(__i386__)
        _mm_setcsr(saved_);
#endif
    }
    ScopedFlushDenormals(const ScopedFlushDenormals&) = delete;
    ScopedFlushDenormals& operator=(const ScopedFlushDenormals&) = delete;

private:
#if defined(__aarch64__)
    uint64_t saved_ = 0;
#else
    uint32_t saved_ = 0;
#endif
};

int64_t nowNanos() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

inline float readSample(const uint8_t* p, SampleFormat format) {
    switch (format) {
        case SampleFormat::kPcm16: {
            int16_t v;
            std::memcpy(&v, p, 2);
            return static_cast<float>(v) * (1.f / 32768.f);
        }
        case SampleFormat::kPcm24Packed: {
            // Assemble unsigned, then sign-extend from bit 23 (shifting a
            // negative value left is undefined behaviour before C++20).
            const uint32_t raw = static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
                                 (static_cast<uint32_t>(p[2]) << 16);
            const int32_t v = static_cast<int32_t>(raw ^ 0x800000U) - 0x800000;
            return static_cast<float>(v) * (1.f / 8388608.f);
        }
        case SampleFormat::kPcm32: {
            int32_t v;
            std::memcpy(&v, p, 4);
            return static_cast<float>(static_cast<double>(v) * (1.0 / 2147483648.0));
        }
        case SampleFormat::kFloat: {
            float v;
            std::memcpy(&v, p, 4);
            return std::isfinite(v) ? v : 0.f;  // one NaN would poison every filter for good
        }
    }
    return 0.f;
}

inline int bytesPerSample(SampleFormat format) {
    switch (format) {
        case SampleFormat::kPcm16: return 2;
        case SampleFormat::kPcm24Packed: return 3;
        case SampleFormat::kPcm32: return 4;
        case SampleFormat::kFloat: return 4;
    }
    return 0;
}

}  // namespace

// ---- Filter design ------------------------------------------------------------------

Biquad designPeaking(double sampleRate, double frequency, double gainDb, double q) {
    const double a = std::pow(10.0, gainDb / 40.0);
    const double w0 = 2.0 * kPi * nyquistSafe(sampleRate, frequency) / sampleRate;
    const double cosW = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * q);
    return normalised(1.0 + alpha * a, -2.0 * cosW, 1.0 - alpha * a,
                      1.0 + alpha / a, -2.0 * cosW, 1.0 - alpha / a);
}

Biquad designLowShelf(double sampleRate, double frequency, double gainDb, double q) {
    const double a = std::pow(10.0, gainDb / 40.0);
    const double w0 = 2.0 * kPi * nyquistSafe(sampleRate, frequency) / sampleRate;
    const double cosW = std::cos(w0);
    const double twoSqrtAAlpha = 2.0 * std::sqrt(a) * std::sin(w0) / (2.0 * q);
    return normalised(a * ((a + 1.0) - (a - 1.0) * cosW + twoSqrtAAlpha),
                      2.0 * a * ((a - 1.0) - (a + 1.0) * cosW),
                      a * ((a + 1.0) - (a - 1.0) * cosW - twoSqrtAAlpha),
                      (a + 1.0) + (a - 1.0) * cosW + twoSqrtAAlpha,
                      -2.0 * ((a - 1.0) + (a + 1.0) * cosW),
                      (a + 1.0) + (a - 1.0) * cosW - twoSqrtAAlpha);
}

Biquad designHighShelf(double sampleRate, double frequency, double gainDb, double q) {
    const double a = std::pow(10.0, gainDb / 40.0);
    const double w0 = 2.0 * kPi * nyquistSafe(sampleRate, frequency) / sampleRate;
    const double cosW = std::cos(w0);
    const double twoSqrtAAlpha = 2.0 * std::sqrt(a) * std::sin(w0) / (2.0 * q);
    return normalised(a * ((a + 1.0) + (a - 1.0) * cosW + twoSqrtAAlpha),
                      -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW),
                      a * ((a + 1.0) + (a - 1.0) * cosW - twoSqrtAAlpha),
                      (a + 1.0) - (a - 1.0) * cosW + twoSqrtAAlpha,
                      2.0 * ((a - 1.0) - (a + 1.0) * cosW),
                      (a + 1.0) - (a - 1.0) * cosW - twoSqrtAAlpha);
}

Biquad designHighPass(double sampleRate, double frequency, double q) {
    const double w0 = 2.0 * kPi * nyquistSafe(sampleRate, frequency) / sampleRate;
    const double cosW = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * q);
    return normalised((1.0 + cosW) / 2.0, -(1.0 + cosW), (1.0 + cosW) / 2.0,
                      1.0 + alpha, -2.0 * cosW, 1.0 - alpha);
}

Biquad designLowPass(double sampleRate, double frequency, double q) {
    const double w0 = 2.0 * kPi * nyquistSafe(sampleRate, frequency) / sampleRate;
    const double cosW = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * q);
    return normalised((1.0 - cosW) / 2.0, 1.0 - cosW, (1.0 - cosW) / 2.0,
                      1.0 + alpha, -2.0 * cosW, 1.0 - alpha);
}

double magnitudeAt(const Biquad& f, double sampleRate, double frequency) {
    const double w = 2.0 * kPi * frequency / sampleRate;
    const double c1 = std::cos(w), s1 = std::sin(w);
    const double c2 = std::cos(2.0 * w), s2 = std::sin(2.0 * w);
    const double numRe = f.b0 + f.b1 * c1 + f.b2 * c2;
    const double numIm = -(f.b1 * s1 + f.b2 * s2);
    const double denRe = 1.0 + f.a1 * c1 + f.a2 * c2;
    const double denIm = -(f.a1 * s1 + f.a2 * s2);
    return std::sqrt((numRe * numRe + numIm * numIm) / (denRe * denRe + denIm * denIm));
}

double proportionalQ(double gainDb) {
    return kPeakQAtFlat + kPeakQGrowth * std::min(std::fabs(gainDb), 12.0) / 12.0;
}

Biquad designBand(int band, double sampleRate, double gainDb) {
    const double frequency = kBandFrequencies[band];
    if (band == 0) return designLowShelf(sampleRate, frequency * kLowShelfCornerRatio, gainDb, kShelfQ);
    if (band == kNumBands - 1) {
        return designHighShelf(sampleRate, frequency * kHighShelfCornerRatio, gainDb, kShelfQ);
    }
    return designPeaking(sampleRate, frequency, gainDb, proportionalQ(gainDb));
}

// ---- SPSC ring ------------------------------------------------------------------------

uint32_t SpscRing::push(const float* samples, uint32_t count) {
    const uint32_t write = write_.load(std::memory_order_relaxed);
    const uint32_t read = read_.load(std::memory_order_acquire);
    const uint32_t room = kCapacity - (write - read);
    const uint32_t n = std::min(count, room);
    for (uint32_t i = 0; i < n; ++i) data_[(write + i) & (kCapacity - 1)] = samples[i];
    write_.store(write + n, std::memory_order_release);
    return n;
}

uint32_t SpscRing::pop(float* out, uint32_t maxCount) {
    const uint32_t read = read_.load(std::memory_order_relaxed);
    const uint32_t write = write_.load(std::memory_order_acquire);
    const uint32_t n = std::min(write - read, maxCount);
    for (uint32_t i = 0; i < n; ++i) out[i] = data_[(read + i) & (kCapacity - 1)];
    read_.store(read + n, std::memory_order_release);
    return n;
}

void SpscRing::discard() {
    read_.store(write_.load(std::memory_order_acquire), std::memory_order_release);
}

// ---- FFT ------------------------------------------------------------------------------

Fft::Fft(int size) : size_(size), log2Size_(0) {
    while ((1 << log2Size_) < size_) ++log2Size_;
    cos_ = new float[size_ / 2];
    sin_ = new float[size_ / 2];
    bitReverse_ = new uint32_t[size_];
    for (int k = 0; k < size_ / 2; ++k) {
        cos_[k] = static_cast<float>(std::cos(2.0 * kPi * k / size_));
        sin_[k] = static_cast<float>(std::sin(2.0 * kPi * k / size_));
    }
    for (int i = 0; i < size_; ++i) {
        uint32_t r = 0;
        for (int b = 0; b < log2Size_; ++b) r |= ((static_cast<uint32_t>(i) >> b) & 1U) << (log2Size_ - 1 - b);
        bitReverse_[i] = r;
    }
}

Fft::~Fft() {
    delete[] cos_;
    delete[] sin_;
    delete[] bitReverse_;
}

void Fft::forward(float* re, float* im) const {
    for (int i = 0; i < size_; ++i) {
        const int j = static_cast<int>(bitReverse_[i]);
        if (j > i) {
            std::swap(re[i], re[j]);
            std::swap(im[i], im[j]);
        }
    }
    for (int length = 2; length <= size_; length <<= 1) {
        const int half = length >> 1;
        const int step = size_ / length;
        for (int start = 0; start < size_; start += length) {
            for (int k = 0; k < half; ++k) {
                const float wr = cos_[k * step];
                const float wi = -sin_[k * step];
                const int a = start + k;
                const int b = a + half;
                const float tr = re[b] * wr - im[b] * wi;
                const float ti = re[b] * wi + im[b] * wr;
                re[b] = re[a] - tr;
                im[b] = im[a] - ti;
                re[a] += tr;
                im[a] += ti;
            }
        }
    }
}

// ---- Engine ---------------------------------------------------------------------------

MobileEqEngine::MobileEqEngine() {
    for (int b = 0; b < kNumBands; ++b) {
        userGainDb_[b].store(0.f, std::memory_order_relaxed);
        autoTargetDb_[b].store(0.f, std::memory_order_relaxed);
    }
    // Periodic Hann: the right window for spectral analysis of a stream.
    for (int n = 0; n < kFftSize; ++n) {
        window_[n] = static_cast<float>(0.5 - 0.5 * std::cos(2.0 * kPi * n / kFftSize));
        fftRe_[n] = 0.f;
        fftIm_[n] = 0.f;
    }
    configure(48000, 2);
}

MobileEqEngine::~MobileEqEngine() = default;

float MobileEqEngine::ceilingDb() const {
    return activeDevice_ == OutputDevice::kUsbDacHiRes ? kUsbCeilingDb : kCeilingDb;
}

bool MobileEqEngine::configure(int sampleRate, int channels) {
    if (sampleRate < kMinSampleRate || sampleRate > kMaxSampleRate) return false;
    if (channels < 1 || channels > kMaxChannels) return false;
    sampleRate_ = sampleRate;
    channels_ = channels;

    coefAlpha_ = 1.0 - std::exp(-1.0 / (kCoefficientSmoothingSeconds * sampleRate));
    gainAlpha_ = smoothingAlpha(kGainSmoothingSeconds, sampleRate);
    envAttack_ = smoothingAlpha(kEnvelopeAttackSeconds, sampleRate);
    envRelease_ = smoothingAlpha(kEnvelopeReleaseSeconds, sampleRate);
    releaseAlpha_ = smoothingAlpha(kLimiterReleaseSeconds, sampleRate);
    lookahead_ = std::clamp(static_cast<int>(std::lround(kLookaheadSeconds * sampleRate)), 1, kMaxLookahead);
    lookaheadDelay_ = lookahead_ - 1;
    autoRampFrames_ = std::max(1, static_cast<int>(std::lround(kAutoMorphSeconds * sampleRate)));
    subBassLowPass_ = designLowPass(sampleRate, kSubBassCutoffHz, kButterworthQ);
    saturatorKnee_ = dbToLinear(kSaturatorKneeDb);

    // Every coefficient depends on the rate: redesign all of them now, at full
    // precision, and start from them directly (the state is cleared anyway).
    activeDevice_ = static_cast<OutputDevice>(device_.load(std::memory_order_acquire));
    designHighPassForDevice();
    highPass_.current = highPass_.target;
    highPass_.settling = false;
    seenControlGeneration_ = controlGeneration_.load(std::memory_order_acquire) - 1;  // force a pass
    for (int b = 0; b < kNumBands; ++b) designedDb_[b] = 1000.f;  // force every band
    preampDirty_ = true;
    preampAgeFrames_ = sampleRate;  // recompute at once
    updateControls();
    for (auto& band : bands_) {
        band.current = band.target;
        band.settling = false;
    }
    preampLinear_ = preampTargetLinear_;

    analyzerSampleRate_.store(sampleRate, std::memory_order_release);
    analyzerEpoch_.fetch_add(1, std::memory_order_acq_rel);
    reset();
    return true;
}

void MobileEqEngine::reset() {
    std::memset(hpState_, 0, sizeof(hpState_));
    std::memset(bandState_, 0, sizeof(bandState_));
    subBassState_[0] = subBassState_[1] = 0.0;
    subBassEnvelope_ = 0.f;
    duckDb_ = 0.f;
    bassDuckDb_.store(0.f, std::memory_order_relaxed);
    highPass_.current = highPass_.target;
    highPass_.settling = false;
    for (auto& band : bands_) {
        band.current = band.target;
        band.settling = false;
    }
    preampLinear_ = preampTargetLinear_;

    std::fill(delay_, delay_ + lookahead_ * kMaxChannels, 0.f);
    delayPos_ = 0;
    minHead_ = 0;
    minCount_ = 0;
    std::fill(box_, box_ + lookahead_, 1.f);
    boxSum_ = static_cast<double>(lookahead_);
    boxPos_ = 0;
    releaseGain_ = 1.f;
    frameIndex_ = 0;
    primeFrames_ = lookaheadDelay_;
}

void MobileEqEngine::designHighPassForDevice() {
    double cutoff = 90.0;
    devicePadDb_ = 0.f;
    switch (activeDevice_) {
        case OutputDevice::kPhoneSpeaker: cutoff = 90.0; break;
        case OutputDevice::kWiredHeadphones: cutoff = 18.0; break;
        case OutputDevice::kBluetoothA2dp:
            cutoff = 24.0;
            devicePadDb_ = kBluetoothPadDb;
            break;
        case OutputDevice::kUsbDacHiRes: cutoff = 10.0; break;
    }
    highPass_.target = designHighPass(sampleRate_, cutoff, kButterworthQ);
    highPass_.settling = true;
    ceiling_ = dbToLinear(ceilingDb());
}

void MobileEqEngine::recomputePreamp(const float* gainsDb) {
    // The loudest point of the whole curve, not the loudest slider: two
    // neighbouring bands at +8 dB add up to more than +8 dB between them.
    const double top = std::min<double>(kMaxBarHz, sampleRate_ * 0.45);
    Biquad bands[kNumBands];
    for (int b = 0; b < kNumBands; ++b) bands[b] = designBand(b, sampleRate_, gainsDb[b]);
    double peakDb = -1000.0;
    auto evaluate = [&](double frequency) {
        double db = 0.0;
        for (int b = 0; b < kNumBands; ++b) {
            db += 20.0 * std::log10(std::max(1e-9, magnitudeAt(bands[b], sampleRate_, frequency)));
        }
        peakDb = std::max(peakDb, db);
    };
    for (int i = 0; i < kPreampGridPoints; ++i) {
        evaluate(kMinBarHz * std::pow(top / kMinBarHz, static_cast<double>(i) / (kPreampGridPoints - 1)));
    }
    for (int b = 0; b < kNumBands; ++b) evaluate(std::min<double>(kBandFrequencies[b], top));

    const float compensation = -kPreampCompensation * static_cast<float>(std::max(0.0, peakDb));
    float user = userPreampDb_.load(std::memory_order_relaxed);
    if (!std::isfinite(user)) user = 0.f;
    user = std::clamp(user, kMinUserPreampDb, kMaxUserPreampDb);
    float level = levelGainDb_.load(std::memory_order_relaxed);
    level = std::isfinite(level) ? std::clamp(level, kMinLevelGainDb, kMaxLevelGainDb) : 0.f;
    const float total = compensation + devicePadDb_ + user + level;
    targetPreampDb_.store(total, std::memory_order_relaxed);
    preampTargetLinear_ = dbToLinear(total);
    std::copy(gainsDb, gainsDb + kNumBands, preampSourceDb_);
    preampDirty_ = false;
    preampAgeFrames_ = 0;
}

void MobileEqEngine::updateControls() {
    const uint32_t generation = controlGeneration_.load(std::memory_order_acquire);
    if (generation != seenControlGeneration_) {
        seenControlGeneration_ = generation;
        preampDirty_ = true;  // the user preamp may have moved
    }

    const auto device = static_cast<OutputDevice>(device_.load(std::memory_order_acquire));
    if (device != activeDevice_) {
        activeDevice_ = device;
        designHighPassForDevice();
        preampDirty_ = true;
    }

    const bool eqOn = eqEnabled_.load(std::memory_order_relaxed);
    const bool autoOn = eqOn && autoMode_.load(std::memory_order_relaxed);

    float desired[kNumBands];
    if (autoOn) {
        const uint32_t autoGeneration = autoGeneration_.load(std::memory_order_acquire);
        if (autoGeneration != seenAutoGeneration_ || !autoWasOn_) {
            // Morph from whatever is heard right now to the new curve.
            for (int b = 0; b < kNumBands; ++b) {
                autoFromDb_[b] = autoWasOn_ ? autoNowDb_[b]
                                            : clampGainDb(userGainDb_[b].load(std::memory_order_relaxed));
                autoToDb_[b] = clampGainDb(autoTargetDb_[b].load(std::memory_order_relaxed));
            }
            autoRampPos_ = 0;
            seenAutoGeneration_ = autoGeneration;
        }
        const float t = std::min(1.f, static_cast<float>(autoRampPos_) / static_cast<float>(autoRampFrames_));
        // Smoothstep: no corner at either end of the 400 ms morph.
        const float eased = t * t * (3.f - 2.f * t);
        for (int b = 0; b < kNumBands; ++b) {
            autoNowDb_[b] = autoFromDb_[b] + (autoToDb_[b] - autoFromDb_[b]) * eased;
            desired[b] = autoNowDb_[b];
        }
        if (autoRampPos_ < autoRampFrames_) autoRampPos_ += kBlock;
    } else {
        for (int b = 0; b < kNumBands; ++b) {
            desired[b] = eqOn ? clampGainDb(userGainDb_[b].load(std::memory_order_relaxed)) : 0.f;
        }
    }
    autoWasOn_ = autoOn;

    // Stage 3: dynamic bass. When the track already carries sub-bass peaks
    // above -6 dBFS, take back up to 4 dB of the 31/62 Hz boost while they hit.
    float duckTarget = 0.f;
    if (autoOn) {
        const float envelopeDb = 20.f * std::log10(subBassEnvelope_ + 1e-9f);
        duckTarget = std::clamp((envelopeDb - kDuckThresholdDb) / kDuckRangeDb, 0.f, 1.f) * kMaxDuckDb;
    }
    if (duckTarget > duckDb_) {
        duckDb_ = duckTarget;  // fast: the coefficient smoothing (8 ms) shapes the attack
    } else {
        const float release = smoothingAlpha(kDuckReleaseSeconds, static_cast<double>(sampleRate_) / kBlock);
        duckDb_ += (duckTarget - duckDb_) * release;
    }
    bassDuckDb_.store(duckDb_, std::memory_order_relaxed);

    for (int b = 0; b < kNumBands; ++b) {
        float effective = desired[b];
        if (b < 2) effective -= std::min(duckDb_, std::max(0.f, effective));
        if (std::fabs(effective - designedDb_[b]) > 0.005f) {
            bands_[b].target = designBand(b, sampleRate_, effective);
            bands_[b].settling = true;
            designedDb_[b] = effective;
        }
    }

    // The preamp follows the curve without the ducking, which only ever lowers it.
    if (!preampDirty_) {
        for (int b = 0; b < kNumBands; ++b) {
            if (std::fabs(desired[b] - preampSourceDb_[b]) > 0.005f) {
                preampDirty_ = true;
                break;
            }
        }
    }
    if (preampDirty_ && preampAgeFrames_ >= static_cast<long>(kPreampRecomputeSeconds * sampleRate_)) {
        recomputePreamp(desired);
    }
    preampAgeFrames_ += kBlock;
}

float MobileEqEngine::limitFrame(float* frame, float* out) {
    // Required gain for this frame to sit under the ceiling.
    float peak = 0.f;
    for (int c = 0; c < channels_; ++c) peak = std::max(peak, std::fabs(frame[c]));
    const float required = peak > ceiling_ ? ceiling_ / peak : 1.f;

    // Minimum over the lookahead window (monotonic deque).
    const int capacity = lookahead_;
    while (minCount_ > 0) {
        const int back = (minHead_ + minCount_ - 1) % capacity;
        if (minValue_[back] < required) break;
        --minCount_;
    }
    const int slot = (minHead_ + minCount_) % capacity;
    minValue_[slot] = required;
    minIndex_[slot] = frameIndex_;
    ++minCount_;
    while (minIndex_[minHead_] <= frameIndex_ - lookahead_) {
        minHead_ = (minHead_ + 1) % capacity;
        --minCount_;
    }
    const float windowMin = minValue_[minHead_];

    // Instant attack, smooth release, then a box average over the lookahead:
    // the gain ramps down across the 2 ms before a peak and is at or below the
    // peak's required gain when the peak comes out of the delay line.
    releaseGain_ = windowMin < releaseGain_ ? windowMin
                                            : releaseGain_ + (windowMin - releaseGain_) * releaseAlpha_;
    boxSum_ += static_cast<double>(releaseGain_) - box_[boxPos_];
    box_[boxPos_] = releaseGain_;
    if (++boxPos_ == lookahead_) boxPos_ = 0;
    if ((frameIndex_ & 0xFFFF) == 0) {  // stop float drift from accumulating
        boxSum_ = 0.0;
        for (int i = 0; i < lookahead_; ++i) boxSum_ += box_[i];
    }
    const float gain = std::min(1.f, static_cast<float>(boxSum_ / lookahead_));
    ++frameIndex_;

    if (lookaheadDelay_ == 0) {
        for (int c = 0; c < channels_; ++c) out[c] = std::clamp(frame[c] * gain, -ceiling_, ceiling_);
        return gain;
    }
    float* stored = delay_ + delayPos_ * kMaxChannels;
    for (int c = 0; c < channels_; ++c) {
        const float delayed = stored[c];
        stored[c] = frame[c];
        // The clamp is a backstop for rounding; the gain already guarantees it.
        out[c] = std::clamp(delayed * gain, -ceiling_, ceiling_);
    }
    if (++delayPos_ == lookaheadDelay_) delayPos_ = 0;
    return gain;
}

int MobileEqEngine::processBlock(float* io, int frames) {
    // io holds frames x channels_ interleaved floats; output is compacted in
    // place (it never overtakes the input) while the lookahead primes.
    const int channels = channels_;
    int written = 0;
    float frame[kMaxChannels];
    float out[kMaxChannels];
    const float knee = saturatorKnee_;
    const float headroom = 1.f - knee;
    for (int i = 0; i < frames; ++i) {
        // Stage 1: de-zippering. Coefficients and the preamp glide per sample.
        if (highPass_.settling) approach(highPass_.current, highPass_.target, coefAlpha_);
        for (auto& band : bands_) {
            if (band.settling) approach(band.current, band.target, coefAlpha_);
        }
        preampLinear_ += (preampTargetLinear_ - preampLinear_) * gainAlpha_;

        const float* in = io + static_cast<size_t>(i) * channels;
        double mono = 0.0;
        for (int c = 0; c < channels; ++c) {
            double y = runBiquad(highPass_.current, hpState_[c], in[c]);
            mono += y;
            y *= preampLinear_;
            // Stage 2: ten cascaded biquads.
            for (int b = 0; b < kNumBands; ++b) y = runBiquad(bands_[b].current, bandState_[b][c], y);
            float x = static_cast<float>(y);
            // Stage 4a: soft-knee saturation above -1.8 dBFS (Padé tanh on the excess).
            const float magnitude = std::fabs(x);
            if (magnitude > knee) {
                const float shaped = knee + headroom * padeTanh((magnitude - knee) / headroom);
                x = x < 0.f ? -shaped : shaped;
            }
            frame[c] = x;
        }

        // Stage 3 detector: sub-bass (below 80 Hz) of what comes in, after the high-pass.
        const auto subBass = static_cast<float>(std::fabs(runBiquad(subBassLowPass_, subBassState_, mono / channels)));
        subBassEnvelope_ += (subBass - subBassEnvelope_) * (subBass > subBassEnvelope_ ? envAttack_ : envRelease_);

        // Stage 4b: lookahead limiter.
        limitFrame(frame, out);
        if (primeFrames_ > 0) {
            --primeFrames_;
            continue;
        }
        float* dst = io + static_cast<size_t>(written) * channels;
        for (int c = 0; c < channels; ++c) dst[c] = out[c];
        ++written;
    }

    for (auto& band : bands_) {
        if (band.settling && settled(band.current, band.target)) {
            band.current = band.target;
            band.settling = false;
        }
    }
    if (highPass_.settling && settled(highPass_.current, highPass_.target)) {
        highPass_.current = highPass_.target;
        highPass_.settling = false;
    }
    return written;
}

void MobileEqEngine::pushAnalyzer(const float* io, int frames) {
    if (!analyzerEnabled_.load(std::memory_order_relaxed) || frames <= 0) return;
    float mono[kBlock];
    const int channels = channels_;
    const float scale = 1.f / static_cast<float>(channels);
    for (int i = 0; i < frames; ++i) {
        float sum = 0.f;
        for (int c = 0; c < channels; ++c) sum += io[static_cast<size_t>(i) * channels + c];
        mono[i] = sum * scale;  // (L + R) * 0.5 for stereo
    }
    ring_.push(mono, static_cast<uint32_t>(frames));
}

int MobileEqEngine::process(const void* input, SampleFormat format, int frames, float* output) {
    if (frames <= 0) return 0;
    ScopedFlushDenormals flushDenormals;
    const int channels = channels_;
    const int sampleBytes = bytesPerSample(format);
    if (sampleBytes == 0) return 0;

    // Convert to float into the output buffer first. Front to back is safe
    // even in place, as a float never takes less room than its source sample.
    const auto* src = static_cast<const uint8_t*>(input);
    const size_t samples = static_cast<size_t>(frames) * channels;
    if (!(format == SampleFormat::kFloat && static_cast<const void*>(output) == input)) {
        for (size_t s = 0; s < samples; ++s) output[s] = readSample(src + s * sampleBytes, format);
    } else {
        for (size_t s = 0; s < samples; ++s) {
            if (!std::isfinite(output[s])) output[s] = 0.f;
        }
    }

    int produced = 0;
    for (int start = 0; start < frames; start += kBlock) {
        const int count = std::min(kBlock, frames - start);
        updateControls();
        float* block = output + static_cast<size_t>(start) * channels;
        const int written = processBlock(block, count);
        if (written > 0 && block != output + static_cast<size_t>(produced) * channels) {
            std::memmove(output + static_cast<size_t>(produced) * channels, block,
                         static_cast<size_t>(written) * channels * sizeof(float));
        }
        pushAnalyzer(output + static_cast<size_t>(produced) * channels, written);
        produced += written;
    }
    return produced;
}

int MobileEqEngine::drain(float* output, int maxFrames) {
    ScopedFlushDenormals flushDenormals;
    const int channels = channels_;
    float silence[kMaxChannels] = {};
    float out[kMaxChannels];
    int produced = 0;
    // Push silence through the limiter until the real audio it still holds is out.
    // The delay line holds lookaheadDelay_ frames; after a short stream some of
    // those are the initial zeros, which the prime counter skips.
    int iterations = lookaheadDelay_;
    while (iterations-- > 0 && produced < maxFrames) {
        limitFrame(silence, out);
        if (primeFrames_ > 0) {
            --primeFrames_;
            continue;
        }
        for (int c = 0; c < channels; ++c) output[static_cast<size_t>(produced) * channels + c] = out[c];
        ++produced;
    }
    for (int start = 0; start < produced; start += kBlock) {
        pushAnalyzer(output + static_cast<size_t>(start) * channels, std::min(kBlock, produced - start));
    }
    // The stream has ended: start the next one from a clean, primed state.
    reset();
    return produced;
}

// ---- Controls ---------------------------------------------------------------------------

void MobileEqEngine::setBandGainDb(int band, float gainDb) {
    if (band < 0 || band >= kNumBands) return;
    userGainDb_[band].store(clampGainDb(gainDb), std::memory_order_relaxed);
    controlGeneration_.fetch_add(1, std::memory_order_release);
}

void MobileEqEngine::setBandGainsDb(const float* gainsDb) {
    for (int b = 0; b < kNumBands; ++b) userGainDb_[b].store(clampGainDb(gainsDb[b]), std::memory_order_relaxed);
    controlGeneration_.fetch_add(1, std::memory_order_release);
}

void MobileEqEngine::setEqEnabled(bool enabled) {
    eqEnabled_.store(enabled, std::memory_order_relaxed);
    controlGeneration_.fetch_add(1, std::memory_order_release);
}

void MobileEqEngine::setAutoMode(bool enabled) {
    autoMode_.store(enabled, std::memory_order_relaxed);
    controlGeneration_.fetch_add(1, std::memory_order_release);
}

void MobileEqEngine::setAutoTargetGainsDb(const float* gainsDb) {
    for (int b = 0; b < kNumBands; ++b) autoTargetDb_[b].store(clampGainDb(gainsDb[b]), std::memory_order_relaxed);
    autoGeneration_.fetch_add(1, std::memory_order_release);
}

void MobileEqEngine::setUserPreampDb(float preampDb) {
    userPreampDb_.store(std::isfinite(preampDb) ? preampDb : 0.f, std::memory_order_relaxed);
    controlGeneration_.fetch_add(1, std::memory_order_release);
}

void MobileEqEngine::setLevelGainDb(float gainDb) {
    levelGainDb_.store(std::isfinite(gainDb) ? gainDb : 0.f, std::memory_order_relaxed);
    controlGeneration_.fetch_add(1, std::memory_order_release);
}

void MobileEqEngine::setOutputDevice(OutputDevice device) {
    device_.store(static_cast<int32_t>(device), std::memory_order_release);
}

void MobileEqEngine::setAnalyzerEnabled(bool enabled) {
    analyzerEnabled_.store(enabled, std::memory_order_relaxed);
}

void MobileEqEngine::setSpectrumBarCount(int bars) {
    barCount_.store(std::clamp(bars, kMinBars, kMaxBars), std::memory_order_relaxed);
}

// ---- Spectrum ---------------------------------------------------------------------------

void MobileEqEngine::rebuildBarMap(int sampleRate, int bars) {
    const float binHz = static_cast<float>(sampleRate) / kFftSize;
    const float top = std::min(kMaxBarHz, 0.5f * sampleRate * 0.995f);
    for (int i = 0; i < bars; ++i) {
        const float low = kMinBarHz * std::pow(top / kMinBarHz, static_cast<float>(i) / bars);
        const float high = kMinBarHz * std::pow(top / kMinBarHz, static_cast<float>(i + 1) / bars);
        barLowBin_[i] = std::max(1, static_cast<int>(std::ceil(low / binHz)));
        barHighBin_[i] = std::min(kFftSize / 2, static_cast<int>(std::floor(high / binHz)));
        barCenterBin_[i] = std::sqrt(low * high) / binHz;
    }
    if (bars != mappedBars_) {
        std::fill(barLevel_, barLevel_ + kMaxBars, 0.f);
        std::fill(peakLevel_, peakLevel_ + kMaxBars, 0.f);
        std::fill(peakHold_, peakHold_ + kMaxBars, 0.f);
        std::fill(peakVelocity_, peakVelocity_ + kMaxBars, 0.f);
    }
    mappedSampleRate_ = sampleRate;
    mappedBars_ = bars;
}

int MobileEqEngine::pollSpectrum(float* bars, float* peaks, int capacity) {
    return pollSpectrumAt(nowNanos(), bars, peaks, capacity);
}

int MobileEqEngine::pollSpectrumAt(int64_t now, float* bars, float* peaks, int capacity) {
    const int barCount = barCount_.load(std::memory_order_relaxed);
    const int sampleRate = analyzerSampleRate_.load(std::memory_order_acquire);
    const uint32_t epoch = analyzerEpoch_.load(std::memory_order_acquire);
    if (epoch != seenAnalyzerEpoch_) {
        // The rate changed: whatever is queued was recorded at another one.
        seenAnalyzerEpoch_ = epoch;
        ring_.discard();
        std::fill(history_, history_ + kFftSize, 0.f);
        historyPos_ = 0;
        pendingHead_ = 0;
        pendingCount_ = 0;
    }
    if (sampleRate != mappedSampleRate_ || barCount != mappedBars_) rebuildBarMap(sampleRate, barCount);

    float dt = lastPollNanos_ == 0 ? 1.f / 60.f : static_cast<float>(now - lastPollNanos_) * 1e-9f;
    dt = std::clamp(dt, 0.001f, 0.1f);
    lastPollNanos_ = now;

    // Queue everything the audio thread has handed over...
    const uint32_t got = ring_.pop(popScratch_, SpscRing::kCapacity);
    for (uint32_t i = 0; i < got; ++i) {
        if (pendingCount_ == kPendingCapacity) {  // full: drop the oldest
            pendingHead_ = (pendingHead_ + 1) & (kPendingCapacity - 1);
            --pendingCount_;
        }
        pending_[(pendingHead_ + pendingCount_) & (kPendingCapacity - 1)] = popScratch_[i];
        ++pendingCount_;
    }
    // ...and read it at the playback rate, catching up if it has fallen far behind.
    uint32_t advance = static_cast<uint32_t>(std::lround(dt * sampleRate));
    const auto maxLag = static_cast<uint32_t>(kMaxLagSeconds * sampleRate);
    if (pendingCount_ > maxLag + advance) advance = pendingCount_ - maxLag;
    advance = std::min(advance, pendingCount_);
    if (advance == 0) {
        staleSeconds_ += dt;
    } else {
        staleSeconds_ = 0.f;
    }
    const uint32_t skip = advance > kFftSize ? advance - kFftSize : 0;
    pendingHead_ = (pendingHead_ + skip) & (kPendingCapacity - 1);
    for (uint32_t i = skip; i < advance; ++i) {
        history_[historyPos_] = pending_[pendingHead_];
        pendingHead_ = (pendingHead_ + 1) & (kPendingCapacity - 1);
        if (++historyPos_ == kFftSize) historyPos_ = 0;
    }
    pendingCount_ -= advance;
    const bool silent = staleSeconds_ > kStaleSeconds;

    for (int n = 0; n < kFftSize; ++n) {
        const int index = (historyPos_ + n) & (kFftSize - 1);  // oldest first
        fftRe_[n] = history_[index] * window_[n];
        fftIm_[n] = 0.f;
    }
    fft_.forward(fftRe_, fftIm_);

    // A full-scale sine at a bin centre reads |X| = N/4 through a Hann window.
    const float normalisationDb = 20.f * std::log10(4.f / kFftSize);
    const float attack = 1.f - std::exp(-dt / kAttackSeconds);
    const float decay = kDecayDbPerSecond / -kFloorDb * dt;
    auto power = [&](int bin) { return fftRe_[bin] * fftRe_[bin] + fftIm_[bin] * fftIm_[bin]; };

    const int count = std::min(barCount, capacity);
    for (int i = 0; i < barCount; ++i) {
        float binPower;
        if (barHighBin_[i] >= barLowBin_[i]) {
            binPower = 0.f;
            for (int k = barLowBin_[i]; k <= barHighBin_[i]; ++k) binPower = std::max(binPower, power(k));
        } else {
            // Narrower than one bin (the lowest bars): read between the two nearest bins.
            const float position = std::clamp(barCenterBin_[i], 1.f, kFftSize / 2.f - 1.f);
            const int k = static_cast<int>(position);
            const float frac = position - k;
            binPower = power(k) * (1.f - frac) + power(k + 1) * frac;
        }
        const float db = 10.f * std::log10(binPower + 1e-20f) + normalisationDb;
        // Nothing new for a while (paused): let the bars fall rather than freeze.
        const float target = silent ? 0.f : std::clamp((db - kFloorDb) / -kFloorDb, 0.f, 1.f);

        float& level = barLevel_[i];
        level = target > level ? level + (target - level) * attack : std::max(target, level - decay);

        float& peak = peakLevel_[i];
        if (level >= peak) {
            peak = level;
            peakHold_[i] = kPeakHoldSeconds;
            peakVelocity_[i] = 0.f;
        } else if (peakHold_[i] > 0.f) {
            peakHold_[i] -= dt;
        } else {
            peakVelocity_[i] += kPeakGravity * dt;
            peak = std::max(level, peak - peakVelocity_[i] * dt);
        }
        if (i < count) {
            if (bars != nullptr) bars[i] = level;
            if (peaks != nullptr) peaks[i] = peak;
        }
    }
    return count;
}

}  // namespace harmony::eq
