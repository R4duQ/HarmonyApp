// HarmonySmartShuffle.mm: see HarmonySmartShuffle.h.

#import "HarmonySmartShuffle.h"

#include <memory>
#include <vector>

#include "SmartShuffleEngine.h"

using harmony::shuffle::AuditCheckId;
using harmony::shuffle::BrainAuditReport;
using harmony::shuffle::SessionEvent;
using harmony::shuffle::SmartShuffleEngine;
using harmony::shuffle::TrackDecisionTrace;
using harmony::shuffle::kAuditChecks;
using harmony::shuffle::kDims;

namespace {

NSString *ToString(const std::string &s) {
    return [[NSString alloc] initWithBytes:s.data() length:s.size() encoding:NSUTF8StringEncoding] ?: @"";
}

HarmonyShuffleEvent ToEvent(SessionEvent event) {
    return static_cast<HarmonyShuffleEvent>(static_cast<int>(event));
}

std::vector<std::int64_t> ToIds(NSArray<NSNumber *> *numbers) {
    std::vector<std::int64_t> ids;
    ids.reserve(numbers.count);
    for (NSNumber *number in numbers) ids.push_back(number.longLongValue);
    return ids;
}

/** [data] holds at least [count] values of T. */
template <typename T>
const T *Values(NSData *data, NSInteger count) {
    if (data == nil || count <= 0 || data.length < static_cast<NSUInteger>(count) * sizeof(T)) return nullptr;
    return static_cast<const T *>(data.bytes);
}

NSString *const kCheckNames[kAuditChecks] = {
    @"Anti-clumping", @"Skip repulsion", @"Echo chamber", @"Vector integrity", @"Transition jerk",
};

}  // namespace

// MARK: - Trace and audit

@interface HarmonyShuffleTrace ()
- (instancetype)initWithTrace:(const TrackDecisionTrace &)trace;
@end

@implementation HarmonyShuffleTrace
- (instancetype)initWithTrace:(const TrackDecisionTrace &)trace {
    if ((self = [super init])) {
        _trackId = trace.trackId;
        _vibePct = trace.vibeSimilarityPct;
        _markovPct = trace.markovHabitPct;
        _affinityPct = trace.userAffinityPct;
        _explorationPct = trace.explorationBonusPct;
        _cosine = trace.cosineSimilarity;
        _smoothness = trace.transitionSmoothnessScore;
        _cooldown = trace.recencyPenaltyMultiplier;
        _zoneName = trace.zone >= 0 ? @(SmartShuffleEngine::zoneWord(trace.zone)) : @"";
        _reasonTag = ToString(trace.primaryReasonTag);
        _vectorQuality = static_cast<NSInteger>(trace.quality);
    }
    return self;
}
@end

@interface HarmonyShuffleAuditCheck ()
- (instancetype)initWithName:(NSString *)name check:(const harmony::shuffle::AuditCheck &)check;
@end

@implementation HarmonyShuffleAuditCheck
- (instancetype)initWithName:(NSString *)name check:(const harmony::shuffle::AuditCheck &)check {
    if ((self = [super init])) {
        _name = [name copy];
        _status = static_cast<NSInteger>(check.status);
        _metric = check.metric;
        _threshold = check.threshold;
        _corrections = check.corrections;
        _detail = ToString(check.detail);
    }
    return self;
}
@end

@interface HarmonyShuffleAudit ()
- (instancetype)initWithReport:(const BrainAuditReport &)report;
@end

@implementation HarmonyShuffleAudit
- (instancetype)initWithReport:(const BrainAuditReport &)report {
    if ((self = [super init])) {
        _generation = report.generation;
        NSMutableArray<HarmonyShuffleAuditCheck *> *checks = [NSMutableArray arrayWithCapacity:kAuditChecks];
        for (int i = 0; i < kAuditChecks; ++i) {
            [checks addObject:[[HarmonyShuffleAuditCheck alloc] initWithName:kCheckNames[i] check:report.checks[i]]];
        }
        _checks = [checks copy];
        _afterFastSkip = report.afterFastSkip;
        _averagePairwiseSimilarity = report.averagePairwiseSimilarity;
        _artistEntropyBits = report.artistEntropyBits;
        _zoneEntropyBits = report.zoneEntropyBits;
        _realVectorPct = report.realVectorPct;
        _elapsedMs = report.elapsedMs;
        _healthy = report.healthy();
    }
    return self;
}
@end

// MARK: - Engine

@implementation HarmonySmartShuffle {
    std::unique_ptr<SmartShuffleEngine> _engine;
}

- (instancetype)init {
    if ((self = [super init])) {
        _engine = std::make_unique<SmartShuffleEngine>();
    }
    return self;
}

- (NSInteger)setLibraryWithIds:(NSData *)ids
                       vectors:(NSData *)vectors
                     qualities:(NSData *)qualities
                       artists:(NSData *)artists
                        albums:(NSData *)albums
                         count:(NSInteger)count {
    const auto *idValues = Values<std::int64_t>(ids, count);
    const auto *vectorValues = Values<float>(vectors, count * kDims);
    const auto *qualityValues = Values<std::uint8_t>(qualities, count);
    const auto *artistValues = Values<std::int32_t>(artists, count);
    const auto *albumValues = Values<std::int32_t>(albums, count);
    if (idValues == nullptr || vectorValues == nullptr || qualityValues == nullptr ||
        artistValues == nullptr || albumValues == nullptr) {
        return _engine->setLibrary(nullptr, nullptr, nullptr, nullptr, nullptr, 0);
    }
    return _engine->setLibrary(idValues, vectorValues, qualityValues, artistValues, albumValues, static_cast<int>(count));
}

- (void)setTrackStatsWithCompleted:(NSData *)completed
                           skipped:(NSData *)skipped
                        lastPlayed:(NSData *)lastPlayedMs
                             count:(NSInteger)count {
    _engine->setTrackStats(Values<std::int32_t>(completed, count), Values<std::int32_t>(skipped, count),
                           Values<std::int64_t>(lastPlayedMs, count), nullptr, static_cast<int>(count));
}

- (void)addTransitionsFrom:(NSData *)from to:(NSData *)to weights:(NSData *)weights count:(NSInteger)count {
    const auto *fromValues = Values<std::int64_t>(from, count);
    const auto *toValues = Values<std::int64_t>(to, count);
    const auto *weightValues = Values<float>(weights, count);
    if (fromValues == nullptr || toValues == nullptr || weightValues == nullptr) return;
    _engine->addTransitions(fromValues, toValues, weightValues, static_cast<int>(count));
}

- (HarmonyShuffleEvent)trackStarted:(int64_t)trackId {
    return ToEvent(_engine->onTrackStarted(trackId));
}

- (HarmonyShuffleEvent)playbackOutcome:(int64_t)trackId
                              fraction:(float)fraction
                              atMillis:(int64_t)atMillis
                           shufflePick:(BOOL)shufflePick {
    return ToEvent(_engine->onPlaybackOutcome(trackId, fraction, atMillis, shufflePick));
}

- (HarmonyShuffleEvent)manualSelect:(int64_t)trackId {
    return ToEvent(_engine->onManualSelect(trackId));
}

- (void)resetSession {
    _engine->resetSession();
}

- (BOOL)hasSession {
    return _engine->hasSession();
}

- (NSArray<NSNumber *> *)sessionVector {
    const float *v = _engine->sessionVector();
    NSMutableArray<NSNumber *> *out = [NSMutableArray arrayWithCapacity:kDims];
    for (int i = 0; i < kDims; ++i) [out addObject:@(v[i])];
    return out;
}

- (BOOL)lastLearnedTransitionFrom:(int64_t *)from to:(int64_t *)to delta:(float *)delta {
    std::int64_t a = -1;
    std::int64_t b = -1;
    float d = 0.0f;
    if (!_engine->lastLearnedTransition(&a, &b, &d)) return NO;
    if (from) *from = a;
    if (to) *to = b;
    if (delta) *delta = d;
    return YES;
}

- (NSArray<NSNumber *> *)generateAfter:(int64_t)currentId
                                prefix:(NSArray<NSNumber *> *)prefix
                               exclude:(NSArray<NSNumber *> *)exclude
                                 count:(NSInteger)count
                              atMillis:(int64_t)atMillis
                                  seed:(uint64_t)seed {
    if (count <= 0) return @[];
    const std::vector<std::int64_t> prefixIds = ToIds(prefix);
    const std::vector<std::int64_t> excludeIds = ToIds(exclude);
    std::vector<std::int64_t> out(static_cast<size_t>(count));
    const int written = _engine->generateQueue(
        currentId, prefixIds.data(), static_cast<int>(prefixIds.size()), excludeIds.data(),
        static_cast<int>(excludeIds.size()), nullptr, 0, static_cast<int>(count), atMillis, seed, out.data());
    NSMutableArray<NSNumber *> *ids = [NSMutableArray arrayWithCapacity:written];
    for (int i = 0; i < written; ++i) [ids addObject:@(out[static_cast<size_t>(i)])];
    return ids;
}

- (NSArray<HarmonyShuffleTrace *> *)lastTraces {
    NSMutableArray<HarmonyShuffleTrace *> *traces = [NSMutableArray array];
    for (const TrackDecisionTrace &trace : _engine->lastTraces()) {
        [traces addObject:[[HarmonyShuffleTrace alloc] initWithTrace:trace]];
    }
    return traces;
}

- (HarmonyShuffleAudit *)lastAudit {
    return [[HarmonyShuffleAudit alloc] initWithReport:_engine->lastAudit()];
}

- (NSInteger)librarySize {
    return _engine->size();
}

@end
