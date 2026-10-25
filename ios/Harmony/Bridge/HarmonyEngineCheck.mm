// HarmonyEngineCheck.mm: Objective-C++ straight into the shared C++ engines,
// with no JNI in between.

#import "HarmonyEngineCheck.h"

#include <chrono>
#include <cmath>
#include <memory>
#include <random>
#include <string>
#include <vector>

#include "MobileEqEngine.h"
#include "SmartShuffleEngine.h"

using harmony::eq::MobileEqEngine;
using harmony::eq::OutputDevice;
using harmony::eq::SampleFormat;

@interface HarmonyEngineCheckResult ()
- (instancetype)initWithTitle:(NSString *)title passed:(BOOL)passed report:(NSString *)report elapsedMs:(double)elapsedMs;
@end

@implementation HarmonyEngineCheckResult
- (instancetype)initWithTitle:(NSString *)title passed:(BOOL)passed report:(NSString *)report elapsedMs:(double)elapsedMs {
    if ((self = [super init])) {
        _title = [title copy];
        _passed = passed;
        _report = [report copy];
        _elapsedMs = elapsedMs;
    }
    return self;
}
@end

namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr int kChannels = 2;
constexpr int kBlockFrames = 512;  // a typical AVAudioEngine render quantum at 48 kHz is 512 or 1024

const char *deviceName(OutputDevice device) {
    switch (device) {
        case OutputDevice::kPhoneSpeaker: return "Phone speaker";
        case OutputDevice::kWiredHeadphones: return "Wired headphones";
        case OutputDevice::kBluetoothA2dp: return "Bluetooth A2DP";
        case OutputDevice::kUsbDacHiRes: return "USB DAC";
    }
    return "?";
}

double dbfs(float linear) { return 20.0 * std::log10(std::max(1e-9f, linear)); }

/**
 * Two seconds of the worst a library holds: full-scale noise, a full-scale
 * 50 Hz kick-like tone with a 1 kHz and a 9 kHz line on top (well past
 * 0 dBFS together), and a burst of NaN and infinities in the middle, as a
 * corrupt frame from a broken decoder might deliver.
 */
std::vector<float> hostileSignal(int sampleRate) {
    const size_t frames = static_cast<size_t>(sampleRate) * 2;
    std::vector<float> x(frames * kChannels);
    std::mt19937 random(7);
    std::uniform_real_distribution<float> noise(-1.f, 1.f);
    for (size_t i = 0; i < frames; ++i) {
        const double t = static_cast<double>(i) / sampleRate;
        float v;
        if (i < frames / 2) {
            v = noise(random);
        } else {
            v = static_cast<float>(0.9 * std::sin(2 * kPi * 50.0 * t) + 0.5 * std::sin(2 * kPi * 1000.0 * t) +
                                   0.35 * std::sin(2 * kPi * 9000.0 * t));
        }
        x[i * 2] = v;
        x[i * 2 + 1] = -v;
    }
    const size_t burst = frames / 2;
    for (size_t i = 0; i < 64; ++i) {
        x[(burst + i) * 2] = (i % 3 == 0) ? NAN : (i % 3 == 1 ? INFINITY : -INFINITY);
    }
    return x;
}

struct EqRun {
    bool passed = false;
    double peakDb = 0;
    double ceilingDb = 0;
    size_t framesIn = 0;
    size_t framesOut = 0;
    bool finite = true;
    uint32_t sanitized = 0;
    float preampDb = 0;
};

EqRun runEq(OutputDevice device, int sampleRate) {
    EqRun result;
    auto engine = std::make_unique<MobileEqEngine>();  // large state: on the heap, never the stack
    engine->setOutputDeviceType(device);
    if (!engine->configure(sampleRate, kChannels)) return result;
    float gains[harmony::eq::kNumBands];
    for (float &g : gains) g = 12.f;
    engine->setBandGainsDb(gains);
    engine->setAutoMode(true);
    engine->setAnalyzerEnabled(true);

    const std::vector<float> input = hostileSignal(sampleRate);
    const int frames = static_cast<int>(input.size() / kChannels);
    std::vector<float> out(input.size() + static_cast<size_t>(engine->latencyFrames() + kBlockFrames) * kChannels);
    size_t written = 0;
    for (int pos = 0; pos < frames; pos += kBlockFrames) {
        const int n = std::min(kBlockFrames, frames - pos);
        written += static_cast<size_t>(engine->process(input.data() + static_cast<size_t>(pos) * kChannels,
                                                       SampleFormat::kFloat, n, out.data() + written * kChannels));
    }
    written += static_cast<size_t>(engine->drain(out.data() + written * kChannels, frames));

    float peak = 0.f;
    for (size_t i = 0; i < written * kChannels; ++i) {
        const float v = out[i];
        if (!std::isfinite(v)) {
            result.finite = false;
            continue;
        }
        peak = std::max(peak, std::fabs(v));
    }
    float effective[harmony::eq::kNumBands];
    float bars[32];
    float peaks[32];
    result.preampDb = engine->pollDspTelemetry(effective, bars, peaks, 32);
    result.peakDb = dbfs(peak);
    result.ceilingDb = engine->ceilingDb();
    result.framesIn = static_cast<size_t>(frames);
    result.framesOut = written;
    result.sanitized = engine->sanitizedSamples();
    const float ceiling = std::pow(10.f, engine->ceilingDb() / 20.f);
    result.passed = result.finite && peak <= ceiling * 1.000001f && written == static_cast<size_t>(frames);
    return result;
}

double millisSince(std::chrono::steady_clock::time_point start) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
}

}  // namespace

@implementation HarmonyEngineCheck

+ (HarmonyEngineCheckResult *)runEqualizerCheck {
    const auto start = std::chrono::steady_clock::now();
    std::string report;
    bool all = true;
    char line[256];
    for (OutputDevice device : {OutputDevice::kPhoneSpeaker, OutputDevice::kWiredHeadphones,
                                OutputDevice::kBluetoothA2dp, OutputDevice::kUsbDacHiRes}) {
        for (int rate : {44100, 48000, 96000, 192000}) {
            const EqRun r = runEq(device, rate);
            all = all && r.passed;
            std::snprintf(line, sizeof line, "%s %-16s %6.1f kHz  peak %6.2f dBFS (ceiling %.2f)  %zu/%zu frames  healed %u\n",
                          r.passed ? "PASS" : "FAIL", deviceName(device), rate / 1000.0, r.peakDb, r.ceilingDb,
                          r.framesOut, r.framesIn, r.sanitized);
            report += line;
        }
    }
    return [[HarmonyEngineCheckResult alloc] initWithTitle:@"Equalizer (MobileEqEngine)"
                                                    passed:all
                                                    report:[NSString stringWithUTF8String:report.c_str()]
                                                 elapsedMs:millisSince(start)];
}

+ (HarmonyEngineCheckResult *)runShuffleBrainCheck {
    const auto start = std::chrono::steady_clock::now();
    bool passed = false;
    std::string report;
    try {
        report = harmony::shuffle::runSyntheticBrainSimulation(&passed);
    } catch (const std::exception &e) {
        passed = false;
        report = std::string("The simulation threw: ") + e.what();
    }
    NSString *text = [NSString stringWithUTF8String:report.c_str()];
    return [[HarmonyEngineCheckResult alloc] initWithTitle:@"Smart Shuffle (Diagnostic Brain)"
                                                    passed:passed
                                                    report:text ?: @"(the report was not valid UTF-8)"
                                                 elapsedMs:millisSince(start)];
}

@end
