# Phase 4: Implementation Status

This document records what the code does now, against `09-handoff.md`. No
ViewModel, repository, service, worker or Room entity was changed. Every new
screen calls functions that already existed.

## Built

| Area | What changed | Where |
|---|---|---|
| **Tokens** | Colour (`Color.kt`), type (`Type.kt`, Bricolage Grotesque + Figtree, variable, OFL) and motion (`Motion.kt`, with reduced motion); the theme (`Theme.kt`) | `core/ui/theme` |
| **Compat** | The old `Catalog` / `CyberGlass` / `EditorialPalette` names resolve from the room, so every screen not yet rebuilt shows the new palette and fonts | `HarmonyCatalog.kt`, `CyberGlassColors.kt` |
| **Components** | `RoomButton`, `RoomChip`, `StatusLine`, `FormatTag`, `ActivityPill`, `RoomIconButton`, `RoomTopBar`, `RoomSegmented`, `RoomSectionTitle`, `RoomEmptyState`, `RoomCard` | `core/ui/room` |
| **Shell** | Listen · Library · Add (bar on phones, rail with Sound and Settings on tablets). The Activity pill, Sound and Settings are in every top bar. The Activity sheet replaces the download banner. Fade-through transitions. | `app/navigation` |
| **Listen** | Replaces Home: the playing or paused card, Mix my library, moods (the mood is applied before the mix starts), Recently played, Made for you, Your playlists, New for you, and the empty library | `feature/home/ListenContent.kt` |
| **Mini player** | The dark record block: name at 22 sp, artist or mix status, play at 56 dp, progress line. One node to TalkBack with Play/Pause, Next and Previous; tap or swipe up to open; swipe sideways to skip. | `feature/player/MiniPlayerCard.kt` |
| **Library** | Top bar, plus a segmented control Songs · Albums · Artists · **Playlists** (Playlists embedded without its own title) | `feature/library`, `feature/playlists` |
| **Add** | One search (it opens the sources page with the query), four doors, and In progress (the same list as Activity). The doors stack at large text. | `feature/downloads/AddScreen.kt`, `ActivityCenter.kt` |
| **Now Playing** | The record is unchanged in motion and gestures. A tap plays or pauses, TalkBack has its actions, and it stands still under reduced motion. Below the title, one line holds the reason and the format tag, and tapping it shows the format card. Steering chips (Calmer, Livelier, Mood, Tune) on tall screens; **Steer** in the action row on short screens and in landscape, with a steering sheet that offers every mood and "Ease into it". Transport reading order: previous, play, next, shuffle, repeat. At large text the page scrolls and the actions wrap to two rows. | `feature/player` |
| **Winamp EQ** | Looks and works exactly as before. TalkBack now gets ten real sliders over the drawn ones ("62 hertz, plus 3 decibels", ±1 dB). | `feature/equalizer/WinampLiveEqualizerScreen.kt` |
| **Strings** | Every new string is in resources, in English and Romanian (`values-ro`), with plurals | each module's `res/` |

## Tests

| Test | Covers |
|---|---|
| `ListenInteractionTest` | Mix, mood, pause, resume, Made for you, empty library, headings |
| `HomeDesignShots` | Listen in light, dark, 200% text, the resume card and the empty library |
| `AddDesignShots` | Add in light, dark and 200% text; where each door leads |
| `NowPlayingScreenShots` | Portrait, short phone, landscape (two sizes) and 200% text, light and dark |
| `PlayerAccessibilityTest` | The mini player as one node with its actions; the record's tap and actions |

## Rebuilt in the second pass

| Area | What changed | Where |
|---|---|---|
| **Get music** (sources page) | Songs · Albums · YouTube link on one segmented control. The source is a "From: SpotiFLAC ▾" line with a sheet to change it. SpotiFLAC results have Get on every row. The provider check shows only when it needs you. Problems come in three parts, with Details behind a button. Soulseek: sign in, then the same search and results, with options and folders folded away. YouTube: paste, check the title, convert, with the lossy note. A YouTube link typed on Add goes straight to the converter. | `GetMusicContent.kt`, `SoulseekSongs.kt`, `DownloadsScreen.kt` |
| **Album page** | Cover, status and one main action (Get N songs, Pause, Resume, Retry). Source, format and Wi-Fi only sit under a "From:" line. Each track says its state in words. Album search gives each edition Get album and Choose songs. | `AlbumDownloadScreen.kt`, `AlbumSearchSection.kt`, `AlbumDownloadsShelf.kt` |
| **From Spotify** | A 3-step setup guide: create an app, copy Harmony's address, paste the Client ID. Then a link field or your playlists. For a chosen playlist: the counts in three numbers, source and format chips, and one button. | `SpotifyPlaylistTransferScreen.kt` |
| **Queue** | The room's type throughout (nothing under 12 sp). Sections read Up next, You added and From the mix. Keep and In your library; a clearer empty state. | `QueueSheet.kt` |
| **Settings** | Sections Look, Playback, Mixes, Library, Adding music, Tools, Privacy, About. The whole row toggles. Sliders with words at both ends. English and Romanian. | `SettingsScreen.kt`, `SettingsRows.kt` |
| **Discover** | The 3-step builder on the room's type scale, with amber actions and soft-amber chips. The unreachable taste-round and album screens are removed. | `feature/discover/ui` |

Removed legacy pieces:

- the Cyber-Glass album components;
- the old download source rail and selector;
- the old YouTube panel;
- the Soulseek connect card;
- the notification pill;
- the download banner;
- the old Discover screens.

**Note on Spotify.** Spotify's Web API needs the connected account even for a
pasted playlist link, so "paste a link without an account" (spec §2.11) is
not possible without a backend change. The guided setup comes first instead.

## Not yet rebuilt

- **Library internals:** song rows, album and artist detail. They have the
  new palette and type, but their layout is the old one.
- **Tag editor, FLAC check, Tidy up, Identify:** same as the Library
  internals.
- **Sound** (equalizer) gains no "Simple" segment yet; the Winamp screen is
  preserved by requirement.
- **Album tint** and the "Moods are almost ready" feed.

## Still to verify on a phone

There is no emulator here, so these have only been checked in Robolectric:

- a manual TalkBack pass;
- Android Auto;
- a real 200% font setting;
- Material You colours.
