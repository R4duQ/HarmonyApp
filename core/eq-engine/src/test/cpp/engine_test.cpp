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
    engine->setOutputDevice(device);
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

void autoPreampCompensates() {
    auto engine = engineAt(48000, 2, OutputDevice::kWiredHeadphones);
    float gains[kNumBands];
    for (float& g : gains) g = 12.f;
    engine->setBandGainsDb(gains);
    std::vector<float> nothing(512 * 2, 0.f);
    run(*engine, nothing);
    const float preamp = engine->targetPreampDb();
    CHECK(preamp < -7.5f && preamp > -12.f, "preamp for all +12 dB: %.2f", preamp);

    for (float& g : gains) g = 0.f;
    gains[5] = 6.f;
    engine->setBandGainsDb(gains);
    run(*engine, nothing);
    CHECK(std::fabs(engine->targetPreampDb() - (-0.65f * 6.f)) < 0.3f, "preamp for +6 dB: %.2f",
          engine->targetPreampDb());

    // Cuts never raise the level.
    for (float& g : gains) g = -12.f;
    engine->setBandGainsDb(gains);
    run(*engine, nothing);
    CHECK(engine->targetPreampDb() <= 0.f, "preamp for cuts: %.2f", engine->targetPreampDb());
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
    const float level = peakOf(out, out.size() * 3 / 4);
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

void autoMorphsOver400ms() {
    const int sr = 48000;
    auto engine = engineAt(sr, 2, OutputDevice::kWiredHeadphones);
    float target[kNumBands] = {};
    target[5] = 6.f;
    engine->setAutoTargetGainsDb(target);
    engine->setAutoMode(true);
    std::vector<float> silence(static_cast<size_t>(sr / 5) * 2, 0.f);  // 200 ms
    std::vector<float> scratch = silence;
    engine->process(scratch.data(), SampleFormat::kFloat, sr / 5, scratch.data());
    const float halfway = engine->designedBandGainDb(5);
    CHECK(halfway > 2.f && halfway < 4.f, "after 200 ms: %.2f dB", halfway);
    scratch = silence;
    engine->process(scratch.data(), SampleFormat::kFloat, sr / 5, scratch.data());
    scratch = silence;
    engine->process(scratch.data(), SampleFormat::kFloat, sr / 20, scratch.data());
    CHECK(std::fabs(engine->designedBandGainDb(5) - 6.f) < 0.01f, "after 450 ms: %.2f dB",
          engine->designedBandGainDb(5));
}

void autoDucksHeavySubBass() {
    const int sr = 48000;
    auto engine = engineAt(sr, 2, OutputDevice::kWiredHeadphones);
    float target[kNumBands] = {};
    target[0] = 8.f;
    target[1] = 8.f;
    engine->setAutoTargetGainsDb(target);
    engine->setAutoMode(true);
    // Quiet bass first: no ducking.
    auto quiet = sine(sr, 2, 50.0, 0.1f, 1.0);
    engine->process(quiet.data(), SampleFormat::kFloat, sr, quiet.data());
    CHECK(engine->bassDuckDb() < 0.01f, "ducking on quiet bass: %.2f", engine->bassDuckDb());
    // Auto also listens to the song; the curve it plays is in autoCurveDb, ducking comes off that.
    float curve[kNumBands];
    engine->autoCurveDb(curve);
    CHECK(std::fabs(engine->designedBandGainDb(1) - curve[1]) < 0.01f, "62 Hz band at %.2f (curve %.2f)",
          engine->designedBandGainDb(1), curve[1]);
    // A heavy 50 Hz line near -1 dBFS: ducks by about 3.3 dB, never more than 4.
    auto loud = sine(sr, 2, 50.0, 0.9f, 0.5);
    engine->process(loud.data(), SampleFormat::kFloat, sr / 2, loud.data());
    const float duck = engine->bassDuckDb();
    CHECK(duck > 2.5f && duck <= 4.f, "ducking on heavy bass: %.2f", duck);
    engine->autoCurveDb(curve);
    CHECK(curve[0] > 2.f, "the 31 Hz boost to duck: %.2f", curve[0]);
    CHECK(std::fabs(engine->designedBandGainDb(0) - (curve[0] - std::min(duck, curve[0]))) < 0.02f,
          "31 Hz band at %.2f (curve %.2f)", engine->designedBandGainDb(0), curve[0]);
    CHECK(std::fabs(engine->designedBandGainDb(2) - curve[2]) < 0.01f, "125 Hz not ducked: %.2f (curve %.2f)",
          engine->designedBandGainDb(2), curve[2]);
    // Ducking needs Auto.
    engine->setAutoMode(false);
    auto again = sine(sr, 2, 50.0, 0.9f, 1.0);
    engine->process(again.data(), SampleFormat::kFloat, sr, again.data());
    CHECK(engine->bassDuckDb() < 0.05f, "ducking without Auto: %.2f", engine->bassDuckDb());
}

/** A "song" of noise, the same in every channel. */
std::vector<float> noiseSong(int sr, int channels, double seconds, unsigned seed) {
    std::mt19937 rng(seed);
    std::normal_distribution<float> white(0.f, 0.05f);
    const size_t frames = static_cast<size_t>(seconds * sr);
    std::vector<float> out(frames * channels);
    for (size_t i = 0; i < frames; ++i) {
        const float v = white(rng);
        for (int c = 0; c < channels; ++c) out[i * channels + c] = v;
    }
    return out;
}

void feed(MobileEqEngine& engine, std::vector<float>& audio, int channels) {
    const size_t total = audio.size() / channels;
    for (size_t at = 0; at < total; at += 4800) {
        const int frames = static_cast<int>(std::min<size_t>(4800, total - at));
        engine.process(audio.data() + at * channels, SampleFormat::kFloat, frames, audio.data() + at * channels);
    }
}

void autoListensToTheSong() {
    // The rule: a song that leans away from a typical record is partly evened out.
    float reference[kNumBands] = {-7.f, 0.f, 0.f, -2.f, -4.f, -7.f, -10.f, -13.f, -16.f, -24.f};
    float out[kNumBands];
    MobileEqEngine::toneCorrectionDb(reference, out);
    for (int b = 0; b < kNumBands; ++b) CHECK(std::fabs(out[b]) < 1e-4f, "reference band %d: %.3f", b, out[b]);
    float boomyAndDull[kNumBands];
    for (int b = 0; b < kNumBands; ++b) boomyAndDull[b] = reference[b] + (b <= 2 ? 6.f : 0.f) + (b >= 7 ? -8.f : 0.f) - 40.f;
    MobileEqEngine::toneCorrectionDb(boomyAndDull, out);
    CHECK(out[1] < -1.5f && out[2] < -1.f, "boomy bass cut: %.2f %.2f", out[1], out[2]);
    CHECK(out[7] > 1.f && out[8] > 2.f, "dull top lifted: %.2f %.2f", out[7], out[8]);
    for (int b = 0; b < kNumBands; ++b) CHECK(out[b] >= -6.f && out[b] <= 5.f, "band %d within limits: %.2f", b, out[b]);
    float missingTop[kNumBands];
    std::copy(reference, reference + kNumBands, missingTop);
    missingTop[9] = -60.f;  // an MP3's cut-off top: absent, not thin
    MobileEqEngine::toneCorrectionDb(missingTop, out);
    CHECK(out[9] <= 0.01f, "absent top not boosted: %.2f", out[9]);

    // In the engine: it hears the song in octave bands and plays the correction for what it heard.
    const int sr = 48000;
    auto engine = engineAt(sr, 2, OutputDevice::kPhoneSpeaker);  // listens before the speaker's high-pass
    float flat[kNumBands] = {};
    engine->setAutoTargetGainsDb(flat);
    engine->setAutoMode(true);
    engine->startTrack(nullptr);
    auto song = noiseSong(sr, 2, 12.0, 7);
    feed(*engine, song, 2);
    float levels[kNumBands], correction[kNumBands], curve[kNumBands], expected[kNumBands];
    const float heard = engine->songLevelsDb(levels);
    engine->listenCorrectionDb(correction);
    engine->autoCurveDb(curve);
    CHECK(heard > 11.f && heard < 12.5f, "heard %.1f s", heard);
    // White noise: about 3 dB more per octave, the 31 Hz band included (the high-pass comes after).
    CHECK(levels[9] - levels[1] > 15.f && levels[9] - levels[1] < 30.f, "white noise rises: %.1f dB", levels[9] - levels[1]);
    MobileEqEngine::toneCorrectionDb(levels, expected);
    for (int b = 0; b < kNumBands; ++b) {
        CHECK(std::fabs(correction[b] - expected[b]) < 0.05f, "band %d correction %.2f for %.2f", b, correction[b], expected[b]);
        CHECK(std::fabs(curve[b] - std::clamp(flat[b] + correction[b], -12.f, 12.f)) < 0.05f, "band %d plays %.2f", b, curve[b]);
    }
    CHECK(correction[9] < -3.f, "too bright a top is taken down: %.2f", correction[9]);

    // A new song starts from nothing, and silence teaches nothing.
    engine->startTrack(nullptr);
    std::vector<float> silence(sr * 2 * 2, 0.f);
    feed(*engine, silence, 2);
    float fresh[kNumBands];
    engine->listenCorrectionDb(fresh);
    for (int b = 0; b < kNumBands; ++b) CHECK(std::fabs(fresh[b]) < 0.01f, "new song band %d: %.2f", b, fresh[b]);
    CHECK(engine->songLevelsDb(levels) == 0.f, "silence heard as music");
    // A song heard before gets its correction at once.
    engine->startTrack(boomyAndDull);
    std::vector<float> moment(4800 * 2, 0.f);
    feed(*engine, moment, 2);
    float known[kNumBands];
    engine->listenCorrectionDb(known);
    CHECK(known[2] < -1.f && known[8] > 2.f, "remembered song corrected at once: %.2f / %.2f", known[2], known[8]);
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
            engine->pollSpectrum(bars, peaks, kMaxBars);
            float gains[kNumBands];
            for (float& g : gains) g = static_cast<float>(std::rand() % 25 - 12);
            engine->setBandGainsDb(gains);
            engine->setOutputDevice(static_cast<OutputDevice>(std::rand() % 4));
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
    engine->setAutoTargetGainsDb(gains);
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
    autoPreampCompensates();
    levelGainAddsToThePreamp();
    neverClips();
    outputStaysAlignedAndComplete();
    slidersDoNotClick();
    deviceHighPass();
    autoMorphsOver400ms();
    autoDucksHeavySubBass();
    autoListensToTheSong();
    convertsEveryFormat();
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
