#!/usr/bin/env bash
set -euo pipefail

# Builds Mornye's Rust backend (the successor of SpotiFLAC's Go one) for the
# Mornye build of Harmony, and stages it where :vendor:mornye picks it up:
#
#   vendor-mornye/kotlin/          UniFFI Kotlin bindings (com.mornye.backend)
#   vendor-mornye/jniLibs/<abi>/   libmornye_mobile.so
#
# It runs Mornye's own build script on the pinned tag, so the library and the
# bindings come from the same source. Intended for GitHub-hosted Ubuntu runners
# and local Linux builds.

MORNYE_TAG="${MORNYE_TAG:-v5.3.1}"
# The commit the tag must point at. A tag can be moved; a commit cannot. When
# MORNYE_TAG changes, this changes with it (and THIRD_PARTY_NOTICES.md).
MORNYE_COMMIT="${MORNYE_COMMIT:-}"
if [[ -z "$MORNYE_COMMIT" ]]; then
  case "$MORNYE_TAG" in
    v5.3.1) MORNYE_COMMIT="e7a8f754a618d28ab0baf88ada3f65ab9d41534a" ;;
    *)
      echo "ERROR: no pinned commit for Mornye $MORNYE_TAG; set MORNYE_COMMIT to the reviewed commit" >&2
      exit 17
      ;;
  esac
fi
MORNYE_NDK_VERSION="${MORNYE_NDK_VERSION:-30.0.16248370}"
# Mornye builds its Android library for ARM only; Harmony's Mornye build is a phone build.
MORNYE_ABIS="${MORNYE_ABIS:-arm64-v8a}"
WORK_DIR="${RUNNER_TEMP:-/tmp}/harmony-mornye-${MORNYE_TAG#v}"
SOURCE_DIR="$WORK_DIR/Mornye"
STAGE_DIR="${GITHUB_WORKSPACE:-$(pwd)}/vendor-mornye"

: "${ANDROID_HOME:?ANDROID_HOME must be set}"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$MORNYE_NDK_VERSION"
if [[ ! -d "$ANDROID_NDK_HOME" ]]; then
  echo "ERROR: Android NDK $MORNYE_NDK_VERSION (Mornye's pinned NDK) is missing at $ANDROID_NDK_HOME" >&2
  exit 13
fi
if ! command -v rustup >/dev/null; then
  echo "ERROR: rustup is required; Mornye pins its Rust toolchain in rust-toolchain.toml" >&2
  exit 14
fi

rm -rf "$WORK_DIR" "$STAGE_DIR"
mkdir -p "$WORK_DIR"
git clone --quiet --depth 1 --branch "$MORNYE_TAG" \
  https://github.com/spotiflacapp/SpotiFLAC-Mobile.git "$SOURCE_DIR"
ACTUAL_COMMIT="$(git -C "$SOURCE_DIR" rev-parse HEAD)"
if [[ "$ACTUAL_COMMIT" != "$MORNYE_COMMIT" ]]; then
  echo "ERROR: Mornye $MORNYE_TAG is at $ACTUAL_COMMIT, not the pinned $MORNYE_COMMIT." >&2
  echo "       The tag moved (or the pin is stale): review the new source before building it." >&2
  exit 18
fi

# rust-toolchain.toml selects the pinned toolchain; install it up front so the
# failure, if any, names the toolchain rather than a crate.
(cd "$SOURCE_DIR/rust_backend" && rustup show active-toolchain || rustup toolchain install)

MORNYE_RUST_ANDROID_ABIS="$MORNYE_ABIS" bash "$SOURCE_DIR/scripts/build_rust_backend.sh" android

TARGET="$SOURCE_DIR/rust_backend/target"
mkdir -p "$STAGE_DIR"
cp -R "$TARGET/bindings/kotlin" "$STAGE_DIR/kotlin"
cp -R "$TARGET/android/jniLibs" "$STAGE_DIR/jniLibs"

IFS=',' read -r -a ABIS <<< "$MORNYE_ABIS"
for ABI in "${ABIS[@]}"; do
  LIBRARY="$STAGE_DIR/jniLibs/$ABI/libmornye_mobile.so"
  if [[ ! -s "$LIBRARY" ]]; then
    echo "ERROR: Mornye backend missing for $ABI at $LIBRARY" >&2
    exit 15
  fi
done
if [[ ! -s "$STAGE_DIR/kotlin/com/mornye/backend/mornye_mobile.kt" ]]; then
  echo "ERROR: Mornye UniFFI Kotlin bindings were not generated" >&2
  exit 16
fi

{
  echo "Mornye $MORNYE_TAG ($(git -C "$SOURCE_DIR" rev-parse HEAD))"
  echo "NDK $MORNYE_NDK_VERSION; ABIs $MORNYE_ABIS"
  (cd "$SOURCE_DIR/rust_backend" && rustc --version)
} > "$STAGE_DIR/SOURCE.txt"
cat "$STAGE_DIR/SOURCE.txt"
find "$STAGE_DIR" -type f -exec ls -l {} \;
