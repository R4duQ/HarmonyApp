// harmony_dsp.cpp — implementation notes inline; layout contract in the header.

#include "harmony_dsp.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

namespace {

constexpr int kFftSize = 2048;
constexpr int kHopSize = 1024;
constexpr int kNumMels = 26;
constexpr int kNumMfcc = 13;
constexpr int kNumChroma = 12;
constexpr float kPi = 3.14159265358979323846f;

// ------------------------------------------------------------------ FFT
// Iterative radix-2 Cooley-Tukey on interleaved re/im. 2048-point real input;
// a split-radix real FFT would be ~2x faster but this is already far from the
// bottleneck (decode is), so we keep the simple, obviously-correct version.
void fft(std::vector<float>& re, std::vector<float>& im) {
    const int n = (int)re.size();
    for (int i = 1, j = 0; i < n; ++i) {
        int bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) { std::swap(re[i], re[j]); std::swap(im[i], im[j]); }
    }
    for (int len = 2; len <= n; len <<= 1) {
        const float ang = -2.0f * kPi / (float)len;
        const float wr = std::cos(ang), wi = std::sin(ang);
        for (int i = 0; i < n; i += len) {
            float cwr = 1.0f, cwi = 0.0f;
            for (int k = 0; k < len / 2; ++k) {
                const float ur = re[i + k], ui = im[i + k];
                const float vr = re[i + k + len / 2] * cwr - im[i + k + len / 2] * cwi;
                const float vi = re[i + k + len / 2] * cwi + im[i + k + len / 2] * cwr;
                re[i + k] = ur + vr;  im[i + k] = ui + vi;
                re[i + k + len / 2] = ur - vr;  im[i + k + len / 2] = ui - vi;
                const float nwr = cwr * wr - cwi * wi;
                cwi = cwr * wi + cwi * wr;
                cwr = nwr;
            }
        }
    }
}

// -------------------------------------------------------------- Biquad
// Direct Form II transposed; used for the two K-weighting stages of the
// BS.1770 loudness pre-filter (RBJ designs approximating the ITU curves:
// +4 dB high shelf @ ~1681.97 Hz Q 0.7071, then high-pass @ ~38.13 Hz Q 0.5).
struct Biquad {
    float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
    float z1 = 0, z2 = 0;

    float process(float x) {
        const float y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }

    static Biquad highShelf(float sr, float fc, float q, float gainDb) {
        Biquad bq;
        const float A = std::pow(10.0f, gainDb / 40.0f);
        const float w = 2.0f * kPi * fc / sr;
        const float cw = std::cos(w), sw = std::sin(w);
        const float alpha = sw / (2.0f * q);
        const float a0 = (A + 1) - (A - 1) * cw + 2 * std::sqrt(A) * alpha;
        bq.b0 = (A * ((A + 1) + (A - 1) * cw + 2 * std::sqrt(A) * alpha)) / a0;
        bq.b1 = (-2 * A * ((A - 1) + (A + 1) * cw)) / a0;
        bq.b2 = (A * ((A + 1) + (A - 1) * cw - 2 * std::sqrt(A) * alpha)) / a0;
        bq.a1 = (2 * ((A - 1) - (A + 1) * cw)) / a0;
        bq.a2 = ((A + 1) - (A - 1) * cw - 2 * std::sqrt(A) * alpha) / a0;
        return bq;
    }

    static Biquad highPass(float sr, float fc, float q) {
        Biquad bq;
        const float w = 2.0f * kPi * fc / sr;
        const float cw = std::cos(w), sw = std::sin(w);
        const float alpha = sw / (2.0f * q);
        const float a0 = 1 + alpha;
        bq.b0 = ((1 + cw) / 2) / a0;
        bq.b1 = (-(1 + cw)) / a0;
        bq.b2 = ((1 + cw) / 2) / a0;
        bq.a1 = (-2 * cw) / a0;
        bq.a2 = (1 - alpha) / a0;
        return bq;
    }
};

// Krumhansl-Schmuckler key profiles (major/minor), the standard reference
// weights for chroma-correlation key estimation.
constexpr float kMajorProfile[12] = {6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f,
                                     2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f};
constexpr float kMinorProfile[12] = {6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f,
                                     2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f};

float correlate12(const float* a, const float* b) {
    float ma = 0, mb = 0;
    for (int i = 0; i < 12; ++i) { ma += a[i]; mb += b[i]; }
    ma /= 12; mb /= 12;
    float num = 0, da = 0, db = 0;
    for (int i = 0; i < 12; ++i) {
        num += (a[i] - ma) * (b[i] - mb);
        da += (a[i] - ma) * (a[i] - ma);
        db += (b[i] - mb) * (b[i] - mb);
    }
    const float den = std::sqrt(da * db);
    return den > 1e-9f ? num / den : 0.0f;
}

inline float hzToMel(float hz) { return 2595.0f * std::log10(1.0f + hz / 700.0f); }
inline float melToHz(float mel) { return 700.0f * (std::pow(10.0f, mel / 2595.0f) - 1.0f); }

} // namespace

struct HarmonyAnalyzer {
    int sampleRate = 0;
    int numBins = kFftSize / 2 + 1;

    // Streaming buffer holding up to one FFT frame of pending samples.
    std::vector<float> pending;

    // Pre-computed.
    std::vector<float> hann;
    std::vector<std::vector<float>> melFilters;  // [mel][bin]
    std::vector<int> chromaMap;                  // bin -> pitch class (or -1)

    // Scratch (reused every frame — no hot-path allocation).
    std::vector<float> re, im, magnitude, prevMagnitude, mels;

    // Accumulators.
    long frameCount = 0;
    double sumSquares = 0; long totalSamples = 0;
    float peak = 0;
    double centroidSum = 0, rolloffSum = 0, bandwidthSum = 0, flatnessSum = 0, contrastSum = 0;
    double bassSum = 0, midSum = 0, trebleSum = 0, zcrSum = 0;
    double chromaSum[kNumChroma] = {};
    double mfccSum[kNumMfcc] = {}, mfccSqSum[kNumMfcc] = {};
    std::vector<float> flux;        // per-frame spectral flux for tempo
    std::vector<float> frameLoudDb; // per-frame loudness for dynamic range

    // Loudness (K-weighted running mean-square with a coarse -70 LUFS gate
    // applied over 400 ms blocks; a full two-stage relative gate is a Phase 9
    // refinement — the absolute-gated value is within ~0.5 LU for music).
    Biquad kShelf, kHighpass;
    double loudSumSq = 0; long loudCount = 0;
    double blockSumSq = 0; long blockCount = 0; long blockLen = 0;

    explicit HarmonyAnalyzer(int sr) : sampleRate(sr) {
        pending.reserve(kFftSize);
        hann.resize(kFftSize);
        for (int i = 0; i < kFftSize; ++i)
            hann[i] = 0.5f * (1.0f - std::cos(2.0f * kPi * i / (kFftSize - 1)));

        re.resize(kFftSize); im.resize(kFftSize);
        magnitude.resize(numBins); prevMagnitude.assign(numBins, 0.0f);
        mels.resize(kNumMels);

        buildMelFilters();
        buildChromaMap();

        kShelf = Biquad::highShelf((float)sr, 1681.97f, 0.7071f, 4.0f);
        kHighpass = Biquad::highPass((float)sr, 38.13f, 0.5f);
        blockLen = (long)(0.4 * sr); // 400 ms loudness blocks

        flux.reserve(1 << 14);
        frameLoudDb.reserve(1 << 14);
    }

    void buildMelFilters() {
        melFilters.assign(kNumMels, std::vector<float>(numBins, 0.0f));
        const float melLo = hzToMel(20.0f);
        const float melHi = hzToMel(std::min(8000.0f, sampleRate / 2.0f));
        std::vector<float> centers(kNumMels + 2);
        for (int i = 0; i < kNumMels + 2; ++i)
            centers[i] = melToHz(melLo + (melHi - melLo) * i / (kNumMels + 1));
        for (int m = 0; m < kNumMels; ++m) {
            const float lo = centers[m], mid = centers[m + 1], hi = centers[m + 2];
            for (int b = 0; b < numBins; ++b) {
                const float hz = (float)b * sampleRate / kFftSize;
                if (hz > lo && hz < mid) melFilters[m][b] = (hz - lo) / (mid - lo);
                else if (hz >= mid && hz < hi) melFilters[m][b] = (hi - hz) / (hi - mid);
            }
        }
    }

    void buildChromaMap() {
        chromaMap.assign(numBins, -1);
        for (int b = 1; b < numBins; ++b) {
            const float hz = (float)b * sampleRate / kFftSize;
            if (hz < 55.0f || hz > 4000.0f) continue; // A1..~C8: melodic range
            const float midi = 69.0f + 12.0f * std::log2(hz / 440.0f);
            chromaMap[b] = ((int)std::lround(midi)) % 12;
            if (chromaMap[b] < 0) chromaMap[b] += 12;
        }
    }

    void process(const float* samples, int count) {
        for (int i = 0; i < count; ++i) {
            const float s = samples[i];
            // Full-signal accumulators.
            sumSquares += (double)s * s;
            peak = std::max(peak, std::fabs(s));
            ++totalSamples;
            // K-weighted loudness path.
            const float kw = kHighpass.process(kShelf.process(s));
            blockSumSq += (double)kw * kw;
            if (++blockCount >= blockLen) {
                const double ms = blockSumSq / blockCount;
                const double lufs = -0.691 + 10.0 * std::log10(ms + 1e-12);
                if (lufs > -70.0) { loudSumSq += ms; ++loudCount; } // absolute gate
                blockSumSq = 0; blockCount = 0;
            }
        }
        // Frame assembly.
        pending.insert(pending.end(), samples, samples + count);
        while ((int)pending.size() >= kFftSize) {
            analyzeFrame(pending.data());
            pending.erase(pending.begin(), pending.begin() + kHopSize);
        }
    }

    void analyzeFrame(const float* frame) {
        // Zero-crossing rate (on the raw frame).
        int zc = 0;
        for (int i = 1; i < kFftSize; ++i)
            if ((frame[i] >= 0) != (frame[i - 1] >= 0)) ++zc;
        zcrSum += (double)zc / kFftSize;

        for (int i = 0; i < kFftSize; ++i) { re[i] = frame[i] * hann[i]; im[i] = 0.0f; }
        fft(re, im);

        double energy = 0, frameFlux = 0;
        double bass = 0, mid = 0, treble = 0;
        double centroidNum = 0;
        double geoLogSum = 0, ariSum = 0;
        for (int b = 0; b < numBins; ++b) {
            const float mag = std::sqrt(re[b] * re[b] + im[b] * im[b]);
            magnitude[b] = mag;
            const float hz = (float)b * sampleRate / kFftSize;
            const double e = (double)mag * mag;
            energy += e;
            if (hz < 250.0f) bass += e; else if (hz < 4000.0f) mid += e; else treble += e;
            centroidNum += hz * mag;
            geoLogSum += std::log(mag + 1e-10);
            ariSum += mag;
            const float d = mag - prevMagnitude[b];
            if (d > 0) frameFlux += d;
            prevMagnitude[b] = mag;
        }
        flux.push_back((float)frameFlux);
        frameLoudDb.push_back((float)(10.0 * std::log10(energy / numBins + 1e-12)));

        const double magSum = ariSum + 1e-10;
        const double centroid = centroidNum / magSum;
        centroidSum += centroid;
        flatnessSum += std::exp(geoLogSum / numBins) / (ariSum / numBins + 1e-10);

        // Rolloff (85% of energy) and bandwidth around the centroid.
        double cum = 0; const double target = 0.85 * energy;
        double bwNum = 0;
        float rolloffHz = 0;
        for (int b = 0; b < numBins; ++b) {
            const float hz = (float)b * sampleRate / kFftSize;
            cum += (double)magnitude[b] * magnitude[b];
            if (rolloffHz == 0 && cum >= target) rolloffHz = hz;
            bwNum += magnitude[b] * (hz - centroid) * (hz - centroid);
        }
        rolloffSum += rolloffHz;
        bandwidthSum += std::sqrt(bwNum / magSum);

        if (energy > 1e-9) {
            bassSum += bass / energy; midSum += mid / energy; trebleSum += treble / energy;
        }

        // Spectral contrast: mean over 6 octave bands of (peak dB - valley dB).
        double contrast = 0; int bands = 0;
        for (float lo = 200.0f; lo < sampleRate / 2.0f && bands < 6; lo *= 2.0f) {
            const int b0 = (int)(lo * kFftSize / sampleRate);
            const int b1 = std::min((int)(lo * 2 * kFftSize / sampleRate), numBins - 1);
            if (b1 - b0 < 4) break;
            float mx = 0, mn = 1e9f;
            for (int b = b0; b <= b1; ++b) { mx = std::max(mx, magnitude[b]); mn = std::min(mn, magnitude[b]); }
            contrast += 20.0 * std::log10((mx + 1e-10) / (mn + 1e-10));
            ++bands;
        }
        if (bands > 0) contrastSum += contrast / bands;

        // Chroma.
        for (int b = 0; b < numBins; ++b)
            if (chromaMap[b] >= 0) chromaSum[chromaMap[b]] += magnitude[b];

        // MFCC: mel energies -> log -> DCT-II.
        for (int m = 0; m < kNumMels; ++m) {
            double acc = 0;
            const auto& filt = melFilters[m];
            for (int b = 0; b < numBins; ++b) acc += filt[b] * magnitude[b];
            mels[m] = std::log((float)acc + 1e-10f);
        }
        for (int c = 0; c < kNumMfcc; ++c) {
            double acc = 0;
            for (int m = 0; m < kNumMels; ++m)
                acc += mels[m] * std::cos(kPi * c * (m + 0.5) / kNumMels);
            mfccSum[c] += acc;
            mfccSqSum[c] += acc * acc;
        }

        ++frameCount;
    }

    // Tempo from the spectral-flux onset envelope: autocorrelation over the
    // 60–200 BPM lag range; confidence = best-lag correlation vs. envelope
    // energy. Simple, robust for most produced music; weakest on rubato
    // classical, which the perceptual layer treats as "no beat" (bpm=0).
    void estimateTempo(float* bpmOut, float* confOut) {
        *bpmOut = 0; *confOut = 0;
        const int n = (int)flux.size();
        if (n < 64) return;
        // Normalize envelope.
        double mean = 0; for (float f : flux) mean += f; mean /= n;
        std::vector<float> env(n);
        for (int i = 0; i < n; ++i) env[i] = flux[i] - (float)mean;

        const float framesPerSec = (float)sampleRate / kHopSize;
        const int minLag = std::max(1, (int)(framesPerSec * 60.0f / 200.0f));
        const int maxLag = std::min(n / 2, (int)(framesPerSec * 60.0f / 60.0f));
        double e0 = 1e-9; for (int i = 0; i < n; ++i) e0 += (double)env[i] * env[i];

        double best = 0; int bestLag = 0;
        for (int lag = minLag; lag <= maxLag; ++lag) {
            double acc = 0;
            for (int i = 0; i + lag < n; ++i) acc += (double)env[i] * env[i + lag];
            if (acc > best) { best = acc; bestLag = lag; }
        }
        if (bestLag > 0) {
            *bpmOut = 60.0f * framesPerSec / bestLag;
            *confOut = (float)std::clamp(best / e0, 0.0, 1.0);
        }
    }

    int finish(float* out, int cap) {
        if (cap < HARMONY_FEATURE_COUNT || frameCount == 0 || totalSamples == 0) return -1;
        const double fc = (double)frameCount;

        out[0] = (float)std::sqrt(sumSquares / totalSamples);
        out[1] = peak;
        out[2] = loudCount > 0
            ? (float)(-0.691 + 10.0 * std::log10(loudSumSq / loudCount + 1e-12))
            : -70.0f;

        // Dynamic range: 95th vs 10th percentile frame loudness.
        {
            std::vector<float> sorted(frameLoudDb);
            std::sort(sorted.begin(), sorted.end());
            const float hi = sorted[(size_t)(0.95 * (sorted.size() - 1))];
            const float lo = sorted[(size_t)(0.10 * (sorted.size() - 1))];
            out[3] = std::max(0.0f, hi - lo);
        }

        out[4] = (float)(bassSum / fc);
        out[5] = (float)(midSum / fc);
        out[6] = (float)(trebleSum / fc);
        out[7] = (float)(centroidSum / fc);
        out[8] = (float)(rolloffSum / fc);
        out[9] = (float)(bandwidthSum / fc);
        out[10] = (float)(flatnessSum / fc);
        out[11] = (float)(contrastSum / fc);

        estimateTempo(&out[12], &out[13]);
        out[14] = (float)(zcrSum / fc);

        // Key estimation over all 24 rotations of the K-S profiles.
        {
            float chroma[12];
            double total = 1e-9;
            for (int i = 0; i < 12; ++i) total += chromaSum[i];
            for (int i = 0; i < 12; ++i) chroma[i] = (float)(chromaSum[i] / total);

            float bestScore = -2; int bestKey = -1; int bestMajor = 1;
            for (int root = 0; root < 12; ++root) {
                float rotated[12];
                for (int i = 0; i < 12; ++i) rotated[i] = chroma[(i + root) % 12];
                const float sMaj = correlate12(rotated, kMajorProfile);
                const float sMin = correlate12(rotated, kMinorProfile);
                if (sMaj > bestScore) { bestScore = sMaj; bestKey = root; bestMajor = 1; }
                if (sMin > bestScore) { bestScore = sMin; bestKey = root; bestMajor = 0; }
            }
            out[15] = (float)bestKey;
            out[16] = (float)bestMajor;
            for (int i = 0; i < 12; ++i) out[17 + i] = chroma[i];
        }

        for (int c = 0; c < kNumMfcc; ++c) {
            const double m = mfccSum[c] / fc;
            const double var = std::max(0.0, mfccSqSum[c] / fc - m * m);
            out[29 + c] = (float)m;
            out[42 + c] = (float)std::sqrt(var);
        }
        return HARMONY_FEATURE_COUNT;
    }
};

// ------------------------------------------------------------------ C API

extern "C" {

HarmonyAnalyzer* harmony_create(int32_t sample_rate) {
    if (sample_rate < 8000 || sample_rate > 384000) return nullptr;
    return new (std::nothrow) HarmonyAnalyzer(sample_rate);
}

void harmony_process(HarmonyAnalyzer* h, const float* samples, int32_t count) {
    if (h && samples && count > 0) h->process(samples, count);
}

int32_t harmony_finish(HarmonyAnalyzer* h, float* out, int32_t out_capacity) {
    return (h && out) ? h->finish(out, out_capacity) : -1;
}

void harmony_destroy(HarmonyAnalyzer* h) { delete h; }

} // extern "C"
