# Harmony 1.0

First stable version. Android versionName **1.0**, versionCode **130**: higher than
every earlier build, including the earlier 1.0 builds (100 to 129), so it installs as an
update over any of them. Library, playlists and settings are kept.

## Download

- `Harmony-v1.0-arm64-v8a.apk`: for phones (64-bit ARM, Android 10 or newer).
- `Harmony-v1.0-x86_64.apk`: for the Android emulator only.

`SHA256SUMS.txt` and `APK-SIGNATURE.txt` let you check the file and its signing
certificate.

Harmony for Windows is a separate download, on its own release: **Harmony for Windows 1.0**
(tag `desktop-v1.0`).

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
- **Romanian letters in tags.** Titles, artists and albums tagged by older Windows software
  showed up as "ºtefan", "Þara" or "Fãrã", and UTF-8 tags in the wrong frame as "È™tefan".
  Harmony now recognises both mistakes and restores ș ț ă î â (in the correct comma-below
  form), leaving correct text in any language alone. Songs already in the library that show
  the problem are read again on the next scan, keeping their playlists, favourites and
  play counts.
- **Crossfade** ends without a hitch. When the fade finishes, Harmony's main player has to
  take the next song over from the crossfade player. It used to do that before it was
  actually making sound, and up to ~200 ms out of step, which was heard as a short catch
  or skip. Now it waits until the main player is audible, brings it into step within a
  few milliseconds while it is still muted, and only then swaps them. The crossfade
  player also uses the same ReplayGain and EQ, so loudness and tone no longer jump at
  that moment.
- **Crossfade starts on time and without distortion.** The next song used to start loading
  only when the fade was due, so on large files the fade began late and short, and the
  loading happened during the part you hear. Now it is loaded 6 seconds ahead, paused and
  muted, and starts the moment the fade is due. The swap between the two players at the
  end of the fade also used gains meant for two different songs; on two copies of the same
  song they added up to 3 dB too loud, past full scale on loud recordings, which was heard
  as a short distortion. The gains now always add up to the song's own level, and the swap
  takes 80 ms instead of 200.
- **Equalizer: Winamp and Auto.** The Equalizer now has two tabs, Winamp and Auto, under a
  segmented switch. The old Simple and Advanced tabs are gone. If you had set them and never
  touched Winamp, your bands are carried over to Winamp's, so the sound stays the same.
- **Winamp equalizer.** Plays music through Winamp's own equalizer: ten one-octave bands at 60,
  170, 310 and 600 Hz and 1, 3, 6, 12, 14 and 16 kHz, ±20 dB each, plus a preamp. It uses the same
  filters as the eq-xmms reconstruction of Winamp's equalizer, which XMMS and VLC also use, so bands
  overlap and add up the way they did in Winamp. The tab is drawn as two Winamp 2 windows:
  - *Main window*: the song scrolls across a green LCD, with its length, bitrate, sample rate,
    stereo light and the device it's playing on, next to a live spectrum analyser with falling
    peaks. The analyser measures what actually reaches the speaker, and only while you look at it.
  - *Equalizer window*: ON, AUTO (switches on Auto's song-by-song tone), PRESETS and RESET; a black
    display with the real response curve, its glow and a dot on each band; the preamp and ten
    sliders whose LED ladders light green to red. Tap a slider to jump, drag it, double-tap it to
    centre.
  - *Presets*: all 18 Winamp presets as cards, each with its own curve. The one in use glows gold.
  - *Signal*: the preamp, the biggest boost and cut, and the output.
- **Automatic equalizer.** The Auto tab adjusts the sound on its own, on top of Winamp, in four
  parts that can each be switched on alone. A glowing header shows what it's doing: the total curve
  over the live spectrum, and a ring for each part. Each part has its own card, colour and curve:
  - *Clarity*: a model of human hearing listens to the music about 90 times a second, in 24 bands
    spaced the way the ear resolves pitch. It works out which sounds are covered up by louder ones
    nearby (the masking threshold) and which push forward, and moves 24 filters to match as the music
    plays: it brings out what is covered up but still there, and holds back resonances, sudden
    harshness and boom. Deep-buried sounds and what you couldn't hear anyway, like the empty top of a
    lossy file, are left alone, so hiss isn't brought up. The level is matched by loudness, so Clarity
    doesn't win by being louder. Five controls: *Recover*, *Tame*, *Bias* (leans towards one or the
    other), *Brighten* (darker or brighter around 1 kHz) and *Boost* (output level), plus six starting
    points (Gentle, Balanced, Detail, Smooth, Warm, Airy). A live view shows what the model hears, the
    masking line, and what it brings out in cyan and holds back in pink. Clarity listens to the music
    inside the app and doesn't use the microphone.
  - *Song by song*: Harmony listens to each song as it plays and evens out its tone towards a
    typical well-mastered record, at most a few dB (a little body for thin recordings, a little less
    edge for harsh ones). It fades in over the first seconds, and a song played again starts with its
    correction right away.
  - *Speaker & room*: Measure plays about 8 seconds of pink noise through the speaker and listens with
    the microphone, then takes out what the speaker and the room add or swallow. Each speaker keeps
    its own measurement and is used again whenever it plays. Deep bass is never boosted, so a small
    speaker isn't pushed into distortion.
  - *Noise around you*: on headphones, the microphone listens to your surroundings while music plays
    and lifts what the noise covers up (bass on a bus, the middle in a crowd), rising in about 2 s and
    falling back in about 6 s. A gauge shows how loud it is around you, from a quiet room to a bus.
    A notification shows while this is on; nothing is recorded or sent. The equalizer also now switches on and off at once,
  instead of at the next seek or song.
- **New mini player.** The bar above the navigation is now a card with the record half out of
  it, turning while the music plays, coasting to a stop on pause and spinning on to the next
  song. It shows where the music is playing (AirPods, phone speaker, car…), the song, a
  rainbow wave that runs along as it plays (drag it to seek), shuffle, previous, play/pause,
  next and repeat, and the next songs as chips you can tap to play. On a light page the card is
  dark, on a dark page it is light. Tap it or swipe up to open Now Playing, swipe sideways to
  skip.
  - *Opening and closing the player is animated.* Swiping up lifts the card with your finger
    while the record grows and rises ahead of it; let go high enough (or flick) and it carries
    on into Now Playing, which grows up out of it; otherwise it springs back. Closing Now
    Playing shrinks it back down, and the card drops into place from above with a little
    bounce, its record spinning in and the controls settling in after it.
- **Where the music is playing.** Now Playing shows an icon for the kind of device:
  earbuds, headphones, neckband, wired earphones, speaker, car stereo, hearing aid,
  Android Auto or the phone itself. Harmony guesses the kind from the device; tap the
  line to set it for that device.
- **Harmony Connect: play on your PC.** Tap where the music plays, under the title in Now
  Playing: **Play on** lists the computers running Harmony for Windows on the same Wi-Fi.
  Choose one (the first time, type the four-digit code it shows) and the song carries on
  through the computer's speakers from the same place. The phone stays the remote and keeps
  the queue: play, pause, skip and seek from the app, the notification, the lock screen,
  your headphones or the computer, and the phone's volume buttons turn the computer up and
  down. The computer is told what plays next, so it goes straight on to the next song
  without a gap and its next button acts at once; the phone follows it. A Wi-Fi hiccup
  doesn't end it (the computer keeps playing; only half a minute of silence brings the
  music back to the phone), and the phone stays on the Wi-Fi through a pause. If music
  from the computer's own library takes over, the phone shows its song paused and play
  brings it back. Choose **This phone** to bring the music back, paused where it was.
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
- **Home, redesigned.**
  - The header shows the date, the greeting and a line about right now ("2,418 songs ready when you are"), with a warm glow behind it.
  - The song in the player gets an immersive card: a dark stage lit by its own cover's colours (Android 12+), a large cover, album and year, a pill with the sound quality (LOSSLESS · FLAC 24/96), its place in the queue, and the time played next to the progress bar.
  - Smart Shuffle is a bright card that says how many songs it picks from.
  - "Your library" shows songs, albums, artists and playlists as four coloured tiles.
  - The cover rows carry more detail: each song's length and a HI-RES tag, the number of songs on each playlist, and NEW on recently added albums.
  - Discover is a colourful banner with an Explore button.
  - The tools each have their own colour, and the page ends with Harmony's sign-off.
- **Playlist page, in much more detail.**
  - The header sits on a wash of the playlist's own cover colours (Android 12+), with two more records peeking out behind the cover.
  - A chip shows when the song playing comes from this playlist.
  - Four tiles count its songs, length, artists and albums.
  - An "About this playlist" card shows the sound quality (Hi-Res, lossless and lossy in one bar, and the best file in it), the main genres with their share, and a chart of the years its songs come from.
  - "Most here" lists the artists with the most songs.
  - Each song shows its place in the list, its album and year, its length and a quality badge (HI-RES, LOSSLESS or the bitrate). The song playing gets moving bars and a highlight.
  - The list ends with a summary and the date the playlist was made.
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
