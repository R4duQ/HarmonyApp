// SlskEngine.h: Harmony's Soulseek client for iOS (search and download).
//
// One thread runs a poll() loop over non-blocking BSD sockets: the server
// session, a listening socket for peers that call in, peer ("P") connections
// for search replies and download negotiation, and file ("F") connections
// carrying the audio. Every peer is reached both ways at once, directly and
// through the server's ConnectToPeer (for peers behind NAT, as an iPhone
// always is); the first to connect wins. The behaviour follows the Android
// SoulseekClient: the same login version, ranking, download handshake
// (QueueUpload, TransferRequest, F connection, offset), resume of partial
// files by content, and the container check before a file is kept.
//
// Harmony for iOS only downloads: it answers browse and upload requests with
// an empty share and "File not shared.".
//
// All public methods are thread-safe: they queue a command for the loop.
// Events arrive on the loop thread; the Objective-C++ bridge moves them to
// the main thread.

#ifndef HARMONY_SLSK_ENGINE_H
#define HARMONY_SLSK_ENGINE_H

#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "SlskProtocol.h"

namespace harmony::slsk {

enum class ConnectionStatus { Disconnected = 0, Connecting = 1, Connected = 2, Error = 3 };

enum class TransferStage {
    ConnectingPeer = 0,
    Requested = 1,     // QueueUpload sent, waiting for the peer
    Queued = 2,        // the peer reported a queue position
    Accepted = 3,      // TransferRequest accepted, waiting for the file connection
    Transferring = 4,
    Verifying = 5,
    Done = 6,
    Failed = 7,
    Cancelled = 8,
};

struct Event {
    enum class Kind { Connection, Search, Transfer };
    Kind kind = Kind::Connection;

    // Connection
    ConnectionStatus status = ConnectionStatus::Disconnected;
    std::string message;
    std::string username;

    // Search
    uint32_t searchId = 0;
    std::vector<Candidate> candidates;
    bool searchDone = false;
    int peersResponded = 0;
    int filesSeen = 0;

    // Transfer
    uint32_t transferId = 0;
    TransferStage stage = TransferStage::ConnectingPeer;
    uint64_t bytes = 0;
    uint64_t totalBytes = 0;
    double bytesPerSecond = 0;
    int queuePlace = 0;
    std::string path;          // Done: the finished file
    bool queuedElsewhere = false;  // Failed only because the peer queued us: worth retrying later
};

struct EngineConfig {
    std::string serverHost = "server.slsknet.org";
    int serverPort = 2242;
    /** Where partial downloads live (resumable across sources by content). */
    std::string partialDirectory;
    /** Where finished files go. */
    std::string downloadDirectory;
    /** Test hook: time runs faster in tests by scaling every timeout. */
    double timeoutScale = 1.0;
};

class Engine {
public:
    using Listener = std::function<void(const Event&)>;

    Engine(EngineConfig config, Listener listener);
    ~Engine();
    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;

    void start();
    void stop();

    void connect(const std::string& username, const std::string& password);
    void disconnect();

    /** Starts a search; results arrive as Search events with this id. */
    uint32_t search(const std::string& query, const SearchOptions& options);
    void cancelSearch(uint32_t searchId);

    /**
     * Downloads [candidate]. [patient]: wait in the peer's queue (minutes)
     * instead of giving up after a few seconds so another source can be tried.
     * [fileName]: the finished file's name without extension (empty: the peer's).
     */
    uint32_t download(const Candidate& candidate, bool patient, const std::string& fileName);
    void cancelTransfer(uint32_t transferId);

    ConnectionStatus status() const { return status_.load(); }

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
    std::atomic<ConnectionStatus> status_{ConnectionStatus::Disconnected};
};

}  // namespace harmony::slsk

#endif  // HARMONY_SLSK_ENGINE_H
