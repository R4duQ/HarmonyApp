// smart_shuffle.cpp — see smart_shuffle.h for the API contract.

#include "smart_shuffle.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <random>
#include <vector>

namespace harmony::shuffle {
namespace {

/// Finite check that survives -ffast-math.
///
/// The existing harmonydsp target compiles with -ffast-math, under which the
/// compiler is allowed to assume no NaN/Inf exists and may fold std::isnan()
/// to a constant false. Inspecting the bit pattern instead of comparing floats
/// gives the same answer under any optimisation level. The CMake target for
/// this file also drops -ffast-math, so this is belt and braces — but it is
/// cheap, and a silently-disabled guard is worse than no guard.
inline bool isFiniteBits(float v) {
    std::uint32_t bits;
    std::memcpy(&bits, &v, sizeof(bits));
    // Exponent all-ones == Inf or NaN.
    return (bits & 0x7F800000u) != 0x7F800000u;
}

/// One scored candidate. Kept to 8 bytes so the top-K pass stays cache-friendly.
struct Scored {
    float score;
    std::int32_t index;
};

}  // namespace

float euclideanDistance(const float* a, const float* b, std::int32_t dim,
                        const float* dimWeights) {
    float sum = 0.0f;
    for (std::int32_t d = 0; d < dim; ++d) {
        const float delta = a[d] - b[d];
        // Weights apply to the squared term, so a weight of 4 doubles that
        // dimension's contribution to the final (post-sqrt) distance.
        const float w = dimWeights ? dimWeights[d] : 1.0f;
        sum += w * delta * delta;
    }
    return std::sqrt(sum);
}

float recencyFactor(std::int64_t lastPlayedMs, std::int64_t nowMs,
                    std::int64_t windowMs) {
    if (lastPlayedMs <= 0) return 0.0f;   // never played
    if (windowMs <= 0) return 0.0f;       // fatigue disabled

    std::int64_t elapsed = nowMs - lastPlayedMs;

    // Clock skew / NTP correction / a row written by a device in another
    // timezone can all produce a timestamp in the future. Treat that as
    // "just played" rather than letting it produce a negative elapsed time
    // and a penalty above the configured maximum.
    if (elapsed < 0) elapsed = 0;
    if (elapsed >= windowMs) return 0.0f;

    // Linear decay across the window. Swap for an exponential half-life
    // (std::exp(-elapsed / halfLife * ln2)) if listening data shows fatigue
    // fading faster than linearly; the rest of the engine is unaffected.
    return 1.0f - static_cast<float>(elapsed) / static_cast<float>(windowMs);
}

float adjustedScore(const Seed& seed, const Library& lib, std::int32_t i,
                    const Config& cfg, std::int64_t nowMs) {
    // Never recommend the track that is already playing.
    if (lib.trackIds[i] == seed.trackId) return -1.0f;

    const float* candidate = lib.features + static_cast<std::size_t>(i) * lib.dim;

    // A single corrupt feature (a failed analysis writing NaN, a divide-by-zero
    // in normalization) would otherwise poison the sort: NaN compares false
    // against everything and can leave nth_element with a garbage pivot.
    for (std::int32_t d = 0; d < lib.dim; ++d) {
        if (!isFiniteBits(candidate[d])) return -1.0f;
    }

    const float distance =
        euclideanDistance(seed.features, candidate, lib.dim, cfg.dimWeights);
    if (!isFiniteBits(distance)) return -1.0f;

    // Penalty weights accumulate. A same-artist track played 20 minutes ago
    // gets both, which is correct: those are independent reasons for fatigue.
    float weight = 0.0f;
    if (seed.artistId >= 0 && lib.artistIds[i] == seed.artistId) {
        weight += cfg.sameArtistPenalty;
    }
    weight += cfg.recentPlayPenalty *
              recencyFactor(lib.lastPlayedMs[i], nowMs, cfg.recencyWindowMs);

    // Multiplicative term honours the spec's "50% distance penalty"; the
    // additive floor is what makes it bite at small distances. See the
    // penaltyFloorFraction comment in the header.
    const float floorUnits =
        cfg.penaltyFloorFraction * std::sqrt(static_cast<float>(lib.dim));
    return distance * (1.0f + weight) + weight * floorUnits;
}

Selection selectNext(const Seed& seed, const Library& lib, const Config& cfg,
                     std::int64_t nowMs, std::uint64_t rngSeed) {
    Selection result;

    // --- Guard the shape of the input before touching any buffer. ---
    if (seed.features == nullptr) return result;
    if (lib.features == nullptr || lib.trackIds == nullptr ||
        lib.artistIds == nullptr || lib.lastPlayedMs == nullptr) {
        return result;
    }
    if (lib.count <= 0 || lib.dim <= 0) return result;

    std::vector<Scored> scored;
    scored.reserve(static_cast<std::size_t>(lib.count));

    for (std::int32_t i = 0; i < lib.count; ++i) {
        const float s = adjustedScore(seed, lib, i, cfg, nowMs);
        if (s < 0.0f) continue;  // skipped: seed itself, or unusable features
        scored.push_back(Scored{s, i});
    }

    result.consideredCount = static_cast<std::int32_t>(scored.size());

    // A library of one track, or one where everything was filtered out. The
    // caller decides what to do (repeat, stop, fall back to linear order) —
    // silently returning the seed again would be worse.
    if (scored.empty()) return result;

    // Small-library case: the pool is simply everything available. With 3
    // candidates this is a uniform pick over all 3, which is the correct
    // degenerate behaviour — no padding, no repetition to reach 5.
    const int poolSize =
        std::min(cfg.topK > 0 ? cfg.topK : 1,
                 static_cast<int>(scored.size()));

    // Deterministic ordering matters: nth_element is not stable, so two
    // candidates with identical scores could otherwise land in or out of the
    // pool depending on the standard library's internal pivot choices. Tying
    // on trackId makes a given (library, seed, now, rngSeed) reproducible,
    // which is what makes the behaviour testable and bug reports actionable.
    const auto better = [&lib](const Scored& a, const Scored& b) {
        if (a.score != b.score) return a.score < b.score;
        return lib.trackIds[a.index] < lib.trackIds[b.index];
    };

    // O(n) partition rather than an O(n log n) full sort. At 50k tracks this
    // is the difference between "free" and "noticeable on a track change".
    if (poolSize < static_cast<int>(scored.size())) {
        std::nth_element(scored.begin(), scored.begin() + poolSize,
                         scored.end(), better);
    }

    // Uniform over the pool, per the spec. If the 5th-best being as likely as
    // the best turns out to feel too loose, weight by inverse rank here —
    // nothing else in the engine has to change.
    std::mt19937_64 rng(rngSeed);
    std::uniform_int_distribution<int> pick(0, poolSize - 1);
    const Scored& chosen = scored[static_cast<std::size_t>(pick(rng))];

    result.index = chosen.index;
    result.trackId = lib.trackIds[chosen.index];
    result.adjustedDistance = chosen.score;
    result.poolSize = poolSize;
    return result;
}

}  // namespace harmony::shuffle
