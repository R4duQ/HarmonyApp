// Host tests for SmartShuffleEngine and the ShuffleDiagnosticBrain: build and
// run with CMake (see CMakeLists.txt here), or under sanitizers, e.g.
//   g++ -std=c++17 -O1 -g -fsanitize=address,undefined -DHARMONY_SHUFFLE_TESTING
//       -I../../main/cpp shuffle_test.cpp ../../main/cpp/SmartShuffleEngine.cpp -o shuffle_test

#include "SmartShuffleEngine.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <limits>
#include <random>
#include <set>
#include <string>
#include <vector>

using namespace harmony::shuffle;

namespace {

int failures = 0;

#define CHECK(condition, ...)                                                    \
    do {                                                                         \
        if (!(condition)) {                                                      \
            ++failures;                                                          \
            std::printf("FAIL %s:%d: %s: ", __FILE__, __LINE__, #condition);     \
            std::printf(__VA_ARGS__);                                            \
            std::printf("\n");                                                   \
        }                                                                        \
    } while (0)

constexpr double kPi = 3.14159265358979323846;
constexpr std::int64_t kNow = 1'800'000'000'000LL;

// ---------------------------------------------------------------------------
// Extraction
// ---------------------------------------------------------------------------

/** Runs [seconds] of [signal(t)] at [rate] through a fresh extractor. */
template <typename Signal>
bool extract(int rate, double seconds, Signal signal, float out[kDims]) {
    AcousticExtractor extractor(rate);
    std::vector<float> chunk(4096);
    const long total = static_cast<long>(seconds * rate);
    long n = 0;
    while (n < total) {
        const int count = static_cast<int>(std::min<long>(chunk.size(), total - n));
        for (int i = 0; i < count; ++i) chunk[i] = static_cast<float>(signal((n + i) / static_cast<double>(rate)));
        extractor.process(chunk.data(), count);
        n += count;
    }
    return extractor.finish(out);
}

double kick(double t) {
    // A 55 Hz thump twice a second, decaying over 150 ms.
    const double phase = std::fmod(t, 0.5);
    return 0.8 * std::exp(-phase / 0.15) * std::sin(2.0 * kPi * 55.0 * phase);
}

void extractorHearsBassAndTreble() {
    std::mt19937 rng(1);
    std::normal_distribution<double> noise(0.0, 0.2);
    double previous = 0.0;
    auto hiss = [&](double) {
        // Differentiated noise: energy rising with frequency.
        const double x = noise(rng);
        const double y = x - previous;
        previous = x;
        return 0.5 * y;
    };

    float bass[kDims];
    float bright[kDims];
    CHECK(extract(44100, 12.0, kick, bass), "bass track measured");
    CHECK(extract(44100, 12.0, hiss, bright), "bright track measured");

    const int bassPeak = static_cast<int>(std::max_element(bass, bass + kBands) - bass);
    const int brightPeak = static_cast<int>(std::max_element(bright, bright + kBands) - bright);
    CHECK(bassPeak <= 1, "kick peaks in a bass band, got band %d", bassPeak);
    CHECK(brightPeak >= 7, "hiss peaks in a treble band, got band %d", brightPeak);
    CHECK(bass[21] < 0.25f && bright[21] > 0.6f, "centroid dark %.2f vs bright %.2f", bass[21], bright[21]);
    CHECK(bass[20] > bright[20] + 0.2f, "sub-bass punch %.2f vs %.2f", bass[20], bright[20]);
    float tone[kDims];
    CHECK(extract(44100, 6.0, [](double t) { return 0.5 * std::sin(2.0 * kPi * 1000.0 * t); }, tone), "tone measured");
    CHECK(tone[22] < 0.05f && bass[22] > tone[22] + 0.15f, "a steady tone has no crest (%.2f), kicks do (%.2f)",
          tone[22], bass[22]);
    // Two kicks a second: the pulse rate should land near 2/(2+3) = 0.4.
    CHECK(bass[23] > 0.25f && bass[23] < 0.55f, "pulse rate %.2f", bass[23]);
    CHECK(bright[23] < bass[23], "steady hiss pulses less (%.2f vs %.2f)", bright[23], bass[23]);

    double norm = 0.0;
    for (int k = 0; k < kBands; ++k) norm += bass[k] * bass[k];
    CHECK(std::fabs(std::sqrt(norm) - 1.0) < 1e-4, "spectral profile is unit length (%.5f)", std::sqrt(norm));
    for (int d = 0; d < kDims; ++d) {
        CHECK(std::isfinite(bass[d]) && bass[d] >= 0.0f && bass[d] <= 1.0f, "dim %d in [0,1]: %f", d, bass[d]);
        CHECK(std::isfinite(bright[d]) && bright[d] >= 0.0f && bright[d] <= 1.0f, "dim %d in [0,1]: %f", d, bright[d]);
    }
}

void extractorHandlesEdges() {
    float out[kDims];
    CHECK(!extract(44100, 1.0, kick, out), "one second is too short to judge");
    CHECK(!extract(44100, 10.0, [](double) { return 0.0; }, out), "silence has no profile");
    CHECK(!AcousticExtractor(1000).valid(), "absurd sample rates are refused");

    // Corrupt samples count as silence and never poison the result.
    auto corrupt = [](double t) {
        const long i = static_cast<long>(t * 48000.0);
        if (i % 97 == 0) return std::numeric_limits<double>::quiet_NaN();
        if (i % 89 == 0) return std::numeric_limits<double>::infinity();
        return kick(t);
    };
    CHECK(extract(48000, 8.0, corrupt, out), "corrupt samples are survivable");
    for (int d = 0; d < kDims; ++d) CHECK(std::isfinite(out[d]), "dim %d finite", d);

    // A 22.05 kHz file cannot hold 16 kHz: that band reads as empty, not as garbage.
    CHECK(extract(22050, 8.0, kick, out), "22.05 kHz measured");
    CHECK(out[9] == 0.0f, "16 kHz band empty at 22.05 kHz: %f", out[9]);
    CHECK(extract(192000, 4.0, kick, out), "192 kHz measured");
    const int peak = static_cast<int>(std::max_element(out, out + kBands) - out);
    CHECK(peak <= 1, "192 kHz kick still peaks in the bass, band %d", peak);
}

// ---------------------------------------------------------------------------
// A small synthetic library: families of songs with a known sound.
// ---------------------------------------------------------------------------

struct Library {
    std::vector<std::int64_t> ids;
    std::vector<float> raw;
    std::vector<std::uint8_t> quality;
    std::vector<std::int32_t> artists;
    std::vector<int> family;
};

/** [perFamily] songs in each of the families, a distinct artist every [songsPerArtist] songs. */
Library makeLibrary(int families, int perFamily, int songsPerArtist, unsigned seed = 3) {
    Library lib;
    std::mt19937 rng(seed);
    std::uniform_real_distribution<float> jitter(0.9f, 1.1f);
    int artist = 0;
    for (int f = 0; f < families; ++f) {
        for (int i = 0; i < perFamily; ++i) {
            if (i % songsPerArtist == 0) ++artist;
            lib.ids.push_back(100000 + f * 1000 + i);
            lib.family.push_back(f);
            lib.artists.push_back(artist);
            lib.quality.push_back(static_cast<std::uint8_t>(VectorQuality::Real));
            // Family f leans on bands around 2f, with its own macro signature.
            for (int k = 0; k < kBands; ++k) {
                const float centre = static_cast<float>(f * 9) / std::max(1, families - 1);
                lib.raw.push_back(jitter(rng) * std::exp(-0.5f * (k - centre) * (k - centre) / 2.0f));
            }
            for (int k = 0; k < kBands; ++k) lib.raw.push_back(jitter(rng) * (0.2f + 0.1f * f));
            for (int m = 0; m < 4; ++m) lib.raw.push_back(jitter(rng) * (0.15f + 0.15f * ((f + m) % 4)));
        }
    }
    return lib;
}

void load(SmartShuffleEngine& engine, const Library& lib) {
    engine.setLibrary(lib.ids.data(), lib.raw.data(), lib.quality.data(), lib.artists.data(), nullptr,
                      static_cast<int>(lib.ids.size()));
}

std::vector<std::int64_t> generate(SmartShuffleEngine& engine, std::int64_t current, int count,
                                   const std::vector<std::int64_t>& exclude = {},
                                   const std::vector<std::int64_t>& replaced = {}, std::uint64_t seed = 1) {
    std::vector<std::int64_t> out(std::max(1, count));
    out.resize(engine.generateQueue(current, nullptr, 0, exclude.data(), static_cast<int>(exclude.size()),
                                    replaced.data(), static_cast<int>(replaced.size()), count, kNow, seed,
                                    out.data()));
    return out;
}

std::string problems(const BrainAuditReport& report) {
    std::string out;
    for (const auto& c : report.checks) {
        if (c.status == AuditStatus::Failed) out += c.detail + "; ";
    }
    return out;
}

float norm(const float* v) {
    double s = 0.0;
    for (int d = 0; d < kDims; ++d) s += static_cast<double>(v[d]) * v[d];
    return static_cast<float>(std::sqrt(s));
}

float dotWith(const float* s, const SmartShuffleEngine& engine, std::int64_t id) {
    float v[kDims];
    engine.vectorOf(id, v);
    float d = 0.0f;
    for (int i = 0; i < kDims; ++i) d += s[i] * v[i];
    return d;
}

// ---------------------------------------------------------------------------
// Parts 2 and 3
// ---------------------------------------------------------------------------

void vectorsAreUnitAndStandardized() {
    const Library lib = makeLibrary(5, 30, 5);
    SmartShuffleEngine engine;
    load(engine, lib);
    float v[kDims];
    double sameFamily = 0.0;
    double otherFamily = 0.0;
    for (size_t i = 0; i < lib.ids.size(); ++i) {
        CHECK(engine.vectorOf(lib.ids[i], v), "vector for %zu", i);
        CHECK(std::fabs(norm(v) - 1.0f) < 1e-4f, "unit length (%f)", norm(v));
    }
    for (int i = 1; i < 30; ++i) sameFamily += engine.cosine(lib.ids[0], lib.ids[i]);
    for (int i = 60; i < 89; ++i) otherFamily += engine.cosine(lib.ids[0], lib.ids[i]);
    sameFamily /= 29.0;
    otherFamily /= 29.0;
    // The point of standardizing: unrelated songs are not all "90% similar".
    CHECK(sameFamily > 0.7, "same family close: %.2f", sameFamily);
    CHECK(otherFamily < 0.3, "other family far: %.2f", otherFamily);
}

void sessionVectorFollowsTheSpec() {
    const Library lib = makeLibrary(3, 20, 4);
    SmartShuffleEngine engine;
    load(engine, lib);
    const std::int64_t a = lib.ids[0];
    const std::int64_t b = lib.ids[25];
    float va[kDims];
    float vb[kDims];
    engine.vectorOf(a, va);
    engine.vectorOf(b, vb);

    CHECK(engine.onTrackStarted(a) == SessionEvent::Seeded, "first song seeds S_t");
    CHECK(std::fabs(dotWith(engine.sessionVector(), engine, a) - 1.0f) < 1e-5f, "S_t = v_a");

    // Play past half: S = normalize(0.6 vb + 0.4 S).
    engine.onTrackStarted(b);
    CHECK(engine.onPlaybackOutcome(b, 0.8f, kNow, false) == SessionEvent::PlayEma, "EMA on play");
    float expected[kDims];
    for (int d = 0; d < kDims; ++d) expected[d] = 0.6f * vb[d] + 0.4f * va[d];
    const float n = norm(expected);
    for (int d = 0; d < kDims; ++d) {
        CHECK(std::fabs(engine.sessionVector()[d] - expected[d] / n) < 1e-5f, "EMA dim %d", d);
    }

    // Fast skip: S = normalize(S - 0.35 v), and it moves away.
    float before[kDims];
    std::copy(engine.sessionVector(), engine.sessionVector() + kDims, before);
    CHECK(engine.onPlaybackOutcome(a, 0.1f, kNow, false) == SessionEvent::FastSkip, "fast skip");
    for (int d = 0; d < kDims; ++d) expected[d] = before[d] - 0.35f * va[d];
    const float m = norm(expected);
    for (int d = 0; d < kDims; ++d) {
        CHECK(std::fabs(engine.sessionVector()[d] - expected[d] / m) < 1e-5f, "repulsion dim %d", d);
    }
    CHECK(dotWith(engine.sessionVector(), engine, a) < dotWith(before, engine, a), "moved away from the skip");

    // Between 20% and 50%: S holds.
    std::copy(engine.sessionVector(), engine.sessionVector() + kDims, before);
    CHECK(engine.onPlaybackOutcome(b, 0.35f, kNow, false) == SessionEvent::Neutral, "neutral middle");
    CHECK(std::equal(before, before + kDims, engine.sessionVector()), "S_t unchanged");

    // Manual pick: S = normalize(0.85 v + 0.15 S).
    CHECK(engine.onManualSelect(a) == SessionEvent::ManualAnchor, "manual anchor");
    for (int d = 0; d < kDims; ++d) expected[d] = 0.85f * va[d] + 0.15f * before[d];
    const float k = norm(expected);
    for (int d = 0; d < kDims; ++d) {
        CHECK(std::fabs(engine.sessionVector()[d] - expected[d] / k) < 1e-5f, "anchor dim %d", d);
    }
}

void skippingTheSeedStillRepels() {
    // S_t == v_skipped: subtracting 0.35 v leaves the direction unchanged, so
    // the brain removes the direction outright (Check 2, part one).
    const Library lib = makeLibrary(3, 20, 4);
    SmartShuffleEngine engine;
    load(engine, lib);
    const std::int64_t a = lib.ids[3];
    engine.onTrackStarted(a);
    CHECK(engine.onPlaybackOutcome(a, 0.05f, kNow, true) == SessionEvent::FastSkip, "skip");
    CHECK(dotWith(engine.sessionVector(), engine, a) < 0.0f, "S_t now points away: %.2f",
          dotWith(engine.sessionVector(), engine, a));
    const std::int64_t next = lib.ids[30];
    engine.onTrackStarted(next);
    generate(engine, next, 6, {a});
    const auto& check = engine.lastAudit().checks[static_cast<int>(AuditCheckId::SkipRepulsion)];
    CHECK(engine.lastAudit().afterFastSkip, "audit knows about the skip");
    CHECK(check.status == AuditStatus::Healed, "degenerate repulsion reported as healed (%d)", static_cast<int>(check.status));
    CHECK(std::fabs(norm(engine.sessionVector()) - 1.0f) < 1e-3f, "still unit");
}

void skipReRanksTheTail() {
    const Library lib = makeLibrary(5, 30, 3);
    SmartShuffleEngine engine;
    load(engine, lib);
    std::vector<std::int64_t> played;
    for (int i = 0; i < 4; ++i) {
        engine.onTrackStarted(lib.ids[i]);
        engine.onPlaybackOutcome(lib.ids[i], 1.0f, kNow, true);
        played.push_back(lib.ids[i]);
    }
    auto queue = generate(engine, played.back(), 8, played);
    // A song like the queue's is fast-skipped.
    const std::int64_t skipped = queue.front();
    engine.onTrackStarted(skipped);
    engine.onPlaybackOutcome(skipped, 0.05f, kNow, true);
    played.push_back(skipped);
    const std::int64_t nowPlaying = queue[1];
    engine.onTrackStarted(nowPlaying);
    played.push_back(nowPlaying);
    std::vector<std::int64_t> oldTail(queue.begin() + 2, queue.end());
    auto next = generate(engine, nowPlaying, 8, played, oldTail, 9);
    const auto& check = engine.lastAudit().checks[static_cast<int>(AuditCheckId::SkipRepulsion)];
    CHECK(check.status != AuditStatus::Failed, "skip check: %s", check.detail.c_str());
    int distinct = 0;
    for (auto id : next) {
        const bool old = std::find(oldTail.begin(), oldTail.end(), id) != oldTail.end();
        if (!old && engine.cosine(id, skipped) < 0.5f) ++distinct;
    }
    CHECK(distinct * 10 >= static_cast<int>(next.size()) * 6, "%d of %zu new and distinct", distinct, next.size());
    bool pivot = false;
    for (const auto& t : engine.lastTraces()) pivot = pivot || t.reason == ReasonTag::PostSkipPivot;
    CHECK(pivot, "some track explains itself as a post-skip pivot");
}

void queueFollowsTheVibeAndNeverRepeats() {
    const Library lib = makeLibrary(5, 40, 4);
    SmartShuffleEngine engine;
    load(engine, lib);
    for (int i = 0; i < 3; ++i) {
        engine.onTrackStarted(lib.ids[i]);
        engine.onPlaybackOutcome(lib.ids[i], 0.9f, kNow, false);
    }
    const auto queue = generate(engine, lib.ids[2], 12, {lib.ids[0], lib.ids[1]});
    CHECK(queue.size() == 12, "full queue: %zu", queue.size());
    std::set<std::int64_t> unique(queue.begin(), queue.end());
    CHECK(unique.size() == queue.size(), "no duplicates");
    for (int i = 0; i < 3; ++i) CHECK(!unique.count(lib.ids[i]), "played songs are not queued again");
    int sameFamily = 0;
    for (auto id : queue) sameFamily += (id - 100000) / 1000 == 0 ? 1 : 0;
    CHECK(sameFamily >= 9, "queue stays near the session: %d of 12", sameFamily);
    CHECK(engine.lastAudit().healthy(), "audit healthy: %s", problems(engine.lastAudit()).c_str());
    for (const auto& t : engine.lastTraces()) {
        const float sum = t.vibeSimilarityPct + t.markovHabitPct + t.userAffinityPct + t.explorationBonusPct;
        CHECK(std::fabs(sum - 100.0f) < 0.5f, "percentages sum to %.2f", sum);
        CHECK(t.recencyPenaltyMultiplier > 0.0f && t.recencyPenaltyMultiplier <= 1.0f, "cooldown in (0,1]");
        CHECK(t.transitionSmoothnessScore >= 0.0f && t.transitionSmoothnessScore <= 1.0f, "smoothness in [0,1]");
        CHECK(!t.primaryReasonTag.empty(), "reason tagged");
    }
}

void sameSeedSameQueue() {
    const Library lib = makeLibrary(4, 30, 3);
    SmartShuffleEngine a;
    SmartShuffleEngine b;
    load(a, lib);
    load(b, lib);
    a.onTrackStarted(lib.ids[5]);
    b.onTrackStarted(lib.ids[5]);
    CHECK(generate(a, lib.ids[5], 10, {}, {}, 77) == generate(b, lib.ids[5], 10, {}, {}, 77), "deterministic per seed");
    SmartShuffleEngine c;
    load(c, lib);
    c.onTrackStarted(lib.ids[5]);
    std::set<std::vector<std::int64_t>> different;
    for (std::uint64_t s = 0; s < 6; ++s) different.insert(generate(c, lib.ids[5], 10, {}, {}, s));
    CHECK(different.size() > 1, "different seeds give different queues");
}

void markovHabitsAreLearned() {
    const Library lib = makeLibrary(3, 30, 3);
    SmartShuffleEngine engine;
    load(engine, lib);
    const std::int64_t from = lib.ids[0];
    const std::int64_t to = lib.ids[70];  // another family entirely
    for (int i = 0; i < 4; ++i) {
        engine.onTrackStarted(from);
        engine.onPlaybackOutcome(from, 1.0f, kNow - 86400000LL * 10, false);
        engine.onTrackStarted(to);
        engine.onPlaybackOutcome(to, 1.0f, kNow - 86400000LL * 10, false);
        std::int64_t f = 0;
        std::int64_t t = 0;
        float delta = 0.0f;
        CHECK(engine.lastLearnedTransition(&f, &t, &delta) && f == from && t == to && delta == 1.0f,
              "transition reported for persistence");
        engine.resetSession();
    }
    CHECK(engine.markovProbability(from, to) > 0.5f, "habit learned: %.2f", engine.markovProbability(from, to));
    // Markov, raised, puts the habitual follower in the queue against the vibe.
    Weights w;
    w.markov = 2.5f;
    engine.setWeights(w);
    engine.onTrackStarted(from);
    const auto queue = generate(engine, from, 6, {}, {}, 5);
    CHECK(std::find(queue.begin(), queue.end(), to) != queue.end(), "habitual follower queued");
    bool tagged = false;
    for (const auto& t : engine.lastTraces()) tagged = tagged || (t.trackId == to && t.reason == ReasonTag::MarkovFavorite);
    CHECK(tagged, "and explained as a Markov favorite transition");
}

void banditLearnsZones() {
    const Library lib = makeLibrary(3, 30, 3);
    SmartShuffleEngine engine;
    load(engine, lib);
    engine.onTrackStarted(lib.ids[0]);
    engine.onPlaybackOutcome(lib.ids[0], 1.0f, kNow, false);
    engine.onTrackStarted(lib.ids[1]);
    engine.onPlaybackOutcome(lib.ids[1], 1.0f, kNow, true);
    int context = -1;
    int arm = -1;
    float reward = -1.0f;
    CHECK(engine.lastBanditUpdate(&context, &arm, &reward) && reward == 1.0f, "a finished pick rewards its zone");
    float pulls[kZones * kZones];
    float rewards[kZones * kZones];
    engine.zoneBandit(pulls, rewards);
    CHECK(pulls[context * kZones + arm] == 1.0f && rewards[context * kZones + arm] == 1.0f, "stored");
    SmartShuffleEngine restored;
    load(restored, lib);
    restored.setZoneBandit(pulls, rewards);
    float again[kZones * kZones];
    restored.zoneBandit(again, nullptr);
    CHECK(std::equal(pulls, pulls + kZones * kZones, again), "round trip");
}

void cooldownKeepsRecentSongsBack() {
    const Library lib = makeLibrary(2, 30, 30);
    SmartShuffleEngine engine;
    load(engine, lib);
    std::vector<std::int32_t> completed(lib.ids.size(), 0);
    std::vector<std::int32_t> skipped(lib.ids.size(), 0);
    std::vector<std::int64_t> last(lib.ids.size(), 0);
    for (int i = 1; i < 30; i += 2) last[i] = kNow - 10 * 60 * 1000;  // ten minutes ago
    engine.setTrackStats(completed.data(), skipped.data(), last.data(), nullptr, static_cast<int>(lib.ids.size()));
    engine.onTrackStarted(lib.ids[0]);
    const auto queue = generate(engine, lib.ids[0], 8);
    int recent = 0;
    for (auto id : queue) recent += ((id - 100000) % 2 == 1 && id < 101000) ? 1 : 0;
    CHECK(recent <= 1, "songs heard ten minutes ago stay back: %d", recent);
    bool reported = false;
    for (const auto& t : engine.lastTraces()) reported = reported || t.recencyPenaltyMultiplier < 0.5f;
    CHECK(!reported || recent > 0, "a cooled-down song only shows a low multiplier if it was picked");
}

// ---------------------------------------------------------------------------
// Part 4: the brain
// ---------------------------------------------------------------------------

void antiClumpingSpacesArtists() {
    Library lib = makeLibrary(2, 40, 40);  // one artist per family
    for (size_t i = 0; i < 40; i += 4) lib.artists[i] = 7000 + static_cast<int>(i);  // a few others
    SmartShuffleEngine engine;
    load(engine, lib);
    engine.onTrackStarted(lib.ids[1]);
    const auto queue = generate(engine, lib.ids[1], 10);
    const auto& check = engine.lastAudit().checks[static_cast<int>(AuditCheckId::AntiClumping)];
    CHECK(check.status != AuditStatus::Failed, "clumping: %s", check.detail.c_str());
    // Wherever the check says spacing s is achievable, the queue honours it.
    const int spacing = static_cast<int>(check.threshold);
    std::vector<std::int32_t> artistOf;
    for (auto id : queue) {
        const auto it = std::find(lib.ids.begin(), lib.ids.end(), id);
        artistOf.push_back(lib.artists[it - lib.ids.begin()]);
    }
    for (size_t a = 0; a < artistOf.size(); ++a) {
        for (size_t b = a + 1; b < artistOf.size() && b < a + spacing; ++b) {
            CHECK(artistOf[a] != artistOf[b], "artist %d at %zu and %zu", artistOf[a], a, b);
        }
    }

    // A one-artist library cannot be spaced: that is not a failure.
    Library mono = makeLibrary(1, 20, 20);
    SmartShuffleEngine single;
    load(single, mono);
    single.onTrackStarted(mono.ids[0]);
    generate(single, mono.ids[0], 8);
    CHECK(single.lastAudit().checks[0].status != AuditStatus::Failed, "one artist: %s",
          single.lastAudit().checks[0].detail.c_str());
}

void echoChamberGetsFreshTracks() {
    // Thirty near-identical songs and thirty very different ones.
    Library lib;
    std::mt19937 rng(5);
    std::uniform_real_distribution<float> tiny(0.999f, 1.001f);
    for (int i = 0; i < 60; ++i) {
        lib.ids.push_back(500 + i);
        lib.artists.push_back(i);
        lib.quality.push_back(static_cast<std::uint8_t>(VectorQuality::Real));
        lib.family.push_back(i < 30 ? 0 : 1);
        for (int d = 0; d < kDims; ++d) {
            const float base = i < 30 ? (d < 3 ? 1.0f : 0.1f) : (d >= 7 && d < 10 ? 1.0f : 0.3f);
            lib.raw.push_back(base * tiny(rng));
        }
    }
    SmartShuffleEngine engine;
    load(engine, lib);
    engine.onTrackStarted(500);
    const auto queue = generate(engine, 500, 10);
    const auto& check = engine.lastAudit().checks[static_cast<int>(AuditCheckId::EchoChamber)];
    CHECK(check.status == AuditStatus::Healed, "echo chamber healed: %s", check.detail.c_str());
    CHECK(engine.exploreBoost() > 1.4f, "w_explore boosted x%.2f", engine.exploreBoost());
    CHECK(engine.lastAudit().averagePairwiseSimilarity <= 0.97f, "avg %.2f", engine.lastAudit().averagePairwiseSimilarity);
    int fresh = 0;
    for (auto id : queue) fresh += id >= 530 ? 1 : 0;
    CHECK(fresh >= 1, "fresh tracks injected: %d", fresh);
}

void integrityHealsAPoisonedSession() {
#ifdef HARMONY_SHUFFLE_TESTING
    const Library lib = makeLibrary(3, 20, 4);
    SmartShuffleEngine engine;
    load(engine, lib);
    engine.onTrackStarted(lib.ids[0]);
    engine.poisonSessionForTest(std::numeric_limits<float>::quiet_NaN());
    const auto queue = generate(engine, lib.ids[0], 6);
    const auto& check = engine.lastAudit().checks[static_cast<int>(AuditCheckId::VectorIntegrity)];
    CHECK(check.status == AuditStatus::Healed, "NaN session healed: %s", check.detail.c_str());
    CHECK(queue.size() == 6, "and the queue still filled");
    CHECK(std::fabs(norm(engine.sessionVector()) - 1.0f) < 1e-3f, "unit again");
    engine.poisonSessionForTest(3.0f);  // finite, wrong length
    generate(engine, lib.ids[0], 6);
    CHECK(engine.lastAudit().checks[3].status == AuditStatus::Healed, "drifted norm renormalized");
    CHECK(std::fabs(engine.lastAudit().sessionNorm - 1.0f) < 1e-3f, "norm %.4f", engine.lastAudit().sessionNorm);
#endif
}

void coldStartIsReported() {
    Library lib = makeLibrary(2, 20, 4);
    // Ten songs only known from the old analysis (some dims unknown), five not at all.
    for (int i = 0; i < 10; ++i) {
        lib.quality[i] = static_cast<std::uint8_t>(VectorQuality::Fallback);
        for (int d = kBands; d < 2 * kBands; ++d) lib.raw[i * kDims + d] = std::numeric_limits<float>::quiet_NaN();
    }
    for (int i = 10; i < 15; ++i) {
        lib.quality[i] = static_cast<std::uint8_t>(VectorQuality::None);
        for (int d = 0; d < kDims; ++d) lib.raw[i * kDims + d] = std::numeric_limits<float>::quiet_NaN();
    }
    SmartShuffleEngine engine;
    load(engine, lib);
    engine.onTrackStarted(lib.ids[20]);
    generate(engine, lib.ids[20], 8);
    const auto& audit = engine.lastAudit();
    CHECK(audit.realTracks == 25 && audit.fallbackTracks == 10 && audit.placeholderTracks == 5,
          "coverage %d/%d/%d", audit.realTracks, audit.fallbackTracks, audit.placeholderTracks);
    CHECK(std::fabs(audit.realVectorPct - 62.5f) < 0.01f, "real %.1f%%", audit.realVectorPct);
    float v[kDims];
    engine.vectorOf(lib.ids[12], v);
    CHECK(std::fabs(norm(v) - 1.0f) < 1e-4f, "placeholders are still unit vectors");
    CHECK(engine.onTrackStarted(lib.ids[12]) == SessionEvent::None, "a placeholder never steers the session");
}

void sonicAdventureTravels() {
    const Library lib = makeLibrary(5, 30, 3);
    SmartShuffleEngine engine;
    load(engine, lib);
    const std::int64_t from = lib.ids[0];     // family 0
    const std::int64_t to = lib.ids[4 * 30];  // family 4
    std::vector<std::int64_t> path;
    std::int64_t previous = from;
    float lastToward = -2.0f;
    int forward = 0;
    for (int step = 1; step <= 10; ++step) {
        const std::int64_t id = engine.journeyStep(from, to, step / 10.0f, previous, path.data(),
                                                   static_cast<int>(path.size()), kNow, step);
        CHECK(id >= 0, "step %d", step);
        if (id < 0) break;
        CHECK(std::find(path.begin(), path.end(), id) == path.end(), "no repeats");
        const float toward = engine.cosine(id, to);
        forward += toward >= lastToward - 0.15f ? 1 : 0;
        lastToward = toward;
        path.push_back(id);
        previous = id;
        CHECK(engine.lastTraces().size() == 1 && engine.lastTraces()[0].reason == ReasonTag::SonicAdventure,
              "journey step explained");
    }
    CHECK(engine.cosine(path.front(), from) > engine.cosine(path.front(), to), "starts near the origin");
    CHECK(engine.cosine(path.back(), to) > 0.6f, "arrives near the destination: %.2f", engine.cosine(path.back(), to));
    CHECK(forward >= 8, "moves steadily toward it: %d of 10 steps", forward);
}

void smallAndEmptyLibraries() {
    SmartShuffleEngine empty;
    CHECK(generate(empty, 1, 5).empty(), "empty library: empty queue");
    CHECK(empty.lastAudit().healthy(), "and a healthy audit");

    const Library lib = makeLibrary(1, 3, 1);
    SmartShuffleEngine tiny;
    load(tiny, lib);
    tiny.onTrackStarted(lib.ids[0]);
    const auto queue = generate(tiny, lib.ids[0], 10, {lib.ids[1]});
    CHECK(queue.size() == 2, "only what exists, excluded songs return when nothing else is left: %zu", queue.size());
    CHECK(tiny.journeyStep(lib.ids[0], 424242, 0.5f, lib.ids[0], nullptr, 0, kNow, 1) == -1, "unknown destination");
}

void steeringBiasesTheQueue() {
    const Library lib = makeLibrary(2, 30, 3);
    SmartShuffleEngine engine;
    load(engine, lib);
    std::vector<float> steer(lib.ids.size(), 0.0f);
    for (int i = 0; i < 30; i += 3) steer[i] = 1.0f;  // an "energy target" only every third song meets
    engine.setSteering(steer.data(), static_cast<int>(steer.size()), 1.5f);
    engine.onTrackStarted(lib.ids[1]);
    const auto queue = generate(engine, lib.ids[1], 6);
    int steered = 0;
    for (auto id : queue) steered += ((id - 100000) % 3 == 0 && id < 101000) ? 1 : 0;
    CHECK(steered >= 4, "steering pulls its songs forward: %d of 6", steered);
    engine.setSteering(nullptr, 0, 0.0f);
    CHECK(engine.weights().steering == 0.0f, "and clears");
}

void fastOnALargeLibrary() {
    const Library lib = makeLibrary(20, 1000, 8);  // 20,000 songs
    SmartShuffleEngine engine;
    load(engine, lib);
    engine.onTrackStarted(lib.ids[0]);
    engine.onPlaybackOutcome(lib.ids[0], 1.0f, kNow, false);
    generate(engine, lib.ids[0], 10);  // warm up
    const auto start = std::chrono::steady_clock::now();
    constexpr int kRuns = 20;
    for (int i = 0; i < kRuns; ++i) generate(engine, lib.ids[0], 10, {}, {}, i);
    const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count() / kRuns;
    std::printf("20k-song queue of 10 with full audit: %.2f ms\n", ms);
    CHECK(ms < 25.0, "%.2f ms per queue", ms);
}

void selfTestPasses() {
    bool passed = false;
    const std::string report = runSyntheticBrainSimulation(&passed);
    CHECK(passed, "self-test:\n%s", report.c_str());
    CHECK(report.find("RESULT: PASS") != std::string::npos, "report carries the verdict");
}

}  // namespace

int main() {
    extractorHearsBassAndTreble();
    extractorHandlesEdges();
    vectorsAreUnitAndStandardized();
    sessionVectorFollowsTheSpec();
    skippingTheSeedStillRepels();
    skipReRanksTheTail();
    queueFollowsTheVibeAndNeverRepeats();
    sameSeedSameQueue();
    markovHabitsAreLearned();
    banditLearnsZones();
    cooldownKeepsRecentSongsBack();
    antiClumpingSpacesArtists();
    echoChamberGetsFreshTracks();
    integrityHealsAPoisonedSession();
    coldStartIsReported();
    sonicAdventureTravels();
    smallAndEmptyLibraries();
    steeringBiasesTheQueue();
    fastOnALargeLibrary();
    selfTestPasses();
    if (failures == 0) {
        std::printf("All SmartShuffleEngine tests passed\n");
        return 0;
    }
    std::printf("%d check(s) failed\n", failures);
    return 1;
}
