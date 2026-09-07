// distance_kernels.cpp — see distance_kernels.h for the API and the reasoning
// behind the two vectorisation axes.

#include "distance_kernels.h"

#include <cmath>
#include <cstdlib>
#include <cstring>
#include <new>

#if defined(_MSC_VER)
#include <malloc.h>
#endif

// Feature detection.
//
// __AVX2__ is only defined when the whole translation unit is compiled with
// -mavx2, which would let the compiler emit AVX2 into the *scalar* fallback
// too and fault on a machine without it. So the AVX2 kernels below carry a
// per-function target attribute instead, and dispatch happens at runtime.
// That way one TU, built for baseline x86-64, contains both paths.
#if defined(__x86_64__) || defined(_M_X64) || defined(__i386__) || defined(_M_IX86)
#define HARMONY_X86 1
#include <immintrin.h>
#else
#define HARMONY_X86 0
#endif

#if defined(__aarch64__) || defined(__ARM_NEON)
#define HARMONY_NEON 1
#include <arm_neon.h>
#else
#define HARMONY_NEON 0
#endif

#if HARMONY_X86 && (defined(__GNUC__) || defined(__clang__))
#define HARMONY_TARGET_AVX2 __attribute__((target("avx2,fma")))
#define HARMONY_HAVE_AVX2_PATH 1
#else
#define HARMONY_TARGET_AVX2
#define HARMONY_HAVE_AVX2_PATH 0
#endif

namespace harmony::simd {
namespace {

constexpr std::size_t kAlign = 32;

}  // namespace

// ---------------------------------------------------------------------------
// Runtime dispatch
// ---------------------------------------------------------------------------

bool avx2Available() {
#if HARMONY_HAVE_AVX2_PATH
    // Cached: __builtin_cpu_supports resolves through a constructor-initialised
    // table, but this is called per scoring pass and a static local is free
    // after the first hit.
    static const bool supported =
        __builtin_cpu_supports("avx2") && __builtin_cpu_supports("fma");
    return supported;
#else
    return false;
#endif
}

const char* activeKernel() {
#if HARMONY_NEON
    return "neon";
#else
    return avx2Available() ? "avx2+fma" : "scalar";
#endif
}

// ---------------------------------------------------------------------------
// Aligned storage
// ---------------------------------------------------------------------------

namespace {

float* allocAligned(std::size_t count) {
    if (count == 0) return nullptr;
    const std::size_t bytes = roundUp(count * sizeof(float), kAlign);
#if defined(_MSC_VER)
    return static_cast<float*>(_aligned_malloc(bytes, kAlign));
#else
    void* p = nullptr;
    // posix_memalign rather than std::aligned_alloc: the latter requires the
    // size to be a multiple of the alignment (rounded above, but easy to get
    // wrong) and only reached bionic at API 28.
    if (posix_memalign(&p, kAlign, bytes) != 0) return nullptr;
    return static_cast<float*>(p);
#endif
}

void freeAligned(float* p) {
    if (p == nullptr) return;
#if defined(_MSC_VER)
    _aligned_free(p);
#else
    std::free(p);
#endif
}

}  // namespace

AlignedFloatBuffer::AlignedFloatBuffer(std::size_t count) { resize(count); }

AlignedFloatBuffer::~AlignedFloatBuffer() { release(); }

AlignedFloatBuffer::AlignedFloatBuffer(AlignedFloatBuffer&& other) noexcept
    : data_(other.data_), size_(other.size_) {
    other.data_ = nullptr;
    other.size_ = 0;
}

AlignedFloatBuffer& AlignedFloatBuffer::operator=(AlignedFloatBuffer&& other) noexcept {
    if (this != &other) {
        release();
        data_ = other.data_;
        size_ = other.size_;
        other.data_ = nullptr;
        other.size_ = 0;
    }
    return *this;
}

void AlignedFloatBuffer::release() {
    freeAligned(data_);
    data_ = nullptr;
    size_ = 0;
}

void AlignedFloatBuffer::resize(std::size_t count) {
    release();
    if (count == 0) return;
    data_ = allocAligned(count);
    if (data_ == nullptr) throw std::bad_alloc();
    size_ = count;
}

void AlignedFloatBuffer::zero() {
    if (data_ != nullptr) std::memset(data_, 0, size_ * sizeof(float));
}

// ---------------------------------------------------------------------------
// Layouts
// ---------------------------------------------------------------------------

void RowMajorLibrary::allocate(std::size_t songCount, std::size_t dimension) {
    count = songCount;
    dim = dimension;
    // Pad the row, not just the buffer: every row start stays 32-byte aligned
    // and the padded lanes are zero in both operands, so no tail handling is
    // needed when scoring a padded row against a padded seed.
    rowStride = roundUp(dimension, 8);
    features.resize(songCount * rowStride);
    features.zero();
}

void RowMajorLibrary::setRow(std::size_t index, const float* values) {
    if (index >= count) return;
    float* dst = features.data() + index * rowStride;
    std::memcpy(dst, values, dim * sizeof(float));
    // Padding must stay zero; a stale value here would silently inflate every
    // distance involving this row.
    std::memset(dst + dim, 0, (rowStride - dim) * sizeof(float));
}

void SoALibrary::allocate(std::size_t songCount, std::size_t dimension) {
    count = songCount;
    dim = dimension;
    // Pad the *song* axis now, because that is the axis the batch kernel
    // strides along 8 lanes at a time.
    colStride = roundUp(songCount, 8);
    features.resize(colStride * dimension);
    features.zero();
}

void SoALibrary::setSong(std::size_t index, const float* values) {
    if (index >= count) return;
    for (std::size_t d = 0; d < dim; ++d) {
        features.data()[d * colStride + index] = values[d];
    }
}

void SoALibrary::fromRowMajor(const RowMajorLibrary& src) {
    allocate(src.count, src.dim);
    // Transpose. Done once per library load, so the cache-unfriendly access
    // pattern here buys a cache-friendly one on every subsequent query.
    for (std::size_t i = 0; i < src.count; ++i) {
        const float* r = src.row(i);
        for (std::size_t d = 0; d < src.dim; ++d) {
            features.data()[d * colStride + i] = r[d];
        }
    }
}

// ---------------------------------------------------------------------------
// Scalar reference
// ---------------------------------------------------------------------------

float euclideanDistanceSquaredScalar(const float* a, const float* b, std::size_t dim,
                                     const float* weights) {
    float sum = 0.0f;
    for (std::size_t i = 0; i < dim; ++i) {
        const float d = a[i] - b[i];
        sum += (weights ? weights[i] : 1.0f) * d * d;
    }
    return sum;
}

// ---------------------------------------------------------------------------
// AVX2 + FMA
// ---------------------------------------------------------------------------

#if HARMONY_HAVE_AVX2_PATH

/// Horizontal sum of a 256-bit register.
///
/// Deliberately not _mm256_hadd_ps: hadd is 3 uops on both Intel and AMD and
/// has to run twice, and it shuffles across the lane boundary in a way that
/// costs extra latency. Folding the high 128 onto the low 128 first and then
/// doing two in-lane shuffles is the standard minimum-latency form — roughly
/// 4 uops total instead of 7, and it is the shape every compiler recognises.
HARMONY_TARGET_AVX2
static inline float horizontalSum256(__m256 v) {
    __m128 lo = _mm256_castps256_ps128(v);          // free: just a register view
    __m128 hi = _mm256_extractf128_ps(v, 1);
    lo = _mm_add_ps(lo, hi);                        // 4 partial sums
    __m128 shuf = _mm_movehdup_ps(lo);              // [1,1,3,3] — no shuffle port
    __m128 sums = _mm_add_ps(lo, shuf);             // [0+1, -, 2+3, -]
    shuf = _mm_movehl_ps(shuf, sums);               // bring [2+3] down
    sums = _mm_add_ss(sums, shuf);
    return _mm_cvtss_f32(sums);
}

HARMONY_TARGET_AVX2
static float distanceSquaredAvx2(const float* a, const float* b,
                                 std::size_t dim, const float* w) {
    // Two accumulators. An FMA has ~4 cycle latency but 0.5 cycle throughput,
    // so a single dependent accumulator chain would stall on itself and run at
    // a quarter of the achievable rate. Two independent chains cover most of
    // it; four would cover all of it but only pays off past dim ~64.
    __m256 acc0 = _mm256_setzero_ps();
    __m256 acc1 = _mm256_setzero_ps();

    std::size_t i = 0;
    for (; i + 16 <= dim; i += 16) {
        __m256 d0 = _mm256_sub_ps(_mm256_loadu_ps(a + i), _mm256_loadu_ps(b + i));
        __m256 d1 = _mm256_sub_ps(_mm256_loadu_ps(a + i + 8), _mm256_loadu_ps(b + i + 8));
        if (w != nullptr) {
            // acc += (w * d) * d, which is w*d^2 with one rounding instead of two.
            acc0 = _mm256_fmadd_ps(_mm256_mul_ps(d0, _mm256_loadu_ps(w + i)), d0, acc0);
            acc1 = _mm256_fmadd_ps(_mm256_mul_ps(d1, _mm256_loadu_ps(w + i + 8)), d1, acc1);
        } else {
            acc0 = _mm256_fmadd_ps(d0, d0, acc0);
            acc1 = _mm256_fmadd_ps(d1, d1, acc1);
        }
    }
    for (; i + 8 <= dim; i += 8) {
        __m256 d0 = _mm256_sub_ps(_mm256_loadu_ps(a + i), _mm256_loadu_ps(b + i));
        if (w != nullptr) {
            acc0 = _mm256_fmadd_ps(_mm256_mul_ps(d0, _mm256_loadu_ps(w + i)), d0, acc0);
        } else {
            acc0 = _mm256_fmadd_ps(d0, d0, acc0);
        }
    }

    float sum = horizontalSum256(_mm256_add_ps(acc0, acc1));

    // Scalar cleanup for dim % 8. With the padded layouts above this never
    // runs; it is here so the function is safe on arbitrary caller data.
    for (; i < dim; ++i) {
        const float d = a[i] - b[i];
        sum += (w ? w[i] : 1.0f) * d * d;
    }
    return sum;
}

HARMONY_TARGET_AVX2
static void batchSquaredAvx2(const float* seed, const SoALibrary& lib,
                             const float* w, std::size_t first,
                             std::size_t last, float* out) {
    const std::size_t stride = lib.colStride;
    const float* base = lib.features.data();

    // Across candidates: lane j holds candidate i+j. Every lane accumulates
    // its own sum, so the horizontal reduction disappears entirely — that is
    // the whole reason this form wins at small dim.
    for (std::size_t i = first; i < last; i += 8) {
        __m256 acc = _mm256_setzero_ps();
        for (std::size_t d = 0; d < lib.dim; ++d) {
            // Aligned: colStride is a multiple of 8 and the buffer is 32-byte
            // aligned, so &base[d*stride + i] is always 32-byte aligned.
            const __m256 cand = _mm256_load_ps(base + d * stride + i);
            const __m256 s = _mm256_set1_ps(seed[d]);
            const __m256 delta = _mm256_sub_ps(cand, s);
            if (w != nullptr) {
                const __m256 wd = _mm256_mul_ps(delta, _mm256_set1_ps(w[d]));
                acc = _mm256_fmadd_ps(wd, delta, acc);
            } else {
                acc = _mm256_fmadd_ps(delta, delta, acc);
            }
        }
        _mm256_storeu_ps(out + (i - first), acc);
    }
}

#endif  // HARMONY_HAVE_AVX2_PATH

// ---------------------------------------------------------------------------
// NEON (aarch64)
// ---------------------------------------------------------------------------
//
// This is the path that runs on the phone. arm64-v8a is Harmony's only
// non-emulator ABI, and AVX2 does nothing there — NEON is mandatory on
// aarch64, so there is no runtime check and no fallback needed.

#if HARMONY_NEON

static inline float distanceSquaredNeon(const float* a, const float* b,
                                        std::size_t dim, const float* w) {
    float32x4_t acc0 = vdupq_n_f32(0.0f);
    float32x4_t acc1 = vdupq_n_f32(0.0f);

    std::size_t i = 0;
    for (; i + 8 <= dim; i += 8) {
        const float32x4_t d0 = vsubq_f32(vld1q_f32(a + i), vld1q_f32(b + i));
        const float32x4_t d1 = vsubq_f32(vld1q_f32(a + i + 4), vld1q_f32(b + i + 4));
        if (w != nullptr) {
            // acc += (w * d) * d. Keep the raw delta rather than reloading it.
            acc0 = vfmaq_f32(acc0, vmulq_f32(d0, vld1q_f32(w + i)), d0);
            acc1 = vfmaq_f32(acc1, vmulq_f32(d1, vld1q_f32(w + i + 4)), d1);
        } else {
            acc0 = vfmaq_f32(acc0, d0, d0);
            acc1 = vfmaq_f32(acc1, d1, d1);
        }
    }
    for (; i + 4 <= dim; i += 4) {
        const float32x4_t raw = vsubq_f32(vld1q_f32(a + i), vld1q_f32(b + i));
        if (w != nullptr) {
            acc0 = vfmaq_f32(acc0, vmulq_f32(raw, vld1q_f32(w + i)), raw);
        } else {
            acc0 = vfmaq_f32(acc0, raw, raw);
        }
    }

    // vaddvq_f32 is a single aarch64 instruction — the horizontal reduction
    // that costs five ops on AVX2 costs one here.
    float sum = vaddvq_f32(vaddq_f32(acc0, acc1));

    for (; i < dim; ++i) {
        const float d = a[i] - b[i];
        sum += (w ? w[i] : 1.0f) * d * d;
    }
    return sum;
}

static void batchSquaredNeon(const float* seed, const SoALibrary& lib,
                             const float* w, std::size_t first,
                             std::size_t last, float* out) {
    const std::size_t stride = lib.colStride;
    const float* base = lib.features.data();
    for (std::size_t i = first; i < last; i += 4) {
        float32x4_t acc = vdupq_n_f32(0.0f);
        for (std::size_t d = 0; d < lib.dim; ++d) {
            const float32x4_t cand = vld1q_f32(base + d * stride + i);
            const float32x4_t delta = vsubq_f32(cand, vdupq_n_f32(seed[d]));
            if (w != nullptr) {
                acc = vfmaq_f32(acc, vmulq_f32(delta, vdupq_n_f32(w[d])), delta);
            } else {
                acc = vfmaq_f32(acc, delta, delta);
            }
        }
        vst1q_f32(out + (i - first), acc);
    }
}

#endif  // HARMONY_NEON

// ---------------------------------------------------------------------------
// Public entry points
// ---------------------------------------------------------------------------

float euclideanDistanceSquared(const float* a, const float* b, std::size_t dim,
                               const float* weights) {
#if HARMONY_NEON
    return distanceSquaredNeon(a, b, dim, weights);
#elif HARMONY_HAVE_AVX2_PATH
    if (avx2Available()) return distanceSquaredAvx2(a, b, dim, weights);
    return euclideanDistanceSquaredScalar(a, b, dim, weights);
#else
    return euclideanDistanceSquaredScalar(a, b, dim, weights);
#endif
}

float euclideanDistance(const float* a, const float* b, std::size_t dim,
                        const float* weights) {
    return std::sqrt(euclideanDistanceSquared(a, b, dim, weights));
}

void euclideanDistanceSquaredRange(const float* seed, const SoALibrary& lib,
                                   const float* weights, std::size_t first,
                                   std::size_t last, float* out) {
    if (lib.dim == 0 || first >= last) return;
    if (last > lib.colStride) last = lib.colStride;
#if HARMONY_NEON
    batchSquaredNeon(seed, lib, weights, first, last, out);
#elif HARMONY_HAVE_AVX2_PATH
    if (avx2Available()) {
        batchSquaredAvx2(seed, lib, weights, first, last, out);
        return;
    }
    for (std::size_t i = first; i < last; ++i) {
        float sum = 0.0f;
        for (std::size_t d = 0; d < lib.dim; ++d) {
            const float delta = lib.features.data()[d * lib.colStride + i] - seed[d];
            sum += (weights ? weights[d] : 1.0f) * delta * delta;
        }
        out[i - first] = sum;
    }
#else
    for (std::size_t i = first; i < last; ++i) {
        float sum = 0.0f;
        for (std::size_t d = 0; d < lib.dim; ++d) {
            const float delta = lib.features.data()[d * lib.colStride + i] - seed[d];
            sum += (weights ? weights[d] : 1.0f) * delta * delta;
        }
        out[i - first] = sum;
    }
#endif
}

void euclideanDistanceSquaredBatch(const float* seed, const SoALibrary& lib,
                                   const float* weights, float* out) {
    if (lib.count == 0) return;
    euclideanDistanceSquaredRange(seed, lib, weights, 0, lib.colStride, out);
}

void euclideanDistanceBatch(const float* seed, const SoALibrary& lib,
                            const float* weights, float* out) {
    euclideanDistanceSquaredBatch(seed, lib, weights, out);
    // sqrt over the real count only; padding lanes are documented garbage.
    for (std::size_t i = 0; i < lib.count; ++i) out[i] = std::sqrt(out[i]);
}

}  // namespace harmony::simd
