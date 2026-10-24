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
# harmonyshuffle: Smart Shuffle (SmartShuffleEngine, the ShuffleDiagnosticBrain,
# the 24-D AcousticExtractor) and its JNI bridge.
#
# A SEPARATE target, not extra sources on harmonydsp. harmonydsp is built with
# -ffast-math, under which the compiler may assume NaN/Inf cannot occur and
# fold the engine's std::isfinite guards to a constant: exactly the checks the
# Diagnostic Brain's integrity audit depends on.
#
# The 24-float dot products are plain fixed-length loops that -O3 vectorizes
# (NEON on arm64-v8a, SSE2 on x86_64); no intrinsics are needed.
add_library(harmonyshuffle SHARED
    SmartShuffleEngine.cpp
    shuffle_jni.cpp
)

target_compile_options(harmonyshuffle PRIVATE
    -O3
    -fno-fast-math       # deliberate: the NaN/Inf guards must survive
    -fvisibility=hidden
    -Wall -Wextra
)

# Same 16 KB page alignment requirement as harmonydsp (Android 15+).
target_link_options(harmonyshuffle PRIVATE "-Wl,-z,max-page-size=16384")

target_link_libraries(harmonyshuffle ${log-lib})
