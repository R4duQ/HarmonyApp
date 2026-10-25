// HarmonyAudioFile.h: a C++ wrapper over AudioToolbox's ExtAudioFileRef.
//
// Opens any file Core Audio can decode (MP3, AAC/M4A, ALAC, FLAC, WAV, AIFF,
// CAF) and reads it as packed 32-bit float PCM at a chosen rate and channel
// count, with sample-accurate seeking. Used by the scanner (mono, 44.1 kHz)
// and by the player's decoder thread (stereo, at the output's rate).
//
// Objective-C++ only (included from .mm files, never from Swift).

#ifndef HARMONY_AUDIO_FILE_H
#define HARMONY_AUDIO_FILE_H

#include <AudioToolbox/AudioToolbox.h>
#include <CoreFoundation/CoreFoundation.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <string>

namespace harmony::ios {

class AudioFile {
public:
    AudioFile() = default;
    ~AudioFile() { close(); }
    AudioFile(const AudioFile&) = delete;
    AudioFile& operator=(const AudioFile&) = delete;

    /**
     * Opens [url] for reading as [channels]-channel float at [clientRate] Hz
     * (0 = the file's own rate). On failure returns false and fills [error].
     */
    bool open(CFURLRef url, double clientRate, int channels, std::string* error) {
        close();
        OSStatus status = ExtAudioFileOpenURL(url, &file_);
        if (status != noErr || file_ == nullptr) {
            file_ = nullptr;
            if (error) *error = describe("open", status);
            return false;
        }
        AudioStreamBasicDescription fileFormat{};
        UInt32 size = sizeof(fileFormat);
        status = ExtAudioFileGetProperty(file_, kExtAudioFileProperty_FileDataFormat, &size, &fileFormat);
        if (status != noErr || fileFormat.mSampleRate <= 0) {
            if (error) *error = describe("read the format", status);
            close();
            return false;
        }
        fileRate_ = fileFormat.mSampleRate;
        fileChannels_ = static_cast<int>(fileFormat.mChannelsPerFrame);
        fileBitsPerChannel_ = static_cast<int>(fileFormat.mBitsPerChannel);
        formatId_ = fileFormat.mFormatID;
        formatFlags_ = fileFormat.mFormatFlags;

        SInt64 frames = 0;
        size = sizeof(frames);
        if (ExtAudioFileGetProperty(file_, kExtAudioFileProperty_FileLengthFrames, &size, &frames) != noErr) frames = 0;
        fileFrames_ = std::max<SInt64>(0, frames);

        clientRate_ = clientRate > 0 ? clientRate : fileRate_;
        channels_ = std::max(1, std::min(channels, 8));
        AudioStreamBasicDescription client{};
        client.mSampleRate = clientRate_;
        client.mFormatID = kAudioFormatLinearPCM;
        client.mFormatFlags = kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked;
        client.mBitsPerChannel = 32;
        client.mChannelsPerFrame = static_cast<UInt32>(channels_);
        client.mFramesPerPacket = 1;
        client.mBytesPerFrame = static_cast<UInt32>(sizeof(float) * channels_);
        client.mBytesPerPacket = client.mBytesPerFrame;
        status = ExtAudioFileSetProperty(file_, kExtAudioFileProperty_ClientDataFormat, sizeof(client), &client);
        if (status != noErr) {
            if (error) *error = describe("set the decode format", status);
            close();
            return false;
        }
        if (fileChannels_ > 2 && channels_ <= 2) {
            // Surround to stereo or mono: front left and right (the AudioConverter's default map).
            SInt32 map[2] = {0, fileChannels_ > 1 ? 1 : 0};
            AudioConverterRef converter = nullptr;
            size = sizeof(converter);
            if (ExtAudioFileGetProperty(file_, kExtAudioFileProperty_AudioConverter, &size, &converter) == noErr && converter) {
                AudioConverterSetProperty(converter, kAudioConverterChannelMap, sizeof(SInt32) * channels_, map);
                CFArrayRef config = nullptr;
                ExtAudioFileSetProperty(file_, kExtAudioFileProperty_ConverterConfig, sizeof(config), &config);
            }
        }
        return true;
    }

    void close() {
        if (file_ != nullptr) {
            ExtAudioFileDispose(file_);
            file_ = nullptr;
        }
        fileFrames_ = 0;
    }

    bool isOpen() const { return file_ != nullptr; }

    /** Reads up to [frames] frames into [out] (frames x channels floats). Returns frames read; 0 at the end, -1 on error. */
    int read(float* out, int frames) {
        if (file_ == nullptr || frames <= 0) return -1;
        AudioBufferList list;
        list.mNumberBuffers = 1;
        list.mBuffers[0].mNumberChannels = static_cast<UInt32>(channels_);
        list.mBuffers[0].mDataByteSize = static_cast<UInt32>(frames * channels_ * sizeof(float));
        list.mBuffers[0].mData = out;
        UInt32 count = static_cast<UInt32>(frames);
        const OSStatus status = ExtAudioFileRead(file_, &count, &list);
        if (status != noErr) return -1;
        return static_cast<int>(count);
    }

    /** Moves the read position to [seconds] from the start. */
    bool seekSeconds(double seconds) {
        if (file_ == nullptr) return false;
        // ExtAudioFileSeek counts frames at the FILE's rate, not the client's.
        SInt64 frame = static_cast<SInt64>(std::max(0.0, seconds) * fileRate_);
        if (fileFrames_ > 0) frame = std::min(frame, fileFrames_);
        return ExtAudioFileSeek(file_, frame) == noErr;
    }

    double durationSeconds() const { return fileRate_ > 0 ? static_cast<double>(fileFrames_) / fileRate_ : 0.0; }
    double fileRate() const { return fileRate_; }
    double clientRate() const { return clientRate_; }
    int channels() const { return channels_; }
    int fileChannels() const { return fileChannels_; }
    int fileBitsPerChannel() const { return fileBitsPerChannel_; }
    AudioFormatID formatId() const { return formatId_; }
    /**
     * The source bit depth: from the ASBD for PCM, from the source-depth flags
     * for FLAC and ALAC (which report 0 bits per channel), 0 for lossy codecs.
     */
    int sourceBitDepth() const {
        if (formatId_ == kAudioFormatLinearPCM) return fileBitsPerChannel_;
        if (formatId_ == kAudioFormatFLAC || formatId_ == kAudioFormatAppleLossless) {
            switch (formatFlags_) {
                case kAppleLosslessFormatFlag_16BitSourceData: return 16;
                case kAppleLosslessFormatFlag_20BitSourceData: return 20;
                case kAppleLosslessFormatFlag_24BitSourceData: return 24;
                case kAppleLosslessFormatFlag_32BitSourceData: return 32;
                default: return fileBitsPerChannel_;
            }
        }
        return 0;
    }

private:
    static std::string describe(const char* what, OSStatus status) {
        char code[5] = {};
        const UInt32 be = CFSwapInt32HostToBig(static_cast<UInt32>(status));
        std::memcpy(code, &be, 4);
        bool printable = true;
        for (char c : std::string(code, 4)) printable &= (c >= 32 && c < 127);
        return std::string("Could not ") + what + " (" +
               (printable ? std::string("'") + code + "'" : std::to_string(static_cast<int>(status))) + ")";
    }

    ExtAudioFileRef file_ = nullptr;
    double fileRate_ = 0;
    double clientRate_ = 0;
    SInt64 fileFrames_ = 0;
    int channels_ = 2;
    int fileChannels_ = 0;
    int fileBitsPerChannel_ = 0;
    AudioFormatID formatId_ = 0;
    AudioFormatFlags formatFlags_ = 0;
};

}  // namespace harmony::ios

#endif  // HARMONY_AUDIO_FILE_H
