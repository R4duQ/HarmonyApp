# Harmony v1.0.1

Compatibility hotfix for SpotiFLAC lossless container finalization on additional Android devices.

- Makes Harmony's bundled FFmpeg process environment match youtubedl-android upstream first.
- Adds an alternate linker-environment retry for device/vendor differences.
- Adds device/ABI and FFmpeg self-test diagnostics to SpotiFLAC error details.
- Keeps FLAC/ALAC-only lossless conversion rules; AAC/Opus are still never relabeled as lossless.
- No changes to provider verification or access controls.
