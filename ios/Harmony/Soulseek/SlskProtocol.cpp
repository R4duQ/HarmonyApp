#include "SlskProtocol.h"

#include <zlib.h>

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstring>
#include <regex>
#include <set>
#include <unordered_set>

namespace harmony::slsk {

namespace {

constexpr size_t kMaxStringBytes = 2 * 1024 * 1024;
constexpr uint32_t kMaxFilesPerResponse = 10000;
constexpr uint32_t kMaxAttributes = 32;
constexpr size_t kMaxInflatedSearchBytes = 64 * 1024 * 1024;

// ASCII folding of U+00C0..U+024F (Latin-1 Supplement and Latin Extended-A/B):
// the NFKD base letter without its marks, plus the ligatures and strokes NFKD
// leaves alone (ß, æ, ø, ł, đ, œ, þ). Empty: not a letter worth keeping.
const char* const kFold[0x250 - 0xC0] = {
    "A", "A", "A", "A", "A", "A", "AE", "C", "E", "E", "E", "E", "I", "I", "I", "I",
    "D", "N", "O", "O", "O", "O", "O", "", "O", "U", "U", "U", "U", "Y", "TH", "ss",
    "a", "a", "a", "a", "a", "a", "ae", "c", "e", "e", "e", "e", "i", "i", "i", "i",
    "d", "n", "o", "o", "o", "o", "o", "", "o", "u", "u", "u", "u", "y", "th", "y",
    "A", "a", "A", "a", "A", "a", "C", "c", "C", "c", "C", "c", "C", "c", "D", "d",
    "D", "d", "E", "e", "E", "e", "E", "e", "E", "e", "E", "e", "G", "g", "G", "g",
    "G", "g", "G", "g", "H", "h", "H", "h", "I", "i", "I", "i", "I", "i", "I", "i",
    "I", "i", "IJ", "ij", "J", "j", "K", "k", "", "L", "l", "L", "l", "L", "l", "",
    "", "L", "l", "N", "n", "N", "n", "N", "n", "", "", "", "O", "o", "O", "o",
    "O", "o", "OE", "oe", "R", "r", "R", "r", "R", "r", "S", "s", "S", "s", "S", "s",
    "S", "s", "T", "t", "T", "t", "T", "t", "U", "u", "U", "u", "U", "u", "U", "u",
    "U", "u", "U", "u", "W", "w", "Y", "y", "Y", "Z", "z", "Z", "z", "Z", "z", "s",
    "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
    "", "", "f", "", "", "", "", "", "", "", "", "", "", "", "", "",
    "O", "o", "", "", "", "", "", "", "", "", "", "", "", "", "", "U",
    "u", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
    "", "", "", "", "DZ", "Dz", "dz", "LJ", "Lj", "lj", "NJ", "Nj", "nj", "A", "a", "I",
    "i", "O", "o", "U", "u", "U", "u", "U", "u", "U", "u", "U", "u", "", "A", "a",
    "A", "a", "", "", "", "", "G", "g", "K", "k", "O", "o", "O", "o", "", "",
    "j", "DZ", "Dz", "dz", "G", "g", "", "", "N", "n", "A", "a", "", "", "", "",
    "A", "a", "A", "a", "E", "e", "E", "e", "I", "i", "I", "i", "O", "o", "O", "o",
    "R", "r", "R", "r", "U", "u", "U", "u", "S", "s", "T", "t", "", "", "H", "h",
    "", "", "", "", "", "", "A", "a", "E", "e", "O", "o", "O", "o", "O", "o",
    "O", "o", "Y", "y", "", "", "", "", "", "", "", "", "", "", "", "",
    "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
};

/** Decodes one UTF-8 code point at [i]; invalid bytes come back as U+FFFD. */
uint32_t nextCodePoint(const std::string& s, size_t& i) {
    const auto c = static_cast<unsigned char>(s[i]);
    auto cont = [&](size_t k) { return i + k < s.size() && (static_cast<unsigned char>(s[i + k]) & 0xc0) == 0x80; };
    auto bits = [&](size_t k) { return static_cast<uint32_t>(static_cast<unsigned char>(s[i + k]) & 0x3f); };
    if (c < 0x80) {
        i += 1;
        return c;
    }
    if ((c & 0xe0) == 0xc0 && cont(1)) {
        const uint32_t cp = ((c & 0x1fu) << 6) | bits(1);
        i += 2;
        return cp;
    }
    if ((c & 0xf0) == 0xe0 && cont(1) && cont(2)) {
        const uint32_t cp = ((c & 0x0fu) << 12) | (bits(1) << 6) | bits(2);
        i += 3;
        return cp;
    }
    if ((c & 0xf8) == 0xf0 && cont(1) && cont(2) && cont(3)) {
        const uint32_t cp = ((c & 0x07u) << 18) | (bits(1) << 12) | (bits(2) << 6) | bits(3);
        i += 4;
        return cp;
    }
    i += 1;
    // Not UTF-8: read the byte as Latin-1, as older Windows clients send.
    return c;
}

/** Folds diacritics but keeps case, punctuation and anything unknown as it was. */
std::string foldDiacritics(const std::string& s) {
    std::string out;
    size_t i = 0;
    while (i < s.size()) {
        const size_t start = i;
        const uint32_t cp = nextCodePoint(s, i);
        if (cp >= 0xC0 && cp < 0x250 && kFold[cp - 0xC0][0] != '\0') {
            out += kFold[cp - 0xC0];
        } else {
            out.append(s, start, i - start);
        }
    }
    return out;
}

std::string lowerAscii(std::string s) {
    for (char& c : s) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return s;
}

std::string collapseSpaces(const std::string& s) {
    std::string out;
    bool space = false;
    for (char c : s) {
        if (std::isspace(static_cast<unsigned char>(c))) {
            space = !out.empty();
        } else {
            if (space) out.push_back(' ');
            space = false;
            out.push_back(c);
        }
    }
    return out;
}

const std::unordered_set<std::string>& stopWords() {
    static const std::unordered_set<std::string> words = {"the",      "and",   "feat", "ft",    "featuring", "official",
                                                          "audio",    "video", "lyrics", "lyric", "music",   "track"};
    return words;
}

double weightedCoverage(const std::vector<std::string>& tokens, const std::string& haystack) {
    if (tokens.empty()) return 0.0;
    double total = 0.0;
    double matched = 0.0;
    for (const auto& token : tokens) {
        const double weight = 1.0 + std::min<size_t>(token.size(), 10) / 10.0;
        total += weight;
        if (haystack.find(token) != std::string::npos) matched += weight;
    }
    return total <= 0.0 ? 0.0 : matched / total;
}

/** "Artist - Title" (hyphen, en or em dash between spaces). */
bool splitArtistTitle(const std::string& value, std::string* artist, std::string* title) {
    static const char* const kDashes[] = {" - ", " \xE2\x80\x93 ", " \xE2\x80\x94 "};
    size_t best = std::string::npos;
    size_t width = 0;
    for (const char* dash : kDashes) {
        const size_t at = value.find(dash);
        if (at != std::string::npos && (best == std::string::npos || at < best)) {
            best = at;
            width = std::strlen(dash);
        }
    }
    if (best == std::string::npos) return false;
    std::string a = collapseSpaces(value.substr(0, best));
    std::string t = collapseSpaces(value.substr(best + width));
    if (a.size() < 2 || t.size() < 2) return false;
    *artist = a;
    *title = t;
    return true;
}

bool inferArtistTitle(const std::string& value, std::string* artist, std::string* title) {
    std::vector<std::string> words;
    std::string word;
    for (char c : value + " ") {
        if (std::isspace(static_cast<unsigned char>(c))) {
            if (!word.empty()) words.push_back(word);
            word.clear();
        } else {
            word.push_back(c);
        }
    }
    if (words.size() < 4) return false;
    const std::string lead = lowerAscii(words.front());
    if (!stopWords().count(lead)) return false;
    size_t cut = 0;
    for (size_t i = 2; i < words.size(); ++i) {
        if (lowerAscii(words[i]) == lead) {
            cut = i;
            break;
        }
    }
    if (cut == 0) return false;
    std::string a, t;
    for (size_t i = 0; i < words.size(); ++i) (i < cut ? a : t) += (i == 0 || i == cut ? "" : " ") + words[i];
    if (a.size() < 2 || t.size() < 2) return false;
    *artist = a;
    *title = t;
    return true;
}

std::string stripSearchNoise(const std::string& value) {
    static const std::regex bracketed(
        R"(\[(?:official\s+)?(?:audio|video|lyrics?|lyric\s+video|music\s+video|visuali[sz]er|4k|hd)\])",
        std::regex::icase);
    static const std::regex parenthesised(
        R"(\((?:official\s+)?(?:audio|video|lyrics?|lyric\s+video|music\s+video|visuali[sz]er|4k|hd)\))",
        std::regex::icase);
    static const std::regex bare(R"(\b(?:official\s+audio|official\s+video|lyric\s+video|music\s+video)\b)",
                                 std::regex::icase);
    std::string s = std::regex_replace(value, bracketed, " ");
    s = std::regex_replace(s, parenthesised, " ");
    s = std::regex_replace(s, bare, " ");
    return collapseSpaces(s);
}

std::string stripTrackNumber(const std::string& value) {
    static const std::regex number(R"(^\s*(?:cd\s*\d+\s*)?\d{1,3}\s*[-._)]\s*)", std::regex::icase);
    std::string s = std::regex_replace(value, number, "", std::regex_constants::format_first_only);
    const size_t dot = s.rfind('.');
    return dot == std::string::npos ? s : s.substr(0, dot);
}

std::string extensionOf(const std::string& filename) {
    const std::string leaf = leafOf(filename);
    const size_t dot = leaf.rfind('.');
    return dot == std::string::npos ? std::string() : leaf.substr(dot + 1);
}

// ---- MD5 (RFC 1321) --------------------------------------------------------

struct Md5 {
    uint32_t a = 0x67452301, b = 0xefcdab89, c = 0x98badcfe, d = 0x10325476;

    static uint32_t rotl(uint32_t x, int n) { return (x << n) | (x >> (32 - n)); }

    void block(const uint8_t* p) {
        static const uint32_t k[64] = {
            0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
            0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be, 0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
            0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
            0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
            0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c, 0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
            0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
            0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
            0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1, 0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391};
        static const int r[64] = {7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
                                  5, 9,  14, 20, 5, 9,  14, 20, 5, 9,  14, 20, 5, 9,  14, 20,
                                  4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
                                  6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21};
        uint32_t w[16];
        for (int i = 0; i < 16; ++i) {
            w[i] = static_cast<uint32_t>(p[i * 4]) | (static_cast<uint32_t>(p[i * 4 + 1]) << 8) |
                   (static_cast<uint32_t>(p[i * 4 + 2]) << 16) | (static_cast<uint32_t>(p[i * 4 + 3]) << 24);
        }
        uint32_t aa = a, bb = b, cc = c, dd = d;
        for (int i = 0; i < 64; ++i) {
            uint32_t f;
            int g;
            if (i < 16) {
                f = (bb & cc) | (~bb & dd);
                g = i;
            } else if (i < 32) {
                f = (dd & bb) | (~dd & cc);
                g = (5 * i + 1) % 16;
            } else if (i < 48) {
                f = bb ^ cc ^ dd;
                g = (3 * i + 5) % 16;
            } else {
                f = cc ^ (bb | ~dd);
                g = (7 * i) % 16;
            }
            const uint32_t tmp = dd;
            dd = cc;
            cc = bb;
            bb = bb + rotl(aa + f + k[i] + w[g], r[i]);
            aa = tmp;
        }
        a += aa;
        b += bb;
        c += cc;
        d += dd;
    }
};

}  // namespace

// ---- Writer / Reader ----------------------------------------------------------

Writer& Writer::u8(uint8_t v) {
    data_.push_back(v);
    return *this;
}

Writer& Writer::u32(uint32_t v) {
    for (int i = 0; i < 4; ++i) data_.push_back(static_cast<uint8_t>(v >> (8 * i)));
    return *this;
}

Writer& Writer::u64(uint64_t v) {
    for (int i = 0; i < 8; ++i) data_.push_back(static_cast<uint8_t>(v >> (8 * i)));
    return *this;
}

Writer& Writer::string(const std::string& v) {
    u32(static_cast<uint32_t>(v.size()));
    data_.insert(data_.end(), v.begin(), v.end());
    return *this;
}

void Reader::need(size_t n) const {
    if (n > size_ - at_) throw ProtocolError("Truncated Soulseek message");
}

uint8_t Reader::u8() {
    need(1);
    return data_[at_++];
}

uint32_t Reader::u32() {
    need(4);
    const uint32_t v = static_cast<uint32_t>(data_[at_]) | (static_cast<uint32_t>(data_[at_ + 1]) << 8) |
                       (static_cast<uint32_t>(data_[at_ + 2]) << 16) | (static_cast<uint32_t>(data_[at_ + 3]) << 24);
    at_ += 4;
    return v;
}

uint64_t Reader::u64() {
    const uint64_t lo = u32();
    const uint64_t hi = u32();
    return lo | (hi << 32);
}

std::string Reader::string() {
    const uint32_t n = u32();
    if (n > kMaxStringBytes) throw ProtocolError("Oversized Soulseek string");
    need(n);
    std::string s(reinterpret_cast<const char*>(data_ + at_), n);
    at_ += n;
    return s;
}

std::vector<uint8_t> frame(uint32_t code, const std::vector<uint8_t>& body) {
    Writer w;
    w.u32(static_cast<uint32_t>(body.size() + 4)).u32(code);
    std::vector<uint8_t> out = w.bytes();
    out.insert(out.end(), body.begin(), body.end());
    return out;
}

std::vector<uint8_t> initFrame(uint8_t code, const std::vector<uint8_t>& body) {
    Writer w;
    w.u32(static_cast<uint32_t>(body.size() + 1)).u8(code);
    std::vector<uint8_t> out = w.bytes();
    out.insert(out.end(), body.begin(), body.end());
    return out;
}

std::string md5Hex(const std::string& text) {
    Md5 md5;
    std::vector<uint8_t> msg(text.begin(), text.end());
    const uint64_t bits = static_cast<uint64_t>(msg.size()) * 8;
    msg.push_back(0x80);
    while (msg.size() % 64 != 56) msg.push_back(0);
    for (int i = 0; i < 8; ++i) msg.push_back(static_cast<uint8_t>(bits >> (8 * i)));
    for (size_t i = 0; i < msg.size(); i += 64) md5.block(msg.data() + i);
    static const char* hex = "0123456789abcdef";
    std::string out;
    for (uint32_t word : {md5.a, md5.b, md5.c, md5.d}) {
        for (int i = 0; i < 4; ++i) {
            const auto byte = static_cast<uint8_t>(word >> (8 * i));
            out.push_back(hex[byte >> 4]);
            out.push_back(hex[byte & 0xf]);
        }
    }
    return out;
}

bool inflateZlib(const uint8_t* data, size_t size, std::vector<uint8_t>* out, size_t maxOut) {
    z_stream z{};
    if (inflateInit(&z) != Z_OK) return false;
    z.next_in = const_cast<Bytef*>(data);
    z.avail_in = static_cast<uInt>(size);
    out->clear();
    std::vector<uint8_t> chunk(64 * 1024);
    int status = Z_OK;
    while (status != Z_STREAM_END) {
        z.next_out = chunk.data();
        z.avail_out = static_cast<uInt>(chunk.size());
        status = inflate(&z, Z_NO_FLUSH);
        if (status != Z_OK && status != Z_STREAM_END) {
            inflateEnd(&z);
            return false;
        }
        out->insert(out->end(), chunk.data(), chunk.data() + (chunk.size() - z.avail_out));
        if (out->size() > maxOut) {
            inflateEnd(&z);
            return false;
        }
        if (status == Z_OK && z.avail_in == 0 && z.avail_out != 0) {
            inflateEnd(&z);
            return false;  // truncated stream
        }
    }
    inflateEnd(&z);
    return true;
}

std::vector<uint8_t> deflateZlib(const std::vector<uint8_t>& data) {
    uLongf size = compressBound(static_cast<uLong>(data.size()));
    std::vector<uint8_t> out(size);
    if (compress(out.data(), &size, data.data(), static_cast<uLong>(data.size())) != Z_OK) return {};
    out.resize(size);
    return out;
}

SearchResponse parseSearchResponse(const uint8_t* compressed, size_t size) {
    std::vector<uint8_t> bytes;
    if (!inflateZlib(compressed, size, &bytes, kMaxInflatedSearchBytes)) throw ProtocolError("Corrupt search response");
    Reader r(bytes);
    SearchResponse response;
    response.username = r.string();
    response.token = r.u32();
    const uint32_t count = r.u32();
    if (count > kMaxFilesPerResponse) throw ProtocolError("Too many search results");
    response.files.reserve(std::min<uint32_t>(count, 256));
    for (uint32_t i = 0; i < count; ++i) {
        SearchFile file;
        r.u8();  // result marker, 1
        file.filename = r.string();
        file.size = r.u64();
        file.extension = r.string();
        const uint32_t attrs = r.u32();
        if (attrs > kMaxAttributes) throw ProtocolError("Too many file attributes");
        for (uint32_t a = 0; a < attrs; ++a) {
            const uint32_t code = r.u32();
            file.attributes[code] = r.u32();
        }
        response.files.push_back(std::move(file));
    }
    response.slotFree = r.boolean();
    response.averageSpeed = r.u32();
    response.queueLength = r.u32();
    // Private results and trailing fields are ignored: inaccessible files are never offered.
    return response;
}

std::vector<uint8_t> buildSearchResponse(const SearchResponse& response) {
    Writer w;
    w.string(response.username).u32(response.token).u32(static_cast<uint32_t>(response.files.size()));
    for (const auto& f : response.files) {
        w.u8(1).string(f.filename).u64(f.size).string(f.extension).u32(static_cast<uint32_t>(f.attributes.size()));
        for (const auto& [code, value] : f.attributes) w.u32(code).u32(value);
    }
    w.boolean(response.slotFree).u32(response.averageSpeed).u32(response.queueLength).u32(0).u32(0);
    return deflateZlib(w.bytes());
}

// ---- Ranking ----------------------------------------------------------------------

bool formatAccepts(FormatPreference preference, const std::string& extension) {
    const std::string ext = lowerAscii(extension);
    switch (preference) {
        case FormatPreference::FlacOnly: return ext == "flac";
        case FormatPreference::Mp3Only: return ext == "mp3";
        case FormatPreference::PreferFlac:
        case FormatPreference::PreferMp3: return ext == "flac" || ext == "mp3";
    }
    return false;
}

bool formatPrefers(FormatPreference preference, const std::string& extension) {
    const std::string ext = lowerAscii(extension);
    const bool flacFirst = preference == FormatPreference::FlacOnly || preference == FormatPreference::PreferFlac;
    return ext == (flacFirst ? "flac" : "mp3");
}

bool isLosslessExtension(const std::string& extension) {
    const std::string ext = lowerAscii(extension);
    return ext == "flac" || ext == "wav" || ext == "aiff" || ext == "aif" || ext == "alac" || ext == "ape" || ext == "wv";
}

std::string leafOf(const std::string& path) {
    const size_t at = path.find_last_of("\\/");
    return at == std::string::npos ? path : path.substr(at + 1);
}

std::string normalizeSearchText(const std::string& text) {
    std::string folded;
    size_t i = 0;
    while (i < text.size()) {
        const uint32_t cp = nextCodePoint(text, i);
        if (cp < 0x80) {
            const char c = static_cast<char>(std::tolower(static_cast<int>(cp)));
            folded.push_back((std::isalnum(static_cast<unsigned char>(c))) ? c : ' ');
        } else if (cp >= 0xC0 && cp < 0x250 && kFold[cp - 0xC0][0] != '\0') {
            folded += lowerAscii(kFold[cp - 0xC0]);
        } else {
            folded.push_back(' ');
        }
    }
    return collapseSpaces(folded);
}

std::vector<std::string> meaningfulTokens(const std::string& normalized) {
    std::vector<std::string> tokens;
    std::set<std::string> seen;
    std::string token;
    for (char c : normalized + " ") {
        if (c == ' ') {
            if (token.size() >= 2 && !stopWords().count(token) && seen.insert(token).second) tokens.push_back(token);
            token.clear();
        } else {
            token.push_back(c);
        }
    }
    return tokens;
}

std::vector<std::string> buildSearchVariants(const std::string& query, SearchMode mode) {
    std::vector<std::string> variants;
    std::set<std::string> keys;
    auto add = [&](const std::string& value) {
        const std::string clean = collapseSpaces(value);
        if (clean.size() >= 2 && keys.insert(lowerAscii(clean)).second) variants.push_back(clean);
    };
    add(query);
    const std::string cleaned = stripSearchNoise(query);
    add(cleaned);
    if (mode != SearchMode::Quick) {
        static const std::regex separators(R"([-_:|]+|\xE2\x80\x93|\xE2\x80\x94)");
        add(std::regex_replace(cleaned, separators, " "));
        const auto compact = meaningfulTokens(normalizeSearchText(cleaned));
        if (compact.size() >= 2) {
            std::string joined;
            for (const auto& t : compact) joined += (joined.empty() ? "" : " ") + t;
            add(joined);
        }
    }
    if (mode == SearchMode::Deep) {
        std::string artist, title;
        if (splitArtistTitle(cleaned, &artist, &title) || inferArtistTitle(cleaned, &artist, &title)) {
            add(artist + " " + title);
            if (meaningfulTokens(normalizeSearchText(title)).size() >= 2) add(title);
        }
        const std::string ascii = foldDiacritics(cleaned);
        if (lowerAscii(ascii) != lowerAscii(cleaned)) add(ascii);
    }
    const size_t limit = mode == SearchMode::Quick ? 1 : mode == SearchMode::Balanced ? 4 : 6;
    if (variants.size() > limit) variants.resize(limit);
    return variants;
}

bool scoreCandidate(const std::string& query, const std::string& username, const SearchFile& file,
                    const SearchOptions& options, bool slotFree, uint32_t averageSpeed, uint32_t queueLength,
                    Candidate* out) {
    std::string extension = file.extension.empty() ? extensionOf(file.filename) : file.extension;
    extension = lowerAscii(extension);
    if (!formatAccepts(options.format, extension) || file.size == 0) return false;

    auto attribute = [&](uint32_t code, uint32_t lo, uint32_t hi) -> int {
        const auto it = file.attributes.find(code);
        if (it == file.attributes.end() || it->second < lo || it->second > hi) return 0;
        return static_cast<int>(it->second);
    };
    const int duration = attribute(1, 1, 86400);
    const int sampleRate = attribute(4, 8000, 768000);
    const int bitDepth = attribute(5, 8, 64);
    const int bitrate = attribute(0, 1, 100000);

    // Duration gate for the resolver: a different edit is a different song.
    int durationBonus = 0;
    if (options.targetDurationSeconds > 0 && duration > 0) {
        const int diff = std::abs(duration - options.targetDurationSeconds);
        if (diff > 6) return false;
        durationBonus = diff <= 2 ? 16 : -8;
    }

    const std::string q = normalizeSearchText(query);
    const std::string path = normalizeSearchText(file.filename);
    const std::string leaf = normalizeSearchText(stripTrackNumber(leafOf(file.filename)));
    const auto qTokens = meaningfulTokens(q);
    const double leafCoverage = weightedCoverage(qTokens, leaf);
    const double pathCoverage = weightedCoverage(qTokens, path);

    std::string artist, title;
    const bool split = splitArtistTitle(query, &artist, &title);
    const double artistCoverage = split ? weightedCoverage(meaningfulTokens(normalizeSearchText(artist)), path) : 0.0;
    const double titleCoverage = split ? weightedCoverage(meaningfulTokens(normalizeSearchText(title)), leaf) : leafCoverage;

    const bool lossless = isLosslessExtension(extension);
    int score = formatPrefers(options.format, extension) ? 18 : 8;
    score += static_cast<int>(leafCoverage * 34.0);
    score += static_cast<int>(pathCoverage * 16.0);
    if (split) {
        score += static_cast<int>(artistCoverage * 8.0);
        score += static_cast<int>(titleCoverage * 14.0);
    }
    if (!q.empty() && leaf.find(q) != std::string::npos) {
        score += 12;
    } else if (!q.empty() && path.find(q) != std::string::npos) {
        score += 6;
    }
    score += slotFree ? 11 : -2;
    if (lossless) {
        score += bitDepth >= 24 ? 5 : bitDepth >= 16 ? 2 : 0;
        score += sampleRate >= 96000 ? 4 : sampleRate >= 48000 ? 3 : sampleRate >= 44100 ? 2 : 0;
    } else {
        score += bitrate == 0 ? 0 : bitrate >= 320 ? 6 : bitrate >= 256 ? 4 : bitrate >= 192 ? 2 : bitrate >= 160 ? 0 : bitrate >= 128 ? -4 : -12;
    }
    if (averageSpeed > 0) {
        score += std::clamp(static_cast<int>(std::log(1.0 + averageSpeed / 120000.0) * 2.7), 0, 9);
    }
    score -= std::min<int>(12, static_cast<int>(std::min<uint32_t>(queueLength, 1000)) * 2);
    for (const char* term : {"live", "remix", "instrumental", "karaoke", "cover", "sped up", "slowed", "nightcore", "reverb"}) {
        if (leaf.find(term) != std::string::npos && q.find(term) == std::string::npos) score -= 10;
    }
    for (const char* term : {"spectrogram", "log", "cue", "checksum", "artwork"}) {
        if (leaf.find(term) != std::string::npos) score -= 18;
    }
    const uint64_t minBytesPerSecond = lossless ? 35000 : 12000;
    if (duration > 120 && file.size < static_cast<uint64_t>(duration) * minBytesPerSecond) score -= 20;
    score += durationBonus;

    std::vector<std::string> reasons;
    if (titleCoverage >= 0.95 && (!split || artistCoverage >= 0.75)) {
        reasons.push_back("excellent match");
    } else if (leafCoverage >= 0.75 || pathCoverage >= 0.85) {
        reasons.push_back("good match");
    }
    if (durationBonus > 0) reasons.push_back("exact length");
    if (lossless) {
        if (bitDepth >= 24) reasons.push_back("24-bit");
        if (sampleRate >= 96000) reasons.push_back("hi-res");
    } else if (bitrate >= 320) {
        reasons.push_back("320 kbps");
    } else if (bitrate > 0 && bitrate <= 128) {
        reasons.push_back("low bitrate");
    }
    if (slotFree) reasons.push_back("free slot");
    if (averageSpeed >= 2000000) reasons.push_back("fast peer");
    if (queueLength == 0) reasons.push_back("no queue");

    Candidate c;
    c.id = md5Hex(username + '\0' + file.filename + '\0' + std::to_string(file.size));
    c.username = username;
    c.filename = file.filename;
    c.leafName = leafOf(file.filename);
    c.size = file.size;
    c.extension = extension;
    c.durationSeconds = duration;
    c.sampleRate = sampleRate;
    c.bitDepth = bitDepth;
    c.bitrateKbps = bitrate;
    c.freeUploadSlot = slotFree;
    c.averageSpeed = averageSpeed;
    c.queueLength = queueLength;
    c.score = std::clamp(score, 0, 100);
    for (const auto& r : reasons) c.recommendation += (c.recommendation.empty() ? "" : " \xC2\xB7 ") + r;
    if (c.recommendation.empty()) {
        std::string upperExt = extension;
        for (char& ch : upperExt) ch = static_cast<char>(std::toupper(static_cast<unsigned char>(ch)));
        c.recommendation = upperExt + " result";
    }
    *out = std::move(c);
    return true;
}

std::vector<Candidate> selectRecommendations(std::vector<Candidate> all, bool onlyFreeSlots, size_t max) {
    if (onlyFreeSlots) {
        all.erase(std::remove_if(all.begin(), all.end(), [](const Candidate& c) { return !c.freeUploadSlot; }), all.end());
    }
    std::stable_sort(all.begin(), all.end(), [](const Candidate& a, const Candidate& b) {
        if (a.score != b.score) return a.score > b.score;
        if (a.freeUploadSlot != b.freeUploadSlot) return a.freeUploadSlot;
        if (a.queueLength != b.queueLength) return a.queueLength < b.queueLength;
        return a.averageSpeed > b.averageSpeed;
    });
    if (all.size() <= max) return all;
    std::vector<Candidate> chosen;
    std::set<std::string> users;
    std::set<std::string> ids;
    for (const auto& c : all) {
        if (!users.insert(lowerAscii(c.username)).second) continue;
        chosen.push_back(c);
        ids.insert(c.id);
        if (chosen.size() == max) return chosen;
    }
    for (const auto& c : all) {
        if (ids.count(c.id)) continue;
        chosen.push_back(c);
        ids.insert(c.id);
        if (chosen.size() == max) break;
    }
    return chosen;
}

bool hasSufficientResults(const std::vector<Candidate>& recommendations) {
    if (recommendations.size() < 10) return false;
    std::set<std::string> users;
    int strong = 0;
    for (const auto& c : recommendations) {
        users.insert(lowerAscii(c.username));
        if (c.score >= 72) ++strong;
    }
    return users.size() >= 7 && strong >= 6;
}

}  // namespace harmony::slsk
