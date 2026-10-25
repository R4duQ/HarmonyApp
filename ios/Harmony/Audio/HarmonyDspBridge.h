// HarmonyDspBridge.h: Harmony's iOS player core, AVAudioEngine to the shared
// C++ equalizer with no JNI in between.
//
//   decoder thread    ExtAudioFileRef -> stereo float at the output's rate,
//                     gapless into the next song, and the passive 24-D vector
//          |
//   lock-free ring    about 5 seconds of audio
//          |
//   AVAudioSourceNode render block -> MobileEqEngine::process() (all four
//                     stages: high-pass + live preamp, Auto calibration, the
//                     10-band bank, saturator + lookahead limiter) -> output
//
// The render block never locks, allocates or calls Objective-C. Controls go
// to the engine's atomics; telemetry is polled at 60 fps from the UI.

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/** The output profile; the same values as MobileEqEngine's OutputDevice. */
typedef NS_ENUM(NSInteger, HarmonyOutputDevice) {
    HarmonyOutputDevicePhoneSpeaker = 0,
    HarmonyOutputDeviceWiredHeadphones = 1,
    HarmonyOutputDeviceBluetoothA2dp = 2,
    HarmonyOutputDeviceUsbDacHiRes = 3,
};

/** The equalizer's ten bands. */
FOUNDATION_EXPORT const NSInteger HarmonyEqBandCount;
/** The most spectrum bars pollTelemetry fills. */
FOUNDATION_EXPORT const NSInteger HarmonyMaxSpectrumBars;

@class HarmonyDspBridge;

/** Everything is delivered on the main thread. */
@protocol HarmonyDspBridgeDelegate <NSObject>
/** The song with [token] started playing: a new load, or the gapless move into the next song. */
- (void)dspBridge:(HarmonyDspBridge *)bridge didStartTrack:(int64_t)token;
/** The last song ended and nothing was queued after it. */
- (void)dspBridgeDidFinishQueue:(HarmonyDspBridge *)bridge;
/** [token] could not be opened or decoded. */
- (void)dspBridge:(HarmonyDspBridge *)bridge didFailTrack:(int64_t)token reason:(NSString *)reason;
/** A 24-D acoustic vector measured for free from the decoded audio, once [token] was heard past 40%. */
- (void)dspBridge:(HarmonyDspBridge *)bridge didMeasureVector:(NSData *)vector forTrack:(int64_t)token;
/** The audio hardware changed (route or rate) and the graph was rebuilt at [sampleRate]. */
- (void)dspBridge:(HarmonyDspBridge *)bridge didRebuildAtSampleRate:(double)sampleRate;
@end

/** What the playing file is: for the "HI-RES USB DAC • 96kHz" badge and Now Playing. */
@interface HarmonyTrackFormat : NSObject
@property (nonatomic, readonly) double sampleRate;
@property (nonatomic, readonly) NSInteger bitsPerChannel;  // 0 for lossy codecs
@property (nonatomic, readonly) NSInteger channels;
/** "FLAC", "ALAC", "AAC", "MP3", "WAV", "AIFF" or the four-character code. */
@property (nonatomic, readonly, copy) NSString *codec;
@property (nonatomic, readonly) BOOL lossless;
@end

@interface HarmonyDspBridge : NSObject

@property (nonatomic, weak, nullable) id<HarmonyDspBridgeDelegate> delegate;

/**
 * Builds AVAudioEngine at the hardware's current rate. Activate the
 * AVAudioSession (.playback) first. Returns NO with [error] when the engine
 * cannot start.
 */
- (BOOL)startEngineWithError:(NSError *_Nullable *_Nullable)error;
/** Stops the engine and releases the graph (the app is closing). */
- (void)stopEngine;

/** Replaces whatever plays with [url], from [startSeconds]. Clears the queued next song. */
- (BOOL)loadURL:(NSURL *)url token:(int64_t)token startSeconds:(double)startSeconds error:(NSError *_Nullable *_Nullable)error
    NS_SWIFT_NAME(load(url:token:startSeconds:));
/** The song to continue into without a gap when this one ends; nil to end after it. */
- (void)setNextURL:(nullable NSURL *)url token:(int64_t)token NS_SWIFT_NAME(setNext(url:token:));

- (void)play;
- (void)pause;
@property (nonatomic, readonly, getter=isPlaying) BOOL playing;
- (void)seekToSeconds:(double)seconds NS_SWIFT_NAME(seek(toSeconds:));

/** Where playback is in the current song, seconds (what is heard, not what is decoded). */
@property (nonatomic, readonly) double positionSeconds;
@property (nonatomic, readonly) double durationSeconds;
/** The token of the song being heard; -1 before the first load. */
@property (nonatomic, readonly) int64_t currentToken;
/** The hardware rate the engine runs at. */
@property (nonatomic, readonly) double outputSampleRate;
@property (nonatomic, readonly, nullable) HarmonyTrackFormat *currentFormat;

// ---- Equalizer (any thread; lock-free) -----------------------------------------

- (void)setBandGain:(float)gainDb forBand:(NSInteger)band;
- (void)setEqEnabled:(BOOL)enabled;
/** Live spectral auto-calibration (the Auto mode). */
- (void)setAutoMode:(BOOL)enabled;
/** -24..+12 dB on top of the live automatic headroom. */
- (void)setUserPreampDb:(float)preampDb;
/** ReplayGain levelling, -30..+15 dB. */
- (void)setLevelGainDb:(float)gainDb;
- (void)setOutputDevice:(HarmonyOutputDevice)device;
/** Feed the 60 fps analyzer only while a spectrum is on screen. */
- (void)setAnalyzerEnabled:(BOOL)enabled;
- (void)setSpectrumBarCount:(NSInteger)bars;

// ---- Telemetry (one polling thread, the main thread at 60 fps) --------------------

/**
 * One frame for the UI: effective gains (sliders + live Auto offsets, ducking
 * included; HarmonyEqBandCount values), spectrum bars and peak-hold dots (0..1,
 * at most [capacity]). Any pointer may be NULL. Returns the live automatic preamp, dB.
 */
- (float)pollTelemetryWithGains:(float *_Nullable)gains
                           bars:(float *_Nullable)bars
                          peaks:(float *_Nullable)peaks
                       capacity:(NSInteger)capacity;
/** Auto's live offset per band, dB (±4.5). */
- (void)copyLiveAutoOffsets:(float *)offsets;
@property (nonatomic, readonly) float bassDuckDb;
@property (nonatomic, readonly) float harshDuckDb;
@property (nonatomic, readonly) BOOL tonalFrozen;
@property (nonatomic, readonly) float limiterCeilingDb;
@property (nonatomic, readonly) uint32_t healCount;
/** Frames the output had to fill with silence because the decoder fell behind (diagnostics). */
@property (nonatomic, readonly) uint64_t underrunFrames;

@end

NS_ASSUME_NONNULL_END
