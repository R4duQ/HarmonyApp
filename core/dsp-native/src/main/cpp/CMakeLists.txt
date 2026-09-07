cmake_minimum_required(VERSION 3.22.1)
project(harmonydsp CXX)

add_library(harmonydsp SHARED
    harmony_dsp.cpp
    jni_bridge.cpp
)

target_compile_options(harmonydsp PRIVATE
    -O3
    -ffast-math          # DSP-safe here: no NaN/Inf-dependent logic in the pipeline
    -fvisibility=hidden
    -Wall -Wextra
)

# 16 KB page alignment: required for Android 15+ device compatibility.
target_link_options(harmonydsp PRIVATE "-Wl,-z,max-page-size=16384")

find_library(log-lib log)
target_link_libraries(harmonydsp ${log-lib})

# ---------------------------------------------------------------------------
# harmonyshuffle: Smart Shuffle candidate selector.
#
# A SEPARATE target, not extra sources on harmonydsp. harmonydsp is built with
# -ffast-math, under which the compiler may assume NaN/Inf cannot occur and
# fold the corrupt-vector guards in the selector to a constant false. The
# comment above ("no NaN/Inf-dependent logic in the pipeline") is true of the
# DSP path and false of a recommendation engine, which must skip a track with
# a corrupt feature vector rather than silently score it.
add_library(harmonyshuffle SHARED
    smart_shuffle.cpp
    smart_shuffle_jni.cpp
    distance_kernels.cpp
    topk_search.cpp
)

target_compile_options(harmonyshuffle PRIVATE
    -O3
    -fno-fast-math       # deliberate: the NaN/Inf guards must survive
    -fvisibility=hidden
    -Wall -Wextra
)

# Same 16 KB page alignment requirement as harmonydsp (Android 15+).
target_link_options(harmonyshuffle PRIVATE "-Wl,-z,max-page-size=16384")

# distance_kernels.cpp is built for the BASELINE ISA on purpose -- no -mavx2
# here. The AVX2 kernels carry __attribute__((target("avx2,fma"))) per function
# and are reached through a runtime __builtin_cpu_supports check, so one object
# holds both paths and still runs on a CPU without AVX2. Adding -mavx2 to this
# target would let the compiler put AVX2 into the scalar fallback as well and
# turn the guard into a lie.
#
# On arm64-v8a (the only non-emulator ABI Harmony ships) NEON is mandatory and
# is selected at compile time -- no dispatch, no fallback.

# OpenMP is OFF by default and that is deliberate.
#
# topk_search.cpp compiles and runs correctly without it (serial fallback via
# #ifdef _OPENMP), and on Android OpenMP costs more than it is worth here:
#   * the NDK's libomp has to be shipped or statically linked (-static-openmp),
#     adding size and a second runtime to the APK;
#   * a parallel region costs microseconds to wake, against a ~200 us search;
#   * schedule(static) assumes equal cores, which big.LITTLE is not.
#
# Turn it on only after measuring on a real device:
#   cmake -DHARMONY_ENABLE_OPENMP=ON
option(HARMONY_ENABLE_OPENMP "Parallelise top-K search with OpenMP" OFF)
if(HARMONY_ENABLE_OPENMP)
    find_package(OpenMP REQUIRED)
    target_link_libraries(harmonyshuffle OpenMP::OpenMP_CXX)
    target_compile_options(harmonyshuffle PRIVATE -fopenmp)
    # Static libomp so no .so has to be packaged alongside.
    target_link_options(harmonyshuffle PRIVATE -static-openmp)
endif()

target_link_libraries(harmonyshuffle ${log-lib})
