// Host tests for MobileEqEngine: build and run with CMake (see CMakeLists.txt here),
// or under sanitizers, e.g.
//   g++ -std=c++17 -O1 -g -fsanitize=address,undefined -I../../main/cpp
//       engine_test.cpp ../../main/cpp/MobileEqEngine.cpp -o engine_test -lpthread

#include "MobileEqEngine.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <random>
#include <thread>
#include <vector>

using namespace harmony::eq;

namespace {

int failures = 0;

#define CHECK(condition, ...)                                         \
    do {                                                              \
        if (!(condition)) {                                           \
            ++failures;                                               \
            std::printf("FAIL %s:%d: %s — ", __FILE__, __LINE__, #condition); \
            std::printf(__VA_ARGS__);                                 \
            std::printf("\n");                                        \
        }                                                             \
    } while (0)

constexpr double kPi = 3.14159265358979323846;

float dbfs(float linear) { return 20.f * std::log10(std::max(linear, 1e-12f)); }

std::unique_ptr<MobileEqEngine> engineAt(int sampleRate, int channels, OutputDevice device) {
    auto engine = std::make_unique<MobileEqEngine>();
    engine->setOutputDeviceType(device);
    CHECK(engine->configure(sampleRate, channels), "configure %d/%d", sampleRate, channels);
    return engine;
}

/** Runs [input] (interleaved float) through [engine] in [block]-frame calls plus the drain. */
std::vector<float> run(MobileEqEngine& engine, const std::vector<float>& input, int block = 512) {
    const int channels = engine.channels();
    const int frames = static_cast<int>(input.size()) / channels;
    std::vector<float> output(input.size() + static_cast<size_t>(engine.latencyFrames() + 1) * channels);
    std::vector<float> scratch(static_cast<size_t>(block) * channels);
    int written = 0;
    for (int start = 0; start < frames; start += block) {
        const int count = std::min(block, frames - start);
        std::memcpy(scratch.data(), input.data() + static_cast<size_t>(start) * channels,
                    static_cast<size_t>(count) * channels * sizeof(float));
        const int produced = engine.process(scratch.data(), SampleFormat::kFloat, count, scratch.data());
        std::memcpy(output.data() + static_cast<size_t>(written) * channels, scratch.data(),
                    static_cast<size_t>(produced) * channels * sizeof(float));
        written += produced;
    }
    written += engine.drain(output.data() + static_cast<size_t>(written) * channels, engine.latencyFrames() + 1);
    output.resize(static_cast<size_t>(written) * channels);
    return output;
}

std::vector<float> sine(int sampleRate, int channels, double frequency, float amplitude, double seconds) {
    const int frames = static_cast<int>(seconds * sampleRate);
    std::vector<float> out(static_cast<size_t>(frames) * channels);
    for (int i = 0; i < frames; ++i) {
        const float v = amplitude * static_cast<float>(std::sin(2.0 * kPi * frequency * i / sampleRate));
        for (int c = 0; c < channels; ++c) out[static_cast<size_t>(i) * channels + c] = v;
    }
    return out;
}

float peakOf(const std::vector<float>& x, size_t from = 0) {
    float peak = 0.f;
    for (size_t i = from; i < x.size(); ++i) peak = std::max(peak, std::fabs(x[i]));
    return peak;
}

/** Steady-state gain in dB of the engine for a sine at [frequency]. */
float gainAt(MobileEqEngine& engine, double frequency, float amplitude = 0.05f) {
    const int sr = engine.sampleRate();
    const auto in = sine(sr, engine.channels(), frequency, amplitude, 1.0);
    const auto out = run(engine, in);
    const size_t from = out.size() / 2;  // after the filters and smoothing settle
    return dbfs(peakOf(out, from)) - dbfs(amplitude);
}

void flatResponseIsTransparent() {
    for (int sr : {44100, 48000, 96000, 192000}) {
        auto engine = engineAt(sr, 2, OutputDevice::kUsbDacHiRes);
        CHECK(std::fabs(engine->targetPreampDb()) < 1e-4f, "flat preamp %f at %d", engine->targetPreampDb(), sr);
        for (double f : {100.0, 1000.0, 10000.0}) {
            const float g = gainAt(*engine, f);
            CHECK(std::fabs(g) < 0.1f, "flat gain %.3f dB at %.0f Hz, %d Hz", g, f, sr);
        }
    }
}

void bandsHitTheirTargets() {
    for (int sr : {44100, 48000, 96000, 192000}) {
        for (int b = 1; b < kNumBands - 1; ++b) {
            for (double g : {-12.0, -6.0, 6.0, 12.0}) {
                const Biquad f = designBand(b, sr, g);
                const double measured = 20.0 * std::log10(magnitudeAt(f, sr, kBandFrequencies[b]));
                CHECK(std::fabs(measured - g) < 0.05, "band %d at %d Hz: %.2f dB for %.1f", b, sr, measured, g);
            }
        }
        // Shelves (Q 0.707, corners on 31 Hz and 16 kHz): half the gain at the
        // corner, all of it beyond.
        const Biquad low = designBand(0, sr, 12.0);
        const double lowAtBand = 20.0 * std::log10(magnitudeAt(low, sr, kBandFrequencies[0]));
        const double lowFar = 20.0 * std::log10(magnitudeAt(low, sr, 5.0));
        CHECK(std::fabs(lowAtBand - 6.0) < 0.3, "low shelf at 31 Hz: %.2f", lowAtBand);
        CHECK(std::fabs(lowFar - 12.0) < 0.5, "low shelf at 5 Hz: %.2f", lowFar);
        const Biquad high = designBand(kNumBands - 1, sr, 12.0);
        const double highAtBand = 20.0 * std::log10(magnitudeAt(high, sr, 16000.0));
        CHECK(highAtBand > 5.0 && highAtBand < 8.0, "high shelf at 16 kHz (%d): %.2f", sr, highAtBand);
        const double highMid = 20.0 * std::log10(magnitudeAt(high, sr, 1000.0));
        CHECK(std::fabs(highMid) < 0.3, "high shelf leaves 1 kHz alone (%d): %.2f", sr, highMid);
    }
    // Proportional Q: narrower at higher gains.
    CHECK(proportionalQ(12.0) > proportionalQ(3.0), "Q grows with gain");
}

std::vector<float> whiteNoise(int sr, int channels, double seconds, float sigma, unsigned seed) {
    std::mt19937 rng(seed);
    std::normal_distribution<float> white(0.f, sigma);
    const size_t frames = static_cast<size_t>(seconds * sr);
    std::vector<float> out(frames * channels);
    for (size_t i = 0; i < frames; ++i) {
        const float v = white(rng);
        for (int c = 0; c < channels; ++c) out[i * channels + c] = v;
    }
    return out;
}

void livePreampFollowsTheEnergy() {
    const int sr = 48000;
    auto engine = engineAt(sr, 2, OutputDevice::kWiredHeadphones);
    float gains[kNumBands] = {};
    gains[1] = 10.f;  // +10 dB at 62 Hz
    engine->setBandGainsDb(gains);
    // A voice-like 1 kHz line: the bass boost has nothing to lift, so almost no headroom is taken.
    auto voice = sine(sr, 2, 1000.0, 0.3f, 1.0);
    run(*engine, voice);
    CHECK(engine->targetPreampDb() > -1.5f, "preamp on a 1 kHz line: %.2f", engine->targetPreampDb());
    // A bass line at 62 Hz: now the boost is all there is, and the preamp takes it back fast.
    auto bass = sine(sr, 2, 62.0, 0.3f, 0.3);
    run(*engine, bass);
    CHECK(engine->targetPreampDb() < -8.f, "preamp on a 62 Hz line: %.2f", engine->targetPreampDb());
    // Back to the voice: given back, slowly but surely.
    run(*engine, voice);
    CHECK(engine->targetPreampDb() > -1.5f, "preamp given back: %.2f", engine->targetPreampDb());

    // Everything at +12 dB on broadband noise: about 12 dB taken.
    for (float& g : gains) g = 12.f;
    engine->setBandGainsDb(gains);
    run(*engine, whiteNoise(sr, 2, 1.0, 0.1f, 3));
    CHECK(engine->targetPreampDb() < -10.5f && engine->targetPreampDb() > -12.5f, "preamp for all +12 dB: %.2f",
          engine->targetPreampDb());

    // Cuts never raise the level.
    for (float& g : gains) g = -12.f;
    engine->setBandGainsDb(gains);
    run(*engine, whiteNoise(sr, 2, 1.0, 0.1f, 4));
    CHECK(engine->targetPreampDb() <= 0.f, "preamp for cuts: %.2f", engine->targetPreampDb());

    // What the UI reads: the same preamp, without the ReplayGain level.
    engine->setLevelGainDb(-6.f);
    run(*engine, whiteNoise(sr, 2, 0.1, 0.1f, 5));
    const float telemetry = engine->pollDspTelemetry(nullptr, nullptr, nullptr, 0);
    CHECK(std::fabs(telemetry - (engine->targetPreampDb() + 6.f)) < 0.05f, "telemetry preamp %.2f vs %.2f",
          telemetry, engine->targetPreampDb());
}

void levelGainAddsToThePreamp() {
    auto engine = engineAt(48000, 2, OutputDevice::kWiredHeadphones);
    std::vector<float> nothing(512 * 2, 0.f);
    engine->setLevelGainDb(-7.5f);
    run(*engine, nothing);
    CHECK(std::fabs(engine->targetPreampDb() - (-7.5f)) < 1e-3f, "level -7.5: %.2f", engine->targetPreampDb());
    engine->setEqEnabled(false);  // levelling stays with the EQ off
    run(*engine, nothing);
    CHECK(std::fabs(engine->targetPreampDb() - (-7.5f)) < 1e-3f, "level with EQ off: %.2f", engine->targetPreampDb());
    engine->setLevelGainDb(40.f);  // clamped
    run(*engine, nothing);
    CHECK(std::fabs(engine->targetPreampDb() - 15.f) < 1e-3f, "level clamp: %.2f", engine->targetPreampDb());
    engine->setLevelGainDb(NAN);
    run(*engine, nothing);
    CHECK(std::fabs(engine->targetPreampDb()) < 1e-3f, "NaN level: %.2f", engine->targetPreampDb());
}

void neverClips() {
    std::mt19937 random(7);
    std::uniform_real_distribution<float> noise(-1.f, 1.f);
    for (auto device : {OutputDevice::kPhoneSpeaker, OutputDevice::kWiredHeadphones,
                        OutputDevice::kBluetoothA2dp, OutputDevice::kUsbDacHiRes}) {
        for (int sr : {44100, 192000}) {
            auto engine = engineAt(sr, 2, device);
            float gains[kNumBands];
            for (float& g : gains) g = 12.f;
            engine->setBandGainsDb(gains);
            // Full-scale noise, a full-scale bass sine and a sweep: the worst there is.
            std::vector<float> input(static_cast<size_t>(sr) * 2 * 2);
            for (size_t i = 0; i < input.size() / 2; ++i) input[i] = noise(random);
            const size_t half = input.size() / 4;
            for (size_t i = 0; i < half; ++i) {
                const float v = static_cast<float>(std::sin(2.0 * kPi * 60.0 * i / sr));
                input[input.size() / 2 + i * 2] = v;
                input[input.size() / 2 + i * 2 + 1] = v;
            }
            const auto out = run(*engine, input, 333);
            const float ceiling = std::pow(10.f, engine->ceilingDb() / 20.f);
            const float peak = peakOf(out);
            CHECK(peak <= ceiling * 1.000001f, "device %d at %d Hz peaks at %.4f dBFS (ceiling %.2f)",
                  static_cast<int>(device), sr, dbfs(peak), engine->ceilingDb());
            bool finite = true;
            for (float v : out) finite &= std::isfinite(v);
            CHECK(finite, "non-finite output");
        }
    }
}

void outputStaysAlignedAndComplete() {
    for (int sr : {44100, 48000, 96000, 192000}) {
        auto engine = engineAt(sr, 2, OutputDevice::kUsbDacHiRes);
        const int expectedLatency = static_cast<int>(std::lround(0.002 * sr)) - 1;
        CHECK(engine->latencyFrames() == expectedLatency, "latency %d at %d", engine->latencyFrames(), sr);
        const auto in = sine(sr, 2, 1000.0, 0.1f, 0.25);
        const auto out = run(*engine, in, 100);
        CHECK(out.size() == in.size(), "%zu frames out for %zu in at %d", out.size() / 2, in.size() / 2, sr);
        // Same samples at the same positions (a flat EQ is transparent at 1 kHz).
        float worst = 0.f;
        for (size_t i = in.size() / 4; i < in.size() * 3 / 4; ++i) worst = std::max(worst, std::fabs(out[i] - in[i]));
        CHECK(worst < 2e-3f, "misaligned by up to %.5f at %d", worst, sr);
    }
}

void slidersDoNotClick() {
    const int sr = 48000;
    auto engine = engineAt(sr, 1, OutputDevice::kWiredHeadphones);
    const auto in = sine(sr, 1, 1000.0, 0.1f, 1.0);
    std::vector<float> out;
    std::vector<float> block(256);
    float gains[kNumBands] = {};
    float baseline = 0.f;
    float during = 0.f;
    for (size_t start = 0; start + 256 <= in.size(); start += 256) {
        if (start == in.size() / 2 - (in.size() / 2) % 256) {
            gains[5] = 12.f;  // slam the 1 kHz slider to the top
            engine->setBandGainsDb(gains);
        }
        std::memcpy(block.data(), in.data() + start, 256 * sizeof(float));
        const int produced = engine->process(block.data(), SampleFormat::kFloat, 256, block.data());
        out.insert(out.end(), block.begin(), block.begin() + produced);
    }
    // Clicks show as spikes in the second difference; a clean sine has a bounded one.
    for (size_t i = 2; i < out.size(); ++i) {
        const float d2 = std::fabs(out[i] - 2.f * out[i - 1] + out[i - 2]);
        if (i < out.size() / 2 - 512) baseline = std::max(baseline, d2 / std::max(1e-6f, peakOf(out, out.size() / 4) ));
        if (i > out.size() / 2 - 512) during = std::max(during, d2);
    }
    // The live preamp takes the new headroom back within milliseconds, so the
    // level moves briefly: bound by the loudest the sine got, not the final one.
    const float level = peakOf(out, out.size() / 2 - 512);
    // The second difference of a sine of amplitude A at w is A*w^2: allow for the louder level only.
    const float w = static_cast<float>(2.0 * kPi * 1000.0 / sr);
    CHECK(during <= level * w * w * 1.3f, "second difference %.5f vs %.5f expected", during, level * w * w);
}

void deviceHighPass() {
    auto speaker = engineAt(48000, 2, OutputDevice::kPhoneSpeaker);
    const float speaker40 = gainAt(*speaker, 40.0);
    auto wired = engineAt(48000, 2, OutputDevice::kWiredHeadphones);
    const float wired40 = gainAt(*wired, 40.0);
    auto bluetooth = engineAt(48000, 2, OutputDevice::kBluetoothA2dp);
    const float bluetooth1k = gainAt(*bluetooth, 1000.0);
    CHECK(speaker40 < -10.f, "speaker passes 40 Hz at %.2f dB", speaker40);
    CHECK(wired40 > -0.6f, "headphones cut 40 Hz to %.2f dB", wired40);
    CHECK(std::fabs(bluetooth1k + 0.3f) < 0.05f, "Bluetooth pad: %.3f dB", bluetooth1k);
}

void autoRulesFollowTheTarget() {
    for (auto device : {OutputDevice::kWiredHeadphones, OutputDevice::kPhoneSpeaker}) {
        float target[kNumBands];
        MobileEqEngine::targetCurveDb(device, target);
        double power[kNumBands];
        float out[kNumBands];
        // Music that already sits on the target: nothing to do.
        for (int b = 0; b < kNumBands; ++b) power[b] = std::pow(10.0, target[b] / 10.0);
        MobileEqEngine::autoOffsetTargetDb(device, power, out);
        for (int b = 0; b < kNumBands; ++b) CHECK(std::fabs(out[b]) < 0.05f, "on target, band %d: %.2f", b, out[b]);
        // Thin sub-bass and air, muddy and harsh: every rule at once.
        const float shape[kNumBands] = {-8.f, -8.f, 8.f, 8.f, 0.f, 0.f, 8.f, 8.f, -8.f, -8.f};
        for (int b = 0; b < kNumBands; ++b) power[b] = std::pow(10.0, (target[b] + shape[b]) / 10.0);
        MobileEqEngine::autoOffsetTargetDb(device, power, out);
        const bool speaker = device == OutputDevice::kPhoneSpeaker;
        for (int b : {0, 1}) {
            if (speaker) CHECK(out[b] == 0.f, "speaker sub-bass never boosted, band %d: %.2f", b, out[b]);
            else CHECK(out[b] > 2.f && out[b] <= 4.5f, "thin sub-bass lifted, band %d: %.2f", b, out[b]);
        }
        for (int b : {8, 9}) CHECK(out[b] > 2.f && out[b] <= 4.5f, "thin air lifted, band %d: %.2f", b, out[b]);
        for (int b : {2, 3}) CHECK(out[b] < -2.f && out[b] >= -4.5f, "mud cut, band %d: %.2f", b, out[b]);
        for (int b : {6, 7}) CHECK(out[b] < -2.f && out[b] >= -4.5f, "harshness cut, band %d: %.2f", b, out[b]);
        for (int b : {4, 5}) CHECK(out[b] == 0.f, "the middle holds, band %d: %.2f", b, out[b]);
        // The opposite: never cut sub-bass or air, never lift mud or harshness.
        for (int b = 0; b < kNumBands; ++b) power[b] = std::pow(10.0, (target[b] - shape[b]) / 10.0);
        MobileEqEngine::autoOffsetTargetDb(device, power, out);
        for (int b : {0, 1, 8, 9}) CHECK(out[b] >= 0.f, "band %d only ever lifted: %.2f", b, out[b]);
        for (int b : {2, 3, 6, 7}) CHECK(out[b] <= 0.f, "band %d only ever cut: %.2f", b, out[b]);
        // A band that isn't there at all (an MP3's cut-off top) is left alone.
        for (int b = 0; b < kNumBands; ++b) power[b] = std::pow(10.0, target[b] / 10.0);
        power[9] = 0.0;
        MobileEqEngine::autoOffsetTargetDb(device, power, out);
        CHECK(out[9] == 0.f, "absent top not boosted: %.2f", out[9]);
    }
}

void feed(MobileEqEngine& engine, std::vector<float> audio) {
    const int channels = engine.channels();
    const size_t total = audio.size() / channels;
    for (size_t at = 0; at < total; at += 4800) {
        const int frames = static_cast<int>(std::min<size_t>(4800, total - at));
        engine.process(audio.data() + at * channels, SampleFormat::kFloat, frames, audio.data() + at * channels);
    }
}

void autoCalibratesLive() {
    const int sr = 48000;
    auto engine = engineAt(sr, 2, OutputDevice::kWiredHeadphones);
    engine->setAutoMode(true);
    float gains[kNumBands] = {};
    gains[5] = 3.f;  // a slider of the listener's: Auto adds to it, never replaces it
    engine->setBandGainsDb(gains);
    // White noise, against a -4.5 dB/octave target: thin at the bottom, harsh in the upper mids.
    feed(*engine, whiteNoise(sr, 2, 6.0, 0.1f, 11));
    float effective[kNumBands], offsets[kNumBands];
    float bars[32], peaks[32];
    const float preamp = engine->pollDspTelemetry(effective, bars, peaks, 32);
    engine->liveAutoOffsetsDb(offsets);
    CHECK(!engine->tonalFrozen(), "playing music is not frozen");
    // 62 Hz is thin and lifted; white noise's 31 Hz octave sits so far under the
    // target that it counts as absent, and absent bands are never boosted.
    CHECK(offsets[1] > 2.f && offsets[0] >= 0.f, "sub-bass lifted: %.2f %.2f", offsets[0], offsets[1]);
    CHECK(offsets[6] < -2.f && offsets[7] < -2.f, "upper mids tamed: %.2f %.2f", offsets[6], offsets[7]);
    CHECK(offsets[4] == 0.f && offsets[5] == 0.f, "middle holds: %.2f %.2f", offsets[4], offsets[5]);
    for (int b = 0; b < kNumBands; ++b) {
        CHECK(offsets[b] >= -4.5f && offsets[b] <= 4.5f, "band %d offset %.2f within ±4.5", b, offsets[b]);
        CHECK(std::fabs(effective[b] - std::clamp(gains[b] + offsets[b], -12.f, 12.f)) < 0.3f,
              "band %d effective %.2f = slider %.1f + offset %.2f", b, effective[b], gains[b], offsets[b]);
    }
    CHECK(std::isfinite(preamp) && preamp <= 0.f, "live preamp %.2f", preamp);

    // Silence: below -42 dBFS the tonal adaptation freezes and the offsets hold.
    feed(*engine, whiteNoise(sr, 2, 3.0, 0.001f, 12));  // about -60 dBFS
    float held[kNumBands];
    engine->liveAutoOffsetsDb(held);
    CHECK(engine->tonalFrozen(), "quiet passage freezes the adaptation");
    for (int b = 0; b < kNumBands; ++b) {
        CHECK(std::fabs(held[b] - offsets[b]) < 0.05f, "band %d held %.2f (was %.2f)", b, held[b], offsets[b]);
    }

    // Auto off: the offsets glide back to nothing.
    engine->setAutoMode(false);
    feed(*engine, whiteNoise(sr, 2, 6.0, 0.1f, 13));
    engine->liveAutoOffsetsDb(offsets);
    for (int b = 0; b < kNumBands; ++b) CHECK(std::fabs(offsets[b]) < 0.05f, "Auto off, band %d: %.2f", b, offsets[b]);

    // On the phone speaker the same music gets no sub-bass boost.
    auto speaker = engineAt(sr, 2, OutputDevice::kPhoneSpeaker);
    speaker->setAutoMode(true);
    feed(*speaker, whiteNoise(sr, 2, 6.0, 0.1f, 11));
    speaker->liveAutoOffsetsDb(offsets);
    CHECK(offsets[0] == 0.f && offsets[1] == 0.f, "speaker sub-bass: %.2f %.2f", offsets[0], offsets[1]);
}

void tamersDuckBassAndHarshness() {
    const int sr = 48000;
    auto engine = engineAt(sr, 2, OutputDevice::kWiredHeadphones);
    float gains[kNumBands] = {};
    gains[0] = 8.f;
    gains[1] = 8.f;
    gains[7] = 6.f;
    gains[8] = 6.f;
    engine->setBandGainsDb(gains);
    // Quiet bass: no ducking.
    feed(*engine, sine(sr, 2, 50.0, 0.1f, 1.0));
    CHECK(engine->bassDuckDb() < 0.01f, "ducking on quiet bass: %.2f", engine->bassDuckDb());
    CHECK(std::fabs(engine->designedBandGainDb(1) - 8.f) < 0.01f, "62 Hz band at %.2f", engine->designedBandGainDb(1));
    // A heavy 50 Hz line near -1 dBFS: the bass boost comes back by up to 4 dB.
    feed(*engine, sine(sr, 2, 50.0, 0.9f, 0.5));
    const float duck = engine->bassDuckDb();
    CHECK(duck > 1.5f && duck <= 4.f, "ducking on heavy bass: %.2f", duck);
    CHECK(std::fabs(engine->designedBandGainDb(0) - (8.f - duck)) < 0.02f, "31 Hz band at %.2f", engine->designedBandGainDb(0));
    CHECK(std::fabs(engine->designedBandGainDb(2)) < 0.01f, "125 Hz untouched: %.2f", engine->designedBandGainDb(2));
    // Harsh sibilance around 5.6 kHz: the 4/8 kHz boost is tamed; quiet sibilance isn't.
    feed(*engine, sine(sr, 2, 5657.0, 0.005f, 0.5));
    CHECK(engine->harshDuckDb() < 0.01f, "tamer on quiet sibilance: %.2f", engine->harshDuckDb());
    feed(*engine, sine(sr, 2, 5657.0, 0.5f, 0.5));
    const float harsh = engine->harshDuckDb();
    CHECK(harsh > 1.5f && harsh <= 4.f, "tamer on harsh sibilance: %.2f", harsh);
    CHECK(std::fabs(engine->designedBandGainDb(7) - (6.f - harsh)) < 0.02f, "4 kHz band at %.2f", engine->designedBandGainDb(7));
    CHECK(std::fabs(engine->designedBandGainDb(5)) < 0.01f, "1 kHz untouched: %.2f", engine->designedBandGainDb(5));
    // The ceiling follows the output: -0.4 dBFS on Bluetooth.
    auto bluetooth = engineAt(sr, 2, OutputDevice::kBluetoothA2dp);
    CHECK(std::fabs(bluetooth->ceilingDb() + 0.4f) < 1e-4f, "Bluetooth ceiling %.2f", bluetooth->ceilingDb());
}

void convertsEveryFormat() {
    const int sr = 48000;
    const int frames = 4800;
    std::vector<float> expected(frames);
    for (int i = 0; i < frames; ++i) expected[i] = 0.25f * static_cast<float>(std::sin(2.0 * kPi * 1000.0 * i / sr));
    // Reference: the same signal as float, through an identical engine.
    std::vector<float> reference(frames);
    {
        auto engine = engineAt(sr, 1, OutputDevice::kUsbDacHiRes);
        std::vector<float> in = expected;
        engine->process(in.data(), SampleFormat::kFloat, frames, reference.data());
    }
    auto check = [&](SampleFormat format, const std::vector<uint8_t>& bytes, float tolerance) {
        auto engine = engineAt(sr, 1, OutputDevice::kUsbDacHiRes);
        std::vector<float> out(frames);
        const int produced = engine->process(bytes.data(), format, frames, out.data());
        float worst = 0.f;
        for (int i = 0; i < produced; ++i) worst = std::max(worst, std::fabs(out[i] - reference[i]));
        CHECK(worst < tolerance, "format %d off by %.7f", static_cast<int>(format), worst);
    };
    std::vector<uint8_t> pcm16(frames * 2), pcm24(frames * 3), pcm32(frames * 4);
    for (int i = 0; i < frames; ++i) {
        const auto v16 = static_cast<int16_t>(std::lround(expected[i] * 32767.0));
        std::memcpy(&pcm16[i * 2], &v16, 2);
        const auto v24 = static_cast<int32_t>(std::lround(expected[i] * 8388607.0));
        pcm24[i * 3] = static_cast<uint8_t>(v24 & 0xFF);
        pcm24[i * 3 + 1] = static_cast<uint8_t>((v24 >> 8) & 0xFF);
        pcm24[i * 3 + 2] = static_cast<uint8_t>((v24 >> 16) & 0xFF);
        const auto v32 = static_cast<int32_t>(std::lround(expected[i] * 2147483647.0));
        std::memcpy(&pcm32[i * 4], &v32, 4);
    }
    // Each within its own quantisation step (and the EQ's tiny gain on it).
    check(SampleFormat::kPcm16, pcm16, 2.f / 32768.f);
    check(SampleFormat::kPcm24Packed, pcm24, 2.f / 8388608.f);
    check(SampleFormat::kPcm32, pcm32, 1e-6f);

    // NaN and infinity in float input must not poison the filters.
    auto engine = engineAt(sr, 1, OutputDevice::kWiredHeadphones);
    std::vector<float> poisoned = sine(sr, 1, 440.0, 0.3f, 0.1);
    poisoned[100] = std::nanf("");
    poisoned[200] = INFINITY;
    const auto out = run(*engine, poisoned);
    bool finite = true;
    for (float v : out) finite &= std::isfinite(v);
    CHECK(finite, "NaN/Inf leaked into the output");
}

void corruptFramesNeverSilencePlayback() {
    // A corrupt stretch in the middle of a song: NaN, infinities and absurd
    // values, as a broken decoder frame might deliver. The output must stay
    // finite and under the ceiling, and the music must come straight back:
    // no silence that only a seek would end.
    for (bool inPlace : {true, false}) {
        const int sr = 48000;
        auto engine = engineAt(sr, 2, OutputDevice::kWiredHeadphones);
        engine->setAutoMode(true);
        float gains[kNumBands] = {6, 4, 2, 0, -2, 0, 2, 4, 6, 8};
        engine->setBandGainsDb(gains);
        std::vector<float> song = sine(sr, 2, 1000.0, 0.25f, 2.0);
        const size_t corruptFrom = static_cast<size_t>(sr) * 2 / 2;  // at 0.5 s
        for (size_t i = corruptFrom; i < corruptFrom + 4800; ++i) {
            switch (i % 4) {
                case 0: song[i] = std::nanf(""); break;
                case 1: song[i] = INFINITY; break;
                case 2: song[i] = -1e30f; break;
                default: song[i] = 3e38f; break;
            }
        }
        std::vector<float> out(song.size());
        const int frames = static_cast<int>(song.size() / 2);
        int written = 0;
        for (int start = 0; start < frames; start += 480) {
            const int count = std::min(480, frames - start);
            float* in = song.data() + static_cast<size_t>(start) * 2;
            float* dst = out.data() + static_cast<size_t>(written) * 2;
            if (inPlace) {
                const int produced = engine->process(in, SampleFormat::kFloat, count, in);
                std::memcpy(dst, in, static_cast<size_t>(produced) * 2 * sizeof(float));
                written += produced;
            } else {
                written += engine->process(in, SampleFormat::kFloat, count, dst);
            }
        }
        bool finite = true;
        for (int i = 0; i < written * 2; ++i) finite &= std::isfinite(out[i]);
        CHECK(finite, "corrupt frames leaked non-finite output (in place %d)", inPlace);
        const float ceiling = std::pow(10.f, engine->ceilingDb() / 20.f);
        CHECK(peakOf(out) <= ceiling * 1.000001f, "corrupt frames broke the ceiling: %.3f", peakOf(out));
        // The last half second: the sine is back at its level (the limiter and preamp recovered).
        std::vector<float> tail(out.begin() + static_cast<long>(written * 2 - sr), out.begin() + written * 2);
        CHECK(peakOf(tail) > 0.1f, "playback stayed quiet after the corrupt frames: %.3f", peakOf(tail));
        CHECK(engine->sanitizedSamples() >= 4800, "repaired samples counted: %u", engine->sanitizedSamples());
        CHECK(engine->healCount() == 0, "sanitized input never needs a heal: %u", engine->healCount());
        float live[kNumBands];
        engine->effectiveGainsDb(live);
        for (float g : live) CHECK(std::isfinite(g), "live gains stay finite");
        CHECK(std::isfinite(engine->targetPreampDb()), "preamp stays finite");
    }
}

void selfHealsPoisonedState() {
    const int sr = 48000;
    auto engine = engineAt(sr, 2, OutputDevice::kWiredHeadphones);
    float gains[kNumBands] = {3, 3, 3, 3, 3, 3, 3, 3, 3, 3};
    engine->setBandGainsDb(gains);
    run(*engine, sine(sr, 2, 440.0, 0.2f, 0.2));
    engine->poisonStateForTest();
    const auto out = run(*engine, sine(sr, 2, 440.0, 0.2f, 1.0));
    bool finite = true;
    for (float v : out) finite &= std::isfinite(v);
    CHECK(finite, "poisoned state leaked into the output");
    CHECK(engine->healCount() >= 1, "the heal ran: %u", engine->healCount());
    std::vector<float> tail(out.end() - sr, out.end());
    CHECK(peakOf(tail) > 0.1f, "sound came back after the heal: %.3f", peakOf(tail));
}

void sampleRateChangesReconfigure() {
    auto engine = engineAt(44100, 2, OutputDevice::kUsbDacHiRes);
    float gains[kNumBands] = {};
    gains[5] = 6.f;
    engine->setBandGainsDb(gains);
    for (int sr : {44100, 96000, 192000, 48000, 384000}) {
        CHECK(engine->configure(sr, 2), "configure %d", sr);
        const float g = gainAt(*engine, 1000.0);
        const float expected = 6.f + engine->targetPreampDb();
        CHECK(std::fabs(g - expected) < 0.15f, "1 kHz at %d Hz: %.2f dB, expected %.2f", sr, g, expected);
    }
    CHECK(!engine->configure(500000, 2), "accepted 500 kHz");
    CHECK(!engine->configure(48000, 9), "accepted 9 channels");
}

int argmaxBar(const float* bars, int n) {
    int best = 0;
    for (int i = 1; i < n; ++i) if (bars[i] > bars[best]) best = i;
    return best;
}

void spectrumFindsTheTone() {
    const int sr = 48000;
    auto engine = engineAt(sr, 2, OutputDevice::kUsbDacHiRes);
    engine->setSpectrumBarCount(32);
    float bars[kMaxBars];
    float peaks[kMaxBars];
    int64_t clock = 1'000'000'000;
    const int perFrame = sr / 60;
    std::vector<float> block;
    float phase = 0.f;
    for (int frame = 0; frame < 60; ++frame) {  // one second at 60 fps
        block.assign(static_cast<size_t>(perFrame) * 2, 0.f);
        for (int i = 0; i < perFrame; ++i) {
            const float v = 0.5f * std::sin(phase);
            phase += static_cast<float>(2.0 * kPi * 1000.0 / sr);
            block[i * 2] = block[i * 2 + 1] = v;
        }
        engine->process(block.data(), SampleFormat::kFloat, perFrame, block.data());
        clock += 16'666'667;
        const int n = engine->pollSpectrumAt(clock, bars, peaks, kMaxBars);
        CHECK(n == 32, "bars %d", n);
    }
    const int loudest = argmaxBar(bars, 32);
    const double low = 20.0 * std::pow(1000.0, static_cast<double>(loudest) / 32);
    const double high = 20.0 * std::pow(1000.0, static_cast<double>(loudest + 1) / 32);
    CHECK(low <= 1000.0 && 1000.0 <= high * 1.05, "loudest bar %d covers %.0f-%.0f Hz", loudest, low, high);
    // -6 dBFS on a 72 dB scale.
    CHECK(bars[loudest] > 0.85f && bars[loudest] <= 1.f, "tone bar at %.3f", bars[loudest]);
    CHECK(peaks[loudest] >= bars[loudest], "peak below bar");
    CHECK(bars[2] < 0.3f, "20-30 Hz bar at %.3f with no bass", bars[2]);

    // Silence (paused): the bars fall to nothing, the peaks after their hold.
    for (int frame = 0; frame < 150; ++frame) {
        clock += 16'666'667;
        engine->pollSpectrumAt(clock, bars, peaks, kMaxBars);
    }
    CHECK(bars[loudest] < 0.01f, "bar still at %.3f after silence", bars[loudest]);
    CHECK(peaks[loudest] < 0.01f, "peak still at %.3f after silence", peaks[loudest]);

    engine->setSpectrumBarCount(64);
    CHECK(engine->pollSpectrumAt(clock + 16'666'667, bars, peaks, kMaxBars) == 64, "64 bars");
}

void analyzerRunsConcurrently() {
    // The audio thread and the UI thread at full speed together, for TSan.
    auto engine = engineAt(96000, 2, OutputDevice::kUsbDacHiRes);
    std::atomic<bool> stop{false};
    std::thread ui([&] {
        float bars[kMaxBars];
        float peaks[kMaxBars];
        while (!stop.load()) {
            float live[kNumBands];
            engine->pollDspTelemetry(live, bars, peaks, kMaxBars);
            float gains[kNumBands];
            for (float& g : gains) g = static_cast<float>(std::rand() % 25 - 12);
            engine->setBandGainsDb(gains);
            engine->setOutputDeviceType(static_cast<OutputDevice>(std::rand() % 4));
            engine->setAutoMode(std::rand() % 2 == 0);
        }
    });
    std::vector<float> block(1024 * 2);
    std::mt19937 random(1);
    std::uniform_real_distribution<float> noise(-1.f, 1.f);
    float worst = 0.f;
    for (int i = 0; i < 2000; ++i) {
        for (float& v : block) v = noise(random);
        const int produced = engine->process(block.data(), SampleFormat::kFloat, 1024, block.data());
        for (int s = 0; s < produced * 2; ++s) worst = std::max(worst, std::fabs(block[s]));
    }
    stop = true;
    ui.join();
    CHECK(worst <= 1.f, "concurrent run peaked at %.4f", worst);
}

void fastEnoughFor192k() {
    auto engine = engineAt(192000, 2, OutputDevice::kUsbDacHiRes);
    float gains[kNumBands] = {6, 4, 2, -2, 0, 3, -4, 5, 2, 6};
    engine->setBandGainsDb(gains);
    engine->setAutoMode(true);
    std::vector<float> block(4096 * 2);
    std::mt19937 random(3);
    std::uniform_real_distribution<float> noise(-0.5f, 0.5f);
    for (float& v : block) v = noise(random);
    const int seconds = 10;
    const auto start = std::chrono::steady_clock::now();
    std::vector<float> scratch(block.size());
    for (int frames = 0; frames < 192000 * seconds; frames += 4096) {
        scratch = block;
        engine->process(scratch.data(), SampleFormat::kFloat, 4096, scratch.data());
    }
    const double elapsed = std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
    std::printf("  192 kHz stereo: %.0fx real time on this host\n", seconds / elapsed);
#if !defined(__SANITIZE_ADDRESS__) && !defined(__SANITIZE_THREAD__)
    CHECK(seconds / elapsed > 20.0, "only %.1fx real time", seconds / elapsed);
#endif
}

}  // namespace

int main() {
    flatResponseIsTransparent();
    bandsHitTheirTargets();
    livePreampFollowsTheEnergy();
    levelGainAddsToThePreamp();
    neverClips();
    outputStaysAlignedAndComplete();
    slidersDoNotClick();
    deviceHighPass();
    autoRulesFollowTheTarget();
    autoCalibratesLive();
    tamersDuckBassAndHarshness();
    convertsEveryFormat();
    corruptFramesNeverSilencePlayback();
    selfHealsPoisonedState();
    sampleRateChangesReconfigure();
    spectrumFindsTheTone();
    analyzerRunsConcurrently();
    fastEnoughFor192k();
    if (failures == 0) {
        std::printf("All MobileEqEngine tests passed\n");
        return 0;
    }
    std::printf("%d check(s) failed\n", failures);
    return 1;
}
