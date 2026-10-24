// SmartShuffleEngine.cpp: see SmartShuffleEngine.h for the design.

#include "SmartShuffleEngine.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <limits>
#include <map>
#include <numeric>

namespace harmony::shuffle {

const float kBandCentersHz[kBands] = {31.25f, 62.5f, 125.0f, 250.0f, 500.0f,
                                      1000.0f, 2000.0f, 4000.0f, 8000.0f, 16000.0f};

namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr float kEps = 1e-12f;

/** Weight of each group of dimensions in the working space. */
constexpr float kSpectralGroupWeight = 1.00f;
constexpr float kDynamicsGroupWeight = 0.60f;
constexpr float kMacroGroupWeight = 1.10f;

/** "Acoustically distinct" from a skipped song (Check 2). */
constexpr float kDistinctCosine = 0.50f;
constexpr float kSkipTailShare = 0.60f;
constexpr float kNormTolerance = 0.001f;

/** splitmix64: small, fast, and reproducible from one seed. */
struct Rng {
    std::uint64_t state;
    explicit Rng(std::uint64_t seed) : state(seed ^ 0x9E3779B97F4A7C15ull) {}
    std::uint64_t next() {
        std::uint64_t z = (state += 0x9E3779B97F4A7C15ull);
        z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ull;
        z = (z ^ (z >> 27)) * 0x94D049BB133111EBull;
        return z ^ (z >> 31);
    }
    /** Uniform in the open interval (0, 1). */
    double uniform() { return (static_cast<double>(next() >> 11) + 0.5) * (1.0 / 9007199254740992.0); }
};

float clamp01(float v) { return std::isfinite(v) ? std::min(1.0f, std::max(0.0f, v)) : 0.0f; }

float groupWeight(int dim) {
    if (dim < kBands) return kSpectralGroupWeight;
    if (dim < 2 * kBands) return kDynamicsGroupWeight;
    return kMacroGroupWeight;
}

float entropyBits(const std::map<std::int64_t, int>& counts, int total) {
    if (total <= 0) return 0.0f;
    double h = 0.0;
    for (const auto& [key, n] : counts) {
        (void) key;
        if (n <= 0) continue;
        const double p = static_cast<double>(n) / total;
        h -= p * std::log2(p);
    }
    return static_cast<float>(h);
}

}  // namespace

// ===========================================================================
// Part 1: AcousticExtractor
// ===========================================================================

AcousticExtractor::AcousticExtractor(int sampleRate) {
    if (sampleRate < 8000 || sampleRate > 768000) return;
    sampleRate_ = sampleRate;
    blockLength_ = std::max(1, sampleRate / 20);  // 50 ms

    // The EQ engine's analysis bank: RBJ band-passes, one octave wide (Q 1.414).
    constexpr double kQ = 1.414;
    for (int k = 0; k < kBands; ++k) {
        const double fc = kBandCentersHz[k];
        Biquad& b = bands_[k];
        if (fc >= 0.45 * sampleRate) continue;  // above what this file can hold
        const double w0 = 2.0 * kPi * fc / sampleRate;
        const double alpha = std::sin(w0) / (2.0 * kQ);
        const double a0 = 1.0 + alpha;
        b.b0 = alpha / a0;
        b.b1 = 0.0;
        b.b2 = -alpha / a0;
        b.a1 = -2.0 * std::cos(w0) / a0;
        b.a2 = (1.0 - alpha) / a0;
        b.enabled = true;
    }

    // Sub-bass: 80 Hz Butterworth low-pass, for the punch measurement.
    const double w0 = 2.0 * kPi * 80.0 / sampleRate;
    const double alpha = std::sin(w0) / (2.0 * 0.7071067811865476);
    const double a0 = 1.0 + alpha;
    const double c = std::cos(w0);
    subLow_.b0 = (1.0 - c) / 2.0 / a0;
    subLow_.b1 = (1.0 - c) / a0;
    subLow_.b2 = subLow_.b0;
    subLow_.a1 = -2.0 * c / a0;
    subLow_.a2 = (1.0 - alpha) / a0;
    subLow_.enabled = true;
}

void AcousticExtractor::process(const float* mono, int count) {
    if (!valid() || mono == nullptr || count <= 0) return;
    for (int i = 0; i < count; ++i) {
        double x = mono[i];
        if (!std::isfinite(x)) x = 0.0;
        x = std::min(4.0, std::max(-4.0, x));
        for (int k = 0; k < kBands; ++k) {
            Biquad& b = bands_[k];
            if (!b.enabled) continue;
            const double y = b.run(x);
            blockBandPower_[k] += y * y;
        }
        const double s = subLow_.run(x);
        blockSubPower_ += s * s;
        blockBroadPower_ += x * x;
        blockPeak_ = std::max(blockPeak_, std::fabs(x));
        if (++blockFill_ >= blockLength_) closeBlock();
    }
}

void AcousticExtractor::closeBlock() {
    const double n = std::max(1, blockFill_);
    const double broadPower = blockBroadPower_ / n;

    // Flush denormal-range filter state left by silence.
    auto flush = [](Biquad& b) {
        if (std::fabs(b.z1) < 1e-30) b.z1 = 0.0;
        if (std::fabs(b.z2) < 1e-30) b.z2 = 0.0;
    };
    for (auto& b : bands_) flush(b);
    flush(subLow_);

    const bool silent = !(broadPower >= 1e-7);  // below -70 dBFS (or NaN)
    if (!silent) {
        ++activeBlocks_;
        double flux = 0.0;
        int enabledBands = 0;
        for (int k = 0; k < kBands; ++k) {
            if (!bands_[k].enabled) continue;
            ++enabledBands;
            const double p = blockBandPower_[k] / n;
            const double a = std::sqrt(std::max(0.0, p));
            bandPowerSum_[k] += p;
            bandAmpSum_[k] += a;
            bandAmpSqSum_[k] += a * a;
            const double logAmp = 10.0 * std::log10(p + 1e-10);
            if (havePreviousBlock_) flux += std::max(0.0, logAmp - previousLogAmp_[k]);
            previousLogAmp_[k] = logAmp;
        }
        if (enabledBands > 0) flux /= enabledBands;
        broadPowerSum_ += broadPower;
        broadAmpSum_ += std::sqrt(broadPower);

        const double subAmp = std::sqrt(std::max(0.0, blockSubPower_ / n));
        if (havePreviousBlock_) subRiseSum_ += std::max(0.0, subAmp - previousSubAmp_);
        previousSubAmp_ = subAmp;
        peak_ = std::max(peak_, blockPeak_);

        // Onsets: a local maximum of the band flux above its adaptive threshold
        // (1.5x the last second's average plus 1 dB). The block before this one
        // is judged now that we know it was a peak.
        double mean = 0.0;
        for (int i = 0; i < fluxHistoryFill_; ++i) mean += fluxHistory_[i];
        if (fluxHistoryFill_ > 0) mean /= fluxHistoryFill_;
        const double threshold = 1.5 * mean + 1.0;
        if (havePreviousBlock_ && previousRising_ && previousFlux_ > previousThreshold_ && previousFlux_ >= flux) {
            ++onsets_;
        }
        previousRising_ = flux > previousFlux_;
        previousFlux_ = flux;
        previousThreshold_ = threshold;
        fluxHistory_[fluxHistoryPos_] = flux;
        fluxHistoryPos_ = (fluxHistoryPos_ + 1) % 20;
        fluxHistoryFill_ = std::min(20, fluxHistoryFill_ + 1);
        havePreviousBlock_ = true;
    }

    std::fill(std::begin(blockBandPower_), std::end(blockBandPower_), 0.0);
    blockBroadPower_ = 0.0;
    blockSubPower_ = 0.0;
    blockPeak_ = 0.0;
    blockFill_ = 0;
}

double AcousticExtractor::activeSeconds() const {
    if (!valid()) return 0.0;
    return static_cast<double>(activeBlocks_) * blockLength_ / sampleRate_;
}

bool AcousticExtractor::finish(float out[kDims]) {
    if (!valid() || out == nullptr) return false;
    if (blockFill_ >= blockLength_ / 2) closeBlock();
    if (activeBlocks_ < 40) return false;  // under two seconds of sound
    const double blocks = static_cast<double>(activeBlocks_);

    // 0-9: each band's RMS level, L2-normalized across the bands.
    double amp[kBands];
    double norm = 0.0;
    for (int k = 0; k < kBands; ++k) {
        amp[k] = std::sqrt(std::max(0.0, bandPowerSum_[k] / blocks));
        norm += amp[k] * amp[k];
    }
    norm = std::sqrt(norm);
    if (!(norm > 1e-12)) return false;
    for (int k = 0; k < kBands; ++k) out[k] = clamp01(static_cast<float>(amp[k] / norm));

    // 10-19: groove dynamics, the coefficient of variation of each band's level.
    for (int k = 0; k < kBands; ++k) {
        const double mean = bandAmpSum_[k] / blocks;
        const double variance = std::max(0.0, bandAmpSqSum_[k] / blocks - mean * mean);
        const double cv = mean > 1e-9 ? std::sqrt(variance) / mean : 0.0;
        out[kBands + k] = clamp01(static_cast<float>(cv / (1.0 + cv)));
    }

    // 20: sub-bass punch, rises of the sub-bass envelope against the broadband level.
    const double broadAmp = broadAmpSum_ / blocks;
    const double punch = broadAmp > 1e-9 ? (subRiseSum_ / blocks) / broadAmp : 0.0;
    out[20] = clamp01(static_cast<float>(punch / (punch + 0.05)));

    // 21: spectral centroid on the octave axis.
    double weighted = 0.0;
    double total = 0.0;
    for (int k = 0; k < kBands; ++k) {
        const double p = bandPowerSum_[k] / blocks;
        weighted += k * p;
        total += p;
    }
    out[21] = clamp01(total > 0.0 ? static_cast<float>(weighted / total / (kBands - 1)) : 0.5f);

    // 22: crest factor, 3..30 dB.
    const double rms = std::sqrt(broadPowerSum_ / blocks);
    const double crestDb = rms > 1e-9 && peak_ > 0.0 ? 20.0 * std::log10(peak_ / rms) : 3.0;
    out[22] = clamp01(static_cast<float>((crestDb - 3.0) / 27.0));

    // 23: pulse rate, onsets per second (3 per second maps to 0.5).
    const double seconds = std::max(1e-3, activeSeconds());
    const double rate = static_cast<double>(onsets_) / seconds;
    out[23] = clamp01(static_cast<float>(rate / (rate + 3.0)));

    for (int d = 0; d < kDims; ++d) {
        if (!std::isfinite(out[d])) out[d] = 0.0f;
    }
    return true;
}

// ===========================================================================
// Telemetry helpers
// ===========================================================================

bool BrainAuditReport::healthy() const { return failedCount() == 0; }

int BrainAuditReport::healedCount() const {
    int n = 0;
    for (const auto& c : checks) n += c.status == AuditStatus::Healed ? 1 : 0;
    return n;
}

int BrainAuditReport::failedCount() const {
    int n = 0;
    for (const auto& c : checks) n += c.status == AuditStatus::Failed ? 1 : 0;
    return n;
}

// ===========================================================================
// Engine: vector math
// ===========================================================================

SmartShuffleEngine::SmartShuffleEngine() : brain_(*this) {}

float SmartShuffleEngine::dot(const float* a, const float* b) {
    // A fixed-length loop the compiler vectorizes (NEON on arm64, SSE on x86_64).
    float s = 0.0f;
    for (int d = 0; d < kDims; ++d) s += a[d] * b[d];
    return s;
}

float SmartShuffleEngine::distance(const float* a, const float* b) {
    float s = 0.0f;
    for (int d = 0; d < kDims; ++d) {
        const float diff = a[d] - b[d];
        s += diff * diff;
    }
    return std::sqrt(s);
}

bool SmartShuffleEngine::normalize(float* v) {
    double s = 0.0;
    for (int d = 0; d < kDims; ++d) {
        if (!std::isfinite(v[d])) return false;
        s += static_cast<double>(v[d]) * v[d];
    }
    if (!(s > 1e-12)) return false;
    const float inv = static_cast<float>(1.0 / std::sqrt(s));
    for (int d = 0; d < kDims; ++d) v[d] *= inv;
    return true;
}

int SmartShuffleEngine::zoneOf(const float* v) {
    if (v == nullptr) return -1;
    int best = 0;
    for (int k = 1; k < kBands; ++k) {
        if (v[k] > v[best]) best = k;
    }
    return best;
}

const char* SmartShuffleEngine::zoneWord(int zone) {
    if (zone < 0 || zone >= kZones) return "Mixed";
    if (zone <= 2) return "Bass";
    if (zone <= 4) return "Warm";
    if (zone <= 6) return "Vocal";
    if (zone <= 8) return "Bright";
    return "Air";
}

int SmartShuffleEngine::rowOf(std::int64_t id) const {
    const auto it = rowById_.find(id);
    return it == rowById_.end() ? -1 : it->second;
}

bool SmartShuffleEngine::vectorOf(std::int64_t id, float out[kDims]) const {
    const int row = rowOf(id);
    if (row < 0 || out == nullptr) return false;
    std::copy(vec(row), vec(row) + kDims, out);
    return true;
}

float SmartShuffleEngine::cosine(std::int64_t a, std::int64_t b) const {
    const int ra = rowOf(a);
    const int rb = rowOf(b);
    if (ra < 0 || rb < 0) return 0.0f;
    return dot(vec(ra), vec(rb));
}

int SmartShuffleEngine::zoneOfTrack(std::int64_t id) const {
    const int row = rowOf(id);
    return row < 0 ? -1 : zone_[row];
}

// ===========================================================================
// Engine: library and habits
// ===========================================================================

int SmartShuffleEngine::setLibrary(const std::int64_t* ids, const float* raw, const std::uint8_t* quality,
                                   const std::int32_t* artistIds, const std::int32_t* albumIds, int count) {
    const int n = (ids != nullptr && count > 0) ? count : 0;
    ids_.assign(ids, ids + n);
    vectors_.assign(static_cast<size_t>(n) * kDims, 0.0f);
    quality_.assign(n, VectorQuality::Real);
    artist_.assign(n, -1);
    album_.assign(n, -1);
    completed_.assign(n, 0);
    skipped_.assign(n, 0);
    lastPlayed_.assign(n, 0);
    favorite_.assign(n, 0);
    steering_.clear();
    weights_.steering = 0.0f;
    zone_.assign(n, -1);
    rowById_.clear();
    rowById_.reserve(static_cast<size_t>(n) * 2);
    repairedInputs_ = 0;
    counts_[0] = counts_[1] = counts_[2] = 0;
    candidateByRow_.clear();

    // Copy the raw features, turning corrupt values into "unknown".
    std::vector<float> values(static_cast<size_t>(n) * kDims, std::numeric_limits<float>::quiet_NaN());
    double sum[kDims] = {};
    double sumSq[kDims] = {};
    int known[kDims] = {};
    for (int i = 0; i < n; ++i) {
        for (int d = 0; d < kDims; ++d) {
            float x = raw != nullptr ? raw[static_cast<size_t>(i) * kDims + d]
                                     : std::numeric_limits<float>::quiet_NaN();
            if (std::isinf(x) || (std::isfinite(x) && std::fabs(x) > 1e6f)) {
                ++repairedInputs_;
                x = std::numeric_limits<float>::quiet_NaN();
            }
            values[static_cast<size_t>(i) * kDims + d] = x;
            if (std::isfinite(x)) {
                sum[d] += x;
                sumSq[d] += static_cast<double>(x) * x;
                ++known[d];
            }
        }
    }

    // Standardize against the library: mean and deviation of each dimension.
    float mean[kDims];
    float deviation[kDims];
    for (int d = 0; d < kDims; ++d) {
        if (known[d] >= 2) {
            const double m = sum[d] / known[d];
            const double var = std::max(0.0, sumSq[d] / known[d] - m * m);
            mean[d] = static_cast<float>(m);
            deviation[d] = std::max(0.02f, static_cast<float>(std::sqrt(var)));
        } else {
            mean[d] = known[d] == 1 ? static_cast<float>(sum[d]) : 0.0f;
            deviation[d] = 1.0f;
        }
    }

    for (int i = 0; i < n; ++i) {
        float* v = &vectors_[static_cast<size_t>(i) * kDims];
        int knownDims = 0;
        for (int d = 0; d < kDims; ++d) {
            const float x = values[static_cast<size_t>(i) * kDims + d];
            if (std::isfinite(x)) {
                ++knownDims;
                v[d] = std::min(4.0f, std::max(-4.0f, (x - mean[d]) / deviation[d])) * groupWeight(d);
            } else {
                v[d] = 0.0f;
            }
        }
        VectorQuality q = quality != nullptr
                              ? static_cast<VectorQuality>(std::min<std::uint8_t>(quality[i], 2))
                              : VectorQuality::Real;
        if (knownDims == 0 || !normalize(v)) {
            // Nothing to stand on: a fixed pseudo-random direction, so the
            // song still gets a place in the space and a fair, small chance.
            Rng rng(static_cast<std::uint64_t>(ids_[i]) * 0x2545F4914F6CDD1Dull + 17u);
            for (int d = 0; d < kDims; ++d) v[d] = static_cast<float>(rng.uniform() * 2.0 - 1.0);
            if (!normalize(v)) {
                std::fill(v, v + kDims, 0.0f);
                v[0] = 1.0f;
            }
            q = VectorQuality::None;
        }
        quality_[i] = q;
        ++counts_[static_cast<int>(q)];
        artist_[i] = artistIds != nullptr ? artistIds[i] : -1;
        album_[i] = albumIds != nullptr ? albumIds[i] : -1;
        zone_[i] = static_cast<std::int8_t>(zoneOf(v));
        rowById_[ids_[i]] = i;
    }

    if (hasSession_ && !normalize(session_.data())) {
        session_.fill(0.0f);
        hasSession_ = false;
    }
    return n;
}

void SmartShuffleEngine::setTrackStats(const std::int32_t* completedPlays, const std::int32_t* skippedPlays,
                                       const std::int64_t* lastPlayedMs, const std::uint8_t* favorite, int count) {
    const int n = std::min(count, size());
    for (int i = 0; i < n; ++i) {
        completed_[i] = completedPlays != nullptr ? std::max(0, completedPlays[i]) : 0;
        skipped_[i] = skippedPlays != nullptr ? std::max(0, skippedPlays[i]) : 0;
        lastPlayed_[i] = lastPlayedMs != nullptr ? std::max<std::int64_t>(0, lastPlayedMs[i]) : 0;
        favorite_[i] = favorite != nullptr && favorite[i] != 0 ? 1 : 0;
    }
}

void SmartShuffleEngine::setSteering(const float* perTrack, int count, float weight) {
    if (perTrack == nullptr || !(weight > 0.0f) || count <= 0) {
        steering_.clear();
        weights_.steering = 0.0f;
        return;
    }
    steering_.assign(size(), 0.5f);
    const int n = std::min(count, size());
    for (int i = 0; i < n; ++i) steering_[i] = std::isfinite(perTrack[i]) ? clamp01(perTrack[i]) : 0.5f;
    weights_.steering = std::min(3.0f, weight);
}

void SmartShuffleEngine::addTransitions(const std::int64_t* from, const std::int64_t* to, const float* weight,
                                        int count) {
    if (from == nullptr || to == nullptr || count <= 0) return;
    for (int i = 0; i < count; ++i) {
        const float w = weight != nullptr ? weight[i] : 1.0f;
        if (!std::isfinite(w) || w <= 0.0f || from[i] == to[i]) continue;
        transitions_[from[i]][to[i]] += w;
        transitionTotals_[from[i]] += w;
    }
}

void SmartShuffleEngine::clearTransitions() {
    transitions_.clear();
    transitionTotals_.clear();
}

float SmartShuffleEngine::markovProbability(std::int64_t from, std::int64_t to) const {
    const auto row = transitions_.find(from);
    if (row == transitions_.end()) return 0.0f;
    const auto cell = row->second.find(to);
    if (cell == row->second.end()) return 0.0f;
    float maxWeight = 0.0f;
    for (const auto& [id, w] : row->second) {
        (void) id;
        maxWeight = std::max(maxWeight, w);
    }
    const float total = transitionTotals_.count(from) ? transitionTotals_.at(from) : 0.0f;
    if (maxWeight <= 0.0f) return 0.0f;
    return clamp01(cell->second / maxWeight * (total / (total + 3.0f)));
}

void SmartShuffleEngine::setZoneBandit(const float* pulls, const float* rewards) {
    for (int c = 0; c < kZones; ++c) {
        for (int a = 0; a < kZones; ++a) {
            const float p = pulls != nullptr ? pulls[c * kZones + a] : 0.0f;
            const float r = rewards != nullptr ? rewards[c * kZones + a] : 0.0f;
            banditPulls_[c][a] = std::isfinite(p) ? std::max(0.0f, p) : 0.0f;
            banditRewards_[c][a] = std::isfinite(r) ? std::min(banditPulls_[c][a], std::max(0.0f, r)) : 0.0f;
        }
    }
}

void SmartShuffleEngine::zoneBandit(float* pulls, float* rewards) const {
    for (int c = 0; c < kZones; ++c) {
        for (int a = 0; a < kZones; ++a) {
            if (pulls != nullptr) pulls[c * kZones + a] = banditPulls_[c][a];
            if (rewards != nullptr) rewards[c * kZones + a] = banditRewards_[c][a];
        }
    }
}

void SmartShuffleEngine::setWeights(const Weights& w) {
    auto bounded = [](float v, float lo, float hi, float fallback) {
        return std::isfinite(v) ? std::min(hi, std::max(lo, v)) : fallback;
    };
    const Weights defaults;
    weights_.similarity = bounded(w.similarity, 0.0f, 5.0f, defaults.similarity);
    weights_.markov = bounded(w.markov, 0.0f, 5.0f, defaults.markov);
    weights_.affinity = bounded(w.affinity, 0.0f, 5.0f, defaults.affinity);
    weights_.explore = bounded(w.explore, 0.0f, 5.0f, defaults.explore);
    weights_.temperature = bounded(w.temperature, 0.02f, 2.0f, defaults.temperature);
    weights_.minArtistSpacing = std::min(20, std::max(0, w.minArtistSpacing));
    weights_.jerkThreshold = bounded(w.jerkThreshold, 0.1f, 2.0f, defaults.jerkThreshold);
    weights_.echoThreshold = bounded(w.echoThreshold, 0.5f, 1.0f, defaults.echoThreshold);
    // Steering keeps its own weight, set with setSteering.
}

// ===========================================================================
// Part 2: the session vibe vector
// ===========================================================================

int SmartShuffleEngine::contextZone() const { return hasSession_ ? zoneOf(session_.data()) : -1; }

void SmartShuffleEngine::rememberStart(std::int64_t id) {
    if (!started_.empty() && started_.back() == id) return;
    if (!started_.empty()) {
        if (predecessor_.size() > 512) predecessor_.clear();
        predecessor_[id] = started_.back();
    }
    started_.push_back(id);
    while (started_.size() > 64) started_.pop_front();
    sessionPlayed_.insert(id);
    if (hasSession_) pickedContext_[id] = contextZone();
    if (pickedContext_.size() > 512) pickedContext_.clear();
}

SessionEvent SmartShuffleEngine::onTrackStarted(std::int64_t id) {
    rememberStart(id);
    const int row = rowOf(id);
    if (hasSession_ || row < 0 || quality_[row] == VectorQuality::None) return SessionEvent::None;
    std::copy(vec(row), vec(row) + kDims, session_.begin());
    hasSession_ = true;
    return SessionEvent::Seeded;
}

SessionEvent SmartShuffleEngine::onPlaybackOutcome(std::int64_t id, float fraction, std::int64_t nowMs,
                                                   bool shufflePick) {
    learnedFrom_ = learnedTo_ = -1;
    learnedDelta_ = 0.0f;
    banditContext_ = banditArm_ = -1;
    const float f = std::isfinite(fraction) ? std::min(1.0f, std::max(0.0f, fraction)) : 0.0f;
    const bool played = f > 0.5f;
    const bool fastSkip = f < 0.2f;
    const int row = rowOf(id);

    // Affinity, in memory (the history database is the record).
    if (row >= 0) {
        if (played) ++completed_[row];
        if (fastSkip) ++skipped_[row];
        lastPlayed_[row] = std::max<std::int64_t>(lastPlayed_[row], nowMs);
    }

    // Markov habit: a song heard past half after the one before it.
    if (played) {
        const auto pred = predecessor_.find(id);
        if (pred != predecessor_.end() && pred->second != id) {
            transitions_[pred->second][id] += 1.0f;
            transitionTotals_[pred->second] += 1.0f;
            learnedFrom_ = pred->second;
            learnedTo_ = id;
            learnedDelta_ = 1.0f;
        }
    }

    // Contextual bandit: the zone Smart Shuffle reached for, in the zone the session was in.
    if (shufflePick && row >= 0) {
        const auto ctx = pickedContext_.find(id);
        const int context = ctx != pickedContext_.end() ? ctx->second : contextZone();
        if (context >= 0) {
            const float reward = played ? 1.0f : (fastSkip ? 0.0f : 0.5f);
            const int arm = zone_[row];
            banditPulls_[context][arm] += 1.0f;
            banditRewards_[context][arm] += reward;
            banditContext_ = context;
            banditArm_ = arm;
            banditReward_ = reward;
        }
    }

    if (row < 0 || quality_[row] == VectorQuality::None) return SessionEvent::Neutral;
    const float* v = vec(row);

    if (played) {
        for (int d = 0; d < kDims; ++d) likedSum_[d] += v[d];
        if (!hasSession_ || !normalize(session_.data())) {
            std::copy(v, v + kDims, session_.begin());
            hasSession_ = true;
            return SessionEvent::Seeded;
        }
        for (int d = 0; d < kDims; ++d) session_[d] = 0.60f * v[d] + 0.40f * session_[d];
        if (!normalize(session_.data())) std::copy(v, v + kDims, session_.begin());
        return SessionEvent::PlayEma;
    }

    if (fastSkip) {
        if (!hasSession_ || !normalize(session_.data())) {
            // Nothing to push away from yet: start opposite the skipped song.
            for (int d = 0; d < kDims; ++d) session_[d] = -v[d];
            hasSession_ = true;
        }
        std::array<float, kDims> before = session_;
        const float dotBefore = dot(before.data(), v);
        std::array<float, kDims> next{};
        for (int d = 0; d < kDims; ++d) next[d] = before[d] - 0.35f * v[d];
        bool moved = normalize(next.data()) && dot(next.data(), v) < dotBefore - 1e-4f;
        bool healed = false;
        if (!moved) {
            // S_t was the skipped song itself (or the update was degenerate):
            // remove its direction outright and lean on what the session liked.
            std::array<float, kDims> liked = likedSum_;
            const float along = dot(liked.data(), v);
            for (int d = 0; d < kDims; ++d) liked[d] -= along * v[d];
            const bool haveLiked = normalize(liked.data());
            for (int d = 0; d < kDims; ++d) {
                next[d] = before[d] - (dotBefore + 0.35f) * v[d] + (haveLiked ? 0.5f * liked[d] : 0.0f);
            }
            if (!normalize(next.data())) {
                for (int d = 0; d < kDims; ++d) next[d] = -v[d];
            }
            healed = true;
        }
        session_ = next;
        pendingSkip_.active = true;
        pendingSkip_.id = id;
        std::copy(v, v + kDims, pendingSkip_.vector.begin());
        pendingSkip_.dotBefore = dotBefore;
        pendingSkip_.dotAfter = dot(session_.data(), v);
        pendingSkip_.healed = healed;
        return SessionEvent::FastSkip;
    }
    return SessionEvent::Neutral;
}

SessionEvent SmartShuffleEngine::onManualSelect(std::int64_t id) {
    const int row = rowOf(id);
    pendingSkip_.active = false;
    if (row < 0 || quality_[row] == VectorQuality::None) return SessionEvent::None;
    const float* v = vec(row);
    if (hasSession_ && normalize(session_.data())) {
        for (int d = 0; d < kDims; ++d) session_[d] = 0.85f * v[d] + 0.15f * session_[d];
        if (!normalize(session_.data())) std::copy(v, v + kDims, session_.begin());
    } else {
        std::copy(v, v + kDims, session_.begin());
    }
    hasSession_ = true;
    return SessionEvent::ManualAnchor;
}

void SmartShuffleEngine::resetSession() {
    session_.fill(0.0f);
    likedSum_.fill(0.0f);
    hasSession_ = false;
    started_.clear();
    sessionPlayed_.clear();
    predecessor_.clear();
    pickedContext_.clear();
    pendingSkip_ = PendingSkip{};
    exploreBoost_ = 1.0f;
}

bool SmartShuffleEngine::lastLearnedTransition(std::int64_t* from, std::int64_t* to, float* delta) const {
    if (learnedFrom_ < 0 && learnedTo_ < 0) return false;
    if (from) *from = learnedFrom_;
    if (to) *to = learnedTo_;
    if (delta) *delta = learnedDelta_;
    return learnedDelta_ > 0.0f;
}

bool SmartShuffleEngine::lastBanditUpdate(int* context, int* arm, float* reward) const {
    if (banditContext_ < 0 || banditArm_ < 0) return false;
    if (context) *context = banditContext_;
    if (arm) *arm = banditArm_;
    if (reward) *reward = banditReward_;
    return true;
}

// ===========================================================================
// Part 3: scoring and queue building
// ===========================================================================

float SmartShuffleEngine::affinityOf(int row) const {
    const float c = static_cast<float>(completed_[row]);
    const float s = static_cast<float>(skipped_[row]);
    const float rate = (c + 1.0f) / (c + s + 2.0f);              // Laplace: 0.5 when unknown
    const float strength = std::min(1.0f, std::log1p(c) / std::log(11.0f));
    float a = 0.65f * rate + 0.35f * strength;
    if (favorite_[row]) a = std::min(1.0f, a + 0.25f);
    return clamp01(a);
}

float SmartShuffleEngine::explorationOf(int row, int context, std::int64_t nowMs) const {
    // Track level: UCB1's bonus (few plays -> large) and staleness (a song not
    // heard for a month is worth rediscovering).
    const float n = static_cast<float>(completed_[row] + skipped_[row]);
    const float novelty = 1.0f / std::sqrt(n + 1.0f);
    float stale = 1.0f;
    if (lastPlayed_[row] > 0) {
        const double days = std::max<std::int64_t>(0, nowMs - lastPlayed_[row]) / 86400000.0;
        stale = static_cast<float>(1.0 - std::exp(-days / 30.0));
    }
    // Context level: UCB on the zone this track leans on, given the zone the
    // session is in. Zones the listener keeps finishing earn trust; zones
    // rarely tried keep an exploration bonus.
    float zoneUcb = 0.5f;
    if (context >= 0) {
        const int arm = zone_[row];
        const float pulls = banditPulls_[context][arm];
        float contextPulls = 0.0f;
        for (int a = 0; a < kZones; ++a) contextPulls += banditPulls_[context][a];
        const float meanReward = (banditRewards_[context][arm] + 1.0f) / (pulls + 2.0f);
        const float bonus = std::sqrt(2.0f * std::log(contextPulls + 2.0f) / (pulls + 1.0f));
        zoneUcb = clamp01(0.6f * meanReward + 0.4f * std::min(1.0f, bonus / 2.5f));
    }
    return clamp01(0.40f * zoneUcb + 0.35f * novelty + 0.25f * stale);
}

float SmartShuffleEngine::cooldownOf(int row, std::int64_t nowMs) const {
    if (lastPlayed_[row] <= 0) return 1.0f;
    const double hours = std::max<std::int64_t>(0, nowMs - lastPlayed_[row]) / 3600000.0;
    return clamp01(static_cast<float>(1.0 - 0.9 * std::exp(-hours / 4.0)));
}

SmartShuffleEngine::Candidate SmartShuffleEngine::scoreRow(int row, const float* target, int context,
                                                           float markovNorm, std::int64_t nowMs) const {
    Candidate c;
    c.row = row;
    float cos = target != nullptr ? dot(target, vec(row)) : 0.0f;
    if (!std::isfinite(cos)) cos = 0.0f;
    // Less trust in what was guessed than in what was measured.
    if (quality_[row] == VectorQuality::None) cos *= 0.3f;
    else if (quality_[row] == VectorQuality::Fallback) cos *= 0.8f;
    c.cosine = cos;
    const float sim01 = target != nullptr ? 0.5f * (cos + 1.0f) : 0.5f;
    c.vibe = weights_.similarity * sim01 +
             (steering_.empty() ? 0.0f : weights_.steering * steering_[row]);
    c.markov = weights_.markov * markovNorm;
    c.affinity = weights_.affinity * affinityOf(row);
    c.explore = weights_.explore * exploreBoost_ * explorationOf(row, context, nowMs);
    c.cooldown = cooldownOf(row, nowMs);
    c.score = (c.vibe + c.markov + c.affinity + c.explore) * c.cooldown;
    if (!std::isfinite(c.score)) c.score = 0.0f;
    return c;
}

void SmartShuffleEngine::scoreLibrary(const float* target, std::int64_t anchorId, std::int64_t nowMs,
                                      const std::vector<std::uint8_t>& blocked,
                                      std::vector<Candidate>& out) const {
    out.clear();
    out.reserve(ids_.size());
    const int context = contextZone();
    const std::unordered_map<std::int64_t, float>* markovRow = nullptr;
    float maxWeight = 0.0f;
    float evidence = 0.0f;
    const auto row = transitions_.find(anchorId);
    if (row != transitions_.end()) {
        markovRow = &row->second;
        for (const auto& [id, w] : row->second) {
            (void) id;
            maxWeight = std::max(maxWeight, w);
        }
        const auto total = transitionTotals_.find(anchorId);
        const float t = total != transitionTotals_.end() ? total->second : 0.0f;
        evidence = t / (t + 3.0f);
    }
    for (int r = 0; r < size(); ++r) {
        if (blocked[r]) continue;
        float markovNorm = 0.0f;
        if (markovRow != nullptr && maxWeight > 0.0f) {
            const auto cell = markovRow->find(ids_[r]);
            if (cell != markovRow->end()) markovNorm = clamp01(cell->second / maxWeight * evidence);
        }
        out.push_back(scoreRow(r, target, context, markovNorm, nowMs));
    }
}

std::vector<int> SmartShuffleEngine::reservoirSample(std::vector<Candidate>& pool, int count,
                                                     std::uint64_t seed) const {
    // Efraimidis-Spirakis A-Res: key = u^(1/w), kept as ln(u)/w; the largest
    // keys win. Weighted sampling without replacement in one pass: strong
    // candidates usually win, but never always, which is what keeps a queue
    // organic instead of the same top ten every time.
    std::vector<std::pair<double, int>> keyed;
    keyed.reserve(pool.size());
    if (pool.empty()) return {};
    float best = pool.front().score;
    for (const auto& c : pool) best = std::max(best, c.score);
    Rng rng(seed);
    const double temperature = std::max(0.02f, weights_.temperature);
    for (const auto& c : pool) {
        const double w = std::max(1e-30, std::exp((c.score - best) / temperature));
        keyed.emplace_back(std::log(rng.uniform()) / w, c.row);
    }
    std::sort(keyed.begin(), keyed.end(), [](const auto& a, const auto& b) { return a.first > b.first; });
    std::vector<int> rows;
    rows.reserve(keyed.size());
    for (const auto& [key, r] : keyed) {
        (void) key;
        rows.push_back(r);
    }
    (void) count;
    return rows;
}

std::vector<int> SmartShuffleEngine::ditherAndChain(const std::vector<int>& prefixRows,
                                                    const std::vector<int>& picked,
                                                    std::uint64_t seed) const {
    // Spotify's anti-clumping, as 1D error diffusion (Floyd-Steinberg on a
    // line): each artist is owed slots at the rate n_a / L, starting from a
    // random offset; every slot goes to an artist the error says is due, and
    // among the due artists' songs the one nearest the previous track wins,
    // which is the greedy nearest-neighbour chain that keeps transitions smooth.
    const int length = static_cast<int>(picked.size());
    if (length == 0) return {};
    auto artistKey = [this](int row) -> std::int64_t {
        return artist_[row] >= 0 ? artist_[row] : -static_cast<std::int64_t>(row) - 2;
    };
    std::map<std::int64_t, std::vector<int>> songs;
    for (int r : picked) songs[artistKey(r)].push_back(r);
    std::map<std::int64_t, double> error;
    std::map<std::int64_t, double> density;
    Rng rng(seed ^ 0xD1B54A32D192ED03ull);
    for (const auto& [artist, rows] : songs) {
        density[artist] = static_cast<double>(rows.size()) / length;
        error[artist] = rng.uniform() * density[artist];
    }
    std::map<std::int64_t, int> lastPosition;
    for (int i = 0; i < static_cast<int>(prefixRows.size()); ++i) lastPosition[artistKey(prefixRows[i])] = i;
    const int spacing = std::max(1, weights_.minArtistSpacing);

    std::vector<int> order;
    order.reserve(length);
    int previous = prefixRows.empty() ? -1 : prefixRows.back();
    for (int slot = 0; slot < length; ++slot) {
        const int position = static_cast<int>(prefixRows.size()) + slot;
        std::vector<std::int64_t> remaining;
        for (auto& [artist, rows] : songs) {
            if (rows.empty()) continue;
            error[artist] += density[artist];
            remaining.push_back(artist);
        }
        std::vector<std::int64_t> eligible;
        for (auto artist : remaining) {
            const auto last = lastPosition.find(artist);
            if (last == lastPosition.end() || position - last->second >= spacing) eligible.push_back(artist);
        }
        if (eligible.empty()) eligible = remaining;
        double maxError = -1e9;
        for (auto artist : eligible) maxError = std::max(maxError, error[artist]);

        int bestRow = -1;
        std::int64_t bestArtist = 0;
        float bestCost = std::numeric_limits<float>::max();
        for (auto artist : eligible) {
            if (error[artist] < maxError - 0.35) continue;
            for (int r : songs[artist]) {
                const float cost = previous >= 0 ? distance(vec(previous), vec(r)) : 0.0f;
                if (cost < bestCost) {
                    bestCost = cost;
                    bestRow = r;
                    bestArtist = artist;
                }
            }
        }
        if (bestRow < 0) break;
        auto& rows = songs[bestArtist];
        rows.erase(std::find(rows.begin(), rows.end(), bestRow));
        error[bestArtist] -= 1.0;
        lastPosition[bestArtist] = position;
        order.push_back(bestRow);
        previous = bestRow;
    }
    return order;
}

int SmartShuffleEngine::generateQueue(std::int64_t currentId,
                                      const std::int64_t* prefix, int prefixCount,
                                      const std::int64_t* exclude, int excludeCount,
                                      const std::int64_t* replacedTail, int replacedCount,
                                      int count, std::int64_t nowMs, std::uint64_t seed,
                                      std::int64_t* outIds) {
    const auto start = std::chrono::steady_clock::now();
    ++generation_;
    audit_ = BrainAuditReport{};
    audit_.generation = generation_;
    traces_.clear();
    candidateByRow_.clear();
    count = std::max(0, std::min(count, 64));

    ShuffleDiagnosticBrain::Draft draft;
    const int currentRow = rowOf(currentId);
    if (currentRow >= 0) draft.prefixRows.push_back(currentRow);
    for (int i = 0; prefix != nullptr && i < prefixCount; ++i) {
        const int r = rowOf(prefix[i]);
        if (r >= 0) draft.prefixRows.push_back(r);
    }
    if (replacedTail != nullptr && replacedCount > 0) draft.replacedTail.assign(replacedTail, replacedTail + replacedCount);

    // Check 4 runs first: the session vector must be sound before anything is scored by it.
    brain_.preflight(audit_, currentRow);

    if (size() == 0 || count == 0 || outIds == nullptr) {
        brain_.runBrainSelfAudit(draft, audit_, false);
        pendingSkip_.active = false;
        audit_.elapsedMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
        return 0;
    }

    // What may not be picked.
    std::vector<std::uint8_t> blocked(size(), 0);
    for (int r : draft.prefixRows) blocked[r] = 1;
    std::vector<std::uint8_t> strict = blocked;
    for (int i = 0; exclude != nullptr && i < excludeCount; ++i) {
        const int r = rowOf(exclude[i]);
        if (r >= 0) strict[r] = 1;
    }
    for (auto id : sessionPlayed_) {
        const int r = rowOf(id);
        if (r >= 0) strict[r] = 1;
    }
    int open = 0;
    for (auto b : strict) open += b ? 0 : 1;
    // A small library runs out of unheard songs: then heard ones come back
    // (still behind the cooldown) rather than the queue running dry.
    if (open >= count) blocked = strict;

    const float* target = hasSession_ ? session_.data() : (currentRow >= 0 ? vec(currentRow) : nullptr);
    const std::int64_t anchorId = draft.prefixRows.empty() ? currentId : ids_[draft.prefixRows.back()];

    bool echoRetry = false;
    for (int attempt = 0; attempt < 2; ++attempt) {
        std::vector<Candidate> scored;
        scoreLibrary(target, anchorId, nowMs, blocked, scored);
        if (scored.empty()) break;
        auto byScore = [](const Candidate& a, const Candidate& b) { return a.score > b.score; };
        const int poolSize = std::min<int>(static_cast<int>(scored.size()), std::max(24, std::min(128, count * 6)));
        std::partial_sort(scored.begin(), scored.begin() + poolSize, scored.end(), byScore);
        std::vector<Candidate> pool(scored.begin(), scored.begin() + poolSize);
        for (const auto& c : pool) candidateByRow_[c.row] = c;

        const std::uint64_t attemptSeed = seed + 0x632BE59BD9B4E019ull * static_cast<std::uint64_t>(attempt);
        const std::vector<int> sampled = reservoirSample(pool, count, attemptSeed);
        std::vector<int> picked(sampled.begin(), sampled.begin() + std::min<int>(count, static_cast<int>(sampled.size())));

        draft.placedRows = ditherAndChain(draft.prefixRows, picked, attemptSeed);
        draft.reserveRows.assign(sampled.begin() + static_cast<long>(picked.size()), sampled.end());
        // Beyond the pool: the next best by score, for repairs that need a different kind of song.
        const int extra = std::min<int>(static_cast<int>(scored.size()) - poolSize, 96);
        if (extra > 0) {
            std::partial_sort(scored.begin() + poolSize, scored.begin() + poolSize + extra, scored.end(), byScore);
            for (int i = 0; i < extra; ++i) {
                draft.reserveRows.push_back(scored[poolSize + i].row);
                candidateByRow_[scored[poolSize + i].row] = scored[poolSize + i];
            }
        }
        // Fresh blood for the echo-chamber repair: the most explorable songs
        // that do not sound like the session.
        std::vector<Candidate> fresh;
        for (const auto& c : scored) {
            if (target == nullptr || dot(target, vec(c.row)) < 0.9f) fresh.push_back(c);
        }
        const size_t freshCount = std::min<size_t>(fresh.size(), 32);
        std::partial_sort(fresh.begin(), fresh.begin() + static_cast<long>(freshCount), fresh.end(),
                          [](const Candidate& a, const Candidate& b) {
                              return a.explore * a.cooldown > b.explore * b.cooldown;
                          });
        draft.freshRows.clear();
        for (size_t i = 0; i < freshCount; ++i) {
            draft.freshRows.push_back(fresh[i].row);
            candidateByRow_[fresh[i].row] = fresh[i];
        }

        if (brain_.runBrainSelfAudit(draft, audit_, echoRetry)) break;
        echoRetry = true;  // the echo chamber check raised w_explore: score again
    }

    buildTraces(draft, false, 0.0f);
    const int written = static_cast<int>(draft.placedRows.size());
    for (int i = 0; i < written; ++i) outIds[i] = ids_[draft.placedRows[i]];
    pendingSkip_.active = false;
    audit_.exploreBoost = exploreBoost_;
    audit_.elapsedMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    return written;
}

std::int64_t SmartShuffleEngine::journeyStep(std::int64_t fromId, std::int64_t toId, float t,
                                             std::int64_t previousId,
                                             const std::int64_t* exclude, int excludeCount,
                                             std::int64_t nowMs, std::uint64_t seed) {
    // Plexamp-style Sonic Adventure: the target slides along the straight line
    // from one song to the other; each step plays a real song near the target
    // that also sits close to the one before it.
    const auto start = std::chrono::steady_clock::now();
    ++generation_;
    audit_ = BrainAuditReport{};
    audit_.generation = generation_;
    traces_.clear();
    candidateByRow_.clear();
    const int previousRow = rowOf(previousId);
    brain_.preflight(audit_, previousRow);
    const int fromRow = rowOf(fromId);
    const int toRow = rowOf(toId);
    if (fromRow < 0 || toRow < 0 || size() == 0) return -1;

    const float position = std::isfinite(t) ? std::min(1.0f, std::max(0.0f, t)) : 1.0f;
    std::array<float, kDims> target{};
    for (int d = 0; d < kDims; ++d) target[d] = (1.0f - position) * vec(fromRow)[d] + position * vec(toRow)[d];
    if (!normalize(target.data())) std::copy(vec(toRow), vec(toRow) + kDims, target.begin());

    std::vector<std::uint8_t> blocked(size(), 0);
    if (previousRow >= 0) blocked[previousRow] = 1;
    for (int i = 0; exclude != nullptr && i < excludeCount; ++i) {
        const int r = rowOf(exclude[i]);
        if (r >= 0) blocked[r] = 1;
    }
    for (auto id : sessionPlayed_) {
        const int r = rowOf(id);
        if (r >= 0 && r != toRow) blocked[r] = 1;
    }

    std::vector<Candidate> scored;
    const Weights saved = weights_;
    weights_.similarity = 2.0f;  // the line is the point: similarity to it dominates
    weights_.markov = 0.0f;
    weights_.explore = 0.05f;
    scoreLibrary(target.data(), previousId, nowMs, blocked, scored);
    weights_ = saved;
    if (scored.empty()) return -1;
    for (auto& c : scored) {
        // Smoothness from the previous song, folded into the vibe term.
        const float smooth = previousRow >= 0 ? 1.0f - 0.5f * distance(vec(previousRow), vec(c.row)) : 1.0f;
        c.vibe += 0.35f * smooth;
        c.score = (c.vibe + c.markov + c.affinity + c.explore) * c.cooldown;
        candidateByRow_[c.row] = c;
    }
    const int poolSize = std::min<int>(static_cast<int>(scored.size()), 12);
    std::partial_sort(scored.begin(), scored.begin() + poolSize, scored.end(),
                      [](const Candidate& a, const Candidate& b) { return a.score > b.score; });
    std::vector<Candidate> pool(scored.begin(), scored.begin() + poolSize);
    const Weights savedTemperature = weights_;
    weights_.temperature = 0.05f;
    const std::vector<int> sampled = reservoirSample(pool, 1, seed);
    weights_ = savedTemperature;

    ShuffleDiagnosticBrain::Draft draft;
    if (previousRow >= 0) draft.prefixRows.push_back(previousRow);
    draft.placedRows.push_back(sampled.front());
    draft.reserveRows.assign(sampled.begin() + 1, sampled.end());
    draft.allowReorder = false;
    brain_.runBrainSelfAudit(draft, audit_, true);
    buildTraces(draft, true, position);
    audit_.exploreBoost = exploreBoost_;
    audit_.elapsedMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    return draft.placedRows.empty() ? -1 : ids_[draft.placedRows.front()];
}

void SmartShuffleEngine::buildTraces(const ShuffleDiagnosticBrain::Draft& draft, bool journey, float journeyT) {
    traces_.clear();
    const int context = contextZone();
    for (size_t j = 0; j < draft.placedRows.size(); ++j) {
        const int row = draft.placedRows[j];
        Candidate c;
        const auto known = candidateByRow_.find(row);
        if (known != candidateByRow_.end()) {
            c = known->second;
        } else {
            c = scoreRow(row, hasSession_ ? session_.data() : nullptr, context, 0.0f, 0);
        }
        TrackDecisionTrace t;
        t.trackId = ids_[row];
        t.rawScore = c.score;
        const float total = std::max(kEps, c.vibe + c.markov + c.affinity + c.explore);
        t.vibeSimilarityPct = 100.0f * c.vibe / total;
        t.markovHabitPct = 100.0f * c.markov / total;
        t.userAffinityPct = 100.0f * c.affinity / total;
        t.explorationBonusPct = 100.0f * c.explore / total;
        t.recencyPenaltyMultiplier = c.cooldown;
        t.cosineSimilarity = c.cosine;
        t.zone = zone_[row];
        t.quality = quality_[row];
        const int previous = j == 0 ? (draft.prefixRows.empty() ? -1 : draft.prefixRows.back()) : draft.placedRows[j - 1];
        t.transitionSmoothnessScore = previous >= 0 ? clamp01(1.0f - 0.5f * distance(vec(previous), vec(row))) : 1.0f;

        char text[96];
        if (quality_[row] == VectorQuality::None) {
            t.reason = ReasonTag::ColdStart;
            std::snprintf(text, sizeof(text), "Cold-Start Wildcard");
        } else if (journey) {
            t.reason = ReasonTag::SonicAdventure;
            std::snprintf(text, sizeof(text), "Sonic Adventure %d%% of the way", static_cast<int>(std::lround(journeyT * 100.0f)));
        } else if (pendingSkip_.active && dot(vec(row), pendingSkip_.vector.data()) < kDistinctCosine) {
            t.reason = ReasonTag::PostSkipPivot;
            std::snprintf(text, sizeof(text), "Post-Skip Pivot (away from %s)", zoneWord(zoneOf(pendingSkip_.vector.data())));
        } else {
            const float parts[4] = {c.vibe, c.markov, c.affinity, c.explore};
            const int top = static_cast<int>(std::max_element(parts, parts + 4) - parts);
            switch (top) {
                case 1:
                    t.reason = ReasonTag::MarkovFavorite;
                    std::snprintf(text, sizeof(text), "Markov Favorite Transition");
                    break;
                case 2:
                    t.reason = ReasonTag::ProvenFavorite;
                    std::snprintf(text, sizeof(text), "Proven Favorite");
                    break;
                case 3:
                    t.reason = ReasonTag::BanditGem;
                    std::snprintf(text, sizeof(text), "Bandit Rediscovery Gem");
                    break;
                default:
                    t.reason = ReasonTag::VibeMatch;
                    std::snprintf(text, sizeof(text), "%d%% %s/Vibe Match",
                                  static_cast<int>(std::lround(50.0f * (c.cosine + 1.0f))), zoneWord(zone_[row]));
                    break;
            }
        }
        t.primaryReasonTag = text;
        traces_.push_back(std::move(t));
    }
}

// ===========================================================================
// Part 4: the Diagnostic Brain
// ===========================================================================

void ShuffleDiagnosticBrain::preflight(BrainAuditReport& report, int currentRow) {
    // Check 4: vector integrity and cold start.
    SmartShuffleEngine& e = engine_;
    AuditCheck& check = report.checks[static_cast<int>(AuditCheckId::VectorIntegrity)];
    check = AuditCheck{};
    check.threshold = kNormTolerance;

    int nonFinite = 0;
    for (float x : e.session_) nonFinite += std::isfinite(x) ? 0 : 1;
    if (e.hasSession_) {
        float norm = 0.0f;
        for (float x : e.session_) norm += std::isfinite(x) ? x * x : 0.0f;
        norm = std::sqrt(norm);
        if (nonFinite > 0 || std::fabs(norm - 1.0f) > kNormTolerance) {
            if (nonFinite == 0 && SmartShuffleEngine::normalize(e.session_.data())) {
                check.detail = "S_t drifted off unit length; renormalized";
            } else if (currentRow >= 0) {
                std::copy(e.vec(currentRow), e.vec(currentRow) + kDims, e.session_.begin());
                check.detail = "S_t held NaN/Inf; reseeded from the playing song";
            } else {
                e.session_.fill(0.0f);
                e.hasSession_ = false;
                check.detail = "S_t held NaN/Inf; reset";
            }
            ++check.corrections;
        }
    }
    // The library itself: every working vector must be finite and unit length.
    for (int r = 0; r < e.size(); ++r) {
        float* v = &e.vectors_[static_cast<size_t>(r) * kDims];
        bool bad = false;
        float norm = 0.0f;
        for (int d = 0; d < kDims; ++d) {
            if (!std::isfinite(v[d])) bad = true;
            else norm += v[d] * v[d];
        }
        if (bad || std::fabs(std::sqrt(norm) - 1.0f) > kNormTolerance) {
            nonFinite += bad ? 1 : 0;
            if (bad || !SmartShuffleEngine::normalize(v)) {
                std::fill(v, v + kDims, 0.0f);
                v[r % kDims] = 1.0f;
                --e.counts_[static_cast<int>(e.quality_[r])];
                e.quality_[r] = VectorQuality::None;
                ++e.counts_[0];
            }
            ++check.corrections;
        }
    }

    float norm = 0.0f;
    for (float x : e.session_) norm += x * x;
    report.sessionNorm = e.hasSession_ ? std::sqrt(norm) : 0.0f;
    report.nonFiniteValues = nonFinite;
    report.realTracks = e.counts_[2];
    report.fallbackTracks = e.counts_[1];
    report.placeholderTracks = e.counts_[0];
    report.realVectorPct = e.size() > 0 ? 100.0f * e.counts_[2] / e.size() : 0.0f;
    check.metric = e.hasSession_ ? report.sessionNorm : 1.0f;
    check.status = check.corrections > 0 ? AuditStatus::Healed : AuditStatus::Pass;
    char text[160];
    std::snprintf(text, sizeof(text), "%s||S_t|| %.4f, %d NaN/Inf, %.1f%% real vectors, %d cold-start%s",
                  check.detail.empty() ? "" : (check.detail + "; ").c_str(), check.metric, nonFinite,
                  report.realVectorPct, e.counts_[0] + e.counts_[1],
                  e.repairedInputs_ > 0 ? ", corrupt inputs repaired at load" : "");
    check.detail = text;
}

bool ShuffleDiagnosticBrain::runBrainSelfAudit(Draft& draft, BrainAuditReport& report, bool echoRetry) {
    checkSkipRepulsion(draft, report);
    if (!checkEchoChamber(draft, report, echoRetry)) return false;
    checkAntiClumping(draft, report);
    checkTransitionJerk(draft, report);

    // The repairs can interact; the final numbers describe the queue as delivered.
    int firstBad = -1;
    int minGap = 0;
    AuditCheck& clumping = report.checks[static_cast<int>(AuditCheckId::AntiClumping)];
    const int spacing = std::max(1, static_cast<int>(clumping.threshold));
    if (clumpViolations(draft, spacing, &firstBad, &minGap) > 0) clumping.status = AuditStatus::Failed;
    clumping.metric = static_cast<float>(minGap);
    report.exploreBoost = engine_.exploreBoost_;
    return true;
}

void ShuffleDiagnosticBrain::checkSkipRepulsion(Draft& draft, BrainAuditReport& report) {
    // Check 2: after a fast skip, S_t moved away from the skipped song and at
    // least 60% of the upcoming tail is new and acoustically distinct.
    SmartShuffleEngine& e = engine_;
    AuditCheck& check = report.checks[static_cast<int>(AuditCheckId::SkipRepulsion)];
    check = AuditCheck{};
    check.threshold = kSkipTailShare;
    const auto& skip = e.pendingSkip_;
    if (!skip.active) {
        check.metric = 1.0f;
        check.detail = "no fast skip since the last queue";
        return;
    }
    report.afterFastSkip = true;
    if (skip.healed) ++check.corrections;
    const bool moved = skip.dotAfter < skip.dotBefore;

    const std::unordered_set<std::int64_t> oldTail(draft.replacedTail.begin(), draft.replacedTail.end());
    auto distinct = [&](int row) {
        return oldTail.count(e.ids_[row]) == 0 &&
               SmartShuffleEngine::dot(e.vec(row), skip.vector.data()) < kDistinctCosine;
    };
    auto share = [&]() {
        if (draft.placedRows.empty()) return 1.0f;
        int n = 0;
        for (int r : draft.placedRows) n += distinct(r) ? 1 : 0;
        return static_cast<float>(n) / static_cast<float>(draft.placedRows.size());
    };

    float ratio = share();
    if (ratio < kSkipTailShare) {
        // Replace the tracks most like the skipped one first.
        std::vector<int> order(draft.placedRows.size());
        std::iota(order.begin(), order.end(), 0);
        std::sort(order.begin(), order.end(), [&](int a, int b) {
            return SmartShuffleEngine::dot(e.vec(draft.placedRows[a]), skip.vector.data()) >
                   SmartShuffleEngine::dot(e.vec(draft.placedRows[b]), skip.vector.data());
        });
        const int spacing = effectiveSpacing(draft);
        for (int position : order) {
            if (ratio >= kSkipTailShare) break;
            if (distinct(draft.placedRows[position])) continue;
            for (size_t k = 0; k < draft.reserveRows.size(); ++k) {
                const int candidate = draft.reserveRows[k];
                if (!distinct(candidate) || !artistFits(draft, position, candidate, spacing)) continue;
                draft.placedRows[position] = candidate;
                draft.reserveRows.erase(draft.reserveRows.begin() + static_cast<long>(k));
                ++check.corrections;
                break;
            }
            ratio = share();
        }
    }
    check.metric = ratio;
    const bool ok = moved && ratio >= kSkipTailShare;
    check.status = !ok ? AuditStatus::Failed : (check.corrections > 0 ? AuditStatus::Healed : AuditStatus::Pass);
    char text[160];
    std::snprintf(text, sizeof(text), "S_t.v_skip %.2f -> %.2f%s; %.0f%% of the tail new and distinct",
                  skip.dotBefore, skip.dotAfter, skip.healed ? " (repelled harder)" : "", ratio * 100.0f);
    check.detail = text;
}

bool ShuffleDiagnosticBrain::checkEchoChamber(Draft& draft, BrainAuditReport& report, bool echoRetry) {
    // Check 3: is the queue monotonous? Average pairwise cosine plus the
    // Shannon entropy of its artists and sonic zones.
    SmartShuffleEngine& e = engine_;
    AuditCheck& check = report.checks[static_cast<int>(AuditCheckId::EchoChamber)];
    check = AuditCheck{};
    check.threshold = e.weights_.echoThreshold;

    auto measure = [&]() {
        const auto& rows = draft.placedRows;
        double sum = 0.0;
        int pairs = 0;
        for (size_t a = 0; a < rows.size(); ++a) {
            for (size_t b = a + 1; b < rows.size(); ++b) {
                sum += SmartShuffleEngine::dot(e.vec(rows[a]), e.vec(rows[b]));
                ++pairs;
            }
        }
        return pairs > 0 ? static_cast<float>(sum / pairs) : 0.0f;
    };
    float average = measure();

    if (average > check.threshold && !echoRetry) {
        // First response: +50% exploration and a fresh draw.
        e.exploreBoost_ = std::min(4.0f, e.exploreBoost_ * 1.5f);
        return false;
    }
    if (echoRetry) {
        ++check.corrections;
        if (average > check.threshold && !draft.freshRows.empty()) {
            // The boost was not enough: swap the most redundant tracks for the
            // most explorable songs that sound different.
            const int swaps = std::max(1, static_cast<int>((draft.placedRows.size() + 3) / 4));
            std::array<float, kDims> centroid{};
            for (int r : draft.placedRows) {
                for (int d = 0; d < kDims; ++d) centroid[d] += e.vec(r)[d];
            }
            SmartShuffleEngine::normalize(centroid.data());
            const int spacing = effectiveSpacing(draft);
            for (int s = 0; s < swaps; ++s) {
                int worst = -1;
                float worstSim = -2.0f;
                for (size_t p = 0; p < draft.placedRows.size(); ++p) {
                    const float sim = SmartShuffleEngine::dot(e.vec(draft.placedRows[p]), centroid.data());
                    if (sim > worstSim) {
                        worstSim = sim;
                        worst = static_cast<int>(p);
                    }
                }
                if (worst < 0) break;
                bool swapped = false;
                for (size_t k = 0; k < draft.freshRows.size(); ++k) {
                    const int candidate = draft.freshRows[k];
                    if (std::find(draft.placedRows.begin(), draft.placedRows.end(), candidate) != draft.placedRows.end()) continue;
                    if (SmartShuffleEngine::dot(e.vec(candidate), centroid.data()) > 0.9f) continue;
                    if (!artistFits(draft, worst, candidate, spacing)) continue;
                    draft.placedRows[worst] = candidate;
                    draft.freshRows.erase(draft.freshRows.begin() + static_cast<long>(k));
                    ++check.corrections;
                    swapped = true;
                    break;
                }
                if (!swapped) break;
            }
            average = measure();
        }
        check.status = average > check.threshold ? AuditStatus::Failed : AuditStatus::Healed;
    } else if (e.exploreBoost_ > 1.0f) {
        // Healthy again: let the boost fade.
        e.exploreBoost_ = std::max(1.0f, e.exploreBoost_ * 0.9f);
    }

    std::map<std::int64_t, int> artists;
    std::map<std::int64_t, int> zones;
    for (int r : draft.placedRows) {
        ++artists[e.artist_[r] >= 0 ? e.artist_[r] : -static_cast<std::int64_t>(r) - 2];
        ++zones[e.zone_[r]];
    }
    const int total = static_cast<int>(draft.placedRows.size());
    report.averagePairwiseSimilarity = average;
    report.artistEntropyBits = entropyBits(artists, total);
    report.zoneEntropyBits = entropyBits(zones, total);
    check.metric = average;
    char text[160];
    std::snprintf(text, sizeof(text), "avg cosine %.2f, artist entropy %.2f bits, zone entropy %.2f bits%s",
                  average, report.artistEntropyBits, report.zoneEntropyBits,
                  echoRetry ? (check.status == AuditStatus::Healed ? "; w_explore +50%, fresh tracks injected"
                                                                     : "; library too uniform to diversify")
                            : "");
    check.detail = text;
    return true;
}

int ShuffleDiagnosticBrain::effectiveSpacing(const Draft& draft) const {
    // The configured spacing, unless the library makes it impossible: keeping
    // every artist s slots apart needs at least s different artists among the
    // songs the queue can be built from (placed plus reserve), and a queue of
    // L slots cannot space anything more than L apart.
    const SmartShuffleEngine& e = engine_;
    const int wanted = e.weights_.minArtistSpacing;
    if (wanted <= 1 || draft.placedRows.empty()) return std::max(1, wanted);
    auto key = [&](int row) { return e.artist_[row] >= 0 ? e.artist_[row] : -static_cast<std::int64_t>(row) - 2; };
    std::unordered_set<std::int64_t> artists;
    for (int r : draft.placedRows) artists.insert(key(r));
    for (int r : draft.reserveRows) {
        if (static_cast<int>(artists.size()) >= wanted) break;
        artists.insert(key(r));
    }
    const int slots = static_cast<int>(draft.placedRows.size() + std::min<size_t>(draft.prefixRows.size(), wanted));
    const int feasible = std::min<int>(static_cast<int>(artists.size()), slots);
    return std::max(1, std::min(wanted, feasible));
}

int ShuffleDiagnosticBrain::clumpViolations(const Draft& draft, int spacing, int* firstBad, int* minGap) const {
    const SmartShuffleEngine& e = engine_;
    const int prefixSize = static_cast<int>(draft.prefixRows.size());
    const int total = prefixSize + static_cast<int>(draft.placedRows.size());
    auto rowAt = [&](int i) { return i < prefixSize ? draft.prefixRows[i] : draft.placedRows[i - prefixSize]; };
    auto key = [&](int row) { return e.artist_[row] >= 0 ? e.artist_[row] : -static_cast<std::int64_t>(row) - 2; };
    std::unordered_map<std::int64_t, int> last;
    int violations = 0;
    int smallest = static_cast<int>(draft.placedRows.size()) + prefixSize;
    if (firstBad) *firstBad = -1;
    for (int i = 0; i < total; ++i) {
        const auto artist = key(rowAt(i));
        const auto seen = last.find(artist);
        if (seen != last.end() && i >= prefixSize) {
            const int gap = i - seen->second;
            smallest = std::min(smallest, gap);
            if (gap < spacing) {
                ++violations;
                if (firstBad && *firstBad < 0) *firstBad = i - prefixSize;
            }
        }
        last[artist] = i;
    }
    if (minGap) *minGap = smallest;
    return violations;
}

bool ShuffleDiagnosticBrain::artistFits(const Draft& draft, int position, int row, int spacing) const {
    const SmartShuffleEngine& e = engine_;
    if (e.artist_[row] < 0) return true;
    const int prefixSize = static_cast<int>(draft.prefixRows.size());
    const int at = prefixSize + position;
    const int total = prefixSize + static_cast<int>(draft.placedRows.size());
    for (int i = std::max(0, at - spacing + 1); i < std::min(total, at + spacing); ++i) {
        if (i == at) continue;
        const int other = i < prefixSize ? draft.prefixRows[i] : draft.placedRows[i - prefixSize];
        if (e.artist_[other] == e.artist_[row]) return false;
    }
    return true;
}

void ShuffleDiagnosticBrain::checkAntiClumping(Draft& draft, BrainAuditReport& report) {
    // Check 1: same-artist tracks at least minArtistSpacing apart, unless the
    // queue's make-up makes that impossible.
    AuditCheck& check = report.checks[static_cast<int>(AuditCheckId::AntiClumping)];
    check = AuditCheck{};
    const int spacing = effectiveSpacing(draft);
    check.threshold = static_cast<float>(spacing);
    int bad = -1;
    int gap = 0;
    int violations = clumpViolations(draft, spacing, &bad, &gap);
    const int limit = 3 * static_cast<int>(draft.placedRows.size()) + 3;
    for (int iteration = 0; violations > 0 && bad >= 0 && iteration < limit; ++iteration) {
        bool fixed = false;
        if (draft.allowReorder) {
            // A dithering swap: the swap partner that leaves the fewest violations.
            int bestPartner = -1;
            int bestViolations = violations;
            for (int k = 0; k < static_cast<int>(draft.placedRows.size()); ++k) {
                if (k == bad) continue;
                std::swap(draft.placedRows[bad], draft.placedRows[k]);
                const int after = clumpViolations(draft, spacing, nullptr, nullptr);
                std::swap(draft.placedRows[bad], draft.placedRows[k]);
                if (after < bestViolations) {
                    bestViolations = after;
                    bestPartner = k;
                }
            }
            if (bestPartner >= 0) {
                std::swap(draft.placedRows[bad], draft.placedRows[bestPartner]);
                fixed = true;
            }
        }
        if (!fixed) {
            // No swap helps: replace the track with one by an artist that fits.
            for (size_t k = 0; k < draft.reserveRows.size(); ++k) {
                const int candidate = draft.reserveRows[k];
                if (!artistFits(draft, bad, candidate, spacing)) continue;
                draft.placedRows[bad] = candidate;
                draft.reserveRows.erase(draft.reserveRows.begin() + static_cast<long>(k));
                fixed = true;
                break;
            }
        }
        if (!fixed) break;
        ++check.corrections;
        violations = clumpViolations(draft, spacing, &bad, &gap);
    }
    check.metric = static_cast<float>(gap);
    check.status = violations > 0 ? AuditStatus::Failed
                                  : (check.corrections > 0 ? AuditStatus::Healed : AuditStatus::Pass);
    char text[128];
    std::snprintf(text, sizeof(text), "min same-artist gap %d (>= %d required), %d repair%s", gap, spacing,
                  check.corrections, check.corrections == 1 ? "" : "s");
    check.detail = text;
}

float ShuffleDiagnosticBrain::jumpBefore(const Draft& draft, int j) const {
    const SmartShuffleEngine& e = engine_;
    const int row = draft.placedRows[j];
    if (j > 0) return SmartShuffleEngine::distance(e.vec(draft.placedRows[j - 1]), e.vec(row));
    if (draft.prefixRows.empty()) return 0.0f;
    const int previous = draft.prefixRows.back();
    // Leaving a song the listener just fast-skipped is the point: that jump is
    // the pivot, not a rough transition.
    if (e.pendingSkip_.active && e.ids_[previous] == e.pendingSkip_.id) return 0.0f;
    return SmartShuffleEngine::distance(e.vec(previous), e.vec(row));
}

float ShuffleDiagnosticBrain::maxJump(const Draft& draft, int* worstIndex) const {
    float worst = 0.0f;
    if (worstIndex) *worstIndex = -1;
    for (int j = 0; j < static_cast<int>(draft.placedRows.size()); ++j) {
        const float jump = jumpBefore(draft, j);
        if (jump > worst) {
            worst = jump;
            if (worstIndex) *worstIndex = j;
        }
    }
    return worst;
}

bool ShuffleDiagnosticBrain::isHabitPick(int row) const {
    // A track the listener's own Markov habit put here: they make that jump
    // themselves, so it is kept even when it is a big one.
    const auto it = engine_.candidateByRow_.find(row);
    if (it == engine_.candidateByRow_.end()) return false;
    const auto& c = it->second;
    return c.markov > 0.0f && c.markov >= 0.5f * (c.vibe + c.markov + c.affinity + c.explore);
}

void ShuffleDiagnosticBrain::checkTransitionJerk(Draft& draft, BrainAuditReport& report) {
    // Check 5: no harsh spectral jump between neighbours. Each harsh jump is
    // first smoothed by re-ordering the four tracks around it, then, if that
    // is not enough, by swapping the outlier for a bridging track. Jumps into
    // or out of a learned habit are left alone.
    SmartShuffleEngine& e = engine_;
    AuditCheck& check = report.checks[static_cast<int>(AuditCheckId::TransitionJerk)];
    check = AuditCheck{};
    const float threshold = e.weights_.jerkThreshold;
    check.threshold = threshold;
    const int spacing = effectiveSpacing(draft);
    const int length = static_cast<int>(draft.placedRows.size());
    auto harshAt = [&](int j) { return jumpBefore(draft, j) > threshold; };
    auto protectedAt = [&](int j) {
        if (isHabitPick(draft.placedRows[j])) return true;
        return j > 0 && isHabitPick(draft.placedRows[j - 1]);
    };
    // (harsh jumps, largest jump) over transitions into from..to+1.
    auto windowCost = [&](int from, int to) {
        int harsh = 0;
        float worst = 0.0f;
        for (int j = from; j <= std::min(length - 1, to + 1); ++j) {
            const float jump = jumpBefore(draft, j);
            harsh += jump > threshold ? 1 : 0;
            worst = std::max(worst, jump);
        }
        return std::make_pair(harsh, worst);
    };

    for (int pass = 0; pass < 2; ++pass) {
        for (int j = 0; j < length; ++j) {
            if (!harshAt(j) || protectedAt(j)) continue;

            if (draft.allowReorder && length >= 2) {
                const int from = std::max(0, std::min(j - 1, length - 4));
                const int to = std::min(length - 1, from + 3);
                std::vector<int> original(draft.placedRows.begin() + from, draft.placedRows.begin() + to + 1);
                std::vector<int> best = original;
                const int baseViolations = clumpViolations(draft, spacing, nullptr, nullptr);
                auto bestCost = windowCost(from, to);
                std::vector<int> permutation = original;
                std::sort(permutation.begin(), permutation.end());
                do {
                    std::copy(permutation.begin(), permutation.end(), draft.placedRows.begin() + from);
                    const auto cost = windowCost(from, to);
                    const bool better = cost.first < bestCost.first ||
                                        (cost.first == bestCost.first && cost.second + 1e-4f < bestCost.second);
                    if (better && clumpViolations(draft, spacing, nullptr, nullptr) <= baseViolations) {
                        bestCost = cost;
                        best = permutation;
                    }
                } while (std::next_permutation(permutation.begin(), permutation.end()));
                std::copy(best.begin(), best.end(), draft.placedRows.begin() + from);
                if (best != original) ++check.corrections;
                if (!harshAt(j)) continue;
            }

            // A bridge: a reserve song close to both neighbours, in place of
            // whichever side of the jump is not a habit.
            for (int side : {j, j - 1}) {
                if (side < 0 || isHabitPick(draft.placedRows[side])) continue;
                const int previous = side == 0 ? (draft.prefixRows.empty() ? -1 : draft.prefixRows.back())
                                               : draft.placedRows[side - 1];
                const int next = side + 1 < length ? draft.placedRows[side + 1] : -1;
                // Leaving a fast-skipped song is a pivot; only the next side matters there.
                const bool pivot = side == 0 && previous >= 0 && e.pendingSkip_.active &&
                                   e.ids_[previous] == e.pendingSkip_.id;
                bool bridged = false;
                for (size_t k = 0; k < draft.reserveRows.size(); ++k) {
                    const int candidate = draft.reserveRows[k];
                    if (previous >= 0 && !pivot &&
                        SmartShuffleEngine::distance(e.vec(previous), e.vec(candidate)) > threshold) continue;
                    if (next >= 0 && SmartShuffleEngine::distance(e.vec(candidate), e.vec(next)) > threshold) continue;
                    if (!artistFits(draft, side, candidate, spacing)) continue;
                    draft.reserveRows.erase(draft.reserveRows.begin() + static_cast<long>(k));
                    draft.reserveRows.push_back(draft.placedRows[side]);
                    draft.placedRows[side] = candidate;
                    ++check.corrections;
                    bridged = true;
                    break;
                }
                if (bridged) break;
            }
        }
    }

    int flagged = 0;
    int kept = 0;
    for (int j = 0; j < length; ++j) {
        if (!harshAt(j)) continue;
        if (protectedAt(j)) ++kept;
        else ++flagged;
    }
    const float worst = maxJump(draft, nullptr);
    check.metric = worst;
    check.status = flagged > 0 ? AuditStatus::Failed
                               : (check.corrections > 0 ? AuditStatus::Healed : AuditStatus::Pass);
    const std::string habits = kept > 0 ? ", " + std::to_string(kept) + " kept as habits" : "";
    char text[160];
    std::snprintf(text, sizeof(text), "largest jump %.2f (limit %.2f), %d harsh%s, %d repair%s", worst, threshold,
                  flagged, habits.c_str(), check.corrections, check.corrections == 1 ? "" : "s");
    check.detail = text;
}

// ===========================================================================
// Part 4.3: the synthetic self-test
// ===========================================================================

namespace {

enum Family { kBassHeavy = 0, kBrightTreble = 1, kVocalMid = 2, kBalanced = 3, kAcoustic = 4, kFamilies = 5 };

/** One virtual song of [family], with +-8% variation. */
void syntheticRaw(int family, Rng& rng, float* out) {
    static const float bands[kFamilies][kBands] = {
        {1.00f, 0.95f, 0.70f, 0.45f, 0.30f, 0.22f, 0.15f, 0.10f, 0.06f, 0.03f},
        {0.12f, 0.15f, 0.20f, 0.25f, 0.35f, 0.50f, 0.70f, 0.90f, 1.00f, 0.80f},
        {0.20f, 0.30f, 0.45f, 0.70f, 0.90f, 1.00f, 0.80f, 0.45f, 0.25f, 0.10f},
        {0.50f, 0.60f, 0.65f, 0.60f, 0.55f, 0.50f, 0.45f, 0.40f, 0.30f, 0.20f},
        {0.15f, 0.30f, 0.55f, 0.75f, 0.80f, 0.65f, 0.50f, 0.35f, 0.20f, 0.08f},
    };
    static const float macro[kFamilies][4] = {
        {0.75f, 0.20f, 0.25f, 0.60f},
        {0.10f, 0.80f, 0.35f, 0.50f},
        {0.25f, 0.50f, 0.40f, 0.35f},
        {0.40f, 0.40f, 0.30f, 0.45f},
        {0.15f, 0.45f, 0.75f, 0.20f},
    };
    auto jitter = [&rng](float v) { return v * static_cast<float>(0.92 + 0.16 * rng.uniform()); };
    double norm = 0.0;
    for (int k = 0; k < kBands; ++k) {
        out[k] = jitter(bands[family][k]);
        norm += out[k] * out[k];
    }
    for (int k = 0; k < kBands; ++k) out[k] = static_cast<float>(out[k] / std::sqrt(norm));
    for (int k = 0; k < kBands; ++k) {
        const bool lively = (family == kBassHeavy && k <= 2) || (family == kBrightTreble && k >= 7);
        out[kBands + k] = jitter(lively ? 0.55f : 0.30f);
    }
    for (int m = 0; m < 4; ++m) out[20 + m] = jitter(macro[family][m]);
}

struct Reporter {
    std::string text;
    int passed = 0;
    int failed = 0;
    void check(bool ok, const char* what, const std::string& detail = "") {
        (ok ? passed : failed)++;
        text += ok ? "[PASS] " : "[FAIL] ";
        text += what;
        if (!detail.empty()) text += " (" + detail + ")";
        text += "\n";
    }
};

std::string format(const char* pattern, double a, double b = 0.0, double c = 0.0) {
    char buffer[160];
    std::snprintf(buffer, sizeof(buffer), pattern, a, b, c);
    return buffer;
}

int familyOfId(std::int64_t id) { return static_cast<int>((id - 1000) / 20); }

float familyShare(const std::vector<std::int64_t>& ids, int family) {
    if (ids.empty()) return 0.0f;
    int n = 0;
    for (auto id : ids) n += familyOfId(id) == family ? 1 : 0;
    return static_cast<float>(n) / static_cast<float>(ids.size());
}

/** The checks that did not pass, with their details. */
std::string problems(const BrainAuditReport& report) {
    static const char* names[kAuditChecks] = {"clumping", "skip", "echo", "integrity", "jerk"};
    std::string out;
    for (int i = 0; i < kAuditChecks; ++i) {
        const auto& c = report.checks[i];
        if (c.status != AuditStatus::Failed) continue;
        if (!out.empty()) out += "; ";
        out += std::string(names[i]) + ": " + c.detail;
    }
    return out;
}

bool sessionSound(const SmartShuffleEngine& e) {
    float norm = 0.0f;
    for (int d = 0; d < kDims; ++d) {
        const float x = e.sessionVector()[d];
        if (!std::isfinite(x)) return false;
        norm += x * x;
    }
    return std::fabs(std::sqrt(norm) - 1.0f) <= kNormTolerance;
}

}  // namespace

std::string runSyntheticBrainSimulation(bool* passed) {
    Reporter r;
    r.text = "SHUFFLE BRAIN SELF-TEST: 100 virtual tracks, 5 families x 4 artists x 5 songs\n";
    const std::int64_t now = 1'800'000'000'000LL;

    // ---- Library -------------------------------------------------------
    constexpr int kTracks = 100;
    std::vector<std::int64_t> ids(kTracks);
    std::vector<float> raw(static_cast<size_t>(kTracks) * kDims);
    std::vector<std::uint8_t> quality(kTracks, static_cast<std::uint8_t>(VectorQuality::Real));
    std::vector<std::int32_t> artists(kTracks);
    std::vector<std::int32_t> albums(kTracks);
    Rng rng(42);
    for (int i = 0; i < kTracks; ++i) {
        const int family = i / 20;
        ids[i] = 1000 + i;
        artists[i] = family * 4 + (i % 4);
        albums[i] = artists[i] * 2 + (i % 20) / 10;
        syntheticRaw(family, rng, &raw[static_cast<size_t>(i) * kDims]);
    }
    SmartShuffleEngine engine;
    engine.setLibrary(ids.data(), raw.data(), quality.data(), artists.data(), albums.data(), kTracks);

    // ---- 5 bass-heavy plays, then 2 fast skips on bright treble ----------
    const auto t0 = std::chrono::steady_clock::now();
    std::vector<std::int64_t> played;
    for (int i = 0; i < 5; ++i) {
        const std::int64_t id = 1000 + i;
        engine.onTrackStarted(id);
        engine.onPlaybackOutcome(id, 0.95f, now, true);
        played.push_back(id);
    }
    std::vector<std::int64_t> queue(10);
    queue.resize(engine.generateQueue(played.back(), nullptr, 0, played.data(), static_cast<int>(played.size()),
                                      nullptr, 0, 10, now, 7, queue.data()));
    const BrainAuditReport afterPlays = engine.lastAudit();

    std::vector<std::pair<int, float>> bands;
    for (int k = 0; k < kBands; ++k) bands.emplace_back(k, engine.sessionVector()[k]);
    std::sort(bands.begin(), bands.end(), [](const auto& a, const auto& b) { return a.second > b.second; });
    const bool bassDominant = bands[0].first <= 2 && bands[1].first <= 2;
    const float bassShare = familyShare(queue, kBassHeavy);

    float dotBefore[2] = {};
    float dotAfter[2] = {};
    bool skipEvents = true;
    float trebleShare = 0.0f;
    float tailReplaced[2] = {};
    bool skipCheckOk = true;
    for (int s = 0; s < 2; ++s) {
        const std::int64_t skipped = 1020 + s * 5;
        float v[kDims];
        engine.vectorOf(skipped, v);
        float dot0 = 0.0f;
        for (int d = 0; d < kDims; ++d) dot0 += engine.sessionVector()[d] * v[d];
        dotBefore[s] = dot0;
        engine.onTrackStarted(skipped);
        skipEvents = skipEvents && engine.onPlaybackOutcome(skipped, 0.08f, now, true) == SessionEvent::FastSkip;
        float dot1 = 0.0f;
        for (int d = 0; d < kDims; ++d) dot1 += engine.sessionVector()[d] * v[d];
        dotAfter[s] = dot1;
        played.push_back(skipped);
        // The skip moves playback on to the next queued song; the rest of the
        // tail is dropped and re-ranked behind it.
        const std::int64_t nowPlaying = queue.front();
        engine.onTrackStarted(nowPlaying);
        played.push_back(nowPlaying);
        std::vector<std::int64_t> oldTail(queue.begin() + 1, queue.end());
        std::vector<std::int64_t> next(10);
        next.resize(engine.generateQueue(nowPlaying, nullptr, 0, played.data(), static_cast<int>(played.size()),
                                         oldTail.data(), static_cast<int>(oldTail.size()), 10, now, 11 + s, next.data()));
        const auto& check = engine.lastAudit().checks[static_cast<int>(AuditCheckId::SkipRepulsion)];
        tailReplaced[s] = check.metric;
        skipCheckOk = skipCheckOk && check.status != AuditStatus::Failed && engine.lastAudit().afterFastSkip;
        trebleShare = familyShare(next, kBrightTreble);
        queue = next;
    }
    const double adaptMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    const BrainAuditReport afterSkips = engine.lastAudit();

    r.check(bassDominant, "Session vector leans on the bass bands after 5 bass-heavy plays",
            format("top bands %.0f and %.0f", bands[0].first, bands[1].first));
    r.check(bassShare >= 0.70f, "Queue follows the bass vibe", format("%.0f%% bass-heavy, need >= 70%%", bassShare * 100.0));
    r.check(afterPlays.healthy(), "All 5 audits pass or heal after the plays",
            format("%.0f healed, %.0f failed", afterPlays.healedCount(), afterPlays.failedCount()) +
                (afterPlays.healthy() ? "" : ": " + problems(afterPlays)));
    r.check(skipEvents, "Both treble skips register as fast skips");
    r.check(dotAfter[0] < dotBefore[0] && dotAfter[1] < dotBefore[1], "Each skip pushes S_t away from the skipped song",
            format("%.2f->%.2f, then %.2f", dotBefore[0], dotAfter[0], dotAfter[1]));
    r.check(skipCheckOk && tailReplaced[0] >= 0.6f && tailReplaced[1] >= 0.6f,
            "At least 60% of the tail replaced with distinct tracks after each skip",
            format("%.0f%% and %.0f%%", tailReplaced[0] * 100.0, tailReplaced[1] * 100.0));
    r.check(trebleShare <= 0.10f, "No bright treble back in the queue after the skips", format("%.0f%% treble", trebleShare * 100.0));
    r.check(sessionSound(engine), "||S_t|| = 1 +- 0.001 and no NaN/Inf");
    r.check(afterSkips.healthy(), "All 5 audits pass or heal after the skips",
            format("%.0f healed, %.0f failed", afterSkips.healedCount(), afterSkips.failedCount()) +
                (afterSkips.healthy() ? "" : ": " + problems(afterSkips)));
    r.check(adaptMs < 5.0, "Adapts in under 5 ms (7 events, 3 queues)", format("%.3f ms", adaptMs));

    bool tracesOk = !engine.lastTraces().empty();
    for (const auto& t : engine.lastTraces()) {
        const float sum = t.vibeSimilarityPct + t.markovHabitPct + t.userAffinityPct + t.explorationBonusPct;
        tracesOk = tracesOk && std::fabs(sum - 100.0f) < 0.5f && !t.primaryReasonTag.empty();
    }
    r.check(tracesOk, "Every queued track explains itself (percentages sum to 100, reason tagged)");

    // ---- Echo chamber drill: two tight families, the session in one -------
    {
        SmartShuffleEngine echo;
        constexpr int n = 60;
        std::vector<std::int64_t> echoIds(n);
        std::vector<float> echoRaw(static_cast<size_t>(n) * kDims);
        std::vector<std::int32_t> echoArtists(n);
        Rng tight(9);
        for (int i = 0; i < n; ++i) {
            echoIds[i] = 5000 + i;
            echoArtists[i] = i;
            const int family = i < 40 ? kBassHeavy : kBrightTreble;
            Rng same(family == kBassHeavy ? 1 : 2);
            syntheticRaw(family, same, &echoRaw[static_cast<size_t>(i) * kDims]);
            for (int d = 0; d < kDims; ++d) echoRaw[static_cast<size_t>(i) * kDims + d] *= static_cast<float>(1.0 + 0.002 * tight.uniform());
        }
        echo.setLibrary(echoIds.data(), echoRaw.data(), nullptr, echoArtists.data(), nullptr, n);
        echo.onTrackStarted(5000);
        echo.onPlaybackOutcome(5000, 1.0f, now, false);
        std::vector<std::int64_t> q(10);
        q.resize(echo.generateQueue(5000, nullptr, 0, nullptr, 0, nullptr, 0, 10, now, 3, q.data()));
        const auto& check = echo.lastAudit().checks[static_cast<int>(AuditCheckId::EchoChamber)];
        r.check(check.status == AuditStatus::Healed && echo.exploreBoost() > 1.0f,
                "Echo chamber detected and healed (w_explore +50%, fresh tracks)",
                format("avg cosine now %.2f, boost x%.2f", echo.lastAudit().averagePairwiseSimilarity, echo.exploreBoost()));
    }

    // ---- Anti-clumping drill: one artist owns half the bass songs ---------
    {
        std::vector<std::int32_t> clumped = artists;
        for (int i = 0; i < 20; i += 2) clumped[i] = 99;
        SmartShuffleEngine clump;
        clump.setLibrary(ids.data(), raw.data(), quality.data(), clumped.data(), albums.data(), kTracks);
        clump.onTrackStarted(1001);
        clump.onPlaybackOutcome(1001, 1.0f, now, false);
        std::vector<std::int64_t> q(12);
        q.resize(clump.generateQueue(1001, nullptr, 0, nullptr, 0, nullptr, 0, 12, now, 5, q.data()));
        int tightest = 99;
        for (size_t a = 0; a < q.size(); ++a) {
            for (size_t b = a + 1; b < q.size(); ++b) {
                const int ra = static_cast<int>(q[a] - 1000);
                const int rb = static_cast<int>(q[b] - 1000);
                if (clumped[ra] == clumped[rb]) tightest = std::min(tightest, static_cast<int>(b - a));
            }
        }
        const auto& check = clump.lastAudit().checks[static_cast<int>(AuditCheckId::AntiClumping)];
        r.check(check.status != AuditStatus::Failed && tightest >= static_cast<int>(check.threshold),
                "Same-artist songs kept apart",
                format("closest pair %.0f apart, spacing %.0f", tightest, check.threshold) + " " + check.detail);
    }

    // ---- Integrity drill: corrupt inputs and a corrupt outcome -----------
    {
        std::vector<float> corrupt = raw;
        corrupt[3] = std::numeric_limits<float>::infinity();
        corrupt[kDims + 5] = std::numeric_limits<float>::quiet_NaN();
        corrupt[2 * kDims + 7] = 1e30f;
        SmartShuffleEngine sturdy;
        sturdy.setLibrary(ids.data(), corrupt.data(), quality.data(), artists.data(), albums.data(), kTracks);
        sturdy.onTrackStarted(1000);
        sturdy.onPlaybackOutcome(1000, std::numeric_limits<float>::quiet_NaN(), now, true);
        sturdy.onPlaybackOutcome(1001, std::numeric_limits<float>::infinity(), now, true);
        std::vector<std::int64_t> q(8);
        q.resize(sturdy.generateQueue(1000, nullptr, 0, nullptr, 0, nullptr, 0, 8, now, 1, q.data()));
        r.check(sessionSound(sturdy) && sturdy.lastAudit().nonFiniteValues == 0 && q.size() == 8,
                "Corrupt inputs never reach the vectors", format("%.0f values repaired at load", sturdy.repairedInputValues()));
    }

    char summary[96];
    std::snprintf(summary, sizeof(summary), "RESULT: %s (%d/%d checks), adaptation %.3f ms\n",
                  r.failed == 0 ? "PASS" : "FAIL", r.passed, r.passed + r.failed, adaptMs);
    r.text += summary;
    if (passed) *passed = r.failed == 0;
    return r.text;
}

}  // namespace harmony::shuffle
