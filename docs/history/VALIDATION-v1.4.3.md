# Validation — Harmony 1.4.3

Checked on 2026-09-05:

- Compiled the production Spotify authorization helper, API client and models
  with Kotlin 2.1.0 using local Android, JSON and injection test doubles.
- Executed all eight `SpotifyAuthorizationTest` methods with an assertion
  harness: incorrect Client ID formats, RFC 7636 PKCE vector, fresh secrets,
  callback destinations, state expiry/mismatch and actionable Spotify errors.
- Executed the production client's request/callback logic with test doubles:
  input trimming, exact OAuth parameters, PKCE wiring, replaced and cancelled
  requests, denied access and duplicate callback delivery.
- Parsed all four changed production Kotlin files with Kotlin's parser.
- Checked the Android manifest, documented redirect and release version
  (`1.4.3`, version code `75`).

These checks do not validate Android UI types, device behavior or Spotify's
live service. A full Gradle build could not run here because the Gradle
distribution download was blocked. No real Spotify Client ID/account was
available for live authorization or a playlist transfer.

On a configured Windows machine, run from `D:\Harmony`:

```bat
gradlew.bat :feature:downloads:testDebugUnitTest --tests com.harmony.feature.downloads.SpotifyAuthorizationTest
Instaleaza-Harmony-pe-telefon.cmd
```

Then follow `INSTRUCTIUNI-Spotify-v1.4.3.txt`, connect using a registered app's
Client ID, and transfer an owned playlist. The browser message
`client_id: Invalid` still requires correcting the Spotify app identifier.
