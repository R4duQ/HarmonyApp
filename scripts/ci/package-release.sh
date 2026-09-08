#!/usr/bin/env bash
set -euo pipefail

VERSION_NAME="${1:?usage: package-release.sh <version-name>}"
ABI="${2:-universal}"
APK_IN="app/build/outputs/apk/release/app-release.apk"
DIST_DIR="dist"

case "$ABI" in
  arm64-v8a|x86_64|universal) ;;
  *)
    echo "ERROR: unsupported release ABI: $ABI" >&2
    exit 5
    ;;
esac

APK_OUT="$DIST_DIR/Harmony-v${VERSION_NAME}-${ABI}.apk"
if [[ "$ABI" == universal ]]; then
  APK_OUT="$DIST_DIR/Harmony-v${VERSION_NAME}.apk"
fi

if [[ ! -f "$APK_IN" ]]; then
  echo "ERROR: release APK not found at $APK_IN" >&2
  exit 2
fi

mkdir -p "$DIST_DIR"
python3 scripts/ci/verify-native-package.py "$APK_IN" --abi "$ABI" \
  --report "$DIST_DIR/NATIVE-${ABI}.json"
cp "$APK_IN" "$APK_OUT"

if [[ -n "${ANDROID_HOME:-}" ]]; then
  APKSIGNER="$(find "$ANDROID_HOME/build-tools" -maxdepth 2 -type f -name apksigner 2>/dev/null | sort -V | tail -n1 || true)"
  ZIPALIGN="$(find "$ANDROID_HOME/build-tools" -maxdepth 2 -type f -name zipalign 2>/dev/null | sort -V | tail -n1 || true)"
  if [[ -n "$APKSIGNER" ]]; then
    "$APKSIGNER" verify --verbose --print-certs "$APK_OUT" | tee "$DIST_DIR/APK-SIGNATURE-${ABI}.txt"
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

(cd "$DIST_DIR" && sha256sum Harmony-v"${VERSION_NAME}"*.apk) > "$DIST_DIR/SHA256SUMS.txt"
cat "$DIST_DIR/SHA256SUMS.txt"
