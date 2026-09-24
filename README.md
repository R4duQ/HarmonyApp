# Harmony 1.0

Android music player with a local library, playlists, equalizer, personalized song
discovery, Spotify playlist import and album and track downloads through the
configured SpotiFLAC and Soulseek integrations.

**1.0** (`versionCode = 101`) is the first stable version. It contains every change
made so far: the FLAC compatibility fix, album downloads, the three-step Discover flow
([instructions](DISCOVER-FLOW.md)), SpotiFLAC search across its providers (Tidal
included), Smart Shuffle, the Home, playlist and album page redesigns and the Android Auto fixes.
Full-album and selected-track downloads are in Downloads; see
[album instructions](ALBUME-DOWNLOADS.md). The versionCode is higher than every
earlier build, so 1.0 installs as an update over them when it is signed with the same key.

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

The release workflow runs when a version tag is pushed, or manually from the
Actions tab (Publish Android Release, Run workflow) with the tag as input. For this
release the tag is `v1.0`; it must match `app/build.gradle.kts`. Configure these repository
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

See [release notes](docs/RELEASE-v1.0-STABLE.md) and
[test instructions](tools/audit/README.md). Previous audit reports and build
instructions are preserved under [docs/history](docs/history/README.md) as
historical evidence, not current installation instructions.

The prior audit included isolated Kotlin compilation and 135 passing JUnit tests.
It did not produce a full Android APK or validate behavior on a physical phone.
This release preparation aligns version labels; it does not establish additional
device validation.

Third-party attribution is in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
