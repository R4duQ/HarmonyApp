# Harmony: Information Architecture, Screens and Flows

`[ACTIVE SKILL: anthropic-skills:mobile-app-ui-design]`

This document uses the tokens from `04-design-system.md` and the capability
numbers (C#) from `01-functional-spec.md`. Every action named here maps to
an existing ViewModel function, so no backend logic changes.

---

## 1. Information architecture

```
Harmony
├── Listen  (start destination)  ── "What's playing, what to play"
│   ├── Resume / Start a mix / Moods
│   ├── Recently played
│   ├── Made for you: smart playlists (Favourites, Most played, Recently added,
│   │                 Recently played, High energy, Low energy)
│   └── New for you  → Add › Discover
│
├── Library ── "What I own"
│   ├── Songs · Albums · Artists · Playlists   (one segmented control)
│   ├── Search (whole library)
│   ├── Album, Artist, Playlist details
│   └── Tools (overflow): Tag editor, FLAC check, Tidy up (review),
│       Import M3U, Rescan
│
├── Add ── "Bring music in"
│   ├── One search: song or album, any source, or paste a link
│   ├── Identify a song (microphone)
│   ├── From Spotify (playlist import)
│   ├── Discover (taste rounds + build a playlist)
│   ├── From your computer (Harmony Connect: pair, transfers)
│   └── YouTube link → FLAC (also accepted in the search box)
│
├── [Global] Now Playing (full screen) → Queue (sheet), Play on (sheet),
│            Tune the mix (sheet), Sleep timer, Speed, Song details
├── [Global] Mini player (above the bottom bar on every destination)
├── [Global] Activity (sheet): downloads, album queue, transfers, analysis
├── [Utility] Sound: Simple (presets + Auto) · Graphic · Winamp · Clarity Live
└── [Utility] Settings
```

### Navigation model

| Element | Phone (< 600 dp) | ≥ 600 dp |
|---|---|---|
| Destinations | **Bottom bar**: Listen · Library · Add (icon + label, 80 dp tall) | **Navigation rail**, with the same 3 destinations plus Sound and Settings at the bottom |
| Mini player | Floats above the bottom bar: 72 dp tall, 8 dp margins | Docked at the bottom of the content pane |
| Now Playing | Full screen, slides up from the mini player (shared artwork, `large` 450 ms) | Two-pane: record left, info and queue right |
| Utilities | **Top app bar** on each destination: `[Activity pill] [Sound] [Settings]` | In the rail |
| Back | System back closes sheets first, then pops detail pages, then returns to Listen, then exits | Same |

Moves from the old structure:

| Old | New | Why |
|---|---|---|
| Home | **Listen** | It is named by the job (P2) |
| Library + Playlists (2 tabs) | **Library** with a segmented control | One home for what I own (F7) |
| Discover tab | **Add › Discover** | Discover is a way to *add* music; it is reachable from Listen through "New for you" |
| Downloads tab (7 tools) | **Add** with one search and 4 doors | One entry point; the source becomes an override (F1, F2) |
| Recognize under Home | **Add › Identify** | It sits with the other ways music comes in (F9) |
| Spotify import under Downloads | **Add › From Spotify** | Named by the outcome |
| Equalizer behind Home | **Sound**, from the top app bar and Now Playing | One tap from where the problem is heard |
| Banner, shelf, pill | **Activity** sheet + pill | One place for background work (F6) |

---

## 2. Screens

The notation for each screen:

- **Purpose**: what the screen is for.
- **First look**: the one thing seen first.
- **Layout**: top to bottom.
- **States**: loading, empty, error, offline.
- **Actions** (→ ViewModel function).

### 2.1 Listen (start destination)

```
┌──────────────────────────────────────┐
│ Listen                 (●2) 🎚  ⚙   │  display · Activity pill · Sound · Settings
│                                      │
│ ┌──────────────────────────────────┐ │
│ │ [sleeve◐record]  Pick up where   │ │  Resume card (surface, r-l)
│ │                  you left off    │ │  nameM + bodySmall
│ │                  Song · Artist   │ │
│ │                  [▶ Resume]      │ │  Filled button 48dp
│ └──────────────────────────────────┘ │
│                                      │
│ Start something                      │  nameM
│ [Mix my library] [Shuffle all]       │  Tonal buttons
│ (Calm)(Focus)(Energy)(Happy)(Late…)  │  Mood chips → mix with that mood
│                                      │
│ Recently played                 All ›│  horizontal artwork row 136dp
│ [■][■][■][■]                         │
│                                      │
│ Made for you                         │
│ [Favourites][Most played][Recently…] │  2-column tiles with generated covers
│                                      │
│ New for you                          │  card → Add › Discover
├──────────────────────────────────────┤
│ ▣ Song title           ▶  (mini)     │  record block, 72dp
├──────────────────────────────────────┤
│  ♪ Listen    ▤ Library    ＋ Add     │
└──────────────────────────────────────┘
```

- **First look:** the Resume card, or, on a first run, "Start something".
- **Actions:**
  - Resume → `HomeViewModel.togglePlayback` / `playRecent`.
  - Mix my library → `startSmartMix`.
  - Mood chip → `PlayerViewModel.onMoodFilterSelected(mood)` **first**,
    then `startSmartMix`, so the first song already matches the mood
    (critique U3).
    The moods shown are Calm, Focus, Energy (workout), Happy, Late night
    (dark) and Party. The rest sit behind "More moods".
  - Recently played item → `playAlbum` / `playPlaylist`.
  - Made for you tile → open Playlist (`smart_playlist/{type}`).
- **States:**

  | State | What shows |
  |---|---|
  | Library empty | "Your library is empty" + [Allow access] when permission is missing, or [Add music] → Add |
  | Analysis still running | Moods are disabled and say "Ready after your songs are analysed (42%)" |
  | Offline | No change: Listen is fully local |

### 2.2 Now Playing (global, full screen)

```
┌──────────────────────────────────────┐
│ ⌄            From "Kind of Blue"   ⋯ │  Close · source · More
│                                      │
│        ╭───────╮                     │
│   ┌────┤ ◉     │   ← record slides   │  Record hero: sleeve (r-l) with the
│   │art │  rec  │     out of sleeve   │  record peeking right; spins 9 s/rev
│   └────┤       │                     │  (portrait: 72% of width)
│        ╰───────╯                     │
│                                      │
│ So What                         ♡    │  nameXL 28 · favourite 48dp
│ Miles Davis                          │  glance 18
│ ⓘ You play it most on weekday…  [HR] │  ONE line: reason + FormatTag (critique U1)
│                                      │
│ ▁▃▅▇▅▃▁▃▅▇▅▃ (waveform seek)         │  48dp tall target
│ 1:42                          9:22   │  caption tnum
│                                      │
│   ⤮     ⏮     ( ▶ )     ⏭     ↻     │  48 · 56 · 72 · 56 · 48
│                                      │
│ (Calmer)(Livelier)(Mood ▾)(Tune…)    │  Steering chips (when a mix is on)
│                                      │
│ ☰ Up next · 14   ⎋ Phone   🎚 Sound  │  bottom action row, 56dp
└──────────────────────────────────────┘
```

**Height budget** (critique U1).

- The record takes the space left after everything else, with weight 1,
  a locked aspect ratio and a minimum of 200 dp.
- The reason and the FormatTag share one line. Tapping the reason opens
  "Why this song"; tapping the tag opens the file details.
- On screens shorter than 720 dp, or in landscape, the four steering chips
  become a single **[Steer]** button in the action row. It opens the
  steering sheet, which holds Calmer, Livelier, the moods and Tune.

**Landscape.** The record and sleeve take the left half, centred and at
the largest size that fits. The right half holds the title, the reason, the
format, the progress, the transport, the steering chips and the action row,
in the same order. The record stays central in its half: this is a product
requirement.

**Top-bar text.** The source line reads "From *Album*", "From *Playlist*",
"Mix · from your library" or "Mix · online".

**Reason line.** The current song's reason from Smart Shuffle, or the online
mix's "Similar to *seed*". Plain-play songs show nothing there, so the
format tag moves up.

**Steering.**

- With a mix playing:
  - *Calmer* / *Livelier* → `onEnergySliderChange(current ∓ 0.2)`.
  - *Mood ▾* → a sheet of moods (`onMoodFilterSelected`), plus "Ease into
    this mood over the next 5 songs" (`onStartJourneyToMood(mood, 5)`).
  - *Tune…* → the Tune the mix sheet (§2.4).
- Without a mix: a single chip, **"Start a mix from this song"** →
  `onStartMix`.

**Gestures.** These come from the product requirement:

| Gesture | Action |
|---|---|
| Swipe the record sideways past 22% of its width | Next or previous |
| Swipe down past 20% of the screen | Close |
| Swipe up 80 dp | Queue |
| Tap the record | Play / pause |

**More (⋯) sheet:**

- Go to album · Go to artist
- Add to playlist (→ AddToPlaylist sheet)
- Sleep timer (→ `setSleepTimerMinutes`)
- Speed (→ `setPlaybackSpeed`)
- Edit tags (→ Tag editor)
- Check this file (→ FLAC check)
- Show: Record / Cover only. Vinyl mode is the default.

**States:**

| State | What shows |
|---|---|
| Buffering (PC stream or online) | The play button shows progress; the record slows to a stop |
| Output lost | StatusLine "Headphones disconnected, paused" + [Play on phone] |
| Online song | "Online" FormatTag + [Keep] (→ `onKeep`) beside the favourite |
| Reduced motion | The record does not turn |

### 2.3 Queue (sheet from Now Playing)

- **Header:** "Up next", and on the right the mix status in words: *Mix ·
  14 more from your library* / *Online mix paused: on mobile data* (→
  [Allow once]) / *Out of similar songs*.
- **Sections, in order:**
  1. **Now** (one row, amber).
  2. **You added** (play next / add to queue).
  3. **From the mix**: each row has a one-line reason in muted text.
  4. **Online**: each row has [Keep], plus a [Keep all] button in the
     section header.
- **Rows:**
  - drag handle on the right (48 dp) → `onMoveQueueItem`;
  - swipe left to remove → `onRemoveQueueItem`, with Undo in a snackbar;
  - tap to play → `onQueueItemClick`.
- **Footer buttons:** [New mix] (`onRegenerateSmartQueue`) and [Stop mix]
  (`onStopMix`).
- **Accessibility:** each row has custom actions "Move up", "Move down" and
  "Remove", so drag is never the only way.

### 2.4 Tune the mix (sheet)

- **Style:** four cards, one selected (`onSmartStyleSelected`):

  | Card | Style |
  |---|---|
  | Mix it up | Balanced |
  | Old favourites | Familiar |
  | Something new | Discover |
  | Smooth flow | Flow |

- **Sliders** with labelled ends:
  - **Familiar ↔ New**: `onSmartFamiliarityChange` / `onSmartDiscoveryChange`.
  - **Similar ↔ Varied**: `onSmartVarietyChange`.
  - **Energy**, with an "Any" checkbox: `onEnergySliderChange`.
- **Keep going online when my songs run out**: a switch plus the source
  (`onChooseMixSource`). "On mobile data" sits under it, linked to Settings.
- **Footer:** [Done].

### 2.5 Play on (sheet)

- A list of outputs: This phone · Bluetooth device name · USB DAC · Your
  computer *(name)*.
- Each row shows its state in words: "Connected", "Untouched audio",
  "Resampled to 48 kHz".
- USB DAC rows carry a **"Send audio untouched (bit-perfect)"** switch
  (→ `EqualizerViewModel.setBitPerfect`). Under it: "Sound settings are
  skipped while this is on."
- Computer rows that are not paired show [Pair], which opens the Pair flow.

### 2.6 Mini player (global)

`record` block, 72 dp tall, `r-l`. It holds the artwork (48 dp), the title
(`nameL` 22 → it shrinks to `title` 17 when the font scale is above 1.3),
the artist (`glance`), and play/pause (56 dp target).

| Interaction | Result |
|---|---|
| Tap | Now Playing |
| Swipe up | Now Playing |
| Swipe sideways | Next / previous |
| Long-press | Queue |

A 2 dp `recordLive` progress line runs along the bottom edge. If the online
mix is on, a small "Online" tag appears after the artist.

### 2.7 Library

```
┌──────────────────────────────────────┐
│ Library                (●) 🎚  ⚙   ⋯ │  ⋯ = Tools
│ [🔍 Search your library            ] │  SearchField 56dp
│ [Songs | Albums | Artists | Playlists]│  SegmentedControl
│ A ───────────────────────────────────│  letter divider (sticky)
│ ▣ Song title                    3:12 │  ListRow 72dp
│   Artist · Album        [Lossless]   │
│ ...                                  │
└──────────────────────────────────────┘
```

**Segments:**

- **Songs:** paged list with a letter fast-scroller on the right edge
  (48 dp wide target). Tapping a row plays the list from that song
  (`onPagedSongClick`).
- **Albums:** 2-column grid. Tapping one opens the album.
- **Artists:** list with avatar artwork.
- **Playlists:** [New playlist] row first, then smart playlists, then the
  user's own playlists (`PlaylistsViewModel`).

**Row "More" sheet:** Play next · Add to queue · Add to playlist · Go to
album · Edit tags · Check file · Delete from phone (wine, with a
confirmation).

**Selection mode** (long-press): it shows a count, then [Play] [Add to
queue] [Add to playlist] [Delete].

**Tools (⋯):** Tag editor · Check files (FLAC) · Tidy up library (review) ·
Import playlist file (M3U) · Rescan now.

**States:**

| State | What shows |
|---|---|
| No permission | Explanation + [Allow access to music] |
| Empty | "No songs yet" + [Add music] |
| Search: no results | "Nothing called "…" in your library" + [Search to add it] → Add, pre-filled |

### 2.8 Album / Artist / Playlist detail

- **Album:**
  - Header: artwork (r-l, 200 dp), with the record peeking out on scroll
    position 0. This is the signature, kept.
  - Title `nameL`, artist, year · N songs · total time · FormatTag
    summary.
  - Actions: [▶ Play] [Shuffle] [⋯ More: Play next, Add to queue, Delete].
  - Track list with numbers in `caption`.
- **Artist:** header, then [Play] [Add to queue], then Albums (a row) and
  Songs (a list).
- **Playlist:**
  - Header: generated cover, name, count and time.
  - Actions: [▶ Play] [Shuffle] [+ Add songs] [⋯ Rename, Export M3U,
    Delete].
  - Reorder by drag, with a "Move" accessibility action. Swipe to remove,
    with Undo.
  - Smart playlists are read-only and say why: "Updates on its own from
    your listening".

### 2.9 Add

```
┌──────────────────────────────────────┐
│ Add                    (●1) 🎚  ⚙    │
│ [🔍 Song, album or paste a link     ]│  SearchField, autofocus off
│                                      │
│ ┌────────────┐ ┌────────────┐        │  4 doors, 2×2, 112dp tall cards
│ │ 🎤 Identify│ │ ⇪ From     │        │
│ │ a song     │ │ Spotify    │        │
│ └────────────┘ └────────────┘        │
│ ┌────────────┐ ┌────────────┐        │
│ │ ✦ Discover │ │ 💻 From your│       │
│ │ new music  │ │ computer   │        │
│ └────────────┘ └────────────┘        │
│                                      │
│ In progress                     All ›│  last 3 Activity items, inline
│ Kind of Blue · 4 of 9 songs   ▮▮▮▯▯  │
│                                      │
│ Recently added                       │  last 5 downloads (download_records)
└──────────────────────────────────────┘
```

#### Search results (the same screen, results state)

- **Tabs:** **Songs · Albums** (`AlbumSearchViewModel` for albums).
- **Each result:** artwork, title, artist, year, plus the **best available
  quality** in words, for example "Lossless" or "Hi-Res 24/96". If the song
  is already owned, the row says "In your library ✓" (moss), and tapping it
  plays the song.
- **Primary action:** [Get] (Tonal, 48 dp).
  - For a song: `selectSpotiFlacSearchResult` → `downloadSpotiFlacSearchResult`,
    using the preferred source.
  - For an album: `AlbumSearchViewModel.downloadAll` (queued in Activity).
- **Other ways** (secondary, under the results): "Not what you want? Try
  Soulseek · YouTube · a direct link." Each opens that source's search,
  pre-filled with the same text (`selectPreferredDownloadSource`,
  `searchSoulseek`).
- **Pasted link:** the field detects the kind of link.
  - YouTube → a "Convert to FLAC" card with the title check
    (`downloadYouTubeAsFlac`).
  - A direct `.flac` → [Download] (`setDirectFlacUrl` + `downloadFlac`).
  - A Spotify playlist link → the Spotify flow, pre-filled
    (`importPlaylistLink`).
  - A Deezer or Spotify track link → resolved, then shown as a result.

#### Get, after the tap

- The row turns into an inline progress bar ("Getting… 34%") and the
  Activity pill counts up.
- **Success (the peak moment):** the row says "Added to your library". A
  check morphs into a 24 dp sleeve glyph (`medium`, or no motion under
  reduced motion; critique U9). [Play] sits on the right.
- **First Get only:** a one-time coach line under the Activity pill says
  "Progress lives here" (critique U4).
- **Needs you (verification):** an slate StatusLine, "Tidal wants a quick
  check in your browser", with [Open browser]. On return the download
  resumes automatically (`prepareSpotiFlacVerification` →
  `checkProviderVerificationAndRetry`).
- **Failed:** a wine StatusLine with the three parts of P4, and actions
  [Try another way] and [Details].

**Source preferences** (a gear in the search field's trailing area):

- Preferred source: Automatic / SpotiFLAC / Soulseek / YouTube.
- Output format.
- Soulseek account (connect, format preference, free slots only, shared
  folder).
- Download folder.
- Parallel downloads (Experimental).

This is where the machinery lives, one tap deep (P1).

### 2.10 Identify a song

A page (critique: it ends in Get or Play) with a large circular [Listen] button (120 dp, amber).

- **Listening:** the button pulses, or stays static under reduced motion,
  and the label says "Listening… hold your phone near the music". There is
  a [Stop] button.
- **Match:** the artwork, title and artist, then:
  - **In your library** → [Play];
  - **Not in your library** → [Get it] (`requestDownloadForIdentifiedTrack`).
  - "Not right? Listen again" sits under it.
- **No match / too quiet / offline:** see the copy deck. Each case has
  [Try again].
- **Permission:** on first use, a priming sentence comes before the system
  prompt. If permission is denied, [Open settings].

### 2.11 From Spotify (flow)

1. **Connect**, with two paths.
   - **Paste a playlist link** is the default and needs no account
     (`importPlaylistLink`).
   - **Connect your Spotify account** shows a 3-step guide:
     - ① Create a free app at developer.spotify.com ([Open]).
     - ② Add this redirect address: `com.harmony.app://spotify-playlist-callback`
       ([Copy]).
     - ③ Paste the Client ID here.

     Then [Connect] opens the browser (`createSpotifyAuthorizationUri`).
     The guide also offers "Why do I need this?", which says Spotify only
     lets apps read private playlists through a registered app.
2. **Your playlists:** a list with counts and [Refresh]. Tapping one opens
   the Match step.
3. **Match:** the summary comes first, as three numbers: **28 in your
   library · 12 to get · 2 not found**.
   - The list below is grouped (To get / In your library / Not found).
     Rows in *To get* are checked by default (`selectAllMissing`,
     `toggleTrack`).
   - The source line reads "Get with: Automatic ▾" (`selectSource`).
   - The primary action is **[Get 12 songs and create playlist]**
     (`requestTransfer`), placed in the thumb zone and full width.
4. **Progress:** runs in Activity. The screen shows per-row status and can
   be left at any time.
5. **Done (a peak moment):** "Road Trip is ready: 38 of 40 songs." It offers
   [Play] and [Open playlist]. Any failures stay listed with [Retry]
   (`retryFailedTracks`).

The account can be disconnected from the overflow menu
(`disconnectSpotify`).

### 2.12 Discover (under Add)

- **Entry:**
  - Two cards: **"Quick taste round"** (the swipe deck, `DiscoverViewModel`)
    and **"Build a playlist"** (the 3-step `DiscoveryFlowViewModel`).
  - Under them: shelves of curated albums by genre, each with [Preview] and
    [Remamber].
- **Taste round:**
  - One card at a time: artwork, title, artist and a reason line.
  - A preview plays **on tap only**. The user's music pauses during the
    preview and resumes after it (critique U2).
  - Labelled buttons, not icons alone: **[Not for me]** · **[Undo]** ·
    **[Like it]** (`rate`, `undoRating`). They sit in the thumb zone at
    56 dp.
  - Swiping left or right does the same.
  - The end of the round is a summary (a peak moment): "You liked 7. [Make
    a playlist] [Another round]".
- **Build a playlist** is a stepper with a visible "Step 1 of 3":
  1. **What you like:** genres and artists as chips (search for artists),
     plus "How adventurous" (`setLevel`) and size.
  2. **Songs:** each row has Preview / Keep / "More like this" / "Not
     interested" / Replace. Actions are in a row "More" sheet, with Keep as
     a visible checkbox.
  3. **Name and save:** a name field, and "Also get the songs I don't have"
     (`setDownload`), then [Create playlist].
- **States:**

  | State | What shows |
  |---|---|
  | Catalogue offline | "Discover needs the internet" + [Try again] |
  | Preview unavailable | Shown on the row, not as an error |

### 2.13 From your computer (Connect)

- **Not paired:**
  - Steps: "Open Harmony on your computer → Connect a phone".
  - The phone searches (with a visible spinner) and lists the computers it
    finds. The user taps one, then enters the 4-digit code in a large OTP
    field (4 boxes of 56 dp).
- **Paired:**
  - Computer name and status.
  - [Play on computer] (→ Play on).
  - "Shared queue: on".
  - **Incoming files:** offers with [Accept] / [Not now], then progress in
    Activity.
  - [Forget this computer].
- **Not found:** after 10 s, "Can't find your computer", with checks (same
  Wi-Fi, app open, firewall) and [Search again].

### 2.14 Activity (sheet, global)

**Opening.** The pill in the top bar opens the sheet. The pill shows:

| Pill | Meaning |
|---|---|
| Hidden | Nothing is running |
| `● 3` | Things are running |
| Slate `! 1` | Something needs you |
| Wine `× 1` | Something failed |

**Groups:**

1. **Needs you** (verification, transfer offers).
2. **Running:**
   - each download, with progress;
   - album queues ("Kind of Blue · song 4 of 9"), with [Pause] / [Cancel];
   - transfers;
   - library analysis ("Analysing your songs · 42%, paused until charging").
3. **Failed**, with retry.
4. **Done today**, collapsible.

The **actions** come from AlbumQueueViewModel (`pause`, `resume`, `cancel`,
`retryTrack`, `skipTrack`), DownloadsViewModel (`cancelSpotiFlacDownload`,
`cancelSoulseekDownload`, `retryFailedEngineDownload`) and
DiscoveryDownloadViewModel.

### 2.15 Sound

Sound **remambers the last segment** used. On first run it opens on Simple
(critique U5).

**Top:**

- an **On/Off** master switch (`setEnabled`);
- an "Active" line in words: "Auto tone for this song · Clarity Live
  medium", or "Off: untouched".

**Segments:**

| Segment | Contents |
|---|---|
| **Simple** (default) | Preset chips (`applyPreset`) + **Auto: tune each song** (`toggleAuto`) + [Flat]. For the Passenger, this is all they need (F10). |
| **Graphic** | Band sliders (`setBand`) with dB labels and preamp. |
| **Winamp** | The Winamp-style live EQ screen, **preserved** as a product requirement. Only the frame and the font change; the screen itself is kept. |
| **Clarity Live** | Switch + Intensity + Balance (`toggleClarity`, `setClarityIntensity`, `setClarityBalance`), with "What it does" in one sentence. |

**When bit-perfect USB is on:** a banner reads "Sound settings are skipped:
audio goes untouched to your DAC" with [Change] (→ Play on).

### 2.16 Settings

The sections follow the IA:

| Section | Settings |
|---|---|
| **Look** | Theme (System / Light / Dark), True black, Wallpaper colours |
| **Playback** | Crossfade (Off–12 s), Volume levelling (Off / Per song / Per album), Keep playing when the app is closed |
| **Mixes** | Default mix style, Familiar ↔ New, Similar ↔ Varied, Keep going online (switch, source, mobile data, auto-keep) |
| **Library** | Analyse only while charging, Rescan now, Download folder |
| **Adding music** | Preferred source, Soulseek account, Parallel downloads (Experimental) |
| **About** | Version (build 173), Licences, Install guide |

### 2.17 Tag editor, FLAC check, Tidy up

- **Tag editor:**
  - Find a song (search), then edit the fields: Title, Artist, Album and
    Album artist.
  - Artwork: Change / Remove / Restore original.
  - [Save] is in the thumb zone; [Revert] sits in the overflow.
  - Under Save: "Saved in Harmony; the file itself isn't changed".
- **Check a file (FLAC):** pick a file to get a verdict card.

  | Verdict | Colour |
  |---|---|
  | "Real lossless" | moss |
  | "Probably converted from a lossy file" | slate |
  | "Not FLAC" | — |

  A details list follows, then [Delete] for suspect files only, with a
  confirmation.
- **Tidy up library:**
  - The list of suggested removals, each with a reason and a Keep/Remove
    switch.
  - [Remove N songs] is a destructive button, followed by system consent;
    there is also [Decide later].
  - Nothing is removed without the system dialog.

---

## 3. Key flows

### Flow A: Start and steer (J1 + J2; car and desk)

```
Launch → Listen
  ├─ [Resume] ───────────────────────────────▶ playing (1 tap)
  ├─ [Mix my library] ───────────────────────▶ playing + mix (1 tap)
  └─ (Calm) mood chip ───────────────────────▶ playing + calm mix (1 tap)
Mini player ─tap/swipe up─▶ Now Playing
  ├─ swipe record → next        (glance, no aim)
  ├─ (Calmer) ───────────────▶ energy −0.2, queue regenerates, snackbar "Calmer from the next song"
  ├─ ⓘ reason ──────────────▶ "Why this song" sheet
  └─ swipe up ──────────────▶ Queue
Android Auto: browse tree Recent · Mixes · Playlists · Albums · Artists (unchanged service)
```

### Flow B: Get a song or album (J3)

```
Add → type "kind of blue" → results (Songs | Albums)
  ├─ Album "Kind of Blue" [Get] ─▶ queued ─▶ Activity pill ●1
  │       └─ row: "4 of 9 songs" ─▶ done: "Added to your library ✓" [Play]
  ├─ Song [Get] ─▶ inline progress ─▶ ✓ Added [Play]
  ├─ needs verification ─▶ [Open browser] ─▶ mornye://callback ─▶ auto-resume
  └─ failed ─▶ "Not found on Tidal, Qobuz or Deezer." [Try another way] ─▶ Soulseek/YouTube, pre-filled
Paste link ─▶ detected kind ─▶ matching action card
Identify ─▶ Listen ─▶ match ─▶ [Get it] ─▶ same as Song [Get]
```

### Flow C: Bring a Spotify playlist (J4)

```
Add › From Spotify
  ├─ Paste link (default) ─────────────────────────────┐
  └─ Connect account ─▶ 3-step guide ─▶ browser ─▶ back ─▶ Your playlists ─┤
                                                                           ▼
              Match: "28 in library · 12 to get · 2 not found"
              [Get 12 songs and create playlist]
                       ▼
              Activity (can leave) ─▶ Done: "Road Trip is ready: 38 of 40" [Play]
```

### Flow D: Pair with the computer (J7)

```
Add › From your computer ─▶ searching… ─▶ list ─▶ tap PC ─▶ 4-digit code ─▶ Paired
   ─▶ Now Playing › Play on › "Desk PC" ─▶ playing on PC, phone is the remote
   ─▶ PC offers files ─▶ Activity "Needs you" ─▶ Accept ─▶ transfer ─▶ in Library
```

### Flow E: Fix the sound (J8)

```
Now Playing › Sound ─▶ Simple: preset chip / Auto ─▶ applied instantly
                    └▶ Graphic / Winamp / Clarity for the Curator
```

### Flow F: First run

```
Launch ─▶ Listen (empty) ─▶ "Let Harmony see your music" [Allow access]
   ─▶ system READ_MEDIA_AUDIO ─▶ scan (progress in Activity) ─▶ Listen populated
   ─▶ (notifications asked only when the first download or mix starts)
```

Every permission is asked at the moment it is needed, never on launch:

- audio access on the empty Listen screen;
- the microphone in Identify;
- notifications at the first background task.

---

## 4. Peak and end moments

| Moment | Treatment |
|---|---|
| A song or album lands in the library | The record slides into the sleeve; "Added to your library ✓"; [Play] |
| Spotify playlist ready | A summary card with the counts and [Play] |
| Taste round finished | "You liked 7" + [Make a playlist] |
| A song identified | The artwork fades in; title in `nameL` |
| A session ends (pause for 30 min or more) | The Resume card on Listen says where you stopped |

All these moments are calm: no confetti, and the motion respects reduced
motion.
