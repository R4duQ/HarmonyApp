---
name: Harmony (Android)
description: A music player for your own files, built around a turning record.
colors:
  # Now Playing, Queue, Smart Shuffle sheet: the player palette (PlayerLook.kt)
  player-field-light: "#DCD7FA"
  player-field-dark: "#15121F"
  player-ink-light: "#17142B"
  player-ink-dark: "#F1EEFF"
  player-muted-light: "#5D5878"
  player-muted-dark: "#9E98BF"
  player-control-light: "#E8E4FD"
  player-control-dark: "#232032"
  player-control-edge-light: "#17142B14"
  player-control-edge-dark: "#FFFFFF1A"
  player-primary-control-light: "#FFFFFF"
  player-primary-control-dark: "#EDE9FF"
  player-accent-light: "#6C4BE8"
  player-accent-dark: "#9B85FF"
  player-track-light: "#B4ACDD"
  player-track-dark: "#393452"
  vinyl-black-light: "#17151C"
  vinyl-black-dark: "#0B0A10"
  vinyl-groove-light: "#FFFFFF1F"
  vinyl-groove-dark: "#FFFFFF14"
  sleeve-shadow-light: "#2A2350"
  # Mini player, nav bar/rail, Library, Downloads: Cyber-Glass (CyberGlassColors.kt)
  glass-obsidian: "#0B0F17"
  glass-obsidian-deep: "#0E131F"
  glass-pearl: "#F4F7FC"
  glass-pearl-deep: "#E8EEF7"
  glass-card-dark: "#111827"
  glass-card-light: "#FFFFFF"
  glass-recessed-dark: "#1A2333"
  glass-recessed-light: "#EDF1F8"
  glass-cyan-dark: "#00E5FF"
  glass-cyan-light: "#0E7490"
  glass-mint-dark: "#00F5D4"
  glass-mint-light: "#0D9488"
  glass-violet-dark: "#8B5CF6"
  glass-violet-light: "#7C3AED"
  glass-purple-dark: "#A855F7"
  glass-purple-light: "#9333EA"
  glass-text-dark: "#FFFFFF"
  glass-text-light: "#0B1220"
  glass-text-secondary-dark: "#94A3B8"
  glass-text-secondary-light: "#475569"
  mini-record: "#0D0D10"
  # Home, album/artist detail, playlists, Settings, Equalizer, Discover: Editorial (EditorialLook.kt)
  editorial-field-light: "#F4FDFF"
  editorial-ink-light: "#16232B"
  editorial-muted-light: "#16232B99"
  editorial-line-light: "#16232B29"
  editorial-field-dark: "#15121F"
  editorial-ink-dark: "#F1EEFF"
  editorial-muted-dark: "#9E98BF"
  editorial-line-dark: "#F1EEFF29"
  accent-amber: "#F3A311"
  accent-green-light: "#1E9153"
  accent-green-dark: "#3FBF77"
  accent-blue-light: "#2C87CE"
  accent-blue-dark: "#4FA3E3"
  accent-stone-light: "#6E6A5F"
  accent-stone-dark: "#E6DDCB"
  accent-coral-light: "#DA4A31"
  accent-coral-dark: "#E9573F"
  accent-teal-light: "#128F8A"
  accent-teal-dark: "#3FBFB6"
typography:
  player-title:
    fontFamily: "Roboto, sans-serif"
    fontSize: "25sp"
    fontWeight: 700
    lineHeight: "30sp"
    letterSpacing: "-0.4sp"
  player-title-landscape:
    fontFamily: "Roboto, sans-serif"
    fontSize: "21sp"
    fontWeight: 700
    lineHeight: "26sp"
    letterSpacing: "-0.4sp"
  sheet-title:
    fontFamily: "Roboto, sans-serif"
    fontSize: "26sp"
    fontWeight: 700
    lineHeight: "31sp"
    letterSpacing: "-0.5sp"
  mini-title:
    fontFamily: "Roboto, sans-serif"
    fontSize: "18sp"
    fontWeight: 700
    lineHeight: "23sp"
  row-title:
    fontFamily: "Roboto, sans-serif"
    fontSize: "14.5sp"
    fontWeight: 600
  body:
    fontFamily: "Roboto, sans-serif"
    fontSize: "14sp"
    fontWeight: 400
    lineHeight: "20sp"
  caption:
    fontFamily: "Roboto, sans-serif"
    fontSize: "11sp"
    fontWeight: 400
  overline:
    fontFamily: "Roboto, sans-serif"
    fontSize: "11sp"
    fontWeight: 700
    letterSpacing: "1.6sp"
  micro-overline:
    fontFamily: "Roboto, sans-serif"
    fontSize: "9sp"
    fontWeight: 500
    lineHeight: "12sp"
    letterSpacing: "1.6sp"
  badge:
    fontFamily: "Roboto, sans-serif"
    fontSize: "8sp"
    fontWeight: 700
    letterSpacing: "0.5sp"
rounded:
  thumb: "8dp"
  sleeve: "12dp"
  panel: "14dp"
  card: "16dp"
  editorial-card: "18dp"
  mini-card: "28dp"
  pill: "50%"
  circle: "50%"
spacing:
  hairline: "1dp"
  xs: "4dp"
  sm: "8dp"
  md: "12dp"
  lg: "16dp"
  gutter: "20dp"
  gutter-landscape: "24dp"
  section: "28dp"
components:
  player-circle-button:
    backgroundColor: "{colors.player-control-light}"
    textColor: "{colors.player-ink-light}"
    rounded: "{rounded.circle}"
    size: "46dp"
  player-play-button:
    backgroundColor: "{colors.player-primary-control-light}"
    textColor: "{colors.player-ink-light}"
    rounded: "{rounded.circle}"
    size: "72dp"
  player-play-button-landscape:
    backgroundColor: "{colors.player-primary-control-light}"
    textColor: "{colors.player-ink-light}"
    rounded: "{rounded.circle}"
    size: "64dp"
  lossless-badge:
    textColor: "{colors.player-accent-light}"
    typography: "{typography.badge}"
    rounded: "{rounded.pill}"
    padding: "3dp 7dp"
  queue-now-playing-card:
    backgroundColor: "{colors.player-control-light}"
    textColor: "{colors.player-ink-light}"
    rounded: "{rounded.card}"
    padding: "12dp"
  queue-row:
    textColor: "{colors.player-ink-light}"
    typography: "{typography.row-title}"
  mini-player-card:
    backgroundColor: "{colors.glass-card-dark}"
    textColor: "{colors.glass-text-dark}"
    rounded: "{rounded.mini-card}"
    padding: "12dp 14dp"
  mini-player-card-light:
    backgroundColor: "{colors.glass-card-light}"
    textColor: "{colors.glass-text-light}"
    rounded: "{rounded.mini-card}"
    padding: "12dp 14dp"
  editorial-card:
    backgroundColor: "{colors.editorial-field-light}"
    textColor: "{colors.editorial-ink-light}"
    rounded: "{rounded.editorial-card}"
---

# Design System: Harmony (Android)

<!--
Scope: the Android app. Desktop (Compose Desktop, Figtree) and iOS follow it and are not described here.
Evidence labels used throughout:
  [seen]  observed in a rendered capture (list in "Evidence" under Overview)
  [code]  inferred from source; not confirmed on a screen
Captured 18 Nov 2026 against main @ 42fc942 (build 172).
-->

## Overview

**Creative North Star: "The Listening Room"**

Harmony is a quiet room where one object matters: the record. On Now Playing the sleeve sits on a soft lavender (dark: aubergine-black) field with the disc drawn halfway out, turning while the music plays. Everything else on that screen is low-contrast and tonal, so the eye goes to the record, then the title, then the one white play button. Around the room is the hi-fi gear: the Cyber-Glass mini player and navigation pill, glass panels with cyan and violet neon rims, and the Library in obsidian (dark) or pearl (light) glass. The pages you pass through (Home, playlists, Settings, Equalizer, Discover) are calm Editorial pages on one shared field, where each section says who it is with a single accent colour.

These three looks coexist on purpose, and the code says so in comments: the player owns its palette and opts out of Material dynamic colour [code: `PlayerLook.kt`]; Cyber-Glass "dresses the library, the floating mini player and the navigation pill, in both themes" [code: `CyberGlassColors.kt`]; Editorial pages share one field and colour only their controls [code: `EditorialLook.kt`]. The record is the thread through all three: the full sleeve-and-disc on Now Playing, a spinning round disc on the mini player, and a static sleeve-and-disc on album grids (`VinylAlbumCover`).

Density is relaxed on the player and the mini player, denser in the Queue (a list you reorder, not browse). Motion is physical and continuous: the record coasts to a stop instead of snapping, drags follow the finger, and every gesture has a visible "not far enough, spring back" state.

**Key Characteristics:**
- One hero per screen; on Now Playing it is always the record.
- The player palette ignores wallpaper colours but follows light/dark/AMOLED.
- The record is a dark object in both themes.
- Neon and glow belong to the chrome (mini player, nav, Library, Downloads), never to the player field.
- Section identity lives in one accent, not in a coloured background.
- No brand font on Android: Roboto, with hand-set sizes.

### Evidence

| Source | What it shows | Freshness |
|---|---|---|
| Roborazzi CI captures, branch `ui-screenshots/5fd84063…` (Nov 13 2026, build 167), files `after_03_home_chrome_*`, `after_05_vinyl_*`, `after_07_album_search_queue_*`, light and dark, 411×891dp @xxhdpi | Mini player + nav bar on Home [seen]; `VinylArtwork` alone, paused, no cover [seen]; Cyber-Glass download cards [seen] | Mini player and VinylArtwork unchanged since. Test scenes, not the whole app. |
| Website screenshots `website/src/assets/img/now-playing-780.webp`, `now-playing-dark-780.webp`, `queue-780.webp` | Full Now Playing in light/dark with a cover, the Queue sheet [seen] | Rendered Oct 11 2026. Now Playing has changed slightly since (output label, Smart Shuffle pill); **Queue changed a lot after Nov 13** (mix bar, online mix panel, Keep buttons), so its capture is layout reference only. |
| No emulator or device capture | | This environment has no Android SDK. Small-phone and font-scale behaviour below is **calculated from code**, not observed. |

## Colors

Three palettes, one per zone, all switching light/dark by reading the Material background's luminance rather than taking a parameter.

### Primary
- **Record Violet** (`player-accent-light` / `player-accent-dark`): the one coloured thing on the player field. Played part of the waveform, active shuffle and repeat, favourite on, the LOSSLESS badge, "Journey 40%", the queue's "NOW PLAYING" label and section labels, Keep/Download actions in the Queue [code; seen on website shots]. Shared with the Editorial lavender accent, so Recognize and the player read as one family.

### Secondary
- **Electric Cyan** (`glass-cyan-dark`, deepened to `glass-cyan-light` on pearl): what is live or selected in Cyber-Glass. The device pill dot, the "LIVE SPECTRAL EQ" telemetry line, the selected nav item, active mini-player buttons [seen: home chrome capture].
- **Neon Violet → Purple** (`glass-violet-*`, `glass-purple-*`): the mini player's play button gradient, the iridescent rims, the coloured shadows [seen].
- **Mint** (`glass-mint-*`): the end of the progress gradient (violet → cyan → mint) and the selected-chip gradient [code].

### Tertiary
- **Section accents** (Editorial): amber for Home and album/artist pages, green for playlists, blue for the Equalizer, coral for Discover, stone for Settings, teal reserved for Downloads, lavender for Now Playing and Recognize [code: `HarmonyApp.kt` route → palette]. Only controls, chips and progress take the accent; the field never does.

### Neutral
- **Lavender Field** (`player-field-light`) and **Aubergine Night** (`player-field-dark`): the Now Playing field, full-bleed, painted behind the system nav bar too [seen].
- **Tonal Circle** (`player-control-*`) with a 1dp **hairline** (`player-control-edge-*`): every secondary control on the player. The hairline carries the shape at this contrast [code comment, seen].
- **Pressing Black** (`vinyl-black-*`, `mini-record`): the record in either theme [seen].
- **Obsidian / Pearl** (`glass-obsidian`, `glass-pearl`): the Cyber-Glass page, a slight vertical gradient to the `-deep` value [code].
- **Pale Ice Field** (`editorial-field-light`) with **Slate Ink**; dark Editorial uses exactly the player's dark field and ink, so in dark mode the player and the Editorial pages share one ground [code comment].

### Named Rules
**The Player Owns Its Light Rule.** Now Playing, the Queue sheet and the Smart Shuffle sheet use `playerPalette()` only. Material dynamic colour never repaints them; light/dark/AMOLED still apply.

**The Dark Record Rule.** The record (`VinylArtwork`, the mini player `Disc`, `VinylDisc`, `VinylAlbumCover`) is near-black in both themes. A coverless mini-player record gets a dark label in light mode too.

**The One Coloured Thing Rule.** On the player field, colour means state: if it is violet, it is on, played, or the current track.

## Typography

**Display / Body / Label Font:** Roboto (Android system default). No font file ships in the Android app [code: no `res/font`, no `FontFamily`]. Desktop bundles Figtree; that is the desktop's choice, not Android's.

**Character:** neutral and compact; hierarchy comes from weight (Bold titles, Regular body) and from very small tracked-out uppercase labels, not from a type pairing.

### Hierarchy
- **Player title** (700, 25sp / 30sp, −0.4sp; 21sp centred and up to 2 lines in landscape): song title on Now Playing, 1 line with ellipsis in portrait [code; seen].
- **Sheet title** (700, 26sp / 31sp, −0.5sp): "Queue" [code; seen].
- **Mini title** (700, 18sp / 23sp): "Title - Artist" on one line, marquee after 2 s [code; seen].
- **Row title** (600, 14.5sp) over a 12sp muted line: Queue rows [code].
- **Body** (Material `bodyMedium`, 14sp): artist under the player title, lyrics use `bodyLarge` [code].
- **Caption** (11sp): times under the waveform, output label, "· Next: …", audio summary "FLAC · 1411 kbps · 96 kHz" (11sp SemiBold) [code; seen].
- **Overline** (700, 11sp, 1.6sp tracking, uppercase): Queue section labels "NEXT UP", "THEN" [code; seen]. Mini player variants: 10.5–11.5sp, 0.6–0.9sp tracking (device pill, telemetry, "UP NEXT") [seen].
- **Micro overline** (500, 9sp, 1.6sp tracking): "NOW PLAYING FROM" [code; seen].
- **Badge** (700, 8sp): "LOSSLESS", the shuffle mode pill ("SMART", "FLOW") [code; seen].

### Named Rules
**The Hand-Set Scale Rule.** Sizes are set per component in `sp`, not taken from Material type roles (except `bodyMedium`, `titleSmall`, `bodyLarge`). Everything is in `sp`, so it follows the system font size.

*Observation, not a rule:* the 8sp and 9sp labels are below a comfortable reading size, and at font scale 1.3 they are what grows the player's height most per line of information [code].

## Layout

**Now Playing, portrait** [code: `NowPlayingScreen.kt`; seen: website shots]. One centred column, 20dp side gutter, capped at 600dp wide on upright tablets (the field fills the margins). Top to bottom: top bar (back · "NOW PLAYING FROM / album" · ⋮), flexible space (weight 1), the record block (full width, height = 0.72 × width), flexible space (weight 1.1), title row with favourite, audio quality line (+8dp), meta line (output · Journey · Next, +7dp), 18dp, waveform seek bar (52dp) with times, 22dp, transport (72dp play, 54dp prev/next, 46dp repeat/shuffle), 28dp, system nav inset. The column does not scroll.

**Now Playing, landscape** [code]. Two halves side by side, 24dp gutter, capped at 1040dp: left = the record (largest 1 : 0.72 block within the half's width and 62% of its height) with the title centred under it (21sp, 2 lines) and the quality line; right = meta, 44dp waveform, transport (64dp play, 48dp sides), max 480dp wide and vertically centred. Favourite moves into the top bar.

**Mini player** [code; seen]. Floats over content, bottom of the screen, 12dp side and 6dp vertical inset, above a 10dp gap and the nav pill. The card is offset right by half the disc so the record (27% of width, 84–120dp) sits half outside the card's left edge. Inside: device pill, title, telemetry line, 22dp wave progress, five controls (34–38dp, play 46–50dp; a live mini spectrum appears when the row is ≥228dp), then "UP NEXT" chips. Hidden on Now Playing. Screens reserve its measured height through `LocalFloatingChromeHeight`.

**Queue** [code; layout seen on Oct capture]. Material `ModalBottomSheet` over Now Playing. 20dp text inset, 16dp card inset: header ("Queue", "N tracks up next"), now-playing card, mix bar, online mix panel when relevant, then sections ("NEXT UP", "THEN") of rows: 48dp drag handle, 42dp artwork, title/artist, 48dp remove (and Keep for online songs).

**Spacing rhythm:** 4 / 8 / 12 / 16 / 20 / 28dp, with the player using odd optical steps (7, 14, 18, 22dp) between its text lines. Touch targets: Queue handles and remove are 48dp; player circles are 46dp (favourite 44dp, 42dp in the landscape top bar); mini-player buttons 34–38dp [code].

### Small-phone budget (calculated, not observed)
On a 360 × 640dp phone with 3-button navigation (≈568dp usable), the portrait player's fixed parts need about 375dp and the record block about 230dp, ≈605dp in total. The two flexible spacers collapse to zero and the column still overflows by ≈35dp, so the bottom margin and the lower part of the transport row are cut off. At font scale 1.3 the overflow grows. On a 411 × 891dp phone (the CI capture size) everything fits with room to spare. Landscape on 640 × 360dp fits by this estimate. To be confirmed on an emulator or device.

## Elevation & Depth

Mostly flat and tonal on the player; lifted and glowing in the chrome.

### Shadow Vocabulary
- **Sleeve shadow** (22dp, rounded 12dp, ambient and spot `sleeve-shadow-light` / black in dark): the only shadow on Now Playing, under the album sleeve [code; seen].
- **Neon chrome shadow** (18dp, ambient violet, spot cyan): under the mini-player card [code; seen as a coloured haze].
- **Record shadow** (16dp, circle, violet/cyan): under the mini-player disc [code].
- **Glow scale** (`Glow` 1.0 dark, 0.55 light): every neon glow is softened on pearl so it doesn't read as a smudge [code].

### Named Rules
**The Quiet Field Rule.** On the player, depth is one shadow (the sleeve) plus tonal circles with hairlines. No glows, no glass, no gradients on the player field.

## Shapes

Rounded rectangles for things you hold (sleeve 12dp, cards 14–18dp, mini-player card 28dp), true circles for things you press and for records, and full capsules for chips, badges and the nav pill. Borders are 1dp hairlines on the player and 1–2dp iridescent gradients (cyan → violet → purple → cyan) in Cyber-Glass. The spindle hole is punched in the field colour, so it reads as a hole [code].

## Components

### Vinyl (Now Playing) — product requirement

> **Requirement (recorded 18 Nov 2026, from the product owner):** Now Playing keeps its Vinyl mode. The record stays the central element and is visible in portrait and in landscape. Its animation and its gestures stay as they are. The record's size and position **may** be adjusted so that the title, the progress bar and the controls stay reachable on small phones. Anything else about the record (removing it, hiding it behind a toggle, replacing it with flat artwork, making it decorative-only) is out of scope.

**What it is** [code: `VinylArtwork.kt`, `NowPlayingScreen.kt` → `ArtworkPager`]
- A 1 : 0.72 block. The album sleeve is a rounded square (12dp corners, 22dp shadow) taking **70% of the block's width**, pinned left. The record has the same diameter, pinned right, so about 30% of it shows past the sleeve [code; seen].
- The record is drawn, not an image: pressing-black disc, 26 groove rings (tighter toward the rim, 0.8% of the radius thick), one soft sheen that turns with the disc, the album art printed as a circular label (34% of the diameter) under a 28% black wash, and a spindle hole (4.5%) punched in the field colour [code]. Without a cover, the label and sleeve fall back to a music-note tile [seen: `after_05_vinyl`].
- The sleeve carries the accessibility label "Album art for {album}"; the record is cleared from semantics as decoration [code].

**Motion (keep)** [code]
- Turns once every **9 s**, linear, looping without a seam while playing. On pause it stops where it is (no snap back).
- **Tuck:** when not playing, the record slides **18dp** into the sleeve on a low-bouncy spring; it slides back out on play.
- **Breathe:** the whole block scales to **0.97** when paused, 1.0 when playing (low-stiffness spring).
- **Track change:** the block cross-fades, new one fading in from 0.92 scale (350ms), old one out to 1.04 (250ms).

**Gestures (keep)** [code]
- **Swipe the record left/right** to skip next/previous: it follows the finger and fades with distance; past **22% of the block's width** it leaves in the swipe direction (160ms), then the track changes; short of that it springs back. Scoped to the record so it never fights the seek bar.
- **Swipe down anywhere** to close the player (past 20% of the screen height); the page follows the finger.
- **Swipe up anywhere** (past 80dp) to open the Queue.
- Same gestures in portrait and landscape.

**Placement today** [code; seen on website shots]
- Portrait: full column width (20dp gutters), between two flexible spaces; on a 411dp-wide phone the block is ≈371 × 267dp.
- Landscape: left half, at most 62% of the half's height, title centred beneath.
- Tablets: the column caps at 600dp (portrait) and 1040dp (landscape), so the record never balloons.

**Where it flexes (per the requirement)**
- The block's size is the variable that frees room: today its height is fixed at 0.72 × width in portrait and nothing shrinks it when the column runs out of height (see the small-phone budget in Layout). Size and vertical position may change; the sleeve/record composition, the 9 s turn, the tuck, the breathe and the swipe must not.

**Related records** (same idea, kept consistent)
- **Mini-player `Disc`** [code; seen]: the record seen from above, round cover as the label, 2dp iridescent rim, violet/cyan shadow, same 9 s turn; coasts +24° on pause and shrinks to 0.93; a skip gives it an extra 200° turn; it turns with a sideways drag; spins in on arrival; grows and rises ahead of the card when you swipe up into Now Playing.
- **`VinylAlbumCover`** [code]: static sleeve with the disc 94% of the sleeve size peeking right, used in Library album grids and album detail.
- **`VinylDisc`** [code]: top-down record with track gaps, used by the Library's arc song list; its sheen stays still while the disc turns (the opposite choice to `VinylArtwork`).

### Now Playing controls
- **Tonal circle button** (`PlayerCircleButton`): 46dp circle, tonal fill, 1dp hairline, icon 20dp. Used for back, ⋮, favourite (44dp), repeat, prev/next (54dp portrait, 48dp landscape). TalkBack reads one button with its label [code; seen].
- **Play/pause:** the one raised control, white (light) / pale lavender (dark), 72dp portrait / 64dp landscape; icon morphs with a scale-fade (160/120ms) [code; seen].
- **Shuffle:** when on, a violet-tinted disc with a 1.5dp violet rim, a small mode glyph (sparkle Smart, dice Random, compass Journey) and an 8sp violet pill under it naming the mode; long-press opens Smart Shuffle; haptic on both [code; seen].
- **Waveform seek bar:** the song's waveform as bars, played part violet, rest `player-track`; bars react to the live level while playing; glides between 500ms position ticks (linear 560ms), springs on jumps; drag to scrub, commits on release [code; seen].
- **Audio quality line:** "LOSSLESS" capsule (violet at 13% fill, 36% rim) + "FLAC · 1411 kbps · 96 kHz"; uses the same resolver as the mini player so both always agree [code; seen].
- **Meta line:** output icon and name (tap → "Play on"; cyan-violet when on a computer), "· Journey N%", "· Next: title" once the crossfade starts [code].
- **Menu:** Material `DropdownMenu` with Queue, Smart Shuffle, Lyrics (when present), Equalizer [code].

### Mini player (`MiniPlayerCard`)
- **Character:** the Cyber-Glass object that follows you everywhere; smoked obsidian glass in dark, pearl glass in light [seen].
- **Card:** 28dp corners, `glass-card-*` at 92% (dark) / 94% (light) opacity, 1dp iridescent gradient rim, 18dp violet/cyan shadow [code; seen].
- **Device pill:** capsule, cyan dot with the output icon, uppercase label ("PHONE SPEAKER") [seen].
- **Wave progress:** violet → cyan → mint gradient over a faint sky track, a glowing thumb; tap or drag to seek [code; seen].
- **Controls:** recessed circles (34–38dp), active ones get a cyan rim and icon; play is a violet → purple gradient circle (46–50dp) [seen].
- **Up next:** "UP NEXT" overline, then capsule chips with a round 24dp cover, fading out at the right edge; tap plays that song [seen].
- **Gestures:** tap card or record → Now Playing (card hops up 36dp); swipe up (90px or a fling) → Now Playing, card follows 1:1 up to 150dp; swipe sideways past 72dp → skip with a haptic tick; drag on the wave → seek [code].
- **Entrance/exit:** rises in with a spring the first time a song loads; lifts and swells into Now Playing as it opens; drops back from above with a bounce, record spinning in, when it closes [code].

### Queue (`QueueSheet`)
- **Character:** a list you *move*, so flatter and denser than other lists, in the player palette [code comment].
- **Surface:** Material `ModalBottomSheet` default container; the content uses `playerPalette()` [code]. *Observation:* the sheet's own background comes from the Material scheme, so with dynamic colour on it can differ from the player field it opens over [code; seen as near-white lavender on the Oct capture].
- **Header:** "Queue" (26sp Bold) and "N tracks up next" [seen].
- **Now-playing card:** 16dp corners, tonal fill, 52dp cover (10dp corners), violet "NOW PLAYING" overline with an equalizer glyph [seen]. Not draggable, not removable.
- **Mix bar / Online mix panel** (new since the last capture): 14dp / 12dp tonal panels, violet-tinted when a mix is active, with the mix source and progress of online songs [code].
- **Rows:** 48dp drag handle (long-press to drag, half a row moves one slot), 42dp cover (8dp corners), title 14.5sp SemiBold, "artist · 3:13" 12sp muted, 48dp remove ✕; dragging row gets the tonal fill; online rows get a Keep/Download button [code; seen].
- **Empty state:** icon, "nothing after this" style message in player colours [code].

### Navigation (`LiquidGlassNavBar`, `LiquidGlassNavRail`)
- Floating frosted capsule (`NavGlass` at 90%), a smaller selected capsule inside it, five destinations (Home, Library, Playlists, Discover, Downloads); a rail on wide screens/landscape, the chrome capped in width beside it [code; seen]. Hidden on Now Playing.

### Editorial kit (where it appears)
- **Pages:** Home and album/artist detail (amber), playlists and smart playlists (green), Equalizer (blue), Discover (coral), Settings, Tag editor, FLAC check (stone), Recognize (lavender) [code: route → palette].
- **Pieces:** `EditorialCard` (18dp, hairline outline, fill = field), `EditorialSongCard` (swipe right to queue, same action in ⋮), `EditorialSectionLabel`, `EditorialSettingRow`, `EditorialSwitch`, `EditorialChoiceChips`, `EditorialSlider` (real Material controls recoloured from the palette), `EditorialTabs`, `EditorialPill`, `EditorialRaisedButton` [code; settings controls seen in `after_04`].
- Moving between sections cross-fades the whole palette over 260ms, carrying the chrome with it [code].

### Cyber-Glass kit (where it appears)
- Library (search bar, chips, song rows, album and artist lists, arc song list), album downloads and Downloads, the mini player and the nav pill [code; download cards seen in `after_07`].
- Pieces: `GlassSearchBar`, `GlassSegmentedTabs`, `GlassSongCard`, `GlassCircleButton`, `CyberGlassPanel`, `NeonWaveformArt` (coverless songs get a seven-bar cyan → violet waveform tile), `neonGlow` [code].

## Do's and Don'ts

### Do:
- **Do** keep the record as Now Playing's centre in both orientations: sleeve at 70% of the block, record the same size peeking right, 9 s turn, 18dp tuck, 0.97 breathe, swipe-to-skip on the record.
- **Do** change the record block's size and vertical position when a phone is short, so the title, waveform and transport stay fully on screen.
- **Do** use `playerPalette()` for anything that opens over Now Playing.
- **Do** keep the record near-black in light and dark.
- **Do** use violet on the player only for state (on, played, current).
- **Do** keep Cyber-Glass neon to the chrome, Library and Downloads, with `Glow` softened on pearl.
- **Do** give every Editorial section one accent and leave the field neutral.
- **Do** keep the mini player and Now Playing reporting the same audio format (shared resolver).

### Don't:
- **Don't** remove, hide, or flatten the Now Playing record, or put it behind a setting.
- **Don't** let Material dynamic colour reach the player field or its sheets' content.
- **Don't** add glows, glass or gradients to the Now Playing field.
- **Don't** make a page-wide horizontal gesture on Now Playing; it would fight the seek bar.
- **Don't** colour an Editorial page's background with its section accent; that was tried and removed.
- **Don't** fix the player's height by hard-coding a smaller record for everyone; the 411dp-and-up phones fit today.

### Known gaps (recorded, not decided)
- Small phones: the portrait player overflows at ≈360 × 640dp (calculated).
- Targets under 48dp: player circles (42–46dp), mini-player buttons (34–38dp).
- 8–9sp labels ("LOSSLESS", shuffle pill, "NOW PLAYING FROM").
- No check of the system "Remove animations" setting anywhere in the app [code: no reference to animator duration scale].
- Leftovers in `EditorialLook.kt`: `miniPlayerField()` (gold), `MiniPlayerRim`, `MiniPlayerInk` are no longer used by the mini player, and comments there still describe a yellow mini player.
- Queue sheet container follows the Material scheme while its content follows the player palette.
