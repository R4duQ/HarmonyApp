# Handoff Spec: Harmony "Listening Room" (Jetpack Compose)

`[ACTIVE SKILL: design:design-handoff]`

**Stack:** Jetpack Compose, Material 3 underneath, Hilt ViewModels. Nothing
below changes a ViewModel, repository, service or worker; every screen is
wired to existing functions, as listed in §5.

**Sources:**

- `04-design-system.md` for the tokens;
- `05-screens-and-flows.md` for the screens;
- `06-ux-copy.md` for the strings;
- `07-design-critique.md` and `08-accessibility-review.md` for the binding
  fixes.

---

## 1. Files

| File | Holds |
|---|---|
| `core/ui/…/theme/Color.kt` | `RoomColors` (every colour token), `LightRoom`, `DarkRoom`, `amoled()`, `withWallpaper()`, `LocalRoom`, `Room` accessor |
| `core/ui/…/theme/Type.kt` | Bundled variable fonts (`res/font/figtree.ttf`, `res/font/bricolage_grotesque.ttf`, plus the OFL texts in `res/raw/`), `RoomType` (named roles), and `HarmonyTypography` (the M3 `Typography` built from the roles) |
| `core/ui/…/theme/Theme.kt` | `HarmonyTheme { }`: picks light, dark or AMOLED, applies Material You to the accent only, and provides `LocalRoom`, the M3 `ColorScheme`, `HarmonyTypography`, the compat `LocalHarmonyColors` and the reduced-motion flag |
| `core/ui/…/theme/Motion.kt` | Duration and easing tokens, plus `rememberReducedMotion()` (reads `ANIMATOR_DURATION_SCALE`) |
| `core/ui/…/room/*` | Components: `RoomButton`, `RoomChip`, `StatusLine`, `FormatTag`, `RoomSegmented`, `RoomTopBar`, `ActivityPill`, `RoomEmptyState`, `RoomSectionTitle` |

### Compatibility

Screens that are not rebuilt in this pass still read the old `Catalog` /
`HarmonyColors` names. Those names now **resolve to the room tokens**:

| Old name | Room token |
|---|---|
| `blue` | `amberText` |
| `onBlue` | `surface` (light) / `onAmber` (dark) |
| `blueContainer` | `amberSoft` |
| `red` | `amberText` |
| `block` | `record` |
| `blockLive` | `recordLive` |

So every screen wears the new palette and fonts at once, with no
section colours left over. The compat layer is deleted when the last
screen moves to `Room`.

## 2. Tokens in code

```kotlin
// Color.kt
@Immutable class RoomColors(
  val isDark: Boolean,
  val ground: Color, val surface: Color, val raised: Color,
  val ink: Color, val muted: Color, val faint: Color, val line: Color, val outline: Color,
  val amber: Color, val onAmber: Color, val amberText: Color, val amberSoft: Color, val onAmberSoft: Color,
  val moss: Color, val slate: Color, val wine: Color, val onWine: Color,
  val record: Color, val onRecord: Color, val onRecordMuted: Color, val recordLive: Color,
)
```

The values are exactly those in `04` §1.2.

The M3 mapping is chosen so that stock components stay accessible:

| M3 role | Room token | Why |
|---|---|---|
| `primary` / `onPrimary` | `amberText` / `surface` in light; `amber` / `onAmber` in dark | M3 uses `primary` as a *text* colour (TextButton, selected tab). In the light theme, amber as text fails at 1.89:1. |
| `primaryContainer` / `onPrimaryContainer` | `amberSoft` / `onAmberSoft` | |
| `secondaryContainer` / `onSecondaryContainer` | `amberSoft` / `onAmberSoft` | NavigationBar indicator |
| `surface*` | `ground`, `surface`, `raised` | |
| `onSurfaceVariant` | `muted` | |
| `outline` / `outlineVariant` | `outline` / `line` | |
| `error` | `wine` | |
| `inverseSurface` | `record` | |

`RoomButton(Filled)` paints `amber` + `onAmber` itself; it is the only
place the amber fill carries a label.

### Type roles

From `04` §2, with the 7 sizes: 34, 28, 22, 18, 16, 14, 12.

| Role | Family, size / line, weight |
|---|---|
| `display` | Bricolage, 34 / 40, 650, opsz 34 |
| `nameXL` | Bricolage, 28 / 34, 650 |
| `nameL` | Bricolage, 22 / 28, 650 |
| `nameM` | Bricolage, 18 / 24, 650 |
| `glance` | Figtree, 18 / 24, 500 |
| `title` | Figtree, 16 / 22, 600 |
| `body` | Figtree, 16 / 24, 400 |
| `label` | Figtree, 16 / 20, 600 |
| `bodySmall` | Figtree, 14 / 20, 400 |
| `labelSmall` | Figtree, 14 / 18, 600 |
| `caption` | Figtree, 14 / 18, 500, `tnum` |
| `micro` | Figtree, 12 / 16, 600, +0.4 tracking |

The M3 `Typography` slots map onto these roles:

| M3 slot | Room role |
|---|---|
| `displaySmall` | display |
| `headlineLarge` | nameXL |
| `headlineMedium` | nameL |
| `headlineSmall` | nameM |
| `titleLarge` | nameL |
| `titleMedium` | title |
| `titleSmall` | labelSmall |
| `bodyLarge` | body |
| `bodyMedium` | bodySmall |
| `bodySmall` | micro |
| `labelLarge` | label |
| `labelMedium` | labelSmall |
| `labelSmall` | micro |

## 3. Components

| Component | Props | States | Spec |
|---|---|---|---|
| `RoomButton` | `text`, `onClick`, `kind` (`Filled`, `Tonal`, `Text`, `Destructive`), `icon?`, `loading`, `enabled`, `prominent` (56 dp) | pressed (ripple, `ink` 12%), focused (2 dp `ink` ring, 2 dp offset), disabled (content at 38%, no fill), loading (spinner replaces the icon; width is locked) | Pill shape. Height 48 dp, or 56 dp when prominent. Horizontal padding 20 dp. Uses `label` |
| `RoomChip` | `text`, `selected`, `onClick`, `leadingCheck` | off (`surface` fill + `line` border); on (`amberSoft` fill + check icon + `onAmberSoft` text, so the state is never shown by colour alone, A23) | 40 dp visible, 48 dp target. Radius 12. Uses `labelSmall`. Semantics: `toggleable` |
| `StatusLine` | `tone` (`Info`, `Success`, `Waiting`, `Error`), `title`, `body?`, `action?` | — | `surface` card, 3 dp leading rule in the tone colour (`slate` for Waiting, `moss` for Success, `wine` for Error), plus an icon. `liveRegion = Polite` |
| `FormatTag` | `label` (e.g. "Hi-Res 24/96", "Lossless", "Online") | — | Radius 4, `line` border, `micro` text. The description reads it out in full words |
| `ActivityPill` | `running`, `needsYou`, `failed`, `onClick` | hidden (all zero), running (amberText dot + count), needs-you (slate), failed (wine) | 32 dp visible, 48 dp target. Its description is the whole summary |
| `RoomTopBar` | `title`, `onBack?`, `actions` | — | Title uses `display` at the top level, or `nameL` with Back on detail pages. Height 64 dp, plus the status bar inset |
| `RoomSegmented` | `options`, `selected`, `onSelect` | — | `raised` track; the selected option sits on a `surface` thumb with an `amberText` semibold label. `selectableGroup` + role Tab |
| `MiniPlayer` | — (via `PlayerViewModel`) | playing, paused, buffering (spinner on play), online (Online tag) | `record` block, radius 20, minimum height 72 dp, horizontal margins 8 dp. Artwork 48 dp, radius 8. Title `nameL` (or `title` when the font scale is above 1.3), artist `glance`, play button 56 dp with a `recordLive` fill and `record` icon. A 2 dp progress line along the bottom. One merged node with custom actions (A3) |
| `Record` (existing `ArtworkPager` + vinyl) | — | spinning, paused (tucked 18 dp, scale 0.97), reduced motion (static) | 9 s per revolution. Gestures are unchanged. Semantics per A9 |

## 4. Motion

| Element | Trigger | Animation | Duration | Easing |
|---|---|---|---|---|
| Press | Touch | Ripple | 100 | standard |
| Chip on/off | Tap | Fill and check fade | 200 | standard `(0.2, 0, 0, 1)` |
| Destination change | Nav | Fade-through | 300 in / 200 out | emphasised decelerate / accelerate |
| Now Playing | Mini player tap | Slides up from the bottom and scales from 0.92 | 450 | emphasised |
| Sheet | Open / close | Slide | 300 / 200 | emphasised |
| Record | Playing | Rotation | 9000 per turn | linear |
| Record | Pause | Tuck 18 dp, scale 0.97 | 300 | emphasised |
| Any of the above | Reduced motion | Crossfade or instant | 0–100 | — |

## 5. Screens and wiring

### 5.1 Shell (`app/navigation/HarmonyApp.kt`)

- **Destinations:** `home` (Listen), `library` and `add`, shown in the
  bottom bar on phones and in the rail at 600 dp and wider.
- **Old routes:** `playlists`, `discover`, `downloads` and `recognize` remain
  as **pages**, opened from Library and Add.
- **Top bar of each destination:** `[ActivityPill] [Sound] [Settings]`.
- **Activity sheet:** a `ModalBottomSheet` showing:
  - `DownloadStatusCenter.active`, including parallel tracks;
  - the album queue (`AlbumQueueViewModel`: pause, resume, cancel, retry,
    skip);
  - the last outcome;
  - a link to "All downloads" (the `downloads` page).
- **Dock:** the mini player above the navigation bar, on `ground`, with a
  hairline.

### 5.2 Listen (`feature/home`)

The screen reads `HomeViewModel.uiState`, `mixStarting` and `message`.

| UI | Action |
|---|---|
| Resume card (`resumeSong`, or `nowPlaying`) | `togglePlayback` / `playRecent(song)` |
| Mix my library | `startSmartMix()` |
| Shuffle all | `startSmartMix()`. The library has no separate shuffle-all in the VM, so this card is **omitted** rather than faked. |
| Mood chips | `onMood(MoodFilter)`, hoisted to the shell, which calls `PlayerViewModel.onMoodFilterSelected(mood)` **then** `HomeViewModel.startSmartMix()` (U3) |
| Recently played (`recentAlbums` / `recentlyPlayed`) | `playAlbum` / `openAlbum` |
| Made for you | Navigate to `smart_playlist/{TYPE}` |
| Your playlists (`playlists`) | `openPlaylist` |
| New for you | Navigate to `discover` |

**States:**

| State | What shows |
|---|---|
| `libraryEmpty` | Empty state, with [Add music] → Add |
| Not `loaded` | Nothing (no flash) |

### 5.3 Library (`feature/library`)

- Segments: Songs · Albums · Artists · **Playlists**. The Playlists slot is
  provided by the shell and renders `PlaylistsScreen` content without its
  own title.
- Tools in the overflow: Tag editor, Check files, Tidy up (`openReview`),
  Rescan.

### 5.4 Add (`feature/downloads`, new `AddScreen.kt`)

- **Search field.** Submitting opens the `downloads` page with the query
  pre-filled (`DownloadsViewModel.setQuery` + `searchSelectedSource`). That
  page is the full results view.
- **Doors:**

  | Door | Destination |
  |---|---|
  | Identify | `recognize` |
  | From Spotify | `spotify_playlist_transfer` |
  | Discover | `discover` |
  | From your computer | the Settings Connect section |

- **In progress:** `DownloadStatusCenter.active`.

### 5.5 Now Playing (`feature/player`)

The layout is a column:

| Order | Element | Size |
|---|---|---|
| 1 | Top bar | 56 |
| 2 | Record stage | `weight(1f)`, minimum 200, aspect-locked |
| 3 | Title and favourite | — |
| 4 | Artist | — |
| 5 | Reason and FormatTag | one line |
| 6 | Waveform and times | — |
| 7 | Transport | 48 / 56 / 72 / 56 / 48 |
| 8 | Steering row | — |
| 9 | Action row | 56 |

Steering on screens of 720 dp or taller in portrait:

| Chip | Action |
|---|---|
| Calmer | `onEnergySliderChange((energy ?: 0.5f) - 0.2f)` |
| Livelier | `onEnergySliderChange((energy ?: 0.5f) + 0.2f)` |
| Mood | the mood sheet: `onMoodFilterSelected`, `onStartJourneyToMood(mood, 5)` |
| Tune… | the existing Smart Shuffle sheet |

On shorter screens and in landscape, these become a single **[Steer]**
action that opens the same sheet. If no mix is playing, a single chip reads
"Start a mix from this song" (`onStartMix`).

### 5.6 Strings

New UI strings go to `res/values/strings.xml` and `res/values-ro/strings.xml`
in the module that uses them (core/ui, home, app, downloads, player).

## 6. Edge cases

| Case | Rule |
|---|---|
| Long titles | Single line with ellipsis in rows and the mini player. Up to 2 lines on Now Playing, then ellipsis. The full text is always in the content description. |
| Romanian text | Buttons wrap to 2 lines and never truncate. The chip row scrolls horizontally. |
| No artwork | `raised` tile with a music glyph in `faint`. |
| No analysis yet | Moods are disabled, with "Moods are almost ready". |
| Font scale 2.0 | Heights are minimums. Now Playing scrolls, with the record at a minimum of 160 dp. |

## 7. Accessibility checklist (from `08`)

These must all be in place before the work is called done:

- A3: the mini player is one merged node with actions;
- A6: seek has slider semantics;
- A8 and A9: every gesture has an alternative;
- A10: targets are at least 48 dp;
- A12: the Now Playing traversal order is set;
- A13: the focus ring;
- A15: reduced motion;
- A20: toggle state;
- A22: segment selection;
- A23: no state shown by amber fill alone.
