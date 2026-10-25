// SlskProtocol.h: the Soulseek wire format and Harmony's search ranking.
//
// A port of the Android app's SoulseekClient (feature/downloads) protocol
// layer and scoring, in plain C++17 (zlib only) so it is tested on the host.
// The network engine (SlskEngine) builds on it.
//
// Framing: every server and peer message is [uint32 length][uint32 code][body];
// peer-init messages (the first on a new peer socket) use a one-byte code.
// Integers are little-endian; strings are a uint32 length then raw bytes
// (UTF-8 in practice; file names are echoed back to peers byte for byte).

#ifndef HARMONY_SLSK_PROTOCOL_H
#define HARMONY_SLSK_PROTOCOL_H

#include <cstdint>
#include <map>
#include <stdexcept>
#include <string>
#include <vector>

namespace harmony::slsk {

// Server message codes.
enum ServerCode : uint32_t {
    kLogin = 1,
    kSetWaitPort = 2,
    kGetPeerAddress = 3,
    kConnectToPeer = 18,
    kFileSearch = 26,
    kSetStatus = 28,
    kServerPing = 32,
    kSharedFoldersFiles = 35,
    kRelogged = 41,
    kCantConnectToPeer = 1001,
};

// Peer-init codes (one byte).
enum PeerInitCode : uint8_t {
    kPierceFirewall = 0,
    kPeerInit = 1,
};

// Peer message codes.
enum PeerCode : uint32_t {
    kSharedFileListRequest = 4,
    kSharedFileListResponse = 5,
    kFileSearchResponse = 9,
    kUserInfoRequest = 15,
    kUserInfoResponse = 16,
    kTransferRequest = 40,
    kTransferResponse = 41,
    kQueueUpload = 43,
    kPlaceInQueueResponse = 44,
    kUploadFailed = 46,
    kUploadDenied = 50,
    kPlaceInQueueRequest = 51,
};

constexpr uint32_t kMajorVersion = 177;  // the Android client's values
constexpr uint32_t kMinorVersion = 28;

struct ProtocolError : std::runtime_error {
    using std::runtime_error::runtime_error;
};

class Writer {
public:
    Writer& u8(uint8_t v);
    Writer& u32(uint32_t v);
    Writer& u64(uint64_t v);
    Writer& boolean(bool v) { return u8(v ? 1 : 0); }
    Writer& string(const std::string& v);
    const std::vector<uint8_t>& bytes() const { return data_; }

private:
    std::vector<uint8_t> data_;
};

class Reader {
public:
    Reader(const uint8_t* data, size_t size) : data_(data), size_(size) {}
    explicit Reader(const std::vector<uint8_t>& v) : Reader(v.data(), v.size()) {}
    uint8_t u8();
    uint32_t u32();
    uint64_t u64();
    bool boolean() { return u8() != 0; }
    std::string string();
    size_t remaining() const { return size_ - at_; }

private:
    void need(size_t n) const;
    const uint8_t* data_;
    size_t size_;
    size_t at_ = 0;
};

/** A whole message: [length][code][body]. */
std::vector<uint8_t> frame(uint32_t code, const std::vector<uint8_t>& body);
/** A peer-init message: [length][code byte][body]. */
std::vector<uint8_t> initFrame(uint8_t code, const std::vector<uint8_t>& body);

std::string md5Hex(const std::string& text);
/** zlib inflate with an output cap; false on corrupt data or when the cap is passed. */
bool inflateZlib(const uint8_t* data, size_t size, std::vector<uint8_t>* out, size_t maxOut);
std::vector<uint8_t> deflateZlib(const std::vector<uint8_t>& data);

struct SearchFile {
    std::string filename;  // raw bytes, echoed back to the peer
    uint64_t size = 0;
    std::string extension;
    std::map<uint32_t, uint32_t> attributes;  // 0 bitrate, 1 duration s, 2 VBR, 4 sample rate, 5 bit depth
};

struct SearchResponse {
    std::string username;
    uint32_t token = 0;
    std::vector<SearchFile> files;
    bool slotFree = false;
    uint32_t averageSpeed = 0;
    uint32_t queueLength = 0;
};

/** Parses a FileSearchResponse (peer code 9) payload, which is zlib-compressed. Throws ProtocolError. */
SearchResponse parseSearchResponse(const uint8_t* compressed, size_t size);
/** Builds one (used by the tests' fake peer). */
std::vector<uint8_t> buildSearchResponse(const SearchResponse& response);

// ---------------------------------------------------------------------------
// Ranking (a port of SoulseekClient.scoreCandidate and friends)
// ---------------------------------------------------------------------------

enum class SearchMode { Quick, Balanced, Deep };

enum class FormatPreference { FlacOnly, PreferFlac, PreferMp3, Mp3Only };

bool formatAccepts(FormatPreference preference, const std::string& extension);
bool formatPrefers(FormatPreference preference, const std::string& extension);
bool isLosslessExtension(const std::string& extension);

struct SearchOptions {
    SearchMode mode = SearchMode::Balanced;
    FormatPreference format = FormatPreference::FlacOnly;
    bool onlyFreeSlots = false;
    /**
     * The length of the song being looked for, seconds (0: unknown). Results
     * within ±2 s rank up; ones more than 6 s off (another edit or version) are
     * dropped. Set by the lossless resolver from the imported track.
     */
    int targetDurationSeconds = 0;
};

struct Candidate {
    std::string id;
    std::string username;
    std::string filename;
    std::string leafName;
    uint64_t size = 0;
    std::string extension;
    int durationSeconds = 0;  // 0 = not reported
    int sampleRate = 0;
    int bitDepth = 0;
    int bitrateKbps = 0;
    bool freeUploadSlot = false;
    uint32_t averageSpeed = 0;
    uint32_t queueLength = 0;
    int score = 0;
    std::string recommendation;
};

std::string normalizeSearchText(const std::string& text);
std::vector<std::string> meaningfulTokens(const std::string& normalized);
std::vector<std::string> buildSearchVariants(const std::string& query, SearchMode mode);

/** Scores one search result. Returns false when it must not be offered at all. */
bool scoreCandidate(const std::string& query, const std::string& username, const SearchFile& file,
                    const SearchOptions& options, bool slotFree, uint32_t averageSpeed, uint32_t queueLength,
                    Candidate* out);

/** Best first, one per peer first, at most [max]. */
std::vector<Candidate> selectRecommendations(std::vector<Candidate> all, bool onlyFreeSlots, size_t max);
bool hasSufficientResults(const std::vector<Candidate>& recommendations);

/** The leaf of a Soulseek path ("Music\\Album\\01 Song.flac" -> "01 Song.flac"). */
std::string leafOf(const std::string& path);

}  // namespace harmony::slsk

#endif  // HARMONY_SLSK_PROTOCOL_H
