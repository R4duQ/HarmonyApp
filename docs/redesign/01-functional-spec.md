# Harmony: Pure Functional Spec

`[ACTIVE SKILL: anthropic-skills:android-reverse-engineering]`

**Source:** `Harmony-1.0-arm64.apk`, build 173 (versionCode 173). SHA-256
`44e6d367588a0291dfcf87049736f98f4ea282672b14fa788476ebb63a4ff083`, the same
value as the release's `SHA256SUMS.txt`. It was decompiled with jadx 1.5.1,
and the findings were cross-checked against the repository at `ea04ebf`.

**Scope:** This document covers what the app *does*, what it *stores* and
what it *talks to*. It has no layout, colour or component names, and nothing
about how anything looks today.

---

## 1. Fingerprint

| Item | Finding |
|---|---|
| Framework | Native Kotlin and Jetpack Compose, a single Activity |
| Architecture | MVVM with Clean Architecture modules (`core/*`, `data/*`, `domain/*`, `feature/*`, `playback`, `sync`) |
| DI | Hilt/Dagger. Workers use `@AssistedInject`. |
| HTTP | OkHttp. There is no Retrofit: requests are built by hand. |
| Serialization | kotlinx.serialization and org.json |
| Persistence | Room (2 databases) and Jetpack DataStore (preferences) |
| Playback | Media3 ExoPlayer inside a `MediaLibraryService` |
| Native code | Rust: `harmonyeq`, `harmonydsp`, `harmonyshuffle` and `harmony_flac`. `libmornye_mobile` is the download engine (UniFFI/JNA). FFmpeg, Python and QuickJS power yt-dlp. |
| Obfuscation | Low. R8 shrinks app classes into short packages, but Kotlin metadata keeps the names. |
| On-device AI | Gemini Nano via AICore (`BIND_SERVICE`). It is optional and can be absent. |
| ABI | arm64-v8a only |

## 2. Platform contract

### 2.1 Permissions

| Permission | Why it exists (capability it serves) |
|---|---|
| `READ_MEDIA_AUDIO` | Scan and play the local library |
| `RECORD_AUDIO` | Song recognition (listen through the microphone and fingerprint the audio) |
| `POST_NOTIFICATIONS` | Playback controls, download progress, transfer prompts |
| `FOREGROUND_SERVICE`, `…_MEDIA_PLAYBACK` | Keep playing with the screen off |
| `FOREGROUND_SERVICE_DATA_SYNC` | Album, discovery and PC-transfer downloads that outlive the screen |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Online catalogues and downloads. Online mixes are gated on Wi-Fi or mobile data. |
| `WAKE_LOCK` | Playback and long downloads |
| `RECEIVE_BOOT_COMPLETED` | Reschedule WorkManager jobs after a reboot |
| `com.google.android.apps.aicore.service.BIND_SERVICE` | Gemini Nano: guesses a song from a messy title for online mixes |

The app also asks at runtime for **scoped-storage consent** in two cases:

- Deleting files: a `MediaStore` delete request, made from library review and
  song delete.
- A user-chosen download folder (an SAF tree URI).

### 2.2 Components

| Component | Kind | Role |
|---|---|---|
| `HarmonyApplication` | Application | Hilt root and the WorkManager factory |
| `MainActivity` | Activity, exported launcher | Hosts every screen and receives deep links |
| `PlaybackService` | `MediaLibraryService`, exported, `mediaPlayback` | Playback, the media notification, Android Auto browse tree, external controllers |
| `ArtworkProvider` | ContentProvider, exported | Serves cover art to Android Auto and system media surfaces |
| WorkManager `SystemForegroundService` | Service, `dataSync` | Runs the workers below in the foreground |

### 2.3 Deep links (inbound)

| URI | Meaning |
|---|---|
| `com.harmony.app://spotify-playlist-callback` | Spotify OAuth (PKCE) redirect |
| `mornye://callback` | A download provider's verification challenge has finished in the browser |
| `mornye://spotify-callback` | The provider's own Spotify step returns |
| `mornye://session-grant` | A provider grants a session after verification |

Each return sends the user back to the flow that started it: Downloads, the
album download, Discover, or the Spotify transfer.

### 2.4 Background work

| Worker / service | Trigger | Does |
|---|---|---|
| `PlaybackService` | Play | Gapless playback, crossfade, ReplayGain, EQ/DSP chain, sleep timer, speed, queue persistence, Smart Shuffle, online mix |
| `LibraryMaintenanceWorker` | Periodic. Optionally only while charging. | Rescans MediaStore, then analyses new or changed files (BPM, key, loudness, mood, embedding) |
| `AlbumDownloadWorker` | User queues an album | Searches and downloads each track, tags FLACs, writes into the library. Pause, resume and cancel work per track and per album. |
| `DiscoveryDownloadWorker` | User saves a Discover playlist with "download" on | Downloads the picked songs and then builds the playlist |
| `TransferWorker` | The paired PC offers files | Pulls files from the PC over the LAN, verifies them, and adds them to the library |
| `QueueService` | Connect session | Mirrors the play queue with the paired PC |

## 3. Capabilities (exhaustive)

These are grouped by job, not by screen. Each one is backed by the ViewModel
action or service named in brackets.

### 3.1 Play what I own

1. **Library browse:** by songs (paged), albums, artists and genres; album
   detail; artist detail. *(LibraryViewModel, AlbumDetailViewModel,
   ArtistDetailViewModel)*
2. **Search:** full-text search over title, artist and album (FTS table).
   *(onSearchQueryChange)*
3. **Play controls:**
   - Play or pause, next, previous, seek on the waveform.
   - Repeat: off, all or one.
   - Shuffle: off, on or Smart.
   - Playback speed and a sleep timer in minutes.
   - Favourite toggle.
   *(PlayerViewModel)*
4. **Queue:** play next, add to queue, reorder by drag, remove, jump to an
   item. The queue is restored after a restart (last ids, index and
   position). *(onMoveQueueItem, onRemoveQueueItem, playNext,
   addAlbumToQueue)*
5. **Audio truth:** shows the codec, bit depth, sample rate, bitrate and
   channels of the current file, and the output decision (bit-perfect USB,
   resampled, DSP active).
6. **Output device:** choose where the sound goes ("Play on"): phone
   speaker, wired or USB DAC, Bluetooth, or a paired PC. *(setOutputForm)*
7. **Vinyl mode:** a product requirement, not legacy UI.
   - The record turns at 9 s per revolution while playing. When paused it
     tucks in by 18 dp and breathes to 0.97.
   - On track change the record crossfades.
   - Swipe the record sideways past 22% of its width to change track.
   - Swipe down past 20% of the screen to close.
   - Swipe up 80 dp to open the queue.
8. **Android Auto and external controllers:** a browse tree (recent,
   playlists, albums, artists) and transport controls. Art comes from
   `ArtworkProvider`.
9. **Crossfade (seconds) and ReplayGain mode** (off, track or album).
10. **Keep playing after the app is swiped away** (a toggle).

### 3.2 Shape the sound

11. **Equalizer:**
    - It can be on or off.
    - Graphic bands, with presets and user presets.
    - A Winamp-style 10-band EQ, a product requirement, with live response.
    - Preamp.
    - **Auto EQ:** a tone per song, from analysis.
    - **Bit-perfect USB:** bypasses all DSP.
    *(EqualizerViewModel: setBand, applyPreset, flat, toggleAuto,
    setBitPerfect)*
12. **Clarity Live:** a perceptual spectral EQ, with intensity and balance
    controls. *(toggleClarity, setClarityIntensity, setClarityBalance)*

### 3.3 Let the app choose (and say why)

13. **Smart Shuffle:**
    - The user picks a style and sets three sliders: familiarity, discovery
      and variety.
    - There is an energy slider and a mood filter.
    - "Journey to mood" moves gradually toward a chosen mood, and the user
      can cancel it.
    - The user can regenerate the queue.
    - Every pick carries a reason.
    *(onSmartStyleSelected, onEnergySliderChange, onStartJourneyToMood,
    onRegenerateSmartQueue)*
14. **Online mix:**
    - After the user's own songs run out, the queue continues with similar
      songs from YouTube Music. Two settings control it: whether it may use
      mobile data, and which source it uses.
    - Each online song can be **kept** (downloaded into the library), or the
      user can keep all of them. Auto-keep is a setting.
    - The mix status is always explainable: preparing, playing,
      offline-paused, out of songs.
    *(onStartMix, onStopMix, onKeep, onKeepAll, onChooseMixSource)*
15. **Smart playlists:** Favourites, Most played, Recently added, Recently
    played, Highest energy, Lowest energy. Home can also start a "Smart Mix".

### 3.4 Organise

16. **Playlists:**
    - Create, rename, delete and play.
    - Add songs, either from search or from any list.
    - Reorder and remove songs.
    - Import and export M3U.
    - Covers are generated.
    *(PlaylistsViewModel, PlaylistDetailViewModel, AddToPlaylistViewModel,
    AddSongsViewModel)*
17. **Tag editor:** edit the title, artist, album and album artist; pick,
    clear or restore the artwork; revert. Edits are stored as overrides
    (`song_edits`), so the file itself stays untouched. *(TagEditorViewModel)*
18. **FLAC check:** inspect a file. It answers whether it is truly lossless
    or an upscale or transcode. *(FlacCheckViewModel.inspect)*
19. **Library review:** the app proposes songs to delete. The user marks
    each one keep or remove, removes the rest, or decides later; deletion
    happens only after system consent. *(LibraryActionsViewModel)*
20. **Rescan library** on demand.

### 3.5 Grow the library

21. **Discover, the taste rounds:**
    - Rate songs in swipe rounds (yes, no or undo) and listen to previews.
    - Browse genres and shelves of curated albums from the bundled SHFL
      catalogue. Remember albums for later.
    - Mark songs as familiar or saved, then finish the remaining items.
    *(DiscoverViewModel)*
22. **Discover, building a playlist:**
    - It runs in three steps: **Preferences** (genres, artists, level, size)
      → **Songs** (keep or remove each, "more like this", "not
      interested", replace, preview) → **Review** (name, then create).
    - It can also download the picked songs.
    - The user can resume a batch, regenerate it, or start over.
    *(DiscoveryFlowViewModel, DiscoveryDownloadViewModel)*
23. **Song download, one track:**
    - Search by text, or paste a URL. Four sources are available:
      **SpotiFLAC/Mornye** (provider chain Tidal, Qobuz, Deezer, Amazon;
      FLAC up to 24/96), **Soulseek** (P2P), **YouTube → FLAC** (yt-dlp +
      FFmpeg) and a **direct FLAC URL**.
    - The user picks a preferred source and an output format, can pick an
      alternative source, and can retry a failed download.
    *(DownloadsViewModel)*
24. **Album download:**
    - Search for an album, then download it.
    - A persistent queue of albums offers pause, resume and cancel, plus
      retry and skip per track.
    - Each track gets a verification step.
    *(AlbumSearchViewModel, AlbumDownloadViewModel, AlbumQueueViewModel)*
25. **Soulseek account:**
    - Connect with a username and password, then disconnect.
    - Choose the format preference and the "free slots only" setting.
    - Search mode is album or track; "download best" picks the strongest
      result.
    - A shared folder can be set, refreshed or cleared, so the user shares
      back.
    - A diagnostics view is available.
26. **Provider verification:**
    - Some providers want a human check in the browser. The app opens it,
      waits for the return through the `mornye://` link, re-checks, and
      resumes the download.
    - If the browser cannot open, the failure is reported in words.
27. **Spotify playlist import:**
    - Connect Spotify with OAuth PKCE. The user supplies their own Client ID.
    - List their playlists, or paste a playlist link.
    - Each track is matched against the library. The user picks which of the
      missing tracks to download and with which source, and can retry
      failures.
    - The playlist is created locally. The user can disconnect.
    *(SpotifyPlaylistTransferViewModel)*
28. **Song recognition:**
    - Listen through the microphone and identify the song (a Shazam-format
      signature).
    - Then find it in the library or download it through the sources above.
    - The user can stop listening or forget a result.
    *(RecognizeViewModel)*
29. **Download folder:** use the default, or pick a folder through SAF.
30. **Parallel downloads** are an experimental toggle.

### 3.6 Link with the computer (Harmony Connect)

31. **Find and pair:**
    - The phone finds Harmony on the PC by UDP broadcast on port 47801.
    - The PC shows a **4-digit code**, which the user types on the phone.
      The phone then gets a token.
32. **Play on the PC, or play the PC's songs on the phone:**
    - Remote control: play, pause, seek, volume.
    - The play queue is shared both ways.
    - The phone can stream the PC's files.
33. **PC → phone transfer:**
    - The PC offers files and the phone shows a prompt (accept or decline).
    - The download runs in the background, with a report sent back.

### 3.7 Settings (functional list)

- **Theme:** light, dark or system; AMOLED black; dynamic colour.
- **Analysis:** run only while charging; rescan now.
- **Playback:**
  - Crossfade length and ReplayGain mode.
  - Keep playing after the app is swiped away.
  - Smart Shuffle defaults: familiarity, discovery and variety.
- **Online mix:** on or off, source, mobile data, auto-keep.
- **Downloads:** parallel downloads (experimental); Soulseek format
  preference; download folder.

## 4. Data model (entities)

### 4.1 `HarmonyDatabase` (Room)

| Table | Holds | Key fields |
|---|---|---|
| `songs` | One row per audio file from MediaStore | id, uri, title, artist, album, albumId, albumArtist, composer, year, genre, disc/track no., durationMs, **bitrateKbps, sampleRateHz, bitDepth, channels**, fileSizeBytes, fileHash, dateAdded, storageVolume, embeddedLyrics, artworkUri, **replayGain track/album dB** |
| `songs_fts` | Full-text index | title, artist, album |
| `albums` / `artists` | Aggregates | name, counts, artwork |
| `analysis_results` | On-device analysis per file hash | bpm, beatConfidence, musicalKey, isMajor, rms, **lufs**, peak, dynamicRange, bass/mid/treble energy, energy, danceability, acousticness, instrumentalness, brightness, warmth, aggressiveness, calmness, happiness, sadness, tension, embedding |
| `playlists` / `playlist_songs` | User playlists | id, name, createdAt; position |
| `favorites` | Hearted songs | songId |
| `play_history` | Every play | songId, playedAt, completed |
| `song_edits` | Tag overrides | title, artist, album, albumArtist, artworkUri, artworkCleared |
| `download_records` | Provenance of downloaded files | uri, title, artist, **source, provider, format, bitDepth, sampleRateHz**, size, downloadedAt, soulseekUsername, originalTrackId, **isrc** |

### 4.2 `SmartShuffleDatabase` (Room)

| Table | Holds |
|---|---|
| `acoustic_vectors` | A song's position in sound space, used for "sounds like" |
| `taste_edges` | Learned "after A, the listener likes B" transitions |
| `zone_bandit` | Explore/exploit statistics per listening zone (the bandit picks the next song) |

### 4.3 DataStore (preferences)

The keys are grouped below by area.

- **Theme:** `theme_mode`, `amoled_dark`, `dynamic_colors`.
- **Analysis:** `charging_only_analysis`.
- **Downloads:** `experimental_parallel_downloads`,
  `soulseek_format_preference`.
- **Playback:** `keep_playing_after_swipe`, `crossfade_seconds`,
  `replaygain_mode`.
- **EQ:**
  - `eq_enabled`, `eq_style`, `eq_auto`, `auto_eq_tone`,
    `eq_bit_perfect_usb`.
  - `eq_bands_v2`, `eq_preamp_v2`, `eq_winamp_bands_csv_v1`,
    `eq_user_presets_v1`.
  - `eq_clarity_live`, `eq_clarity_intensity`, `eq_clarity_balance`.
- **Online mix:** `online_mix_enabled`, `online_mix_source`,
  `online_mix_mobile_data`, `online_mix_auto_keep`.
- **Smart Shuffle:** `energy_slider`, `smart_shuffle_style_v2`,
  `smart_shuffle_familiarity_v2`, `smart_shuffle_discovery_v2`,
  `smart_shuffle_variety_v2`.
- **Output:** `output_forms_v1`.
- **Queue restore:** `last_queue_song_ids`, `last_queue_index`,
  `last_position_ms`.

Secrets live outside DataStore, in app-private SharedPreferences:
`harmony_spotify_playlist_transfer` holds the Spotify Client ID and tokens,
`harmony_soulseek` holds the Soulseek credentials, and `harmony_downloader`
holds the download settings. The download providers' extension storage is
sealed with a non-exportable Android Keystore key.

## 5. Network endpoints (Tier 1 inventory)

There is no Harmony backend. Every call goes to a public third-party service
or to the user's own PC.

| Host | Method | Path | Auth | Used for |
|---|---|---|---|---|
| `music.youtube.com` | POST | `/youtubei/v1/search`, `/youtubei/v1/next` | none (InnerTube client context) | Online mix candidates, song lookup |
| `www.youtube.com` | GET | `/oembed?format=json&url=` | none | YT Converter title check |
| `api.deezer.com` | GET | `/search`, `/search/artist`, `/search/playlist`, `/artist/{id}/top`, `/artist/{id}/related`, `/chart/{genre}/tracks`, `/playlist/{id}/tracks`, `/track/{id}`, `/album/{id}` | none | Discover catalogue, metadata, previews |
| `itunes.apple.com` | GET | `/search?entity=song`, `/lookup?id=` | none | Previews, artwork, metadata |
| `rss.marketingtools.apple.com` | GET | `/api/v2/{cc}/music/most-played/100/songs.json` | none | Discover "what's popular" |
| `musicbrainz.org` | GET | `/ws/2/artist/?query=`, `/ws/2/artist/{id}?inc=url-rels` | none (User-Agent) | Artist search and links in Discover |
| `api.song.link` | GET | `/v1-alpha.1/links?url=` | none | Translate a Deezer/Spotify link to other services |
| `amp.shazam.com` | POST | `/discovery/v5/en/US/android/-/tag/{uuid}/{uuid}` | none | Song recognition (signature upload) |
| `accounts.spotify.com` | GET / POST | `/authorize`, `/api/token` | OAuth 2 PKCE (user Client ID) | Spotify sign-in and refresh |
| `api.spotify.com` | GET | `/v1/me`, `/v1/me/playlists`, `/v1/playlists/{id}/tracks` | Bearer | Playlist import |
| `raw.githubusercontent.com` | GET | `/mornyeapp/Mornye-Extension/{ref}/registry.json`, `…/extensions/{file}.sflx` | none | Download provider registry and provider packages (pinned fallback commit) |
| Provider CDNs (`*.audio.tidal.com`, `streaming-qobuz-std.akamaized.net`, `dzcdn.net`, Amazon) | GET | signed URLs | signed URL | Audio files. Reached only through the Mornye engine. |
| `images.theshfl.com` | GET | `/{id}_600.jpg` | none | Covers for the bundled curated album catalogue |
| Soulseek network | TCP (P2P) | Soulseek protocol | username + password | Search, download, share |
| **Paired PC** `http://{lan-ip}:47800` | GET / POST | `/info`, `/pair`, `/status`, `/control`, `/play`, `/volume`, `/disconnect`, `/media/{id}?token=`, `/queue/since`, `/queue/commands`, `/transfer/offers`, `/transfer/offer/{id}`, `/transfer/file/{id}`, `/transfer/report/{id}` | 4-digit pairing → token | Harmony Connect |
| LAN broadcast | UDP | port 47801 | none | Finding PCs |

The release APK holds no hard-coded API keys. Spotify uses the user's own
Client ID.

## 6. Auth flows (Tier 2)

### 6.1 Spotify (OAuth 2.0, Authorization Code + PKCE)

1. The user pastes their own Client ID. It is required, because no Harmony
   app is registered.
2. The app builds `accounts.spotify.com/authorize` with `code_challenge`,
   scope `playlist-read-private playlist-read-collaborative`, and redirect
   `com.harmony.app://spotify-playlist-callback`, then opens the browser.
3. The redirect lands on `MainActivity`. The app checks `state`, then posts
   the code and verifier to `/api/token`. It gets an access token and a
   refresh token.
4. Requests go to `api.spotify.com/v1/*` with a Bearer token, under a strict
   URL policy (`SpotifyApiUrlPolicy` only allows that exact host and the `/v1`
   path).
5. When the access token expires, the app refreshes it (`grant_type=refresh_token`).
   If the refresh fails, it falls back to "connect again".

These are the failure states the UI must word:

- missing or invalid Client ID;
- redirect URI not registered in the Spotify dashboard;
- the browser could not open;
- the user cancelled;
- the token expired and the refresh failed;
- the playlist is private or unavailable;
- the rate limit was hit (429).

### 6.2 Download provider verification (Mornye)

1. A download fails with a "verification needed" status.
2. `prepareSpotiFlacVerification` gets a challenge URL, and the browser opens
   it.
3. The user completes the check. The browser returns through
   `mornye://callback` or `mornye://session-grant`.
4. `checkProviderVerificationAndRetry` resumes the waiting downloads.

These are the failure states the UI must word:

- the browser did not open;
- the user came back without finishing the check;
- the provider still refuses;
- the provider has no lossless copy, so the app falls back to the next
  provider.

### 6.3 Soulseek

The user enters a username and password, and the app logs into the Soulseek
server. These are the failure states the UI must word:

- wrong password;
- the server rejected the username (`INVALIDUSERNAME`, which comes with
  the reason);
- the server is unreachable;
- the user is banned or has no free slots;
- the peer went offline mid-transfer.

### 6.4 Harmony Connect pairing

1. A UDP broadcast on port 47801 finds PCs, and each PC answers with its
   `{id, name, port, version}`.
2. The user picks the PC. The PC shows a 4-digit code, and the user types it
   on the phone.
3. The phone sends `POST /pair` with the code and gets a token. Every later
   call carries that token.

These are the failure states the UI must word:

- no PC found (different network, firewall);
- wrong code;
- the PC app version is too old;
- the PC went away mid-session.

## 7. Hardware and system dependencies

| Need | Used by | When absent |
|---|---|---|
| Microphone | Recognition | The feature hides behind a permission prompt. If permission is denied, the reason is stated and Settings is offered. |
| USB audio DAC | Bit-perfect output | The option shows as unavailable, with a reason. |
| Bluetooth audio | Output picker | The device is listed as available or not. |
| Wi-Fi or mobile data | Online mix, downloads, Discover | Online mix pauses on mobile data unless the user allowed it. Downloads wait. |
| Gemini Nano (AICore) | Better title guessing for online mix | It falls back silently to rule-based matching. |
| Charging state | Library analysis | Analysis waits for the charger if that option is on. |
| Car (Android Auto) | Browse tree and controls | — |

## 8. Error surface (for Step 6 copy)

Every error a person can meet, grouped by origin:

- **Library:**
  - no audio permission;
  - an empty library;
  - a file was removed outside the app;
  - delete consent was refused;
  - the scan is still running.
- **Playback:**
  - the output device was lost;
  - an unsupported codec;
  - the PC stream dropped;
  - USB is not bit-perfect capable.
- **Online mix:**
  - offline;
  - on mobile data but not allowed;
  - no similar songs found;
  - a YouTube Music lookup failed;
  - keeping a song (downloading it) failed.
- **Downloads:**
  - not found on any provider;
  - verification needed;
  - the provider has no lossless copy;
  - the network dropped mid-file;
  - the disk is full;
  - the folder permission was lost;
  - the FFmpeg conversion failed;
  - a yt-dlp extraction failed;
  - the YouTube link is not a song;
  - the download was cancelled;
  - a duplicate was found in the library.
- **Soulseek:** the cases in §6.3, plus no results and a queue position
  instead of a download.
- **Spotify import:** the cases in §6.1, plus "N tracks could not be
  matched".
- **Recognition:** no microphone permission, too quiet or noisy, no match,
  offline.
- **Connect:** the cases in §6.4, plus a transfer that was refused, failed
  verification, or was interrupted.
- **Discover:** the catalogue could not load, a preview is unavailable, and
  no songs match the chosen preferences.
