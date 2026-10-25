// SmartShuffleEngine.h: Harmony's offline Smart Shuffle and the
// ShuffleDiagnosticBrain that audits it.
//
// Everything here runs on the phone, from audio alone. No network, no account,
// and no genre tags: a song is known by a 24-dimensional acoustic vector that
// the background indexer measures from its decoded PCM (AcousticExtractor).
//
//   AcousticExtractor   decoded PCM -> 24 raw acoustic features
//   SmartShuffleEngine  library of unit vectors, the session vibe vector S_t,
//                       multi-factor scoring, Efraimidis-Spirakis sampling,
//                       1D error-diffusion dithering (Spotify's anti-clumping)
//                       and greedy nearest-neighbour ordering; Sonic Adventure
//                       journeys between two songs
//   ShuffleDiagnosticBrain
//                       explains every queued track (TrackDecisionTrace),
//                       checks five invariants after every generation and
//                       repairs the queue when one fails (runBrainSelfAudit)
//   runSyntheticBrainSimulation
//                       a built-in self-test with 100 virtual tracks
//
// Pure C++17, no JNI and no Android headers, so the whole engine builds and is
// tested on the host (src/test/cpp). Not thread-safe: one caller at a time
// (the JNI bridge holds a lock per engine).
//
// ## The 24 dimensions
//
//   0-9    mean spectral profile: the RMS level of each octave band (31 Hz to
//          16 kHz, the same band-pass bank as the EQ engine's live analyser),
//          L2-normalized across the ten bands
//   10-19  groove dynamics: how much each band's level moves from one 50 ms
//          block to the next (coefficient of variation, mapped to 0..1)
//   20     sub-bass punch: rises of the sub-bass (below 80 Hz) envelope
//          against the broadband level
//   21     spectral centroid on the octave axis (0 = dark, 1 = bright)
//   22     crest factor (peak to RMS), 3..30 dB mapped to 0..1
//   23     pulse rate: detected onsets per second, mapped to 0..1
//
// ## The working space
//
// The raw features are all positive, and positive vectors are always close:
// two unrelated songs score a cosine of 0.9 or more, which leaves similarity
// nothing to discriminate with (and would trip the echo-chamber check on every
// queue). So setLibrary standardizes each dimension against the library
// (z-score, clamped to +-4), weights the three groups, and only then
// normalizes every vector to unit length. In that space cosine similarity is
// a plain 24-float dot product, unrelated songs sit near 0, and a dimension
// marked unknown (NaN, for songs indexed only by the older analysis) lands on
// the library average instead of skewing the vector.

#ifndef HARMONY_SMART_SHUFFLE_ENGINE_H
#define HARMONY_SMART_SHUFFLE_ENGINE_H

#include <array>
#include <cstdint>
#include <deque>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace harmony::shuffle {

inline constexpr int kDims = 24;
inline constexpr int kBands = 10;
/** The bandit's contexts and arms: the octave band a vector leans on most. */
inline constexpr int kZones = kBands;
inline constexpr int kAuditChecks = 5;

/** Band centres, Hz: 31.25 * 2^k. */
extern const float kBandCentersHz[kBands];

// ---------------------------------------------------------------------------
// Part 1: 24-D acoustic vector extraction
// ---------------------------------------------------------------------------

/**
 * Streams mono PCM (any sample rate from 8 kHz to 768 kHz) into the 24 raw
 * features. Allocation-free after construction, so it can share the indexer's
 * decode loop with the older analyser.
 */
class AcousticExtractor {
public:
    explicit AcousticExtractor(int sampleRate);

    bool valid() const { return sampleRate_ > 0; }

    /** [-1, 1] mono samples. Non-finite samples count as silence. */
    void process(const float* mono, int count);

    /**
     * Writes the 24 raw features (each in [0, 1]). Returns false when there
     * were fewer than two seconds of non-silent audio to measure.
     */
    bool finish(float out[kDims]);

    /** Seconds of non-silent audio measured so far. */
    double activeSeconds() const;

private:
    struct Biquad {
        double b0 = 0, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
        double z1 = 0, z2 = 0;
        bool enabled = false;
        double run(double x) {
            const double y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }
    };

    void closeBlock();

    int sampleRate_ = 0;
    int blockLength_ = 0;
    int blockFill_ = 0;
    Biquad bands_[kBands];
    Biquad subLow_;
    double blockBandPower_[kBands] = {};
    double blockBroadPower_ = 0;
    double blockSubPower_ = 0;
    double blockPeak_ = 0;

    // Whole-song accumulators over non-silent blocks.
    long long activeBlocks_ = 0;
    double bandPowerSum_[kBands] = {};
    double bandAmpSum_[kBands] = {};
    double bandAmpSqSum_[kBands] = {};
    double broadPowerSum_ = 0;
    double broadAmpSum_ = 0;
    double subRiseSum_ = 0;
    double previousSubAmp_ = 0;
    double peak_ = 0;

    // Onset detection over the band levels.
    double previousLogAmp_[kBands] = {};
    bool havePreviousBlock_ = false;
    double fluxHistory_[20] = {};
    int fluxHistoryFill_ = 0;
    int fluxHistoryPos_ = 0;
    double previousFlux_ = 0;
    double previousThreshold_ = 0;
    bool previousRising_ = false;
    long long onsets_ = 0;
};

// ---------------------------------------------------------------------------
// Parts 2-4: engine, telemetry and the Diagnostic Brain
// ---------------------------------------------------------------------------

/** How much the engine knows about a track. */
enum class VectorQuality : std::uint8_t {
    /** Nothing measured: a deterministic placeholder direction. */
    None = 0,
    /** Approximated from the older analysis (cold-start fallback). */
    Fallback = 1,
    /** A real 24-D measurement. */
    Real = 2,
};

enum class SessionEvent : int {
    None = 0,
    /** S_t started from this song. */
    Seeded = 1,
    /** Played past half: S_t = normalize(0.60 v + 0.40 S_t-1). */
    PlayEma = 2,
    /** Skipped before 20%: S_t = normalize(S_t-1 - 0.35 v). Steers the next picks. */
    FastSkip = 3,
    /** Chosen by hand: S_t = normalize(0.85 v + 0.15 S_t-1). */
    ManualAnchor = 4,
    /** Between 20% and 50%, or a song without a usable vector: S_t holds. */
    Neutral = 5,
};

enum class AuditStatus : int {
    Pass = 0,
    /** The check failed and the brain repaired it. */
    Healed = 1,
    /** The check failed and could not be fully repaired (the library is too small). */
    Failed = 2,
};

enum class AuditCheckId : int {
    AntiClumping = 0,
    SkipRepulsion = 1,
    EchoChamber = 2,
    VectorIntegrity = 3,
    TransitionJerk = 4,
};

struct AuditCheck {
    AuditStatus status = AuditStatus::Pass;
    /** The measured value (see BrainAuditReport for each check's metric). */
    float metric = 0.0f;
    float threshold = 0.0f;
    /** Repairs made: swaps, replacements, renormalizations. */
    int corrections = 0;
    std::string detail;
};

/**
 * The five checks, run on every generated or re-ranked queue.
 *
 *   AntiClumping     metric: smallest same-artist gap; threshold: the spacing enforced
 *   SkipRepulsion    metric: share of the tail replaced with distinct tracks after a
 *                    fast skip; threshold 0.60
 *   EchoChamber      metric: average pairwise cosine of the queue; threshold 0.97
 *   VectorIntegrity  metric: ||S_t||; threshold: the 0.001 tolerance
 *   TransitionJerk   metric: largest ||v_a - v_b|| between neighbours; threshold:
 *                    the harshness limit
 */
struct BrainAuditReport {
    std::array<AuditCheck, kAuditChecks> checks{};
    std::uint64_t generation = 0;
    bool afterFastSkip = false;
    float averagePairwiseSimilarity = 0.0f;
    /** Shannon entropy of the queue's artists and of its sonic zones, bits. */
    float artistEntropyBits = 0.0f;
    float zoneEntropyBits = 0.0f;
    float sessionNorm = 0.0f;
    int nonFiniteValues = 0;
    /** Share of the library with a real 24-D vector, 0..100. */
    float realVectorPct = 0.0f;
    int realTracks = 0;
    int fallbackTracks = 0;
    int placeholderTracks = 0;
    /** Current multiplier on the exploration weight (1 = none). */
    float exploreBoost = 1.0f;
    double elapsedMs = 0.0;

    bool healthy() const;
    int healedCount() const;
    int failedCount() const;
};

enum class ReasonTag : int {
    VibeMatch = 0,
    MarkovFavorite = 1,
    BanditGem = 2,
    PostSkipPivot = 3,
    ProvenFavorite = 4,
    SonicAdventure = 5,
    ColdStart = 6,
};

/** Why the brain placed a track where it did. Percentages sum to 100. */
struct TrackDecisionTrace {
    std::int64_t trackId = -1;
    float rawScore = 0.0f;
    float vibeSimilarityPct = 0.0f;
    float markovHabitPct = 0.0f;
    float userAffinityPct = 0.0f;
    float explorationBonusPct = 0.0f;
    /** RecencyCooldown(i): 1 = no cooldown. */
    float recencyPenaltyMultiplier = 1.0f;
    /** 1 - ||v_prev - v||/2: 1 = seamless, 0 = opposite. */
    float transitionSmoothnessScore = 1.0f;
    /** Cosine with S_t (or with the journey's target). */
    float cosineSimilarity = 0.0f;
    int zone = -1;
    VectorQuality quality = VectorQuality::None;
    ReasonTag reason = ReasonTag::VibeMatch;
    std::string primaryReasonTag;
};

/** The scorer's knobs. */
struct Weights {
    float similarity = 1.00f;
    float markov = 0.30f;
    float affinity = 0.30f;
    float explore = 0.20f;
    /** Optional per-track steering (energy target, mood); folded into "vibe". */
    float steering = 0.0f;
    /** Efraimidis-Spirakis softness: weight = exp((score - best) / temperature). */
    float temperature = 0.12f;
    int minArtistSpacing = 3;
    /** ||v_a - v_b|| above this is a harsh jump (unit vectors: cosine below ~0.2). */
    float jerkThreshold = 1.25f;
    /** Above this average pairwise cosine the queue is an echo chamber. */
    float echoThreshold = 0.97f;
};

class SmartShuffleEngine;

/**
 * The auditor. Owned by the engine; runs after every generation, reads the
 * engine's state and repairs the draft queue in place.
 */
class ShuffleDiagnosticBrain {
public:
    explicit ShuffleDiagnosticBrain(SmartShuffleEngine& engine) : engine_(engine) {}

    struct Draft {
        /** Rows already ahead of the new tracks (current song, then queued ones). */
        std::vector<int> prefixRows;
        /** The new tracks, in queue order. The brain may swap or replace them. */
        std::vector<int> placedRows;
        /** Scored candidates not placed, best first: replacements come from here. */
        std::vector<int> reserveRows;
        /** The Sonic Adventure keeps its order: no swaps, replacements only. */
        bool allowReorder = true;
        /** The most explorable songs, whatever they sound like (Check 3's fresh tracks). */
        std::vector<int> freshRows;
        /** Ids queued before a fast-skip re-rank (Check 2). */
        std::vector<std::int64_t> replacedTail;
    };

    /**
     * Check 4, before anything is scored: S_t finite and of unit length (else
     * renormalized or reseeded from [currentRow]), every library vector sound,
     * and how much of the library has real vectors.
     */
    void preflight(BrainAuditReport& report, int currentRow);

    /**
     * Runs checks 2, 3, 1 and 5 on [draft] and repairs it in place. Returns
     * false when the echo-chamber check has raised w_explore by 50% and wants
     * the queue drawn again; the engine does so once, with [echoRetry] set,
     * and that audit injects fresh tracks if the boost was not enough.
     */
    bool runBrainSelfAudit(Draft& draft, BrainAuditReport& report, bool echoRetry);

private:
    void checkSkipRepulsion(Draft& draft, BrainAuditReport& report);
    bool checkEchoChamber(Draft& draft, BrainAuditReport& report, bool echoRetry);
    void checkAntiClumping(Draft& draft, BrainAuditReport& report);
    void checkTransitionJerk(Draft& draft, BrainAuditReport& report);

    int effectiveSpacing(const Draft& draft) const;
    int clumpViolations(const Draft& draft, int spacing, int* firstBad, int* minGap) const;
    float maxJump(const Draft& draft, int* worstIndex) const;
    /** ||v_prev - v|| into placed[j]; 0 when leaving a song just fast-skipped. */
    float jumpBefore(const Draft& draft, int j) const;
    /** Placed mostly by the listener's own Markov habit: never replaced to smooth a jump. */
    bool isHabitPick(int row) const;
    bool artistFits(const Draft& draft, int position, int row, int spacing) const;

    SmartShuffleEngine& engine_;
};

class SmartShuffleEngine {
public:
    SmartShuffleEngine();

    // -- Library -----------------------------------------------------------

    /**
     * Loads the library. [raw] holds count * 24 raw features (NaN = unknown),
     * [quality] one VectorQuality per track; artist and album ids are interned
     * by the caller (negative = unknown, never clumped with anyone). Resets
     * statistics and steering, keeps the session and the learned habits.
     * Returns the number of tracks loaded.
     */
    int setLibrary(const std::int64_t* ids, const float* raw, const std::uint8_t* quality,
                   const std::int32_t* artistIds, const std::int32_t* albumIds, int count);

    /** Listening history, aligned with the library order (any pointer may be null). */
    void setTrackStats(const std::int32_t* completedPlays, const std::int32_t* skippedPlays,
                       const std::int64_t* lastPlayedMs, const std::uint8_t* favorite, int count);

    /** Per-track 0..1 steering scores (energy target, mood), or null to clear. */
    void setSteering(const float* perTrack, int count, float weight);

    /** Markov habits: weights of "to after from" transitions, added to what is known. */
    void addTransitions(const std::int64_t* from, const std::int64_t* to, const float* weight, int count);
    void clearTransitions();
    /** Markov probability P(to | from) in [0, 1], shrunk by evidence. */
    float markovProbability(std::int64_t from, std::int64_t to) const;

    /** The contextual bandit, kZones x kZones pulls and rewards (row = context). */
    void setZoneBandit(const float* pulls, const float* rewards);
    void zoneBandit(float* pulls, float* rewards) const;

    void setWeights(const Weights& weights);
    const Weights& weights() const { return weights_; }

    // -- Session vibe vector -----------------------------------------------

    /** A song started playing. Seeds S_t if there is none yet. */
    SessionEvent onTrackStarted(std::int64_t id);

    /**
     * A song ended or was left after [fraction] of its length. Updates S_t,
     * the in-memory habits (Markov, bandit, affinity) and arms the skip audit.
     * [shufflePick] says whether Smart Shuffle had queued it (bandit reward).
     */
    SessionEvent onPlaybackOutcome(std::int64_t id, float fraction, std::int64_t nowMs, bool shufflePick);

    /** The listener picked this song by hand. */
    SessionEvent onManualSelect(std::int64_t id);

    void resetSession();
    bool hasSession() const { return hasSession_; }
    /** S_t, 24 floats (zeros before the first song). */
    const float* sessionVector() const { return session_.data(); }
    /** The last Markov transition learned (from, to, delta weight), for persistence. */
    bool lastLearnedTransition(std::int64_t* from, std::int64_t* to, float* delta) const;
    /** The bandit cell the last outcome rewarded (context, arm, reward), for persistence. */
    bool lastBanditUpdate(int* context, int* arm, float* reward) const;

    // -- Queue -------------------------------------------------------------

    /**
     * Builds [count] tracks to follow [currentId] and the already queued
     * [prefix]. [exclude] lists ids that must not be picked (recently played,
     * already queued). After a fast skip, [replacedTail] lists what was queued
     * before the re-rank, for Check 2. Returns the number written to [outIds];
     * lastTraces() and lastAudit() describe them.
     */
    int generateQueue(std::int64_t currentId,
                      const std::int64_t* prefix, int prefixCount,
                      const std::int64_t* exclude, int excludeCount,
                      const std::int64_t* replacedTail, int replacedCount,
                      int count, std::int64_t nowMs, std::uint64_t seed,
                      std::int64_t* outIds);

    /**
     * Sonic Adventure: the next song on a straight line through the vector
     * space from [fromId] to [toId], at position [t] in [0, 1] of the way,
     * following [previousId]. Returns the id, or -1.
     */
    std::int64_t journeyStep(std::int64_t fromId, std::int64_t toId, float t,
                             std::int64_t previousId,
                             const std::int64_t* exclude, int excludeCount,
                             std::int64_t nowMs, std::uint64_t seed);

    const std::vector<TrackDecisionTrace>& lastTraces() const { return traces_; }
    const BrainAuditReport& lastAudit() const { return audit_; }

    // -- Inspection ----------------------------------------------------------

    int size() const { return static_cast<int>(ids_.size()); }
    /** The working (standardized, unit) vector of a track. */
    bool vectorOf(std::int64_t id, float out[kDims]) const;
    float cosine(std::int64_t a, std::int64_t b) const;
    int zoneOfTrack(std::int64_t id) const;
    static int zoneOf(const float* v);
    /** "Bass", "Warm", "Vocal", "Bright" or "Air". */
    static const char* zoneWord(int zone);
    /** How many raw values were corrupt (Inf, absurd) and repaired at load. */
    int repairedInputValues() const { return repairedInputs_; }
    float exploreBoost() const { return exploreBoost_; }

#ifdef HARMONY_SHUFFLE_TESTING
    void poisonSessionForTest(float value) { session_.fill(value); hasSession_ = true; }
#endif

private:
    friend class ShuffleDiagnosticBrain;

    struct Candidate {
        int row = -1;
        float score = 0.0f;
        float vibe = 0.0f;
        float markov = 0.0f;
        float affinity = 0.0f;
        float explore = 0.0f;
        float cooldown = 1.0f;
        float cosine = 0.0f;
    };

    struct PendingSkip {
        bool active = false;
        std::int64_t id = -1;
        std::array<float, kDims> vector{};
        float dotBefore = 0.0f;
        float dotAfter = 0.0f;
        bool healed = false;
    };

    int rowOf(std::int64_t id) const;
    const float* vec(int row) const { return &vectors_[static_cast<size_t>(row) * kDims]; }
    static float dot(const float* a, const float* b);
    static float distance(const float* a, const float* b);
    static bool normalize(float* v);

    float affinityOf(int row) const;
    float explorationOf(int row, int context, std::int64_t nowMs) const;
    float cooldownOf(int row, std::int64_t nowMs) const;
    Candidate scoreRow(int row, const float* target, int context, float markovNorm, std::int64_t nowMs) const;
    void scoreLibrary(const float* target, std::int64_t anchorId, std::int64_t nowMs,
                      const std::vector<std::uint8_t>& blocked, std::vector<Candidate>& out) const;
    std::vector<int> reservoirSample(std::vector<Candidate>& pool, int count, std::uint64_t seed) const;
    std::vector<int> ditherAndChain(const std::vector<int>& prefixRows, const std::vector<int>& picked,
                                    std::uint64_t seed) const;
    void buildTraces(const ShuffleDiagnosticBrain::Draft& draft, bool journey, float journeyT);
    int contextZone() const;
    void rememberStart(std::int64_t id);

    // Library.
    std::vector<std::int64_t> ids_;
    std::vector<float> vectors_;
    std::vector<VectorQuality> quality_;
    std::vector<std::int32_t> artist_;
    std::vector<std::int32_t> album_;
    std::vector<std::int32_t> completed_;
    std::vector<std::int32_t> skipped_;
    std::vector<std::int64_t> lastPlayed_;
    std::vector<std::uint8_t> favorite_;
    std::vector<float> steering_;
    std::vector<std::int8_t> zone_;
    std::unordered_map<std::int64_t, int> rowById_;
    int repairedInputs_ = 0;
    int counts_[3] = {0, 0, 0};

    // Habits.
    std::unordered_map<std::int64_t, std::unordered_map<std::int64_t, float>> transitions_;
    std::unordered_map<std::int64_t, float> transitionTotals_;
    float banditPulls_[kZones][kZones] = {};
    float banditRewards_[kZones][kZones] = {};
    Weights weights_;
    float exploreBoost_ = 1.0f;

    // Session.
    std::array<float, kDims> session_{};
    /** Sum of the vectors of songs played past half this session. */
    std::array<float, kDims> likedSum_{};
    bool hasSession_ = false;
    std::deque<std::int64_t> started_;
    std::unordered_set<std::int64_t> sessionPlayed_;
    std::unordered_map<std::int64_t, std::int64_t> predecessor_;
    std::unordered_map<std::int64_t, int> pickedContext_;
    PendingSkip pendingSkip_;
    std::int64_t learnedFrom_ = -1, learnedTo_ = -1;
    float learnedDelta_ = 0.0f;
    int banditContext_ = -1, banditArm_ = -1;
    float banditReward_ = 0.0f;

    // Telemetry.
    std::vector<Candidate> lastCandidates_;
    std::unordered_map<int, Candidate> candidateByRow_;
    std::vector<TrackDecisionTrace> traces_;
    BrainAuditReport audit_;
    std::uint64_t generation_ = 0;

    ShuffleDiagnosticBrain brain_;
};

/**
 * The built-in self-test: 100 virtual tracks in five sonic families, five
 * bass-heavy plays, then two fast skips on bright treble tracks, plus the
 * echo-chamber, clumping and integrity drills. Returns the PASS/FAIL report;
 * [passed] (optional) receives the verdict.
 */
std::string runSyntheticBrainSimulation(bool* passed = nullptr);

}  // namespace harmony::shuffle

#endif  // HARMONY_SMART_SHUFFLE_ENGINE_H
