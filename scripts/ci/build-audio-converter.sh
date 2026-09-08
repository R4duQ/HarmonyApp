#!/usr/bin/env bash
set -euo pipefail
# Baseline-CPU executable installed by Android in nativeLibraryDir.
# FFmpeg and LAME are static; only public Android libraries are dynamic.
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SOURCES="$PROJECT_ROOT/third_party/audio-converter"
OUTPUT_ROOT="${HARMONY_CONVERTER_OUTPUT:-$PROJECT_ROOT/feature/downloads/src/main/jniLibs}"
TARGETS="${HARMONY_CONVERTER_TARGETS:-arm64-v8a x86_64}"
WORK="$(mktemp -d "${RUNNER_TEMP:-/tmp}/harmony-converter.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
(cd "$SOURCES" && sha256sum -c <<'SUMS'
de668509caf9e35e3cd162473441fdb29538c6d96ed080292b3cf9e6fc5d558f  ffmpeg-7.1.5.tar.xz
ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e  lame-3.100.tar.gz
SUMS
)
tar --no-same-owner -xf "$SOURCES/ffmpeg-7.1.5.tar.xz" -C "$WORK"
tar --no-same-owner -xf "$SOURCES/lame-3.100.tar.gz" -C "$WORK"
mapfile -t FFMPEG_OPTIONS < "$SOURCES/ffmpeg-config.txt"
for ABI in $TARGETS; do
  BUILD="$WORK/$ABI"
  PREFIX="$BUILD/lame-install"
  mkdir -p "$BUILD/lame" "$BUILD/ffmpeg"
  CROSS=()
  LAME_CROSS=()
  LINK_FLAGS='-pie'
  case "$ABI" in
    arm64-v8a) ARCH=aarch64; TARGET=aarch64-linux-android ;;
    x86_64) ARCH=x86_64; TARGET=x86_64-linux-android ;;
    host) ARCH=''; TARGET='' ;;
    *) echo "Unsupported converter ABI: $ABI" >&2; exit 2 ;;
  esac
  if [[ "$ABI" == host ]]; then
    CONVERTER_CC="${CC:-cc}"
    CONVERTER_AR="${AR:-ar}"
    CONVERTER_RANLIB="${RANLIB:-ranlib}"
    CONVERTER_STRIP="${STRIP:-strip}"
    CONVERTER_NM="${NM:-nm}"
  else
    CONVERTER_NDK="${ANDROID_NDK_HOME:-${ANDROID_HOME:?Set ANDROID_HOME or ANDROID_NDK_HOME}/ndk/29.0.14206865}"
    TOOL_BIN="$CONVERTER_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
    CONVERTER_CC="$TOOL_BIN/${TARGET}29-clang"
    CONVERTER_AR="$TOOL_BIN/llvm-ar"
    CONVERTER_RANLIB="$TOOL_BIN/llvm-ranlib"
    CONVERTER_STRIP="$TOOL_BIN/llvm-strip"
    CONVERTER_NM="$TOOL_BIN/llvm-nm"
    CROSS=(--target-os=android --arch="$ARCH" --enable-cross-compile)
    LAME_CROSS=(--host="$TARGET")
    LINK_FLAGS+=' -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384'
  fi
  (
    cd "$BUILD/lame"
    CC="$CONVERTER_CC" AR="$CONVERTER_AR" RANLIB="$CONVERTER_RANLIB" \
      CFLAGS='-O2 -fPIC' "$WORK/lame-3.100/configure" \
      "${LAME_CROSS[@]}" --prefix="$PREFIX" \
      --disable-frontend --disable-decoder --disable-shared --enable-static \
      --disable-nasm --with-pic
    make -j"${HARMONY_BUILD_JOBS:-4}"
    make install
  )
  (
    cd "$BUILD/ffmpeg"
    "$WORK/ffmpeg-7.1.5/configure" "${FFMPEG_OPTIONS[@]}" "${CROSS[@]}" \
      --cc="$CONVERTER_CC" --ar="$CONVERTER_AR" --ranlib="$CONVERTER_RANLIB" \
      --strip="$CONVERTER_STRIP" --nm="$CONVERTER_NM" \
      --extra-cflags="-fPIE -I$PREFIX/include" \
      --extra-ldflags="$LINK_FLAGS -L$PREFIX/lib"
    make -j"${HARMONY_BUILD_JOBS:-4}" ffmpeg
  )
  mkdir -p "$OUTPUT_ROOT/$ABI"
  install -m 755 "$BUILD/ffmpeg/ffmpeg" "$OUTPUT_ROOT/$ABI/libharmony_flac.so"
  echo "Built $ABI audio converter"
done
python3 - "$OUTPUT_ROOT" "$SOURCES" <<'PY'
import hashlib, json, pathlib, sys
out, source = map(pathlib.Path, sys.argv[1:])
manifest = {
    'ffmpeg': '7.1.5', 'lame': '3.100', 'android_api': 29,
    'ndk': '29.0.14206865', 'page_alignment': 16384,
    'configuration_sha256': hashlib.sha256((source/'ffmpeg-config.txt').read_bytes()).hexdigest(),
    'binaries': {str(p.relative_to(out)): hashlib.sha256(p.read_bytes()).hexdigest()
                 for p in sorted(out.glob('*/libharmony_flac.so'))},
}
(out/'audio-converter-manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
PY
