// HarmonySoulseekBridge.mm: SlskEngine events onto the main thread.

#import "HarmonySoulseekBridge.h"

#include <memory>

#include "SlskEngine.h"

namespace {

/** Peers send UTF-8, older Windows clients Latin-1: show either, keep the raw bytes for requests. */
NSString *displayString(const std::string &raw) {
    NSString *utf8 = [[NSString alloc] initWithBytes:raw.data() length:raw.size() encoding:NSUTF8StringEncoding];
    if (utf8) return utf8;
    return [[NSString alloc] initWithBytes:raw.data() length:raw.size() encoding:NSISOLatin1StringEncoding] ?: @"";
}

std::string utf8(NSString *s) {
    const char *c = s.UTF8String;
    return c ? std::string(c) : std::string();
}

}  // namespace

@interface HarmonySlskCandidate () {
@public
    harmony::slsk::Candidate _raw;
}
- (instancetype)initWithCandidate:(const harmony::slsk::Candidate &)candidate;
@end

@implementation HarmonySlskCandidate

- (instancetype)initWithCandidate:(const harmony::slsk::Candidate &)c {
    if ((self = [super init])) {
        _raw = c;
        _identifier = displayString(c.id);
        _username = displayString(c.username);
        _filename = displayString(c.filename);
        _leafName = displayString(c.leafName);
        _size = c.size;
        _fileExtension = displayString(c.extension);
        _durationSeconds = c.durationSeconds;
        _sampleRate = c.sampleRate;
        _bitDepth = c.bitDepth;
        _bitrateKbps = c.bitrateKbps;
        _freeUploadSlot = c.freeUploadSlot;
        _averageSpeed = c.averageSpeed;
        _queueLength = c.queueLength;
        _score = c.score;
        _recommendation = displayString(c.recommendation);
    }
    return self;
}

@end

@interface HarmonySoulseekBridge ()
- (void)deliver:(const harmony::slsk::Event &)event;
@end

@implementation HarmonySoulseekBridge {
    std::unique_ptr<harmony::slsk::Engine> _engine;
}

- (instancetype)initWithPartialDirectory:(NSString *)partialDirectory downloadDirectory:(NSString *)downloadDirectory {
    if ((self = [super init])) {
        harmony::slsk::EngineConfig config;
        config.partialDirectory = utf8(partialDirectory);
        config.downloadDirectory = utf8(downloadDirectory);
        __weak HarmonySoulseekBridge *weakSelf = self;
        _engine = std::make_unique<harmony::slsk::Engine>(config, [weakSelf](const harmony::slsk::Event &event) {
            [weakSelf deliver:event];
        });
        _engine->start();
    }
    return self;
}

- (void)dealloc {
    if (_engine) _engine->stop();
}

/** Called on the engine thread: copy what is needed, then hop to the main thread. */
- (void)deliver:(const harmony::slsk::Event &)event {
    using Kind = harmony::slsk::Event::Kind;
    __weak HarmonySoulseekBridge *weakSelf = self;
    switch (event.kind) {
        case Kind::Connection: {
            const auto status = static_cast<HarmonySlskStatus>(event.status);
            NSString *message = displayString(event.message);
            dispatch_async(dispatch_get_main_queue(), ^{
                HarmonySoulseekBridge *bridge = weakSelf;
                [bridge.delegate soulseekStatusChanged:status message:message];
            });
            break;
        }
        case Kind::Search: {
            NSMutableArray<HarmonySlskCandidate *> *results = [NSMutableArray arrayWithCapacity:event.candidates.size()];
            for (const auto &c : event.candidates) [results addObject:[[HarmonySlskCandidate alloc] initWithCandidate:c]];
            const uint32_t searchId = event.searchId;
            const BOOL done = event.searchDone;
            const NSInteger peers = event.peersResponded;
            const NSInteger files = event.filesSeen;
            dispatch_async(dispatch_get_main_queue(), ^{
                HarmonySoulseekBridge *bridge = weakSelf;
                [bridge.delegate soulseekSearch:searchId results:results done:done peersResponded:peers filesSeen:files];
            });
            break;
        }
        case Kind::Transfer: {
            const uint32_t transferId = event.transferId;
            const auto stage = static_cast<HarmonySlskStage>(event.stage);
            const uint64_t bytes = event.bytes;
            const uint64_t total = event.totalBytes;
            const double speed = event.bytesPerSecond;
            const NSInteger place = event.queuePlace;
            NSString *message = displayString(event.message);
            NSString *path = event.path.empty() ? nil : displayString(event.path);
            const BOOL queued = event.queuedElsewhere;
            dispatch_async(dispatch_get_main_queue(), ^{
                HarmonySoulseekBridge *bridge = weakSelf;
                [bridge.delegate soulseekTransfer:transferId
                                            stage:stage
                                            bytes:bytes
                                            total:total
                                   bytesPerSecond:speed
                                       queuePlace:place
                                          message:message
                                             path:path
                                  queuedElsewhere:queued];
            });
            break;
        }
    }
}

- (HarmonySlskStatus)status {
    return static_cast<HarmonySlskStatus>(_engine->status());
}

- (void)connectWithUsername:(NSString *)username password:(NSString *)password {
    _engine->connect(utf8(username), utf8(password));
}

- (void)disconnect {
    _engine->disconnect();
}

- (uint32_t)search:(NSString *)query
              mode:(HarmonySlskSearchMode)mode
            format:(HarmonySlskFormat)format
     onlyFreeSlots:(BOOL)onlyFreeSlots
    targetDurationSeconds:(NSInteger)targetDurationSeconds {
    harmony::slsk::SearchOptions options;
    options.mode = static_cast<harmony::slsk::SearchMode>(mode);
    options.format = static_cast<harmony::slsk::FormatPreference>(format);
    options.onlyFreeSlots = onlyFreeSlots;
    options.targetDurationSeconds = static_cast<int>(MAX(0, targetDurationSeconds));
    return _engine->search(utf8(query), options);
}

- (void)cancelSearch:(uint32_t)searchId {
    _engine->cancelSearch(searchId);
}

- (uint32_t)download:(HarmonySlskCandidate *)candidate patient:(BOOL)patient fileName:(NSString *)fileName {
    return _engine->download(candidate->_raw, patient, fileName ? utf8(fileName) : std::string());
}

- (void)cancelTransfer:(uint32_t)transferId {
    _engine->cancelTransfer(transferId);
}

- (void)pauseTransfer:(uint32_t)transferId {
    _engine->cancelTransfer(transferId, true);
}

- (void)discardPartialForCandidate:(HarmonySlskCandidate *)candidate {
    _engine->discardPartial(candidate->_raw);
}

@end
