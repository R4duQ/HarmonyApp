// HarmonyDspBridge.mm: AVAudioEngine + ExtAudioFileRef around HarmonyPlayerCore.

#import "HarmonyDspBridge.h"

#import <AVFoundation/AVFoundation.h>

#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <string>

#include "HarmonyAudioFile.h"
#include "HarmonyPlayerCore.h"

using harmony::ios::CoreEvent;
using harmony::ios::PcmSource;
using harmony::ios::PlayerCore;

const NSInteger HarmonyEqBandCount = harmony::eq::kNumBands;
const NSInteger HarmonyMaxSpectrumBars = harmony::eq::kMaxBars;

static NSString *const HarmonyDspErrorDomain = @"HarmonyDspBridge";

// ---------------------------------------------------------------------------
// HarmonyTrackFormat
// ---------------------------------------------------------------------------

@interface HarmonyTrackFormat ()
- (instancetype)initWithRate:(double)rate bits:(NSInteger)bits channels:(NSInteger)channels formatId:(AudioFormatID)formatId;
@end

@implementation HarmonyTrackFormat
- (instancetype)initWithRate:(double)rate bits:(NSInteger)bits channels:(NSInteger)channels formatId:(AudioFormatID)formatId {
    if ((self = [super init])) {
        _sampleRate = rate;
        _bitsPerChannel = bits;
        _channels = channels;
        switch (formatId) {
            case kAudioFormatFLAC: _codec = @"FLAC"; _lossless = YES; break;
            case kAudioFormatAppleLossless: _codec = @"ALAC"; _lossless = YES; break;
            case kAudioFormatLinearPCM: _codec = @"PCM"; _lossless = YES; break;
            case kAudioFormatMPEG4AAC:
            case kAudioFormatMPEG4AAC_HE:
            case kAudioFormatMPEG4AAC_HE_V2: _codec = @"AAC"; break;
            case kAudioFormatMPEGLayer3: _codec = @"MP3"; break;
            case kAudioFormatOpus: _codec = @"OPUS"; break;
            default: {
                const UInt32 be = CFSwapInt32HostToBig(formatId);
                char code[5] = {};
                memcpy(code, &be, 4);
                NSString *name = [NSString stringWithUTF8String:code] ?: @"?";
                _codec = [name stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceCharacterSet];
            }
        }
    }
    return self;
}
@end

// ---------------------------------------------------------------------------
// A song file as a PcmSource
// ---------------------------------------------------------------------------

namespace {

struct FormatInfo {
    double rate = 0;
    int bits = 0;
    int channels = 0;
    AudioFormatID formatId = 0;
};

class FileSource final : public PcmSource {
public:
    FileSource(NSURL *url, bool scoped) : url_(url), scoped_(scoped) {}
    ~FileSource() override {
        file_.close();
        if (scoped_) [url_ stopAccessingSecurityScopedResource];
    }
    bool open(double rate, std::string *error) { return file_.open((__bridge CFURLRef)url_, rate, 2, error); }
    int read(float *out, int frames) override { return file_.read(out, frames); }
    bool seekSeconds(double seconds) override { return file_.seekSeconds(seconds); }
    double durationSeconds() const override { return file_.durationSeconds(); }
    FormatInfo format() const {
        return {file_.fileRate(), file_.sourceBitDepth(), file_.fileChannels(), file_.formatId()};
    }

private:
    NSURL *url_;  // strong (ARC in Objective-C++)
    bool scoped_;
    harmony::ios::AudioFile file_;
};

/** What the bridge shares with the core's callbacks (decoder and control threads). */
struct SharedState {
    std::mutex lock;
    std::map<int64_t, NSURL *> urls;
    std::map<int64_t, FormatInfo> formats;
    std::set<int64_t> vectorsReceived;
};

}  // namespace

// ---------------------------------------------------------------------------
// HarmonyDspBridge
// ---------------------------------------------------------------------------

@implementation HarmonyDspBridge {
    std::shared_ptr<SharedState> _shared;
    std::unique_ptr<PlayerCore> _core;
    AVAudioEngine *_engine;
    AVAudioSourceNode *_source;
    dispatch_source_t _timer;
    uint32_t _seenTrackSeq;
    uint32_t _seenFinishSeq;
    BOOL _playing;
    BOOL _rebuilding;
    /** After a rebuild the core reloads the same song; that start is not news. */
    BOOL _swallowStart;
    int64_t _swallowToken;
    id _configObserver;
}

- (instancetype)init {
    if ((self = [super init])) {
        _shared = std::make_shared<SharedState>();
        std::weak_ptr<SharedState> weakShared = _shared;
        __weak HarmonyDspBridge *weakSelf = self;

        auto factory = [weakShared](int64_t token, double rate, std::string *error) -> std::unique_ptr<PcmSource> {
            auto shared = weakShared.lock();
            if (!shared) return nullptr;
            NSURL *url = nil;
            {
                std::lock_guard<std::mutex> guard(shared->lock);
                auto it = shared->urls.find(token);
                if (it != shared->urls.end()) url = it->second;
            }
            if (url == nil) {
                if (error) *error = "Unknown song";
                return nullptr;
            }
            const bool scoped = [url startAccessingSecurityScopedResource];
            auto source = std::make_unique<FileSource>(url, scoped);
            if (!source->open(rate, error)) return nullptr;
            std::lock_guard<std::mutex> guard(shared->lock);
            shared->formats[token] = source->format();
            return source;
        };

        auto sink = [weakSelf, weakShared](CoreEvent event) {
            // Copy what the main thread needs; the event dies with this call.
            const CoreEvent::Kind kind = event.kind;
            const int64_t token = event.token;
            NSString *message = [NSString stringWithUTF8String:event.message.c_str()] ?: @"";
            NSData *vector = kind == CoreEvent::Kind::VectorMeasured
                                 ? [NSData dataWithBytes:event.vector.data() length:event.vector.size() * sizeof(float)]
                                 : nil;
            dispatch_async(dispatch_get_main_queue(), ^{
                HarmonyDspBridge *bridge = weakSelf;
                id<HarmonyDspBridgeDelegate> delegate = bridge.delegate;
                if (bridge == nil) return;
                if (kind == CoreEvent::Kind::TrackFailed) {
                    [delegate dspBridge:bridge didFailTrack:token reason:message];
                } else if (vector != nil) {
                    if (auto shared = weakShared.lock()) {
                        std::lock_guard<std::mutex> guard(shared->lock);
                        shared->vectorsReceived.insert(token);
                    }
                    [delegate dspBridge:bridge didMeasureVector:vector forTrack:token];
                }
            });
        };

        _core = std::make_unique<PlayerCore>(factory, sink);
    }
    return self;
}

- (void)dealloc {
    [self stopEngine];
}

// ---- The engine -------------------------------------------------------------------

- (BOOL)buildGraphWithError:(NSError **)error {
    _engine = [[AVAudioEngine alloc] init];
    double rate = [_engine.outputNode outputFormatForBus:0].sampleRate;
    if (rate <= 0) rate = AVAudioSession.sharedInstance.sampleRate;
    if (rate <= 0) rate = 48000;
    if (!_core->configure(rate)) {
        if (error) *error = [NSError errorWithDomain:HarmonyDspErrorDomain code:1
                                            userInfo:@{NSLocalizedDescriptionKey : [NSString stringWithFormat:@"Unsupported output rate %.0f Hz", rate]}];
        return NO;
    }
    AVAudioFormat *format = [[AVAudioFormat alloc] initStandardFormatWithSampleRate:rate channels:2];
    PlayerCore *core = _core.get();  // outlives the node: the graph is torn down before the core
    _source = [[AVAudioSourceNode alloc] initWithFormat:format
                                            renderBlock:^OSStatus(BOOL *isSilence, const AudioTimeStamp *timestamp,
                                                                  AVAudioFrameCount frameCount, AudioBufferList *output) {
        (void)timestamp;
        if (output->mNumberBuffers < 2) {
            for (UInt32 b = 0; b < output->mNumberBuffers; ++b) memset(output->mBuffers[b].mData, 0, output->mBuffers[b].mDataByteSize);
            *isSilence = YES;
            return noErr;
        }
        auto *left = static_cast<float *>(output->mBuffers[0].mData);
        auto *right = static_cast<float *>(output->mBuffers[1].mData);
        const int heard = core->render(left, right, static_cast<int>(frameCount));
        if (heard == 0) *isSilence = YES;
        return noErr;
    }];
    [_engine attachNode:_source];
    [_engine connect:_source to:_engine.mainMixerNode format:format];
    [_engine prepare];

    __weak HarmonyDspBridge *weakSelf = self;
    _configObserver = [NSNotificationCenter.defaultCenter
        addObserverForName:AVAudioEngineConfigurationChangeNotification
                    object:_engine
                     queue:NSOperationQueue.mainQueue
                usingBlock:^(NSNotification *note) {
                    (void)note;
                    [weakSelf rebuildAtCurrentPosition];
                }];
    return YES;
}

- (void)tearDownGraph {
    if (_configObserver) {
        [NSNotificationCenter.defaultCenter removeObserver:_configObserver];
        _configObserver = nil;
    }
    if (_engine) {
        [_engine stop];
        if (_source) [_engine detachNode:_source];
    }
    _source = nil;
    _engine = nil;
}

- (BOOL)startEngineWithError:(NSError **)error {
    if (_engine) return YES;
    if (![self buildGraphWithError:error]) return NO;
    _core->startDecoderThread();
    [self startTimer];
    return YES;
}

- (void)stopEngine {
    [self stopTimer];
    _core->setPlaying(false);
    _playing = NO;
    [self tearDownGraph];
    _core->stopDecoderThread();
}

/** The hardware changed (headphones out, a USB DAC at a new rate): rebuild where we were. */
- (void)rebuildAtCurrentPosition {
    if (_rebuilding || _engine == nil) return;
    _rebuilding = YES;
    const BOOL wasPlaying = _playing;
    const int64_t token = _core->currentToken();
    const double position = _core->positionSeconds();
    const int64_t next = _core->queuedNextToken();
    _core->setPlaying(false);
    [self tearDownGraph];
    NSError *error = nil;
    if ([self buildGraphWithError:&error]) {
        std::string message;
        if (token >= 0 && _core->load(token, position, &message)) {
            if (next >= 0) _core->setNext(next, &message);
        }
        // The core will count the reload as a song start; it is the same song.
        _swallowStart = token >= 0;
        _swallowToken = token;
        if (wasPlaying) [self play];
        [self.delegate dspBridge:self didRebuildAtSampleRate:_core->sampleRate()];
    }
    _rebuilding = NO;
}

// ---- Transport ------------------------------------------------------------------------

- (void)rememberURL:(NSURL *)url token:(int64_t)token keep:(int64_t)keep {
    std::lock_guard<std::mutex> guard(_shared->lock);
    for (auto it = _shared->urls.begin(); it != _shared->urls.end();) {
        if (it->first != keep && it->first != token && it->first != _core->currentToken()) {
            _shared->formats.erase(it->first);
            it = _shared->urls.erase(it);
        } else {
            ++it;
        }
    }
    _shared->urls[token] = url;
}

- (BOOL)loadURL:(NSURL *)url token:(int64_t)token startSeconds:(double)startSeconds error:(NSError **)error {
    _swallowStart = NO;
    [self rememberURL:url token:token keep:-1];
    std::string message;
    if (!_core->load(token, startSeconds, &message)) {
        if (error) *error = [NSError errorWithDomain:HarmonyDspErrorDomain code:2
                                            userInfo:@{NSLocalizedDescriptionKey : [NSString stringWithUTF8String:message.c_str()] ?: @"Could not open the song"}];
        return NO;
    }
    return YES;
}

- (void)setNextURL:(NSURL *)url token:(int64_t)token {
    std::string message;
    if (url == nil) {
        _core->setNext(-1, &message);
        return;
    }
    [self rememberURL:url token:token keep:token];
    switch (_core->setNext(token, &message)) {
        case PlayerCore::NextResult::Queued: break;
        case PlayerCore::NextResult::NeedsLoad:
            // The last song had already ended: start the new one now.
            if (_core->load(token, 0, &message) && _playing) [self play];
            break;
        case PlayerCore::NextResult::Failed:
            [self.delegate dspBridge:self didFailTrack:token reason:[NSString stringWithUTF8String:message.c_str()] ?: @""];
            break;
    }
}

- (void)play {
    if (_engine == nil && ![self startEngineWithError:nil]) return;
    if (!_engine.isRunning) {
        NSError *error = nil;
        if (![_engine startAndReturnError:&error]) {
            NSLog(@"Harmony: the audio engine did not start: %@", error);
            return;
        }
    }
    _core->setPlaying(true);
    _playing = YES;
}

- (void)pause {
    _core->setPlaying(false);
    _playing = NO;
    [_engine pause];
}

- (BOOL)isPlaying { return _playing; }

- (void)seekToSeconds:(double)seconds { _core->seek(seconds); }

- (double)positionSeconds { return _core->positionSeconds(); }
- (double)durationSeconds { return _core->durationSeconds(); }
- (int64_t)currentToken { return _core->currentToken(); }
- (double)outputSampleRate { return _core->sampleRate(); }

- (HarmonyTrackFormat *)currentFormat {
    std::lock_guard<std::mutex> guard(_shared->lock);
    auto it = _shared->formats.find(_core->currentToken());
    if (it == _shared->formats.end()) return nil;
    const FormatInfo &f = it->second;
    return [[HarmonyTrackFormat alloc] initWithRate:f.rate bits:f.bits channels:f.channels formatId:f.formatId];
}

// ---- Events (main thread, 10 Hz) ---------------------------------------------------------

- (void)startTimer {
    if (_timer) return;
    _timer = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, 0, dispatch_get_main_queue());
    dispatch_source_set_timer(_timer, dispatch_time(DISPATCH_TIME_NOW, 0), NSEC_PER_SEC / 10, NSEC_PER_SEC / 50);
    __weak HarmonyDspBridge *weakSelf = self;
    dispatch_source_set_event_handler(_timer, ^{ [weakSelf tick]; });
    dispatch_resume(_timer);
}

- (void)stopTimer {
    if (_timer) {
        dispatch_source_cancel(_timer);
        _timer = nil;
    }
}

- (void)tick {
    const uint32_t trackSeq = _core->trackSequence();
    if (trackSeq != _seenTrackSeq) {
        _seenTrackSeq = trackSeq;
        const int64_t started = _core->currentToken();
        const BOOL sameAsBefore = _swallowStart && started == _swallowToken;
        _swallowStart = NO;
        if (!sameAsBefore) [self.delegate dspBridge:self didStartTrack:started];
    }
    const uint32_t finishSeq = _core->finishSequence();
    if (finishSeq != _seenFinishSeq) {
        _seenFinishSeq = finishSeq;
        _playing = NO;
        _core->setPlaying(false);
        [_engine pause];
        [self.delegate dspBridgeDidFinishQueue:self];
    }
    // The passive vector: once 40% of the song has actually been heard.
    const int64_t token = _core->currentToken();
    const double duration = _core->durationSeconds();
    if (token >= 0 && duration > 5 &&
        static_cast<double>(_core->listenedFrames()) >= 0.40 * duration * _core->sampleRate()) {
        bool received;
        {
            std::lock_guard<std::mutex> guard(_shared->lock);
            received = _shared->vectorsReceived.count(token) > 0;
        }
        if (!received) _core->requestVector(token);
    }
}

// ---- Equalizer --------------------------------------------------------------------------

- (void)setBandGain:(float)gainDb forBand:(NSInteger)band {
    if (band >= 0 && band < harmony::eq::kNumBands) _core->eq().setBandGainDb(static_cast<int>(band), gainDb);
}
- (void)setEqEnabled:(BOOL)enabled { _core->eq().setEqEnabled(enabled); }
- (void)setAutoMode:(BOOL)enabled { _core->eq().setAutoMode(enabled); }
- (void)setUserPreampDb:(float)preampDb { _core->eq().setUserPreampDb(preampDb); }
- (void)setLevelGainDb:(float)gainDb { _core->eq().setLevelGainDb(gainDb); }
- (void)setOutputDevice:(HarmonyOutputDevice)device {
    _core->eq().setOutputDeviceType(static_cast<harmony::eq::OutputDevice>(device));
}
- (void)setAnalyzerEnabled:(BOOL)enabled { _core->eq().setAnalyzerEnabled(enabled); }
- (void)setSpectrumBarCount:(NSInteger)bars { _core->eq().setSpectrumBarCount(static_cast<int>(bars)); }

- (float)pollTelemetryWithGains:(float *)gains bars:(float *)bars peaks:(float *)peaks capacity:(NSInteger)capacity {
    return _core->eq().pollDspTelemetry(gains, bars, peaks, static_cast<int>(std::max<NSInteger>(0, capacity)));
}
- (void)copyLiveAutoOffsets:(float *)offsets { _core->eq().liveAutoOffsetsDb(offsets); }
- (float)bassDuckDb { return _core->eq().bassDuckDb(); }
- (float)harshDuckDb { return _core->eq().harshDuckDb(); }
- (BOOL)tonalFrozen { return _core->eq().tonalFrozen(); }
- (float)limiterCeilingDb { return _core->eq().ceilingDb(); }
- (uint32_t)healCount { return _core->eq().healCount(); }
- (uint64_t)underrunFrames { return _core->underrunFrames(); }

@end
