// distance_kernels.h — SIMD Euclidean distance for the recommendation engine.
//
// Three implementations behind one API:
//   * AVX2 + FMA   (x86-64 with runtime dispatch)
//   * NEON         (aarch64, always available, no dispatch needed)
//   * scalar       (portable, and what the compiler auto-vectorises anyway)
//
// And two axes of vectorisation, which is the part that actually matters:
//
//   euclideanDistance()      vectorises ACROSS DIMENSIONS. One pair, one
//                            distance. Needs a horizontal reduction at the
//                            end. Good when dim is large (>= 32).
//
//   euclideanDistanceBatch() vectorises ACROSS CANDIDATES. One seed against
//                            N candidates, 8 distances per iteration, each
//                            SIMD lane holding a different candidate. No
//                            horizontal reduction at all.
//
// For Harmony's 5-dimensional feature vectors the second form is the one
// worth having: a 5-wide loop cannot fill a single 8-wide register, so the
// first form spends most of its time on reduction overhead for a body that
// does one masked FMA. See the benchmark in distance_kernels_test.cpp.

#ifndef HARMONY_DISTANCE_KERNELS_H
#define HARMONY_DISTANCE_KERNELS_H

#include <cstddef>
#include <cstdint>

namespace harmony::simd {

/// SIMD width in floats for the widest available kernel on this build.
#if defined(__aarch64__) || defined(__ARM_NEON)
inline constexpr std::size_t kLanes = 4;   // NEON: 128-bit
#else
inline constexpr std::size_t kLanes = 8;   // AVX2: 256-bit
#endif

/// True when the AVX2+FMA kernels will actually be used at runtime.
/// Always false on non-x86 builds. Result is cached after the first call.
bool avx2Available();

/// Name of the kernel that will service calls on this machine, for logging:
/// "avx2+fma", "neon", or "scalar".
const char* activeKernel();

// ---------------------------------------------------------------------------
// Aligned storage
// ---------------------------------------------------------------------------

/// 32-byte aligned float buffer. 32 bytes is the AVX2 register width; NEON
/// only needs 16, but over-aligning costs nothing and lets one layout serve
/// both targets.
///
/// Aligned loads (_mm256_load_ps) versus unaligned (_mm256_loadu_ps) are the
/// same speed on every core since Nehalem *when the address is actually
/// aligned* — the win from alignment is avoiding cache-line splits, not the
/// instruction choice. A 32-byte-aligned, padded row can never straddle a
/// 64-byte line boundary mid-vector.
class AlignedFloatBuffer {
public:
    AlignedFloatBuffer() = default;
    explicit AlignedFloatBuffer(std::size_t count);
    ~AlignedFloatBuffer();

    AlignedFloatBuffer(const AlignedFloatBuffer&) = delete;
    AlignedFloatBuffer& operator=(const AlignedFloatBuffer&) = delete;
    AlignedFloatBuffer(AlignedFloatBuffer&& other) noexcept;
    AlignedFloatBuffer& operator=(AlignedFloatBuffer&& other) noexcept;

    void resize(std::size_t count);   ///< reallocates; contents undefined after
    void zero();

    [[nodiscard]] float* data() { return data_; }
    [[nodiscard]] const float* data() const { return data_; }
    [[nodiscard]] std::size_t size() const { return size_; }
    [[nodiscard]] bool empty() const { return size_ == 0; }

private:
    void release();
    float* data_ = nullptr;
    std::size_t size_ = 0;
};

/// Rounds `n` up to the next multiple of `multiple`.
inline std::size_t roundUp(std::size_t n, std::size_t multiple) {
    return ((n + multiple - 1) / multiple) * multiple;
}

// ---------------------------------------------------------------------------
// Library layouts
// ---------------------------------------------------------------------------

/// Row-major: one contiguous, zero-padded row per song.
///
/// Layout:  [song0 f0..f4 pad pad pad][song1 f0..f4 pad pad pad]...
///          |<-------- rowStride --------->|
///
/// Each row is padded to a multiple of 8 floats and each row start is
/// 32-byte aligned, which is what removes the scalar tail from the hot loop:
/// padding is zero in both operands, so the padded lanes contribute
/// (0 - 0)^2 = 0 to the sum and need no masking.
///
/// This is the layout to use when you score one candidate at a time, or when
/// dim is large. Note it is strictly *array-of-structures*, flattened —
/// "SoA" in the loose sense of "one flat float matrix", but not feature-major.
struct RowMajorLibrary {
    AlignedFloatBuffer features;
    std::size_t count = 0;       ///< number of songs
    std::size_t dim = 0;         ///< real feature count
    std::size_t rowStride = 0;   ///< padded floats per row (multiple of 8)

    void allocate(std::size_t songCount, std::size_t dimension);

    /// Copies one song's features into row `index`, zeroing the padding.
    void setRow(std::size_t index, const float* values);

    [[nodiscard]] const float* row(std::size_t index) const {
        return features.data() + index * rowStride;
    }
};

/// Feature-major (true Structure-of-Arrays): all songs' feature 0, then all
/// songs' feature 1, and so on.
///
/// Layout:  [f0: song0..songN][f1: song0..songN]...
///          |<--- colStride --->|
///
/// This is the layout the batch kernel wants. Loading 8 candidates' feature d
/// is one contiguous aligned 256-bit load, the seed's feature d is a
/// broadcast, and each lane accumulates its own candidate's squared distance
/// independently — so there is no horizontal reduction anywhere in the loop.
///
/// For dim = 5 this is roughly the whole optimisation.
struct SoALibrary {
    AlignedFloatBuffer features;
    std::size_t count = 0;       ///< number of songs
    std::size_t dim = 0;
    std::size_t colStride = 0;   ///< padded songs per feature (multiple of 8)

    void allocate(std::size_t songCount, std::size_t dimension);

    /// Copies one song's features into column `index`.
    void setSong(std::size_t index, const float* values);

    /// Builds this layout from a row-major one (transpose).
    void fromRowMajor(const RowMajorLibrary& src);

    [[nodiscard]] const float* feature(std::size_t d) const {
        return features.data() + d * colStride;
    }
};

// ---------------------------------------------------------------------------
// Kernels
// ---------------------------------------------------------------------------

/// Euclidean distance between two vectors of `dim` floats.
/// `weights` may be null (uniform); when present it multiplies the squared
/// term, so a weight of 4 doubles that dimension's contribution to the result.
///
/// Handles any `dim`: full 8-wide (or 4-wide on NEON) blocks, then a scalar
/// cleanup loop for the remainder. Safe on unaligned and unpadded input.
float euclideanDistance(const float* a, const float* b, std::size_t dim,
                        const float* weights = nullptr);

/// Squared distance — same thing without the final sqrt. Prefer this when you
/// only need to *rank* candidates, since sqrt is monotonic: rank on squares,
/// then take the root of the few winners.
float euclideanDistanceSquared(const float* a, const float* b, std::size_t dim,
                               const float* weights = nullptr);

/// Squared distances for the candidate sub-range [first, last), written to
/// `out[0 .. last-first)`.
///
/// `first` MUST be a multiple of 8 (4 on NEON) or the aligned loads inside
/// break. This is the entry point the OpenMP search uses: each thread owns a
/// tile of candidates, and tiling on a SIMD-width boundary is what lets the
/// kernel keep using aligned loads after the work is split across threads.
void euclideanDistanceSquaredRange(const float* seed, const SoALibrary& lib,
                                   const float* weights, std::size_t first,
                                   std::size_t last, float* out);

/// One seed against every song in `lib`, writing `lib.count` distances to
/// `out`. `out` must have room for at least `lib.colStride` floats — the
/// kernel writes whole SIMD registers and may touch padding lanes past
/// `count`. Those values are meaningless; do not read them.
///
/// This is the function to call from the selector's scoring loop.
void euclideanDistanceBatch(const float* seed, const SoALibrary& lib,
                            const float* weights, float* out);

/// Same, squared. Cheaper: skips `count` square roots.
void euclideanDistanceSquaredBatch(const float* seed, const SoALibrary& lib,
                                   const float* weights, float* out);

/// Reference implementation, returning the SQUARED distance (no sqrt, to
/// match euclideanDistanceSquared). Exposed so tests can diff SIMD against it.
///
/// Expect the last ulp or two to differ: FMA does not round between the
/// multiply and the add, and the SIMD versions sum in a different order.
/// That is the SIMD path being *more* accurate, not less — but it does mean
/// exact float equality is the wrong assertion in a test.
float euclideanDistanceSquaredScalar(const float* a, const float* b, std::size_t dim,
                                     const float* weights);

}  // namespace harmony::simd

#endif  // HARMONY_DISTANCE_KERNELS_H
