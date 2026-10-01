# Harmony 1.0

First stable version. Android versionName **1.0**, versionCode **110**: higher than
every earlier build, including the earlier 1.0 builds (100 to 109), so it installs as an
update over any of them. Library, playlists and settings are kept.

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
  - Tick or untick each missing song to choose which ones are downloaded, with
    Select all and Select none. Unticked songs aren't downloaded.
  - Deezer and Apple Music are used with timeouts, retries and fallback. Offline, you
    can still build a playlist from your library.
- **SpotiFLAC search** asks SpotiFLAC's own providers first, including Tidal. It then
  tries several phrasings on Deezer, then Apple Music, so titles with hyphens or
  missing diacritics are found.
- **SpotiFLAC engine 4.9.6** (was 4.9.5): stalled source resolution times out, a provider
  asking for verification pauses fallback instead of counting the track as unavailable,
  better matching of artist order, capitalization and title annotations, and ordinary
  lossless downloads no longer pick spatial audio by accident.
- **A provider asking for verification no longer stops the download.** Since engine 4.9.6
  each provider's session is checked first; a provider that asks for verification is now
  set aside and the others are tried. Its challenge is shown only if none of them works.
- **SpotiFLAC no longer hangs on "Resolving".** Selecting a result waits at most 8 s for
  Deezer's details and a download waits at most 15 s for song.link; past that Harmony
  continues with the data it already has.
- **Downloads.** Before a batch starts, Harmony checks that the provider is ready.
  Downloads show per-song progress. Albums and selected tracks can be downloaded
  through SpotiFLAC or Soulseek.
- **FLAC compatibility fix.** FFmpeg runs from its own verified library set.
- **Smart Shuffle** reshuffles the queue correctly.
- **Crossfade** no longer goes silent for half a second when the fade ends: the
  next song keeps playing while the player takes over.
- **Where the music is playing.** Now Playing shows an icon for the kind of device:
  earbuds, headphones, neckband, wired earphones, speaker, car stereo, hearing aid,
  Android Auto or the phone itself. Harmony guesses the kind from the device; tap the
  line to set it for that device.
- **Closing the app closes the notification.** Swiping Harmony away stops playback and
  removes its notification unless Android Auto is connected. Before, the system's media
  controls, Bluetooth or a watch could be mistaken for the car, leaving music and a
  notification that could not be dismissed.
- **Notification opens the app.** Tapping the playback notification or the lock-screen
  player brings Harmony to the front, or starts it if it was closed.
- Redesigned **Home** and **playlist detail** screens.
- Redesigned **album page**: the cover as a record that turns while the album
  plays, a link to the artist, a quality badge (lossless, bit depth and sample
  rate), tracks in album order split by disc, a line for each track missing from
  your library, "About this album" details and the artist's other albums.
- **YouTube converter fix.** The release build's code shrinker had removed classes the
  YouTube downloader needs to unpack itself, so it could not start ("The free FLAC
  conversion failed"). They are now kept, and the release build checks for them. yt-dlp
  only downloads the audio, and Harmony converts it to FLAC with the same bundled FFmpeg
  that SpotiFLAC uses. Starting, converting and saving each report their own error with
  a Details log. YouTube's bot check is no longer shown as a login problem: no YouTube
  account or Premium is needed.
- **Android Auto.** The session stays alive when the phone app is swiped away.
  Connections are also written to a small diagnostic log, which you can read with
  `adb pull /sdcard/Android/data/com.harmony.app/files/android-auto-log.txt`.

## Limits

The APKs are built and signed by GitHub Actions from this source. Unit tests run
during the build. Behaviour on individual phones and head units is not certified.
Downloads depend on internet access and on provider availability and authentication.
