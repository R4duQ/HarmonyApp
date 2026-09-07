// harmony_dsp.h — Harmony's native audio analysis core.
//
// Design contract:
//  - Streaming API: create -> process(chunks of mono float PCM) -> finish.
//    The Kotlin decoder feeds PCM as it comes off MediaCodec; the analyzer
//    never holds the whole song in memory (a 10-min FLAC decoded is ~100 MB;
//    we hold two FFT frames and small per-frame accumulators instead).
//  - Output is a flat float array with a FIXED layout, mirrored exactly by
//    FeatureLayout.kt in :domain:analysis. Any change here MUST bump
//    ANALYSIS_VERSION there, which forces re-analysis of the library.
//  - No allocations in the hot path after warm-up; everything is
//    pre-allocated at create() from the sample rate.
//
// Feature layout (index -> meaning), total HARMONY_FEATURE_COUNT floats:
//   0  rms                (linear, 0..1)
//   1  peak               (linear, 0..1)
//   2  lufs               (integrated, ITU-R BS.1770 K-weighted approx, dB)
//   3  dynamic_range      (dB between 95th and 10th percentile frame loudness)
//   4  bass_energy        (fraction of spectral energy < 250 Hz)
//   5  mid_energy         (fraction 250 Hz..4 kHz)
//   6  treble_energy      (fraction > 4 kHz)
//   7  spectral_centroid  (Hz, mean)
//   8  spectral_rolloff   (Hz, mean, 85%)
//   9  spectral_bandwidth (Hz, mean)
//   10 spectral_flatness  (0..1, mean)
//   11 spectral_contrast  (dB, mean peak-to-valley across bands)
//   12 bpm                (60..200, 0 if undetected)
//   13 beat_confidence    (0..1)
//   14 zero_crossing_rate (mean, 0..1)
//   15 key_index          (0=C..11=B, -1 if unknown)
//   16 key_is_major       (1 major / 0 minor)
//   17..28  chroma mean (12, L1-normalized)
//   29..41  mfcc mean   (13)
//   42..54  mfcc stddev (13)

#ifndef HARMONY_DSP_H
#define HARMONY_DSP_H

#include <cstdint>

#define HARMONY_FEATURE_COUNT 55

#ifdef __cplusplus
extern "C" {
#endif

typedef struct HarmonyAnalyzer HarmonyAnalyzer;

// sample_rate: source rate in Hz (no resampling; filterbanks adapt).
// Returns nullptr on invalid arguments.
HarmonyAnalyzer* harmony_create(int32_t sample_rate);

// samples: mono float PCM in [-1, 1]. Safe to call with any count >= 0.
void harmony_process(HarmonyAnalyzer* h, const float* samples, int32_t count);

// Finalizes analysis and writes HARMONY_FEATURE_COUNT floats into out.
// Returns HARMONY_FEATURE_COUNT on success, -1 on error.
int32_t harmony_finish(HarmonyAnalyzer* h, float* out, int32_t out_capacity);

void harmony_destroy(HarmonyAnalyzer* h);

#ifdef __cplusplus
}
#endif

#endif // HARMONY_DSP_H
