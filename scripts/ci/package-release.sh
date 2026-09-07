#!/usr/bin/env bash
set -euo pipefail

VERSION_NAME="${1:?usage: package-release.sh <version-name>}"
ABI="${2:-arm64-v8a}"
APK_IN="app/build/outputs/apk/release/app-release.apk"
DIST_DIR="dist"

case "$ABI" in
  arm64-v8a|x86_64) ;;
  *)
    echo "ERROR: unsupported release ABI: $ABI" >&2
    exit 5
    ;;
esac

APK_OUT="$DIST_DIR/Harmony-v${VERSION_NAME}-${ABI}.apk"

if [[ ! -f "$APK_IN" ]]; then
  echo "ERROR: release APK not found at $APK_IN" >&2
  exit 2
fi

mkdir -p "$DIST_DIR"
cp "$APK_IN" "$APK_OUT"
sha256sum "$DIST_DIR"/Harmony-v"${VERSION_NAME}"-*.apk > "$DIST_DIR/SHA256SUMS.txt"

if [[ -n "${ANDROID_HOME:-}" ]]; then
  APKSIGNER="$(find "$ANDROID_HOME/build-tools" -maxdepth 2 -type f -name apksigner 2>/dev/null | sort -V | tail -n1 || true)"
  ZIPALIGN="$(find "$ANDROID_HOME/build-tools" -maxdepth 2 -type f -name zipalign 2>/dev/null | sort -V | tail -n1 || true)"
  if [[ -n "$APKSIGNER" ]]; then
    "$APKSIGNER" verify --verbose --print-certs "$APK_OUT" | tee "$DIST_DIR/APK-SIGNATURE.txt"
  else
    echo "ERROR: apksigner was not found under ANDROID_HOME/build-tools" >&2
    exit 3
  fi
  if [[ -n "$ZIPALIGN" ]]; then
    "$ZIPALIGN" -c -P 16 -v 4 "$APK_OUT"
  else
    echo "ERROR: zipalign was not found under ANDROID_HOME/build-tools" >&2
    exit 4
  fi
fi

cat "$DIST_DIR/SHA256SUMS.txt"
