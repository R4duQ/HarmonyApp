// topk_search.h — OpenMP-parallel top-K nearest-neighbour search.
//
// Sits on top of the SIMD distance kernels: each thread owns a tile of
// candidates, scores it with the batch kernel, and keeps its own top-K in
// thread-local storage. Nothing is shared inside the hot loop, so there is no
// lock, no atomic and no false sharing on the critical path. The per-thread
// results are merged once, after the loop.
//
// READ THIS BEFORE ENABLING IT ON ANDROID
// ---------------------------------------
// Two things decide whether this is worth using, and neither is about the
// code:
//
//  1. Problem size. With the batch kernel at ~0.4 ns/track, a 100k library at
//     dim=5 is ~40 us of work. An OpenMP parallel region costs single-digit
//     microseconds to wake and join on a warm pool, and considerably more on
//     a cold one or when the scheduler has parked the big cores. Parallelism
//     pays here only once the serial time is comfortably above the region
//     overhead — measure `benchParallelRegionOverhead()` on the target device
//     and compare, rather than assuming.
//
//  2. Core heterogeneity. schedule(static) hands every thread an equal number
//     of tiles. That is correct on a homogeneous server and wrong on a phone:
//     an 8-core Snapdragon is 1 prime + 3 performance + 4 efficiency cores
//     that can differ by 2-3x in throughput, so equal tile counts mean the
//     fast cores finish early and idle at the barrier while the little cores
//     grind. Schedule::Dynamic (or Guided) lets fast cores steal work and is
//     usually the right default on ARM. Static is kept because it is what
//     homogeneous x86 wants, and it is measurably cheaper when it fits.
//
// Determinism: results are independent of thread count and schedule. Scores
// tie-break on candidate index, and the merge is a full sort, so the same
// library and seed always produce the same ordered top-K. That matters
// because the shuffle selector's reproducibility depends on it.

#ifndef HARMONY_TOPK_SEARCH_H
#define HARMONY_TOPK_SEARCH_H

#include <cstddef>
#include <cstdint>

#include "distance_kernels.h"

namespace harmony::search {

/// One candidate and its (squared) distance to the seed.
struct Match {
    float score = 0.0f;        ///< squared distance; monotonic in distance
    std::int32_t index = -1;   ///< candidate index in the library
};

/// Strict "a is a better match than b", with a deterministic tie-break.
inline bool betterMatch(const Match& a, const Match& b) {
    if (a.score != b.score) return a.score < b.score;
    return a.index < b.index;
}

/// OpenMP loop schedule for the tile loop.
enum class Schedule {
    Static,   ///< equal tiles per thread. Cheapest; assumes equal cores.
    Dynamic,  ///< work-stealing. Costs a little sync; wins on big.LITTLE.
    Guided,   ///< large chunks first, shrinking. Middle ground.
};

/// Which thread-local top-K container to use.
enum class TopKContainer {
    /// std::priority_queue max-heap. O(log k) per accepted candidate.
    /// MEASURED FASTER at k=5 (~8-9%) despite the theory below, so it is the
    /// default. At k=5 a sift touches 2-3 levels of a 5-element vector that
    /// never leaves L1, and libstdc++'s push/pop are extremely well optimised.
    Heap,
    /// Fixed sorted array, insertion by backwards shift. O(k) worst case per
    /// accepted candidate but O(1) to reject.
    ///
    /// The intuition that this beats a heap for tiny k did NOT survive
    /// measurement here — it lost by ~8-9% at k=5 on 100k and 1M tracks. Kept
    /// because it has no allocation at all once constructed, which may matter
    /// more on a phone than the throughput difference does, and because the
    /// gap may invert on ARM. Benchmark on the target before choosing.
    SortedArray,
};

struct SearchOptions {
    /// 0 means "use omp_get_max_threads()".
    int threads = 0;
    Schedule schedule = Schedule::Static;
    TopKContainer container = TopKContainer::Heap;  // measured fastest at k=5
    /// Candidates per tile. Must be a multiple of 8 so the aligned SIMD loads
    /// survive the split. Large enough to amortise loop overhead, small enough
    /// that the distance scratch buffer stays in L1.
    std::size_t tile = 1024;
};

/// Parallel top-K search.
///
/// Writes up to `k` matches to `out` in ascending score order (best first)
/// and returns how many were written, which is min(k, lib.count).
///
/// `out` must have room for `k` matches. `weights` may be null.
/// Thread-safe with respect to `lib` and `seed`, which are read-only.
std::size_t findTopKParallel(const simd::SoALibrary& lib, const float* seed,
                             const float* weights, std::size_t k, Match* out,
                             const SearchOptions& options = {});

/// Single-threaded reference. Same results, same order — used to prove the
/// parallel version agrees regardless of thread count.
std::size_t findTopKSerial(const simd::SoALibrary& lib, const float* seed,
                           const float* weights, std::size_t k, Match* out,
                           TopKContainer container = TopKContainer::Heap);

/// True when this build actually has OpenMP. False means every entry point
/// silently runs serial, which is correct but not parallel.
bool openMpEnabled();

/// Threads this build would use by default.
int defaultThreadCount();

/// Measures the cost of entering and leaving an empty parallel region, in
/// nanoseconds, averaged over `iterations`.
///
/// This is the number to compare against your serial search time before
/// deciding parallelism is worth it. On a device where this comes back at
/// 30us, a 40us search will not get meaningfully faster.
double benchParallelRegionOverhead(int threads, int iterations = 2000);

}  // namespace harmony::search

#endif  // HARMONY_TOPK_SEARCH_H
