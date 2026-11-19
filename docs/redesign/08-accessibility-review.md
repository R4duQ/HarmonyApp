# Accessibility Audit: Harmony "Listening Room" Spec

`[ACTIVE SKILL: design:accessibility-review]`

| Item | Detail |
|---|---|
| **Standard** | WCAG 2.1 AA, applied to native Android (Compose semantics, TalkBack, Switch Access, hardware keyboard) |
| **Scope** | `04-design-system.md`, `05-screens-and-flows.md` (with the critique fixes from `07`), and `06-ux-copy.md` |
| **Method** | Spec review plus measured contrast. The Phase 4 code is then verified with Compose semantics tests and the screenshot tests at font scale 2.0. |

## Summary

**Issues found: 23.** Critical: 5 · Major: 12 · Minor: 6.

Contrast already passes everywhere. The issues are in **gestures without
alternatives**, **custom controls without semantics** (the record, the
waveform, the Winamp EQ), **fixed heights under large text**, and **status
messages that are not announced**. Each fix below is a requirement for
Phase 4.

## Findings

### Perceivable

| # | Issue | WCAG | Severity | Fix (required in Phase 4) |
|---|---|---|---|---|
| A1 | The album tint mixes artwork colour into `record` and `surface`, so text contrast can drop. | 1.4.3 | 🟡 Major | At runtime, re-check every text pair after tinting (`muted` and `onRecordMuted` must stay ≥ 4.5:1) and drop the tint if any fails. Unit-test with pure red, yellow and white artwork. |
| A2 | Status (In your library, Needs a check, Couldn't get it) could be read from colour alone. | 1.4.1 | 🟡 Major | Every status shows **an icon and a word** (already in the copy deck §8). Icons carry no content description when the word is present. |
| A3 | The mini player title truncates with ellipsis (Bricolage Grotesque at 22 sp). | 1.3.1 | 🟢 Minor | The mini player is **one merged node**: "Now playing: *full title*, by *artist*. Playing." Its actions are Play/Pause, Next, Previous and Open player. |
| A4 | **Large text (200%) breaks fixed heights**: the 72 dp mini player, 64/72 dp rows, the 80 dp bottom bar and the Now Playing budget. | 1.4.4 | 🔴 Critical | Heights become **minimums** (`heightIn(min=…)`), never fixed. Above font scale 1.3: the mini player title uses `title` (16 sp); the bottom bar hides nothing; Now Playing becomes **vertically scrollable** with the record at a minimum of 160 dp and the transport kept on screen. Screenshot tests run at fontScale 2.0. |
| A5 | The record artwork and sleeve are visual. | 1.1.1 | 🟢 Minor | The sleeve image has the description "Cover of *album*". The record graphic is decorative (no description); its actions live on the merged artwork node (A9). |
| A6 | The waveform is a custom drawing. | 1.1.1, 4.1.2 | 🔴 Critical | Give it semantics as a **slider**: `progressBarRangeInfo` (0–duration), `stateDescription` "1:42 of 9:22", and `setProgress` for TalkBack's volume-key adjust. Seek steps are ±10 s. |
| A7 | Online mix and Activity changes happen without being announced. | 4.1.3 | 🟡 Major | Every StatusLine and the queue mix-status line use `liveRegion = Polite`. Snackbars announce automatically. Failures that need action say so ("…. Retry available"). |

### Operable

| # | Issue | WCAG | Severity | Fix |
|---|---|---|---|---|
| A8 | **Gesture-only actions**: swiping the record (next/prev), swiping down to close, swiping up for the queue, swipe-to-remove, drag-to-reorder, the Discover swipe deck, mini player swipes and long-presses. | 2.5.1, 2.1.1 | 🔴 Critical | Every gesture has a **visible control or a custom accessibility action**: transport buttons (next/prev), the Close button, the "Up next" button, the Remove action plus "Move up"/"Move down" on queue and playlist rows, the deck's labelled buttons, and mini player custom actions. Long-press selection is also reachable with **Select** in the overflow. |
| A9 | Tapping the record toggles play, which is an unlabelled target. | 2.5.3, 4.1.2 | 🟡 Major | The record and artwork node: role Button, label "Play" or "Pause", plus custom actions Next song, Previous song, Open queue. |
| A10 | Chips are 40 dp visible. | 2.5.5 (AAA), Android 48 dp | 🟡 Major | `minimumInteractiveComponentSize()` on every chip, icon and toggle, giving a 48 dp target. Chip rows keep 8 dp gaps so targets don't overlap. |
| A11 | The letter fast-scroller has small per-letter targets. | 2.5.5 | 🟢 Minor | Hide the scroller from accessibility. Letter dividers are **headings**, so TalkBack users jump letter by letter with heading navigation. |
| A12 | Focus order on Now Playing is not defined. | 2.4.3 | 🟡 Major | Use `isTraversalGroup` with this order: Close → source → More → artwork → title → Favourite → reason → format → seek → Previous → **Play/Pause** → Next → Shuffle → Repeat → steering → Up next → Play on → Sound. The landscape order is the same (left group, then right group). |
| A13 | Hardware keyboard and Switch Access focus is not visible on custom surfaces. | 2.4.7 | 🟡 Major | Focus indication: a **2 dp ink ring with 2 dp offset** on every focusable item, ≥ 3:1 in both themes (14.68 light, 16.21 dark). Media keys and Space toggle play when Now Playing is focused. |
| A14 | Snackbar Undo can time out before TalkBack users reach it. | 2.2.1 | 🟡 Major | Snackbars with an action use `SnackbarDuration.Long`, extended with `AccessibilityManager.calculateRecommendedTimeoutMillis`. |
| A15 | Motion: the record turns forever, and Identify pulses. | 2.3.3 (AAA), 2.2.2 | 🔴 Critical | Respect **Remove animations** (`ANIMATOR_DURATION_SCALE == 0`): the record stops, the pulse becomes a static ring, and transitions become crossfades. The record also **stops when paused** (it already tucks in), which satisfies 2.2.2's pause mechanism. |
| A16 | Sheets can trap focus or lose it. | 2.4.3 | 🟢 Minor | Sheets move focus to their title on open and give it back to the opener on close. The drag handle has the action "Close sheet". |

### Understandable

| # | Issue | WCAG | Severity | Fix |
|---|---|---|---|---|
| A17 | Search fields and OTP boxes need labels. | 3.3.2 | 🟡 Major | Search: label "Search your library" or "Search to add music"; the placeholder is not used as the label. OTP: a single field read as "Pairing code, 4 digits", with the boxes drawn but not focusable separately. |
| A18 | Errors must name the item and the fix. | 3.3.1, 3.3.3 | 🟢 Minor | Already handled by the three-part copy (`06` §7). The error text is linked to its row (`error()` semantics on the field or row). |
| A19 | The language switches between EN and RO. | 3.1.1 | 🟢 Minor | Strings live in resources with locale qualifiers. Song names keep their own language and are not marked. |

### Robust (name, role, value)

| # | Control | Required semantics | Severity |
|---|---|---|---|
| A20 | Favourite, steering chips, the Sound On/Off master, mix switches | `toggleable` with state ("Favourite, on"; "Calmer, selected") | 🟡 Major |
| A21 | **Winamp EQ (preserved screen)**, Graphic EQ bands, Clarity sliders | Each band is a **slider node**: label "60 hertz", `stateDescription` "+3 decibels", `setProgress`, ±1 dB steps. The preamp works the same way. | 🔴 Critical (the screen is custom-drawn) |
| A22 | Library segments, Sound segments, Tune style cards | `selectableGroup()` with each option `selected` and role Tab or RadioButton | 🟡 Major |

## Colour contrast check

The palette was revised after the critique: the accent is now an amber valve
glow, and "waiting" uses slate. These values come from the revised palette.

| Element | Foreground | Background | Light | Dark | Required | Pass |
|---|---|---|---|---|---|---|
| Body text | ink | ground | 14.68 | 16.21 | 4.5 | ✅ |
| Secondary text | muted | raised (worst) | 5.50 | 7.15 | 4.5 | ✅ |
| Primary button text | onAmber | amber | 8.25 | 9.80 | 4.5 | ✅ |
| Selected chip text | onAmberSoft | amberSoft | 10.45 | 9.77 | 4.5 | ✅ |
| Playing title / selected label | amberText | ground · amberSoft | 5.92 · 5.34 | 11.89 · 7.77 | 4.5 | ✅ |
| Mini player artist | onRecordMuted | record | 8.31 | 9.57 | 4.5 | ✅ |
| Play icon on the live button | record | recordLive | 9.71 | 11.18 | 3.0 | ✅ |
| Progress line | recordLive | record | 9.71 | 11.18 | 3.0 | ✅ |
| Field and checkbox border | outline | surface | 3.90 | 4.19 | 3.0 | ✅ |
| Focus ring | ink | ground | 14.68 | 16.21 | 3.0 | ✅ |
| Status words (worst case) | moss | raised | 5.16 | 8.22 | 4.5 | ✅ |
| **Amber fill as a state cue** | amber | ground | **1.89** | 10.64 | 3.0 | ❌ in light, so it is **never used alone** (A23) |
| Hairline | line | ground | — | — | decorative | n/a |

**A23 (🟡 Major, 1.4.11).** In the light theme, a selected nav item or chip
cannot rely on an amber fill. It also changes the **icon to filled**, puts
the **label in amberText and semibold**, and adds a check to chips.

## Keyboard, D-pad and Switch Access

| Element | Focus order | Enter / Space | Escape / Back | Arrow keys |
|---|---|---|---|---|
| Bottom bar / rail | After content | Go to destination | — | Move between items |
| Mini player | Before the bottom bar | Open player | — | — |
| Now Playing | See A12 | Activate | Close player | Seek ±10 s when the seek bar is focused |
| Queue row | Row → drag handle | Play | Close sheet | — (Move up/down via actions) |
| EQ band | Left to right | — | Back | ±1 dB |
| Sheets | Title first | Activate | Close sheet, focus returns to the opener | — |

## Screen reader (TalkBack): key announcements

| Element | Announced as |
|---|---|
| Mini player | "Now playing: So What, by Miles Davis. Playing. Button. Double-tap to open player. Actions available." |
| Play button (Now Playing) | "Pause. Button." / "Play. Button." |
| Seek | "Song position, 1:42 of 9:22. Slider." |
| Reason line | "Why this song: you play it most on weekday mornings. Button." |
| Format tag | "Hi-Res lossless, 24-bit, 96 kilohertz. Button. Double-tap for file details." |
| Queue row (online) | "So What, Miles Davis, online, similar to Blue in Green. Actions: Keep, Remove, Move up, Move down." |
| Activity pill | "Activity: 3 running, 1 needs you. Button." |
| Get button | "Get So What. Hi-Res available." Then the live update: "Getting So What, 34 percent", and finally "So What added to your library." |
| Letter divider | "A. Heading." |
| Winamp band | "60 hertz, plus 3 decibels. Slider." |

## Priority fixes (all required in Phase 4)

1. **Gesture alternatives (A8, A9).** Without them, TalkBack and Switch
   Access users cannot change songs on the record, reorder the queue, or
   rate in Discover.
2. **Semantics for custom controls (A6, A21).** The waveform and the
   preserved Winamp EQ are otherwise invisible to TalkBack.
3. **Large text (A4).** Heights become minimums, Now Playing scrolls, and
   the screenshot tests run at 200%.
4. **Reduced motion (A15).** The record stops when animations are off.
5. **Live regions and timeouts (A7, A14)**, then name, role and value on
   toggles and segments (A20, A22), and the focus order (A12, A13).

## Verification plan (Phase 4)

- **Compose UI tests** assert the semantics of the mini player, Now Playing
  transport, seek, queue row actions, EQ bands and segments: role, state,
  custom actions and traversal order.
- **Screenshot tests** run every redesigned screen in light, dark and
  fontScale 2.0.
- **A unit test** for the album-tint contrast guard (A1).
- **Manual TalkBack pass** on a device. This needs the owner's phone, since
  there is no emulator here.
