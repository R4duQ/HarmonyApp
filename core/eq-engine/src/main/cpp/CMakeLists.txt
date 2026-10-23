cmake_minimum_required(VERSION 3.22.1)
project(harmonyeq CXX)

add_library(harmonyeq SHARED
    MobileEqEngine.cpp
    eq_jni.cpp
)

target_compile_options(harmonyeq PRIVATE
    -O3
    # No -ffast-math: the engine relies on std::isfinite to keep NaN/Inf out of
    # its filters, which -ffast-math is allowed to fold away. These two keep
    # most of its speed without that risk.
    -fno-math-errno
    -ffp-contract=fast
    -fvisibility=hidden
    -Wall -Wextra
)

# 16 KB page alignment: required for Android 15+ device compatibility.
target_link_options(harmonyeq PRIVATE "-Wl,-z,max-page-size=16384")
