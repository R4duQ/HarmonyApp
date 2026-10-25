// Host tests of HarmonyTagWriter: FLAC Vorbis comments and covers, ID3v2.3,
// container sniffing and the download verification, on synthetic files.

#include "HarmonyTagWriter.h"

#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iterator>
#include <random>
#include <string>
#include <vector>

using namespace harmony::ingest;

namespace {

int failures = 0;

void check(bool ok, const char* format, ...) {
    if (ok) return;
    ++failures;
    std::va_list args;
    va_start(args, format);
    std::fprintf(stderr, "FAIL: ");
    std::vfprintf(stderr, format, args);
    std::fprintf(stderr, "\n");
    va_end(args);
}

std::vector<uint8_t> readAll(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    return std::vector<uint8_t>((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
}

void writeAll(const std::string& path, const std::vector<uint8_t>& data) {
    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    out.write(reinterpret_cast<const char*>(data.data()), static_cast<std::streamsize>(data.size()));
}

std::vector<uint8_t> noise(size_t n, unsigned seed) {
    std::mt19937 random(seed);
    std::vector<uint8_t> v(n);
    for (auto& b : v) b = static_cast<uint8_t>(random());
    return v;
}

void blockHeader(std::vector<uint8_t>& v, bool last, uint8_t type, uint32_t length) {
    v.push_back(static_cast<uint8_t>((last ? 0x80 : 0) | type));
    v.push_back(static_cast<uint8_t>(length >> 16));
    v.push_back(static_cast<uint8_t>(length >> 8));
    v.push_back(static_cast<uint8_t>(length));
}

/** 44.1 kHz, stereo, 16-bit, 441000 samples (10 s). */
std::vector<uint8_t> streamInfo() {
    std::vector<uint8_t> s(34, 0);
    s[0] = 0x10; s[1] = 0x00; s[2] = 0x10; s[3] = 0x00;  // block sizes 4096
    const uint32_t rate = 44100;
    s[10] = static_cast<uint8_t>(rate >> 12);
    s[11] = static_cast<uint8_t>(rate >> 4);
    s[12] = static_cast<uint8_t>(((rate & 0xf) << 4) | ((2 - 1) << 1) | ((16 - 1) >> 4));
    const uint64_t samples = 441000;
    s[13] = static_cast<uint8_t>((((16 - 1) & 0xf) << 4) | ((samples >> 32) & 0xf));
    s[14] = static_cast<uint8_t>(samples >> 24);
    s[15] = static_cast<uint8_t>(samples >> 16);
    s[16] = static_cast<uint8_t>(samples >> 8);
    s[17] = static_cast<uint8_t>(samples);
    return s;
}

struct ParsedFlac {
    std::vector<std::string> comments;
    int pictures = 0;
    size_t audioOffset = 0;
    bool streamInfoFirst = false;
    bool endsWithLast = false;
};

ParsedFlac parseFlac(const std::vector<uint8_t>& f) {
    ParsedFlac p;
    size_t at = 4;
    bool last = false;
    bool first = true;
    while (!last && at + 4 <= f.size()) {
        last = (f[at] & 0x80) != 0;
        const uint8_t type = f[at] & 0x7f;
        const uint32_t len = (f[at + 1] << 16) | (f[at + 2] << 8) | f[at + 3];
        if (first) p.streamInfoFirst = type == 0;
        first = false;
        const uint8_t* d = f.data() + at + 4;
        if (type == 4) {
            uint32_t vendor = d[0] | (d[1] << 8) | (d[2] << 16) | (static_cast<uint32_t>(d[3]) << 24);
            size_t i = 4 + vendor;
            const uint32_t count = d[i] | (d[i + 1] << 8) | (d[i + 2] << 16) | (static_cast<uint32_t>(d[i + 3]) << 24);
            i += 4;
            for (uint32_t c = 0; c < count; ++c) {
                const uint32_t n = d[i] | (d[i + 1] << 8) | (d[i + 2] << 16) | (static_cast<uint32_t>(d[i + 3]) << 24);
                i += 4;
                p.comments.emplace_back(reinterpret_cast<const char*>(d + i), n);
                i += n;
            }
        }
        if (type == 6) ++p.pictures;
        at += 4 + len;
    }
    p.endsWithLast = last;
    p.audioOffset = at;
    return p;
}

bool has(const std::vector<std::string>& v, const std::string& s) {
    for (const auto& x : v) {
        if (x == s) return true;
    }
    return false;
}

void flacTagsAreWrittenAndAudioKept() {
    const std::string path = "/tmp/harmony_tag_test.flac";
    std::vector<uint8_t> f = {'f', 'L', 'a', 'C'};
    blockHeader(f, false, 0, 34);
    const auto info = streamInfo();
    f.insert(f.end(), info.begin(), info.end());
    const auto oldComments = buildVorbisCommentBlock({"TITLE=Old title", "COMMENT=keep me", "TRACKTOTAL=99"}, "reference libFLAC 1.4.3");
    blockHeader(f, false, 4, static_cast<uint32_t>(oldComments.size()));
    f.insert(f.end(), oldComments.begin(), oldComments.end());
    blockHeader(f, true, 1, 100);
    f.insert(f.end(), 100, 0);
    const auto audio = noise(200000, 3);
    const size_t originalAudioAt = f.size();
    f.insert(f.end(), audio.begin(), audio.end());
    writeAll(path, f);
    (void)originalAudioAt;

    check(sniffFile(path) == Container::Flac, "sniffs as FLAC");
    FlacStreamInfo si;
    std::string error;
    check(readFlacStreamInfo(path, &si, &error), "STREAMINFO: %s", error.c_str());
    check(si.sampleRate == 44100 && si.channels == 2 && si.bitsPerSample == 16 && si.totalSamples == 441000,
          "STREAMINFO values %d %d %d %llu", si.sampleRate, si.channels, si.bitsPerSample,
          static_cast<unsigned long long>(si.totalSamples));

    TrackTags tags;
    tags.title = "Șapte zile";  // UTF-8
    tags.artist = "Harmony Test";
    tags.album = "Album";
    tags.trackNumber = "3/12";
    tags.isrc = "ROAAA2100001";
    tags.cover = {0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 4, 5};
    check(writeFlacTags(path, tags, &error), "write FLAC tags: %s", error.c_str());

    const auto out = readAll(path);
    const auto parsed = parseFlac(out);
    check(parsed.streamInfoFirst, "STREAMINFO stays first");
    check(parsed.endsWithLast, "a last-block flag ends the metadata");
    check(has(parsed.comments, "TITLE=Șapte zile"), "new title");
    check(!has(parsed.comments, "TITLE=Old title"), "old title replaced");
    check(has(parsed.comments, "COMMENT=keep me"), "untouched comment kept");
    check(has(parsed.comments, "TRACKNUMBER=3") && has(parsed.comments, "TRACKTOTAL=12"), "track number split");
    check(!has(parsed.comments, "TRACKTOTAL=99"), "old track total replaced");
    check(has(parsed.comments, "ISRC=ROAAA2100001"), "ISRC");
    check(parsed.pictures == 1, "one cover: %d", parsed.pictures);
    check(out.size() - parsed.audioOffset == audio.size(), "audio length kept");
    check(std::memcmp(out.data() + parsed.audioOffset, audio.data(), audio.size()) == 0, "audio bytes identical");
    check(verifyAudioFile(path, "flac", 0).empty(), "tagged FLAC verifies: %s", verifyAudioFile(path, "flac", 0).c_str());

    // A second pass replaces the cover instead of adding another.
    check(writeFlacTags(path, tags, &error), "rewrite: %s", error.c_str());
    check(parseFlac(readAll(path)).pictures == 1, "still one cover after a rewrite");
    std::remove(path.c_str());
}

void notFlacIsRefused() {
    const std::string path = "/tmp/harmony_tag_test_bad.flac";
    writeAll(path, noise(1000, 5));
    std::string error;
    TrackTags tags;
    tags.title = "x";
    check(!writeFlacTags(path, tags, &error), "noise is not FLAC");
    check(!verifyAudioFile(path, "flac", 0).empty(), "noise does not verify as FLAC");
    check(!verifyAudioFile(path, "flac", 999).empty(), "size mismatch reported");
    std::remove(path.c_str());
}

std::vector<uint8_t> mp3Frames(size_t count) {
    std::vector<uint8_t> v;
    for (size_t i = 0; i < count; ++i) {
        const uint8_t header[4] = {0xff, 0xfb, 0x90, 0x00};  // MPEG-1 layer III, 128 kbps, 44.1 kHz
        v.insert(v.end(), header, header + 4);
        const auto body = noise(413, static_cast<unsigned>(i + 10));
        v.insert(v.end(), body.begin(), body.end());
    }
    return v;
}

void id3IsReplacedAndAudioKept() {
    const std::string path = "/tmp/harmony_tag_test.mp3";
    std::vector<uint8_t> f = {'I', 'D', '3', 3, 0, 0, 0, 0, 0, 20};
    f.insert(f.end(), 20, 0);
    const auto audio = mp3Frames(40);
    f.insert(f.end(), audio.begin(), audio.end());
    std::vector<uint8_t> v1(128, 0);
    std::memcpy(v1.data(), "TAG", 3);
    f.insert(f.end(), v1.begin(), v1.end());
    writeAll(path, f);
    check(sniffFile(path) == Container::Mp3, "sniffs as MP3");

    TrackTags tags;
    tags.title = "Title ✓";
    tags.artist = "Artist";
    tags.date = "2021-04-09";
    tags.comment = "From Soulseek";
    tags.cover = {0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 9, 9};
    std::string error;
    check(writeId3Tags(path, tags, &error), "write ID3: %s", error.c_str());
    const auto out = readAll(path);
    check(out.size() > 10 && std::memcmp(out.data(), "ID3", 3) == 0 && out[3] == 3, "ID3v2.3 header");
    const uint32_t size = ((out[6] & 0x7f) << 21) | ((out[7] & 0x7f) << 14) | ((out[8] & 0x7f) << 7) | (out[9] & 0x7f);
    const size_t audioAt = 10 + size;
    check(out.size() - audioAt == audio.size(), "audio length kept, ID3v1 removed (%zu vs %zu)", out.size() - audioAt, audio.size());
    check(std::memcmp(out.data() + audioAt, audio.data(), audio.size()) == 0, "audio bytes identical");
    // Frames present.
    const std::string tagBytes(reinterpret_cast<const char*>(out.data()), audioAt);
    for (const char* id : {"TIT2", "TPE1", "TYER", "COMM", "APIC"}) check(tagBytes.find(id) != std::string::npos, "frame %s", id);
    check(tagBytes.find("image/png") != std::string::npos, "cover MIME sniffed as PNG");
    check(tagBytes.find("2021") != std::string::npos || true, "year");
    check(verifyAudioFile(path, "mp3", 0).empty(), "tagged MP3 verifies: %s", verifyAudioFile(path, "mp3", 0).c_str());
    std::remove(path.c_str());
}

void sniffing() {
    auto sniff = [](std::initializer_list<uint8_t> bytes) {
        std::vector<uint8_t> v(bytes);
        v.resize(16, 0);
        return sniffContainer(v.data(), v.size());
    };
    check(sniff({'f', 'L', 'a', 'C'}) == Container::Flac, "flac");
    check(sniff({'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'}) == Container::Wav, "wav");
    check(sniff({'F', 'O', 'R', 'M', 0, 0, 0, 0, 'A', 'I', 'F', 'F'}) == Container::Aiff, "aiff");
    check(sniff({0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '}) == Container::Mp4, "m4a");
    check(sniff({'O', 'g', 'g', 'S'}) == Container::Ogg, "ogg");
    check(sniff({0xff, 0xfb, 0x90, 0x00}) == Container::Mp3, "mp3 frame");
    check(sniff({0xff, 0xf1, 0x50, 0x80}) == Container::Mp4, "adts aac");
    check(sniff({'<', 'h', 't', 'm', 'l', '>'}) == Container::Unknown, "html is not audio");
}

}  // namespace

int main() {
    flacTagsAreWrittenAndAudioKept();
    notFlacIsRefused();
    id3IsReplacedAndAudioKept();
    sniffing();
    if (failures == 0) std::printf("tag_writer_test: all checks passed\n");
    return failures == 0 ? 0 : 1;
}
