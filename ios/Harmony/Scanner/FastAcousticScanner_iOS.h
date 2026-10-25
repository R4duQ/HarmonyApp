// FastAcousticScanner_iOS.h: the 24-D acoustic vector of a song, from 4.5
// seconds of it.
//
// The iOS counterpart of the Android indexer's decode pass. AudioToolbox's
// ExtAudioFileRef decodes MP3, AAC/M4A/ALAC, FLAC, WAV and AIFF straight to
// mono 32-bit float, and only three 1.5-second windows are read (at 25%, 50%
// and 75% of the song) instead of the whole file: about 50 times less
// decoding, so a library of thousands of songs is indexed in minutes without
// warming the phone. The windows feed Smart Shuffle's own AcousticExtractor
// (shared C++, core/dsp-native), so the vectors are the ones the Android app
// measures.
//
// The decode rate is 44.1 kHz (or the file's own rate when lower), not a
// decimated 11 or 22 kHz: the extractor measures a band only when its centre
// is below 45% of the sample rate, so 22 kHz loses the 16 kHz band and 11 kHz
// the 8 kHz one too, and a vector without its "air" makes bright songs look dark.

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/** The 24 floats of an acoustic vector. */
FOUNDATION_EXPORT const NSInteger HarmonyAcousticDims;

@interface HarmonyAcousticScanResult : NSObject
/** True when a vector was measured. */
@property (nonatomic, readonly) BOOL success;
/** 24 little-endian float32 values (96 bytes), each in [0, 1]; nil on failure. */
@property (nonatomic, readonly, nullable) NSData *vector;
/** The song's length, seconds (0 when the file does not say). */
@property (nonatomic, readonly) double durationSeconds;
/** How much audio was actually decoded, seconds. */
@property (nonatomic, readonly) double decodedSeconds;
/** The file's own sample rate. */
@property (nonatomic, readonly) double fileSampleRate;
/** Why it failed (unreadable file, too little non-silent audio, cancelled). */
@property (nonatomic, readonly, copy, nullable) NSString *failureReason;
/** Wall time of the scan, milliseconds. */
@property (nonatomic, readonly) double elapsedMs;
@end

@interface HarmonyFastAcousticScanner : NSObject

/**
 * Measures [url] synchronously. Call it on a background queue. Songs under
 * 8 seconds are decoded whole; when the three windows hold less than two
 * seconds of non-silent audio (a quiet classical passage, a long fade), four
 * more windows (12.5, 37.5, 62.5 and 87.5%) are read before giving up.
 * [isCancelled] is polled between decode chunks; return YES to stop.
 */
+ (HarmonyAcousticScanResult *)scanFileAtURL:(NSURL *)url
                                  isCancelled:(BOOL (^_Nullable)(void))isCancelled;

@end

NS_ASSUME_NONNULL_END
