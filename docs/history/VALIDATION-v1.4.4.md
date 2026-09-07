# Validation — Harmony 1.4.4

Checked on 2026-09-05:

- Added a callback-normalization regression test and observed it fail against
  the 1.4.3 production helper before applying the correction.
- Compiled the production Spotify authorization helper, API client and models
  with Kotlin 2.1.0 and local Android, JSON and injection test doubles.
- Executed all ten `SpotifyAuthorizationTest` methods through an assertion
  harness. Coverage includes Client ID format, the RFC 7636 PKCE vector,
  randomness, destination restrictions, empty/root paths, ignored fragments,
  redacted rejection categories, state mismatch/expiry and mapped API errors.
- Executed the production client's request/callback logic using test doubles:
  exact authorization parameters and redirect, PKCE wiring, replaced/cancelled
  requests and duplicate returns.
- Executed eight query-based access-denied responses across empty/root paths
  and absent/empty/nonempty fragments. Verified the denial event and cleanup
  of pending state, including fragments carrying conflicting parameters.
- Verified that wrong query state cannot be replaced by a matching fragment
  state; fragment-only responses cannot complete authorization; rejected
  destinations preserve the pending attempt; a fragment code cannot trigger
  a token exchange when the query has no code.
- Verified that a recreated client can process a normalized callback using the
  original context's pending preferences. This is not a real Android process
  death test.
- Parsed changed Kotlin sources and the Gradle version file; checked release
  1.4.4 / version code 76 and unchanged Android callback registration.
- Checked the source archive's CRC and unchanged bundled vendor dependencies.

The test harness uses a Java URI-backed Android Uri double and lightweight
JUnit assertions. It does not validate Android framework parsing, UI types,
actual browser behavior, or Spotify's live token/API responses. The harness's
synthetic callbacks do not include real credentials.

A full Gradle/Android build remains unavailable here because the Gradle
distribution download was blocked and the Android build environment is not
configured. No APK was generated, and no live Spotify connection, playlist
transfer or download was claimed as tested.

On a configured Windows machine, run from `D:\Harmony`:

```bat
gradlew.bat :feature:downloads:testDebugUnitTest --tests com.harmony.feature.downloads.SpotifyAuthorizationTest
Instaleaza-Harmony-pe-telefon.cmd
```

Then follow `INSTRUCTIUNI-Spotify-v1.4.4.txt` and start a fresh connection.
