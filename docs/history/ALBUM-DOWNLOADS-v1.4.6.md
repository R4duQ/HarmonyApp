# Album downloads and file management — Harmony 1.4.6

Discover now opens an album workflow directly from each sleeve. The listener
chooses a metadata edition and sees its full tracklist before starting a batch.
The public Deezer metadata endpoint already used by Harmony supplies editions
and tracklists; The Shfl remains the source of the curated recommendation.
This feature does not require Spotify playlist authorization.

## Download behavior

- One unique WorkManager album job runs at a time, in a foreground `dataSync`
  service with a progress notification and a Pause action. Network and storage
  constraints apply; an optional unmetered constraint waits for a suitable network.
- The selected SpotiFLAC/Soulseek engine is explicit. No automatic switch between
  these sources. FLAC and MP3 are supported. SpotiFLAC MP3 still downloads lossless
  input first; Soulseek MP3 keeps the peer's actual bitrate.
- Existing files require a conservative artist/title/album/duration match. A
  checkpointed URI is verified before reuse. Formats of existing files stay intact.
- Native transfers share an exclusive gate because the clients expose single
  active-transfer state. Album cancellation cannot be triggered by a manual
  workflow's cancel button. A busy engine returns an actionable retry state.
- Soulseek prefers free slots, shorter queues and faster peers, warms up at most
  two connections, and tries up to three candidates for each track. Transfers
  remain serial. Duration is checked before publishing; container validation is
  shared with the existing download workflow.
- Only the current track is staged. The album job checks temporary space and
  removes its abandoned staging file. It checkpoints the published URI before
  library scanning, so indexing failures do not start another download of that file.
- One incremental scan runs at batch completion; Play can also request a scan.
  Completed tracks survive pause, navigation and process recreation. Retry uses
  exponential backoff for recognized transient failures, with at most two automatic
  retries; unresolved tracks remain visible for manual resume.
- Provider verification pauses the batch and stays in its own callback ownership
  route. After browser verification, the listener explicitly resumes the album.
  Interrupted-file byte resumption depends on the native engine and source.

## Listening and the keep/delete decision

An application-scoped repository persists the edition, ordered tracks, exact
published URIs, file provenance, byte sizes and listened intervals. Mutations
are serialized and committed before the observable state changes. This is separate
from scrobble counts and Discover's manually set "Heard the album" flag.

The playback service monitors the actual player, including notification controls
and screen-off listening. Position intervals are bounded by elapsed real time and
playback speed, merged as a union, and reset at seek/track discontinuities. Replaying
the same section cannot accumulate coverage twice. Checkpoints are flushed about
every ten seconds and at pauses/transitions; abrupt process loss can undercount
the last uncommitted interval. Playback duration takes precedence over rounded
metadata duration.

An album is ready when at least 90% of every track in the chosen edition has been
heard. The activity-level review waits until playback pauses/stops or leaves the
album, then offers Keep whole album, Keep selected, Delete whole album, and Decide
later. Decide later defers automatic review for 24 hours; manual management stays
available. Favorites and pre-existing tracks start selected. Whole-album deletion
explicitly includes existing tracks too. No listening event itself deletes a file.

## Deletion

The Songs list, search results, arc browser and album detail expose file deletion.
Every action first displays a file-count/title confirmation explaining permanence.
On Android 11+, MediaStore files use `createDeleteRequest` in bounded groups.
Android 10 uses per-item recoverable permission consent and retries only after a
positive result. Document-provider files use SAF; ordinary file URIs delete only
files, never directories. A permission or provider error is not treated as success.

Only successful/verified deletions clean Room and the player. The DAO transaction
deletes exact URIs and download records, cascades the existing song foreign keys,
and prunes empty albums/artists. The controller removes all matching queue entries,
clears cached songs and persists the remaining queue. Late playback-history events
use an atomic `INSERT ... WHERE EXISTS` to avoid a foreign-key failure after deletion.
Android consent state retains its bound URI list across activity recreation.

Android references:
- https://developer.android.com/training/data-storage/shared/media
- https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running
- https://developer.android.com/develop/background-work/services/fgs/timeout

## Validation and limits

- 38 JUnit tests: the existing 24 Discover tests plus 14 coverage, matching and
  native-transfer gate tests. Checks include skipped/repeated sections, unknown
  durations, partial albums, different recordings, compilations and cancellation.
- Isolated Kotlin 2.1 compilation of the domain modules, album worker/view models,
  Compose screens, persistence, deletion host and Media3 monitor against Android 35
  and the project's actual AndroidX/Compose/Coil/WorkManager versions. Existing
  download engine boundaries are represented by signatures in that compile.
- The complete core UI and Library feature compile together against their real
  domain APIs, including the arc/list/search deletion controls.
- Application navigation and Discover are checked together; unrelated destination
  screens are represented by their function signatures.
- A local harness exercises real repository/deletion/review logic with simulated
  storage and Android consent: persistent coverage, failed preference commit,
  Android 10 retry, Android 11+ batch consent, partial results, denied consent,
  revoked access, SAF deletion and preservation of unselected files.
- SQLite checks use the exported schema and real DAO statements to verify cascade
  cleanup, retained tracks, album pruning and the late-history-event race.

The full Gradle/Android build is blocked by Gradle distribution network access in
this environment. No APK, device layout, real Android permission dialog, provider
audio transfer, WorkManager process restart or battery benchmark was tested here.
Provider availability and source matching still require a phone check. There is no
new Room schema version: album-session state is stored separately in preferences.
