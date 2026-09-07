# Harmony 1.0.0

Android versionName: **1.0.0**  
Android versionCode: **83**

This public release brings together the current Harmony source and prior audit
fixes under version 1.0.0. The package ID, database schema and signing
configuration are unchanged by this version update.

- Local music library, playlists, playback and equalizer.
- Album discovery with refresh, genre filtering and swipe-based recommendations.
- Explained album recommendations after swipe rounds.
- Spotify playlist import and configured SpotiFLAC/Soulseek integrations.
- Selective album downloads and keep/delete flows for downloaded music.
- Previously completed fixes for discovery, playback, downloads and library data.

The existing audit evidence is retained in `docs/history/`. Version consistency
was checked for this release preparation. Full Android packaging and real-device
testing remain necessary before distributing a release APK.

The GitHub workflow produces `Harmony-v1.0.0-arm64-v8a.apk` and
`Harmony-v1.0.0-x86_64.apk`, with checksums and signing information. These are
expected workflow outputs; this source archive does not contain newly built APKs.
