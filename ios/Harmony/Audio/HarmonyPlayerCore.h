// HarmonyPlayerCore.h: the platform-neutral heart of the iOS player.
//
// A decoder thread fills a lock-free ring with stereo float; the real-time
// render callback takes from it, runs MobileEqEngine and hands the result to
// the output. Plain C++17 (no Apple headers), so the whole state machine
// (gapless hand-over, seeking, the end of the queue, the passive vector) is
// tested on the host. HarmonyDspBridge.mm plugs in ExtAudioFileRef sources and
// AVAudioEngine.
//
// Threads:
//   render     render() only. Never blocks: it try-locks renderLock_ and
//              plays silence in the rare callback a control call holds it.
//   decoder    decodeStep()/serveVectorRequest(), holding decoderLock_.
//   control    load/seek/setNext/configure (the main thread), taking
//              decoderLock_ and, when the render side must change too,
//              renderLock_ after it. setNext() never touches renderLock_, so
//              queueing the next song can't cause a dropout.

#ifndef HARMONY_PLAYER_CORE_H
#define HARMONY_PLAYER_CORE_H

#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "MobileEqEngine.h"
#include "SmartShuffleEngine.h"

namespace harmony::ios {

/** A decoded song: interleaved stereo float at the rate it was opened for. */
class PcmSource {
public:
    virtual ~PcmSource() = default;
    /** Up to [frames] frames into [out]; 0 at the end, -1 on a decode error. */
    virtual int read(float* out, int frames) = 0;
    virtual bool seekSeconds(double seconds) = 0;
    virtual double durationSeconds() const = 0;
};

/** Opens song [token] as stereo float at [sampleRate]; null with [error] on failure. */
using SourceFactory = std::function<std::unique_ptr<PcmSource>(int64_t token, double sampleRate, std::string* error)>;

struct CoreEvent {
    enum class Kind { TrackFailed, VectorMeasured };
    Kind kind;
    int64_t token;
    std::string message;
    std::vector<float> vector;  // 24 values for VectorMeasured
};
/** Called on the decoder or control thread; the bridge forwards to the main thread. */
using EventSink = std::function<void(CoreEvent)>;

/** Lock-free single-producer / single-consumer ring of interleaved stereo frames. */
class FrameRing {
public:
    static constexpr uint64_t kFrames = 1ull << 19;  // ~10.9 s at 48 kHz, 2.7 s at 192 kHz
    FrameRing() : data_(kFrames * 2) {}
    uint64_t available() const {
        return write_.load(std::memory_order_acquire) - read_.load(std::memory_order_relaxed);
    }
    uint64_t space() const {
        return kFrames - (write_.load(std::memory_order_relaxed) - read_.load(std::memory_order_acquire));
    }
    /** Producer. Stores min(frames, space()) frames; returns how many. */
    uint64_t push(const float* frames, uint64_t count);
    /** Consumer. */
    uint64_t pop(float* out, uint64_t count);
    /** Only while both sides are excluded (both locks held). */
    void reset() {
        write_.store(0, std::memory_order_relaxed);
        read_.store(0, std::memory_order_relaxed);
    }

private:
    alignas(64) std::atomic<uint64_t> write_{0};
    alignas(64) std::atomic<uint64_t> read_{0};
    std::vector<float> data_;
};

class PlayerCore {
public:
    static constexpr int kChunkFrames = 4096;
    static constexpr int kRenderSlice = 4096;

    enum class NextResult { Queued, NeedsLoad, Failed };

    PlayerCore(SourceFactory factory, EventSink sink);
    ~PlayerCore();
    PlayerCore(const PlayerCore&) = delete;
    PlayerCore& operator=(const PlayerCore&) = delete;

    // ---- Control thread ------------------------------------------------------

    /** (Re)prepares for the output's [sampleRate]. Stops what plays; load() again after. */
    bool configure(double sampleRate);
    /** Plays [token] from [startSeconds], replacing everything. */
    bool load(int64_t token, double startSeconds, std::string* error);
    /** Continue into [token] when the current song ends (-1: stop after it). */
    NextResult setNext(int64_t token, std::string* error);
    /** Seeks the song being heard. */
    void seek(double seconds);
    void setPlaying(bool playing) { playing_.store(playing, std::memory_order_release); }
    bool playing() const { return playing_.load(std::memory_order_acquire); }
    /** Asks the decoder thread for [token]'s passive vector (once it was heard past 40%). */
    void requestVector(int64_t token) { vectorRequest_.store(token, std::memory_order_release); }
    /** The song queued after the one heard, if any (for a rebuild). */
    int64_t queuedNextToken();

    void startDecoderThread();
    void stopDecoderThread();

    // ---- Decoder thread (public for deterministic tests) -------------------------------

    /** Decodes one chunk, or opens the next song at the end of this one. Returns whether it did anything. */
    bool decodeStep();
    void serveVectorRequest();

    // ---- Render thread -----------------------------------------------------------------

    /** Fills [left] and [right] with [frames] frames. Returns frames of real audio (the rest is silence). */
    int render(float* left, float* right, int frames);

    // ---- Any thread ------------------------------------------------------------------

    int64_t currentToken() const { return token_.load(std::memory_order_acquire); }
    double positionSeconds() const { return position_.load(std::memory_order_relaxed); }
    double durationSeconds() const { return duration_.load(std::memory_order_relaxed); }
    /** Bumps each time a song starts being heard. */
    uint32_t trackSequence() const { return trackSeq_.load(std::memory_order_acquire); }
    /** Bumps each time the queue runs out. */
    uint32_t finishSequence() const { return finishSeq_.load(std::memory_order_acquire); }
    /** Frames of the current song actually heard (seeking ahead doesn't count). */
    int64_t listenedFrames() const { return listened_.load(std::memory_order_relaxed); }
    uint64_t underrunFrames() const { return underrun_.load(std::memory_order_relaxed); }
    double sampleRate() const { return rate_.load(std::memory_order_relaxed); }
    harmony::eq::MobileEqEngine& eq() { return *eq_; }

private:
    struct Marker {
        uint64_t frame = 0;  // stream frame where the song starts being heard
        int64_t token = -1;
        double startSeconds = 0;
        double duration = 0;
        bool seek = false;   // the same song from another place, not a new one
    };
    /** SPSC queue of markers: produced under decoderLock_, consumed by render. */
    class MarkerQueue {
    public:
        static constexpr uint32_t kCapacity = 16;
        bool push(const Marker& m);
        bool peek(Marker* out) const;
        void pop() { head_.store(head_.load(std::memory_order_relaxed) + 1, std::memory_order_release); }
        void reset() {
            head_.store(0, std::memory_order_relaxed);
            tail_.store(0, std::memory_order_relaxed);
        }

    private:
        Marker items_[kCapacity];
        std::atomic<uint32_t> head_{0};
        std::atomic<uint32_t> tail_{0};
    };

    enum EndState : int { kDecoding = 0, kEnded = 1, kClaimed = 2 };

    /** Both locks held. */
    void resetStreamLocked();
    /** decoderLock_ held. */
    bool openSourceLocked(int64_t token, double startSeconds, std::string* error);
    void startExtractorLocked(int64_t token);
    void finishExtractorLocked();
    void feedExtractorLocked(const float* stereo, int frames);
    void produce(int frames);
    void applyMarkers(uint64_t limit);

    SourceFactory factory_;
    EventSink sink_;
    std::unique_ptr<harmony::eq::MobileEqEngine> eq_;
    FrameRing ring_;
    MarkerQueue markers_;
    std::mutex decoderLock_;
    std::mutex renderLock_;
    std::thread decoderThread_;
    std::atomic<bool> quit_{false};

    // Shared.
    std::atomic<double> rate_{48000.0};
    std::atomic<bool> playing_{false};
    std::atomic<int> endState_{kDecoding};
    std::atomic<int64_t> token_{-1};
    std::atomic<double> position_{0.0};
    std::atomic<double> duration_{0.0};
    std::atomic<uint32_t> trackSeq_{0};
    std::atomic<uint32_t> finishSeq_{0};
    std::atomic<int64_t> listened_{0};
    std::atomic<uint64_t> underrun_{0};
    std::atomic<int64_t> vectorRequest_{-1};

    // Decoder side (decoderLock_).
    std::unique_ptr<PcmSource> source_;
    int64_t sourceToken_ = -1;
    bool hasNext_ = false;
    int64_t nextToken_ = -1;
    uint64_t written_ = 0;
    std::vector<float> chunk_ = std::vector<float>(static_cast<size_t>(kChunkFrames) * 2);
    std::vector<float> mono_ = std::vector<float>(kChunkFrames);
    std::unique_ptr<harmony::shuffle::AcousticExtractor> extractor_;
    int64_t extractorToken_ = -1;
    int64_t lastVectorToken_ = -1;
    std::vector<float> lastVector_;
    int64_t servedVectorToken_ = -1;

    // Render side (renderLock_).
    std::vector<float> in_ = std::vector<float>(static_cast<size_t>(kChunkFrames) * 2);
    std::vector<float> stage_;
    int staged_ = 0;
    uint64_t output_ = 0;           // stream frames heard since the last load/seek
    uint64_t trackStart_ = 0;       // the stream frame the current song started at
    double startSeconds_ = 0;
    int64_t listenedBase_ = 0;
    double rateLocal_ = 48000.0;
    bool finished_ = false;
};

}  // namespace harmony::ios

#endif  // HARMONY_PLAYER_CORE_H
