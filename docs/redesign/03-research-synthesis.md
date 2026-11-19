# Research Synthesis: Harmony Redesign, Phase 1

`[ACTIVE SKILL: design:research-synthesis]`

| Item | Detail |
|---|---|
| **Method** | Desk synthesis: functional extraction from APK build 173, a code audit of builds 172–173, and the proto-personas, JTBD and contexts from `02-user-research.md`. |
| **Participants** | 0 real participants. The owner's statements are used as the only first-person input. |
| **Date** | November 2026 |
| **Confidence** | Every theme is graded **H** (seen in code, screens or the audit), **M** (owner said) or **L** (inferred). There are no user quotes, because none exist. Quotes are not invented. |

## Executive summary

Harmony does far more than a player usually does: 33 capabilities, 12 data
tables and 14 external services. Its interface shows that breadth through
the machinery that does the work: sources, providers, engines, sliders.

The three daily jobs are:

- **start music**;
- **steer it at a glance**;
- **get a song into the library**.

Each one is buried under choices that only the Curator (the owner) can make
with confidence. The Passenger and the Driver pay for that.

Build 173 fixed the visual chaos: one look instead of 4, and one type scale.
The structure behind it has not changed:

- five tabs, organised by feature rather than by job;
- a Downloads tab that holds seven tools;
- errors written for the person who wrote the code.

The redesign has to reorganise around *jobs*, speak in *outcomes*, and keep
the two things that make Harmony itself: **the record** and **the truth
about the file**.

## Key themes

### Theme 1: The machine is in front of the music

- **Confidence:** H
- **Observations:**
  - Downloads exposes four sources (SpotiFLAC, Soulseek, YouTube → FLAC,
    direct URL), two modes (song and album), recognition, Spotify import and
    the YT converter, all under one tab. `DownloadsViewModel` has 50+ public
    actions.
  - The user must choose a source before searching.
  - Provider names, "verification", "extension" and "engine" all appear in
    status text.
- **Implication:** The Passenger cannot finish J3 ("get this song") without
  first learning how the app works inside. The source is an *implementation
  detail* for B, and an *override* for A.

### Theme 2: Navigation follows features, not jobs

- **Confidence:** H
- **Observations:**
  - The top level is Home, Library, Playlists, Discover and Downloads.
  - Equalizer, Settings and Recognize sit behind Home.
  - Playlists and Library hold the same thing (my music) in two tabs.
  - Recognize (a way to *add* music) lives under Home, away from Downloads.
  - The Spotify import lives under Downloads, although it produces a
    *playlist*.
- **Implication:** Users have to know which tool solves a job before they
  can find it. Grouping by job gives three verbs:
  - **Listen**: Home, the player, the mix.
  - **Library**: my songs and playlists.
  - **Add**: everything that brings music in.

### Theme 3: Automatic choices are powerful but opaque at the moment they matter

- **Confidence:** H for the controls, M for usage.
- **Observations:**
  - Smart Shuffle has a style, three sliders (familiarity, discovery,
    variety), an energy slider, a mood filter and "journey to mood".
  - Online mix has its own source, mobile-data and auto-keep settings.
  - Pick reasons exist; build 173 moved them into the queue as tags, but a
    driver cannot read a queue.
- **Implication:**
  - Steering has to be one verbal action ("calmer", "more like this", "less
    of this") rather than a set of sliders.
  - Reasons belong where the eye already is: under the current song.
  - The sliders stay, as *tuning* for A.

### Theme 4: Status and failure are reported in system terms, not in "what now"

- **Confidence:** H
- **Observations:**
  - Errors come straight from the engines: provider refusal, Soulseek
    `INVALIDUSERNAME` passed through, verification loops, FFmpeg or yt-dlp
    failures.
  - Long work (album queues, discovery downloads, PC transfers) runs in
    WorkManager, but its progress is scattered across a banner, a shelf and
    a pill.
  - The 172 critique scored *Error Recovery 2/4* and *Help 2/4*.
- **Implication:**
  - Every failure needs three parts: *what happened*, *what the app already
    tried* (for example, the next provider), and *one next action*.
  - Ongoing work needs **one place** for "what Harmony is doing for me
    right now".

### Theme 5: Glance contexts are first-class, but the UI was built for collecting sessions

- **Confidence:** M for contexts, H for UI facts.
- **Observations:**
  - The owner names the car and the desk as two of the three dominant
    contexts.
  - Build 172 had 85+ texts at 8–10 sp and translucent chrome. Build 173
    raised the floor to 11 sp.
  - 11 sp is readable in the hand, but not in a mount at arm's length.
  - Primary transport sits at mid-screen in landscape.
- **Implication:**
  - Glanceable surfaces need large type: the mini player, Now Playing and
    the notification, read at about 60 cm.
  - The primary controls belong in the thumb zone.
  - No dialogs on the playback path.

### Theme 6: The truth about the file is the trust contract

- **Confidence:** H
- **Observations:**
  - The format card, FLAC check, bit-perfect USB and the download records
    (provider, bit depth, ISRC) are things almost no other player shows.
  - They are what the Curator relies on.
- **Implication:** Keep and strengthen them, but say them plainly:
  "24-bit / 96 kHz FLAC · untouched to your DAC", not raw codes. The
  Passenger also benefits from a simpler phrasing, such as "Lossless".

### Theme 7: Identity lives in two objects

- **Confidence:** M (the owner's hard requirement, stated twice)
- **Observations:**
  - The vinyl record (motion and gestures) and the Winamp EQ are the only
    two elements the owner protects.
  - Everything else was negotiable across three redesigns.
- **Implication:** The new UI is built *around* the record as the hero of
  Listen, and the Winamp EQ stays as a signature mode of Sound. Warmth
  comes from these objects and from the palette, not from decoration.

## Friction points (prioritised)

| # | Friction | Who | Job | Severity | Evidence |
|---|---|---|---|---|---|
| F1 | Must pick a download source before searching for a song | B (A tolerates it) | J3 | **High** | DownloadsViewModel flow; source rail comes first |
| F2 | Seven acquisition tools in one tab, with no single "add music" entry point | B | J3, J4 | **High** | §3.5 capabilities 21–30 |
| F3 | Failures read as engine messages with no next step | A, B | J3, J4 | **High** | Error strings; 172 critique (Error Recovery 2/4) |
| F4 | Steering the mix needs sliders. There is no "calmer" or "more like this" on Now Playing. | B, C | J2 | **High** | PlayerViewModel; Smart Shuffle UI |
| F5 | Pick reasons live in the queue, not under the song | C | J2 | Med | Build 173 QueueTags |
| F6 | Background work progress is scattered (banner, shelf, pill, notification) | A | J3, J7 | Med | DownloadStatusBanner, AlbumDownloadsShelf, NotificationPill |
| F7 | Library and Playlists are split into two tabs | A, B | J1, J9 | Med | Routes |
| F8 | Spotify connect needs the user's own Client ID, an unexplained developer step | B | J4 | **High** (a blocker) | §6.1; no Harmony app is registered |
| F9 | Recognize is hidden under Home | B | J3 | Med | Routes |
| F10 | Four EQ modes (Graphic, Winamp, Auto, Clarity) overlap for one job | B | J8 | Med | EqualizerViewModel |
| F11 | Glance text and targets are sized for the hand, not for a mount | C | J1, J2 | Med | 11 sp floor; 172 audit |
| F12 | No honouring of "Remove animations" for the spinning record | Any | — | Med (a11y) | 172 audit P2 |
| F13 | English-only literal strings; Romanian is impossible without a refactor | B | All | Med | 0 `stringResource` |
| F14 | Online mix state (paused on mobile data, out of songs) is visible only in the queue | B, C | J1 | Med | `mixStatusFor` in the queue sheet |

## Insights → opportunities

| Insight | Opportunity | Impact | Effort |
|---|---|---|---|
| The source is a detail for B and an override for A | **One search box for "Add"**: results from the best source by default; "Get it another way" as a secondary path | High | Med (UI only: the VM already has `selectPreferredDownloadSource` and alternatives) |
| Jobs fall into three verbs | IA of **Listen · Library · Add**, plus Sound and Settings as utilities | High | Med |
| Errors need "what now" | An error model of *what happened → what we tried → one action* (Step 6 maps every API error to it) | High | Low–Med |
| Drivers steer verbally | Steering chips on Now Playing: *Calmer · More energy · More like this · Less of this*, mapped to the existing energy, mood and similarity actions | High | Med |
| Background work is scattered | One **Activity** surface (downloads, album queues, transfers, analysis), opened from a persistent indicator | Med | Med |
| The Client ID is a blocker | A guided Spotify set-up with 3 steps, copyable redirect URI, a "Why?" explanation, and the paste-link path promoted as the no-account alternative | High | Low |
| The record is the identity | Make the record the hero of Listen in both orientations, honour Remove animations, and keep its gestures | High | Low (preserve) |

## User segments

| Segment | Characteristics | Needs | Rough share |
|---|---|---|---|
| Curator (A) | Builds the library, uses every source, Connect | Control, truth, overrides, batch work | 1 person (the owner), but most of the usage |
| Passenger (B) | Mostly plays, adds songs occasionally | One-tap starts, plain words, a default path | Most of the friends (assumed) |
| Driver (C) | A context of A or B | Glanceable type, thumb-zone controls, no dialogs | A large share of listening time (owner statement) |

## The 5 Core UX Principles

These are the rules every screen in Phases 2–4 is checked against.

### 1. Music first, machinery on request

The music is what people see. Sources, providers, engines and sliders are
one tap deeper, offered as *overrides* for those who want them. The default
path never needs knowledge of how Harmony works inside.

*Test:* can a Passenger finish the job without meeting a technical noun?

*Answers:* F1, F2, F10.

### 2. Organise by what I want to do

The structure follows jobs: **Listen** (what is playing and what comes
next), **Library** (what I own, including playlists), **Add** (every way
music comes in). Each job has one home and one entry point.

*Test:* for any of the jobs J1–J9, is there exactly one obvious place to
start?

*Answers:* F2, F7, F9.

### 3. Readable at arm's length, workable with one thumb

Every playback surface is designed for a phone in a car mount:

- glance text of 16 sp or more, with the title at 22 sp or more;
- primary controls of 56 dp or more in the lower half;
- no dialogs on the playback path.

Collecting screens may be denser, but they never go below the system's
accessible minimums.

*Test:* can the Driver read the song and skip it in under 2 s, at 60 cm?

*Answers:* F4, F11.

### 4. Say why, say what's happening, say what now

Every automatic choice shows its reason where the eye already is, and every
piece of background work is visible in one place. Every failure has the
same three parts:

- what happened, in plain words;
- what Harmony already tried;
- one clear next action.

*Test:* after any failure, does the user know the single next tap?

*Answers:* F3, F5, F6, F8, F14.

### 5. Warm by nature, true to the file

The warmth comes from what the app shows and how it talks:

- what belongs to the music: the turning record, album colour and the
  Winamp EQ as a signature;
- the palette;
- a calm voice.

It never comes from decoration. The audio facts are always exact (format,
bit depth, untouched or processed) and phrased for people. Motion respects
the system's Remove animations setting.

*Test:* is every visual flourish tied to the music or the file, and is every
technical fact both accurate and plain?

*Answers:* Themes 6–7, F12, F13.

## Questions for further research

1. Do friends (B) actually use Downloads, and which source succeeds most
   often for them? Usage data would come from `download_records.source`,
   aggregated on-device with consent.
2. How often is Harmony used through Android Auto rather than on the
   phone? (Asking the owner is enough to start.)
3. Do people want Romanian as the default? Is the absence of a translation
   a real barrier for any friend?
4. Which EQ mode do people use? Could Auto plus a small set of presets cover
   B, with Graphic and Winamp left for A?
5. Is Connect (the PC link) used by anyone besides the owner?

## Methodology notes

- No primary research exists. This synthesis rests on code and APK facts
  (high confidence), on the owner's statements (medium), and on inferences
  (low), each labelled.
- The usability study in `02-user-research.md` §4 is designed to validate
  or overturn these principles. Its baseline run on build 173 should happen
  before the redesign ships.
- Bias: the author of this synthesis also built the 172/173 redesign. The
  study's comparison against build 173 is the check on that.
