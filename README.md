# Harmony 1.0.1-universal

Android music player with a local library, playlists, equalizer, album discovery,
swipe-based recommendations, Spotify playlist import and album downloads through
the configured SpotiFLAC and Soulseek integrations.

This patch is **1.0.1-universal** (`versionCode = 85`), based on the working
audiofix archive. Update existing installations using the same signing key.
It supports Android 10+ with a 64-bit ARM or x86 Android userspace. The default
APK includes the complete SpotiFLAC backend and converter for both architectures.

## Build locally

Requires Java 17, SDK 35 and NDK 29.0.14206865. The release workflow records the SDK, NDK,
Go and backend versions used for builds. Open the project root in Android Studio
and configure your SDK through the untracked `local.properties` file.

```sh
chmod +x gradlew
./gradlew :app:assembleDebug
```

This creates a universal APK. Add `-PharmonyAbi=arm64-v8a` or `-PharmonyAbi=x86_64`
only when you deliberately want a smaller architecture-specific APK.

The ZIP includes both converter binaries and the dual-ABI backend. `vendor-maven/` is
ignored by Git; a fresh Git checkout must first build it using
`scripts/ci/build-spotiflac-backend.sh`, with the toolchain and environment from
`.github/workflows/release.yml`. GitHub Actions performs this step automatically.
Rebuild the converter with `scripts/ci/build-audio-converter.sh`; its complete
sources and licenses are in [third_party/audio-converter](third_party/audio-converter/README.md).

Windows installation and update instructions: [INSTRUCTIUNI.txt](INSTRUCTIUNI.txt).
Keep the existing signing key when updating an installed app.

## Publish on GitHub

Upload the contents of this project folder to your repository, including
`.github/`, `gradle/` and the Gradle wrapper. Keep the `.gitignore` rules in place;
do not upload local SDK settings or signing material.

The release workflow runs when a version tag is pushed. For this release the tag
is `v1.0.1-universal`; it must match `app/build.gradle.kts`. Configure these repository
Actions secrets before triggering a signed build:

- `HARMONY_KEYSTORE_BASE64`
- `HARMONY_KEYSTORE_PASSWORD`
- `HARMONY_KEY_ALIAS`
- `HARMONY_KEY_PASSWORD`

The workflow builds a signed universal APK plus arm64-v8a and x86_64 APKs and publishes a GitHub
release. Check whether the tag/release already exists before pushing it: the
existing workflow replaces release assets when a release with that tag exists.
Uploading these sources alone does not trigger a tagged release.

## Release and verification

See [release notes](docs/RELEASE-v1.0.1-universal-STABLE.md),
[current checks and their limits](docs/UNIVERSAL-VERIFICATION.md), and
[test instructions](tools/audit/README.md). Previous audit reports and build
instructions are preserved under [docs/history](docs/history/README.md) as
historical evidence, not current installation instructions.

The prior audit included isolated Kotlin compilation and 135 passing JUnit tests.
It did not produce a full Android APK or validate behavior on a physical phone.
This release preparation aligns version labels; it does not establish additional
device validation.

Third-party attribution is in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
