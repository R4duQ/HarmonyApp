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

## Identifying the song playing (`SongMatcher`)

The player's metadata (title, artist, album, length) is searched with the
songs filter. A result is the recording only when:

1. it is typed `MUSIC_VIDEO_TYPE_ATV` (a song). An official video, an upload
   or an episode is rejected whatever its title: a `videoId` alone says
   nothing about what it is;
2. the lead artist of one side is among the credits of the other
   (diacritics, case, ş/ș and ţ/ț, punctuation ignored: `TrackIdentity`);
3. the base title and the named versions agree. Live, remix, acoustic,
   cover, karaoke, instrumental, demo, edit, extended and sped up/slowed are
   other recordings. A remaster is the same take: accepted with a penalty;
4. lengths, when both are known, are within 8 s (2 s scores highest). An
   unknown length stays unknown: no bonus, no rejection;
5. clean and explicit editions must agree when both sides say. Files rarely
   tag it, so the seed's edition is usually unknown and not used;
6. 70 points or more (an agreeing length or the same album is needed), and
   no other recording within 10 points. Two listings of one take (same
   length within 2 s) are not a choice.

Otherwise nothing is chosen (`AMBIGUOUS`, `UNCERTAIN`, `NO_MATCH`) and the
mix stays the library's.

**Corpus** (`domain/online/src/test/resources/ytmusic/match-corpus.v1.json`,
44 cases, 23 with a right answer, 10 Romanian; constructed by hand, plus 3
items from the live answers). Thresholds written into the file **before**
tuning: no wrong match accepted, at least 85% of right ones recovered.
Measured on the first run, with no tuning: **0 wrong matches, 21/23 = 91.3%
recovered, 10/10 Romanian**. The two misses are the cases built to be hard
(nothing but the name confirms the song). `SongMatcherCorpusTest` fails the
build if either threshold is missed.

## From the radio to candidates (`OnlineMixPlanner`)

The radio is always the **seed's** (its confirmed id), so the mix does not
drift along its own recommendations. Turned away: anything but a song,
greyed-out items, the seed, what the queue holds, the recent plays (the
smart-shuffle window) and this session's picks (by recording, so a library
song and its online copy count as one), sped-up/karaoke/cover versions the
seed isn't, lengths under 1 min or over 15 min.

The online score is its own (points): the radio rank (up to 45), a known
length (10), a known album (5), an artist shared with the seed (10), a
version the seed isn't (−25), an artist skipped fast this session (−30).
Below 50 it is turned away; when few pass, the batch is **smaller**, never
padded. No lead artist twice in a row, at most two per batch. Nothing
acoustic is invented for an online song: the 24-D analysis applies to the
library songs of the mix (the existing `ShuffleBrain.mix`), which keeps the
seed as its landmark.

## Checking each candidate (`OnlineMixCoordinator`, `OnlineMixPreparer`)

1. **The library first** (`LibraryLocalCopies`): the same recording, a file
   that opens: it plays from the library, nothing is downloaded.
2. **What `checkAvailability` means**: read in
   `SpotiFlacDownloadEngine.checkAvailability`, it always answers `UNKNOWN`
   (Mornye 5.3.1 and SpotiFLAC 4.9.6 confirm availability only inside a
   download). So it is not used as evidence: a candidate's availability is
   `UNKNOWN` until a download confirms it (`CONFIRMED`) or a provider says it
   has none (`ABSENT`).
3. **Four facts kept apart** on every candidate (`OnlineCandidate`): listed by
   the catalog as a song; availability; a file prepared; that file checked.
4. **Preparing** (`OnlineMixPreparer`): the providers' own search (as the
   Downloads screen), only the same recording (`OnlineSourceMatch`: lead
   artist, base title, versions, length within 3 s; an ISRC first), the
   engine's download as FLAC with its routing, sign-in, quality rules and
   staging, then the file is **read**: native FLAC (`fLaC` + STREAMINFO,
   `validateStagedFlac`) and a length within 3 s of the catalog's. Only then
   is it labelled FLAC (bit depth and rate from the file) and moved into the
   online cache (`cache/online-mix/<videoId>.flac`, 1 GB, least recently
   used first, never a file the queue holds).
5. **Outcomes keep their meaning**: nothing listed by the providers → *not
   available*; sign-in or verification needed → *not verified* (nothing
   confirmed either way); network, a busy engine, a rate limit → *retry
   later* (30 s, 60 s, then given up); a file that is not lossless audio of
   this recording → *rejected*, deleted; Wi-Fi only, free space → *held*.
6. **The user's downloads come first**: an online preparation runs as a
   background transfer (`DownloadEngineGate.runInBackground`); a download
   the user starts cancels it (its staging tidied as for any cancel), takes
   the engine, and the preparation is tried again later. "Cancel" on the
   Downloads screen does not touch it.

A candidate being checked shows in the queue sheet under the mix bar, with
its stage; it joins the playable queue only once it is ready.

## Modes and the queue

| Mode | What a mix draws from |
|---|---|
| **Offline** (default, and always while the setting is off) | The library's 24-D mix, as before. **No request leaves the phone** (`anOfflineMixMakesNoOnlineRequest`, `offlineMakesNoRequestAtAll`). |
| **Online** | Online recommendations first; the library's mix fills whatever is not ready yet, so playback never waits on the network. |
| **Combined** | The library's mix with about one online song in three; a recording the library has always plays from the library. |

Every queue entry keeps its own identity (`QueueShadow`). Online picks only
ever join **at the end, as new automatic entries**: nothing the user queued
(Play next, Add to queue), moved (pinned) or added twice on purpose is moved,
replaced or removed, and automatic picks never repeat what is queued.
While an online mix runs the lookahead is 5 songs (12 otherwise), and a pick
that becomes ready joins at once if the queue is under 7 ahead, so a
prepared recommendation is heard within a few songs.

Each start of a mix has its own key (seed, start time, generation). Stopping
the mix, starting another list, choosing another shuffle mode, a journey or
starting a mix again ends the online side; anything that arrives for an
earlier key is dropped (`aLateRecommendationOfAnEndedOrReplacedMixIsDropped`,
`aLateResultOfAnEndedMixIsDropped`).

**Online songs in the player** (`OnlineSongs`): media id `ytm:<videoId>`, a
song id below zero (never a library id, never a computer's), the prepared
file as its address. The queue is rebuilt from the media items after the UI
reconnects (`PlaybackConnection.syncState`). Like a computer's songs, they
are not restored after the app is killed (`QueueRestoration` restores
library songs only), and they are never added to the library, its play
counts or the 24-D analysis.

**Android–PC sync**: an online song is the phone's song in the shared queue,
`dev:<phone>:ytm:<videoId>` (the same key `ConnectSession` gives what the
phone plays); the computer treats keys as opaque and, when it plays the
phone's queue, fetches the file through the phone's Connect file server,
which serves `file://` addresses.

## Background cost, priority to playback

- Requests: one search per seed (kept 7 days; "not found" 1 day), one radio
  per seed (kept 30 min, at most 3 pages, stopping early when a page repeats).
  Callers asking for the same seed at once share one request.
- Failures: timeout, no network and 5xx retried twice (1 s, 3 s); a 429
  pauses all catalog requests for its Retry-After (or 60 s); a changed answer
  is reported, not retried.
- One preparation at a time, at most **one** ready ahead of the queue.
- Nothing is prepared while the player buffers or a computer is being
  connected to (Connect handoff).
- Downloads follow the mobile-data setting (Wi-Fi only by default); the
  catalog requests themselves are small JSON and are made on any network.
- Only complete, checked files join the queue, so crossfade and gapless treat
  an online song exactly as a local file.
- Logs (tag `ONLINE_MIX`, `MIX online`): ids, counts and timings only; never
  titles, URLs or tokens.

## Settings (feature flag)

Settings → *Online mixes (experimental)*: **off by default**. On: the mix
source (Combined, Online, Offline; tap to change) and *Prepare online songs on
mobile data* (off by default).

## Tests that block a merge (CI, `android-check`)

| Area | Tests |
|---|---|
| Adapter | `YtMusicClientTest`: request bodies and headers as ytmusicapi's, no account, visitor id once; 429 with Retry-After; 5xx, 4xx; timeout; no network; not JSON; a moved section list; missing fields stay unknown; continuations stop on a repeat; a bad id is not sent; cancellation. |
| Conformance | `YtmConformanceTest` (7 recorded answers, field by field against ytmusicapi 1.12.3's own output); CI also re-runs ytmusicapi on them (`expected_outputs.py --check`) and re-derives the derived fixtures. |
| Matching | `SongMatcherCorpusTest` (thresholds above). |
| Planner, session | `OnlineMixPlannerTest`, `OnlineMixCoordinatorTest`: songs only, no repeats, variants, smaller batch, artist spread; identify once, radio once, TTL; rate-limit pause; retries with backoff; each outcome's meaning; a dropped connection retried and handed out once; busy playback; fast skip; late results dropped. |
| Queue | `SmartQueueManagerQueueTest` (online section): the user's entries keep their ids and order through an online pick, a reorder, refills, shuffle off/on, an online mix and a mode change; the user's duplicates stay, no automatic repeat; late answers dropped; Offline makes no request; Online never runs dry; fast skip reported. |
| Player, engine | `OnlineSongsTest`; `DownloadEngineGateTest` (the user's download takes the engine, the background never enters while held, a caller's own cancel is a cancel); `OnlineMixSourcesTest` (same recording only, outcomes, cache eviction never touching the queue). |

A passing fixture confirms only the behaviour it covers. The derived
fixtures (`scripts/ytmusic/derive_fixtures.py`) keep the real structure and
change the values a song carries; they are not answers YouTube Music gave.

## Live test (repeatable)

```bash
HARMONY_LIVE_YTM=1 HARMONY_LIVE_YTM_GL=RO ./gradlew :domain:online:test --tests '*YtMusicLiveTest*' -i
```

Results so far: the table under "Live test (2026-11-11, no account)" above (a data-centre network:
no songs at all).

## Still to check on a phone

Nothing below has been run on a phone yet.

1. **Anonymous catalogue from a phone in Romania**: open the queue sheet with
   an online mix running on a well-known song; the panel says "Found on
   YouTube Music" or why not. If it says *No songs on YouTube Music from this
   network*, anonymous access does not give songs there either, and the
   feature needs a signed-in client (not built: it would need the user's
   cookies or an OAuth client of their own, see ytmusicapi's setup docs).
2. **A first complete flow**: Combined mode, a song with known online
   neighbours; a recommendation goes *Waiting → Preparing → Ready ·
   FLAC …* and is heard within about five songs.
3. **Mornye routes**: a recommendation needing Tidal sign-in shows *Not
   verified*, not *Not available*.
4. **Interruptions**: Wi-Fi off during *Preparing* → *Retrying soon*, then
   ready once Wi-Fi is back, one file in `cache/online-mix`; start a download
   from the Downloads screen during *Preparing* → it starts at once.
5. **Offline mode**: no request to music.youtube.com (Android Studio network
   inspector, or the router).
6. **Measurements, before and after on the same phone**: queue interaction
   latency (drag, Play next) with an online mix running; time from the mix
   start to the first online song ready; memory (Android Studio profiler);
   traffic per hour of online mix; a Cast/Connect session to the PC for 30
   minutes with an online mix running.
