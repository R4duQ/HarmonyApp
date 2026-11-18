---
version: 1
slug: "com-harmony-app-navigation-harmonyapp-kt-5b6e292b"
primary_target: "app/src/main/kotlin/com/harmony/app/navigation/HarmonyApp.kt"
related_targets: ["feature/home/src/main/kotlin/com/harmony/feature/home/HomeContent.kt","feature/player/src/main/kotlin/com/harmony/feature/player/NowPlayingScreen.kt","feature/library/src/main/kotlin/com/harmony/feature/library/LibraryScreen.kt","feature/playlists/src/main/kotlin/com/harmony/feature/playlists/PlaylistDetailContent.kt"]
---

# Harmony app shell and core screens

Scope: the Android app shell (navigation bar/rail, mini player) and the core
screens Home, Now Playing, Library, Playlist detail. Other screens inherit the
theme and palette. Mode: Operate.

Audience and job: the owner and friends playing their own lossless library,
in the car (glance, one hand), at the desk (background, occasional reach) and
in collecting sessions (long, deliberate). Constraints: keep the vinyl behind
the cover, the Winamp EQ screen, light and dark, the name and icon.

Unresolved: Downloads, Discover, Settings, EQ and Recognize get the shared
tokens only in this round; their bespoke layouts are a later pass.

## Direction contract

THESIS: Harmony is a record shop catalogue for a collection you own. It refuses the category's dark-neon player with blurred art and the per-section rainbow it shipped before: one ground, one structure colour, one live colour.

OWN-WORLD: Cool paper-grey ground (blue-black in dark), near-black ink, catalogue blue for structure and selection, margin red only for what is playing now. Archivo grotesk with tabular figures on one six-step scale. Hairline row rules, no glass, glow or gradients.

STORY: The listener sees what is playing and what the file really is, finds any record by its divider letter, and acts with one thumb.

FIRST VIEWPORT: Home: greeting at headline scale flush left; the now-playing block (cover, title, artist, short format line, pause) on a solid surface; Smart Shuffle row with a blue filled Start Mix; Recently played under a divider. Opaque nav bar at the bottom, solid mini player above it, red progress hairline on its top edge.

FORM: Record-shop crate dividers and catalogue cards, candidate 4 of 7, seed:6fec5ec76c7df794. Signature move: Library letter headers as filled blue divider tabs that stick at the top while scrolling; Now Playing carries a format card with a red header rule and labelled fields (format, bit depth, sample rate, bitrate).

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
