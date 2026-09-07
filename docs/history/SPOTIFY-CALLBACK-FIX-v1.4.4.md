# Spotify callback correction — Harmony 1.4.4

This source release addresses the app-side message:

> Harmony ignored an unexpected Spotify authorization return.

That message comes from the callback destination guard, before state validation
and the token request. It does not establish that the Client ID is invalid.
The screenshot identifies this branch but does not show the received URI, so
the exact device-specific trigger remains unconfirmed.

## Reproduced defect and correction

The 1.4.3 guard required an empty path and a null fragment. It rejected a
query-based authorization response if the URI ended in `#`, had a fragment
such as `#_=_`, or used `/` as the native endpoint's root path. A new regression
test failed against the old helper before applying this correction.

The guard now checks the destination independently from the response:

- Require the same scheme and host, without user information or an explicit
  port (including an empty port).
- Accept only the empty path or `/`; reject any additional or encoded path.
- Ignore the fragment. Read `state`, `code` and `error` only from query
  parameters, as in the existing client. A fragment-only response cannot
  authorize a connection or substitute its state/code for query values.
- Preserve the fresh random state, constant-time state comparison, ten-minute
  expiry, PKCE verifier, cancelled/replaced-request protection and duplicate
  handling.
- Report a fixed rejection category for unexpected destinations. Never include
  the received URI, state, authorization code or tokens in those messages.

The registered redirect URI and the exact value sent to both Spotify's
authorization and token endpoints are unchanged:

`com.harmony.app://spotify-playlist-callback`

No new Client ID, Client Secret, app registration or local HTTP listener is
required by this patch. Do not add a slash or fragment to the Dashboard value.
Existing playlist import, SpotiFLAC and Soulseek behavior is unchanged.

## Install and verify on the phone

Follow `INSTRUCTIUNI-Spotify-v1.4.4.txt`. Merge the source folder into
`D:\Harmony`, keep local configuration and signing keys, and run
`Instaleaza-Harmony-pe-telefon.cmd` to build and install the update. Do not
uninstall or clear app data. Use the same signing identity as the installed app.

Confirm the installed version is 1.4.4 (version code 76), then start a **new**
connection using **Connect**. Do not replay an old browser return. Accept the
read-only playlist permissions, wait for the connection confirmation, and
test importing an owned playlist before testing downloads.

If a rejection remains, share only a screenshot of Harmony's new error text.
The full callback URI can contain one-time credentials and should not be shared.

See `VALIDATION-v1.4.4.md` for executed checks and limitations. Earlier 1.4.3
documents describe the previous setup release; this file supersedes their
callback-validation behavior.

Protocol reference: Spotify's [Authorization Code with PKCE flow](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow).
