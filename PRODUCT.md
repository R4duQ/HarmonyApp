# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

The owner and a small circle of friends, who install signed APKs by hand
(see `Instaleaza-Harmony-pe-telefon.cmd`). Romanian speakers who also read
English. The interface is in English; the screens rebuilt in the Listening
Room redesign (docs/redesign/) also ship in Romanian, and the rest follow as
their strings move to resources. They own their music as files,
largely lossless (FLAC, hi-res up to 24/96), and build that collection
themselves.

Three situations dominate, by the owner's account:

- **On the move / in the car:** quick glances, one hand, Android Auto.
- **At the desk while working:** phone on the table, music in the
  background, occasional reach to skip, queue or adjust.
- **Collecting sessions:** searching, downloading, tagging, building and
  importing playlists, sitting down with the phone for a while.

## Product Purpose

A music player for a library you own: play local files well (gapless,
lossless, a real equalizer), and grow the library deliberately (discover,
download, import). Success is a player trusted with a lossless collection
that also makes adding to it painless.

## Positioning

Not a streaming client. Harmony plays files the listener owns, shows what
they actually are (codec, bit depth, sample rate, bitrate), shapes them with
a native Rust equalizer and perceptual "Clarity Live" bands, and builds mixes
(Smart Shuffle) and discovery playlists from on-device analysis of the
listener's own library and history, with the reason for every pick shown.

## Operating Context

- Android phones and tablets; Android Auto; a companion Windows desktop app
  that can link to the phone (shared queue, PC-to-phone transfer).
- Downloads through configured SpotiFLAC, Soulseek and a YouTube converter;
  Spotify playlist import; song recognition.
- Long-lived sessions in Downloads and Discover; short glances everywhere
  else.

## Capabilities and Constraints

- Jetpack Compose, Material 3 underneath, Kotlin; native audio and analysis
  in Rust (`native/`).
- Library: songs, albums, artists, search, tag editor, FLAC check.
- Player: Now Playing, queue with play-next, mini player, output device
  picker ("Play on"), Smart Shuffle, waveform seek.
- Playlists with generated covers; Discover in three steps (Preferences,
  Songs, Playlist) with previews; Downloads (albums and tracks); Equalizer
  (graphic, Winamp-style, Clarity Live); Settings.
- Navigation: Listen · Library · Add, with Activity, Sound and Settings in
  every top bar (docs/redesign/05-screens-and-flows.md).
- Light and dark themes are both required.

## Brand Commitments

- The name **Harmony** and its launcher icon stay.
- The **vinyl record sliding out from behind the cover** (Now Playing,
  album grids) stays as a signature.
- The **Winamp-style equalizer screen** stays as it is.
- Both **light and dark** themes stay.
- Voice: plain, literal words over jargon ("Clarity Live in plain words",
  "Save to library"); technical audio facts are shown, not hyped.

## Evidence on Hand

- Rendered screens of 1.0 (invented songs, generated covers) in
  `website/src/assets/img/`.
- Roborazzi theme showcase: `app/src/test/kotlin/com/harmony/app/ThemeShowcaseScreenshotTest.kt`.
- No real user research, store listing, reviews or usage numbers exist; do
  not invent them.

## Product Principles

1. **The file is the truth.** Show what the audio really is; never decorate
   a fact into a claim.
2. **Explain every automatic choice.** Mixes and discoveries say why.
3. **Glanceable first, deep on demand.** The car and the desk need one look;
   collecting sessions can open everything.
4. **One hand, one thumb.** Primary controls sit within reach.

## Accessibility & Inclusion

Readable at a glance in a moving car and in bright daylight: text and
controls must hold contrast in both themes; touch targets at Android's 48dp
minimum. Romanian and English strings must both fit.
