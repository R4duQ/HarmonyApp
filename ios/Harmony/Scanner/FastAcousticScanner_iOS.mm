// FastAcousticScanner_iOS.mm: three 1.5 s windows through ExtAudioFileRef
// into the shared AcousticExtractor.

#import "FastAcousticScanner_iOS.h"

#include <chrono>
#include <cmath>
#include <string>
#include <vector>

#include "HarmonyAudioFile.h"
#include "SmartShuffleEngine.h"

const NSInteger HarmonyAcousticDims = harmony::shuffle::kDims;

@interface HarmonyAcousticScanResult ()
- (instancetype)initWithVector:(nullable NSData *)vector
                      duration:(double)duration
                       decoded:(double)decoded
                    sampleRate:(double)sampleRate
                       failure:(nullable NSString *)failure
                     elapsedMs:(double)elapsedMs;
@end

@implementation HarmonyAcousticScanResult
- (instancetype)initWithVector:(NSData *)vector
                      duration:(double)duration
                       decoded:(double)decoded
                    sampleRate:(double)sampleRate
                       failure:(NSString *)failure
                     elapsedMs:(double)elapsedMs {
    if ((self = [super init])) {
        _vector = [vector copy];
        _success = vector != nil;
        _durationSeconds = duration;
        _decodedSeconds = decoded;
        _fileSampleRate = sampleRate;
        _failureReason = [failure copy];
        _elapsedMs = elapsedMs;
    }
    return self;
}
@end

namespace {

constexpr double kWindowSeconds = 1.5;
/** Under this a song is decoded whole: three windows would overlap anyway. */
constexpr double kWholeSongBelowSeconds = 8.0;
/** The decode rate, unless the file's own is lower (see the header). */
constexpr double kAnalysisRate = 44100.0;
constexpr int kChunkFrames = 4096;
/** A file whose length is unknown (some VBR MP3s without a Xing header): read this much from the start. */
constexpr double kUnknownLengthCapSeconds = 30.0;

const double kPrimaryWindows[] = {0.25, 0.50, 0.75};
const double kExtraWindows[] = {0.125, 0.375, 0.625, 0.875};

struct ScanState {
    harmony::ios::AudioFile file;
    std::vector<float> chunk = std::vector<float>(kChunkFrames);
    double decodedFrames = 0;
    bool cancelled = false;
};

/** Decodes up to [seconds] from the current position into [extractor]. Returns false on a read error. */
bool decodeInto(ScanState &state, harmony::shuffle::AcousticExtractor &extractor, double seconds,
                BOOL (^isCancelled)(void)) {
    long long remaining = static_cast<long long>(std::llround(seconds * state.file.clientRate()));
    while (remaining > 0) {
        if (isCancelled && isCancelled()) {
            state.cancelled = true;
            return true;
        }
        const int want = static_cast<int>(std::min<long long>(remaining, kChunkFrames));
        const int got = state.file.read(state.chunk.data(), want);
        if (got < 0) return false;
        if (got == 0) break;  // the end of the file
        extractor.process(state.chunk.data(), got);
        state.decodedFrames += got;
        remaining -= got;
    }
    return true;
}

double millisSince(std::chrono::steady_clock::time_point start) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
}

}  // namespace

@implementation HarmonyFastAcousticScanner

+ (HarmonyAcousticScanResult *)scanFileAtURL:(NSURL *)url isCancelled:(BOOL (^)(void))isCancelled {
    const auto start = std::chrono::steady_clock::now();
    auto fail = [&](NSString *reason, double duration, double decoded, double rate) {
        return [[HarmonyAcousticScanResult alloc] initWithVector:nil
                                                        duration:duration
                                                         decoded:decoded
                                                      sampleRate:rate
                                                         failure:reason
                                                       elapsedMs:millisSince(start)];
    };

    @autoreleasepool {
        // Files the user picked from outside the sandbox: the security scope
        // is held by the library's bookmark, but taking it here too is harmless
        // and makes the scanner usable on any URL.
        const BOOL scoped = [url startAccessingSecurityScopedResource];
        ScanState state;
        std::string error;
        // Probe the file's rate first: decode at 44.1 kHz, or lower when the file is.
        if (!state.file.open((__bridge CFURLRef)url, 0, 1, &error)) {
            if (scoped) [url stopAccessingSecurityScopedResource];
            return fail([NSString stringWithUTF8String:error.c_str()], 0, 0, 0);
        }
        const double fileRate = state.file.fileRate();
        const double rate = std::min(kAnalysisRate, fileRate);
        if (rate != fileRate && !state.file.open((__bridge CFURLRef)url, rate, 1, &error)) {
            if (scoped) [url stopAccessingSecurityScopedResource];
            return fail([NSString stringWithUTF8String:error.c_str()], 0, 0, fileRate);
        }
        const double duration = state.file.durationSeconds();
        harmony::shuffle::AcousticExtractor extractor(static_cast<int>(std::lround(rate)));
        if (!extractor.valid()) {
            if (scoped) [url stopAccessingSecurityScopedResource];
            return fail([NSString stringWithFormat:@"Unsupported sample rate %.0f Hz", rate], duration, 0, fileRate);
        }

        bool ok = true;
        if (duration <= 0) {
            ok = decodeInto(state, extractor, kUnknownLengthCapSeconds, isCancelled);
        } else if (duration < kWholeSongBelowSeconds) {
            ok = decodeInto(state, extractor, duration + 1.0, isCancelled);
        } else {
            for (double at : kPrimaryWindows) {
                if (!ok || state.cancelled) break;
                ok = state.file.seekSeconds(duration * at - kWindowSeconds / 2) &&
                     decodeInto(state, extractor, kWindowSeconds, isCancelled);
            }
            // Mostly silence in those 4.5 s: look in four more places.
            if (ok && !state.cancelled && extractor.activeSeconds() < 2.0) {
                for (double at : kExtraWindows) {
                    if (!ok || state.cancelled) break;
                    ok = state.file.seekSeconds(duration * at - kWindowSeconds / 2) &&
                         decodeInto(state, extractor, kWindowSeconds, isCancelled);
                }
            }
        }
        state.file.close();
        if (scoped) [url stopAccessingSecurityScopedResource];

        const double decoded = state.decodedFrames / rate;
        if (state.cancelled) return fail(@"Cancelled", duration, decoded, fileRate);
        if (!ok) return fail(@"The file could not be decoded", duration, decoded, fileRate);

        float vector[harmony::shuffle::kDims];
        if (!extractor.finish(vector)) {
            return fail(@"Too little non-silent audio to measure", duration, decoded, fileRate);
        }
        for (float v : vector) {
            if (!std::isfinite(v)) return fail(@"The measurement was not finite", duration, decoded, fileRate);
        }
        NSData *data = [NSData dataWithBytes:vector length:sizeof vector];
        return [[HarmonyAcousticScanResult alloc] initWithVector:data
                                                        duration:duration
                                                         decoded:decoded
                                                      sampleRate:fileRate
                                                         failure:nil
                                                       elapsedMs:millisSince(start)];
    }
}

@end
