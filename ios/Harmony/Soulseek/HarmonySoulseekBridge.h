// HarmonySoulseekBridge.h: the Soulseek engine (SlskEngine, C++) for Swift.
// Every delegate call arrives on the main thread.

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

typedef NS_ENUM(NSInteger, HarmonySlskStatus) {
    HarmonySlskStatusDisconnected = 0,
    HarmonySlskStatusConnecting = 1,
    HarmonySlskStatusConnected = 2,
    HarmonySlskStatusError = 3,
};

typedef NS_ENUM(NSInteger, HarmonySlskStage) {
    HarmonySlskStageConnectingPeer = 0,
    HarmonySlskStageRequested = 1,
    HarmonySlskStageQueued = 2,
    HarmonySlskStageAccepted = 3,
    HarmonySlskStageTransferring = 4,
    HarmonySlskStageVerifying = 5,
    HarmonySlskStageDone = 6,
    HarmonySlskStageFailed = 7,
    HarmonySlskStageCancelled = 8,
};

typedef NS_ENUM(NSInteger, HarmonySlskFormat) {
    HarmonySlskFormatFlacOnly = 0,
    HarmonySlskFormatPreferFlac = 1,
    HarmonySlskFormatPreferMp3 = 2,
    HarmonySlskFormatMp3Only = 3,
};

typedef NS_ENUM(NSInteger, HarmonySlskSearchMode) {
    HarmonySlskSearchModeQuick = 0,
    HarmonySlskSearchModeBalanced = 1,
    HarmonySlskSearchModeDeep = 2,
};

/** One ranked search result. */
@interface HarmonySlskCandidate : NSObject
@property (nonatomic, readonly, copy) NSString *identifier;
@property (nonatomic, readonly, copy) NSString *username;
/** The peer's path, exactly as it sent it (raw bytes kept for the request). */
@property (nonatomic, readonly, copy) NSString *filename;
@property (nonatomic, readonly, copy) NSString *leafName;
@property (nonatomic, readonly) uint64_t size;
@property (nonatomic, readonly, copy) NSString *fileExtension;
@property (nonatomic, readonly) NSInteger durationSeconds;  // 0: not reported
@property (nonatomic, readonly) NSInteger sampleRate;
@property (nonatomic, readonly) NSInteger bitDepth;
@property (nonatomic, readonly) NSInteger bitrateKbps;
@property (nonatomic, readonly) BOOL freeUploadSlot;
@property (nonatomic, readonly) uint32_t averageSpeed;
@property (nonatomic, readonly) uint32_t queueLength;
@property (nonatomic, readonly) NSInteger score;
@property (nonatomic, readonly, copy) NSString *recommendation;
@end

@protocol HarmonySoulseekDelegate <NSObject>
- (void)soulseekStatusChanged:(HarmonySlskStatus)status message:(NSString *)message;
- (void)soulseekSearch:(uint32_t)searchId
               results:(NSArray<HarmonySlskCandidate *> *)results
                  done:(BOOL)done
        peersResponded:(NSInteger)peers
             filesSeen:(NSInteger)files;
- (void)soulseekTransfer:(uint32_t)transferId
                   stage:(HarmonySlskStage)stage
                   bytes:(uint64_t)bytes
                   total:(uint64_t)total
          bytesPerSecond:(double)bytesPerSecond
              queuePlace:(NSInteger)queuePlace
                 message:(NSString *)message
                    path:(nullable NSString *)path
         queuedElsewhere:(BOOL)queuedElsewhere;
@end

@interface HarmonySoulseekBridge : NSObject
- (instancetype)initWithPartialDirectory:(NSString *)partialDirectory
                       downloadDirectory:(NSString *)downloadDirectory NS_DESIGNATED_INITIALIZER;
- (instancetype)init NS_UNAVAILABLE;

@property (nonatomic, weak, nullable) id<HarmonySoulseekDelegate> delegate;
@property (nonatomic, readonly) HarmonySlskStatus status;

- (void)connectWithUsername:(NSString *)username password:(NSString *)password;
- (void)disconnect;

/** [targetDurationSeconds]: the length of the song wanted (0: any); see SearchOptions. */
- (uint32_t)search:(NSString *)query
              mode:(HarmonySlskSearchMode)mode
            format:(HarmonySlskFormat)format
     onlyFreeSlots:(BOOL)onlyFreeSlots
    targetDurationSeconds:(NSInteger)targetDurationSeconds;
- (void)cancelSearch:(uint32_t)searchId;

- (uint32_t)download:(HarmonySlskCandidate *)candidate patient:(BOOL)patient fileName:(nullable NSString *)fileName;
- (void)cancelTransfer:(uint32_t)transferId;
@end

NS_ASSUME_NONNULL_END
