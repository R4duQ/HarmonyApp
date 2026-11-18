# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

Android is the product. Harmony for Windows (Compose Desktop, `desktop/`) and Harmony
for iOS (SwiftUI, sideloaded, `ios/`) follow the phone app's look and behavior rather than
defining their own. The website (`website/`) is a separate single-page product site.

## Users

Primarily the developer: Harmony is their own music player first. Friends are the second
audience; they install it from GitHub Releases (Android APK, Windows MSI/setup, an unsigned
iOS IPA via Sideloadly or AltStore). There is no store-driven or anonymous mass audience
to design for.

The job: play a personal library of music files on the phone (and the computer), find new
songs that fit, get them as files, and keep playlists in order.

## Product Purpose

A music player for music files you keep yourself. It plays the local library, shapes the
sound, suggests what to hear next, and brings new music in as files through downloads and
Spotify playlist import. Success is the developer reaching for Harmony instead of a
streaming app, and friends being able to install and use it without help.

## Positioning

Harmony works on files the listener owns and keeps everything on their devices: no
account, no Harmony server, no analytics, no ads. Its intelligence runs on the device
(Rust song analyzer, Smart Shuffle, equalizer and Clarity in `native/`), and the phone can
play through the computer's speakers over the home network with Harmony Connect, without
any cloud in between.

## Operating Context

- Phone in everyday listening: Home, Library, Playlists, Discover, Downloads, Now Playing,
  mini player, notification, lock screen, headphone buttons, Android Auto.
- Computer: Windows app reads music folders and acts as a Harmony Connect speaker; the
  phone stays the remote and keeps the queue. Pairing uses a four-digit code.
- Getting music in: SpotiFLAC/Mornye providers (FLAC CD, FLAC up to 24/96, MP3 320),
  Soulseek, full-album and selected-track downloads, Spotify playlist import, song
  recognition (Shazam fingerprint).
- Distribution and updates: GitHub Releases; updates install over the previous build when
  signed with the same key. Install guides are written in Romanian; the app UI is English.

## Capabilities and Constraints

- Local library with tags and covers; playlists; queue with history and reasons; tag editor.
- Sound: equalizer (Winamp-style bands and presets, bass/treble, stereo width), even
  volume / ReplayGain, crossfade up to 12 s, Clarity Live (perceptual auto-EQ).
- Discover: a three-step flow (Preferences, Songs, Playlist) built from listening history,
  favorites and choices; public Deezer, Apple Music and MusicBrainz data as sources.
- Smart Shuffle and Smart Mix; online mixes that play online songs only.
- FLAC check: spectrum view of whether a FLAC looks genuinely lossless, with rate and depth.
- Themes: light, dark, follow system, true black, wallpaper colors on Android 12+.
- Android 10+ (minSdk 29), arm64-v8a only for phone releases.
- Online features run only when the user uses them; the privacy policy
  (`docs/PRIVACY.md`) lists exactly what each one sends.

## Brand Commitments

- Name: Harmony.
- No account, no server of its own, no analytics, no ads; everything the app keeps stays on
  the device it was made on. Design and copy must not imply sign-in, cloud sync, or
  tracking that does not exist.
- Existing marks: `desktop/icon.svg`, `desktop/icon.ico`,
  `desktop/src/main/resources/harmony-icon.png`, `desktop/msix/Assets/`.
- Required third-party disclaimers (`THIRD_PARTY_NOTICES.md`): not affiliated with The
  Shfl, Shazam/Apple, Deezer, or the download providers; their names and artwork remain
  their owners'.

## Evidence on Hand

- Website screenshots are rendered from Harmony's own Compose code with invented songs,
  artists and generated covers (`website/src/assets/img/`, `website/src/content/screens.mjs`);
  the site states that the music shown is sample content.
- Release notes and verification records: `docs/RELEASE-v1.0-STABLE.md`, `docs/verification/`,
  `docs/history/`.
- No testimonials, user counts, reviews, press, or store ratings exist. Do not invent them.
- Real album artwork is not licensed for marketing use; do not put it in screenshots or
  promotional surfaces.

## Product Principles

1. Built for one real listener first. Choices are judged by whether they make the
   developer's own daily listening better, then by whether a friend can follow them.
2. The device is the whole system. No feature may depend on an account or a Harmony server.
3. The phone leads. Desktop and iOS mirror the Android app instead of diverging.
4. The network is used on request. Online lookups happen when the user acts, and the user
   can see what was sent where.
