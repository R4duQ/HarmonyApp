# Harmony 1.0

First stable version. Android versionName **1.0**, versionCode **118**: higher than
every earlier build, including the earlier 1.0 builds (100 to 117), so it installs as an
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
- **Recognize** names the song playing near you with Shazam, including songs heard live,
  across a room or through a crowd. Tap the button on Home or in Downloads: a glowing
  orb on a moving aurora listens, its ring of bars follows the sound, and the five
  attempts light up as it goes; a match takes over the stage with its cover. Harmony
  listens for up to 20 seconds and asks Shazam after 4, 8, 12, 16 and 20 seconds, each time
  with the latest 12 seconds, so talking or applause at the start drops out; it stops at
  the first match. It records without the phone's voice noise filter (which treats music
  as noise), brings quiet recordings up to a normal level, and keeps going if one attempt
  loses the connection. No account or key is needed. Download sends the song straight to
  a SpotiFLAC search, My library looks it up in your files, and the last 30 recognitions
  are kept. Only a fingerprint of the sound is sent, never the recording. This uses
  Shazam's private app interface, so Shazam can limit or change it.
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
- **Crossfade** ends without a hitch. When the fade finishes, Harmony's main player has to
  take the next song over from the crossfade player. It used to do that before it was
  actually making sound, and up to ~200 ms out of step, which was heard as a short catch
  or skip. Now it waits until the main player is audible, brings it into step within a
  few milliseconds while it is still muted, and only then swaps them. The crossfade
  player also uses the same ReplayGain and EQ, so loudness and tone no longer jump at
  that moment.
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
- **Tablet layout.** On tablets a glass navigation rail on the left replaces the bottom
  bar; swipe up or down on it to move between sections. Pages keep a readable width, and
  Now Playing stays compact upright and side by side in landscape. Phones are unchanged.
- **Vinyl mode** in Library (the record button on Songs) is redesigned. Each song is now a real
  record, with grooves, track gaps and its artwork as the label, riding a lit rail. The centre
  record grows and spins under a fixed sheen. Its card shows the album, the audio quality
  (HI-RES 24/96, LOSSLESS 16/44.1 or the bitrate) and the length, with a round Play button.
  Delete moved into a menu, away from Play. A counter shows where you are ("8 of 1,240"), and
  the card is now readable in dark mode.
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
