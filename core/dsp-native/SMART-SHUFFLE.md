# Smart Shuffle engine and Diagnostic Brain

Offline Smart Shuffle: no network, no account, no genre tags. Songs are known by
how they sound.

| Layer | File | Role |
|---|---|---|
| Rust | `native/harmonyshuffle/src/` | 24-D extractor, session vector, scorer, sampling, dithering, Sonic Adventure, the Diagnostic Brain (`brain.rs`), the mix (`radio.rs`), `run_synthetic_brain_simulation()` |
| JNI | `native/harmonyshuffle/src/lib.rs` | `SmartShuffleNative`, exported by JNI name, one mutex per engine |
| C++ | `src/main/cpp/SmartShuffleEngine.h/.cpp` | the original, now built by the iOS app only; the Rust port matches it value for value (ties between equal scores aside: the port breaks them stably, the same way on every platform) |
| Kotlin | `src/main/kotlin/.../SmartShuffleNative.kt` | `NativeShuffleEngine`, `NativeAcousticExtractor` |
| Room | `data/shuffle/.../SmartShuffleDatabase.kt` | `smart_shuffle.db`: 24-D vectors, Markov transitions, zone bandit |
| Brain | `data/shuffle/.../NativeShuffleBrain.kt` | keeps the engine in step with both databases |
| Queue | `domain/shuffle/.../SmartQueueManager.kt` | the player's queue, feedback, telemetry, `SHUFFLE_BRAIN` Logcat |
| UI | `feature/player/.../ShuffleBrainDebugOverlay.kt` | the HUD (Smart Shuffle sheet → Show brain HUD) |

## The pipeline

1. **Indexing.** `AnalysisRepositoryImpl` decodes each song once (first 4 minutes,
   only while nothing plays). The PCM feeds the older analyser and the
   `AcousticExtractor`: ten RBJ octave band-passes (Q 1.414, the EQ engine's
   analysis bank) and an 80 Hz low-pass, read in 50 ms blocks; silent blocks are
   left out. The 24 raw features go to `acoustic_vectors`, keyed to the file hash.
   After the update every song is queued once for its vector.
2. **Loading.** `NativeShuffleBrain` loads the songs, artists (tag edits applied),
   vectors, play history, favourites, Markov habits and the bandit. It reloads when
   the song, vector or analysis count changes, and at least every 30 minutes.
3. **Session.** `S_t` follows the brief exactly:
   - past half: `normalize(0.60 v + 0.40 S)`;
   - under 20% (a fast skip): `normalize(S − 0.35 v)`. The queue is not touched:
     skipping never changes the songs already queued, only the batch appended
     at the end next;
   - a hand pick, or a song swiped in as Play next: `normalize(0.85 v + 0.15 S)`.
4. **Scoring.**
   `(w_sim·sim + w_markov·P(prev→i) + w_aff·affinity + w_explore·UCB) · cooldown`
   over the whole library, then:
   - Efraimidis–Spirakis A-Res sampling (`ln(u)/w`) of the batch;
   - 1D error-diffusion dithering by artist;
   - a greedy nearest-neighbour chain among the artists the dithering says are due.
5. **Audit.** The brain runs Check 4 first, then 2, 3, 1 and 5, and repairs the
   draft in place.

## Where it departs from the brief, and why

- **The working space is standardized.** Raw features are all positive, and
  positive vectors are always close: unrelated songs score a cosine of 0.9 or
  more. Similarity would then discriminate nothing, and Check 3 (> 0.97) would
  fire on almost any queue. So each dimension is z-scored against the library
  (clamped to ±4), the three groups are weighted 1.0 / 0.6 / 1.1, and only then is
  each vector normalized to unit length. In that space unrelated songs sit near 0,
  and cosine is still a 24-float dot product. Unknown dimensions (NaN) land on the
  library mean.
- **Dims 0–9 are band RMS levels** (the square root of each band's average
  energy), L2-normalized. Raw energies would let a single band dominate by 20 dB.
- **Cold start has two levels.**
  - `FALLBACK`: approximated from the older analysis (band split, crest, a
    confident tempo).
  - `NONE`: a fixed pseudo-random direction, a weak similarity, and never steers
    `S_t`.
- **The contextual bandit works on sonic zones.** Context is the band `S_t` leans
  on most and the arm is the pick's band: 10 × 10 cells of pulls and rewards
  (finished 1, middle 0.5, fast skip 0). Its UCB is mixed with each track's UCB1
  novelty and staleness. Zones stay stable across reloads; clusters would not.
- **Play counts are not duplicated.** Affinity reads `harmony.db`'s play history
  and favourites. `smart_shuffle.db` is a separate file so its schema can change
  without migrating the library database.
- **Check statuses.** Besides PASS and HEALED there is FAILED (a red LED), for
  when the library makes a repair impossible. Hiding that would make the audit
  dishonest.
- **Check 2.** "Acoustically distinct" means new to the tail and a cosine below
  0.5 with the skipped song. If `S_t` was the skipped song itself, subtracting
  0.35 v cannot change its direction; the brain then removes the component along
  v and leans on what the session finished.
- **Check 5 keeps two kinds of jumps.**
  - A jump out of a learned Markov habit is kept: the listener makes that jump
    themselves.
  - Leaving a song that was just fast-skipped is the pivot, not a rough
    transition.
- **Rolling batches.** The player's queue is refilled when fewer than 4 songs are
  left, up to 8. A larger batch gives the dithering something to spread.
- **ExoPlayer is driven through `PlaybackController`** (the app's MediaController
  wrapper), like everything else in the app. That keeps gapless playback, Android
  Auto and Harmony Connect working.

## Mix din piesa asta (a radio from one song)

The queue sheet in Now Playing has **Mix from this song** and, while a mix
runs, **Stop mix**. A mix is a radio started from the song playing, made from
the local library only: nothing in it touches a network, so it works the
same in airplane mode. `SmartShuffleEngine::generateRadio` builds each batch;
`SmartQueueManager.startMix/stopMix` runs it as a variant of Smart Shuffle.

### What it borrows from YouTube Music

Google's posts describe YouTube Music's recommender as three stages
(retrieval, ranking, filtering), with a transformer in the ranking stage that
weighs each past action (a skip, a full play, a pick) by how relevant its
context is to the current one: a skip while working out counts for little at
home. A later study ("Breaking the Loop") found that what the listener already
plays crowds out what they have not heard, and compared ways to make room for
novelty. Harmony cannot run a transformer trained on millions of listeners, so
each idea is mapped onto what a phone has: the 24-D vectors instead of learned
track embeddings, the local Markov habits instead of co-listening across
users, and a small attention over this session's feedback.

| Stage | YouTube Music | Harmony's mix |
|---|---|---|
| 1. Candidates | Several retrieval sources merged | Up to 80 closest to the seed, 40 closest to S_t, 40 from habits (what was played after the seed, after the song the batch follows, and after the 12 songs that sound most like the seed; noisy-OR `1 − Π(1 − w·P)`), 24 rarely heard songs (≤ 2 plays) that sound right (cosine ≥ 0.40), 40 from the taste memory (below). Union, duplicates removed. |
| 2. Ranking | Transformer over the action sequence and the current context | `RadioWeights` (below). Session feedback goes through an attention: an event counts `0.85^age × (0.35 + 0.65 × cos(context then, context now))`, times a sharp likeness kernel `exp((cos − 1)/0.15)`, a fast skip 1.5 times a full play. |
| 3. Filtering | Business rules, diversity | Queue, excluded and session-played songs out; the same artist at least 3 songs apart; songs new to the listener only above an acoustic gate, and no more of them than a quarter of the batch (or the share of new songs among the seed's own closest matches, when that is higher). |

### The score (Harmony's starting weights)

These weights are Harmony's choices, not YouTube Music's (which publishes
none). Each head is 0..1.

```
relevance  = max(cos(0.7·seed + 0.3·S_t, v),  0.8 × historyConfidence × habit,  0.75 × taste)
transition = max((1 − 0.6·h)·cos(song before, v) + 0.6·h·habit,  0.75 × taste)   h = historyConfidence
preference = s·(0.5 + 0.5·feedback) + (1 − s)·(c + 1)/(c + 1.5·k + 2)  s = session evidence for v
novelty    = 1/√(1 + plays), only once there is history, never below the gate

score = (w_rel·relevance + 0.20·transition + w_pref·preference + 0.10·novelty)
        × cooldown × exp(1.2 · min(0, feedback))
w_pref = 0.20 × max(historyConfidence, sessionConfidence);  w_rel = 0.50 + (0.20 − w_pref)
```

- **Little history means a mix by sound.** `historyConfidence` reaches 1 at
  40 plays across the library, `sessionConfidence` at 4 feedback events; the
  preference weight they leave unused goes to relevance.
- **Feedback.** A near-complete play (≥ 90%) is +1, past half +0.5, a pick by
  hand (the seed included) +0.8, 20–50% −0.25, a fast skip (< 20%) −1. Only
  the session's last 48 events are kept; `resetSession` forgets them.
- **The seed stays the landmark.** It carries 70% of relevance in every
  batch, however far S_t drifts.
- **Songs without a 24-D vector** use the cold-start approximation from the
  older analysis (`FALLBACK`, trusted at 0.85). Songs with nothing measured
  (`NONE`) take part only through habits or as the last fill, and say so.
- **The taste memory** (`set_taste`, filled by `TasteMemory` in `data/shuffle`).
  Each YouTube Music radio an online mix reads is remembered on the phone:
  the seed song with every recording of its radio (`0.6 × rank` per radio,
  rank 1 at the top down to 0.4 at the bottom, noisy-OR as radios repeat it),
  and the seed's artist with every other artist of it (`0.25 × rank` per
  appearance). An offline mix reads it as co-listening it could not learn
  alone: `taste = 1 − Π(1 − w)` over the seed's links, the links of the song
  the batch follows (× 0.5) and the seed artist's links to each song's artist
  (× 0.7). It vouches for relevance and fit as habits do, and a song with
  taste ≥ 0.3 passes the gate for new songs (or songs not measured yet: a song
  just kept from an online mix). With no taste memory, every mix is exactly as
  before.
- The Diagnostic Brain audits each batch as it audits Smart Shuffle's
  (integrity, clumping, jerk), repairing only from the mix's own candidates;
  a habitual jump is kept, as for Smart Shuffle.

### In the queue

- **Start:** Smart Shuffle turns on if it was off (the list's own songs still
  to come make way, as when Smart Shuffle starts), the session restarts on the
  seed, and the free picks after the protected head (the song playing, the
  next one, the Play next run) become the mix's first batch. The user's
  entries stay where they are.
- **While it runs:** the rolling top-up is Smart Shuffle's (fewer than 10
  ahead: append up to 12). Feedback never rewrites what is already shown; it
  shapes the next batch.
- **Stop:** back to the mode from before. From Smart Shuffle, what is queued
  stays and the next batches are Smart Shuffle's; from off, the original
  running order comes back. A new list, another shuffle mode or a journey
  also ends the mix.
- Logcat (`SHUFFLE_BRAIN`) gets one `MIX` line per batch: ids, stage counts,
  confidences and timing, no titles.

### Measured against Smart Shuffle (host test `radioComparedWithSmartShuffle`)

240 synthetic songs (six sonic families in two rhythm styles, cosine ≈ 0.70
within a style, 0.57 across), one shared history (families 0–2 well played, a
habitual follower after family 2), 18 seeds, the same simulated listener
(fast-skips the busy style of the seed's family, finishes the rest), batches
of 10:

| engine | cos@5 | fam@5 | artists | closest repeat | skipped style, batch 1 → 2 | its share of the family in batch 2 | batch 2 still on the seed's family | habit found |
|---|---|---|---|---|---|---|---|---|
| Smart Shuffle | 0.72 | 97% | 7.5 | 3.8 | 2.5 → 0.7 | 21% | 32% | 2/18 |
| Mix | 0.75 | 98% | 7.7 | 3.9 | 2.7 → 0.8 | 12% | 72% | 5/18 |

Smart Shuffle answers the skips mostly by leaving the seed's family; the mix
stays on it and keeps fewer of the skipped style among those songs. A 20,000
song library builds a mix batch of 10 in about 1.1 ms on a desktop core.
These are synthetic families: whether the mix *sounds* better has to be
judged by listening (see the device checklist in the pull request).

## Running the tests

```sh
(cd native && cargo test --release -p harmonyshuffle --features testing)   # engine, brain, extractor, self-test
(cd native && cargo build --release)       # the host JNI library for the JVM tests
HARMONY_SHUFFLE_HOST_LIB_DIR=$PWD/native/target/release ./gradlew :core:dsp-native:testReleaseUnitTest
./gradlew :domain:shuffle:test :data:shuffle:testReleaseUnitTest
adb logcat -s SHUFFLE_BRAIN                # the live table on a device
```

A 20,000-song library builds a 10-song queue, with the full audit, in about
2 ms on a desktop core.
