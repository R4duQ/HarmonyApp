// HarmonyTagWriter.h: metadata and cover art for downloaded and imported songs,
// written straight into the file, plus the container checks every download
// passes before it reaches the library.
//
//   FLAC   Vorbis comments and a front-cover PICTURE block (the audio frames
//          are copied byte for byte; STREAMINFO, SEEKTABLE, CUESHEET and
//          APPLICATION blocks are kept)
//   MP3    an ID3v2.3 tag (UTF-16 text, APIC cover) replacing any old one
//   M4A    done by the Swift side through AVFoundation (IosNativeAudioTranscoder)
//
// Plain C++17 with file I/O only, so it is tested on the host (ios/Tests).

#ifndef HARMONY_TAG_WRITER_H
#define HARMONY_TAG_WRITER_H

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace harmony::ingest {

enum class Container {
    Unknown = 0,
    Flac = 1,
    Mp3 = 2,
    Mp4 = 3,   // M4A / AAC / ALAC
    Wav = 4,
    Aiff = 5,
    Ogg = 6,   // Ogg Vorbis / Opus
    Caf = 7,
};

/** What a container is called in the UI and in file names. */
const char* containerExtension(Container container);

/** Identifies a container from its first bytes (16 or more), by magic numbers, not by name. */
Container sniffContainer(const uint8_t* head, size_t size);
/** The same for a file on disk. */
Container sniffFile(const std::string& path);

struct TrackTags {
    std::string title;
    std::string artist;
    std::string album;
    std::string albumArtist;
    std::string trackNumber;  // "3" or "3/12"
    std::string discNumber;
    std::string date;         // "2021" or "2021-04-09"
    std::string genre;
    std::string isrc;
    std::string comment;
    std::vector<uint8_t> cover;  // JPEG or PNG
    std::string coverMime;       // "image/jpeg" or "image/png"; sniffed when empty
};

struct FlacStreamInfo {
    int sampleRate = 0;
    int channels = 0;
    int bitsPerSample = 0;
    uint64_t totalSamples = 0;
    double durationSeconds() const { return sampleRate > 0 ? static_cast<double>(totalSamples) / sampleRate : 0.0; }
};

/** Reads a FLAC file's STREAMINFO (skipping a stray ID3v2 tag in front). */
bool readFlacStreamInfo(const std::string& path, FlacStreamInfo* out, std::string* error);

/**
 * Replaces the tags of the FLAC file at [path]. Existing comments are kept
 * unless [tags] sets the same field; an existing front cover is replaced only
 * when [tags] carries one. Written to a temporary file and renamed over the
 * original, so a failure never leaves a broken file.
 */
bool writeFlacTags(const std::string& path, const TrackTags& tags, std::string* error);

/** Replaces any ID3v2 tag at the start of the MP3 at [path] with an ID3v2.3 one. */
bool writeId3Tags(const std::string& path, const TrackTags& tags, std::string* error);

/**
 * The check a download must pass before it is kept: the bytes are the
 * container the name says, and long enough to hold it. For FLAC the
 * STREAMINFO must be sane; for MP3 an MPEG frame sync must follow the tag.
 * Returns an empty string when the file is good, else why it is not.
 */
std::string verifyAudioFile(const std::string& path, const std::string& expectedExtension, uint64_t expectedSize);

// Exposed for tests.
std::vector<uint8_t> buildVorbisCommentBlock(const std::vector<std::string>& comments, const std::string& vendor);
std::vector<uint8_t> buildFlacPictureBlock(const std::vector<uint8_t>& image, const std::string& mime);
std::vector<uint8_t> buildId3v23Tag(const TrackTags& tags, size_t padding);
std::string sniffImageMime(const std::vector<uint8_t>& image);

}  // namespace harmony::ingest

#endif  // HARMONY_TAG_WRITER_H
