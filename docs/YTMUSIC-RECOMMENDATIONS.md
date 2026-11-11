# Online recommendations: YouTube Music for discovery, Mornye for the audio

"Mix din piesa asta" can reach past the library: the song playing is found on
YouTube Music, its radio gives recommendations, and each one is played from
the library when Harmony has it, or prepared as lossless audio through the
Mornye engine. Behind a setting that is **off by default**.

This page records the decision (where the code runs and why), what was
checked and how, and what still has to be checked on a phone. Every claim
about behaviour names the file it comes from.

## Sources and versions

| What | Version | Where |
|---|---|---|
| ytmusicapi | **1.12.3** (PyPI, 2026-09-16), sdist sha256 `be81511af7494eec8e5970c56a10e51aada6253246649e4ea6714fd6aeb654ee`, MIT | `scripts/ytmusic/requirements.txt` (pinned by hash) |
| ytmusicapi needs | Python ≥ 3.10, `requests ≥ 2.22` | `pyproject.toml` of the sdist |
| Harmony base | `main` at `9317eab` | |
| Download engine | Mornye 5.3.1 / SpotiFLAC 4.9.6, unchanged | [MORNYE-DOWNLOAD-ENGINE.md](MORNYE-DOWNLOAD-ENGINE.md) |

[Documentation](https://ytmusicapi.readthedocs.io/en/stable/) ·
[source](https://github.com/sigma67/ytmusicapi).

### What ytmusicapi documents, and what had to be tested

Read from the 1.12.3 source (`ytmusic.py`, `mixins/search.py`,
`mixins/watch.py`, `parsers/search.py`, `parsers/songs.py`,
`parsers/watch.py`, `continuations.py`, `helpers.py`, `constants.py`):

- **Without an account** (`YTMusic()` with no `auth`): every request is a
  `POST https://music.youtube.com/youtubei/v1/<endpoint>?alt=json` with a
  `WEB_REMIX` client context (`clientVersion = 1.<UTC date>.01.00`,
  `hl = en`), a visitor id read once from the home page's `ytcfg`, and the
  cookie `SOCS=CAI`. No API key is sent without browser auth.
- **`search(query, filter="songs")`**: body `{query, params:
  "EgWKAQIIAWoMEA4QChADEAQQCRAF"}`. Each result has `videoId`, `title`,
  `artists`, `album`, `duration_seconds`, `videoType`, `isExplicit` (a
  badge is present), `isAvailable` (not greyed out), `year`.
- **`get_watch_playlist(videoId, radio=True)`**: endpoint `next`, body
  `{videoId, playlistId: "RDAMVM"+videoId, params: "wAEB", isAudioOnly,
  enablePersistentPlaylistPanel, tunerSettingValue}`; more tracks through
  continuations (`&ctoken=…&continuation=…`). Unplayable items are dropped;
  a song/video wrapper is read as its song. **No explicit flag** is parsed
  for radio tracks.
- **Authentication** (browser cookies or OAuth with your own Google Cloud
  client) is only *required* for the library, uploads, history, likes and
  playlist edits. Search and radio work without it **in the code**. Whether
  they return useful results without it was a thing to test (below).

Assumptions that had to be tested, not read: that anonymous search returns
songs; that an anonymous radio holds songs; how long the calls take; what
`videoType` the items carry.

## Live test (2026-11-11, no account)

Run twice: through ytmusicapi 1.12.3 itself and through Harmony's Kotlin
client (`YtMusicLiveTest`, `HARMONY_LIVE_YTM=1`), from this session's cloud
machine (a data-centre network, `gl=RO` and unset).

| Call | Result |
|---|---|
| visitor id (home page, ~270 KB) | 750 ms |
| `search(…, filter="songs")` for *Oasis Wonderwall*, *Subcarpați Pe Drum*, *Phoenix Andrii Popa*, *Massive Attack Teardrop* | **"No results"** for every one, 240–1,600 ms |
| `search(…, filter="albums")` | no results |
| `search(…)` unfiltered | official videos, user uploads, artists, playlists, podcasts: **no songs** |
| radio from `ZrOKjDZOtkA` (*Wonderwall*, a song in the docs) | 6 unique tracks: 5 `UGC`, 1 `PODCAST_EPISODE`; the seed itself typed `UGC` ("Oasis - Topic"); the continuation repeated the first page |
| radio from `DF_CKTwIg3I` (*Subcarpați - Drumul spre pace*) | 50 tracks, **all `UGC`**, 300–800 ms |

**What this shows.** Requests without an account are answered and parsed by
both implementations identically. From this network, YouTube Music shows no
licensed catalogue: no song (`MUSIC_VIDEO_TYPE_ATV`) came back at all, and
every radio track was a user upload or an episode. Harmony therefore
identified nothing and recommended nothing, which is the intended answer to
such input. **Whether anonymous requests from a phone in Romania (where
YouTube Music is offered) return songs is not known yet**: it is the first
check on the phone (below).

**Recommendations without an account are not personal.** They come from the
seed alone (YouTube Music's radio for that song), not from the listener's
YouTube history, likes or subscriptions. Harmony adds its own session
knowledge on top (what is queued, recently played, skipped).

## The decision: a Kotlin client that follows ytmusicapi, ytmusicapi as the reference

ytmusicapi is a Python library. Three places it could run were weighed:

| | Python on the phone (Chaquopy) | Python service on the PC | **Kotlin client on the phone, ytmusicapi as reference** |
|---|---|---|---|
| APK size | +7.3 MB native runtime (arm64, `target-3.13.16-0-arm64-v8a.zip`, Maven Central) + 4.3 MB stdlib (`stdlib-pyc.zip`) + ~3 MB ytmusicapi, requests, urllib3, idna, charset-normalizer, certifi (measured installed sizes) ≈ **+15 MB per ABI**, a third runtime beside Go/Rust | none on the phone | **~60 KB of Kotlin**, no new runtime |
| Works without the PC | yes | **no** | yes |
| Latency | Python start on first use (not measured), then the same HTTP | phone → PC → internet → PC → phone | the HTTP calls only (measured above) |
| Upkeep when YouTube changes | bump ytmusicapi | bump ytmusicapi on the PC | port the change; the conformance test points at it |
| Build | a Gradle plugin, pip at build time, per-ABI packaging | a PC service, discovery, pairing | nothing new |

**Recommended and built: the third.** Harmony's client
(`domain/online/.../YtMusicClient.kt`, `YtmParsers.kt`) makes exactly the two
calls ytmusicapi makes, with its request bodies and its parsing rules,
transcribed with file and function names. ytmusicapi stays in the loop as
the **reference implementation**: CI installs 1.12.3 by hash and runs its own
parsers over the recorded answers (`scripts/ytmusic/expected_outputs.py
--check`); the Kotlin test (`YtmConformanceTest`) must read every field the
same way. To try a new ytmusicapi release: change the pin, run the script,
and the differences show up as failing tests to port.

No infrastructure is introduced: no server, no Python on the phone, no PC
dependency.

**The narrow edge.** Everything else in Harmony sees only
`OnlineCatalog` (`searchSongs`, `radio`) and `CatalogTrack`
(`domain/online/.../OnlineCatalog.kt`). A Chaquopy- or PC-backed catalog
could replace `YtMusicClient` without touching the matcher, the planner or
the queue.

## The flow

```
song playing ─▶ identify (search, songs) ─▶ SongMatcher ─▶ confirmed videoId
                                                   └─▶ not found / ambiguous: no online picks
confirmed id ─▶ radio (always the seed's) ─▶ OnlineMixPlanner ─▶ candidates
candidate ─▶ library has it? ─▶ play the library's song
          └▶ Mornye: search providers, download, check fLaC + length ─▶ online cache ─▶ queue
```

Stages are the next sections; this page is completed as each one lands.
