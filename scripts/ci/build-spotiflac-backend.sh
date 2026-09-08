#!/usr/bin/env bash
set -euo pipefail

# Builds Harmony's typed GoMobile AAR from the pinned SpotiFLAC Mobile source.
# Intended for GitHub-hosted Ubuntu runners and local Linux CI.

SPOTIFLAC_TAG="${SPOTIFLAC_TAG:-v4.9.5}"
SPOTIFLAC_VERSION="${SPOTIFLAC_VERSION:-4.9.5}"
EXPECTED_GO_VERSION="${EXPECTED_GO_VERSION:-1.26.6}"
NDK_VERSION="${NDK_VERSION:-29.0.14206865}"
ANDROID_API="${ANDROID_API:-24}"
WORK_DIR="$(mktemp -d "${RUNNER_TEMP:-/tmp}/harmony-spotiflac.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT
SOURCE_DIR="$WORK_DIR/SpotiFLAC-Mobile"
BACKEND_DIR="$SOURCE_DIR/go_backend"
MAVEN_ROOT="${GITHUB_WORKSPACE:-$(pwd)}/vendor-maven"
MAVEN_DIR="$MAVEN_ROOT/com/harmony/vendor/gobackend/$SPOTIFLAC_VERSION"
AAR_OUT="$WORK_DIR/gobackend-$SPOTIFLAC_VERSION.aar"
POM_OUT="$WORK_DIR/gobackend-$SPOTIFLAC_VERSION.pom"

: "${ANDROID_HOME:?ANDROID_HOME must be set}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$NDK_VERSION"
export CGO_ENABLED=1

if [[ ! -d "$ANDROID_NDK_HOME" ]]; then
  echo "ERROR: Android NDK $NDK_VERSION is missing at $ANDROID_NDK_HOME" >&2
  exit 13
fi

mkdir -p "$MAVEN_DIR"

echo "Cloning SpotiFLAC Mobile $SPOTIFLAC_TAG..."
git clone --quiet --depth 1 --branch "$SPOTIFLAC_TAG" \
  https://github.com/spotiflacapp/SpotiFLAC-Mobile.git "$SOURCE_DIR"

if [[ ! -f "$BACKEND_DIR/go.mod" ]]; then
  echo "ERROR: go_backend/go.mod is missing in $SPOTIFLAC_TAG" >&2
  exit 14
fi

GO_DIRECTIVE="$(awk '$1 == "go" { print $2; exit }' "$BACKEND_DIR/go.mod")"
XMOBILE_VERSION="$(awk '$1 == "golang.org/x/mobile" { print $2; exit }' "$BACKEND_DIR/go.mod")"

if [[ "$GO_DIRECTIVE" != "$EXPECTED_GO_VERSION" ]]; then
  echo "ERROR: SpotiFLAC $SPOTIFLAC_TAG expects Go $GO_DIRECTIVE; workflow is pinned to $EXPECTED_GO_VERSION" >&2
  exit 15
fi
if [[ -z "$XMOBILE_VERSION" ]]; then
  echo "ERROR: could not read golang.org/x/mobile revision from go.mod" >&2
  exit 16
fi

FOUND_GO="$(go env GOVERSION | sed 's/^go//')"
if [[ "$FOUND_GO" != "$EXPECTED_GO_VERSION" ]]; then
  echo "ERROR: Go $EXPECTED_GO_VERSION required, found $FOUND_GO" >&2
  exit 17
fi

GOBIN_DIR="${RUNNER_TEMP:-/tmp}/harmony-gobin"
mkdir -p "$GOBIN_DIR"
export GOBIN="$GOBIN_DIR"
export PATH="$GOBIN_DIR:$PATH"

pushd "$BACKEND_DIR" >/dev/null

echo "Downloading Go modules..."
go mod download

echo "Installing pinned gobind $XMOBILE_VERSION..."
go install "golang.org/x/mobile/cmd/gobind@$XMOBILE_VERSION"

echo "Building arm64 + x86_64 gobackend.aar..."
go run "golang.org/x/mobile/cmd/gomobile@$XMOBILE_VERSION" bind \
  -v \
  -ldflags="-s -w" \
  -target=android/arm64,android/amd64 \
  -androidapi "$ANDROID_API" \
  -o "$AAR_OUT" \
  .

popd >/dev/null

if [[ ! -s "$AAR_OUT" ]]; then
  echo "ERROR: gomobile completed but $AAR_OUT was not created" >&2
  exit 23
fi

unzip -l "$AAR_OUT" | grep -q 'classes.jar' || {
  echo "ERROR: gobackend.aar does not contain classes.jar" >&2
  exit 24
}
unzip -l "$AAR_OUT" | grep -q 'jni/arm64-v8a/libgojni.so' || {
  echo "ERROR: gobackend.aar does not contain arm64-v8a/libgojni.so" >&2
  exit 25
}
unzip -l "$AAR_OUT" | grep -q 'jni/x86_64/libgojni.so' || {
  echo "ERROR: gobackend.aar does not contain x86_64/libgojni.so" >&2
  exit 26
}

cat > "$POM_OUT" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.harmony.vendor</groupId>
  <artifactId>gobackend</artifactId>
  <version>$SPOTIFLAC_VERSION</version>
  <packaging>aar</packaging>
  <name>Harmony generated SpotiFLAC Go backend</name>
</project>
POM

CHECK_DIR="$WORK_DIR/aar-check"
mkdir -p "$CHECK_DIR"
(
  cd "$CHECK_DIR"
  unzip -q "$AAR_OUT" classes.jar
  javap -classpath classes.jar gobackend.Gobackend > contract.txt
)

required_methods=(
  downloadByStrategy getAllDownloadProgress clearItemProgress cancelDownload
  resetDownloadCancel initExtensionSystem loadExtensionsFromDir
  setProviderPriorityJSON setExtensionFallbackProviderIDsJSON
  setExtensionEnabledByID getExtensionPendingAuthJSON getAllPendingAuthRequestsJSON
  getPendingAuthRequest isExtensionAuthenticatedByID setExtensionAuthCodeByID
  setExtensionSessionGrantByID invokeExtensionActionJSON clearExtensionPendingAuthByID
)
for method in "${required_methods[@]}"; do
  grep -q "$method" "$CHECK_DIR/contract.txt" || {
    echo "ERROR: required SpotiFLAC backend method is missing: $method" >&2
    exit 28
  }
done

SPOTIFLAC_COMMIT="$(git -C "$SOURCE_DIR" rev-parse HEAD)"
(cd "$WORK_DIR" && sha256sum "$(basename "$AAR_OUT")") | tee "$AAR_OUT.sha256.txt"
cat > "$AAR_OUT.source.txt" <<META
SpotiFLAC Mobile $SPOTIFLAC_TAG
SpotiFLAC commit $SPOTIFLAC_COMMIT
Go $GO_DIRECTIVE
x/mobile $XMOBILE_VERSION
NDK $NDK_VERSION
Targets android/arm64,android/amd64
Linker flags -s -w (omit native debug symbols)
META

cat "$AAR_OUT.source.txt"
python3 "${GITHUB_WORKSPACE:-$(pwd)}/scripts/ci/verify-native-package.py" "$AAR_OUT" --mode aar
cp "$AAR_OUT" "$POM_OUT" "$AAR_OUT.sha256.txt" "$AAR_OUT.source.txt" "$MAVEN_DIR/"
echo "SpotiFLAC backend ready: $MAVEN_DIR/$(basename "$AAR_OUT")"
