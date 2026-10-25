# Harmony 1.0

First stable version. Android versionName **1.0**, versionCode **142**: higher than
every earlier build, including the earlier 1.0 builds (100 to 141), so it installs as an
update over any of them. Library, playlists and settings are kept.

## Download

- `Harmony-v1.0-arm64-v8a.apk`: for phones (64-bit ARM, Android 10 or newer). The stable
  build: downloads run on SpotiFLAC 4.9.6, as before.
- `Harmony-v1.0-mornye-arm64-v8a.apk`: the same app for phones, with downloads running on
  Mornye 5.2.5 instead (see below). Try it if you want the newer download runtime.
- `Harmony-v1.0-x86_64.apk`: for the Android emulator only (stable build).

The two phone APKs are the same app with the same version, so either one installs over
the other, in both directions, and keeps your library, playlists and settings. Only the
downloads differ.

### Stable or Mornye

SpotiFLAC, the download runtime behind Harmony's lossless downloads, has become
**Mornye**, and from version 5 its Go runtime was replaced by a new one written in Rust.
The stable build keeps SpotiFLAC 4.9.6, which still runs every current download
extension. The Mornye build uses Mornye 5.2.5 with the same extensions (Tidal, Qobuz,
Deezer, Amazon), the same requests and the same answers, so downloads look and behave
the same. Differences you may notice:

- Sign-in and verification pages return to the app on `mornye://` links instead of
  `spotiflac://`. If the Mornye app itself is also installed, Android may ask which app
  should open them: choose Harmony.
- Mornye looks up network addresses with its own encrypted DNS.
- Extension sign-ins are stored separately per runtime, so after switching builds a
  provider may ask you to verify once more.

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
- **A new Smart Shuffle that listens.** Smart Shuffle now runs on Harmony's own engine, written
  in C++, entirely on the phone: no internet, no account, and no genre tags. It knows each song
  by how it sounds.
  - *How it hears a song*: in the background, while nothing is playing, Harmony measures every
    song on 24 points: the level of ten octave bands from 31 Hz to 16 kHz, how much each band
    moves, the sub-bass punch, brightness, dynamics and how often it pulses. Songs not measured
    yet borrow an estimate from the older analysis until their turn comes.
  - *The vibe of the session*: as you listen, Harmony keeps a running picture of what the
    session sounds like. A song you hear past half pulls it toward that song; a song you skip
    in its first fifth pushes it away and the songs lined up after it are replaced at once; a
    song you pick yourself or swipe in as Play next sets the direction.
  - *How songs are chosen*: each song is scored on how well it fits the vibe, the songs you
    usually play after the one before (learned from your own listening), how often you finish
    it, and a bonus for songs you have not heard in a while. Songs heard in the last few hours
    wait their turn. The next eight are drawn by weighted chance, so the best fits usually win
    but never always, then spread so the same artist doesn't come back within three songs, and
    ordered so each song flows into the next.
  - *It checks itself*: every time it builds the queue, Smart Shuffle checks that artists are
    spaced out, that a skip really moved it away from what you skipped, that the queue is not
    all the same sound, that its numbers are sound, and that no song jumps harshly from the one
    before. When a check fails it repairs the queue itself.
  - *Journeys* toward a mood now travel through the same 24-point space, a song at a time.
  - Energy, mood and the style sliders work as before.
  - *Diagnostic Brain*: in the Smart Shuffle sheet, Show brain HUD opens a panel over the
    player with the vibe as 24 live bars, a light per check (green passed, amber repaired, red
    could not be repaired) and, under each upcoming song, why it was picked ("94% Bass/Vibe
    Match", "Markov Favorite Transition", "Bandit Rediscovery Gem", "Post-Skip Pivot"). Run
    self-test plays a simulated session on 100 virtual songs and shows a pass or fail for each
    step. Developers can follow every queue in Logcat under `SHUFFLE_BRAIN`.
  - The first time, measuring the whole library takes a while; Smart Shuffle works from the
    start and gets better as more songs are measured.
- **Romanian letters in tags.** Titles, artists and albums tagged by older Windows software
  showed up as "ºtefan", "Þara" or "Fãrã", and UTF-8 tags in the wrong frame as "È™tefan".
  Harmony now recognises both mistakes and restores ș ț ă î â (in the correct comma-below
  form), leaving correct text in any language alone. Songs already in the library that show
  the problem are read again on the next scan, keeping their playlists, favourites and
  play counts.
- **Crossfade with two players, without a hitch at the end.** Harmony now has two players
  that take turns. While a song plays, the other player loads the next one 6 seconds before
  the fade, paused and muted, so its file is open and its first audio is ready. When the fade
  is due it starts at once, and the two are faded against each other. When the first song
  ends, the second player simply carries on: it is already playing the next song at full
  volume, so nothing is reloaded, sought or realigned at that moment. Before, the main player
  had to jump onto the song the crossfade player was playing and be brought into step with
  it, and that last step was where a short catch could be heard. Each player has its own
  EQ engine (with ReplayGain in it), so loudness and tone stay the same, and the visualiser
  follows whichever one is playing. Pausing, seeking, a call or a change to the queue that
  replaces the next song calls the fade off cleanly; adding songs to the queue during a fade
  does not. Shuffle uses one order for both players, so the next song is the same whichever
  one plays.
- **New equalizer engine.** The equalizer now runs on Harmony's own engine, written in C++ and
  working in 32-bit float at the file's own sample rate (44.1 to 384 kHz), so 24-bit and hi-res files
  reach it whole instead of being cut to 16 bits first. The old equalizer and the old Auto (song tone,
  speaker & room measurement, noise around you, Clarity) are gone, and so is the microphone service
  they used. Your curve is carried over to the new bands.
  - *Ten bands*: 31 Hz to 16 kHz, ±12 dB each, a shelf at each end and peaks between them that narrow
    as they rise so neighbours don't pile up, plus a preamp.
  - *Never clips*: the engine makes its own headroom as you boost (see *Live preamp*), moves every
    change smoothly so dragging a slider never clicks, then a soft saturator and a 2 ms look-ahead
    limiter keep peaks under −0.15 dBFS (−0.4 dBFS on Bluetooth, −0.05 dBFS on a USB DAC).
  - *Knows where it plays*: phone speaker, wired, Bluetooth or USB DAC. It filters out what that output
    can't play (below 90 Hz on the phone speaker, 24 Hz on Bluetooth, which also gets a little extra
    room for its codec, 18 Hz wired and 10 Hz on a DAC).
  - *AUTO LIVE*: Auto works only from what the music sounds like right now, never from tags or
    genres. The engine hears the music in the ten bands (about the last 1.2 seconds) and compares
    its balance with a target for the output (a warm −4.5 dB/octave curve on headphones, Bluetooth
    and DACs; a midrange curve on the phone speaker). It lifts thin sub-bass and air by up to 4.5 dB
    and takes out boxy mid-bass and harsh upper mids by up to 4.5 dB, gliding on top of your
    sliders. In silence and quiet intros it holds still, so it never lifts noise. Heavy kicks pull
    the bass boost back and sharp sibilance the 4–8 kHz boost, within milliseconds.
  - *Live preamp*: the headroom the EQ takes follows the music: a big bass boost only lowers the
    level while there is bass to boost, so vocals and acoustic passages stay as loud as they can.
  - *Live window*: one 60 fps display with the 32-bar spectrum behind ten sliders. Each slider has
    your thumb and a glowing "ghost" thumb that moves on its own to where the engine actually is,
    with a curve through each; EQ ON, AUTO LIVE, the live preamp, FLAT and a badge for the output
    (for example USB DAC • 96kHz / 32-bit Float) sit on top.
  - *Spectrum*: it comes from the engine's own FFT at 60 frames a second, timed to what you hear. The
    Now Playing visualiser uses it too.
  - *Bit-perfect USB* (Android 14 and newer, with a USB DAC): a switch to send the file to the DAC
    untouched instead of equalizing it.
  - ReplayGain now runs inside the engine, ahead of its limiter, so lifting a quiet record can't clip.
- **No more freezes at a bad spot in a song.**
  - Some files made playback stop or go silent at the same moment every time, until you seeked past it. Each cause is fixed:
    - a decoder buffer that ended mid-frame was never taken, and now always is;
    - corrupt samples (NaN, infinities, absurd values) are repaired before the EQ;
    - the engine heals itself if anything non-finite ever reaches its filters.
  - A corrupt frame now makes playback hop 200 ms past it instead of failing the song.
  - A decoder that fails hands over to another one.
  - MP3s without a proper seek table seek accurately.
  - If playback ever stands still for 3 seconds while it should be playing, it is nudged on by itself.
  - Developers can see each of these in Logcat under `EQ_DEBUG`.
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
  brings it back. The computer is told each song's length, so its progress bar follows
  along. Choose **This phone** to bring the music back, paused where it was.
- **Closing the app closes the notification.** Swiping Harmony away stops playback and
  removes its notification unless Android Auto is connected. Before, the system's media
  controls, Bluetooth or a watch could be mistaken for the car, leaving music and a
  notification that could not be dismissed.
  - *No player left behind.* Harmony no longer offers Android a "resume" player, so on
    phones and tablets that keep media players in quick settings (Samsung's do by
    default) no Harmony card stays there after you close or force-stop the app. A card
    left over from an earlier build goes once you swipe it away (or long-press it and
    choose Hide). Android Auto can still offer the last song when the car connects.
  - *It stays closed.* After you swipe Harmony away, nothing in the app connects to the
    player again on its own; before, a background task or a settings update could
    reconnect, restore the last queue (paused) and bring the notification back. The last
    queue is now only restored when you open the app (or a car connects), so the library
    analysis running in the background no longer makes a paused notification appear.
  - *Keep playing after closing the app*, a new switch in Settings > Playback, is off by
    default. When it is on, swiping Harmony away while music plays leaves the music and
    its notification; a paused player is still closed.
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
