#!/usr/bin/env bash
set -euo pipefail

APP_GRADLE="${1:-app/build.gradle.kts}"
RELEASE_TAG="${2:-${GITHUB_REF_NAME:-}}"

VERSION_NAME="$(sed -n 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$APP_GRADLE" | head -n1)"
VERSION_CODE="$(sed -n 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$APP_GRADLE" | head -n1)"

if [[ -z "$VERSION_NAME" || -z "$VERSION_CODE" ]]; then
  echo "ERROR: could not read versionName/versionCode from $APP_GRADLE" >&2
  exit 2
fi

if [[ -n "$RELEASE_TAG" && "$RELEASE_TAG" != "v$VERSION_NAME" ]]; then
  echo "ERROR: tag $RELEASE_TAG does not match app version v$VERSION_NAME" >&2
  exit 3
fi

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    echo "version_name=$VERSION_NAME"
    echo "version_code=$VERSION_CODE"
  } >> "$GITHUB_OUTPUT"
fi

echo "Harmony versionName=$VERSION_NAME versionCode=$VERSION_CODE"
