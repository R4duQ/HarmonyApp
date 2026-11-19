# Design Critique: Harmony "Listening Room" Proposal

`[ACTIVE SKILL: design:design-critique]`

- **Reviewed:**
  - `04-design-system.md`
  - `05-screens-and-flows.md`
  - `06-ux-copy.md`
- **Checked against:**
  - the 5 Core UX Principles (`03`)
  - the personas and journeys (`02`)
- **Stage:** specification, before any code. The design has not been tested
  on a device yet.

## Overall impression

The structure is right. **Listen · Library · Add** turns 7 tools into 3
jobs, and "Get, with other ways underneath" removes the biggest Passenger
blocker (F1). The warm palette and the label-style grotesque used only for names give the
app a personality that does not depend on decoration.

The biggest risk is **Now Playing**. It is asked to carry eleven things,
and on a typical 720 dp-tall phone they do not fit with the record at the
size the brand needs. The type scale has also grown to 10 sizes, which
works against the "one system" goal.

## Usability

| # | Finding | Severity | Recommendation (folded into the docs) |
|---|---|---|---|
| U1 | **Now Playing overflows.** Top bar 56, record ≈ 282, title 34, artist 24, reason 24, format 28, waveform 66, transport 72, steering 48, action row 56 and padding ≈ 96 add up to **≈ 786 dp**, against about 728 dp usable on a 800 dp phone. The record would shrink well below "central and dominant". | 🔴 Critical | 1. **Reason and format share one line.** Show the reason when there is one, and the FormatTag at the end of that line; details stay one tap away. 2. **The record takes the remaining space** (weight 1, aspect-locked, min 200 dp), as build 173 already does. 3. **Steering collapses on short screens.** Below 720 dp height, or in landscape, the 4 chips become a single **[Steer]** button in the action row that opens the steering sheet. |
| U2 | **Discover previews "play automatically on Wi-Fi"** would interrupt whatever is playing, which breaks the user's own music. | 🔴 Critical | Previews play **on tap only**. Main playback pauses and resumes after the preview, as `PreviewController` already does. |
| U3 | **Mood chips on Listen** call two ViewModels (`startSmartMix`, then `onMoodFilterSelected`). If the mood is applied after the queue is built, the first song may not match the mood. | 🟡 Moderate | Apply the mood filter **first**, then start the mix. Phase 4 should test that the first song carries the mood. |
| U4 | **Activity pill hidden when idle.** Users will not learn where the progress lives until something is running. | 🟡 Moderate | Add › "In progress" also shows it inline, and the first Get shows a one-time coach line: "Progress lives here" pointing at the pill. |
| U5 | **Sound defaults to Simple**, but the owner protects the Winamp EQ as a signature and uses it. | 🟡 Moderate | Sound **remambers the last segment**. The first-run default is Simple. |
| U6 | **The Tools menu (⋯) in Library** hides Tag editor and FLAC check. | 🟢 Minor | Both are also in each song row's More sheet ("Edit tags", "Check this file"). Leave Tools for whole-library actions only. |
| U7 | **"Get" in Romanian.** "Ia" reads oddly, and "Adaugă" clashes with the Add destination. | 🟢 Minor | RO uses **"Descarcă"** for Get. The EN glossary rule (no "Download" on the default path) is about English jargon; in Romanian "descarcă" is the plain word. |
| U8 | **Landscape Now Playing on phones:** the right column (title, reason, waveform, transport, steering, action row) needs about 300 dp of a 360 dp height, which is tight. | 🟡 Moderate | Same rule as U1: in landscape, steering is the single **[Steer]** button. The waveform shrinks to 40 dp. |
| U9 | **Record overuse:** Now Playing, the album header *and* a "record into sleeve" success animation everywhere risk making the signature ordinary. | 🟢 Minor | The full record appears only on **Now Playing and the album header**. The success moment uses a check that morphs into a small sleeve glyph (24 dp). |

## Visual hierarchy

- **What draws the eye first:**
  - Listen: the Resume card ✔.
  - Now Playing: the record ✔.
  - Add: the search field ✔.
  - Library: the search and segments ✔. Songs come right after, which is
    acceptable.
- **Reading flow:** top-down everywhere, with primary actions low. On Now
  Playing the order is *what* → *why* → *where in the song* → *control* →
  *steer* → *more*, which matches the jobs.
- **Emphasis risk:**
  - Amber marks both the **primary action** and **what is playing**.
  - Lists are where the two can sit next to each other, a playing row next
    to a [Get] button.
  - The "playing" cue is amber text **plus** an equaliser glyph.
  - [Get] is a *tonal* (amberSoft) button, not filled.
  - So the two read differently, and the shape carries the meaning, not
    colour alone ✔.

## Consistency

| Element | Issue | Recommendation (folded in) |
|---|---|---|
| Type scale | **10 sizes** (34, 28, 22, 18, 17, 16, 15, 14, 13, 12) and 2 families. Too many: 17/16 and 15/14 are indistinguishable at a glance. | Consolidate to **7 sizes: 34 · 28 · 22 · 18 · 16 · 14 · 12**. `title` becomes 16/600 (same size as body, set apart by weight). `label` becomes 16/600 for buttons and 14/600 for chips. `caption` becomes 14/500 tnum. |
| Buttons | Heights of 48 and 56 dp are both allowed with no rule for which. | **56 dp** for the single primary CTA of a flow, and on glance surfaces. **48 dp** everywhere else. |
| Terminology | "Added", "In your library" and "Kept" all mean the same outcome. | The *state* is **"In your library"**. The *event* is "Added to your library". "Keep" is only the *button* on online songs. |
| Sheets vs pages | Tune, Play on, Mood and Activity are sheets; Identify is a full-screen modal; Spotify, Discover and Connect are pages. | The rule is: **a sheet** when the task returns to where you were (steering, output, activity); **a page** when the task has steps or ends somewhere else. Identify becomes a page too, since it ends in Get or Play. |

## Accessibility (first pass; the full audit is Step 8)

- **Colour contrast:**
  - Every text pair passes 4.5:1 and every non-text pair passes 3:1, in
    both themes (measured in `04` §1.3).
  - The album tint has a contrast guard.
- **Touch targets:**
  - Most targets are 48 dp or larger, and transport controls are 56/72 dp.
  - Filter and steering chips are 40 dp visible but have a 48 dp target.
  - The letter fast-scroller is 48 dp wide.
- **Readability:**
  - The floor is 12 sp; glance text is 16 sp or larger; everything uses sp.
  - Risk: Bricolage Grotesque at 22 sp in the mini player truncates long titles. That
    is accepted, with ellipsis, and the full title goes in the content
    description.
- **Motion:** reduced motion is specified for the record. Identify's pulse
  must also stop under reduced motion.

## What works well

- **One search for Add**, with the *best quality said in words* on each
  result. It serves the Passenger and the Curator in one row.
- **Three-part errors with a hidden *Details* link** keep the Curator's raw
  facts available without making the Passenger read them.
- **Steering in words** ("Calmer", "Livelier") uses the existing energy and
  mood engine with no backend change.
- **The Spotify flow leads with "paste a link"**, so the Client ID blocker
  (F8) becomes an advanced path instead of a wall.
- **The record is protected and given room**: its exact motion, gestures
  and the reduced-motion behaviour are written down.

## Priority recommendations

1. **Fix the Now Playing budget (U1, U8).** The record flexes, reason and
   format share one line, and steering collapses on short screens and in
   landscape.
2. **Stop auto-preview (U2).** Never interrupt the user's music.
3. **Consolidate the type scale to 7 sizes**, with a button height rule.

All three are applied to `04` and `05` in this step.

## Addendum: revising the palette and display face (after Steps 7 and 8)

When the pages were checked one more time, the first palette was dropped:
cream ground, a soft serif (Fraunces) for names, and a terracotta "ember"
accent. Together these are the most generic "warm" combination, and they
would make Harmony look templated. Principle 5 asks for warmth that comes
from the music's own world. The changes:

- **Accent:** a valve amp's **amber glow** (`amber` fill + `amberText` for
  text), instead of terracotta.
- **"Waiting" status:** cool **slate** instead of ochre, so it can't be
  confused with the accent.
- **Names:** set in **Bricolage Grotesque** instead of the serif. It is a
  warm grotesque with optical sizes, like the type on a record label.

All contrast was re-measured; see `04` §1.3 and `08`.
