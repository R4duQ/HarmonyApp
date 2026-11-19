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

## Not yet rebuilt

These screens wear the new palette and fonts through the compat layer, but
keep their previous layout:

- **Discover:** taste rounds and the playlist builder. The icon-only actions
  should become labelled buttons (spec §2.12).
- **Downloads sources page:** search results with Get, the three-part errors
  of `06` §7, and the source preferences behind a gear.
- **Spotify transfer:** the 3-step Client ID guide, and "paste a link" as the
  default.
- **Settings:** the sections of spec §2.16.
- **Sound "Simple" segment:** presets plus Auto, remembered segment (U5).
- **Queue sheet:** grouping by origin with the room's look.
- **Tag editor, FLAC check, Tidy up, album and artist detail.**
- **Strings of the older screens** are still English literals, so the
  Romanian translation covers only the new screens.
- **Album tint** (`04` §1.2) is not implemented.
- **"Moods are almost ready"**: the state exists in the UI, but nothing
  feeds it, because analysis progress is not in `HomeUiState`.

## Still to verify on a phone

There is no emulator here, so these have only been checked in Robolectric:

- a manual TalkBack pass;
- Android Auto;
- a real 200% font setting;
- Material You colours.
