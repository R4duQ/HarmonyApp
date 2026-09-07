# Validation — Harmony 1.4.5

Checked on 2026-09-05:

- Compiled the new Discover screen, view model, preferences store, album models,
  catalogue, matcher, URL policy and browser launcher with Kotlin 2.1.0 and the
  Compose compiler plugin. Used actual Compose 1.7.6, Material 3 1.3.1,
  Lifecycle 2.8.7, Coil 2.7.0 and AndroidX Browser 1.8.0 artifacts.
- This isolated compilation used an Android framework stub jar plus small
  stand-ins for Harmony's palette/domain interfaces and Hilt annotations. It
  validates UI API usage and Kotlin types, not the complete Android application,
  dependency graph, generated Hilt code, resource packaging or rendering.
- Executed 24 real JUnit 4 tests: catalogue integrity, canonical source URLs,
  cover patterns, accurate artist/album matching, diacritics/remasters,
  compilation entry tracks, alternate recordings, ordered deduplicated playback,
  explicit familiarity, saved/genre intersections, listened/undo behavior,
  stable ordering and existing browser URL restrictions.
- Ran production preferences/view-model logic with in-memory Android and
  lifecycle doubles: rapid serialized bookmark toggles, saved/familiar/listened
  retention, live saved counts, empty filters, rapid reverse tab selection,
  restored shelf/current album, library updates and local-only playback. This
  is not an instrumented Android process-death or disk persistence test.
- Parsed changed navigation/search Kotlin files and the release version file.
- Verified release version 1.4.5 / version code 77.
- Checked archive integrity and that the bundled vendor dependencies and
  Spotify 1.4.4 callback correction are unchanged.

The full Gradle command was attempted:

```text
./gradlew :feature:discover:compileDebugKotlin :feature:discover:testDebugUnitTest --no-daemon -PharmonyAbi=arm64-v8a
```

It stopped while downloading Gradle 8.11.1 with `Network is unreachable`.
No complete APK was generated. Device rendering, actual swipe gestures,
TalkBack, large-font behavior, Custom Tabs, real Room data and playback on a
phone still require a configured Android build/device.

On the user's Windows machine, run from `D:\Harmony`:

```bat
gradlew.bat :feature:discover:testDebugUnitTest
Instaleaza-Harmony-pe-telefon.cmd
```

Then check:

1. Swipe both directions with a playing mini-player, and scroll vertically to
   reach all actions. Use the arrow controls as well.
2. Save an album, reopen Discover/restart the app and check Saved. Remove the
   last saved album and confirm the empty state offers a way back.
3. Filter an empty shelf, change filters quickly, and confirm no blank or
   out-of-range pager appears.
4. Mark a record as listened, use Undo and verify it remains in All albums.
5. Check a matched local entry and the exact local album track count before
   playback. Try a similarly named cover/compilation to verify the distinction.
6. Open the correct The Shfl album and return to the same card. Test a failed
   cover load and Retry cover without losing the deck.
7. Use Find locally and confirm the album title appears in Library search.
8. Verify portrait/landscape, dark/light themes and larger system text settings.
