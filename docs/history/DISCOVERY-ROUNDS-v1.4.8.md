# Explained swipe rounds — Harmony 1.4.8

Discovery now ends each ten-vote round with one album reveal. The result connects
real liked songs to the chosen album and pairs that personalized explanation with
the existing original Harmony listening note. The full album's public SHFL page
remains available through the existing validated launcher. No review text is fetched
or automatically summarized, and no external language-model service is required.

## Listener flow

Like and dislike count toward the visible ten-choice progress bar; Skip has no
preference effect. The deck prioritizes other albums before another single from
an album already rated during this round. At ten votes, it stops accepting votes,
scrolls to the dedicated reveal and shows the album cover, reason, listening note,
starting track and actions to choose downloads, bookmark, read on SHFL or continue.
An entry song the user actually liked is used as the starting track when possible.

Undo removes the current round's final vote. Undoing the tenth vote clears the
reveal and restores nine choices. Earlier votes in the same round may then be undone
in reverse order. Next round preserves historical likes/dislikes and archives the
previous recommendation; it clears only round progress. Changing genre, switching
to Albums, returning from the album detail screen and process recreation preserve
the active round and its chosen album. Bookmarks update directly on the reveal.

The existing 24-album browser, download selection, previews, connectivity recovery
and keep/delete flows retain their 1.4.7 behavior. A missing connection hides the
Discovery interaction; ViewModel guards also block votes, undo and round changes
until the existing five-second validated-network recovery completes.

## Ranking and evidence

`SwipeRoundEngine` handles pure transitions. `RoundAlbumRecommender` ranks the
catalogue using the existing normalized album/artist/genre taste scores. The score
is three times the current-round score plus the score across all saved choices,
including the current round. It is a transparent heuristic rather than a trained
recommendation model. The separate Albums / For you ranking still includes the
existing local-library matches.

The round picker excludes albums manually marked listened. It avoids an explicitly
disliked album from the current round when alternatives exist, unless a different
song from that album was liked in the same round. It first favors positively scored
choices, then previously unrecommended albums within that pool when possible. A
round-seeded tie break is deterministic; the chosen ID is persisted, so later UI
changes cannot silently choose another album.

Explanations cite up to two actual liked songs, preferring a direct album match,
then the same artist, then overlapping genres with a net positive vote count. If
only older likes support the result, the text identifies them as older choices.
If no likes support it, the result is labeled exploratory. No percentage confidence,
inferred audio characteristics or invented SHFL endorsement is displayed.

The second explanation is the album's existing original `listeningNote`, attributed
to Harmony. The link is attributed to The Shfl. The catalogue remains the same
finite set of 96 albums across 19 genres, with no new remote content in this release.

## Persistence and boundaries

The existing preferences file gains `swipe_round_v1` JSON (number, ordered song IDs,
completion flag and chosen album ID) and `recommended_album_ids`. Votes and round
state are written in one serialized editor commit. Store transitions return their
persisted result; the ViewModel can handle a vote rejected by newer store state
without waiting indefinitely for a choice that was never accepted. Input remains
disabled while the store write is in progress. No database schema changes are made.

Old vote sets are retained as history, with a fresh empty round on upgrade. Unknown
song/album IDs are filtered, contradictory legacy ratings favor the like set, and
invalid round JSON falls back to an empty round without deleting taste history.
The final incomplete round may be explicitly revealed after all catalogue songs
have been rated. This also supports an exhausted pre-upgrade history. Next round
is unavailable when no unrated songs remain. If all eligible albums were marked
listened, completion has a visible empty-result explanation and the album browser
remains available.

## Verification

- 45 Discovery JUnit tests passed, including 15 new tests covering reveal at ten,
  duplicate votes, blocked extra votes, undo, round boundaries, recent-vote weighting,
  rejected/listened albums, recommendation variety, evidence and catalogue exhaustion.
- All Discover Kotlin/Compose code excluding the unchanged Hilt module compiled
  against Android 35 and the project's actual Compose and Media3 APIs.
- App navigation compiled with the real updated Discover and existing feature jars;
  unrelated screens/native engine boundaries use matching signatures in this check.
- A runtime harness exercised the real store and ViewModel with simulated Android
  preferences/lifecycle boundaries: four distinct album batches, concurrent input,
  offline/recovery guards, restoration at nine votes, reveal at ten, unchanged reveal
  after filtering/bookmarking/recreation, undo and re-vote, the next round, retained
  history, malformed JSON and exhausted legacy ratings.

These checks do not run Android resource merging, generated Hilt bindings, physical
gestures or on-device rendering. The full Gradle build remains unavailable in this
environment because its distribution cannot be downloaded. No APK is included.
Build/install using the existing Windows script under `D:\Harmony`, with Android
SDK at `D:\Android` and the existing signing key and local configuration preserved.

Phone acceptance: make nine choices with some skips, relaunch, make the tenth,
read both explanations, undo and re-vote, open the selected-download screen and
return, bookmark, start a new round, and check that airplane mode followed by
reconnection restores access after five seconds without losing progress.
