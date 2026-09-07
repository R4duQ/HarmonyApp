// topk_search.cpp — see topk_search.h.

#include "topk_search.h"

#include <algorithm>
#include <chrono>
#include <queue>
#include <vector>

#ifdef _OPENMP
#include <omp.h>
#endif

namespace harmony::search {
namespace {

// ---------------------------------------------------------------------------
// Thread-local top-K containers
// ---------------------------------------------------------------------------

/// Max-heap on "worst match", so top() is the weakest of the current k and is
/// the one to evict. Note the comparator is the REVERSE of betterMatch:
/// std::priority_queue puts the element for which comp(x, top) is false at the
/// top, so a "less-good-first" comparator yields a max-heap on badness.
struct WorseFirst {
    bool operator()(const Match& a, const Match& b) const {
        return betterMatch(a, b);
    }
};

class HeapTopK {
public:
    explicit HeapTopK(std::size_t k) : k_(k) {}

    void offer(float score, std::int32_t index) {
        const Match m{score, index};
        if (heap_.size() < k_) {
            heap_.push(m);
        } else if (betterMatch(m, heap_.top())) {
            // One pop + one push rather than push-then-pop: keeps the heap at
            // exactly k and avoids a transient realloc.
            heap_.pop();
            heap_.push(m);
        }
    }

    void drainInto(std::vector<Match>& out) {
        while (!heap_.empty()) {
            out.push_back(heap_.top());
            heap_.pop();
        }
    }

private:
    std::size_t k_;
    std::priority_queue<Match, std::vector<Match>, WorseFirst> heap_;
};

/// Sorted-array top-K, best first.
///
/// For small k this beats the heap because the common case is rejection, and
/// rejection here is a single compare against the last element with no branch
/// misprediction and no pointer chasing. The heap pays a log-k sift and its
/// backing vector is a separate allocation.
class SortedTopK {
public:
    explicit SortedTopK(std::size_t k) : k_(k), buf_(k) {}

    void offer(float score, std::int32_t index) {
        const Match m{score, index};
        // Fast reject against the current worst. After warm-up this is taken
        // for almost every candidate, so it is the branch that decides the
        // loop's cost. Checked before anything else touches memory.
        if (n_ == k_ && !betterMatch(m, buf_[k_ - 1])) return;

        // Raw shift loop rather than upper_bound + vector::insert. At k=5 a
        // binary search is pure overhead against a handful of compares, and
        // insert() carries iterator and capacity bookkeeping that a plain
        // backwards shift does not.
        std::size_t pos = (n_ < k_) ? n_++ : k_ - 1;
        while (pos > 0 && betterMatch(m, buf_[pos - 1])) {
            buf_[pos] = buf_[pos - 1];
            --pos;
        }
        buf_[pos] = m;
    }

    void drainInto(std::vector<Match>& out) {
        out.insert(out.end(), buf_.begin(), buf_.begin() + n_);
        n_ = 0;
    }

private:
    std::size_t k_;
    std::size_t n_ = 0;      ///< how many of buf_ are live
    std::vector<Match> buf_; ///< always sized k_; [0, n_) is sorted, best first
};

/// Scores one tile and offers every real candidate to a thread-local top-K.
template <typename TopK>
void processTile(const simd::SoALibrary& lib, const float* seed,
                 const float* weights, std::size_t first, std::size_t last,
                 float* scratch, TopK& local) {
    simd::euclideanDistanceSquaredRange(seed, lib, weights, first, last, scratch);
    // Stop at lib.count, not at `last`: the SoA layout is padded up to
    // colStride and those trailing lanes hold zeros, which would otherwise
    // look like perfect matches at distance 0 and poison the result.
    const std::size_t realEnd = std::min(last, lib.count);
    for (std::size_t i = first; i < realEnd; ++i) {
        local.offer(scratch[i - first], static_cast<std::int32_t>(i));
    }
}

/// Merges per-thread partial results into the final ordered top-K.
///
/// Runs on the master thread after the parallel region. The input is at most
/// threads*k matches — 8 threads at k=5 is 40 elements — so a partial_sort is
/// far cheaper than a k-way merge and much easier to get right.
std::size_t reducePartials(std::vector<Match>& all, std::size_t k, Match* out) {
    const std::size_t n = std::min(k, all.size());
    if (n == 0) return 0;
    std::partial_sort(all.begin(), all.begin() + n, all.end(), betterMatch);
    std::copy(all.begin(), all.begin() + n, out);
    return n;
}

template <typename TopK>
std::size_t searchSerialImpl(const simd::SoALibrary& lib, const float* seed,
                             const float* weights, std::size_t k, Match* out,
                             std::size_t tile) {
    TopK local(k);
    std::vector<float> scratch(tile);
    for (std::size_t first = 0; first < lib.colStride; first += tile) {
        const std::size_t last = std::min(first + tile, lib.colStride);
        processTile(lib, seed, weights, first, last, scratch.data(), local);
    }
    std::vector<Match> all;
    all.reserve(k);
    local.drainInto(all);
    return reducePartials(all, k, out);
}

}  // namespace

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

bool openMpEnabled() {
#ifdef _OPENMP
    return true;
#else
    return false;
#endif
}

int defaultThreadCount() {
#ifdef _OPENMP
    return omp_get_max_threads();
#else
    return 1;
#endif
}

std::size_t findTopKSerial(const simd::SoALibrary& lib, const float* seed,
                           const float* weights, std::size_t k, Match* out,
                           TopKContainer container) {
    if (k == 0 || lib.count == 0 || lib.dim == 0) return 0;
    constexpr std::size_t kTile = 1024;
    return container == TopKContainer::Heap
               ? searchSerialImpl<HeapTopK>(lib, seed, weights, k, out, kTile)
               : searchSerialImpl<SortedTopK>(lib, seed, weights, k, out, kTile);
}

namespace {

template <typename TopK>
std::size_t searchParallelImpl(const simd::SoALibrary& lib, const float* seed,
                               const float* weights, std::size_t k, Match* out,
                               const SearchOptions& opt) {
    // Tile must be a SIMD-width multiple or the aligned loads in the ranged
    // kernel break once work is split.
    std::size_t tile = simd::roundUp(opt.tile == 0 ? 1024 : opt.tile, 8);

    const std::size_t total = lib.colStride;
    const long tiles = static_cast<long>((total + tile - 1) / tile);

#ifdef _OPENMP
    int threads = opt.threads > 0 ? opt.threads : omp_get_max_threads();
    if (threads < 1) threads = 1;
    // Never spawn more threads than there are tiles; the extras would only
    // pay the barrier.
    if (static_cast<long>(threads) > tiles) threads = static_cast<int>(tiles);

    // One result slot per thread, written by exactly one thread. This is the
    // "lock-free" part: no critical section, no atomic, no shared heap.
    //
    // Each slot is a separate std::vector, so the per-thread state that gets
    // written in the loop lives in that thread's own heap allocation rather
    // than in adjacent bytes of one array — which is what would otherwise put
    // two threads' writes on the same cache line and cause false sharing.
    std::vector<std::vector<Match>> perThread(static_cast<std::size_t>(threads));

#pragma omp parallel num_threads(threads)
    {
        const int tid = omp_get_thread_num();
        TopK local(k);
        std::vector<float> scratch(tile);

        // Split from `parallel for` deliberately: the thread-local top-K has
        // to survive across iterations of the tile loop. With the combined
        // construct it would be reconstructed per iteration and requirement 2
        // would be defeated. `nowait` because the merge happens after the
        // region, so there is nothing to wait for at the loop's end.
        switch (opt.schedule) {
            case Schedule::Static:
#pragma omp for schedule(static) nowait
                for (long t = 0; t < tiles; ++t) {
                    const std::size_t first = static_cast<std::size_t>(t) * tile;
                    processTile(lib, seed, weights, first,
                                std::min(first + tile, total), scratch.data(), local);
                }
                break;
            case Schedule::Dynamic:
#pragma omp for schedule(dynamic, 1) nowait
                for (long t = 0; t < tiles; ++t) {
                    const std::size_t first = static_cast<std::size_t>(t) * tile;
                    processTile(lib, seed, weights, first,
                                std::min(first + tile, total), scratch.data(), local);
                }
                break;
            case Schedule::Guided:
#pragma omp for schedule(guided) nowait
                for (long t = 0; t < tiles; ++t) {
                    const std::size_t first = static_cast<std::size_t>(t) * tile;
                    processTile(lib, seed, weights, first,
                                std::min(first + tile, total), scratch.data(), local);
                }
                break;
        }

        auto& slot = perThread[static_cast<std::size_t>(tid)];
        slot.reserve(k);
        local.drainInto(slot);
    }  // implicit barrier here; every slot is complete past this point

    // Master reduction.
    std::vector<Match> all;
    all.reserve(static_cast<std::size_t>(threads) * k);
    for (auto& slot : perThread) all.insert(all.end(), slot.begin(), slot.end());
    return reducePartials(all, k, out);
#else
    (void)opt;
    (void)tiles;
    return searchSerialImpl<TopK>(lib, seed, weights, k, out, tile);
#endif
}

}  // namespace

std::size_t findTopKParallel(const simd::SoALibrary& lib, const float* seed,
                             const float* weights, std::size_t k, Match* out,
                             const SearchOptions& options) {
    if (k == 0 || lib.count == 0 || lib.dim == 0) return 0;
    return options.container == TopKContainer::Heap
               ? searchParallelImpl<HeapTopK>(lib, seed, weights, k, out, options)
               : searchParallelImpl<SortedTopK>(lib, seed, weights, k, out, options);
}

double benchParallelRegionOverhead(int threads, int iterations) {
#ifdef _OPENMP
    if (threads <= 0) threads = omp_get_max_threads();
    volatile int sink = 0;
    // Warm the pool first: the very first region pays thread creation, which
    // would otherwise dominate the average and flatter later measurements.
#pragma omp parallel num_threads(threads)
    { sink += omp_get_thread_num(); }

    const auto t0 = std::chrono::high_resolution_clock::now();
    for (int i = 0; i < iterations; ++i) {
#pragma omp parallel num_threads(threads)
        { sink += omp_get_thread_num(); }
    }
    const auto t1 = std::chrono::high_resolution_clock::now();
    (void)sink;
    return std::chrono::duration<double, std::nano>(t1 - t0).count() / iterations;
#else
    (void)threads;
    (void)iterations;
    return 0.0;
#endif
}

}  // namespace harmony::search
