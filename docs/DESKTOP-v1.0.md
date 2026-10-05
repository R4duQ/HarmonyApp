# Harmony for Windows 1.0

Harmony on your computer: play the music in your folders, and play the music on your phone
through the computer's speakers with **Harmony Connect**.

## Download

- `Harmony-Windows-1.0.x.msi`: the installer (Windows 10 or 11, 64-bit).
- `Harmony-Windows-1.0.x-setup.exe`: the same, as a setup program.

The phone app is a separate download, on its own release: **Harmony 1.0** (tag `v1.0`).
Harmony Connect needs the phone at build 128 or newer.

Each new build installs over the last one. The installer isn't signed yet, so Windows may
say "Windows protected your PC": choose **More info**, then **Run anyway**.

## What's in it

- **Your music.** Choose your music folders; Harmony reads every song in them (FLAC, MP3, AAC,
  ALAC, OGG, Opus, WAV, AIFF, WMA, APE, WavPack, DSD and more) with its tags and cover, and
  remembers them, so it opens at once next time. Songs, Albums and Artists, with search.
- **The player.** The record turning while the music plays, the rainbow wave you can click or
  drag to seek, shuffle, repeat, what plays next, volume, and the sound quality of every song
  (FLAC 24/96, MP3 320).
- **Equalizer.** Winamp's equalizer, with its 18 presets, and Clarity, the same as on the phone.
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
  The song is streamed from the phone as it plays; nothing is copied onto the computer.
  If Windows asks whether Harmony may use the network, allow it on private networks.

Harmony for Windows uses FFmpeg (LGPL build, from BtbN/FFmpeg-Builds) to read and decode
audio; its licence is installed next to it.
