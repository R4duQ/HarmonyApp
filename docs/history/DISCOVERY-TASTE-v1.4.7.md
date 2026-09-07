# Discover taste and selected downloads — Harmony 1.4.7

The new song deck records explicit likes and dislikes and offers Skip without a
negative vote. It provides horizontal gestures and equivalent accessible buttons,
undo for the last rating, and preview playback on demand. The album browser stays
available as a separate mode. Its catalogue now contains 96 albums across 19 genres.

## Recommendation and batch behavior

`SongTasteEngine` combines normalized ratings at album, artist and genre level
with the existing local-library relevance score. No machine learning service or
listening-history upload is used. Album bookmarks, manual familiarity and the
listened flag keep their previous meanings. Ratings are persisted in the existing
Discover preferences with mutually exclusive like/dislike sets and serialized
commits. Rapid duplicate swipes are ignored until persistence catches up.

Refresh selects up to 24 previously unshown candidates in the current filter round.
It does not merely shuffle a fixed first page. With 96 eligible albums there are
four disjoint batches; a new round avoids immediately repeating the last batch.
Filters with fewer candidates display the actual count, and fewer than 24 remaining
candidates produce a partial batch. Shelf/genre changes reset the round. New taste
ratings rerank For you. All albums remains unfiltered by taste; Saved still contains
bookmarked albums. This is a finite curated selection, not a live SHFL feed.

Each added album's public page and cover metadata were checked against The Shfl on
2026-09-06. Source links use observed slugs, including Exodus-1 and Wave-1 to avoid
different albums with the same title. Listening notes are original Harmony text.

## Preview and connectivity

An explicit Listen tap first searches the local library for the exact recording.
Otherwise it queries Deezer for a matching artist/title with a provider preview URL.
Only HTTPS preview URLs on the provider CDN are accepted. A small in-memory cache
bounds repeated lookups; responses are size-limited and requests have timeouts.
Unavailable previews get a visible retry/skip state, never an unrelated recording.

The existing pinned Media3 1.5.0 player clips playback to at most 30 seconds, handles
audio focus and unplugged headphones, and is released on navigation, backgrounding,
connection loss, another preview, or ViewModel teardown. The main queue pauses when
preview is requested. Preview playback does not contribute to full-album listening
coverage or library play counts. Album downloads continue to use the chosen native
SpotiFLAC/Soulseek engine, not preview audio.

One application-scoped `ConnectivityManager` callback observes the default network's
INTERNET and VALIDATED capabilities. Loss disables online entry points immediately.
Validation starts a cancellable five-second countdown; duplicate capability events
and screen rotation do not restart it. Discover hides its interactive online content
while unavailable; the album screen disables metadata/download/provider controls.
ViewModel guards also reject launches while offline or recovering. Edition loading
retries after connection recovery, including when an older request was still busy.
Local playback, favorites, file management and Pause remain available. WorkManager
continues to apply network/storage constraints to queued transfers.

## Track selection and progress ownership

`selectedForDownload` persists per track, defaults to true for older journals, and
does not alter coverage or file provenance. Existing files are shown as available.
Start freezes selected missing track IDs, source and format into WorkManager input.
The worker iterates that selection, reuses checkpointed files and retains the previous
bounded staging, validation, peer selection and single scan behavior. No selection
means Start is disabled. Selection changes for a queued album require Pause.

The full-album review still requires every track's listening coverage; selecting only
a few tracks does not silently mark an album complete. Manual keep/delete management
remains available for partial albums. Existing deletion confirmation is preserved.

The flickering banner came from unrelated workflow ViewModels publishing null/clear
into the same global slot while album work continued. `DownloadStatusCenter.Publisher`
now gives each ViewModel and each worker a separate owner. Clearing a screen cannot
clear an album, and foreground album progress takes precedence. A completion banner
cannot replace active progress; stale dismissal timers cannot consume new outcomes.
Album pages display their own scoped live progress and suppress the duplicate shell
banner. The Android foreground notification is started once per worker execution,
updated under the same ID between tracks, uses a fixed timestamp and alerts only once.
Pause, completion and a genuine WorkManager stop can still remove that notification.

## Verification

- 51 JUnit tests passed: 30 catalogue/recommendation/URL/batch/taste tests and 21
  coverage/matching/selection/native-gate/progress-owner/network-recovery tests.
- Full core UI and Library, full Discover excluding its unchanged DI module, and
  the changed album worker/ViewModel/screens/store/monitor were compiled with Kotlin
  2.1, Android 35 and the project's real Compose, Media3 and WorkManager APIs.
  Existing native engine boundaries use matching signatures in the isolated check.
- App navigation compiled with real changed features; unrelated destination screens
  use signatures. This checks Kotlin/API integration, not Android resource merging
  or generated Hilt bindings from a full Gradle build.
- Runtime harnesses exercised the real preference stores and ViewModel logic with
  simulated Android/lifecycle boundaries: persistent ratings, concurrent writes,
  undo, duplicate input, process recreation, four distinct batches, offline guards,
  selected tracks, legacy journal defaults and the existing deletion/consent flows.

The full Gradle build cannot fetch Gradle 8.11.1 in this environment (`Network is
unreachable`). No APK or device render was produced. Actual provider playback,
downloads and the OS notification lifecycle require a device check. No battery or
network-throughput benchmark is claimed. Room's schema version is unchanged.

Primary integration references:

- https://developer.android.com/develop/connectivity/network-ops/reading-network-state
- https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running
- https://developer.android.com/media/media3/exoplayer/media-items
- https://support.deezer.com/hc/en-gb/articles/360011538897-Deezer-FAQs-For-Developers
- https://theshfl.com/best-of

Phone checks: swipe/undo a song, refresh through four batches, try several new genre
filters, enable airplane mode then reconnect while Discover is open, download two
selected tracks, navigate through Downloads/Spotify Transfer during that batch, pause
and reopen the album, and confirm that the unchanged keep/delete flow retains the
unselected files when Android consent is denied.
