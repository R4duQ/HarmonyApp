# Harmony 1.0

First stable version. Android versionName **1.0**, versionCode **100**: higher than
every earlier build, so it installs as an update over 1.0.0–1.0.4 when signed with
the same key. Library, playlists and settings are kept.

## Download

- `Harmony-v1.0-arm64-v8a.apk`: for phones (64-bit ARM, Android 10 or newer).
- `Harmony-v1.0-x86_64.apk`: for the Android emulator only.

`SHA256SUMS.txt` and `APK-SIGNATURE.txt` let you check the file and its signing
certificate.

## What's in it

- **Discover** in three steps: preferences, songs, playlist.
  - Recommendations come from your listening history, favourites and playlists.
    Three exploration levels: For my taste, Balanced mix, Surprise me.
  - Per-song actions: 30 s preview, Keep, Replace, More like this and Not interested.
  - "Create playlist with the X available songs" saves what is ready now. The rest
    join the same playlist once they are downloaded.
  - Deezer and Apple Music are used with timeouts, retries and fallback. Offline, you
    can still build a playlist from your library.
- **SpotiFLAC search** asks SpotiFLAC's own providers first, including Tidal. It then
  tries several phrasings on Deezer, then Apple Music, so titles with hyphens or
  missing diacritics are found.
- **Downloads.** Before a batch starts, Harmony checks that the provider is ready.
  Downloads show per-song progress. Albums and selected tracks can be downloaded
  through SpotiFLAC or Soulseek.
- **FLAC compatibility fix.** FFmpeg runs from its own verified library set.
- **Smart Shuffle** reshuffles the queue correctly.
- Redesigned **Home** and **playlist detail** screens.
- **Android Auto.** The session stays alive when the phone app is swiped away.
  Connections are also written to a small diagnostic log, which you can read with
  `adb pull /sdcard/Android/data/com.harmony.app/files/android-auto-log.txt`.

## Limits

The APKs are built and signed by GitHub Actions from this source. Unit tests run
during the build. Behaviour on individual phones and head units is not certified.
Downloads depend on internet access and on provider availability and authentication.
