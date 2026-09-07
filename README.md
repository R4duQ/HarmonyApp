# Harmony 1.0.0

Android music player with a local library, playlists, equalizer, album discovery,
swipe-based recommendations, Spotify playlist import and album downloads through
the configured SpotiFLAC and Soulseek integrations.

The public release version is **1.0.0** (`versionCode = 83`). The internal Android
code is retained so existing installations can update with the same signing key.

## Build locally

Requires Java 17 and an Android SDK. The release workflow records the SDK, NDK,
Go and backend versions used for builds. Open the project root in Android Studio
and configure your SDK through the untracked `local.properties` file.

```sh
chmod +x gradlew
./gradlew :app:assembleDebug -PharmonyAbi=arm64-v8a
```

The ZIP includes the local backend artifact for local builds. `vendor-maven/` is
ignored by Git; a fresh Git checkout must first build it using
`scripts/ci/build-spotiflac-backend.sh`, with the toolchain and environment from
`.github/workflows/release.yml`. GitHub Actions performs this step automatically.

Windows installation and update instructions: [INSTRUCTIUNI.txt](INSTRUCTIUNI.txt).
Keep the existing signing key when updating an installed app.

## Publish on GitHub

Upload the contents of this project folder to your repository, including
`.github/`, `gradle/` and the Gradle wrapper. Keep the `.gitignore` rules in place;
do not upload local SDK settings or signing material.

The release workflow runs when a version tag is pushed. For this release the tag
is `v1.0.0`; it must match `app/build.gradle.kts`. Configure these repository
Actions secrets before triggering a signed build:

- `HARMONY_KEYSTORE_BASE64`
- `HARMONY_KEYSTORE_PASSWORD`
- `HARMONY_KEY_ALIAS`
- `HARMONY_KEY_PASSWORD`

The workflow builds signed arm64-v8a and x86_64 APKs and publishes a GitHub
release. Check whether the tag/release already exists before pushing it: the
existing workflow replaces release assets when a release with that tag exists.
Uploading these sources alone does not trigger a tagged release.

## Release and verification

See [release notes](docs/RELEASE-v1.0.0-STABLE.md) and
[test instructions](tools/audit/README.md). Previous audit reports and build
instructions are preserved under [docs/history](docs/history/README.md) as
historical evidence, not current installation instructions.

The prior audit included isolated Kotlin compilation and 135 passing JUnit tests.
It did not produce a full Android APK or validate behavior on a physical phone.
This release preparation aligns version labels; it does not establish additional
device validation.

Third-party attribution is in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
