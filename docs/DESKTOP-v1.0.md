# Harmony for Windows 1.0

Harmony on your computer: play the music in your folders, and play the music on your phone
through the computer's speakers with **Harmony Connect**.

## Download

- `Harmony-Windows-1.0.x.msi`: the installer (Windows 10 or 11, 64-bit).
- `Harmony-Windows-1.0.x-setup.exe`: the same, as a setup program.

The phone app is a separate download, on its own release: **Harmony 1.0** (tag `v1.0`).
Harmony Connect needs the phone at build 128 or newer (129 or newer for songs running
straight into each other and next acting at once on the computer).

Each new build installs over the last one, into your own app folder (no administrator
needed).

**Can't uninstall?** An earlier build let you pick the folder; installed on another drive
than C:, removing it from Settings fails with "Could not set file security ... Config.Msi
... Error: 5". Download `Dezinstaleaza-Harmony.cmd` from this release and double-click it:
it asks for administrator rights and removes Harmony. Then install the new build. The installer isn't signed yet, so Windows may
say "Windows protected your PC": choose **More info**, then **Run anyway**.

## What's in it

- **Your music.** Choose your music folders; Harmony reads every song in them (FLAC, MP3, AAC,
  ALAC, OGG, Opus, WAV, AIFF, WMA, APE, WavPack, DSD and more) with its tags and cover, and
  remembers them, so it opens at once next time. Songs, Albums and Artists, with search.
- **The player.** The record turning while the music plays, the rainbow wave you can click or
  drag to seek, shuffle, repeat, what plays next, volume, and the sound quality of every song
  (FLAC 24/96, MP3 320). Buttons and seeking act at once, and one song runs straight into the
  next without a gap: the next one is made ready while the current one is still playing.
- **Equalizer.** See it work: a live analyzer shows the music as you hear it, how it came in,
  and your curve over both. Hold **Compare** to hear the music without the equalizer for as
  long as you hold it.
  - Winamp's equalizer, with its 18 presets.
  - **Tone:** bass and treble you can turn up to 12 dB either way, and stereo width (from mono
    to twice as wide).
  - **Even volume** brings quiet songs up and loud ones down, slowly, so you don't reach for
    the volume between songs.
  - **Clarity**, the same as on the phone.
- **Harmony Connect.** Play your phone's music on the computer:
  1. Phone and computer on the same Wi-Fi.
  2. On the phone, open the song that's playing and tap where it plays, under the title
     ("Phone speaker", your headphones): **Play on** lists the computers on the Wi-Fi.
     Choose yours. (Not listed? Add it by the address shown on its Connect page.)
  3. Type the four-digit code shown on the computer's **Connect** page (only the first time).

  The phone stays the remote and keeps the queue: play, pause, skip and seek from either side,
  from the app, the notification, the lock screen or your headphones' buttons. The phone's
  volume buttons turn the computer up and down. Choose **This phone** in Play on to bring
  the music back, paused where it was.
  The song is fetched from the phone over the Wi-Fi while it plays, with the next one, so
  seeking and the next song don't wait; the copies are kept only in a temporary folder and
  deleted when the phone lets go and when Harmony starts. With a phone at build 129 or newer
  the computer also goes straight on to the next song by itself, without a gap, and next
  pressed on the computer acts at once; the phone follows.
  The progress bar knows each phone song's length, and the player shows its quality
  (FLAC 16/44.1) as for your own songs. If the phone's Wi-Fi drops mid-song, the computer starts the song again where it stopped
  (from its copy when it has one). Play music from the computer's own library while the
  phone is connected and the phone's song is set aside, paused; play on the phone brings it
  back.
  If Windows asks whether Harmony may use the network, allow it on private networks.

Harmony for Windows uses FFmpeg (LGPL build, from BtbN/FFmpeg-Builds) to read and decode
audio; its licence is installed next to it.
