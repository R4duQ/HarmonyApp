# Harmony v1.0.2

Downloads-screen compatibility hotfix for Android emulators.

- Adds a native `x86_64` SpotiFLAC backend for Pixel and other 64-bit Android emulators.
- Publishes separate `arm64-v8a` phone and `x86_64` emulator APKs.
- Checks the packaged Go backend ABI before entering the native bridge.
- Keeps the Downloads screen open with an actionable error if the wrong APK is installed.
- Preserves the v1.0.1 device-FFmpeg finalization fix and all provider-verification rules.
