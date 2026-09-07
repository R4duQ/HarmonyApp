// smart_shuffle.h — Harmony Smart Shuffle candidate selector.
//
// Pure C++17. No JNI, no Android headers, no allocation of the library data —
// the caller owns every buffer. That keeps this file compilable and testable
// on the host machine, which is where the scoring rules should be pinned down.
//
// Coordinate space: each track is a vector of normalized features in [0, 1],
// e.g. [Energy, Acousticness, Normalized_BPM, Valence, Danceability]. The
// engine never assumes 5 dimensions; `dim` is carried with the data.

#ifndef HARMONY_SMART_SHUFFLE_H
#define HARMONY_SMART_SHUFFLE_H

#include <cstdint>

namespace harmony::shuffle {

/// Two hours, the fatigue window from the product spec.
inline constexpr std::int64_t kDefaultRecencyWindowMs = 2 * 60 * 60 * 1000;

/// Tunables. Defaults implement the spec as written.
struct Config {
    /// Same-artist penalty. 0.50 == "50% distance penalty".
    float sameArtistPenalty = 0.50f;

    /// Peak penalty for a track played *just now*, decaying to 0 across the
    /// window. Set above sameArtistPenalty deliberately: hearing the same
    /// song twice in an hour is more jarring than hearing the same artist.
    float recentPlayPenalty = 0.75f;

    /// Fatigue window. Outside it, recency contributes nothing.
    std::int64_t recencyWindowMs = kDefaultRecencyWindowMs;

    /// Additive component of the penalty, as a fraction of the feature-space
    /// diagonal (sqrt(dim)). This is the important one, and it is not in the
    /// spec.
    ///
    /// A purely multiplicative penalty (d * 1.5) is worthless exactly where it
    /// matters most: for a near-duplicate track by the same artist d ~= 0.02,
    /// and 0.02 * 1.5 = 0.03 still beats an unrelated track at d = 0.25. The
    /// rule would be arithmetically applied and behaviourally invisible. The
    /// additive floor is what makes it bite at small distances.
    ///
    /// Expressed as a fraction of sqrt(dim) rather than in absolute units so
    /// it stays calibrated if you add features later. At the default 0.15 with
    /// dim = 5 the same-artist rule adds 0.5 * 0.15 * sqrt(5) ~= 0.168, which
    /// is a large step in a space where near neighbours sit at 0.05-0.30 —
    /// enough to push a same-artist track out of the top 5 in a real library,
    /// while still letting it win if the alternatives are genuinely unrelated.
    /// This value is the main quality knob; it was calibrated against the
    /// behavioural tests, not derived.
    float penaltyFloorFraction = 0.15f;

    /// Size of the random-walk pool.
    int topK = 5;

    /// Optional per-dimension weights, length == dim, or null for uniform.
    /// Euclidean distance over raw normalized features treats a BPM shift as
    /// equal in importance to a valence shift, which is rarely what you want;
    /// this is the cheapest lever for tuning perceived quality.
    const float* dimWeights = nullptr;
};

/// The currently playing track.
struct Seed {
    const float* features = nullptr;  ///< length == Library::dim
    std::int64_t trackId = -1;
    std::int32_t artistId = -1;       ///< interned on the Kotlin side
};

/// The candidate pool, as flat parallel arrays (see the JNI bridge for why).
struct Library {
    const float* features = nullptr;       ///< count * dim, row-major
    const std::int64_t* trackIds = nullptr;
    const std::int32_t* artistIds = nullptr;
    const std::int64_t* lastPlayedMs = nullptr;  ///< <= 0 means "never played"
    std::int32_t count = 0;
    std::int32_t dim = 0;
};

struct Selection {
    std::int32_t index = -1;           ///< index into Library, or -1
    std::int64_t trackId = -1;
    float adjustedDistance = 0.0f;
    std::int32_t consideredCount = 0;  ///< candidates that survived filtering
    std::int32_t poolSize = 0;         ///< min(topK, consideredCount)

    [[nodiscard]] bool valid() const { return index >= 0; }
};

/// Raw Euclidean distance, before penalties. Exposed for tests and telemetry.
float euclideanDistance(const float* a, const float* b, std::int32_t dim,
                        const float* dimWeights);

/// Recency fatigue in [0, 1]: 1.0 if played this instant, 0.0 at or beyond the
/// window. Exposed so the decay curve can be characterised in a unit test.
float recencyFactor(std::int64_t lastPlayedMs, std::int64_t nowMs,
                    std::int64_t windowMs);

/// Full adjusted score for one candidate. Returns a negative value if the
/// candidate should be skipped entirely (seed itself, or non-finite features).
float adjustedScore(const Seed& seed, const Library& lib, std::int32_t i,
                    const Config& cfg, std::int64_t nowMs);

/// Scores the library, takes the topK lowest, and picks one uniformly at
/// random. `rngSeed` is explicit so a given call is reproducible in tests;
/// production callers should pass something that varies (see the Kotlin side).
Selection selectNext(const Seed& seed, const Library& lib, const Config& cfg,
                     std::int64_t nowMs, std::uint64_t rngSeed);

}  // namespace harmony::shuffle

#endif  // HARMONY_SMART_SHUFFLE_H
