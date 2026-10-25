# Smart Shuffle engine and Diagnostic Brain

Offline Smart Shuffle: no network, no account, no genre tags. Songs are known by
how they sound.

| Layer | File | Role |
|---|---|---|
| C++ | `src/main/cpp/SmartShuffleEngine.h/.cpp` | 24-D extractor, session vector, scorer, sampling, dithering, Sonic Adventure, the `ShuffleDiagnosticBrain`, `runSyntheticBrainSimulation()` |
| JNI | `src/main/cpp/shuffle_jni.cpp` | `SmartShuffleNative`, registered in `JNI_OnLoad`, one lock per engine |
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

## Running the tests

```sh
cmake -S core/dsp-native/src/test/cpp -B build/shuffle-host && cmake --build build/shuffle-host
./build/shuffle-host/shuffle_test          # engine, brain, extractor, self-test
HARMONY_SHUFFLE_HOST_LIB_DIR=$PWD/build/shuffle-host ./gradlew :core:dsp-native:testReleaseUnitTest
./gradlew :domain:shuffle:test :data:shuffle:testReleaseUnitTest
adb logcat -s SHUFFLE_BRAIN                # the live table on a device
```

A 20,000-song library builds a 10-song queue, with the full audit, in about
2 ms on a desktop core.
