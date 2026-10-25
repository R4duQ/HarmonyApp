#!/usr/bin/env bash
set -euo pipefail

# Build the same Rust/Swift ABI and provider packages on CI and local macOS.
# Generated binaries and bindings live in ios/Vendor and are never committed.
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
readonly MORNYE_COMMIT=9bf4c000a07fb7bf4b6b68cd80a552cdf8e81298
readonly EXTENSIONS_COMMIT=6b544663fb5b7a7dfbe1959f3c0edab017378f53
SOURCE="$ROOT/build/mornye-ios-source"
STAGE="$ROOT/ios/Vendor/Mornye"
FFMPEG="$ROOT/ios/Vendor/FFmpeg"

[[ "$(uname -s)" == Darwin ]] || { echo "Mornye iOS requires macOS and Xcode." >&2; exit 1; }
command -v rustup >/dev/null || { echo "Install rustup before building Mornye iOS." >&2; exit 1; }
mkdir -p "$SOURCE" "$STAGE" "$FFMPEG"
if [[ ! -d "$SOURCE/.git" ]]; then
  git -C "$SOURCE" init -q
  git -C "$SOURCE" remote add origin https://github.com/mornyeapp/Mornye.git
fi
git -C "$SOURCE" fetch --depth 1 origin "$MORNYE_COMMIT"
git -C "$SOURCE" checkout --detach --force FETCH_HEAD
[[ "$(git -C "$SOURCE" rev-parse HEAD)" == "$MORNYE_COMMIT" ]]
(
  cd "$SOURCE/rust_backend"
  rustup toolchain install 1.99.0 --profile minimal
)
bash "$SOURCE/scripts/build_rust_backend.sh" ios
cp "$SOURCE/rust_backend/target/ios/MornyeBackend.swift" "$STAGE/"
ditto "$SOURCE/rust_backend/target/ios/MornyeBackendFFI.xcframework" "$STAGE/MornyeBackendFFI.xcframework"
printf 'Mornye 5.2.5\ncommit %s\n' "$MORNYE_COMMIT" > "$STAGE/SOURCE.txt"

mkdir -p "$STAGE/Host"
cp "$SOURCE/rust_backend/target/release/libmornye_mobile.dylib" "$STAGE/Host/"
cp "$SOURCE/rust_backend/target/ios/headers/MornyeBackendFFI.h" "$STAGE/Host/"
cp "$SOURCE/rust_backend/target/ios/headers/module.modulemap" "$STAGE/Host/"
mkdir -p "$STAGE/MornyeProviders"
while read -r id package checksum; do
  [[ -n "$id" ]] || continue
  url="https://raw.githubusercontent.com/mornyeapp/Mornye-Extension/$EXTENSIONS_COMMIT/extensions/$package"
  curl --fail --location --retry 3 "$url" -o "$STAGE/MornyeProviders/$id.sflx"
  printf '%s  %s\n' "$checksum" "$STAGE/MornyeProviders/$id.sflx" | shasum -a 256 --check
  curl --fail --location --retry 3 \
    "https://raw.githubusercontent.com/mornyeapp/Mornye-Extension/$EXTENSIONS_COMMIT/sources/$id/manifest.json" \
    -o "$STAGE/MornyeProviders/$id.json"
done <<'PROVIDERS'
tidal-web tidal-web.sflx 69ce52eabdd9df6464a30b793b802d3d7be32b331082c02a9bf2b25d1d49114d
qobuz-web qobuz-web.sflx 054c1d2c40058b49f5ade17a64442014ddf05944663a12c790a17d63ee99985f
deezer deezer.sflx 39868171eee142f21de323b38c2ee5690e44659496f8e70e01554564b464f292
amazon amzn.sflx d45e105b4286d4c7ddc062b73b8190c93099e7f6e4d4586b420e0b31dabcb967
PROVIDERS

# The FFmpegKit audio fork used by Mornye 5.2.5 itself. Each combined
# XCFramework includes device and simulator slices. Verify every ZIP.
while read -r name checksum; do
  [[ -n "$name" ]] || continue
  archive="$ROOT/build/$name-8.1.2-audio.zip"
  curl --fail --location --retry 3 \
    "https://github.com/sk3llo/ffmpeg_kit_flutter/releases/download/8.1.2-audio/$name.xcframework.zip" -o "$archive"
  printf '%s  %s\n' "$checksum" "$archive" | shasum -a 256 --check
  unzip -q -o "$archive" -d "$FFMPEG"
  test -f "$FFMPEG/$name.xcframework/Info.plist"
done <<'FFMPEG_LIBRARIES'
ffmpegkit 122f9165b9c4dafdf64d4167d754b803c90ada4b17a285d4e17493b9c5618780
libavcodec 17af32fb8b4a476262c4d2174adbe75abce11e6a4a09cfc288de7e9ccde15d3c
libavdevice c1ef46493cc78b348e00ba334e6b57a66f0e5d09e5743611ce1baf4ae06a76bb
libavfilter 91ac817bc2e411cc53199ca75e829aa674ccf1d61a38d6b9a44edac408b73f9d
libavformat 3f538c4351584d511efa295e2ce697af1cdf9fafd44cf9bdba0fd818fdea09d4
libavutil e5dfcdbd0b718aa8ae392a07762bca6fe381de9c4c5cdf95c3d7bf6eff10e628
libswresample 08518579265217dc93e7c369fe7542cc6f5b1ca9a709f1b86aa8f3ed1cfee288
libswscale 8c5ca6c09efd2829aa3d6ad0d654ecdc2922f52ff4c51ef658f712d70022611d
FFMPEG_LIBRARIES

test -s "$STAGE/MornyeBackend.swift"
test -f "$STAGE/MornyeBackendFFI.xcframework/Info.plist"
echo "Mornye iOS runtime, Swift bindings, provider packages and FFmpeg frameworks are ready."
