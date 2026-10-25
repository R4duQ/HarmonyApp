// HarmonySmartShuffle.h: Smart Shuffle for Swift, straight into the shared
// C++ SmartShuffleEngine (core/dsp-native) and its Diagnostic Brain.
//
// The same engine as the Android app: the 24-D session vibe vector S_t,
// multi-factor scoring (vibe, Markov habits, affinity, UCB exploration,
// recency cooldown), Efraimidis-Spirakis sampling, anti-clumping dithering,
// nearest-neighbour ordering, and the five self-audits on every queue.
//
// Not thread-safe: use one instance from one serial queue at a time.

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/** What a listening event did to S_t (SessionEvent in the C++ engine). */
typedef NS_ENUM(NSInteger, HarmonyShuffleEvent) {
    HarmonyShuffleEventNone = 0,
    /** S_t started from this song. */
    HarmonyShuffleEventSeeded = 1,
    /** Played past half: S_t moved toward the song. */
    HarmonyShuffleEventPlayEma = 2,
    /** Skipped before 20%: S_t pushed away from the song. */
    HarmonyShuffleEventFastSkip = 3,
    /** Picked by hand: S_t anchored on the song. */
    HarmonyShuffleEventManualAnchor = 4,
    /** Between 20% and 50%, or no usable vector: S_t held. */
    HarmonyShuffleEventNeutral = 5,
};

/** Why the brain picked a song (TrackDecisionTrace). The four percentages sum to 100. */
@interface HarmonyShuffleTrace : NSObject
@property (nonatomic, readonly) int64_t trackId;
@property (nonatomic, readonly) float vibePct;
@property (nonatomic, readonly) float markovPct;
@property (nonatomic, readonly) float affinityPct;
@property (nonatomic, readonly) float explorationPct;
/** Cosine with S_t, -1..1. */
@property (nonatomic, readonly) float cosine;
/** 1 = seamless from the song before, 0 = opposite. */
@property (nonatomic, readonly) float smoothness;
/** 1 = no recency cooldown. */
@property (nonatomic, readonly) float cooldown;
/** "Bass", "Warm", "Vocal", "Bright" or "Air". */
@property (nonatomic, readonly, copy) NSString *zone;
/** "94% Bass/Vibe Match", "Markov Favorite Transition", "Bandit Rediscovery Gem"… */
@property (nonatomic, readonly, copy) NSString *reasonTag;
/** 2 = a real 24-D measurement, 1 = estimated, 0 = none. */
@property (nonatomic, readonly) NSInteger vectorQuality;
@end

/** One of the five self-audits. */
@interface HarmonyShuffleAuditCheck : NSObject
/** "Anti-clumping", "Skip repulsion", "Echo chamber", "Vector integrity", "Transition jerk". */
@property (nonatomic, readonly, copy) NSString *name;
/** 0 = passed, 1 = failed and repaired, 2 = failed (library too small to repair). */
@property (nonatomic, readonly) NSInteger status;
@property (nonatomic, readonly) float metric;
@property (nonatomic, readonly) float threshold;
@property (nonatomic, readonly) NSInteger corrections;
@property (nonatomic, readonly, copy) NSString *detail;
@end

/** The audit of the last queue the engine built. */
@interface HarmonyShuffleAudit : NSObject
@property (nonatomic, readonly) uint64_t generation;
@property (nonatomic, readonly, copy) NSArray<HarmonyShuffleAuditCheck *> *checks;
@property (nonatomic, readonly) BOOL afterFastSkip;
@property (nonatomic, readonly) float averagePairwiseSimilarity;
@property (nonatomic, readonly) float artistEntropyBits;
@property (nonatomic, readonly) float zoneEntropyBits;
/** Share of the library with a real 24-D vector, 0..100. */
@property (nonatomic, readonly) float realVectorPct;
@property (nonatomic, readonly) double elapsedMs;
/** No check left failed. */
@property (nonatomic, readonly) BOOL healthy;
@end

@interface HarmonySmartShuffle : NSObject

/**
 * Loads the library. [ids] holds [count] int64, [vectors] count x 24 float32
 * (NaN where unknown), [qualities] count uint8 (2 = real), [artists] and
 * [albums] count int32 ids interned by the caller (-1 = unknown). Keeps the
 * session and the learned habits. Returns the number of songs loaded.
 */
- (NSInteger)setLibraryWithIds:(NSData *)ids
                       vectors:(NSData *)vectors
                     qualities:(NSData *)qualities
                       artists:(NSData *)artists
                        albums:(NSData *)albums
                         count:(NSInteger)count;

/** Listening history in library order: completed and skipped plays (int32), last played (int64 ms). */
- (void)setTrackStatsWithCompleted:(NSData *)completed
                           skipped:(NSData *)skipped
                        lastPlayed:(NSData *)lastPlayedMs
                             count:(NSInteger)count;

/** Markov habits: [count] "to after from" transitions (int64, int64, float32), added to what is known. */
- (void)addTransitionsFrom:(NSData *)from to:(NSData *)to weights:(NSData *)weights count:(NSInteger)count;

- (HarmonyShuffleEvent)trackStarted:(int64_t)trackId;
/** A song ended or was left after [fraction] of it. [shufflePick]: Smart Shuffle had queued it. */
- (HarmonyShuffleEvent)playbackOutcome:(int64_t)trackId
                              fraction:(float)fraction
                              atMillis:(int64_t)atMillis
                           shufflePick:(BOOL)shufflePick;
- (HarmonyShuffleEvent)manualSelect:(int64_t)trackId;
- (void)resetSession;
@property (nonatomic, readonly) BOOL hasSession;
/** S_t: 24 values (zeros before the first song). */
@property (nonatomic, readonly, copy) NSArray<NSNumber *> *sessionVector;

/** The Markov transition the last outcome taught (to persist it). */
- (BOOL)lastLearnedTransitionFrom:(int64_t *)from to:(int64_t *)to delta:(float *)delta;

/**
 * Up to [count] songs to follow [currentId] and the songs already queued
 * after it ([prefix]), never any of [exclude]. Ids as NSNumber int64.
 */
- (NSArray<NSNumber *> *)generateAfter:(int64_t)currentId
                                prefix:(NSArray<NSNumber *> *)prefix
                               exclude:(NSArray<NSNumber *> *)exclude
                                 count:(NSInteger)count
                              atMillis:(int64_t)atMillis
                                  seed:(uint64_t)seed;

/** Why each song of the last generateAfter was picked. */
@property (nonatomic, readonly, copy) NSArray<HarmonyShuffleTrace *> *lastTraces;
@property (nonatomic, readonly) HarmonyShuffleAudit *lastAudit;
/** Songs loaded. */
@property (nonatomic, readonly) NSInteger librarySize;

@end

NS_ASSUME_NONNULL_END
