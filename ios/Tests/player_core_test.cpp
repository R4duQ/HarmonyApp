// Host tests of HarmonyPlayerCore: the iOS player's state machine with
// synthetic songs instead of ExtAudioFileRef and a loop instead of AVAudioEngine.

#include "HarmonyPlayerCore.h"

#include <chrono>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <map>
#include <thread>

using harmony::ios::CoreEvent;
using harmony::ios::PcmSource;
using harmony::ios::PlayerCore;

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

constexpr double kRate = 48000.0;
constexpr int kBlock = 512;

/** A song of [seconds]: a few tones whose loudness moves, like music. */
class ToneSource : public PcmSource {
public:
    ToneSource(double seconds, double rate, double pitch)
        : frames_(static_cast<int64_t>(seconds * rate)), rate_(rate), pitch_(pitch) {}
    int read(float* out, int frames) override {
        const int n = static_cast<int>(std::min<int64_t>(frames, frames_ - pos_));
        for (int i = 0; i < n; ++i) {
            const double t = static_cast<double>(pos_ + i) / rate_;
            const double env = 0.5 + 0.4 * std::sin(2 * M_PI * 2.0 * t);
            const float v = static_cast<float>(env * (0.4 * std::sin(2 * M_PI * pitch_ * t) +
                                                      0.2 * std::sin(2 * M_PI * pitch_ * 7.3 * t)));
            out[i * 2] = v;
            out[i * 2 + 1] = v;
        }
        pos_ += n;
        return n;
    }
    bool seekSeconds(double seconds) override {
        pos_ = std::min<int64_t>(frames_, static_cast<int64_t>(seconds * rate_));
        return true;
    }
    double durationSeconds() const override { return static_cast<double>(frames_) / rate_; }

private:
    int64_t frames_;
    int64_t pos_ = 0;
    double rate_;
    double pitch_;
};

struct Harness {
    std::map<int64_t, double> lengths;  // token -> seconds; missing = unopenable
    std::vector<CoreEvent> events;
    std::unique_ptr<PlayerCore> core;

    Harness() {
        core = std::make_unique<PlayerCore>(
            [this](int64_t token, double rate, std::string* error) -> std::unique_ptr<PcmSource> {
                auto it = lengths.find(token);
                if (it == lengths.end()) {
                    if (error) *error = "missing";
                    return nullptr;
                }
                return std::make_unique<ToneSource>(it->second, rate, 110.0 * (1 + token % 5));
            },
            [this](CoreEvent e) { events.push_back(std::move(e)); });
        core->configure(kRate);
    }

    /** Decodes until the decoder has nothing to do (ring full or the queue decoded). */
    void decode() {
        while (core->decodeStep()) {
        }
    }

    /** Renders [blocks] blocks, decoding between them as the thread would. Returns frames of real audio. */
    int64_t render(int blocks) {
        std::vector<float> l(kBlock), r(kBlock);
        int64_t heard = 0;
        for (int b = 0; b < blocks; ++b) {
            decode();
            heard += core->render(l.data(), r.data(), kBlock);
            for (int i = 0; i < kBlock; ++i) check(std::isfinite(l[i]) && std::isfinite(r[i]), "non-finite output");
        }
        return heard;
    }

    int64_t renderUntilFinished(int maxBlocks = 100000) {
        const uint32_t before = core->finishSequence();
        int64_t heard = 0;
        for (int b = 0; b < maxBlocks && core->finishSequence() == before; ++b) heard += render(1);
        return heard;
    }

    size_t count(CoreEvent::Kind kind) const {
        size_t n = 0;
        for (const auto& e : events) n += e.kind == kind;
        return n;
    }
};

void gaplessIntoTheNextSong() {
    Harness h;
    h.lengths = {{1, 2.0}, {2, 1.0}};
    std::string error;
    check(h.core->load(1, 0, &error), "load: %s", error.c_str());
    check(h.core->setNext(2, &error) == PlayerCore::NextResult::Queued, "queue next");
    h.core->setPlaying(true);
    int64_t heard = h.render(1);
    check(h.core->currentToken() == 1, "first song heard, token %lld", static_cast<long long>(h.core->currentToken()));
    heard += h.renderUntilFinished();
    check(heard == static_cast<int64_t>(3.0 * kRate), "every frame of both songs heard: %lld", static_cast<long long>(heard));
    check(h.core->currentToken() == 2, "ended on the second song");
    check(h.core->trackSequence() == 2, "two songs started: %u", h.core->trackSequence());
    check(h.core->finishSequence() == 1, "the queue finished once");
    check(h.core->underrunFrames() == 0, "no underrun: %llu", static_cast<unsigned long long>(h.core->underrunFrames()));
    check(std::fabs(h.core->positionSeconds() - 1.0) < 0.02, "position at the end of song 2: %.3f", h.core->positionSeconds());
}

void songChangeLandsOnTheBoundary() {
    Harness h;
    h.lengths = {{1, 1.0}, {2, 1.0}};
    std::string error;
    h.core->load(1, 0, &error);
    h.core->setNext(2, &error);
    h.core->setPlaying(true);
    int64_t frames = 0;
    int64_t switchedAt = -1;
    for (int b = 0; b < 200 && switchedAt < 0; ++b) {
        frames += h.render(1);
        if (h.core->currentToken() == 2) switchedAt = frames;
    }
    // Reported within one block of the real boundary (frame 48000).
    check(switchedAt >= 48000 && switchedAt <= 48000 + kBlock, "switched at %lld", static_cast<long long>(switchedAt));
}

void seekKeepsTheSongAndMovesThePosition() {
    Harness h;
    h.lengths = {{1, 3.0}};
    std::string error;
    h.core->load(1, 0, &error);
    h.core->setPlaying(true);
    int64_t heard = h.render(static_cast<int>(0.5 * kRate / kBlock));
    h.core->seek(2.0);
    h.render(1);
    check(std::fabs(h.core->positionSeconds() - 2.0) < 0.02, "position after the seek: %.3f", h.core->positionSeconds());
    check(h.core->trackSequence() == 1, "a seek is not a new song: %u", h.core->trackSequence());
    heard += kBlock + h.renderUntilFinished();
    const double expected = (0.5 * kRate / kBlock) * kBlock + 1.0 * kRate;
    check(std::fabs(static_cast<double>(heard) - expected) <= kBlock, "heard %lld frames, expected about %.0f",
          static_cast<long long>(heard), expected);
    check(h.core->listenedFrames() > static_cast<int64_t>(1.4 * kRate), "listening is kept across the seek: %lld",
          static_cast<long long>(h.core->listenedFrames()));
}

void seekAfterTheDecoderMovedOn() {
    Harness h;
    h.lengths = {{1, 1.0}, {2, 2.0}};
    std::string error;
    h.core->load(1, 0, &error);
    h.core->setNext(2, &error);
    h.core->setPlaying(true);
    h.render(10);  // song 1 is fully decoded, the decoder is into song 2
    h.core->seek(0.5);
    const int64_t heard = h.renderUntilFinished();
    check(std::fabs(static_cast<double>(heard) - 2.5 * kRate) <= kBlock, "song 1 from 0.5 s, then song 2: %lld",
          static_cast<long long>(heard));
    check(h.core->currentToken() == 2, "still moved on to song 2");
}

void nextQueuedLateStillFollowsWithoutAGap() {
    Harness h;
    h.lengths = {{1, 1.0}, {2, 1.0}};
    std::string error;
    h.core->load(1, 0, &error);
    h.core->setPlaying(true);
    int64_t heard = h.render(40);  // song 1 entirely decoded, about 0.43 s heard
    check(h.core->setNext(2, &error) == PlayerCore::NextResult::Queued, "late next is queued");
    heard += h.renderUntilFinished();
    check(heard == static_cast<int64_t>(2.0 * kRate), "both songs, no gap: %lld", static_cast<long long>(heard));
    check(h.core->underrunFrames() == 0, "no underrun");
}

void nextAfterTheEndNeedsALoad() {
    Harness h;
    h.lengths = {{1, 0.5}, {2, 1.0}};
    std::string error;
    h.core->load(1, 0, &error);
    h.core->setPlaying(true);
    h.renderUntilFinished();
    check(h.core->setNext(2, &error) == PlayerCore::NextResult::NeedsLoad, "after the end, next means a fresh load");
    check(h.core->load(2, 0, &error), "load after the end");
    const int64_t heard = h.renderUntilFinished();
    check(heard == static_cast<int64_t>(kRate), "the new song plays whole: %lld", static_cast<long long>(heard));
}

void unopenableNextIsReportedAndTheQueueEnds() {
    Harness h;
    h.lengths = {{1, 0.5}};
    std::string error;
    h.core->load(1, 0, &error);
    h.core->setNext(99, &error);
    h.core->setPlaying(true);
    const int64_t heard = h.renderUntilFinished();
    check(heard == static_cast<int64_t>(0.5 * kRate), "song 1 plays: %lld", static_cast<long long>(heard));
    check(h.count(CoreEvent::Kind::TrackFailed) == 1, "one failure reported");
    check(!h.events.empty() && h.events[0].token == 99, "for the right song");
    check(!h.core->load(98, 0, &error), "loading a missing song fails");
}

void pausedRendersSilence() {
    Harness h;
    h.lengths = {{1, 1.0}};
    std::string error;
    h.core->load(1, 0, &error);
    check(h.render(5) == 0, "paused: silence");
    h.core->setPlaying(true);
    check(h.render(5) > 0, "playing: sound");
}

void passiveVectorAfterFortyPercent() {
    Harness h;
    h.lengths = {{7, 6.0}};
    std::string error;
    h.core->load(7, 0, &error);
    h.core->setPlaying(true);
    h.render(static_cast<int>(0.45 * 6.0 * kRate / kBlock));
    check(h.core->listenedFrames() >= static_cast<int64_t>(0.4 * 6.0 * kRate), "past 40%%");
    h.core->requestVector(7);
    h.core->serveVectorRequest();
    check(h.count(CoreEvent::Kind::VectorMeasured) == 1, "one vector measured");
    for (const auto& e : h.events) {
        if (e.kind != CoreEvent::Kind::VectorMeasured) continue;
        check(e.token == 7 && e.vector.size() == 24, "24 values for song 7");
        for (float v : e.vector) check(std::isfinite(v) && v >= 0.f && v <= 1.f, "vector value %f", v);
    }
    h.core->requestVector(7);
    h.core->serveVectorRequest();
    check(h.count(CoreEvent::Kind::VectorMeasured) == 1, "measured once only");
}

void reconfigureForANewRate() {
    Harness h;
    h.lengths = {{1, 1.0}};
    std::string error;
    h.core->load(1, 0, &error);
    h.core->setPlaying(true);
    h.render(10);
    check(h.core->configure(96000.0), "configure 96 kHz");
    check(h.core->load(1, 0.25, &error), "reload at the new rate");
    std::vector<float> l(kBlock), r(kBlock);
    int64_t heard = 0;
    for (int b = 0; b < 1000 && h.core->finishSequence() == 0; ++b) {
        h.decode();
        heard += h.core->render(l.data(), r.data(), kBlock);
    }
    check(std::fabs(static_cast<double>(heard) - 0.75 * 96000.0) <= 1, "0.75 s at 96 kHz: %lld", static_cast<long long>(heard));
}

void withARealDecoderThread() {
    Harness h;
    h.lengths = {{1, 1.5}, {2, 1.5}};
    std::string error;
    h.core->load(1, 0, &error);
    h.core->setNext(2, &error);
    h.core->startDecoderThread();
    h.core->setPlaying(true);
    std::vector<float> l(kBlock), r(kBlock);
    int64_t heard = 0;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(20);
    while (h.core->finishSequence() == 0 && std::chrono::steady_clock::now() < deadline) {
        heard += h.core->render(l.data(), r.data(), kBlock);
        std::this_thread::sleep_for(std::chrono::microseconds(200));
    }
    h.core->stopDecoderThread();
    check(heard == static_cast<int64_t>(3.0 * kRate), "threaded: all of both songs: %lld", static_cast<long long>(heard));
}

}  // namespace

int main() {
    gaplessIntoTheNextSong();
    songChangeLandsOnTheBoundary();
    seekKeepsTheSongAndMovesThePosition();
    seekAfterTheDecoderMovedOn();
    nextQueuedLateStillFollowsWithoutAGap();
    nextAfterTheEndNeedsALoad();
    unopenableNextIsReportedAndTheQueueEnds();
    pausedRendersSilence();
    passiveVectorAfterFortyPercent();
    reconfigureForANewRate();
    withARealDecoderThread();
    if (failures == 0) std::printf("player_core_test: all checks passed\n");
    return failures == 0 ? 0 : 1;
}
