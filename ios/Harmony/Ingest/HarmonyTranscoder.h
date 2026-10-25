// HarmonyTranscoder.h: on-device audio conversion and tagging with Apple's
// own codecs (AudioToolbox's ExtAudioFileRef), no FFmpeg process needed.
//
//   decode: anything Core Audio reads (MP3, AAC/M4A, ALAC, FLAC, WAV, AIFF,
//           CAF, Opus in CAF)
//   encode: FLAC (16/24-bit, the source's depth), ALAC (.m4a), AAC 256 kbps
//           (.m4a, on the hardware encoder when there is one)
//
// The same file's metadata for FLAC and MP3 is written by HarmonyTagWriter
// (C++); M4A tags go through AVFoundation on the Swift side.

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

typedef NS_ENUM(NSInteger, HarmonyTranscodeTarget) {
    HarmonyTranscodeTargetFlac = 0,
    HarmonyTranscodeTargetAlac = 1,
    HarmonyTranscodeTargetAac = 2,
};

/** What a file holds, read from its own headers. */
@interface HarmonySourceInfo : NSObject
@property (nonatomic, readonly) double sampleRate;
@property (nonatomic, readonly) NSInteger channels;
/** The source bit depth (16, 24, 32) for PCM, FLAC and ALAC; 0 for lossy codecs. */
@property (nonatomic, readonly) NSInteger bitDepth;
@property (nonatomic, readonly) double durationSeconds;
/** "FLAC", "ALAC", "PCM", "AAC", "MP3", "OPUS" or the four-character code. */
@property (nonatomic, readonly, copy) NSString *codec;
@property (nonatomic, readonly) BOOL lossless;
/** Sniffed from the bytes: "flac", "mp3", "m4a", "wav", "aiff", "ogg", "caf" or "" when not audio. */
@property (nonatomic, readonly, copy) NSString *container;
@end

@interface HarmonyAudioTags : NSObject
@property (nonatomic, copy, nullable) NSString *title;
@property (nonatomic, copy, nullable) NSString *artist;
@property (nonatomic, copy, nullable) NSString *album;
@property (nonatomic, copy, nullable) NSString *albumArtist;
@property (nonatomic, copy, nullable) NSString *trackNumber;
@property (nonatomic, copy, nullable) NSString *discNumber;
@property (nonatomic, copy, nullable) NSString *date;
@property (nonatomic, copy, nullable) NSString *genre;
@property (nonatomic, copy, nullable) NSString *isrc;
@property (nonatomic, copy, nullable) NSString *comment;
/** JPEG or PNG. */
@property (nonatomic, copy, nullable) NSData *cover;
@end

@interface HarmonyTranscoder : NSObject

/** Reads [url]'s format; nil when Core Audio cannot open it. */
+ (nullable HarmonySourceInfo *)inspectFileAtURL:(NSURL *)url;

/** "flac", "mp3", "m4a", "wav", "aiff", "ogg", "caf" or "" (not audio), from the first bytes. */
+ (NSString *)sniffContainerAtPath:(NSString *)path;

/**
 * Converts [input] to [target] at [output] (overwritten), keeping the
 * sample rate, channels and, for FLAC and ALAC, the bit depth (24-bit
 * stays 24-bit; lossy sources are written as 16-bit). [progress] gets 0..1;
 * [isCancelled] is polled between chunks.
 */
+ (BOOL)transcodeFileAtURL:(NSURL *)input
                     toURL:(NSURL *)output
                    target:(HarmonyTranscodeTarget)target
                  progress:(void (^_Nullable)(double fraction))progress
               isCancelled:(BOOL (^_Nullable)(void))isCancelled
                     error:(NSError *_Nullable *_Nullable)error;

/** FLAC (Vorbis comments + cover) and MP3 (ID3v2.3) in place. Other containers: NO with an error. */
+ (BOOL)writeTags:(HarmonyAudioTags *)tags toFileAtPath:(NSString *)path error:(NSError *_Nullable *_Nullable)error;

/** nil when the file is the container [expectedExtension] says (and [expectedSize] bytes, when non-zero), else why not. */
+ (nullable NSString *)verifyFileAtPath:(NSString *)path
                      expectedExtension:(NSString *)expectedExtension
                           expectedSize:(uint64_t)expectedSize;

@end

NS_ASSUME_NONNULL_END
