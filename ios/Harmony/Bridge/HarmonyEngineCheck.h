// HarmonyEngineCheck.h: runs the shared C++ engines on the iPhone and reports.
//
// The first thing the iOS app does is prove, on the device itself, that the
// same C++ core as on Android works under Apple's toolchain on ARM64: the
// equalizer never clips and heals from corrupt input on every output, and the
// Smart Shuffle Diagnostic Brain passes its own 100-track simulation.

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/** One check's verdict and its human-readable report. */
@interface HarmonyEngineCheckResult : NSObject
@property (nonatomic, readonly) BOOL passed;
@property (nonatomic, readonly, copy) NSString *title;
@property (nonatomic, readonly, copy) NSString *report;
/** How long the check took, in milliseconds. */
@property (nonatomic, readonly) double elapsedMs;
@end

@interface HarmonyEngineCheck : NSObject

/**
 * MobileEqEngine: every output profile (speaker, wired, Bluetooth, USB DAC) at
 * 44.1, 48, 96 and 192 kHz, all ten bands at +12 dB and Auto on, fed
 * full-scale music-like signal with NaN and Inf bursts. Passes when every
 * sample comes out finite, under that output's ceiling, and none is lost.
 * Slow (about a second); call it off the main thread.
 */
+ (HarmonyEngineCheckResult *)runEqualizerCheck;

/** SmartShuffleEngine's built-in self-test (runSyntheticBrainSimulation). Call it off the main thread. */
+ (HarmonyEngineCheckResult *)runShuffleBrainCheck;

@end

NS_ASSUME_NONNULL_END
