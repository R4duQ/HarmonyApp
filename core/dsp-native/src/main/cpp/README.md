# Shared with the iOS app only

The Android app no longer builds anything in this folder: its native code is
Rust, in `native/` at the repository root (built by the `harmony.rust.jni`
Gradle plugin). The C++ engine here is compiled by the iOS app
(`ios/project.yml`) and tested by `.github/workflows/ios-build.yml`
(`../../test/cpp`).

The Rust crates were ported from these sources and checked against them
value for value. A fix to the engine belongs in both until iOS moves to Rust too.
