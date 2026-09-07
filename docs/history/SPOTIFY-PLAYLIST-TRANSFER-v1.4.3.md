# Spotify Playlist Transfer — Harmony v1.4.3

This is a source package. Rebuild and install with
`Instaleaza-Harmony-pe-telefon.cmd` from `D:\Harmony` (SDK: `D:\Android`).

## Connection correction

The reported browser message `client_id: Invalid` means Spotify rejected the
application identifier supplied in the authorization request. This release
blocks common incorrect values before opening a browser and explains where to
copy the registered app's Client ID. It cannot create an app registration or
turn a fabricated identifier into a valid one.

The previous suggestion to replace the custom callback with a loopback server
was based on an overly broad reading of the Web API redirect guide. Spotify's
[Android authorization guide](https://developer.spotify.com/documentation/android/tutorials/authorization)
still documents a custom-scheme browser return. This package uses the existing
direct Android callback below; no local HTTP listener is included.

## What it does

Playlist Transfer reads an owned or collaborative Spotify playlist, compares
its tracks with Harmony's local library, and creates a normal editable Harmony
playlist in the original order. Missing tracks can be selected and located
through either SpotiFLAC or Soulseek before the playlist is created.

Spotify is used only for playlist structure and metadata. Audio is never read
or downloaded from Spotify.

## One-time Spotify setup

1. Open [Spotify Developer Dashboard](https://developer.spotify.com/dashboard).
   Create or select an active app with **Web API** access.
2. In **Settings**, add this exact Redirect URI and save it:

   `com.harmony.app://spotify-playlist-callback`

   Harmony's **Copy Redirect URI** button copies the exact value. Do not add
   a trailing slash. If you registered the loopback address suggested earlier,
   add this native callback for the delivered v1.4.3 build.
3. Copy **Client ID** from that same app's **Settings / Basic Information**.
   It is the 32-character app identifier, not your username, a playlist ID,
   the app name, or **Client Secret**.
4. In Harmony, open **Playlists**, tap the cloud-download icon, paste the
   Client ID, and tap **Connect**.
5. Approve the requested read-only playlist scopes in the browser.
6. If Spotify displays `client_id: Invalid`, return to Harmony, tap
   **Connection help**, and recopy the Client ID from an active Spotify app.
   Format validation does not establish that an app exists or has API access.

For Development Mode, Spotify requires the app owner to have Premium and limits
access to a small allowlist. In **Settings → Users Management**, add the account
you will sign in with. An unlisted account may authenticate but receive 403
when Harmony requests playlists. See Spotify's
[quota documentation](https://developer.spotify.com/documentation/web-api/concepts/quota-modes).

Harmony uses Authorization Code with PKCE. No Spotify client secret belongs in
the Android project or in the app UI.

This update also rejects unexpected callback destinations and stale states,
ignores duplicate callback deliveries, and prevents a cancelled or replaced
login from saving tokens over a newer attempt. A failed browser launch now
shows a message instead of silently doing nothing.

## Transfer flow

1. Choose a Spotify playlist or paste its Spotify link.
2. Review the local matches and selected missing tracks.
3. Explicitly choose **SpotiFLAC** or **Soulseek** for the batch.
4. Choose the SpotiFLAC output format when that engine is selected.
5. Confirm the source and start the transfer.
6. If SpotiFLAC requests provider verification, complete it in the browser;
   Harmony returns to Playlist Transfer and resumes the same ordered batch.
7. Open the resulting playlist from the completion card.

Harmony never switches between SpotiFLAC and Soulseek automatically. Soulseek
must already be connected from the Downloads screen. Its existing format
preference is respected.

## Matching and ordering

- Local matching is conservative and compares normalized title, primary
  artist, album, duration, and edition markers such as Live or Remix.
- Romanian diacritics compare correctly.
- Questionable versions remain missing rather than silently linking the wrong
  recording.
- Downloaded files use the existing staging, container validation, metadata,
  provenance, media scan, and library insertion pipeline.
- The final Harmony playlist is rewritten in Spotify order after each completed
  batch or retry. Duplicate occurrences map to Harmony's existing distinct-song
  playlist behavior.

## Current Spotify API constraint

In Spotify Web API Development Mode, playlist items are available only when the
signed-in user owns the playlist or is a collaborator. Harmony keeps other
followed playlists visible but disables importing their items and explains why.
