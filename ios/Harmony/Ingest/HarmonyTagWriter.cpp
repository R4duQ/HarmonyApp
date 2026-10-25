#include "HarmonyTagWriter.h"

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <memory>
#include <set>

namespace harmony::ingest {

namespace {

constexpr uint8_t kFlacStreamInfo = 0;
constexpr uint8_t kFlacPadding = 1;
constexpr uint8_t kFlacVorbisComment = 4;
constexpr uint8_t kFlacPicture = 6;
constexpr uint32_t kFlacMaxBlock = (1u << 24) - 1;
constexpr size_t kCopyChunk = 1 << 20;
constexpr size_t kFlacPaddingBytes = 4096;
constexpr size_t kId3PaddingBytes = 1024;

struct FileCloser {
    void operator()(FILE* f) const {
        if (f) std::fclose(f);
    }
};
using File = std::unique_ptr<FILE, FileCloser>;

File openFile(const std::string& path, const char* mode) { return File(std::fopen(path.c_str(), mode)); }

bool readExact(FILE* f, void* out, size_t n) { return std::fread(out, 1, n, f) == n; }

uint64_t fileSize(FILE* f) {
    const long here = std::ftell(f);
    std::fseek(f, 0, SEEK_END);
    const long end = std::ftell(f);
    std::fseek(f, here, SEEK_SET);
    return end < 0 ? 0 : static_cast<uint64_t>(end);
}

void putBe32(std::vector<uint8_t>& v, uint32_t x) {
    v.push_back(static_cast<uint8_t>(x >> 24));
    v.push_back(static_cast<uint8_t>(x >> 16));
    v.push_back(static_cast<uint8_t>(x >> 8));
    v.push_back(static_cast<uint8_t>(x));
}

void putLe32(std::vector<uint8_t>& v, uint32_t x) {
    v.push_back(static_cast<uint8_t>(x));
    v.push_back(static_cast<uint8_t>(x >> 8));
    v.push_back(static_cast<uint8_t>(x >> 16));
    v.push_back(static_cast<uint8_t>(x >> 24));
}

uint32_t le32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) | (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}

uint32_t be32(const uint8_t* p) {
    return (static_cast<uint32_t>(p[0]) << 24) | (static_cast<uint32_t>(p[1]) << 16) |
           (static_cast<uint32_t>(p[2]) << 8) | static_cast<uint32_t>(p[3]);
}

uint32_t syncsafe(const uint8_t* p) {
    return (static_cast<uint32_t>(p[0] & 0x7f) << 21) | (static_cast<uint32_t>(p[1] & 0x7f) << 14) |
           (static_cast<uint32_t>(p[2] & 0x7f) << 7) | static_cast<uint32_t>(p[3] & 0x7f);
}

std::string lower(std::string s) {
    for (char& c : s) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return s;
}

std::string upper(std::string s) {
    for (char& c : s) c = static_cast<char>(std::toupper(static_cast<unsigned char>(c)));
    return s;
}

/** Size of an ID3v2 tag at the start of [head] (0 when there is none). */
uint64_t id3v2Size(const uint8_t* head, size_t size) {
    if (size < 10 || std::memcmp(head, "ID3", 3) != 0) return 0;
    if (head[3] == 0xff || head[4] == 0xff) return 0;
    for (int i = 6; i < 10; ++i) {
        if (head[i] & 0x80) return 0;
    }
    const bool footer = (head[5] & 0x10) != 0;
    return 10 + static_cast<uint64_t>(syncsafe(head + 6)) + (footer ? 10 : 0);
}

bool isMpegSync(const uint8_t* p) {
    if (p[0] != 0xff || (p[1] & 0xe0) != 0xe0) return false;
    const int version = (p[1] >> 3) & 3;  // 1 is reserved
    const int layer = (p[1] >> 1) & 3;    // 0 is reserved (ADTS AAC uses it)
    const int bitrate = (p[2] >> 4) & 0xf;
    const int rate = (p[2] >> 2) & 3;
    return version != 1 && layer != 0 && bitrate != 0xf && rate != 3;
}

bool isAdtsSync(const uint8_t* p) { return p[0] == 0xff && (p[1] & 0xf6) == 0xf0; }

/** UTF-8 to UTF-16LE with a byte-order mark (ID3v2.3 text encoding 1). */
std::vector<uint8_t> utf16WithBom(const std::string& text) {
    std::vector<uint8_t> out = {0xff, 0xfe};
    auto put = [&](uint32_t unit) {
        out.push_back(static_cast<uint8_t>(unit & 0xff));
        out.push_back(static_cast<uint8_t>(unit >> 8));
    };
    size_t i = 0;
    while (i < text.size()) {
        const auto c = static_cast<unsigned char>(text[i]);
        uint32_t cp = 0xfffd;
        size_t len = 1;
        if (c < 0x80) {
            cp = c;
        } else if ((c & 0xe0) == 0xc0 && i + 1 < text.size()) {
            cp = ((c & 0x1fu) << 6) | (static_cast<unsigned char>(text[i + 1]) & 0x3fu);
            len = 2;
        } else if ((c & 0xf0) == 0xe0 && i + 2 < text.size()) {
            cp = ((c & 0x0fu) << 12) | ((static_cast<unsigned char>(text[i + 1]) & 0x3fu) << 6) |
                 (static_cast<unsigned char>(text[i + 2]) & 0x3fu);
            len = 3;
        } else if ((c & 0xf8) == 0xf0 && i + 3 < text.size()) {
            cp = ((c & 0x07u) << 18) | ((static_cast<unsigned char>(text[i + 1]) & 0x3fu) << 12) |
                 ((static_cast<unsigned char>(text[i + 2]) & 0x3fu) << 6) |
                 (static_cast<unsigned char>(text[i + 3]) & 0x3fu);
            len = 4;
        }
        i += len;
        if (cp >= 0x10000 && cp <= 0x10ffff) {
            cp -= 0x10000;
            put(0xd800 + (cp >> 10));
            put(0xdc00 + (cp & 0x3ff));
        } else {
            put(cp > 0xffff ? 0xfffd : cp);
        }
    }
    return out;
}

/** Copies [from, to) of [in] to [out]. */
bool copyRange(FILE* in, FILE* out, uint64_t from, uint64_t to) {
    if (std::fseek(in, static_cast<long>(from), SEEK_SET) != 0) return false;
    std::vector<uint8_t> buffer(kCopyChunk);
    uint64_t remaining = to - from;
    while (remaining > 0) {
        const size_t want = static_cast<size_t>(std::min<uint64_t>(remaining, buffer.size()));
        const size_t got = std::fread(buffer.data(), 1, want, in);
        if (got == 0) return false;
        if (std::fwrite(buffer.data(), 1, got, out) != got) return false;
        remaining -= got;
    }
    return true;
}

bool replaceFile(const std::string& temp, const std::string& path, std::string* error) {
    if (std::rename(temp.c_str(), path.c_str()) != 0) {
        std::remove(temp.c_str());
        if (error) *error = "Could not replace the file";
        return false;
    }
    return true;
}

void setError(std::string* error, const char* message) {
    if (error) *error = message;
}

/** The comments [tags] sets, as "KEY=value". */
std::vector<std::string> vorbisFields(const TrackTags& tags) {
    std::vector<std::string> fields;
    auto add = [&](const char* key, const std::string& value) {
        if (!value.empty()) fields.push_back(std::string(key) + "=" + value);
    };
    add("TITLE", tags.title);
    add("ARTIST", tags.artist);
    add("ALBUM", tags.album);
    add("ALBUMARTIST", tags.albumArtist);
    const auto slash = tags.trackNumber.find('/');
    if (slash != std::string::npos) {
        add("TRACKNUMBER", tags.trackNumber.substr(0, slash));
        add("TRACKTOTAL", tags.trackNumber.substr(slash + 1));
    } else {
        add("TRACKNUMBER", tags.trackNumber);
    }
    const auto discSlash = tags.discNumber.find('/');
    if (discSlash != std::string::npos) {
        add("DISCNUMBER", tags.discNumber.substr(0, discSlash));
        add("DISCTOTAL", tags.discNumber.substr(discSlash + 1));
    } else {
        add("DISCNUMBER", tags.discNumber);
    }
    add("DATE", tags.date);
    add("GENRE", tags.genre);
    add("ISRC", tags.isrc);
    add("COMMENT", tags.comment);
    return fields;
}

}  // namespace

const char* containerExtension(Container container) {
    switch (container) {
        case Container::Flac: return "flac";
        case Container::Mp3: return "mp3";
        case Container::Mp4: return "m4a";
        case Container::Wav: return "wav";
        case Container::Aiff: return "aiff";
        case Container::Ogg: return "ogg";
        case Container::Caf: return "caf";
        case Container::Unknown: break;
    }
    return "";
}

Container sniffContainer(const uint8_t* head, size_t size) {
    if (head == nullptr || size < 4) return Container::Unknown;
    if (std::memcmp(head, "fLaC", 4) == 0) return Container::Flac;
    if (std::memcmp(head, "OggS", 4) == 0) return Container::Ogg;
    if (std::memcmp(head, "caff", 4) == 0) return Container::Caf;
    if (size >= 12 && std::memcmp(head, "RIFF", 4) == 0 && std::memcmp(head + 8, "WAVE", 4) == 0) return Container::Wav;
    if (size >= 12 && std::memcmp(head, "FORM", 4) == 0 &&
        (std::memcmp(head + 8, "AIFF", 4) == 0 || std::memcmp(head + 8, "AIFC", 4) == 0)) {
        return Container::Aiff;
    }
    if (size >= 8 && std::memcmp(head + 4, "ftyp", 4) == 0) return Container::Mp4;
    if (size >= 3 && std::memcmp(head, "ID3", 3) == 0) return Container::Mp3;  // sniffFile looks past it
    if (size >= 3 && isMpegSync(head)) return Container::Mp3;
    if (size >= 3 && isAdtsSync(head)) return Container::Mp4;  // raw AAC, read like M4A
    return Container::Unknown;
}

Container sniffFile(const std::string& path) {
    File f = openFile(path, "rb");
    if (!f) return Container::Unknown;
    uint8_t head[16] = {};
    const size_t got = std::fread(head, 1, sizeof head, f.get());
    const uint64_t tag = id3v2Size(head, got);
    if (tag == 0) return sniffContainer(head, got);
    // Something after an ID3v2 tag: usually MP3, sometimes a FLAC a tagger mistreated.
    if (std::fseek(f.get(), static_cast<long>(tag), SEEK_SET) != 0) return Container::Unknown;
    uint8_t after[16] = {};
    const size_t more = std::fread(after, 1, sizeof after, f.get());
    if (more >= 4 && std::memcmp(after, "fLaC", 4) == 0) return Container::Flac;
    return Container::Mp3;
}

std::string sniffImageMime(const std::vector<uint8_t>& image) {
    if (image.size() >= 3 && image[0] == 0xff && image[1] == 0xd8 && image[2] == 0xff) return "image/jpeg";
    if (image.size() >= 8 && std::memcmp(image.data(), "\x89PNG\r\n\x1a\n", 8) == 0) return "image/png";
    return "image/jpeg";
}

bool readFlacStreamInfo(const std::string& path, FlacStreamInfo* out, std::string* error) {
    File f = openFile(path, "rb");
    if (!f) return setError(error, "Could not open the file"), false;
    uint8_t head[10] = {};
    if (!readExact(f.get(), head, sizeof head)) return setError(error, "The file is too short"), false;
    const uint64_t skip = id3v2Size(head, sizeof head);
    std::fseek(f.get(), static_cast<long>(skip), SEEK_SET);
    uint8_t magic[4];
    if (!readExact(f.get(), magic, 4) || std::memcmp(magic, "fLaC", 4) != 0) {
        return setError(error, "Not a FLAC file"), false;
    }
    uint8_t header[4];
    if (!readExact(f.get(), header, 4) || (header[0] & 0x7f) != kFlacStreamInfo) {
        return setError(error, "The FLAC file has no STREAMINFO"), false;
    }
    const uint32_t length = (static_cast<uint32_t>(header[1]) << 16) | (static_cast<uint32_t>(header[2]) << 8) | header[3];
    uint8_t info[34];
    if (length < 34 || !readExact(f.get(), info, 34)) return setError(error, "The STREAMINFO is truncated"), false;
    FlacStreamInfo s;
    s.sampleRate = static_cast<int>((static_cast<uint32_t>(info[10]) << 12) | (static_cast<uint32_t>(info[11]) << 4) | (info[12] >> 4));
    s.channels = ((info[12] >> 1) & 0x7) + 1;
    s.bitsPerSample = (((info[12] & 1) << 4) | (info[13] >> 4)) + 1;
    s.totalSamples = (static_cast<uint64_t>(info[13] & 0xf) << 32) | be32(info + 14);
    if (out) *out = s;
    return true;
}

std::vector<uint8_t> buildVorbisCommentBlock(const std::vector<std::string>& comments, const std::string& vendor) {
    std::vector<uint8_t> v;
    putLe32(v, static_cast<uint32_t>(vendor.size()));
    v.insert(v.end(), vendor.begin(), vendor.end());
    putLe32(v, static_cast<uint32_t>(comments.size()));
    for (const auto& c : comments) {
        putLe32(v, static_cast<uint32_t>(c.size()));
        v.insert(v.end(), c.begin(), c.end());
    }
    return v;
}

std::vector<uint8_t> buildFlacPictureBlock(const std::vector<uint8_t>& image, const std::string& mime) {
    std::vector<uint8_t> v;
    putBe32(v, 3);  // front cover
    const std::string type = mime.empty() ? sniffImageMime(image) : mime;
    putBe32(v, static_cast<uint32_t>(type.size()));
    v.insert(v.end(), type.begin(), type.end());
    putBe32(v, 0);  // no description
    putBe32(v, 0);  // width, height, depth and colours: unknown (allowed)
    putBe32(v, 0);
    putBe32(v, 0);
    putBe32(v, 0);
    putBe32(v, static_cast<uint32_t>(image.size()));
    v.insert(v.end(), image.begin(), image.end());
    return v;
}

bool writeFlacTags(const std::string& path, const TrackTags& tags, std::string* error) {
    File in = openFile(path, "rb");
    if (!in) return setError(error, "Could not open the file"), false;
    const uint64_t total = fileSize(in.get());
    uint8_t head[10] = {};
    if (!readExact(in.get(), head, sizeof head)) return setError(error, "The file is too short"), false;
    const uint64_t start = id3v2Size(head, sizeof head);  // a stray ID3v2 tag is dropped
    std::fseek(in.get(), static_cast<long>(start), SEEK_SET);
    uint8_t magic[4];
    if (!readExact(in.get(), magic, 4) || std::memcmp(magic, "fLaC", 4) != 0) {
        return setError(error, "Not a FLAC file"), false;
    }

    struct Block {
        uint8_t type;
        std::vector<uint8_t> data;
    };
    std::vector<Block> kept;
    std::vector<Block> pictures;
    std::vector<std::string> comments;
    std::string vendor = "Harmony";
    bool sawStreamInfo = false;
    bool last = false;
    while (!last) {
        uint8_t header[4];
        if (!readExact(in.get(), header, 4)) return setError(error, "The FLAC metadata is truncated"), false;
        last = (header[0] & 0x80) != 0;
        const uint8_t type = header[0] & 0x7f;
        const uint32_t length = (static_cast<uint32_t>(header[1]) << 16) | (static_cast<uint32_t>(header[2]) << 8) | header[3];
        if (static_cast<uint64_t>(std::ftell(in.get())) + length > total) {
            return setError(error, "A FLAC metadata block runs past the end"), false;
        }
        std::vector<uint8_t> data(length);
        if (length > 0 && !readExact(in.get(), data.data(), length)) {
            return setError(error, "The FLAC metadata is truncated"), false;
        }
        if (type == kFlacStreamInfo) {
            sawStreamInfo = true;
            kept.insert(kept.begin(), {type, std::move(data)});
        } else if (type == kFlacVorbisComment) {
            // Parse the existing comments, tolerating junk at the end.
            if (data.size() >= 8) {
                const uint32_t vendorLength = le32(data.data());
                if (4ull + vendorLength + 4 <= data.size()) {
                    vendor.assign(reinterpret_cast<const char*>(data.data() + 4), vendorLength);
                    size_t at = 4 + vendorLength;
                    const uint32_t count = le32(data.data() + at);
                    at += 4;
                    for (uint32_t i = 0; i < count && at + 4 <= data.size(); ++i) {
                        const uint32_t n = le32(data.data() + at);
                        at += 4;
                        if (at + n > data.size()) break;
                        comments.emplace_back(reinterpret_cast<const char*>(data.data() + at), n);
                        at += n;
                    }
                }
            }
        } else if (type == kFlacPicture) {
            const bool frontCover = data.size() >= 4 && be32(data.data()) == 3;
            if (!(frontCover && !tags.cover.empty())) pictures.push_back({type, std::move(data)});
        } else if (type == kFlacPadding) {
            // Dropped: fresh padding goes at the end.
        } else {
            kept.push_back({type, std::move(data)});
        }
    }
    if (!sawStreamInfo) return setError(error, "The FLAC file has no STREAMINFO"), false;
    const uint64_t audioStart = static_cast<uint64_t>(std::ftell(in.get()));

    // Merge: the fields being set replace the old ones of the same name.
    const std::vector<std::string> fresh = vorbisFields(tags);
    std::set<std::string> replaced;
    for (const auto& field : fresh) replaced.insert(upper(field.substr(0, field.find('='))));
    if (!tags.trackNumber.empty()) replaced.insert("TRACKTOTAL");
    if (!tags.discNumber.empty()) replaced.insert("DISCTOTAL");
    std::vector<std::string> merged;
    for (const auto& c : comments) {
        const auto eq = c.find('=');
        if (eq == std::string::npos) continue;
        if (replaced.count(upper(c.substr(0, eq)))) continue;
        merged.push_back(c);
    }
    merged.insert(merged.end(), fresh.begin(), fresh.end());
    kept.push_back({kFlacVorbisComment, buildVorbisCommentBlock(merged, vendor)});
    if (!tags.cover.empty()) {
        auto picture = buildFlacPictureBlock(tags.cover, tags.coverMime);
        if (picture.size() <= kFlacMaxBlock) kept.push_back({kFlacPicture, std::move(picture)});
    }
    for (auto& p : pictures) kept.push_back(std::move(p));
    kept.push_back({kFlacPadding, std::vector<uint8_t>(kFlacPaddingBytes, 0)});

    const std::string temp = path + ".tagtmp";
    {
        File out = openFile(temp, "wb");
        if (!out) return setError(error, "Could not write next to the file"), false;
        bool ok = std::fwrite("fLaC", 1, 4, out.get()) == 4;
        for (size_t i = 0; ok && i < kept.size(); ++i) {
            const auto& b = kept[i];
            if (b.data.size() > kFlacMaxBlock) continue;
            const bool isLast = i + 1 == kept.size();
            const uint8_t header[4] = {static_cast<uint8_t>((isLast ? 0x80 : 0) | b.type),
                                       static_cast<uint8_t>(b.data.size() >> 16),
                                       static_cast<uint8_t>(b.data.size() >> 8), static_cast<uint8_t>(b.data.size())};
            ok = std::fwrite(header, 1, 4, out.get()) == 4 &&
                 (b.data.empty() || std::fwrite(b.data.data(), 1, b.data.size(), out.get()) == b.data.size());
        }
        ok = ok && copyRange(in.get(), out.get(), audioStart, total);
        ok = ok && std::fflush(out.get()) == 0;
        if (!ok) {
            out.reset();
            std::remove(temp.c_str());
            return setError(error, "Could not write the tagged file"), false;
        }
    }
    in.reset();
    return replaceFile(temp, path, error);
}

std::vector<uint8_t> buildId3v23Tag(const TrackTags& tags, size_t padding) {
    std::vector<uint8_t> frames;
    auto frame = [&](const char* id, const std::vector<uint8_t>& body) {
        frames.insert(frames.end(), id, id + 4);
        putBe32(frames, static_cast<uint32_t>(body.size()));
        frames.push_back(0);
        frames.push_back(0);
        frames.insert(frames.end(), body.begin(), body.end());
    };
    auto text = [&](const char* id, const std::string& value) {
        if (value.empty()) return;
        std::vector<uint8_t> body = {1};  // UTF-16 with BOM
        const auto utf16 = utf16WithBom(value);
        body.insert(body.end(), utf16.begin(), utf16.end());
        frame(id, body);
    };
    text("TIT2", tags.title);
    text("TPE1", tags.artist);
    text("TALB", tags.album);
    text("TPE2", tags.albumArtist);
    text("TRCK", tags.trackNumber);
    text("TPOS", tags.discNumber);
    text("TYER", tags.date.substr(0, std::min<size_t>(4, tags.date.size())));
    text("TCON", tags.genre);
    text("TSRC", tags.isrc);
    if (!tags.comment.empty()) {
        std::vector<uint8_t> body = {1, 'e', 'n', 'g', 0xff, 0xfe, 0, 0};  // empty description
        const auto utf16 = utf16WithBom(tags.comment);
        body.insert(body.end(), utf16.begin(), utf16.end());
        frame("COMM", body);
    }
    if (!tags.cover.empty()) {
        const std::string mime = tags.coverMime.empty() ? sniffImageMime(tags.cover) : tags.coverMime;
        std::vector<uint8_t> body = {0};  // ISO-8859-1
        body.insert(body.end(), mime.begin(), mime.end());
        body.push_back(0);
        body.push_back(3);  // front cover
        body.push_back(0);  // empty description
        body.insert(body.end(), tags.cover.begin(), tags.cover.end());
        frame("APIC", body);
    }
    const uint32_t size = static_cast<uint32_t>(frames.size() + padding);
    std::vector<uint8_t> tag = {'I', 'D', '3', 3, 0, 0,
                                static_cast<uint8_t>((size >> 21) & 0x7f), static_cast<uint8_t>((size >> 14) & 0x7f),
                                static_cast<uint8_t>((size >> 7) & 0x7f), static_cast<uint8_t>(size & 0x7f)};
    tag.insert(tag.end(), frames.begin(), frames.end());
    tag.insert(tag.end(), padding, 0);
    return tag;
}

bool writeId3Tags(const std::string& path, const TrackTags& tags, std::string* error) {
    File in = openFile(path, "rb");
    if (!in) return setError(error, "Could not open the file"), false;
    const uint64_t total = fileSize(in.get());
    uint8_t head[10] = {};
    const size_t got = std::fread(head, 1, sizeof head, in.get());
    uint64_t start = id3v2Size(head, got);
    if (start > total) return setError(error, "The ID3 tag claims to be larger than the file"), false;
    uint64_t end = total;
    if (total >= start + 128) {
        uint8_t tail[3];
        std::fseek(in.get(), static_cast<long>(total - 128), SEEK_SET);
        if (readExact(in.get(), tail, 3) && std::memcmp(tail, "TAG", 3) == 0) end = total - 128;  // old ID3v1
    }
    const auto tag = buildId3v23Tag(tags, kId3PaddingBytes);
    const std::string temp = path + ".tagtmp";
    {
        File out = openFile(temp, "wb");
        if (!out) return setError(error, "Could not write next to the file"), false;
        bool ok = std::fwrite(tag.data(), 1, tag.size(), out.get()) == tag.size();
        ok = ok && copyRange(in.get(), out.get(), start, end);
        ok = ok && std::fflush(out.get()) == 0;
        if (!ok) {
            out.reset();
            std::remove(temp.c_str());
            return setError(error, "Could not write the tagged file"), false;
        }
    }
    in.reset();
    return replaceFile(temp, path, error);
}

std::string verifyAudioFile(const std::string& path, const std::string& expectedExtension, uint64_t expectedSize) {
    File f = openFile(path, "rb");
    if (!f) return "The downloaded file is missing";
    const uint64_t size = fileSize(f.get());
    if (expectedSize > 0 && size != expectedSize) {
        return "The file is " + std::to_string(size) + " bytes, " + std::to_string(expectedSize) + " were expected";
    }
    f.reset();
    const std::string ext = lower(expectedExtension);
    const Container found = sniffFile(path);
    if (ext == "flac") {
        if (size < 42) return "Too short to be a FLAC file";
        if (found != Container::Flac) return "The file is not FLAC";
        FlacStreamInfo info;
        std::string error;
        if (!readFlacStreamInfo(path, &info, &error)) return error;
        if (info.sampleRate <= 0 || info.sampleRate > 655350 || info.channels < 1 || info.bitsPerSample < 4) {
            return "The FLAC STREAMINFO is not valid";
        }
        return "";
    }
    if (ext == "mp3") {
        if (size < 128) return "Too short to be an MP3 file";
        if (found != Container::Mp3) return "The file is not MP3";
        // An MPEG frame must follow the tag within the first 64 KB.
        File g = openFile(path, "rb");
        uint8_t head[10] = {};
        const size_t got = std::fread(head, 1, sizeof head, g.get());
        const uint64_t start = id3v2Size(head, got);
        std::fseek(g.get(), static_cast<long>(start), SEEK_SET);
        std::vector<uint8_t> buffer(64 * 1024);
        const size_t n = std::fread(buffer.data(), 1, buffer.size(), g.get());
        for (size_t i = 0; i + 4 <= n; ++i) {
            if (isMpegSync(buffer.data() + i)) return "";
        }
        return "No MPEG audio frames found";
    }
    const bool matches = (ext == "m4a" || ext == "mp4" || ext == "aac" || ext == "alac") ? found == Container::Mp4
                         : ext == "wav"                                                   ? found == Container::Wav
                         : (ext == "aiff" || ext == "aif")                                ? found == Container::Aiff
                         : (ext == "ogg" || ext == "opus" || ext == "oga")                ? found == Container::Ogg
                         : ext == "caf"                                                   ? found == Container::Caf
                                                                                          : found != Container::Unknown;
    if (!matches) return ext.empty() ? std::string("Not an audio file") : "The file is not " + ext;
    return "";
}

}  // namespace harmony::ingest
