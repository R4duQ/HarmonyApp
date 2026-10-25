// HarmonyTranscoder.mm: ExtAudioFileRef in, ExtAudioFileRef out.

#import "HarmonyTranscoder.h"

#import <AudioToolbox/AudioToolbox.h>

#include <algorithm>
#include <string>
#include <vector>

#include "../Audio/HarmonyAudioFile.h"
#include "HarmonyTagWriter.h"

static NSString *const HarmonyTranscoderErrorDomain = @"HarmonyTranscoder";

namespace {

NSError *makeError(NSInteger code, NSString *message) {
    return [NSError errorWithDomain:HarmonyTranscoderErrorDomain code:code userInfo:@{NSLocalizedDescriptionKey : message}];
}

NSString *statusText(OSStatus status) {
    const UInt32 be = CFSwapInt32HostToBig(static_cast<UInt32>(status));
    char code[5] = {};
    memcpy(code, &be, 4);
    bool printable = true;
    for (int i = 0; i < 4; ++i) printable &= code[i] >= 32 && code[i] < 127;
    return printable ? [NSString stringWithFormat:@"'%s'", code] : [NSString stringWithFormat:@"%d", static_cast<int>(status)];
}

NSString *codecName(AudioFormatID format) {
    switch (format) {
        case kAudioFormatFLAC: return @"FLAC";
        case kAudioFormatAppleLossless: return @"ALAC";
        case kAudioFormatLinearPCM: return @"PCM";
        case kAudioFormatMPEG4AAC:
        case kAudioFormatMPEG4AAC_HE:
        case kAudioFormatMPEG4AAC_HE_V2: return @"AAC";
        case kAudioFormatMPEGLayer3: return @"MP3";
        case kAudioFormatOpus: return @"OPUS";
        default: {
            const UInt32 be = CFSwapInt32HostToBig(format);
            char code[5] = {};
            memcpy(code, &be, 4);
            NSString *name = [NSString stringWithUTF8String:code] ?: @"?";
            return [name stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceCharacterSet];
        }
    }
}

std::string utf8(NSString *s) {
    const char *c = s.UTF8String;
    return c ? std::string(c) : std::string();
}

}  // namespace

@interface HarmonySourceInfo ()
- (instancetype)initWithRate:(double)rate channels:(NSInteger)channels bits:(NSInteger)bits duration:(double)duration
                       codec:(NSString *)codec lossless:(BOOL)lossless container:(NSString *)container;
@end

@implementation HarmonySourceInfo
- (instancetype)initWithRate:(double)rate channels:(NSInteger)channels bits:(NSInteger)bits duration:(double)duration
                       codec:(NSString *)codec lossless:(BOOL)lossless container:(NSString *)container {
    if ((self = [super init])) {
        _sampleRate = rate;
        _channels = channels;
        _bitDepth = bits;
        _durationSeconds = duration;
        _codec = [codec copy];
        _lossless = lossless;
        _container = [container copy];
    }
    return self;
}
@end

@implementation HarmonyAudioTags
@end

@implementation HarmonyTranscoder

+ (NSString *)sniffContainerAtPath:(NSString *)path {
    return [NSString stringWithUTF8String:harmony::ingest::containerExtension(harmony::ingest::sniffFile(utf8(path)))] ?: @"";
}

+ (HarmonySourceInfo *)inspectFileAtURL:(NSURL *)url {
    harmony::ios::AudioFile file;
    std::string error;
    if (!file.open((__bridge CFURLRef)url, 0, 2, &error)) return nil;
    const AudioFormatID format = file.formatId();
    const BOOL lossless = format == kAudioFormatFLAC || format == kAudioFormatAppleLossless || format == kAudioFormatLinearPCM;
    return [[HarmonySourceInfo alloc] initWithRate:file.fileRate()
                                          channels:file.fileChannels()
                                              bits:file.sourceBitDepth()
                                          duration:file.durationSeconds()
                                             codec:codecName(format)
                                          lossless:lossless
                                         container:[self sniffContainerAtPath:url.path]];
}

+ (BOOL)transcodeFileAtURL:(NSURL *)input
                     toURL:(NSURL *)output
                    target:(HarmonyTranscodeTarget)target
                  progress:(void (^)(double))progress
               isCancelled:(BOOL (^)(void))isCancelled
                     error:(NSError **)error {
    // AAC tries Apple's hardware encoder first and falls back to the software one.
    const int attempts = target == HarmonyTranscodeTargetAac ? 2 : 1;
    NSError *last = nil;
    for (int attempt = 0; attempt < attempts; ++attempt) {
        if ([self transcodeOnce:input to:output target:target hardware:(attempt == 0 && attempts == 2)
                       progress:progress isCancelled:isCancelled error:&last]) {
            return YES;
        }
        if (last.code == 9) break;  // cancelled: don't retry
    }
    if (error) *error = last;
    return NO;
}

+ (BOOL)transcodeOnce:(NSURL *)input
                   to:(NSURL *)output
               target:(HarmonyTranscodeTarget)target
             hardware:(BOOL)hardware
             progress:(void (^)(double))progress
          isCancelled:(BOOL (^)(void))isCancelled
                error:(NSError **)error {
    ExtAudioFileRef in = nullptr;
    OSStatus status = ExtAudioFileOpenURL((__bridge CFURLRef)input, &in);
    if (status != noErr || !in) {
        if (error) *error = makeError(1, [NSString stringWithFormat:@"Could not open the source (%@)", statusText(status)]);
        return NO;
    }
    AudioStreamBasicDescription source{};
    UInt32 size = sizeof source;
    ExtAudioFileGetProperty(in, kExtAudioFileProperty_FileDataFormat, &size, &source);
    SInt64 totalFrames = 0;
    size = sizeof totalFrames;
    ExtAudioFileGetProperty(in, kExtAudioFileProperty_FileLengthFrames, &size, &totalFrames);

    // The source's bit depth (FLAC and ALAC carry it in their flags).
    UInt32 sourceBits = source.mBitsPerChannel;
    if (source.mFormatID == kAudioFormatFLAC || source.mFormatID == kAudioFormatAppleLossless) {
        switch (source.mFormatFlags) {
            case kAppleLosslessFormatFlag_20BitSourceData: sourceBits = 20; break;
            case kAppleLosslessFormatFlag_24BitSourceData: sourceBits = 24; break;
            case kAppleLosslessFormatFlag_32BitSourceData: sourceBits = 32; break;
            default: sourceBits = 16; break;
        }
    }
    const bool deep = sourceBits > 16;  // lossy sources report 0: written as 16-bit
    const UInt32 channels = std::max<UInt32>(1, std::min<UInt32>(source.mChannelsPerFrame, target == HarmonyTranscodeTargetAac ? 2 : 8));
    const double rate = source.mSampleRate > 0 ? source.mSampleRate : 44100.0;

    AudioStreamBasicDescription client{};
    client.mSampleRate = rate;
    client.mFormatID = kAudioFormatLinearPCM;
    client.mFormatFlags = kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked;
    client.mBitsPerChannel = 32;
    client.mChannelsPerFrame = channels;
    client.mFramesPerPacket = 1;
    client.mBytesPerFrame = 4 * channels;
    client.mBytesPerPacket = client.mBytesPerFrame;
    status = ExtAudioFileSetProperty(in, kExtAudioFileProperty_ClientDataFormat, sizeof client, &client);
    if (status != noErr) {
        ExtAudioFileDispose(in);
        if (error) *error = makeError(2, [NSString stringWithFormat:@"Could not decode the source (%@)", statusText(status)]);
        return NO;
    }

    AudioStreamBasicDescription encoded{};
    AudioFileTypeID fileType = kAudioFileM4AType;
    encoded.mChannelsPerFrame = channels;
    switch (target) {
        case HarmonyTranscodeTargetFlac:
            encoded.mFormatID = kAudioFormatFLAC;
            encoded.mSampleRate = rate;
            encoded.mFormatFlags = deep ? kAppleLosslessFormatFlag_24BitSourceData : kAppleLosslessFormatFlag_16BitSourceData;
            fileType = kAudioFileFLACType;
            break;
        case HarmonyTranscodeTargetAlac:
            encoded.mFormatID = kAudioFormatAppleLossless;
            encoded.mSampleRate = rate;
            encoded.mFormatFlags = deep ? kAppleLosslessFormatFlag_24BitSourceData : kAppleLosslessFormatFlag_16BitSourceData;
            break;
        case HarmonyTranscodeTargetAac:
            encoded.mFormatID = kAudioFormatMPEG4AAC;
            encoded.mSampleRate = std::min(rate, 48000.0);  // AAC-LC tops out at 48 kHz; ExtAudioFile resamples
            break;
    }
    size = sizeof encoded;
    AudioFormatGetProperty(kAudioFormatProperty_FormatInfo, 0, nullptr, &size, &encoded);

    [NSFileManager.defaultManager removeItemAtURL:output error:nil];
    ExtAudioFileRef out = nullptr;
    status = ExtAudioFileCreateWithURL((__bridge CFURLRef)output, fileType, &encoded, nullptr, kAudioFileFlags_EraseFile, &out);
    if (status != noErr || !out) {
        ExtAudioFileDispose(in);
        if (error) *error = makeError(3, [NSString stringWithFormat:@"Could not create the %@ file (%@)",
                                                                    target == HarmonyTranscodeTargetFlac ? @"FLAC" : @"M4A", statusText(status)]);
        return NO;
    }
    if (target == HarmonyTranscodeTargetAac) {
        UInt32 manufacturer = hardware ? kAppleHardwareAudioCodecManufacturer : kAppleSoftwareAudioCodecManufacturer;
        ExtAudioFileSetProperty(out, kExtAudioFileProperty_CodecManufacturer, sizeof manufacturer, &manufacturer);
    }
    status = ExtAudioFileSetProperty(out, kExtAudioFileProperty_ClientDataFormat, sizeof client, &client);
    if (status == noErr && target == HarmonyTranscodeTargetAac) {
        AudioConverterRef converter = nullptr;
        size = sizeof converter;
        if (ExtAudioFileGetProperty(out, kExtAudioFileProperty_AudioConverter, &size, &converter) == noErr && converter) {
            UInt32 bitRate = channels > 1 ? 256000 : 160000;
            AudioConverterSetProperty(converter, kAudioConverterEncodeBitRate, sizeof bitRate, &bitRate);
            CFArrayRef config = nullptr;
            ExtAudioFileSetProperty(out, kExtAudioFileProperty_ConverterConfig, sizeof config, &config);
        }
    }
    if (status != noErr) {
        ExtAudioFileDispose(in);
        ExtAudioFileDispose(out);
        [NSFileManager.defaultManager removeItemAtURL:output error:nil];
        if (error) *error = makeError(4, [NSString stringWithFormat:@"The encoder refused the format (%@)", statusText(status)]);
        return NO;
    }

    constexpr UInt32 kChunk = 8192;
    std::vector<float> buffer(static_cast<size_t>(kChunk) * channels);
    SInt64 done = 0;
    BOOL ok = YES;
    NSError *failure = nil;
    while (true) {
        if (isCancelled && isCancelled()) {
            ok = NO;
            failure = makeError(9, @"Cancelled");
            break;
        }
        AudioBufferList list;
        list.mNumberBuffers = 1;
        list.mBuffers[0].mNumberChannels = channels;
        list.mBuffers[0].mDataByteSize = static_cast<UInt32>(buffer.size() * sizeof(float));
        list.mBuffers[0].mData = buffer.data();
        UInt32 frames = kChunk;
        status = ExtAudioFileRead(in, &frames, &list);
        if (status != noErr) {
            ok = NO;
            failure = makeError(5, [NSString stringWithFormat:@"The source stopped decoding (%@)", statusText(status)]);
            break;
        }
        if (frames == 0) break;
        status = ExtAudioFileWrite(out, frames, &list);
        if (status != noErr) {
            ok = NO;
            failure = makeError(6, [NSString stringWithFormat:@"The encoder failed (%@)", statusText(status)]);
            break;
        }
        done += frames;
        if (progress && totalFrames > 0) {
            // totalFrames counts the file's rate; the client runs at the same rate.
            progress(std::min(1.0, static_cast<double>(done) / static_cast<double>(totalFrames)));
        }
    }
    ExtAudioFileDispose(in);
    const OSStatus closed = ExtAudioFileDispose(out);
    if (ok && closed != noErr) {
        ok = NO;
        failure = makeError(7, [NSString stringWithFormat:@"Could not finish the file (%@)", statusText(closed)]);
    }
    if (!ok) {
        [NSFileManager.defaultManager removeItemAtURL:output error:nil];
        if (error) *error = failure;
        return NO;
    }
    if (progress) progress(1.0);
    return YES;
}

+ (BOOL)writeTags:(HarmonyAudioTags *)tags toFileAtPath:(NSString *)path error:(NSError **)error {
    harmony::ingest::TrackTags t;
    t.title = utf8(tags.title);
    t.artist = utf8(tags.artist);
    t.album = utf8(tags.album);
    t.albumArtist = utf8(tags.albumArtist);
    t.trackNumber = utf8(tags.trackNumber);
    t.discNumber = utf8(tags.discNumber);
    t.date = utf8(tags.date);
    t.genre = utf8(tags.genre);
    t.isrc = utf8(tags.isrc);
    t.comment = utf8(tags.comment);
    if (tags.cover.length > 0) {
        const auto *bytes = static_cast<const uint8_t *>(tags.cover.bytes);
        t.cover.assign(bytes, bytes + tags.cover.length);
    }
    const std::string file = utf8(path);
    std::string message;
    bool ok = false;
    switch (harmony::ingest::sniffFile(file)) {
        case harmony::ingest::Container::Flac: ok = harmony::ingest::writeFlacTags(file, t, &message); break;
        case harmony::ingest::Container::Mp3: ok = harmony::ingest::writeId3Tags(file, t, &message); break;
        default: message = "Tags for this container are written through AVFoundation"; break;
    }
    if (!ok && error) *error = makeError(8, [NSString stringWithUTF8String:message.c_str()] ?: @"Could not tag the file");
    return ok ? YES : NO;
}

+ (NSString *)verifyFileAtPath:(NSString *)path expectedExtension:(NSString *)expectedExtension expectedSize:(uint64_t)expectedSize {
    const std::string problem = harmony::ingest::verifyAudioFile(utf8(path), utf8(expectedExtension), expectedSize);
    return problem.empty() ? nil : ([NSString stringWithUTF8String:problem.c_str()] ?: @"The file failed the check");
}

@end
