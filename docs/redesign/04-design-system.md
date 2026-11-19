# Harmony Design System: "Listening Room"

`[ACTIVE SKILL: design:design-system]` (mode: **extend**, new system from a
clean slate)

**Idea.** Harmony should feel like a warm room with a record player in it:

- the **amber glow of a valve amplifier** as the one accent, for what you can
  act on and what is playing;
- **walnut and sleeve paper** for the neutrals;
- **the record** as the one object the room is arranged around;
- **a characterful grotesque** (Bricolage Grotesque, with an optical size
  axis) for names, like the type on a record label, and a friendly sans for
  everything you read and tap.

This avoids the generic "cream + serif + terracotta" look; the warmth comes
from Harmony's own world.

It is built to satisfy the 5 Core UX Principles. Each token below cites the
principle it serves, P1–P5.

---

## 1. Colour

### 1.1 Roles

| Token | Job | Rule |
|---|---|---|
| `ground` | The page | One ground everywhere (P2, consistency) |
| `surface` | Cards, sheets, the format card | One step off the ground |
| `raised` | Chips, pressed rows, artwork well, input fields | |
| `ink` | Primary text | |
| `muted` | Secondary text: artist, counts, durations | Passes 4.5:1 on every surface |
| `faint` | Placeholder icons, dividers you must see, disabled | Passes 3:1 (non-text) on every surface. **Never body text.** |
| `line` | Hairlines between rows | Decorative only (1.3–1.5:1) |
| `outline` | Field borders, checkbox and radio edges, focus ring base | Passes 3:1 |
| `amber` / `onAmber` | **The one accent (fill):** primary button, play button, progress, selected nav icon | One accent: no section colours (P5). Light theme: amber vs ground is 1.89:1, so **amber fill never carries state alone**; text on it is `onAmber` |
| `amberText` | The accent **as text or icon**: the playing song's title, a selected tab's label, links | Passes 4.5:1 on every surface |
| `amberSoft` / `onAmberSoft` | Selected chip, current row in a list, steering chip on | |
| `moss` | Success: "In your library", "Downloaded", "Lossless verified" | Always paired with an icon and a word |
| `slate` | Waiting or needs you: "Waiting for Wi-Fi", "Needs a quick check". It is cool on purpose, so it never reads as the accent | Icon plus word |
| `wine` / `onWine` | Error and destructive actions | Icon plus word; never colour alone |
| `record` / `onRecord` / `onRecordMuted` / `recordLive` | The mini player and the Now Playing stage | Dark in both themes, like a record sleeve |
| `scrim` | Behind sheets | `#000` at 40% (light) or 60% (dark) |

### 1.2 Values

| Token | Light | Dark |
|---|---|---|
| ground | `#F7F4EF` | `#15120F` |
| surface | `#FFFDFA` | `#1E1A16` |
| raised | `#EEE8DF` | `#29241F` |
| ink | `#26201A` | `#F5EEE5` |
| muted | `#665A4E` | `#BCAF9F` |
| faint | `#8A7E71` | `#877B6E` |
| line | `#E3DBCF` | `#38312A` |
| outline | `#8A7E71` | `#877B6E` |
| amber | `#F2A43A` | `#FFB54D` |
| onAmber | `#2A1803` | `#2A1700` |
| amberText | `#8C4F00` | `#FFC470` |
| amberSoft | `#FCE6C4` | `#4A3010` |
| onAmberSoft | `#4A2C00` | `#FFE2B8` |
| moss | `#3B6B35` | `#93CC88` |
| slate | `#335C91` | `#9DBDF0` |
| wine | `#A1283E` | `#FF8FA0` |
| onWine | `#FFFFFF` | `#3D0612` |
| record | `#211B16` | `#0E0B09` |
| onRecord | `#F7F0E6` | `#F7F0E6` |
| onRecordMuted | `#C1B3A3` | `#C1B3A3` |
| recordLive | `#FFB54D` | `#FFB54D` |

**AMOLED variant (dark only).** `ground` becomes `#000000`, `surface`
`#0E0B09` and `raised` `#1A1611`. Everything else stays.

**Material You (optional).** When dynamic colour is on, only `amber`,
`onAmber`, `amberText`, `amberSoft` and `onAmberSoft` are taken from the wallpaper's
primary tones. The ground, ink and status colours stay, so the app still
reads as Harmony.

**Album tint.** Now Playing and the album header may mix up to **12%** of
the artwork's dominant colour into `record` or `surface`, computed in code.
Text contrast is re-checked after mixing, and the tint is dropped if any
pair falls below its threshold (P5: the colour comes from the music).

### 1.3 Measured contrast (WCAG 2.1)

Measured with `scratchpad/ds/contrast.py`. Where a cell lists several
values, they are in the same order as the backgrounds named in that row.

| Pair | Light | Dark | Needs |
|---|---|---|---|
| ink / ground | 14.68 | 16.21 | 4.5 ✅ |
| muted / ground · surface · raised | 6.10 · 6.59 · 5.50 | 8.68 · 8.04 · 7.15 | 4.5 ✅ |
| faint / ground · raised (non-text) | 3.61 · 3.25 | 4.52 · 3.72 | 3.0 ✅ |
| amberText / ground · raised · amberSoft | 5.92 · 5.33 · 5.34 | 11.89 · 9.79 · 7.77 | 4.5 ✅ |
| onAmber / amber | 8.25 | 9.80 | 4.5 ✅ |
| onAmberSoft / amberSoft | 10.45 | 9.77 | 4.5 ✅ |
| moss · slate · wine / raised (worst surface) | 5.16 · 5.60 · 5.97 | 8.22 · 8.03 · 7.09 | 4.5 ✅ |
| onRecord · onRecordMuted · recordLive / record | 15.05 · 8.31 · 9.71 | 17.34 · 9.57 · 11.18 | 4.5 ✅ |
| amber fill / ground (light) | 1.89 | 10.64 | — : this is why amber never carries state alone in light |

## 2. Typography

There are two families, both under the OFL and both bundled. Each covers
ă â î ș ț.

- **Bricolage Grotesque**, variable: `opsz` 12–96, `wdth` 75–100, `wght`
  200–800. It is a warm, slightly quirky grotesque with optical sizes, used
  **only for names**: song, album, artist, playlist and screen titles. The
  axes are set to opsz = the size, wdth 100, wght 650.
- **Figtree**, variable: `wght` 300–900. It is a friendly sans with
  **tabular figures** (`tnum`), used for everything else.

Seven sizes in total: **34 · 28 · 22 · 18 · 16 · 14 · 12**. This scale
was consolidated by the Step 7 critique.

| Role | Family | Size / line | Weight | Use |
|---|---|---|---|---|
| `display` | Bricolage | 34 / 40 | 600 | Screen title (Listen, Library, Add) |
| `nameXL` | Bricolage | 28 / 34 | 600 | Song title on Now Playing (**glance**) |
| `nameL` | Bricolage | 22 / 28 | 600 | Album and playlist header; song in the mini player (**glance**) |
| `nameM` | Bricolage | 18 / 24 | 600 | Section titles, card titles |
| `glance` | Figtree | 18 / 24 | 500 | Artist on Now Playing and in the mini player; reason line (**glance**) |
| `title` | Figtree | 16 / 22 | 600 | List row primary, dialog title |
| `body` | Figtree | 16 / 24 | 400 | Body copy, explanations |
| `label` | Figtree | 16 / 20 | 600 | Buttons |
| `bodySmall` | Figtree | 14 / 20 | 400 | List row secondary (artist · album) |
| `labelSmall` | Figtree | 14 / 18 | 600 | Chips, tabs, segmented control |
| `caption` | Figtree | 14 / 18 | 500, tnum | Format facts, durations, counts |
| `micro` | Figtree | 12 / 16 | 600, +0.4 tracking | Overlines and badges. **The floor: nothing smaller.** |

Rules:

- **Glance surfaces** (Now Playing, the mini player, the notification
  layout and anything shown in the car) use only `nameXL`, `nameL`,
  `glance`, `label` and `caption`, so the smallest text there is 14 sp. In
  practice 16 sp and up carries the meaning (P3).
- All sizes are in `sp` and scale with the system font size. Layouts are
  tested at 200% (see Step 8).
- Numbers that line up (durations, counts, kHz) use `caption` or `title`,
  which have `tnum` on.

## 3. Space: an 8 dp grid

| Token | dp | Use |
|---|---|---|
| `s0` | 0 | |
| `s½` | 4 | Icon-to-label gap, tight stacks |
| `s1` | 8 | Gap inside a component |
| `s1½` | 12 | Gap between chips; list row vertical padding |
| `s2` | 16 | **Screen gutter (phones)**; card padding |
| `s3` | 24 | Gap between sections; screen gutter at 600 dp+ |
| `s4` | 32 | Large separation; empty-state padding |
| `s5` | 40 | |
| `s6` | 48 | Minimum touch target |
| `s7` | 56 | **Glance target**: transport buttons, the mini player height floor |
| `s9` | 72 | Play/pause on Now Playing |

### Layout

| Width | Columns | Gutter | Navigation |
|---|---|---|---|
| < 600 dp (phone) | 4 | 16 | Bottom bar, 3 destinations |
| 600–839 (small tablet, phone landscape) | 8 | 24 | Navigation rail |
| ≥ 840 | 12 | 24 | Rail; Now Playing as a two-pane layout |

The **thumb zone** is the lower 45% of a portrait phone screen. Primary
actions live there: the transport, the mini player, the bottom bar, the
"Add" search field on focus, and the main CTA in flows (P3).

## 4. Shape

| Token | Radius | Use |
|---|---|---|
| `r-xs` | 4 | Badges, format tag |
| `r-s` | 8 | Artwork in list rows, input fields |
| `r-m` | 12 | Cards, chips, list groups |
| `r-l` | 20 | Bottom sheets (top corners), the mini player, big artwork |
| `r-xl` | 28 | Dialogs, the steering panel |
| `r-full` | 50% | Buttons (pill), the play button, the record, avatars |

The record is always a perfect circle with a label hole. It is the only
circular *surface* in the app, so it stays special.

## 5. Elevation

The look is flat and paper-like. Levels are made with **tone first**, and
shadows only where something floats over content.

| Level | Tone | Shadow | Use |
|---|---|---|---|
| 0 | ground | none | Page |
| 1 | surface | none, `line` hairline | Cards, list groups |
| 2 | surface | `0 1dp 3dp` 12% ink | The navigation bar (sits above lists) |
| 3 | record | `0 4dp 12dp` 20% black | The mini player (floats) |
| 4 | surface | `0 8dp 24dp` 24% black, plus scrim | Bottom sheets, dialogs |

No translucency, glass or glow, anywhere. This is the 172 audit's P1, kept
fixed.

## 6. Motion

| Token | Duration | Easing | Use |
|---|---|---|---|
| `tap` | 100 ms | standard | Press states, ripples |
| `small` | 200 ms | standard `(0.2, 0, 0, 1)` | Chips, toggles, row expand |
| `medium` | 300 ms | emphasized decelerate `(0.05, 0.7, 0.1, 1)` | Sheets in; screen changes (fade-through) |
| `exit` | 200 ms | emphasized accelerate `(0.3, 0, 0.8, 0.15)` | Sheets and screens out |
| `large` | 450 ms | emphasized | Mini player ⇄ Now Playing (shared artwork) |

**Record motion** is a product requirement, preserved exactly:

- one revolution every **9 s** while playing;
- when paused, it **tucks in 18 dp** and **breathes to scale 0.97**;
- crossfade on track change;
- swipe sideways past **22% of its width** to change track;
- swipe down past **20% of the screen** to close;
- swipe up **80 dp** to open the Queue.

**Reduced motion.** When the system "Remove animations" setting is on
(`ANIMATOR_DURATION_SCALE == 0`):

- the record **stops turning** and shows its label upright;
- the tuck and breathing become an instant state change;
- transitions become crossfades of 0–100 ms;
- the gestures still work (P5, and fix F12).

## 7. Iconography

- Material Symbols **Rounded**, weight 400, at sizes 20, 24 and 28 dp.
  Optical size 24 is the default.
- Every icon-only button has a text label, either visible or as a
  content description. Destructive and irreversible actions always show a
  visible word (from 172 critique P2).

## 8. Core components

Each component lists its variants and states. Full specs, with props and
measurements, follow in Step 9.

| Component | Variants | States | Notes |
|---|---|---|---|
| **Button** | Filled (amber + onAmber), Tonal (amberSoft), Text, Destructive (wine) | enabled, pressed (−8% tone), focused (2 dp `ink` ring with 2 dp offset), disabled (38% content, no tone), **loading** (spinner replaces icon, label stays, width locked) | **56 dp** for the single primary CTA of a flow and on glance surfaces; **48 dp** everywhere else; pill shape |
| **IconButton** | Standard, Filled (play) | as above | Target ≥ 48 dp; transport 56 dp; play 72 dp |
| **Chip** | Filter, Steering, Input | off, on (amberSoft + check), pressed, disabled | Height 40 dp **visible**, 48 dp target |
| **ListRow** | Song, Album, Artist, Playlist, Setting, Download | default, pressed, **playing** (amberText title + equaliser glyph), selected (checkbox), disabled | Minimum height 64 dp (song) or 72 dp (with artwork); the whole row is one target, and a trailing "More" opens a sheet |
| **MiniPlayer** | Phone, Car-size (when large text is on) | playing, paused, buffering, error, **online mix** (shows the source badge) | Height 72 dp; play/pause 56 dp at the right edge; swipe up opens Now Playing |
| **Record** | Hero (Now Playing), Peek (album header, sliding out from the sleeve) | spinning, paused (tucked), reduced-motion | See §6 |
| **FormatTag** | Lossless, Hi-Res, Lossy, Online | — | `caption`, `r-xs`; Hi-Res means more than 16-bit or more than 48 kHz, said in words |
| **StatusLine** | info, success, waiting, error | — | Icon + sentence + optional single action (P4) |
| **ActivityPill** | idle (hidden), running (count), needs-you (slate), failed (wine) | — | Lives in the top app bar; opens the Activity sheet |
| **Sheet** | Modal bottom sheet | collapsed, half, full | Drag handle with "Close" a11y action |
| **SearchField** | — | empty, typing, results, offline | 56 dp tall, `r-m`, leading icon, clear button |
| **SegmentedControl** | 2–4 options | — | Used for Library sections (Songs · Albums · Artists · Playlists) |
| **Slider** | Continuous, Stepped (EQ) | — | Thumb ≥ 24 dp, target 48 dp; value read aloud |
| **EmptyState** | — | — | Illustration-free: a short title (`nameM`), one sentence and one button |

### Do / Don't

| ✅ Do | ❌ Don't |
|---|---|
| Use amber for one primary action per view | Colour sections differently |
| Say status in a word with an icon | Rely on colour alone (moss, slate or wine) |
| Use Bricolage for names | Use Bricolage for numbers, buttons or body text |
| Keep glance text at 16 sp or more | Put anything below 12 sp anywhere |
| Let the record be the only circle-as-surface | Add decorative discs, glows or gradients |
