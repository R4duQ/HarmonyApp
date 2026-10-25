// Host tests of the iOS Soulseek client: the protocol and ranking, then the
// whole engine against a fake Soulseek server and fake peers on localhost
// (login, search replies both direct and through ConnectToPeer/PierceFirewall,
// a direct download, an indirect download that resumes a partial file, and a
// file that fails the container check).

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <functional>
#include <iterator>
#include <map>
#include <mutex>
#include <random>
#include <string>
#include <thread>
#include <vector>

#include "SlskEngine.h"
#include "SlskProtocol.h"

using namespace harmony::slsk;

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

// ---- Protocol ------------------------------------------------------------------

void protocolBasics() {
    check(md5Hex("") == "d41d8cd98f00b204e9800998ecf8427e", "md5 empty: %s", md5Hex("").c_str());
    check(md5Hex("abc") == "900150983cd24fb0d6963f7d28e17f72", "md5 abc");
    check(md5Hex("The quick brown fox jumps over the lazy dog") == "9e107d9d372bb6826bd81d3542a419d6", "md5 fox");

    check(normalizeSearchText("Șapte Zile \xE2\x80\x93 Live!") == "sapte zile live", "normalize: '%s'",
          normalizeSearchText("Șapte Zile \xE2\x80\x93 Live!").c_str());
    check(normalizeSearchText("Björk - Jóga") == "bjork joga", "normalize bjork");

    const auto variants = buildSearchVariants("The Weeknd - The Hills (Official Audio)", SearchMode::Deep);
    check(variants.size() >= 3, "deep variants: %zu", variants.size());
    check(variants.front() == "The Weeknd - The Hills (Official Audio)", "first variant is the query");
    bool hasCompact = false;
    for (const auto& v : variants) hasCompact |= v == "weeknd hills";
    check(hasCompact, "compact variant present");
    check(buildSearchVariants("abc", SearchMode::Quick).size() == 1, "quick: one variant");

    SearchResponse response;
    response.username = "alice";
    response.token = 1234;
    response.slotFree = true;
    response.averageSpeed = 500000;
    response.queueLength = 0;
    SearchFile f;
    f.filename = "Music\\Artist\\01 Artist - Song.flac";
    f.size = 30000000;
    f.extension = "flac";
    f.attributes = {{1, 200}, {4, 96000}, {5, 24}};
    response.files.push_back(f);
    const auto wire = buildSearchResponse(response);
    const auto parsed = parseSearchResponse(wire.data(), wire.size());
    check(parsed.username == "alice" && parsed.token == 1234 && parsed.files.size() == 1, "search response round trip");
    check(parsed.files[0].attributes.at(4) == 96000, "attributes survive");

    bool threw = false;
    try {
        std::vector<uint8_t> junk = {1, 2, 3, 4, 5};
        parseSearchResponse(junk.data(), junk.size());
    } catch (const ProtocolError&) {
        threw = true;
    }
    check(threw, "corrupt search response is rejected");
}

void ranking() {
    SearchOptions options;
    options.format = FormatPreference::FlacOnly;
    SearchFile exact{"Music\\Artist\\01 Artist - Song.flac", 30000000, "flac", {{1, 200}, {4, 44100}, {5, 16}}};
    SearchFile live{"Music\\Artist\\05 Artist - Song (Live).flac", 30000000, "flac", {{1, 260}}};
    SearchFile mp3{"Music\\Artist\\01 Artist - Song.mp3", 8000000, "mp3", {{0, 320}}};
    Candidate a, b, c;
    check(scoreCandidate("Artist - Song", "u1", exact, options, false, 0, 3, &a), "exact accepted");
    check(scoreCandidate("Artist - Song", "u2", live, options, false, 0, 3, &b), "live accepted");
    check(!scoreCandidate("Artist - Song", "u3", mp3, options, true, 900000, 0, &c), "mp3 refused when FLAC only");
    check(a.score > b.score, "studio beats live: %d vs %d", a.score, b.score);

    options.targetDurationSeconds = 201;
    Candidate d, e;
    check(scoreCandidate("Artist - Song", "u1", exact, options, false, 0, 3, &d), "±2 s accepted");
    check(d.score >= a.score, "exact length ranks up");
    check(d.recommendation.find("exact length") != std::string::npos, "says exact length");
    check(!scoreCandidate("Artist - Song", "u2", live, options, true, 900000, 0, &e), "another edit (59 s off) dropped");

    std::vector<Candidate> many;
    for (int i = 0; i < 15; ++i) {
        Candidate x = a;
        x.id = "id" + std::to_string(i);
        x.username = i < 12 ? "same" : "other" + std::to_string(i);
        x.score = 90 - i;
        many.push_back(x);
    }
    const auto chosen = selectRecommendations(many, false, 10);
    check(chosen.size() == 10, "ten recommendations");
    check(chosen[1].username != "same", "one per peer first");
}

// ---- Sockets for the fakes ---------------------------------------------------------

int listenLocal(int* port) {
    const int fd = ::socket(AF_INET, SOCK_STREAM, 0);
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    ::bind(fd, reinterpret_cast<sockaddr*>(&addr), sizeof addr);
    ::listen(fd, 16);
    socklen_t len = sizeof addr;
    getsockname(fd, reinterpret_cast<sockaddr*>(&addr), &len);
    *port = ntohs(addr.sin_port);
    return fd;
}

int connectLocal(int port) {
    const int fd = ::socket(AF_INET, SOCK_STREAM, 0);
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = htons(static_cast<uint16_t>(port));
    if (::connect(fd, reinterpret_cast<sockaddr*>(&addr), sizeof addr) != 0) {
        ::close(fd);
        return -1;
    }
    return fd;
}

bool readExact(int fd, void* out, size_t n) {
    auto* p = static_cast<uint8_t*>(out);
    while (n > 0) {
        const ssize_t got = ::recv(fd, p, n, 0);
        if (got <= 0) return false;
        p += got;
        n -= static_cast<size_t>(got);
    }
    return true;
}

void writeAll(int fd, const std::vector<uint8_t>& data) {
    size_t at = 0;
    while (at < data.size()) {
        const ssize_t n = ::send(fd, data.data() + at, data.size() - at, 0);
        if (n <= 0) return;
        at += static_cast<size_t>(n);
    }
}

/** Reads one [len][code u32][body] frame. */
bool readFrame(int fd, uint32_t* code, std::vector<uint8_t>* body) {
    uint8_t head[8];
    if (!readExact(fd, head, 4)) return false;
    const uint32_t len = Reader(head, 4).u32();
    if (len < 4 || len > (64u << 20)) return false;
    if (!readExact(fd, head + 4, 4)) return false;
    *code = Reader(head + 4, 4).u32();
    body->resize(len - 4);
    return body->empty() || readExact(fd, body->data(), body->size());
}

/** Reads one peer-init frame [len][code u8][body]. */
bool readInit(int fd, uint8_t* code, std::vector<uint8_t>* body) {
    uint8_t head[5];
    if (!readExact(fd, head, 5)) return false;
    const uint32_t len = Reader(head, 4).u32();
    *code = head[4];
    body->resize(len - 1);
    return body->empty() || readExact(fd, body->data(), body->size());
}

std::vector<uint8_t> flacBytes(size_t total, unsigned seed) {
    std::vector<uint8_t> f = {'f', 'L', 'a', 'C', 0x80, 0, 0, 34};
    std::vector<uint8_t> info(34, 0);
    const uint32_t rate = 44100;
    info[10] = static_cast<uint8_t>(rate >> 12);
    info[11] = static_cast<uint8_t>(rate >> 4);
    info[12] = static_cast<uint8_t>(((rate & 0xf) << 4) | (1 << 1));
    info[13] = 0xf0;
    info[17] = 100;
    f.insert(f.end(), info.begin(), info.end());
    std::mt19937 random(seed);
    while (f.size() < total) f.push_back(static_cast<uint8_t>(random()));
    return f;
}

std::vector<uint8_t> readFile(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    return std::vector<uint8_t>((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
}

// ---- The fake network -------------------------------------------------------------------

struct FakePeer {
    std::string name;
    std::map<std::string, std::vector<uint8_t>> files;  // Soulseek path -> bytes
    std::atomic<uint64_t> lastOffset{UINT64_MAX};
    int listenFd = -1;
    int port = 0;
    int engineListenPort = 0;
    std::mutex threadsLock;
    std::vector<std::thread> threads;
    std::atomic<bool> stop{false};

    void spawn(std::function<void()> f) {
        std::lock_guard<std::mutex> guard(threadsLock);
        threads.emplace_back(std::move(f));
    }
    std::mutex pierceLock;
    std::function<void(int fd)> onPierce;

    /** Serves one P connection: QueueUpload -> TransferRequest -> F connection. */
    void serveP(int fd) {
        uint32_t code;
        std::vector<uint8_t> body;
        while (readFrame(fd, &code, &body)) {
            if (code != kQueueUpload) continue;
            Reader r(body);
            const std::string filename = r.string();
            auto it = files.find(filename);
            if (it == files.end()) {
                writeAll(fd, frame(kUploadDenied, Writer().string(filename).string("File not shared.").bytes()));
                continue;
            }
            const uint32_t tok = 77;
            writeAll(fd, frame(kTransferRequest, Writer().u32(1).u32(tok).string(filename).u64(it->second.size()).bytes()));
            if (!readFrame(fd, &code, &body) || code != kTransferResponse) {
                // Skip a PlaceInQueueRequest that may come first.
                while (code == kPlaceInQueueRequest && readFrame(fd, &code, &body)) {
                }
                if (code != kTransferResponse) return;
            }
            Reader tr(body);
            if (tr.u32() != tok || !tr.boolean()) return;
            // Open the file connection to the engine and stream from the requested offset.
            const int ff = connectLocal(engineListenPort);
            writeAll(ff, initFrame(kPeerInit, Writer().string(name).string("F").u32(0).bytes()));
            writeAll(ff, Writer().u32(tok).bytes());
            uint8_t off[8];
            if (!readExact(ff, off, 8)) {
                ::close(ff);
                return;
            }
            const uint64_t offset = Reader(off, 8).u64();
            lastOffset = offset;
            std::vector<uint8_t> rest(it->second.begin() + static_cast<long>(offset), it->second.end());
            for (size_t at = 0; at < rest.size(); at += 7000) {
                writeAll(ff, std::vector<uint8_t>(rest.begin() + static_cast<long>(at),
                                                  rest.begin() + static_cast<long>(std::min(rest.size(), at + 7000))));
            }
            ::close(ff);
        }
        ::close(fd);
    }

    void listen() {
        listenFd = listenLocal(&port);
        spawn([this] {
            while (!stop) {
                const int fd = ::accept(listenFd, nullptr, nullptr);
                if (fd < 0) return;
                uint8_t code;
                std::vector<uint8_t> body;
                if (!readInit(fd, &code, &body)) {
                    ::close(fd);
                    continue;
                }
                if (code == kPierceFirewall) {
                    std::function<void(int)> handler;
                    {
                        std::lock_guard<std::mutex> guard(pierceLock);
                        handler = onPierce;
                    }
                    if (handler) {
                        spawn([handler, fd] { handler(fd); });
                    } else {
                        ::close(fd);
                    }
                    continue;
                }
                spawn([this, fd] { serveP(fd); });
            }
        });
    }

    /** Sends search results by calling the engine (an inbound P connection). */
    void replyDirect(uint32_t token, const std::vector<SearchFile>& found) {
        const int fd = connectLocal(engineListenPort);
        writeAll(fd, initFrame(kPeerInit, Writer().string(name).string("P").u32(0).bytes()));
        SearchResponse response;
        response.username = name;
        response.token = token;
        response.files = found;
        response.slotFree = true;
        response.averageSpeed = 1500000;
        writeAll(fd, frame(kFileSearchResponse, buildSearchResponse(response)));
        spawn([this, fd] { serveP(fd); });
    }

    /** Answers an indirect dial from the engine: it calls us and pierces with the token. */
    void acceptIndirect(std::function<void(int fd)> then) {
        std::lock_guard<std::mutex> guard(pierceLock);
        onPierce = std::move(then);
    }

    void shutdown() {
        stop = true;
        if (listenFd >= 0) {
            ::shutdown(listenFd, SHUT_RDWR);
            ::close(listenFd);
        }
        std::lock_guard<std::mutex> guard(threadsLock);
        for (auto& t : threads) {
            if (t.joinable()) t.detach();
        }
    }
};

struct FakeServer {
    int listenFd = -1;
    int port = 0;
    int client = -1;
    std::thread thread;
    std::mutex lock;
    std::vector<uint32_t> searchTokens;
    std::atomic<int> engineListenPort{0};
    std::atomic<bool> loginHashOk{false};
    std::map<std::string, int> addresses;  // user -> port (0: not directly reachable)
    std::mutex hookLock;
    std::function<void(const std::string& user, uint32_t token)> onConnectToPeer;
    std::mutex writeLock;

    void send(const std::vector<uint8_t>& bytes) {
        std::lock_guard<std::mutex> guard(writeLock);
        writeAll(client, bytes);
    }

    void start() {
        listenFd = listenLocal(&port);
        thread = std::thread([this] {
            client = ::accept(listenFd, nullptr, nullptr);
            uint32_t code;
            std::vector<uint8_t> body;
            while (readFrame(client, &code, &body)) {
                Reader r(body);
                if (code == kLogin) {
                    const std::string user = r.string();
                    const std::string pass = r.string();
                    r.u32();
                    loginHashOk = r.string() == md5Hex(user + pass);
                    send(frame(kLogin, Writer().boolean(true).string("Welcome").u32(0x7f000001).string("x").boolean(false).bytes()));
                } else if (code == kSetWaitPort) {
                    engineListenPort = static_cast<int>(r.u32());
                } else if (code == kFileSearch) {
                    std::lock_guard<std::mutex> guard(lock);
                    searchTokens.push_back(r.u32());
                } else if (code == kGetPeerAddress) {
                    const std::string user = r.string();
                    const int p = addresses.count(user) ? addresses[user] : 0;
                    send(frame(kGetPeerAddress, Writer().string(user).u32(p ? 0x7f000001 : 0).u32(static_cast<uint32_t>(p)).bytes()));
                } else if (code == kConnectToPeer) {
                    const uint32_t tok = r.u32();
                    const std::string user = r.string();
                    std::function<void(const std::string&, uint32_t)> hook;
                    {
                        std::lock_guard<std::mutex> guard(hookLock);
                        hook = onConnectToPeer;
                    }
                    if (hook) hook(user, tok);
                }
            }
        });
    }

    /** The server relays a peer's request that we call it (ConnectToPeer). */
    void relayConnectToPeer(const std::string& user, const std::string& type, int peerPort, uint32_t token) {
        send(frame(kConnectToPeer,
                   Writer().string(user).string(type).u32(0x7f000001).u32(static_cast<uint32_t>(peerPort)).u32(token).boolean(false).bytes()));
    }

    void shutdown() {
        if (client >= 0) ::shutdown(client, SHUT_RDWR);
        ::shutdown(listenFd, SHUT_RDWR);
        ::close(listenFd);
        if (thread.joinable()) thread.join();
        if (client >= 0) ::close(client);
    }
};

struct Recorder {
    std::mutex lock;
    std::condition_variable changed;
    std::vector<Event> events;

    void add(const Event& e) {
        std::lock_guard<std::mutex> guard(lock);
        events.push_back(e);
        changed.notify_all();
    }

    bool waitFor(const std::function<bool(const Event&)>& match, Event* out, int seconds = 10) {
        std::unique_lock<std::mutex> guard(lock);
        size_t seen = 0;
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(seconds);
        while (true) {
            for (; seen < events.size(); ++seen) {
                if (match(events[seen])) {
                    if (out) *out = events[seen];
                    return true;
                }
            }
            if (changed.wait_until(guard, deadline) == std::cv_status::timeout) return false;
        }
    }
};

void engineEndToEnd() {
    const std::string base = "/tmp/harmony_slsk_test";
    if (std::system(("rm -rf " + base).c_str()) != 0) std::fprintf(stderr, "could not clear %s\n", base.c_str());

    FakePeer alice;
    alice.name = "alice";
    const std::string aliceFile = "Music\\Artist\\01 Artist - Song.flac";
    alice.files[aliceFile] = flacBytes(300000, 1);
    alice.files["Music\\Artist\\bad.flac"] = std::vector<uint8_t>(5000, 'x');
    alice.listen();

    FakePeer bob;  // behind NAT: only reachable through ConnectToPeer
    bob.name = "bob";
    const std::string bobFile = "share/Artist/Artist - Song.flac";
    bob.files[bobFile] = flacBytes(250000, 2);
    bob.listen();

    FakeServer server;
    server.addresses = {{"alice", alice.port}, {"bob", 0}};
    server.start();

    Recorder recorder;
    EngineConfig config;
    config.serverHost = "127.0.0.1";
    config.serverPort = server.port;
    config.partialDirectory = base + "/partial";
    config.downloadDirectory = base + "/done";
    Engine engine(config, [&](const Event& e) { recorder.add(e); });
    engine.start();
    engine.connect("harmony", "secret");

    Event e;
    check(recorder.waitFor([](const Event& x) { return x.kind == Event::Kind::Connection && x.status == ConnectionStatus::Connected; }, &e),
          "logged in");
    check(server.loginHashOk, "login hash is md5(user + password)");
    for (int i = 0; i < 100 && server.engineListenPort == 0; ++i) std::this_thread::sleep_for(std::chrono::milliseconds(20));
    check(server.engineListenPort > 0, "listening port announced");
    alice.engineListenPort = bob.engineListenPort = server.engineListenPort;

    // Search: alice replies directly, bob through ConnectToPeer + PierceFirewall.
    SearchOptions options;
    options.mode = SearchMode::Quick;
    options.format = FormatPreference::FlacOnly;
    const uint32_t searchId = engine.search("Artist - Song", options);
    uint32_t token = 0;
    for (int i = 0; i < 100 && token == 0; ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
        std::lock_guard<std::mutex> guard(server.lock);
        if (!server.searchTokens.empty()) token = server.searchTokens.front();
    }
    check(token != 0, "search sent to the server");
    alice.replyDirect(token, {SearchFile{aliceFile, alice.files[aliceFile].size(), "flac", {{1, 200}, {4, 44100}, {5, 16}}},
                              SearchFile{"Music\\Artist\\01 Artist - Song.mp3", 4000000, "mp3", {}}});
    bob.acceptIndirect([&bob, token, bobFile](int fd) {
        SearchResponse response;
        response.username = "bob";
        response.token = token;
        response.files = {SearchFile{bobFile, bob.files[bobFile].size(), "flac", {{1, 200}}}};
        response.slotFree = true;
        writeAll(fd, frame(kFileSearchResponse, buildSearchResponse(response)));
        ::close(fd);
    });
    server.relayConnectToPeer("bob", "P", bob.port, 9001);

    check(recorder.waitFor([searchId](const Event& x) { return x.kind == Event::Kind::Search && x.searchId == searchId && x.searchDone; }, &e, 15),
          "search finished");
    check(e.candidates.size() == 2, "two FLAC candidates (the MP3 filtered): %zu", e.candidates.size());
    check(e.peersResponded == 2, "two peers answered: %d", e.peersResponded);
    Candidate fromAlice, fromBob;
    for (const auto& c : e.candidates) (c.username == "alice" ? fromAlice : fromBob) = c;
    check(fromAlice.filename == aliceFile && fromBob.filename == bobFile, "both sources present");

    // A direct download from alice.
    const uint32_t first = engine.download(fromAlice, false, "Artist - Song");
    check(recorder.waitFor([first](const Event& x) { return x.kind == Event::Kind::Transfer && x.transferId == first && x.stage >= TransferStage::Done; }, &e, 15),
          "direct download finished");
    check(e.stage == TransferStage::Done, "direct download done: %s", e.message.c_str());
    check(readFile(e.path) == alice.files[aliceFile], "downloaded bytes match");
    check(e.path.find("/done/Artist - Song.flac") != std::string::npos, "named as asked: %s", e.path.c_str());

    // An indirect download from bob that resumes a partial file.
    {
        std::lock_guard<std::mutex> hookGuard(server.hookLock);
        server.onConnectToPeer = [&bob](const std::string& user, uint32_t tok) {
            if (user != "bob") return;
            std::thread([&bob, tok] {
                const int fd = connectLocal(bob.engineListenPort);
                writeAll(fd, initFrame(kPierceFirewall, Writer().u32(tok).bytes()));
                bob.serveP(fd);
            }).detach();
        };
    }
    {
        // The first 100000 bytes are already here from an earlier attempt.
        const std::string partial = base + "/partial/" + md5Hex("artist - song.flac|" + std::to_string(bob.files[bobFile].size())) + ".part";
        std::ofstream out(partial, std::ios::binary);
        out.write(reinterpret_cast<const char*>(bob.files[bobFile].data()), 100000);
    }
    const uint32_t second = engine.download(fromBob, false, "");
    check(recorder.waitFor([second](const Event& x) { return x.kind == Event::Kind::Transfer && x.transferId == second && x.stage >= TransferStage::Done; }, &e, 15),
          "indirect download finished");
    check(e.stage == TransferStage::Done, "indirect download done: %s", e.message.c_str());
    check(bob.lastOffset == 100000, "resumed at the partial's end: %llu", static_cast<unsigned long long>(bob.lastOffset.load()));
    check(readFile(e.path) == bob.files[bobFile], "resumed file matches");

    // A file that is not what it claims is refused.
    Candidate bad = fromAlice;
    bad.filename = "Music\\Artist\\bad.flac";
    bad.leafName = "bad.flac";
    bad.size = 5000;
    const uint32_t third = engine.download(bad, false, "");
    check(recorder.waitFor([third](const Event& x) { return x.kind == Event::Kind::Transfer && x.transferId == third && x.stage >= TransferStage::Done; }, &e, 15),
          "bad download ended");
    check(e.stage == TransferStage::Failed && e.message.find("check") != std::string::npos, "bad file refused: %s", e.message.c_str());

    // A file the peer doesn't share is denied.
    Candidate missing = fromAlice;
    missing.filename = "Music\\nope.flac";
    missing.leafName = "nope.flac";
    const uint32_t fourth = engine.download(missing, false, "");
    check(recorder.waitFor([fourth](const Event& x) { return x.kind == Event::Kind::Transfer && x.transferId == fourth && x.stage >= TransferStage::Done; }, &e, 15),
          "denied download ended");
    check(e.stage == TransferStage::Failed && e.message.find("denied") != std::string::npos, "denial reported: %s", e.message.c_str());

    engine.stop();
    server.shutdown();
    alice.shutdown();
    bob.shutdown();
}

}  // namespace

int main() {
    protocolBasics();
    ranking();
    engineEndToEnd();
    if (failures == 0) std::printf("slsk_test: all checks passed\n");
    std::fflush(stdout);
    // Detached fake-peer threads may still be blocked in accept(); leave without unwinding them.
    std::_Exit(failures == 0 ? 0 : 1);
}
