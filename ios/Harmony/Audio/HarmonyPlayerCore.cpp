#include "HarmonyPlayerCore.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>

#if defined(__APPLE__)
#include <pthread.h>
#endif

namespace harmony::ios {

using harmony::eq::SampleFormat;

namespace {
/** Stage: one render slice, plus a decode chunk on top, plus the limiter's lookahead. */
constexpr size_t kStageFrames = PlayerCore::kRenderSlice + PlayerCore::kChunkFrames + 1024;
}  // namespace

// ---------------------------------------------------------------------------
// FrameRing
// ---------------------------------------------------------------------------

uint64_t FrameRing::push(const float* frames, uint64_t count) {
    const uint64_t w = write_.load(std::memory_order_relaxed);
    const uint64_t r = read_.load(std::memory_order_acquire);
    const uint64_t n = std::min(count, kFrames - (w - r));
    const uint64_t at = w & (kFrames - 1);
    const uint64_t first = std::min(n, kFrames - at);
    std::memcpy(data_.data() + at * 2, frames, first * 2 * sizeof(float));
    if (n > first) std::memcpy(data_.data(), frames + first * 2, (n - first) * 2 * sizeof(float));
    write_.store(w + n, std::memory_order_release);
    return n;
}

uint64_t FrameRing::pop(float* out, uint64_t count) {
    const uint64_t r = read_.load(std::memory_order_relaxed);
    const uint64_t w = write_.load(std::memory_order_acquire);
    const uint64_t n = std::min(count, w - r);
    const uint64_t at = r & (kFrames - 1);
    const uint64_t first = std::min(n, kFrames - at);
    std::memcpy(out, data_.data() + at * 2, first * 2 * sizeof(float));
    if (n > first) std::memcpy(out + first * 2, data_.data(), (n - first) * 2 * sizeof(float));
    read_.store(r + n, std::memory_order_release);
    return n;
}

// ---------------------------------------------------------------------------
// MarkerQueue
// ---------------------------------------------------------------------------

bool PlayerCore::MarkerQueue::push(const Marker& m) {
    const uint32_t t = tail_.load(std::memory_order_relaxed);
    if (t - head_.load(std::memory_order_acquire) >= kCapacity) return false;
    items_[t % kCapacity] = m;
    tail_.store(t + 1, std::memory_order_release);
    return true;
}

bool PlayerCore::MarkerQueue::peek(Marker* out) const {
    const uint32_t h = head_.load(std::memory_order_relaxed);
    if (h == tail_.load(std::memory_order_acquire)) return false;
    *out = items_[h % kCapacity];
    return true;
}

// ---------------------------------------------------------------------------
// PlayerCore
// ---------------------------------------------------------------------------

PlayerCore::PlayerCore(SourceFactory factory, EventSink sink)
    : factory_(std::move(factory)),
      sink_(std::move(sink)),
      eq_(std::make_unique<harmony::eq::MobileEqEngine>()),
      stage_(kStageFrames * 2) {
    eq_->configure(48000, 2);
}

PlayerCore::~PlayerCore() { stopDecoderThread(); }

bool PlayerCore::configure(double sampleRate) {
    std::lock_guard<std::mutex> decoder(decoderLock_);
    std::lock_guard<std::mutex> render(renderLock_);
    if (!eq_->configure(static_cast<int>(std::lround(sampleRate)), 2)) return false;
    rate_.store(sampleRate, std::memory_order_relaxed);
    rateLocal_ = sampleRate;
    source_.reset();
    sourceToken_ = -1;
    hasNext_ = false;
    resetStreamLocked();
    endState_.store(kEnded, std::memory_order_release);
    finished_ = true;  // nothing to play until load()
    return true;
}

void PlayerCore::resetStreamLocked() {
    ring_.reset();
    markers_.reset();
    written_ = 0;
    output_ = 0;
    trackStart_ = 0;
    staged_ = 0;
    finished_ = false;
    eq_->reset();
    endState_.store(kDecoding, std::memory_order_release);
}

bool PlayerCore::openSourceLocked(int64_t token, double startSeconds, std::string* error) {
    std::unique_ptr<PcmSource> source = factory_(token, rate_.load(std::memory_order_relaxed), error);
    if (!source) return false;
    if (startSeconds > 0 && !source->seekSeconds(startSeconds)) {
        if (error) *error = "Could not seek";
        return false;
    }
    source_ = std::move(source);
    sourceToken_ = token;
    return true;
}

bool PlayerCore::load(int64_t token, double startSeconds, std::string* error) {
    std::lock_guard<std::mutex> decoder(decoderLock_);
    std::unique_ptr<PcmSource> previous = std::move(source_);
    const int64_t previousToken = sourceToken_;
    if (!openSourceLocked(token, std::max(0.0, startSeconds), error)) {
        source_ = std::move(previous);
        sourceToken_ = previousToken;
        return false;
    }
    std::lock_guard<std::mutex> render(renderLock_);
    hasNext_ = false;
    resetStreamLocked();
    markers_.push({0, token, std::max(0.0, startSeconds), source_->durationSeconds()});
    if (extractorToken_ != token) startExtractorLocked(token);
    return true;
}

PlayerCore::NextResult PlayerCore::setNext(int64_t token, std::string* error) {
    std::lock_guard<std::mutex> decoder(decoderLock_);
    if (token < 0) {
        hasNext_ = false;
        return NextResult::Queued;
    }
    if (sourceToken_ == token && token_.load(std::memory_order_acquire) != token) {
        return NextResult::Queued;  // already decoding into it
    }
    int expected = kEnded;
    if (endState_.load(std::memory_order_acquire) == kDecoding) {
        hasNext_ = true;
        nextToken_ = token;
        return NextResult::Queued;
    }
    if (endState_.compare_exchange_strong(expected, kDecoding, std::memory_order_acq_rel)) {
        // The last song had been fully decoded, but render hasn't run out yet:
        // open the next one now and it follows without a gap.
        if (!openSourceLocked(token, 0, error)) {
            endState_.store(kEnded, std::memory_order_release);
            return NextResult::Failed;
        }
        markers_.push({written_, token, 0, source_->durationSeconds()});
        startExtractorLocked(token);
        hasNext_ = false;
        return NextResult::Queued;
    }
    return NextResult::NeedsLoad;  // render already ran out: start it fresh
}

void PlayerCore::seek(double seconds) {
    std::lock_guard<std::mutex> decoder(decoderLock_);
    const int64_t heard = token_.load(std::memory_order_acquire);
    if (heard < 0) return;
    seconds = std::max(0.0, seconds);
    if (source_ && sourceToken_ == heard) {
        if (!source_->seekSeconds(seconds)) return;
    } else {
        // The decoder had moved on into the next song: reopen the one heard
        // and keep the other queued.
        const int64_t ahead = sourceToken_;
        std::string error;
        if (!openSourceLocked(heard, seconds, &error)) return;
        if (ahead >= 0) {
            hasNext_ = true;
            nextToken_ = ahead;
        }
        startExtractorLocked(heard);
    }
    std::lock_guard<std::mutex> render(renderLock_);
    resetStreamLocked();
    markers_.push({0, heard, seconds, source_->durationSeconds(), true});
}

int64_t PlayerCore::queuedNextToken() {
    std::lock_guard<std::mutex> decoder(decoderLock_);
    if (hasNext_) return nextToken_;
    if (sourceToken_ >= 0 && sourceToken_ != token_.load(std::memory_order_acquire)) return sourceToken_;
    return -1;
}

// ---- Decoder thread ---------------------------------------------------------------

void PlayerCore::startExtractorLocked(int64_t token) {
    extractor_ = std::make_unique<harmony::shuffle::AcousticExtractor>(
        static_cast<int>(std::lround(rate_.load(std::memory_order_relaxed))));
    extractorToken_ = token;
}

void PlayerCore::feedExtractorLocked(const float* stereo, int frames) {
    if (!extractor_) return;
    for (int i = 0; i < frames; ++i) mono_[static_cast<size_t>(i)] = 0.5f * (stereo[i * 2] + stereo[i * 2 + 1]);
    extractor_->process(mono_.data(), frames);
}

void PlayerCore::finishExtractorLocked() {
    if (!extractor_) return;
    float vector[harmony::shuffle::kDims];
    if (extractor_->finish(vector)) {
        lastVectorToken_ = extractorToken_;
        lastVector_.assign(vector, vector + harmony::shuffle::kDims);
    }
    extractor_.reset();
    extractorToken_ = -1;
}

bool PlayerCore::decodeStep() {
    std::lock_guard<std::mutex> decoder(decoderLock_);
    if (!source_ || endState_.load(std::memory_order_acquire) != kDecoding) return false;
    if (ring_.space() < static_cast<uint64_t>(kChunkFrames)) return false;
    const int got = source_->read(chunk_.data(), kChunkFrames);
    if (got > 0) {
        ring_.push(chunk_.data(), static_cast<uint64_t>(got));
        written_ += static_cast<uint64_t>(got);
        if (extractorToken_ == sourceToken_) feedExtractorLocked(chunk_.data(), got);
        return true;
    }
    if (got < 0 && sink_) sink_({CoreEvent::Kind::TrackFailed, sourceToken_, "The song stopped decoding", {}});
    // The end of this song.
    if (extractorToken_ == sourceToken_) finishExtractorLocked();
    while (hasNext_) {
        hasNext_ = false;
        std::string error;
        const int64_t next = nextToken_;
        if (openSourceLocked(next, 0, &error)) {
            markers_.push({written_, next, 0, source_->durationSeconds()});
            startExtractorLocked(next);
            return true;
        }
        if (sink_) sink_({CoreEvent::Kind::TrackFailed, next, error, {}});
    }
    endState_.store(kEnded, std::memory_order_release);
    return true;
}

void PlayerCore::serveVectorRequest() {
    const int64_t wanted = vectorRequest_.exchange(-1, std::memory_order_acq_rel);
    if (wanted < 0 || wanted == servedVectorToken_) return;
    std::lock_guard<std::mutex> decoder(decoderLock_);
    if (wanted == extractorToken_ && extractor_) {
        float vector[harmony::shuffle::kDims];
        if (!extractor_->finish(vector)) return;  // not enough heard yet: ask again later
        lastVectorToken_ = wanted;
        lastVector_.assign(vector, vector + harmony::shuffle::kDims);
        extractor_.reset();
        extractorToken_ = -1;
    }
    if (wanted == lastVectorToken_ && !lastVector_.empty()) {
        servedVectorToken_ = wanted;
        if (sink_) sink_({CoreEvent::Kind::VectorMeasured, wanted, {}, lastVector_});
    }
}

void PlayerCore::startDecoderThread() {
    if (decoderThread_.joinable()) return;
    quit_.store(false);
    decoderThread_ = std::thread([this] {
#if defined(__APPLE__)
        pthread_setname_np("Harmony decoder");
        pthread_set_qos_class_self_np(QOS_CLASS_USER_INITIATED, 0);
#endif
        while (!quit_.load(std::memory_order_acquire)) {
            serveVectorRequest();
            if (!decodeStep()) std::this_thread::sleep_for(std::chrono::milliseconds(4));
        }
    });
}

void PlayerCore::stopDecoderThread() {
    quit_.store(true, std::memory_order_release);
    if (decoderThread_.joinable()) decoderThread_.join();
}

// ---- Render thread ----------------------------------------------------------------

void PlayerCore::produce(int frames) {
    while (staged_ < frames) {
        const uint64_t want = static_cast<uint64_t>(std::min(kChunkFrames, frames - staged_));
        const uint64_t got = ring_.pop(in_.data(), want);
        if (got == 0) {
            int expected = kEnded;
            if (ring_.available() == 0) endState_.compare_exchange_strong(expected, kClaimed, std::memory_order_acq_rel);
            if (endState_.load(std::memory_order_acquire) == kClaimed && !finished_) {
                const int drained = eq_->drain(stage_.data() + static_cast<size_t>(staged_) * 2, frames - staged_);
                staged_ += drained;
                if (drained == 0) {
                    finished_ = true;
                    finishSeq_.fetch_add(1, std::memory_order_acq_rel);
                }
            }
            return;
        }
        staged_ += eq_->process(in_.data(), SampleFormat::kFloat, static_cast<int>(got),
                                stage_.data() + static_cast<size_t>(staged_) * 2);
    }
}

void PlayerCore::applyMarkers(uint64_t limit) {
    Marker m;
    while (markers_.peek(&m) && m.frame <= limit) {
        markers_.pop();
        const bool sameSong = m.seek && m.token == token_.load(std::memory_order_relaxed);
        // A seek within the song keeps what was already heard; a new song starts from nothing.
        listenedBase_ = sameSong ? listened_.load(std::memory_order_relaxed) : 0;
        trackStart_ = m.frame;
        startSeconds_ = m.startSeconds;
        duration_.store(m.duration, std::memory_order_relaxed);
        if (!sameSong) {
            token_.store(m.token, std::memory_order_release);
            trackSeq_.fetch_add(1, std::memory_order_acq_rel);
        }
    }
}

int PlayerCore::render(float* left, float* right, int frames) {
    std::unique_lock<std::mutex> lock(renderLock_, std::try_to_lock);
    if (!lock.owns_lock() || !playing_.load(std::memory_order_acquire)) {
        std::memset(left, 0, sizeof(float) * static_cast<size_t>(frames));
        std::memset(right, 0, sizeof(float) * static_cast<size_t>(frames));
        return 0;
    }
    int heard = 0;
    for (int done = 0; done < frames;) {
        const int n = std::min(kRenderSlice, frames - done);
        if (!finished_) produce(n);
        const int out = std::min(staged_, n);
        applyMarkers(output_ + static_cast<uint64_t>(out));
        const float* s = stage_.data();
        for (int i = 0; i < out; ++i) {
            left[done + i] = s[i * 2];
            right[done + i] = s[i * 2 + 1];
        }
        if (out < n) {
            std::memset(left + done + out, 0, sizeof(float) * static_cast<size_t>(n - out));
            std::memset(right + done + out, 0, sizeof(float) * static_cast<size_t>(n - out));
            // Short only because the decoder fell behind (not the end of the queue).
            if (!finished_ && endState_.load(std::memory_order_acquire) == kDecoding) underrun_.fetch_add(static_cast<uint64_t>(n - out), std::memory_order_relaxed);
        }
        if (out > 0 && staged_ > out) {
            std::memmove(stage_.data(), stage_.data() + static_cast<size_t>(out) * 2,
                         static_cast<size_t>(staged_ - out) * 2 * sizeof(float));
        }
        staged_ -= out;
        output_ += static_cast<uint64_t>(out);
        heard += out;
        if (output_ >= trackStart_) {
            listened_.store(listenedBase_ + static_cast<int64_t>(output_ - trackStart_), std::memory_order_relaxed);
            position_.store(startSeconds_ + static_cast<double>(output_ - trackStart_) / rateLocal_,
                            std::memory_order_relaxed);
        }
        done += n;
    }
    return heard;
}

}  // namespace harmony::ios
