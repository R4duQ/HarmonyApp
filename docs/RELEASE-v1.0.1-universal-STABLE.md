# Harmony 1.0.1-universal

Built on the user's working `Harmony-v1_0_1-audiofix.zip`. The ReplayGain and
equalizer buffer fixes are preserved. Android versionCode is 85.

- Complete SpotiFLAC Go backend for ARM64 and x86_64 from upstream v4.9.5.
- Portable bundled FFmpeg/LAME conversion for FLAC/ALAC and optional MP3 output,
  avoiding extracted Python/FFmpeg dependencies and vendor codecs.
- FLAC-in-MP4 stream copy first; lossless FLAC level 5 for ALAC and fallback,
  reducing CPU work without changing decoded audio samples.
- Universal APK by default, plus optional architecture-specific releases.
- Build checks reject missing/wrong native architectures. Release packaging
  validates top-level ELF alignment, APK alignment and signatures.
- Full, scrollable and copyable SpotiFLAC error details.
- Settings displays the installed version rather than a fixed old value.

Supported: Android 10+ with a 64-bit ARM or x86 Android userspace. A 64-bit
processor running 32-bit Android is not supported. The converter and backend
have 16 KB ELF load alignment, compatible with 4 KB and 16 KB memory pages.
This is not certification on every physical phone. Downloads still depend on
internet, provider availability and authentication. Existing provider routing
and protected-stream handling are preserved.

The older yt-dlp runtime remains for other download paths and as a legacy
fallback. Its nested libraries are not certified by the portable converter's
alignment checks. See [verification](UNIVERSAL-VERIFICATION.md) for test limits.

Update with the same signing key. The recommended release asset is
`Harmony-v1.0.1-universal.apk`; see [INSTRUCTIUNI.txt](../INSTRUCTIUNI.txt).
