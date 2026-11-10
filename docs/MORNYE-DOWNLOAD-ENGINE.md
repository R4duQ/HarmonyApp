# The download engine: sources, baseline, and the staged work

What Harmony's track and album downloads run on, how a download flows through
the code, what was changed in stages 0 to 4, what was tested, and what still has
to be measured on a phone. Every claim about behaviour names the file it was
read from.

## What is in each APK

| APK | Runtime | Source pin | Where it is set |
|---|---|---|---|
| Stable | SpotiFLAC 4.9.6, Go (GoMobile, `libgojni.so`) | tag `v4.9.6` **must be** commit `c0f6a60706607151d278a4d22ea2fd874b191cb1`, or the build stops (stage 1) | `scripts/ci/build-spotiflac-backend.sh`, `feature/downloads/src/stable/.../GoSpotiFlacBackend.kt` |
| Mornye | Mornye 5.3.1, Rust (UniFFI/JNA, `libmornye_mobile.so`) | tag `v5.3.1` **must be** commit `e7a8f754a618d28ab0baf88ada3f65ab9d41534a`, or the build stops (stage 1) | `scripts/ci/build-mornye-backend.sh`, `feature/downloads/src/mornye/.../MornyeSpotiFlacBackend.kt` |

Both run the same four provider packages (Tidal, Qobuz, Deezer, Amazon) from the
Mornye-Extension registry; pinned fallback packages are at
`93d55eee1cc52f088c311459e015b168c4798aa5`
(`SpotiFlacProviderRegistry.kt`). FFmpeg comes from `youtubedl-android` 0.18.1
(`feature/downloads/build.gradle.kts`); its FFmpeg version was not verified.

Harmony's baseline for this work is `main` at `19ce628`.

## The flow of one download (Harmony `19ce628`)

All in `feature/downloads/src/main/kotlin/com/harmony/feature/downloads/`.

1. **Gate.** `DownloadEngineGate.run` lets one transfer in; a second finds it
   busy (`DownloadEngineGate.kt`).
2. **Engine.** `SpotiFlacDownloadEngine.download` (line 128) →
   `downloadExclusive` (137): `ensureReady` (578: registry, provider packages,
   runtime start), a staging folder per attempt, the song.link lookup
   (`resolveProviderIdentifiers`, 1635), then `executeProviderPlan` (671).
3. **Provider plan.** One provider per native call. Before each call the
   runtime-wide route is narrowed to that provider (`configureProviderRoute`,
   1566) and the request carries `service: ""`, `use_fallback: false`.
4. **Native adapter.** `callDownloadWithWatchdog` (1128) runs
   `backend.downloadByStrategy` on an executor and waited `PROVIDER_WATCHDOG_MS`
   = 95 s (3431) **for the whole call**, then cancelled it.
5. **Progress.** A poller reads `backend.getAllDownloadProgress()` every 450 ms
   and finds the job's node by `item_id` (`readProgress`, 1423).
6. **Audio checks.** `resolveOutputFile` (2267), `probeAudioOutput`,
   `finalizeLosslessContainerToFlac` (2428), `enforceFlacQualityLimit` (2626),
   Amazon's finalizer, `enrichNativeFlacMetadata` (2779) →
   `rewriteNativeFlacMetadata` (2859), `verifyFlacSignature` (3341).
7. **Import.** The caller validates and publishes: for albums
   `AlbumDownloadWorker.downloadTrack` → `validateStagedFlac` (341) →
   `publishStagedFlac` (350).

## What the runtime does (Mornye `e7a8f754`, `rust_backend/crates/`)

- **Provider selection.** `extensions/src/backend/downloads.rs`:
  `download_by_strategy` (61) → `plan_download` (168). `selected =
  request.service` (214); with `use_fallback` false the priority list becomes
  just the selected provider (223–231); the runtime-wide priority is only the
  starting list. The provider used for the verification preflight is
  `service`, else `source` (178). So a request *can* name its own provider.
  This is read from source; it has **not** been confirmed by the runtime's
  behaviour on a phone, which is why the request still uses the runtime-wide
  route unless the stage 4 experiment is on.
- **Progress and cancel.** `core/src/downloads.rs`: one lease and one progress
  entry per `item_id` (`acquire` 127, `cancel` 131). Nothing is shared between
  two item ids.
- **Resume.** `extensions/src/download.rs` and `download/store.rs`: the file is
  staged as `<target>.partial` with `<target>.partial.checkpoint.json`
  (`store.rs` 34–52) holding a fingerprint of scheme, host and path
  (`download.rs` 421), a validator and the byte count. A later call for the
  same target continues only with a matching fingerprint and a validator,
  sending `Range` and `If-Range` (204); a 206 must start at the asked offset
  (258–263) with the same validator (274); a 200 rewinds to 0 (283). Segmented
  downloads keep `…partial.checkpoint.json.segments`
  (`download/segment_store.rs`). Whether a provider resumes at all is its
  package's choice (`transfer_policy.rs`: default `resume_policy: "none"`;
  Tidal declares `validated` with a persistent checkpoint).
- **Read timeout.** 60 s without data ends a read (`download.rs` 218), and the
  runtime retries with backoff. Harmony's 95 s stall limit sits above that.
- **Availability.** `check_availability_with_lease` is `pub(crate)`
  (`extensions/src/manager/metadata.rs` 37); the mobile API exports
  `download_by_strategy` (153) and `download` (171) in
  `mobile/src/manager.rs`, no availability call.
- **Extension data.** One folder per extension under the data directory
  (`extensions/src/environment.rs` 183).
- **Limits a package can declare.** `transfer_policy.rs` 20–33: by default 3
  attempts, 3 parallel segments, 3 concurrent downloads; Tidal's package says
  4 segments, 2 downloads.

The Go runtime (SpotiFLAC 4.9.6) was not re-read for this work; what is said
about it is what Harmony's adapter calls.

## The baseline

**Nothing here was measured on a phone.** This work was done without a device;
every performance gain below is therefore *unmeasured* until the numbers exist.

Stage 0 adds what is needed to take them. Each download writes one line, to
Logcat (`adb logcat -s HarmonyDownload`) and to
`files/download-timings.txt` in the app's storage:

```
DOWNLOAD job=426614174000 outcome=imported cold=true init=1800ms resolve=700ms first_byte=2500ms
transfer=10000ms finalize=400ms import=250ms calls=1 retries=0 host_requests=3 received=30000000 resumed=0 resent=0
```

- `cold` is true for the download that had to start the runtime.
- `first_byte` is from the provider call to the first byte (the provider's
  own lookup, sign-in and stream negotiation); progress is sampled about twice
  a second, so it is known to about half a second.
- `calls` are provider calls; `retries` adds the runtime's own attempts.
- `host_requests` are Harmony's own (registry, packages, song.link, covers).
- `resumed` are bytes continued from an earlier attempt; `resent` are bytes
  sent again (a restart inside the runtime, or a partial file that could not
  be continued).
- No titles, hosts or URLs. Diagnostics shown in the app go through
  `SpotiFlacRedaction` (tokens, keys, cookies, signed URLs).

To take the baseline: install the build before this work and the one after,
download the same album on the same network with each, once right after
starting the app (cold) and once again (warm), and compare the lines.

What can be said without a phone, from the code alone:

| | Before (`19ce628`) | After |
|---|---|---|
| Requests before the first download can start, warm cache | registry (≤ 12 s + 12 s timeouts) + song.link (≤ 15 s) | none for the registry; song.link only when the first provider's id is not known |
| Requests for covers on a 14-track album | 14 | 1 |
| song.link requests for a track retried | 1 per attempt | 1 per day |
| Longest a provider call may run | 95 s in all | 95 s to a first byte, then while bytes arrive (95 s without one ends it), 45 min at most |
| After an interrupted transfer | starts from zero | continues where the provider allows a validated resume (Tidal) |

## The stages

| Stage | What | Tests (all in `feature/downloads/src/test/…`) |
|---|---|---|
| 0 | Timelines; redacted diagnostics | `SpotiFlacDiagnosticsTest` |
| 1 | Pinned commit; package store, downgrade and permission gate; `min_app_version` per runtime; start on cached providers, registry in the background; sessions carried over; output files and covers validated | `SpotiFlacProviderGateTest`, `SpotiFlacStartupTest`, `SpotiFlacProviderRegistryTest`, `SpotiFlacProviderInstallerTest` |
| 2 | Watchdog by phase; failure kinds and Retry-After; resumable staging with leases; cancel cleans after the native call returned | `SpotiFlacTransferSupportTest`, `SpotiFlacResumeAcceptanceTest` (controlled HTTP server) |
| 3 | song.link skipped, remembered, deduplicated; availability with an explicit UNKNOWN; one cover per album; one final tag writer with album tags | `SpotiFlacAlbumWorkTest` (real FLAC fixture) |
| 4 | Two downloads at once: built, isolated per job, **off by default** | `SpotiFlacParallelTest` |

### What the tests are and are not

- They run in CI on the JVM. HTTP is a local server (`TestCdn`); the runtime's
  transfer is `FakeTransfer`, which follows the rules read from Mornye's
  `download.rs`/`store.rs` but is not the runtime. They prove Harmony's side:
  which folder a job gets, what is kept or removed, when a call is stopped,
  which file may be imported, the byte counts.
- The runtime's own resume, and the engine class as a whole (it needs Android),
  are not exercised by them.

### Decisions worth knowing

- **Reviewed hosts.** A registry package that asks to reach a host this build
  does not know for that provider is not installed; the provider stays on the
  last accepted package until a Harmony update. The lists are in
  `SpotiFlacProviderGate.reviewed`.
- **Sessions.** A new package set still starts in a new runtime folder. The
  providers whose package did not change keep their data; an updated provider
  starts clean, as before. The previous folder is kept to go back to.
- **Adopting an update.** Mornye's runtime is started again between downloads;
  SpotiFLAC's Go runtime is not restarted while the app runs, so it takes the
  new set at the next start.
- **Metadata.** The runtime still embeds what the provider has; Harmony's
  writer runs last and decides title, artist, album, album artist, ISRC and
  the album's numbering and date. No conversion or copy was removed: nothing
  here shows it would be safe to.
- **Availability.** `checkAvailability` says whether the provider's exact id is
  known and answers `UNKNOWN` for availability, because neither runtime can be
  asked without downloading.

## Still to do on a phone

1. Take the baseline and the after numbers (above).
2. Both APKs: first start with no network, start after a provider update,
   a provider signed in before the update still signed in after.
3. A hi-res track on a slow connection finishes; a transfer interrupted
   (airplane mode) continues on Tidal and restarts cleanly elsewhere;
   Cancel during a transfer, then the same track again.
4. A 14-track album: tags per track and disc in the library, one cover.
5. Stage 4, before the default changes: Settings → *Two album tracks at once
   (experimental)*; the same album with one job and with two, on steady Wi‑Fi,
   on poor Wi‑Fi and while casting; total time, failures, and whether
   playback stutters. Two at once becomes the default only if it is faster
   with no regression in any of those.
