# Portable SpotiFLAC audio conversion

Harmony packages `libharmony_flac.so` for ARM64 and x86_64. Despite its filename,
this is a PIE command-line executable, installed by Android in nativeLibraryDir.
It does not use MediaCodec, Python, or the extracted yt-dlp FFmpeg runtime.
Its dynamic dependencies are Android's public libc, libm and libz.

The build targets API 29, baseline CPUs and 16 KB ELF load alignment using
NDK 29.0.14206865. This alignment supports 4 KB and 16 KB memory page sizes;
it is not a claim of testing every physical device.

Capabilities: local FLAC-in-MP4 stream copy, ALAC/FLAC lossless decoding and FLAC
encoding, MP3 320 kbps encoding through LAME, and JPEG/PNG attached artwork.
Networking and unrelated codecs are disabled. Harmony still verifies the source
codec and validates the resulting container before importing it.

## Sources and licenses

Unmodified complete source archives are included so recipients can rebuild and
replace the converter. No FFmpeg or LAME source patches are applied.
FFmpeg 7.1.5 and LAME 3.100 are built under LGPL 2.1-or-later; GPL and nonfree
FFmpeg options are not enabled. Complete license notices accompany the archives.

- FFmpeg: https://ffmpeg.org/releases/ffmpeg-7.1.5.tar.xz
  SHA-256 `de668509caf9e35e3cd162473441fdb29538c6d96ed080292b3cf9e6fc5d558f`
- LAME: https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz
  SHA-256 `ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e`

On Linux with the specified NDK installed:

```sh
export ANDROID_HOME=/path/to/android-sdk
bash scripts/ci/build-audio-converter.sh
```

Outputs and hashes are in `feature/downloads/src/main/jniLibs/`. Rebuilding
Harmony packages the replacement executables. The converter runs as a separate
process communicating through files and exit status. The Go backend and the
separate yt-dlp FFmpeg runtime retain their own upstream notices.

For host codec verification with the same enabled codec components:

```sh
HARMONY_CONVERTER_TARGETS=host HARMONY_CONVERTER_OUTPUT=/tmp/harmony-converter \
  bash scripts/ci/build-audio-converter.sh
python3 scripts/ci/test-audio-converter.py /tmp/harmony-converter/host/libharmony_flac.so
```

Host tests require ffmpeg/ffprobe to create synthetic fixtures and independently
compare PCM. They do not contact music services or replace Android device tests.
