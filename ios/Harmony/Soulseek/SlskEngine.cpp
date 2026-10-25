#include "SlskEngine.h"

#include <arpa/inet.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <deque>
#include <map>
#include <random>
#include <set>

#include "../Ingest/HarmonyTagWriter.h"

namespace harmony::slsk {

namespace {

constexpr size_t kMaxServerFrame = 16 * 1024 * 1024;
constexpr size_t kMaxPeerFrame = 32 * 1024 * 1024;
constexpr size_t kMaxInitFrame = 64 * 1024;
constexpr size_t kReadChunk = 256 * 1024;
constexpr int kMaxIndirectDials = 24;
constexpr size_t kMaxRecommendations = 10;

int64_t nowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

std::string lowerKey(std::string s) {
    for (char& c : s) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return s;
}

std::string upperKey(std::string s) {
    for (char& c : s) c = static_cast<char>(std::toupper(static_cast<unsigned char>(c)));
    return s;
}

void configureSocket(int fd) {
    const int flags = fcntl(fd, F_GETFL, 0);
    fcntl(fd, F_SETFL, flags | O_NONBLOCK);
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);
    setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &one, sizeof one);
#ifdef SO_NOSIGPIPE
    setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, sizeof one);
#endif
}

ssize_t sendNoSignal(int fd, const uint8_t* data, size_t size) {
#ifdef MSG_NOSIGNAL
    return ::send(fd, data, size, MSG_NOSIGNAL);
#else
    return ::send(fd, data, size, 0);
#endif
}

bool fileExists(const std::string& path) {
    struct stat st {};
    return ::stat(path.c_str(), &st) == 0;
}

uint64_t fileSizeOf(const std::string& path) {
    struct stat st {};
    return ::stat(path.c_str(), &st) == 0 ? static_cast<uint64_t>(st.st_size) : 0;
}

void makeDirectories(const std::string& path) {
    std::string partial;
    for (size_t i = 0; i < path.size(); ++i) {
        partial.push_back(path[i]);
        if ((path[i] == '/' && i > 0) || i + 1 == path.size()) ::mkdir(partial.c_str(), 0755);
    }
}

std::string sanitizeFileName(const std::string& value) {
    std::string out;
    for (char c : value) {
        const auto u = static_cast<unsigned char>(c);
        out.push_back((u < 32 || std::strchr("\\/:*?\"<>|", c)) ? '_' : c);
    }
    while (!out.empty() && (out.back() == ' ' || out.back() == '.')) out.pop_back();
    while (!out.empty() && out.front() == ' ') out.erase(out.begin());
    if (out.size() > 180) out.resize(180);
    return out.empty() ? std::string("Soulseek download") : out;
}

std::string stripExtension(const std::string& leaf) {
    const size_t dot = leaf.rfind('.');
    return dot == std::string::npos || dot == 0 ? leaf : leaf.substr(0, dot);
}

/** Moves a finished file into place, copying when rename() can't (another volume). */
bool moveFile(const std::string& from, const std::string& to) {
    if (std::rename(from.c_str(), to.c_str()) == 0) return true;
    FILE* in = std::fopen(from.c_str(), "rb");
    FILE* out = in ? std::fopen(to.c_str(), "wb") : nullptr;
    bool ok = in && out;
    std::vector<uint8_t> buffer(1 << 20);
    while (ok) {
        const size_t n = std::fread(buffer.data(), 1, buffer.size(), in);
        if (n == 0) break;
        ok = std::fwrite(buffer.data(), 1, n, out) == n;
    }
    if (in) std::fclose(in);
    if (out) ok = (std::fclose(out) == 0) && ok;
    if (ok) {
        std::remove(from.c_str());
    } else {
        std::remove(to.c_str());
    }
    return ok;
}

}  // namespace

struct Engine::Impl {
    enum class Kind { Server, Incoming, DialDirectP, DialIndirect, PeerP, FileF };

    struct Conn {
        int id = 0;
        int fd = -1;
        Kind kind = Kind::Incoming;
        std::string user;          // peer's name as they spell it
        std::string indirectType;  // DialIndirect: "P" or "F"
        uint32_t indirectToken = 0;
        std::vector<uint8_t> in;
        std::vector<uint8_t> out;
        size_t outAt = 0;
        bool connecting = false;
        int64_t deadline = 0;      // connect or init deadline (0: none)
        int64_t lastActivity = 0;
        bool haveFileToken = false;
        uint32_t downloadId = 0;
        bool closed = false;
    };

    struct PeerRequest {
        std::string user;
        uint32_t token = 0;
        int64_t deadline = 0;
        int directConn = -1;
        bool directFailed = false;
        bool indirectFailed = false;
        std::vector<std::function<void(int, const std::string&)>> waiters;
    };

    struct Search {
        uint32_t id = 0;
        std::string query;
        SearchOptions options;
        std::vector<uint32_t> tokens;
        std::map<std::string, Candidate> candidates;
        std::set<std::string> peers;
        int files = 0;
        int64_t minEnd = 0;
        int64_t maxEnd = 0;
        int64_t lastEmit = 0;
        int64_t graceEnd = 0;
        bool done = false;
        bool dirty = false;
    };

    struct Download {
        uint32_t id = 0;
        Candidate candidate;
        bool patient = false;
        std::string partialPath;
        std::string finalBase;
        TransferStage stage = TransferStage::ConnectingPeer;
        int64_t deadline = 0;
        int queuePlace = 0;
        uint32_t transferToken = 0;
        uint64_t expected = 0;
        uint64_t offset = 0;
        FILE* file = nullptr;
        int fileConn = -1;
        int64_t lastProgress = 0;
        int64_t lastEmit = 0;
        int64_t nextQueuePoll = 0;
        int64_t queuePollInterval = 0;
        int64_t lastKnock = 0;
        uint64_t speedBytes = 0;
        int64_t speedTime = 0;
        double speed = 0;
    };

    Engine& owner;
    EngineConfig config;
    Listener listener;
    std::thread thread;
    std::atomic<bool> running{false};
    int wakeRead = -1;
    int wakeWrite = -1;
    std::mutex commandsLock;
    std::deque<std::function<void()>> commands;
    std::atomic<uint32_t> nextPublicId{1};

    uint32_t nextToken;
    std::string user;
    std::string password;
    int serverConn = -1;
    int listenFd = -1;
    int listenPort = 0;
    bool loggedIn = false;
    int64_t loginDeadline = 0;
    int64_t nextPing = 0;
    int64_t nextHousekeeping = 0;
    int nextConnId = 1;
    int indirectDials = 0;

    std::map<int, Conn> conns;
    std::map<std::string, int> peerByUser;
    std::map<std::string, PeerRequest> peerRequests;
    std::map<uint32_t, std::string> indirectTokens;
    std::map<uint32_t, Search> searches;
    std::map<uint32_t, uint32_t> searchTokens;
    std::map<uint32_t, Download> downloads;
    std::map<uint32_t, uint32_t> transferTokens;

    Impl(Engine& o, EngineConfig c, Listener l) : owner(o), config(std::move(c)), listener(std::move(l)) {
        std::random_device seed;
        nextToken = 0x10000 + (seed() % 0x7fff0000u);
        int fds[2];
        if (::pipe(fds) == 0) {
            wakeRead = fds[0];
            wakeWrite = fds[1];
            fcntl(wakeRead, F_SETFL, O_NONBLOCK);
            fcntl(wakeWrite, F_SETFL, O_NONBLOCK);
        }
    }

    ~Impl() {
        if (wakeRead >= 0) ::close(wakeRead);
        if (wakeWrite >= 0) ::close(wakeWrite);
    }

    int64_t ms(double milliseconds) const { return static_cast<int64_t>(milliseconds * config.timeoutScale); }

    void post(std::function<void()> f) {
        {
            std::lock_guard<std::mutex> guard(commandsLock);
            commands.push_back(std::move(f));
        }
        const uint8_t one = 1;
        if (wakeWrite >= 0) (void)!::write(wakeWrite, &one, 1);
    }

    void emit(const Event& e) {
        if (listener) listener(e);
    }

    void setStatus(ConnectionStatus status, const std::string& message) {
        owner.status_.store(status);
        Event e;
        e.kind = Event::Kind::Connection;
        e.status = status;
        e.message = message;
        e.username = user;
        emit(e);
    }

    uint32_t token() { return nextToken++; }

    // ---- Sockets ---------------------------------------------------------------

    Conn& addConn(int fd, Kind kind) {
        Conn c;
        c.id = nextConnId++;
        c.fd = fd;
        c.kind = kind;
        c.lastActivity = nowMs();
        return conns.emplace(c.id, std::move(c)).first->second;
    }

    Conn* conn(int id) {
        auto it = conns.find(id);
        return it == conns.end() || it->second.closed ? nullptr : &it->second;
    }

    /** Starts a non-blocking TCP connection. */
    Conn* dial(uint32_t ipv4HostOrder, int port, Kind kind, int64_t timeoutMs) {
        const int fd = ::socket(AF_INET, SOCK_STREAM, 0);
        if (fd < 0) return nullptr;
        configureSocket(fd);
        sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_port = htons(static_cast<uint16_t>(port));
        addr.sin_addr.s_addr = htonl(ipv4HostOrder);
        const int r = ::connect(fd, reinterpret_cast<sockaddr*>(&addr), sizeof addr);
        if (r != 0 && errno != EINPROGRESS) {
            ::close(fd);
            return nullptr;
        }
        Conn& c = addConn(fd, kind);
        c.connecting = true;
        c.deadline = nowMs() + timeoutMs;
        return &c;
    }

    void queue(Conn& c, const std::vector<uint8_t>& bytes) {
        c.out.insert(c.out.end(), bytes.begin(), bytes.end());
        if (!c.connecting) flush(c);
    }

    void sendServer(uint32_t code, const Writer& body) {
        if (Conn* s = conn(serverConn)) queue(*s, frame(code, body.bytes()));
    }

    void sendPeer(Conn& c, uint32_t code, const Writer& body) { queue(c, frame(code, body.bytes())); }

    void flush(Conn& c) {
        while (c.outAt < c.out.size()) {
            const ssize_t n = sendNoSignal(c.fd, c.out.data() + c.outAt, c.out.size() - c.outAt);
            if (n > 0) {
                c.outAt += static_cast<size_t>(n);
                continue;
            }
            if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) return;
            closeConn(c.id, "write failed");
            return;
        }
        c.out.clear();
        c.outAt = 0;
    }

    void closeConn(int id, const std::string& why) {
        auto it = conns.find(id);
        if (it == conns.end() || it->second.closed) return;
        Conn& c = it->second;
        c.closed = true;
        ::close(c.fd);
        const std::string key = lowerKey(c.user);
        switch (c.kind) {
            case Kind::Server:
                serverConn = -1;
                if (loggedIn || owner.status_.load() == ConnectionStatus::Connecting) {
                    teardown(ConnectionStatus::Error, "The Soulseek connection was lost (" + why + ")");
                }
                break;
            case Kind::PeerP: {
                auto p = peerByUser.find(key);
                if (p != peerByUser.end() && p->second == id) peerByUser.erase(p);
                break;
            }
            case Kind::DialDirectP: {
                auto r = peerRequests.find(key);
                if (r != peerRequests.end() && r->second.directConn == id) {
                    r->second.directFailed = true;
                    r->second.directConn = -1;
                    maybeFailRequest(key);
                }
                break;
            }
            case Kind::DialIndirect:
                if (c.connecting) {
                    --indirectDials;
                    sendServer(kCantConnectToPeer, Writer().u32(c.indirectToken).string(c.user));
                }
                break;
            case Kind::FileF:
                if (c.downloadId != 0) {
                    auto d = downloads.find(c.downloadId);
                    if (d != downloads.end() && d->second.fileConn == id && d->second.stage == TransferStage::Transferring) {
                        d->second.fileConn = -1;
                        if (d->second.offset < d->second.expected) {
                            failDownload(d->second, "The peer disconnected at " + std::to_string(d->second.offset) + " of " +
                                                        std::to_string(d->second.expected) + " bytes", false);
                        }
                    }
                }
                break;
            case Kind::Incoming: break;
        }
    }

    // ---- The loop -------------------------------------------------------------------

    void run() {
        std::vector<pollfd> fds;
        std::vector<int> ids;
        std::vector<uint8_t> chunk(kReadChunk);
        while (running.load()) {
            fds.clear();
            ids.clear();
            fds.push_back({wakeRead, POLLIN, 0});
            ids.push_back(0);
            if (listenFd >= 0) {
                fds.push_back({listenFd, POLLIN, 0});
                ids.push_back(-1);
            }
            for (auto& [id, c] : conns) {
                if (c.closed) continue;
                short events = c.connecting ? POLLOUT : POLLIN;
                if (!c.connecting && c.outAt < c.out.size()) events |= POLLOUT;
                fds.push_back({c.fd, events, 0});
                ids.push_back(id);
            }
            const int ready = ::poll(fds.data(), static_cast<nfds_t>(fds.size()), 50);
            if (ready < 0 && errno != EINTR) break;
            for (size_t i = 0; ready > 0 && i < fds.size(); ++i) {
                if (fds[i].revents == 0) continue;
                if (ids[i] == 0) {
                    uint8_t drain[64];
                    while (::read(wakeRead, drain, sizeof drain) > 0) {
                    }
                    continue;
                }
                if (ids[i] == -1) {
                    acceptPeers();
                    continue;
                }
                Conn* c = conn(ids[i]);
                if (!c) continue;
                if (c->connecting) {
                    if (fds[i].revents & (POLLOUT | POLLERR | POLLHUP)) finishConnect(*c);
                    continue;
                }
                if (fds[i].revents & POLLIN) readFrom(ids[i], chunk);
                c = conn(ids[i]);
                if (c && (fds[i].revents & POLLOUT)) flush(*c);
                c = conn(ids[i]);
                if (c && (fds[i].revents & (POLLERR | POLLNVAL))) closeConn(ids[i], "socket error");
            }
            runCommands();
            timers();
            // Forget closed connections.
            for (auto it = conns.begin(); it != conns.end();) {
                it = it->second.closed ? conns.erase(it) : std::next(it);
            }
        }
        teardown(ConnectionStatus::Disconnected, "Stopped");
        for (auto& [id, c] : conns) {
            if (!c.closed) ::close(c.fd);
        }
        conns.clear();
    }

    void runCommands() {
        std::deque<std::function<void()>> pending;
        {
            std::lock_guard<std::mutex> guard(commandsLock);
            pending.swap(commands);
        }
        for (auto& f : pending) f();
    }

    void acceptPeers() {
        while (true) {
            const int fd = ::accept(listenFd, nullptr, nullptr);
            if (fd < 0) return;
            configureSocket(fd);
            Conn& c = addConn(fd, Kind::Incoming);
            c.deadline = nowMs() + ms(20000);
        }
    }

    void finishConnect(Conn& c) {
        int error = 0;
        socklen_t len = sizeof error;
        getsockopt(c.fd, SOL_SOCKET, SO_ERROR, &error, &len);
        if (error != 0) {
            closeConn(c.id, std::strerror(error));
            return;
        }
        c.connecting = false;
        c.deadline = 0;
        c.lastActivity = nowMs();
        switch (c.kind) {
            case Kind::Server: onServerConnected(c); break;
            case Kind::DialDirectP: {
                c.kind = Kind::PeerP;
                queue(c, initFrame(kPeerInit, Writer().string(user).string("P").u32(0).bytes()));
                registerPeer(c.id);
                break;
            }
            case Kind::DialIndirect: {
                --indirectDials;
                queue(c, initFrame(kPierceFirewall, Writer().u32(c.indirectToken).bytes()));
                if (c.indirectType == "F") {
                    c.kind = Kind::FileF;
                } else {
                    c.kind = Kind::PeerP;
                    registerPeer(c.id);
                }
                break;
            }
            default: break;
        }
        if (Conn* again = conn(c.id)) flush(*again);
    }

    void readFrom(int id, std::vector<uint8_t>& chunk) {
        while (true) {
            Conn* c = conn(id);
            if (!c) return;
            const ssize_t n = ::recv(c->fd, chunk.data(), chunk.size(), 0);
            if (n > 0) {
                c->lastActivity = nowMs();
                if (c->kind == Kind::FileF && c->haveFileToken) {
                    fileData(*c, chunk.data(), static_cast<size_t>(n));
                } else {
                    c->in.insert(c->in.end(), chunk.data(), chunk.data() + n);
                    process(id);
                }
                continue;
            }
            if (n == 0) {
                closeConn(id, "closed by peer");
                return;
            }
            if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) return;
            closeConn(id, std::strerror(errno));
            return;
        }
    }

    /** Parses whatever whole messages [id] has buffered. */
    void process(int id) {
        while (true) {
            Conn* c = conn(id);
            if (!c) return;
            std::vector<uint8_t>& in = c->in;
            if (c->kind == Kind::FileF) {
                if (!c->haveFileToken) {
                    if (in.size() < 4) return;
                    const uint32_t tok = Reader(in.data(), 4).u32();
                    std::vector<uint8_t> rest(in.begin() + 4, in.end());
                    in.clear();
                    c->haveFileToken = true;
                    fileToken(*c, tok);
                    Conn* still = conn(id);
                    if (still && !rest.empty()) fileData(*still, rest.data(), rest.size());
                }
                return;
            }
            if (in.size() < 4) return;
            const uint32_t length = Reader(in.data(), 4).u32();
            const bool init = c->kind == Kind::Incoming;
            const size_t limit = c->kind == Kind::Server ? kMaxServerFrame : init ? kMaxInitFrame : kMaxPeerFrame;
            if (length < (init ? 1u : 4u) || length > limit) {
                closeConn(id, "invalid frame");
                return;
            }
            if (in.size() < 4 + static_cast<size_t>(length)) return;
            std::vector<uint8_t> message(in.begin() + 4, in.begin() + 4 + length);
            in.erase(in.begin(), in.begin() + 4 + length);
            try {
                if (init) {
                    handleInit(*c, message[0], message.data() + 1, message.size() - 1);
                } else {
                    const uint32_t code = Reader(message.data(), 4).u32();
                    if (c->kind == Kind::Server) {
                        handleServer(code, message.data() + 4, message.size() - 4);
                    } else {
                        handlePeer(id, code, message.data() + 4, message.size() - 4);
                    }
                }
            } catch (const std::exception&) {
                // A malformed message from one peer never stops the engine.
                if (c->kind == Kind::Incoming || c->kind == Kind::Server) {
                    closeConn(id, "malformed message");
                    return;
                }
            }
        }
    }

    // ---- Session -------------------------------------------------------------------------

    void doConnect(const std::string& name, const std::string& pass) {
        teardown(ConnectionStatus::Disconnected, "");
        user = name;
        password = pass;
        setStatus(ConnectionStatus::Connecting, "Connecting to Soulseek…");
        if (!openListener()) {
            setStatus(ConnectionStatus::Error, "Could not open a port for peers");
            return;
        }
        addrinfo hints{};
        hints.ai_family = AF_INET;
        hints.ai_socktype = SOCK_STREAM;
        addrinfo* result = nullptr;
        if (getaddrinfo(config.serverHost.c_str(), std::to_string(config.serverPort).c_str(), &hints, &result) != 0 ||
            result == nullptr) {
            setStatus(ConnectionStatus::Error, "Could not find the Soulseek server (no internet?)");
            return;
        }
        const auto* addr = reinterpret_cast<const sockaddr_in*>(result->ai_addr);
        const uint32_t ip = ntohl(addr->sin_addr.s_addr);
        const int port = ntohs(addr->sin_port);
        freeaddrinfo(result);
        Conn* s = dial(ip, port, Kind::Server, ms(10000));
        if (!s) {
            setStatus(ConnectionStatus::Error, "Could not reach the Soulseek server");
            return;
        }
        serverConn = s->id;
        loginDeadline = nowMs() + ms(15000);
    }

    bool openListener() {
        if (listenFd >= 0) return true;
        const int fd = ::socket(AF_INET, SOCK_STREAM, 0);
        if (fd < 0) return false;
        int one = 1;
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
        sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_addr.s_addr = htonl(INADDR_ANY);
        addr.sin_port = 0;
        if (::bind(fd, reinterpret_cast<sockaddr*>(&addr), sizeof addr) != 0 || ::listen(fd, 64) != 0) {
            ::close(fd);
            return false;
        }
        socklen_t len = sizeof addr;
        getsockname(fd, reinterpret_cast<sockaddr*>(&addr), &len);
        listenPort = ntohs(addr.sin_port);
        fcntl(fd, F_SETFL, fcntl(fd, F_GETFL, 0) | O_NONBLOCK);
        listenFd = fd;
        return true;
    }

    void onServerConnected(Conn& s) {
        queue(s, frame(kLogin, Writer()
                                    .string(user)
                                    .string(password)
                                    .u32(kMajorVersion)
                                    .string(md5Hex(user + password))
                                    .u32(kMinorVersion)
                                    .bytes()));
    }

    void teardown(ConnectionStatus status, const std::string& message) {
        const bool wasActive = serverConn >= 0 || loggedIn || owner.status_.load() == ConnectionStatus::Connecting;
        loggedIn = false;
        loginDeadline = 0;
        for (auto& [id, d] : downloads) {
            if (d.stage != TransferStage::Done && d.stage != TransferStage::Failed && d.stage != TransferStage::Cancelled) {
                failDownload(d, "Soulseek disconnected", false);
            }
        }
        for (auto& [id, s] : searches) {
            if (!s.done) finishSearch(s);
        }
        searches.clear();
        searchTokens.clear();
        for (auto& [key, r] : peerRequests) {
            for (auto& w : r.waiters) w(-1, "Disconnected");
        }
        peerRequests.clear();
        indirectTokens.clear();
        peerByUser.clear();
        transferTokens.clear();
        for (auto& [id, c] : conns) {
            if (!c.closed) {
                c.closed = true;
                ::close(c.fd);
            }
        }
        serverConn = -1;
        if (listenFd >= 0) {
            ::close(listenFd);
            listenFd = -1;
        }
        indirectDials = 0;
        if (wasActive && !message.empty()) setStatus(status, message);
        else owner.status_.store(status);
    }

    void handleServer(uint32_t code, const uint8_t* body, size_t size) {
        Reader r(body, size);
        switch (code) {
            case kLogin: {
                const bool success = r.boolean();
                if (success) {
                    loggedIn = true;
                    loginDeadline = 0;
                    sendServer(kSetWaitPort, Writer().u32(static_cast<uint32_t>(listenPort)));
                    sendServer(kSetStatus, Writer().u32(2));
                    sendServer(kSharedFoldersFiles, Writer().u32(0).u32(0));
                    nextPing = nowMs() + 60000;
                    setStatus(ConnectionStatus::Connected, "Connected as " + user);
                } else {
                    const std::string reason = r.remaining() >= 4 ? r.string() : std::string();
                    const std::string message = reason == "INVALIDPASS"       ? "Soulseek rejected the password for this username."
                                                : reason == "INVALIDUSERNAME" ? "Soulseek rejected this username."
                                                : "Soulseek login failed" + (reason.empty() ? std::string(".") : ": " + reason);
                    teardown(ConnectionStatus::Error, message);
                }
                break;
            }
            case kGetPeerAddress: {
                const std::string name = r.string();
                const uint32_t ip = r.u32();
                const uint32_t port = r.u32();
                auto it = peerRequests.find(lowerKey(name));
                if (it == peerRequests.end() || it->second.directConn >= 0) break;
                if (ip == 0 || port == 0 || port > 65535) {
                    it->second.directFailed = true;
                    maybeFailRequest(it->first);
                    break;
                }
                Conn* c = dial(ip, static_cast<int>(port), Kind::DialDirectP, ms(5000));
                if (!c) {
                    it->second.directFailed = true;
                    maybeFailRequest(it->first);
                    break;
                }
                c->user = it->second.user;
                it->second.directConn = c->id;
                break;
            }
            case kConnectToPeer: {
                const std::string name = r.string();
                const std::string type = upperKey(r.string());
                const uint32_t ip = r.u32();
                const uint32_t port = r.u32();
                const uint32_t tok = r.u32();
                onConnectToPeer(name, type, ip, port, tok);
                break;
            }
            case kRelogged:
                teardown(ConnectionStatus::Error,
                         "This Soulseek account was signed in from another client. Soulseek allows one session per account.");
                break;
            case kCantConnectToPeer: {
                const uint32_t tok = r.u32();
                auto it = indirectTokens.find(tok);
                if (it == indirectTokens.end()) break;
                auto req = peerRequests.find(it->second);
                indirectTokens.erase(it);
                if (req != peerRequests.end()) {
                    req->second.indirectFailed = true;
                    maybeFailRequest(req->first);
                }
                break;
            }
            default: break;  // rooms, privileges, distributed network: not for Harmony
        }
    }

    bool searchWindowOpen() const {
        for (const auto& [id, s] : searches) {
            if (!s.done) return true;
        }
        return false;
    }

    bool downloadingFrom(const std::string& name) const {
        const std::string key = lowerKey(name);
        for (const auto& [id, d] : downloads) {
            if (lowerKey(d.candidate.username) == key && d.stage < TransferStage::Done) return true;
        }
        return false;
    }

    void onConnectToPeer(const std::string& name, const std::string& type, uint32_t ip, uint32_t port, uint32_t tok) {
        // "D" (the distributed search network) is ignored: Harmony does not join it.
        if (type != "P" && type != "F") return;
        // A "P" call-back only matters while a search listens, or from a peer we download from.
        if (type == "P" && !searchWindowOpen() && !downloadingFrom(name)) return;
        if (indirectDials >= kMaxIndirectDials) return;
        if (port == 0 || port > 65535 || ip == 0) {
            sendServer(kCantConnectToPeer, Writer().u32(tok).string(name));
            return;
        }
        Conn* c = dial(ip, static_cast<int>(port), Kind::DialIndirect, ms(6000));
        if (!c) {
            sendServer(kCantConnectToPeer, Writer().u32(tok).string(name));
            return;
        }
        ++indirectDials;
        c->user = name;
        c->indirectType = type;
        c->indirectToken = tok;
    }

    // ---- Peer connections -------------------------------------------------------------------

    void handleInit(Conn& c, uint8_t code, const uint8_t* body, size_t size) {
        Reader r(body, size);
        if (code == kPeerInit) {
            c.user = r.string();
            const std::string type = upperKey(r.string());
            c.deadline = 0;
            if (type == "P") {
                c.kind = Kind::PeerP;
                registerPeer(c.id);
            } else if (type == "F") {
                c.kind = Kind::FileF;
                process(c.id);  // the file token may already be buffered
            } else {
                closeConn(c.id, "unsupported connection type");
            }
            return;
        }
        if (code == kPierceFirewall) {
            const uint32_t tok = r.u32();
            auto it = indirectTokens.find(tok);
            if (it == indirectTokens.end()) {
                closeConn(c.id, "unknown firewall token");
                return;
            }
            auto req = peerRequests.find(it->second);
            c.user = req != peerRequests.end() ? req->second.user : it->second;
            indirectTokens.erase(it);
            c.kind = Kind::PeerP;
            c.deadline = 0;
            registerPeer(c.id);
            return;
        }
        closeConn(c.id, "unknown init message");
    }

    void registerPeer(int id) {
        Conn* c = conn(id);
        if (!c) return;
        const std::string key = lowerKey(c->user);
        auto live = peerByUser.find(key);
        int chosen = id;
        if (live != peerByUser.end() && live->second != id && conn(live->second)) {
            chosen = live->second;  // keep the established one; this one still delivers its messages
        } else {
            peerByUser[key] = id;
        }
        auto req = peerRequests.find(key);
        if (req != peerRequests.end()) {
            PeerRequest done = std::move(req->second);
            peerRequests.erase(req);
            indirectTokens.erase(done.token);
            if (done.directConn >= 0 && done.directConn != chosen && done.directConn != id) {
                closeConn(done.directConn, "superseded");
            }
            for (auto& w : done.waiters) w(chosen, "");
        }
    }

    /** Gets a P connection to [name]: an existing one, or a direct and an indirect attempt at once. */
    void requestPeer(const std::string& name, std::function<void(int, const std::string&)> done) {
        const std::string key = lowerKey(name);
        auto live = peerByUser.find(key);
        if (live != peerByUser.end() && conn(live->second)) {
            done(live->second, "");
            return;
        }
        auto existing = peerRequests.find(key);
        if (existing != peerRequests.end()) {
            existing->second.waiters.push_back(std::move(done));
            return;
        }
        PeerRequest req;
        req.user = name;
        req.token = token();
        req.deadline = nowMs() + ms(12000);
        req.waiters.push_back(std::move(done));
        indirectTokens[req.token] = key;
        sendServer(kConnectToPeer, Writer().u32(req.token).string(name).string("P"));
        sendServer(kGetPeerAddress, Writer().string(name));
        peerRequests.emplace(key, std::move(req));
    }

    void maybeFailRequest(const std::string& key) {
        auto it = peerRequests.find(key);
        if (it == peerRequests.end() || !(it->second.directFailed && it->second.indirectFailed)) return;
        failRequest(key, "Could not connect to " + it->second.user);
    }

    void failRequest(const std::string& key, const std::string& why) {
        auto it = peerRequests.find(key);
        if (it == peerRequests.end()) return;
        PeerRequest req = std::move(it->second);
        peerRequests.erase(it);
        indirectTokens.erase(req.token);
        if (req.directConn >= 0) closeConn(req.directConn, "gave up");
        for (auto& w : req.waiters) w(-1, why);
    }

    void handlePeer(int id, uint32_t code, const uint8_t* body, size_t size) {
        Conn* c = conn(id);
        if (!c) return;
        Reader r(body, size);
        switch (code) {
            case kFileSearchResponse: onSearchResponse(body, size); break;
            case kTransferRequest: {
                const uint32_t direction = r.u32();
                const uint32_t tok = r.u32();
                const std::string filename = r.string();
                if (direction != 1) {
                    sendPeer(*c, kTransferResponse, Writer().u32(tok).boolean(false).string("Cancelled"));
                    break;
                }
                const uint64_t size64 = r.remaining() >= 8 ? r.u64() : 0;
                Download* d = findDownload(c->user, filename);
                if (!d || !(d->stage == TransferStage::Requested || d->stage == TransferStage::Queued ||
                            d->stage == TransferStage::Accepted)) {
                    sendPeer(*c, kTransferResponse, Writer().u32(tok).boolean(false).string("Cancelled"));
                    break;
                }
                if (d->transferToken != 0) transferTokens.erase(d->transferToken);
                d->transferToken = tok;
                d->expected = size64 > 0 ? size64 : d->candidate.size;
                transferTokens[tok] = d->id;
                sendPeer(*c, kTransferResponse, Writer().u32(tok).boolean(true));
                setStage(*d, TransferStage::Accepted);
                d->deadline = nowMs() + ms(d->patient ? 30000 : 15000);
                break;
            }
            case kPlaceInQueueResponse: {
                const std::string filename = r.string();
                const uint32_t place = r.u32();
                Download* d = findDownload(c->user, filename);
                if (!d || !(d->stage == TransferStage::Requested || d->stage == TransferStage::Queued)) break;
                d->queuePlace = static_cast<int>(place);
                if (place > 0) setStage(*d, TransferStage::Queued);
                const int limit = d->patient ? 30 : 1;
                if (static_cast<int>(place) > limit) {
                    failDownload(*d, c->user + " placed this request at queue #" + std::to_string(place), true);
                }
                break;
            }
            case kUploadDenied: {
                const std::string filename = r.string();
                const std::string reason = r.remaining() >= 4 ? r.string() : std::string("Upload denied");
                if (Download* d = findDownload(c->user, filename)) {
                    if (d->stage < TransferStage::Done) failDownload(*d, c->user + " denied the file: " + reason, false);
                }
                break;
            }
            case kUploadFailed: {
                const std::string filename = r.string();
                if (Download* d = findDownload(c->user, filename)) {
                    if (d->stage < TransferStage::Transferring) failDownload(*d, c->user + " could not start this upload", false);
                }
                break;
            }
            case kQueueUpload: {
                const std::string filename = r.string();
                sendPeer(*c, kUploadDenied, Writer().string(filename).string("File not shared."));
                break;
            }
            case kSharedFileListRequest: {
                // An empty share: Harmony for iOS only downloads.
                const auto payload = deflateZlib(Writer().u32(0).u32(0).u32(0).bytes());
                queue(*c, frame(kSharedFileListResponse, payload));
                break;
            }
            case kUserInfoRequest:
                sendPeer(*c, kUserInfoResponse,
                         Writer().string("Harmony for iOS").boolean(false).u32(0).u32(0).boolean(false));
                break;
            default: break;
        }
    }

    // ---- Search ------------------------------------------------------------------------------

    void doSearch(uint32_t id, const std::string& query, const SearchOptions& options) {
        Search s;
        s.id = id;
        s.query = query;
        s.options = options;
        const int64_t now = nowMs();
        const double minWindow = options.mode == SearchMode::Quick ? 3200 : options.mode == SearchMode::Balanced ? 5000 : 7000;
        const double maxWindow = options.mode == SearchMode::Quick ? 5200 : options.mode == SearchMode::Balanced ? 8800 : 12000;
        s.minEnd = now + ms(minWindow);
        s.maxEnd = now + ms(maxWindow);
        if (!loggedIn) {
            s.done = true;
            emitSearch(s);
            return;
        }
        for (const auto& variant : buildSearchVariants(query, options.mode)) {
            const uint32_t tok = token();
            s.tokens.push_back(tok);
            searchTokens[tok] = id;
            sendServer(kFileSearch, Writer().u32(tok).string(variant));
        }
        searches.emplace(id, std::move(s));
    }

    void onSearchResponse(const uint8_t* body, size_t size) {
        const SearchResponse response = parseSearchResponse(body, size);
        auto t = searchTokens.find(response.token);
        if (t == searchTokens.end()) return;
        auto it = searches.find(t->second);
        if (it == searches.end() || it->second.done) return;
        Search& s = it->second;
        s.peers.insert(lowerKey(response.username));
        s.files += static_cast<int>(response.files.size());
        for (const auto& file : response.files) {
            Candidate candidate;
            if (!scoreCandidate(s.query, response.username, file, s.options, response.slotFree, response.averageSpeed,
                                response.queueLength, &candidate)) {
                continue;
            }
            s.candidates[response.username + '\0' + file.filename] = std::move(candidate);
        }
        s.dirty = true;
    }

    std::vector<Candidate> ranked(const Search& s) const {
        std::vector<Candidate> all;
        all.reserve(s.candidates.size());
        for (const auto& [key, c] : s.candidates) all.push_back(c);
        return selectRecommendations(std::move(all), s.options.onlyFreeSlots, kMaxRecommendations);
    }

    void emitSearch(Search& s) {
        Event e;
        e.kind = Event::Kind::Search;
        e.searchId = s.id;
        e.candidates = ranked(s);
        e.searchDone = s.done;
        e.peersResponded = static_cast<int>(s.peers.size());
        e.filesSeen = s.files;
        s.lastEmit = nowMs();
        s.dirty = false;
        emit(e);
    }

    void finishSearch(Search& s) {
        s.done = true;
        s.graceEnd = nowMs() + ms(12000);
        emitSearch(s);
    }

    // ---- Downloads -----------------------------------------------------------------------------

    Download* findDownload(const std::string& name, const std::string& filename) {
        const std::string key = lowerKey(name);
        for (auto& [id, d] : downloads) {
            if (d.candidate.filename == filename && lowerKey(d.candidate.username) == key && d.stage < TransferStage::Done) {
                return &d;
            }
        }
        return nullptr;
    }

    void emitTransfer(Download& d, const std::string& message = std::string(), const std::string& path = std::string(),
                      bool queued = false) {
        Event e;
        e.kind = Event::Kind::Transfer;
        e.transferId = d.id;
        e.stage = d.stage;
        e.bytes = d.offset;
        e.totalBytes = d.expected > 0 ? d.expected : d.candidate.size;
        e.bytesPerSecond = d.speed;
        e.queuePlace = d.queuePlace;
        e.message = message;
        e.path = path;
        e.queuedElsewhere = queued;
        e.username = d.candidate.username;
        d.lastEmit = nowMs();
        emit(e);
    }

    void setStage(Download& d, TransferStage stage) {
        if (d.stage == stage) return;
        d.stage = stage;
        emitTransfer(d);
    }

    void releaseFile(Download& d) {
        if (d.file) {
            std::fflush(d.file);
            std::fclose(d.file);
            d.file = nullptr;
        }
        if (d.fileConn >= 0) {
            const int fc = d.fileConn;
            d.fileConn = -1;
            if (Conn* c = conn(fc)) {
                c->downloadId = 0;
                closeConn(fc, "transfer over");
            }
        }
        if (d.transferToken != 0) transferTokens.erase(d.transferToken);
    }

    void failDownload(Download& d, const std::string& why, bool queued) {
        if (d.stage >= TransferStage::Done) return;
        releaseFile(d);
        d.stage = TransferStage::Failed;
        d.speed = 0;
        emitTransfer(d, why, std::string(), queued);
    }

    void doDownload(uint32_t id, const Candidate& candidate, bool patient, const std::string& fileName) {
        Download d;
        d.id = id;
        d.candidate = candidate;
        d.patient = patient;
        d.expected = candidate.size;
        // The partial is named by content (leaf + size), so another peer's copy of the same file resumes it.
        makeDirectories(config.partialDirectory);
        d.partialPath = config.partialDirectory + "/" + md5Hex(lowerKey(candidate.leafName) + "|" + std::to_string(candidate.size)) + ".part";
        d.finalBase = sanitizeFileName(fileName.empty() ? stripExtension(candidate.leafName) : fileName);
        d.lastProgress = nowMs();
        auto [it, inserted] = downloads.emplace(id, std::move(d));
        Download& stored = it->second;
        emitTransfer(stored);
        if (!loggedIn) {
            failDownload(stored, "Not connected to Soulseek", false);
            return;
        }
        if (findOtherActive(stored)) {
            failDownload(stored, "This file is already downloading", false);
            return;
        }
        requestPeer(candidate.username, [this, id](int connId, const std::string& error) {
            auto found = downloads.find(id);
            if (found == downloads.end() || found->second.stage != TransferStage::ConnectingPeer) return;
            Download& dl = found->second;
            Conn* c = connId >= 0 ? conn(connId) : nullptr;
            if (!c) {
                failDownload(dl, error.empty() ? "Could not connect to the peer" : error, false);
                return;
            }
            sendPeer(*c, kQueueUpload, Writer().string(dl.candidate.filename));
            sendPeer(*c, kPlaceInQueueRequest, Writer().string(dl.candidate.filename));
            const int64_t now = nowMs();
            dl.deadline = now + ms(dl.patient ? 6 * 60000 : (dl.candidate.freeUploadSlot || dl.candidate.queueLength == 0) ? 12000 : 8000);
            dl.queuePollInterval = ms(1200);
            dl.nextQueuePoll = now + dl.queuePollInterval;
            dl.lastKnock = now;
            setStage(dl, TransferStage::Requested);
        });
    }

    bool findOtherActive(const Download& d) const {
        for (const auto& [id, other] : downloads) {
            if (id != d.id && other.partialPath == d.partialPath && other.stage < TransferStage::Done) return true;
        }
        return false;
    }

    void fileToken(Conn& c, uint32_t tok) {
        auto t = transferTokens.find(tok);
        if (t == transferTokens.end()) {
            closeConn(c.id, "unknown transfer token");
            return;
        }
        auto it = downloads.find(t->second);
        if (it == downloads.end() || lowerKey(it->second.candidate.username) != lowerKey(c.user) ||
            !(it->second.stage == TransferStage::Accepted)) {
            closeConn(c.id, "unexpected file connection");
            return;
        }
        Download& d = it->second;
        uint64_t offset = fileSizeOf(d.partialPath);
        if (offset > d.expected) {
            std::remove(d.partialPath.c_str());
            offset = 0;
        }
        d.file = std::fopen(d.partialPath.c_str(), offset > 0 ? "r+b" : "wb");
        if (!d.file) {
            failDownload(d, "Could not write the download", false);
            return;
        }
        std::fseek(d.file, static_cast<long>(offset), SEEK_SET);
        d.offset = offset;
        d.fileConn = c.id;
        c.downloadId = d.id;
        d.lastProgress = nowMs();
        d.speedBytes = offset;
        d.speedTime = d.lastProgress;
        queue(c, Writer().u64(offset).bytes());
        setStage(d, TransferStage::Transferring);
        if (offset >= d.expected) completeDownload(d);
    }

    void fileData(Conn& c, const uint8_t* data, size_t size) {
        auto it = downloads.find(c.downloadId);
        if (it == downloads.end() || it->second.stage != TransferStage::Transferring || !it->second.file) {
            closeConn(c.id, "no transfer");
            return;
        }
        Download& d = it->second;
        const uint64_t room = d.expected - d.offset;
        const size_t n = static_cast<size_t>(std::min<uint64_t>(room, size));
        if (n > 0 && std::fwrite(data, 1, n, d.file) != n) {
            failDownload(d, "Could not write the download (storage full?)", false);
            return;
        }
        d.offset += n;
        d.lastProgress = nowMs();
        if (d.offset >= d.expected) completeDownload(d);
    }

    std::string uniquePath(const std::string& base, const std::string& extension) {
        makeDirectories(config.downloadDirectory);
        std::string path = config.downloadDirectory + "/" + base + "." + extension;
        for (int n = 2; fileExists(path) && n < 1000; ++n) {
            path = config.downloadDirectory + "/" + base + " (" + std::to_string(n) + ")." + extension;
        }
        return path;
    }

    void completeDownload(Download& d) {
        releaseFile(d);
        d.speed = 0;
        setStage(d, TransferStage::Verifying);
        const std::string problem = harmony::ingest::verifyAudioFile(d.partialPath, d.candidate.extension, d.expected);
        if (!problem.empty()) {
            std::remove(d.partialPath.c_str());  // a bad container must not be resumed by the next source
            failDownload(d, "The downloaded file failed the check: " + problem, false);
            return;
        }
        const std::string path = uniquePath(d.finalBase, d.candidate.extension);
        if (!moveFile(d.partialPath, path)) {
            failDownload(d, "Could not move the finished file into the library", false);
            return;
        }
        d.stage = TransferStage::Done;
        emitTransfer(d, std::string(), path);
    }

    void doCancel(uint32_t id) {
        auto it = downloads.find(id);
        if (it == downloads.end() || it->second.stage >= TransferStage::Done) return;
        Download& d = it->second;
        releaseFile(d);
        std::remove(d.partialPath.c_str());
        d.stage = TransferStage::Cancelled;
        d.speed = 0;
        emitTransfer(d, "Cancelled");
    }

    // ---- Timers ---------------------------------------------------------------------------------

    void timers() {
        const int64_t now = nowMs();
        if (loginDeadline != 0 && now > loginDeadline) {
            teardown(ConnectionStatus::Error, "Soulseek did not answer the login");
        }
        if (loggedIn && now >= nextPing) {
            sendServer(kServerPing, Writer());
            nextPing = now + 60000;
        }
        for (auto& [id, c] : conns) {
            if (!c.closed && c.deadline != 0 && now > c.deadline) closeConn(id, "timed out");
        }
        std::vector<std::string> expired;
        for (auto& [key, r] : peerRequests) {
            if (now > r.deadline) expired.push_back(key);
        }
        for (const auto& key : expired) failRequest(key, "Could not connect to the peer in time");

        for (auto it = searches.begin(); it != searches.end();) {
            Search& s = it->second;
            if (!s.done) {
                if (now >= s.maxEnd || (now >= s.minEnd && hasSufficientResults(ranked(s)))) {
                    finishSearch(s);
                } else if (s.dirty && now - s.lastEmit >= 400) {
                    emitSearch(s);
                }
                ++it;
            } else if (now >= s.graceEnd) {
                for (uint32_t tok : s.tokens) searchTokens.erase(tok);
                it = searches.erase(it);
            } else {
                ++it;
            }
        }

        for (auto it = downloads.begin(); it != downloads.end();) {
            Download& d = it->second;
            switch (d.stage) {
                case TransferStage::Requested:
                case TransferStage::Queued: {
                    if (now > d.deadline) {
                        failDownload(d, d.candidate.username + " has not answered the request", true);
                        break;
                    }
                    Conn* c = nullptr;
                    auto p = peerByUser.find(lowerKey(d.candidate.username));
                    if (p != peerByUser.end()) c = conn(p->second);
                    if (c && now >= d.nextQueuePoll) {
                        sendPeer(*c, kPlaceInQueueRequest, Writer().string(d.candidate.filename));
                        d.queuePollInterval = std::min<int64_t>(d.queuePollInterval * 2, ms(10000));
                        d.nextQueuePoll = now + d.queuePollInterval;
                    }
                    if (c && d.patient && now - d.lastKnock >= 45000) {
                        // Re-knocking lands on a slot the peer freed without telling us.
                        sendPeer(*c, kQueueUpload, Writer().string(d.candidate.filename));
                        d.lastKnock = now;
                    }
                    break;
                }
                case TransferStage::Accepted:
                    if (now > d.deadline) failDownload(d, d.candidate.username + " accepted but did not open the transfer", false);
                    break;
                case TransferStage::Transferring: {
                    if (now - d.lastProgress > ms(60000)) {
                        failDownload(d, "No progress for a minute", false);
                        break;
                    }
                    const int64_t elapsed = now - d.speedTime;
                    if (elapsed >= 500) {
                        const double instant = static_cast<double>(d.offset - d.speedBytes) * 1000.0 / static_cast<double>(elapsed);
                        d.speed = d.speed <= 0 ? instant : d.speed * 0.65 + instant * 0.35;
                        d.speedBytes = d.offset;
                        d.speedTime = now;
                    }
                    if (now - d.lastEmit >= 250) emitTransfer(d);
                    break;
                }
                default: break;
            }
            // Finished transfers are forgotten once reported.
            if (d.stage >= TransferStage::Done) {
                it = downloads.erase(it);
            } else {
                ++it;
            }
        }

        if (now >= nextHousekeeping) {
            nextHousekeeping = now + 10000;
            // Idle peer connections nobody downloads from are given back.
            for (auto& [id, c] : conns) {
                if (c.closed || c.kind != Kind::PeerP) continue;
                if (now - c.lastActivity > 90000 && !downloadingFrom(c.user)) closeConn(id, "idle");
            }
        }
    }
};

// ---------------------------------------------------------------------------
// Engine
// ---------------------------------------------------------------------------

Engine::Engine(EngineConfig config, Listener listener)
    : impl_(std::make_unique<Impl>(*this, std::move(config), std::move(listener))) {}

Engine::~Engine() { stop(); }

void Engine::start() {
    if (impl_->running.exchange(true)) return;
    impl_->thread = std::thread([this] { impl_->run(); });
}

void Engine::stop() {
    if (!impl_->running.exchange(false)) return;
    impl_->post([] {});
    if (impl_->thread.joinable()) impl_->thread.join();
}

void Engine::connect(const std::string& username, const std::string& password) {
    impl_->post([this, username, password] { impl_->doConnect(username, password); });
}

void Engine::disconnect() {
    impl_->post([this] { impl_->teardown(ConnectionStatus::Disconnected, "Disconnected"); });
}

uint32_t Engine::search(const std::string& query, const SearchOptions& options) {
    const uint32_t id = impl_->nextPublicId++;
    impl_->post([this, id, query, options] { impl_->doSearch(id, query, options); });
    return id;
}

void Engine::cancelSearch(uint32_t searchId) {
    impl_->post([this, searchId] {
        auto it = impl_->searches.find(searchId);
        if (it != impl_->searches.end() && !it->second.done) impl_->finishSearch(it->second);
    });
}

uint32_t Engine::download(const Candidate& candidate, bool patient, const std::string& fileName) {
    const uint32_t id = impl_->nextPublicId++;
    impl_->post([this, id, candidate, patient, fileName] { impl_->doDownload(id, candidate, patient, fileName); });
    return id;
}

void Engine::cancelTransfer(uint32_t transferId) {
    impl_->post([this, transferId] { impl_->doCancel(transferId); });
}

}  // namespace harmony::slsk
