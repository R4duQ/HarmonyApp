//! SmartShuffleEngine: the library of unit vectors, the session vibe vector
//! S_t, multi-factor scoring, Efraimidis-Spirakis sampling, 1D error-diffusion
//! dithering (Spotify's anti-clumping) and greedy nearest-neighbour ordering;
//! Sonic Adventure journeys between two songs.
//!
//! Everything runs on the phone, from audio alone: no network, no account and
//! no genre tags. Not thread-safe by itself: one caller at a time (the JNI
//! bridge holds a mutex per engine).
//!
//! ## The working space
//!
//! The raw features are all positive, and positive vectors are always close:
//! two unrelated songs score a cosine of 0.9 or more, which leaves similarity
//! nothing to discriminate with (and would trip the echo-chamber check on every
//! queue). So set_library standardizes each dimension against the library
//! (z-score, clamped to +-4), weights the three groups, and only then
//! normalizes every vector to unit length. In that space cosine similarity is
//! a plain 24-float dot product, unrelated songs sit near 0, and a dimension
//! marked unknown (NaN, for songs indexed only by the older analysis) lands on
//! the library average instead of skewing the vector.
//!
//! Ported from SmartShuffleEngine.cpp. The arithmetic keeps the C++ float and
//! double types step for step. Where the C++ sorted with std::sort or
//! std::partial_sort (whose order among equal keys depends on the standard
//! library), this port sorts stably, so ties resolve the same way everywhere.

use std::cmp::Ordering;
use std::collections::{BTreeMap, HashMap, HashSet, VecDeque};
use std::time::Instant;

use crate::types::*;

pub(crate) const EPS: f32 = 1e-12;

/// Weight of each group of dimensions in the working space.
const SPECTRAL_GROUP_WEIGHT: f32 = 1.00;
const DYNAMICS_GROUP_WEIGHT: f32 = 0.60;
const MACRO_GROUP_WEIGHT: f32 = 1.10;

/// "Acoustically distinct" from a skipped song (Check 2).
pub(crate) const DISTINCT_COSINE: f32 = 0.50;
pub(crate) const SKIP_TAIL_SHARE: f32 = 0.60;
pub(crate) const NORM_TOLERANCE: f32 = 0.001;
/// The mix's feedback memory: this many events (a long session's worth).
const FEEDBACK_MEMORY: usize = 48;
/// Played this far is a near-complete play: the strongest positive signal.
const NEAR_COMPLETE_FRACTION: f32 = 0.9;
/// A song picked by hand, as feedback for the mix.
const MANUAL_SIGNAL: f32 = 0.8;

/// splitmix64: small, fast, and reproducible from one seed.
pub(crate) struct Rng(u64);

impl Rng {
    pub(crate) fn new(seed: u64) -> Self {
        Rng(seed ^ 0x9E37_79B9_7F4A_7C15)
    }
    pub(crate) fn next(&mut self) -> u64 {
        self.0 = self.0.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = self.0;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    }
    /// Uniform in the open interval (0, 1).
    pub(crate) fn uniform(&mut self) -> f64 {
        ((self.next() >> 11) as f64 + 0.5) * (1.0 / 9_007_199_254_740_992.0)
    }
}

pub(crate) fn clamp01(v: f32) -> f32 {
    if v.is_finite() {
        1.0f32.min(0.0f32.max(v))
    } else {
        0.0
    }
}

fn group_weight(dim: usize) -> f32 {
    if dim < BANDS {
        SPECTRAL_GROUP_WEIGHT
    } else if dim < 2 * BANDS {
        DYNAMICS_GROUP_WEIGHT
    } else {
        MACRO_GROUP_WEIGHT
    }
}

pub(crate) fn entropy_bits(counts: &BTreeMap<i64, i32>, total: i32) -> f32 {
    if total <= 0 {
        return 0.0;
    }
    let mut h = 0.0f64;
    for &n in counts.values() {
        if n <= 0 {
            continue;
        }
        let p = n as f64 / total as f64;
        h -= p * p.log2();
    }
    h as f32
}

/// Sorts `v` best first by `key` (descending), stably: equal keys keep their
/// order. Each key is computed once.
pub(crate) fn sort_desc_by<T: Clone, K: PartialOrd>(v: &mut [T], key: impl Fn(&T) -> K) {
    let keys: Vec<K> = v.iter().map(&key).collect();
    let mut order: Vec<usize> = (0..v.len()).collect();
    order.sort_by(|&a, &b| keys[b].partial_cmp(&keys[a]).unwrap_or(std::cmp::Ordering::Equal));
    let sorted: Vec<T> = order.iter().map(|&i| v[i].clone()).collect();
    v.clone_from_slice(&sorted);
}

/// The first `k` of `v` under `order`, in order, with equal elements in
/// their original order (as a stable sort would list them). The rest is
/// dropped. O(n + k log k).
pub(crate) fn top_k_by<T: Clone>(v: &mut Vec<T>, k: usize, order: impl Fn(&T, &T) -> Ordering) {
    let k = k.min(v.len());
    // A total order: `order`, then position.
    let total = |&a: &usize, &b: &usize| order(&v[a], &v[b]).then(a.cmp(&b));
    let mut index: Vec<usize> = (0..v.len()).collect();
    if k > 0 && k < index.len() {
        index.select_nth_unstable_by(k - 1, total);
    }
    index.truncate(k);
    index.sort_by(total);
    *v = index.iter().map(|&i| v[i].clone()).collect();
}

/// The `k` best of `v` by `key` (descending), as a stable sort would list
/// them. Each key is computed once. Used where the C++ took the top k with
/// std::partial_sort, whose order among equal keys the standard library decides.
pub(crate) fn top_k_desc_by<T: Clone, K: PartialOrd>(v: &mut Vec<T>, k: usize, key: impl Fn(&T) -> K) {
    let keys: Vec<K> = v.iter().map(&key).collect();
    let mut index: Vec<usize> = (0..v.len()).collect();
    top_k_by(&mut index, k, |&a, &b| keys[b].partial_cmp(&keys[a]).unwrap_or(Ordering::Equal));
    *v = index.iter().map(|&i| v[i].clone()).collect();
}

/// Best first: `a` before `b` when its key is larger.
fn desc(a: f32, b: f32) -> Ordering {
    b.partial_cmp(&a).unwrap_or(Ordering::Equal)
}

pub(crate) fn dot(a: &[f32], b: &[f32]) -> f32 {
    // Fixed-length views: no bounds checks in the loop. The sum stays in
    // order (as the C++ wrote it, without -ffast-math), so it is not reordered.
    let (a, b): (&[f32; DIMS], &[f32; DIMS]) = (a[..DIMS].try_into().unwrap(), b[..DIMS].try_into().unwrap());
    let mut s = 0.0f32;
    for d in 0..DIMS {
        s += a[d] * b[d];
    }
    s
}

pub(crate) fn distance(a: &[f32], b: &[f32]) -> f32 {
    let (a, b): (&[f32; DIMS], &[f32; DIMS]) = (a[..DIMS].try_into().unwrap(), b[..DIMS].try_into().unwrap());
    let mut s = 0.0f32;
    for d in 0..DIMS {
        let diff = a[d] - b[d];
        s += diff * diff;
    }
    s.sqrt()
}

/// Scales `v` to unit length; false (and `v` untouched) when it is not finite or ~0.
pub(crate) fn normalize(v: &mut [f32]) -> bool {
    let mut s = 0.0f64;
    for &x in &v[..DIMS] {
        if !x.is_finite() {
            return false;
        }
        s += x as f64 * x as f64;
    }
    #[allow(clippy::neg_cmp_op_on_partial_ord)]
    if !(s > 1e-12) {
        return false;
    }
    let inv = (1.0 / s.sqrt()) as f32;
    for x in &mut v[..DIMS] {
        *x *= inv;
    }
    true
}

/// The octave band a vector leans on most (its sonic zone).
pub fn zone_of(v: &[f32]) -> i32 {
    let mut best = 0;
    for k in 1..BANDS {
        if v[k] > v[best] {
            best = k;
        }
    }
    best as i32
}

/// "Bass", "Warm", "Vocal", "Bright" or "Air" ("Mixed" outside 0..9).
pub fn zone_word(zone: i32) -> &'static str {
    match zone {
        z if !(0..ZONES as i32).contains(&z) => "Mixed",
        0..=2 => "Bass",
        3..=4 => "Warm",
        5..=6 => "Vocal",
        7..=8 => "Bright",
        _ => "Air",
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct Candidate {
    pub row: i32,
    pub score: f32,
    pub vibe: f32,
    pub markov: f32,
    pub affinity: f32,
    pub explore: f32,
    pub cooldown: f32,
    pub cosine: f32,
}

impl Default for Candidate {
    fn default() -> Self {
        Candidate {
            row: -1,
            score: 0.0,
            vibe: 0.0,
            markov: 0.0,
            affinity: 0.0,
            explore: 0.0,
            cooldown: 1.0,
            cosine: 0.0,
        }
    }
}

#[derive(Clone, Default)]
pub(crate) struct PendingSkip {
    pub active: bool,
    pub id: i64,
    pub vector: [f32; DIMS],
    pub dot_before: f32,
    pub dot_after: f32,
    pub healed: bool,
}

/// One feedback event for the mix: the song's vector, the vector of the song
/// it followed, and the signal (+1 a full play, +0.8 a pick by hand, -1 a fast skip...).
#[derive(Clone)]
pub(crate) struct FeedbackEvent {
    pub item: [f32; DIMS],
    pub context: [f32; DIMS],
    pub has_context: bool,
    pub signal: f32,
}

/// The queue the brain audits and repairs.
#[derive(Clone, Default)]
pub(crate) struct Draft {
    /// Rows already ahead of the new tracks (current song, then queued ones).
    pub prefix_rows: Vec<i32>,
    /// The new tracks, in queue order. The brain may swap or replace them.
    pub placed_rows: Vec<i32>,
    /// Scored candidates not placed, best first: replacements come from here.
    pub reserve_rows: Vec<i32>,
    /// The Sonic Adventure keeps its order: no swaps, replacements only.
    pub allow_reorder: bool,
    /// The most explorable songs, whatever they sound like (Check 3's fresh tracks).
    pub fresh_rows: Vec<i32>,
    /// Ids queued before a fast-skip re-rank (Check 2).
    pub replaced_tail: Vec<i64>,
}

impl Draft {
    pub(crate) fn new() -> Self {
        Draft { allow_reorder: true, ..Default::default() }
    }
}

pub struct SmartShuffleEngine {
    // Library.
    pub(crate) ids: Vec<i64>,
    pub(crate) vectors: Vec<f32>,
    pub(crate) quality: Vec<VectorQuality>,
    pub(crate) artist: Vec<i32>,
    pub(crate) album: Vec<i32>,
    pub(crate) completed: Vec<i32>,
    pub(crate) skipped: Vec<i32>,
    pub(crate) last_played: Vec<i64>,
    pub(crate) favorite: Vec<u8>,
    pub(crate) steering: Vec<f32>,
    pub(crate) zone: Vec<i8>,
    pub(crate) row_by_id: HashMap<i64, i32>,
    /// What set_library / update_tracks were given, kept to rebuild the working space.
    input_raw: Vec<f32>,
    input_quality: Vec<u8>,
    pub(crate) repaired_inputs: i32,
    pub(crate) counts: [i32; 3],

    // Habits.
    pub(crate) transitions: HashMap<i64, HashMap<i64, f32>>,
    pub(crate) transition_totals: HashMap<i64, f32>,
    /// Taste: songs online radios put together (song id -> song id, 0..1).
    pub(crate) taste_songs: HashMap<i64, HashMap<i64, f32>>,
    /// Taste by artist (the artist ids of set_library), 0..1.
    pub(crate) taste_artists: HashMap<i32, HashMap<i32, f32>>,
    pub(crate) bandit_pulls: [[f32; ZONES]; ZONES],
    pub(crate) bandit_rewards: [[f32; ZONES]; ZONES],
    pub(crate) weights: Weights,
    pub(crate) explore_boost: f32,

    // Session.
    pub(crate) session: [f32; DIMS],
    /// Sum of the vectors of songs played past half this session.
    pub(crate) liked_sum: [f32; DIMS],
    pub(crate) has_session: bool,
    pub(crate) started: VecDeque<i64>,
    pub(crate) session_played: HashSet<i64>,
    pub(crate) predecessor: HashMap<i64, i64>,
    pub(crate) picked_context: HashMap<i64, i32>,
    pub(crate) pending_skip: PendingSkip,
    /// The mix's session feedback, newest last (at most FEEDBACK_MEMORY).
    pub(crate) feedback: VecDeque<FeedbackEvent>,
    pub(crate) radio_weights: RadioWeights,
    pub(crate) radio: RadioReport,
    learned_from: i64,
    learned_to: i64,
    learned_delta: f32,
    bandit_context: i32,
    bandit_arm: i32,
    bandit_reward: f32,

    // Telemetry.
    pub(crate) candidate_by_row: HashMap<i32, Candidate>,
    pub(crate) traces: Vec<TrackDecisionTrace>,
    pub(crate) audit: BrainAuditReport,
    pub(crate) generation: u64,
}

impl Default for SmartShuffleEngine {
    fn default() -> Self {
        Self::new()
    }
}

impl SmartShuffleEngine {
    pub fn new() -> Self {
        SmartShuffleEngine {
            ids: Vec::new(),
            vectors: Vec::new(),
            quality: Vec::new(),
            artist: Vec::new(),
            album: Vec::new(),
            completed: Vec::new(),
            skipped: Vec::new(),
            last_played: Vec::new(),
            favorite: Vec::new(),
            steering: Vec::new(),
            zone: Vec::new(),
            row_by_id: HashMap::new(),
            input_raw: Vec::new(),
            input_quality: Vec::new(),
            repaired_inputs: 0,
            counts: [0; 3],
            transitions: HashMap::new(),
            transition_totals: HashMap::new(),
            taste_songs: HashMap::new(),
            taste_artists: HashMap::new(),
            bandit_pulls: [[0.0; ZONES]; ZONES],
            bandit_rewards: [[0.0; ZONES]; ZONES],
            weights: Weights::default(),
            explore_boost: 1.0,
            session: [0.0; DIMS],
            liked_sum: [0.0; DIMS],
            has_session: false,
            started: VecDeque::new(),
            session_played: HashSet::new(),
            predecessor: HashMap::new(),
            picked_context: HashMap::new(),
            pending_skip: PendingSkip::default(),
            feedback: VecDeque::new(),
            radio_weights: RadioWeights::default(),
            radio: RadioReport::default(),
            learned_from: -1,
            learned_to: -1,
            learned_delta: 0.0,
            bandit_context: -1,
            bandit_arm: -1,
            bandit_reward: 0.0,
            candidate_by_row: HashMap::new(),
            traces: Vec::new(),
            audit: BrainAuditReport::default(),
            generation: 0,
        }
    }

    // ---- Vector math and inspection ---------------------------------------------------

    #[inline]
    pub(crate) fn vec(&self, row: i32) -> &[f32] {
        let at = row as usize * DIMS;
        &self.vectors[at..at + DIMS]
    }

    pub(crate) fn row_of(&self, id: i64) -> i32 {
        self.row_by_id.get(&id).copied().unwrap_or(-1)
    }

    pub fn size(&self) -> usize {
        self.ids.len()
    }

    /// The working (standardized, unit) vector of a track.
    pub fn vector_of(&self, id: i64) -> Option<[f32; DIMS]> {
        let row = self.row_of(id);
        (row >= 0).then(|| self.vec(row).try_into().expect("24 floats"))
    }

    pub fn cosine(&self, a: i64, b: i64) -> f32 {
        let (ra, rb) = (self.row_of(a), self.row_of(b));
        if ra < 0 || rb < 0 {
            return 0.0;
        }
        dot(self.vec(ra), self.vec(rb))
    }

    pub fn zone_of_track(&self, id: i64) -> i32 {
        let row = self.row_of(id);
        if row < 0 {
            -1
        } else {
            self.zone[row as usize] as i32
        }
    }

    /// How many raw values were corrupt (Inf, absurd) and repaired at load.
    pub fn repaired_input_values(&self) -> i32 {
        self.repaired_inputs
    }

    pub fn explore_boost(&self) -> f32 {
        self.explore_boost
    }

    pub fn weights(&self) -> Weights {
        self.weights
    }

    pub fn has_session(&self) -> bool {
        self.has_session
    }

    /// S_t, 24 floats (zeros before the first song).
    pub fn session_vector(&self) -> &[f32; DIMS] {
        &self.session
    }

    pub fn last_traces(&self) -> &[TrackDecisionTrace] {
        &self.traces
    }

    pub fn last_audit(&self) -> &BrainAuditReport {
        &self.audit
    }

    pub fn last_radio(&self) -> &RadioReport {
        &self.radio
    }

    pub fn set_radio_weights(&mut self, weights: RadioWeights) {
        self.radio_weights = weights;
    }

    pub fn radio_weights(&self) -> RadioWeights {
        self.radio_weights
    }

    /// The session feedback the mix learns from (newest last), for tests and the HUD.
    pub fn feedback_count(&self) -> usize {
        self.feedback.len()
    }

    #[cfg(any(test, feature = "testing"))]
    pub fn poison_session_for_test(&mut self, value: f32) {
        self.session = [value; DIMS];
        self.has_session = true;
    }

    // ---- Library and habits -------------------------------------------------------------

    /// Loads the library. `raw` holds `ids.len() * 24` raw features (NaN =
    /// unknown), `quality` one VectorQuality per track; artist and album ids
    /// are interned by the caller (negative = unknown, never clumped with
    /// anyone). Each optional slice, when given, holds one value per track.
    /// Resets statistics and steering, keeps the session and the learned
    /// habits. Returns the number of tracks loaded.
    pub fn set_library(
        &mut self,
        ids: &[i64],
        raw: Option<&[f32]>,
        quality: Option<&[u8]>,
        artist_ids: Option<&[i32]>,
        album_ids: Option<&[i32]>,
    ) -> usize {
        let n = ids.len();
        self.ids = ids.to_vec();
        self.artist = vec![-1; n];
        self.album = vec![-1; n];
        self.completed = vec![0; n];
        self.skipped = vec![0; n];
        self.last_played = vec![0; n];
        self.favorite = vec![0; n];
        self.steering.clear();
        self.weights.steering = 0.0;
        self.row_by_id = HashMap::with_capacity(n * 2);
        self.input_raw = match raw {
            Some(r) => r[..n * DIMS].to_vec(),
            None => vec![f32::NAN; n * DIMS],
        };
        self.input_quality = match quality {
            Some(q) => q[..n].to_vec(),
            None => vec![VectorQuality::Real as u8; n],
        };
        for i in 0..n {
            self.artist[i] = artist_ids.map_or(-1, |a| a[i]);
            self.album[i] = album_ids.map_or(-1, |a| a[i]);
            self.row_by_id.insert(self.ids[i], i as i32);
        }
        self.rebuild_vectors();
        n
    }

    /// Replaces the raw features and quality of the tracks in `ids` (24 floats
    /// and one VectorQuality each, as for set_library) and rebuilds the working
    /// space. Ids not in the library are ignored. Unlike set_library this keeps
    /// the statistics, the steering and everything learned this session: it is
    /// for new measurements arriving while the library itself is unchanged.
    /// The vectors come out exactly as a set_library of the same data would
    /// give them (the library-wide standardization is recomputed, so every
    /// vector may move a little). Returns how many tracks were updated.
    pub fn update_tracks(&mut self, ids: &[i64], raw: &[f32], quality: &[u8]) -> usize {
        let mut updated = 0;
        for (k, &id) in ids.iter().enumerate() {
            let row = self.row_of(id);
            if row < 0 || raw.len() < (k + 1) * DIMS || quality.len() <= k {
                continue;
            }
            let at = row as usize * DIMS;
            self.input_raw[at..at + DIMS].copy_from_slice(&raw[k * DIMS..(k + 1) * DIMS]);
            self.input_quality[row as usize] = quality[k];
            updated += 1;
        }
        if updated > 0 {
            self.rebuild_vectors();
        }
        updated
    }

    /// The working space from the stored inputs: standardize every dimension
    /// against the library, weight the groups, normalize; a placeholder
    /// direction where nothing is known. Also re-checks the session vector.
    fn rebuild_vectors(&mut self) {
        let n = self.ids.len();
        self.vectors = vec![0.0; n * DIMS];
        self.quality = vec![VectorQuality::Real; n];
        self.zone = vec![-1; n];
        self.repaired_inputs = 0;
        self.counts = [0; 3];
        self.candidate_by_row.clear();

        // Copy the raw features, turning corrupt values into "unknown".
        let mut values = vec![f32::NAN; n * DIMS];
        let mut sum = [0.0f64; DIMS];
        let mut sum_sq = [0.0f64; DIMS];
        let mut known = [0i32; DIMS];
        for i in 0..n {
            for d in 0..DIMS {
                let mut x = self.input_raw[i * DIMS + d];
                if x.is_infinite() || (x.is_finite() && x.abs() > 1e6) {
                    self.repaired_inputs += 1;
                    x = f32::NAN;
                }
                values[i * DIMS + d] = x;
                if x.is_finite() {
                    sum[d] += x as f64;
                    sum_sq[d] += x as f64 * x as f64;
                    known[d] += 1;
                }
            }
        }

        // Standardize against the library: mean and deviation of each dimension.
        let mut mean = [0.0f32; DIMS];
        let mut deviation = [1.0f32; DIMS];
        for d in 0..DIMS {
            if known[d] >= 2 {
                let m = sum[d] / known[d] as f64;
                let var = (sum_sq[d] / known[d] as f64 - m * m).max(0.0);
                mean[d] = m as f32;
                deviation[d] = 0.02f32.max(var.sqrt() as f32);
            } else {
                mean[d] = if known[d] == 1 { sum[d] as f32 } else { 0.0 };
                deviation[d] = 1.0;
            }
        }

        for i in 0..n {
            let at = i * DIMS;
            let mut known_dims = 0;
            for d in 0..DIMS {
                let x = values[at + d];
                self.vectors[at + d] = if x.is_finite() {
                    known_dims += 1;
                    4.0f32.min((-4.0f32).max((x - mean[d]) / deviation[d])) * group_weight(d)
                } else {
                    0.0
                };
            }
            let mut q = VectorQuality::from_u8(self.input_quality[i]);
            let v = &mut self.vectors[at..at + DIMS];
            if known_dims == 0 || !normalize(v) {
                // Nothing to stand on: a fixed pseudo-random direction, so the
                // song still gets a place in the space and a fair, small chance.
                let mut rng = Rng::new((self.ids[i] as u64).wrapping_mul(0x2545_F491_4F6C_DD1D).wrapping_add(17));
                for x in v.iter_mut() {
                    *x = (rng.uniform() * 2.0 - 1.0) as f32;
                }
                if !normalize(v) {
                    v.fill(0.0);
                    v[0] = 1.0;
                }
                q = VectorQuality::None;
            }
            self.zone[i] = zone_of(v) as i8;
            self.quality[i] = q;
            self.counts[q as usize] += 1;
        }

        if self.has_session && !normalize(&mut self.session) {
            self.session = [0.0; DIMS];
            self.has_session = false;
        }
    }

    /// Listening history, aligned with the library order (each slice optional;
    /// `count` values are read from every slice given).
    pub fn set_track_stats(
        &mut self,
        completed_plays: Option<&[i32]>,
        skipped_plays: Option<&[i32]>,
        last_played_ms: Option<&[i64]>,
        favorite: Option<&[u8]>,
        count: usize,
    ) {
        let n = count.min(self.size());
        for i in 0..n {
            self.completed[i] = completed_plays.map_or(0, |c| c[i].max(0));
            self.skipped[i] = skipped_plays.map_or(0, |s| s[i].max(0));
            self.last_played[i] = last_played_ms.map_or(0, |l| l[i].max(0));
            self.favorite[i] = favorite.map_or(0, |f| (f[i] != 0) as u8);
        }
    }

    /// Per-track 0..1 steering scores (energy target, mood), or None to clear.
    pub fn set_steering(&mut self, per_track: Option<&[f32]>, weight: f32) {
        let Some(values) = per_track.filter(|v| !v.is_empty() && weight > 0.0) else {
            self.steering.clear();
            self.weights.steering = 0.0;
            return;
        };
        self.steering = vec![0.5; self.size()];
        let n = values.len().min(self.size());
        for i in 0..n {
            self.steering[i] = if values[i].is_finite() { clamp01(values[i]) } else { 0.5 };
        }
        self.weights.steering = 3.0f32.min(weight);
    }

    /// Markov habits: weights of "to after from" transitions, added to what is
    /// known. `weight` (one per pair) defaults to 1.
    pub fn add_transitions(&mut self, from: &[i64], to: &[i64], weight: Option<&[f32]>) {
        for i in 0..from.len().min(to.len()) {
            let w = weight.map_or(1.0, |w| w[i]);
            if !w.is_finite() || w <= 0.0 || from[i] == to[i] {
                continue;
            }
            *self.transitions.entry(from[i]).or_default().entry(to[i]).or_insert(0.0) += w;
            *self.transition_totals.entry(from[i]).or_insert(0.0) += w;
        }
    }

    /// Replaces the taste memory: what online radios, seen while the phone
    /// was online, put together. `to[i]` was in a radio of `from[i]` (song
    /// ids), `artist_to[i]` in radios of `artist_from[i]` (the artist ids of
    /// set_library). Weights are 0..1; anything else, and a pair of one song
    /// or one artist with itself, is ignored. The mix ("Mix from this song")
    /// reads it; Smart Shuffle does not.
    pub fn set_taste(
        &mut self,
        from: &[i64],
        to: &[i64],
        weight: &[f32],
        artist_from: &[i32],
        artist_to: &[i32],
        artist_weight: &[f32],
    ) {
        self.taste_songs.clear();
        self.taste_artists.clear();
        for i in 0..from.len().min(to.len()).min(weight.len()) {
            let w = weight[i];
            if w.is_finite() && w > 0.0 && from[i] != to[i] {
                let cell = self.taste_songs.entry(from[i]).or_default().entry(to[i]).or_insert(0.0);
                *cell = cell.max(w.min(1.0));
            }
        }
        for i in 0..artist_from.len().min(artist_to.len()).min(artist_weight.len()) {
            let w = artist_weight[i];
            if w.is_finite() && w > 0.0 && artist_from[i] >= 0 && artist_to[i] >= 0 && artist_from[i] != artist_to[i] {
                let cell = self.taste_artists.entry(artist_from[i]).or_default().entry(artist_to[i]).or_insert(0.0);
                *cell = cell.max(w.min(1.0));
            }
        }
    }

    pub fn clear_transitions(&mut self) {
        self.transitions.clear();
        self.transition_totals.clear();
    }

    /// Markov probability P(to | from) in [0, 1], shrunk by evidence.
    pub fn markov_probability(&self, from: i64, to: i64) -> f32 {
        let Some(row) = self.transitions.get(&from) else { return 0.0 };
        let Some(&cell) = row.get(&to) else { return 0.0 };
        let max_weight = row.values().fold(0.0f32, |m, &w| m.max(w));
        let total = self.transition_totals.get(&from).copied().unwrap_or(0.0);
        if max_weight <= 0.0 {
            return 0.0;
        }
        clamp01(cell / max_weight * (total / (total + 3.0)))
    }

    /// The contextual bandit, ZONES x ZONES pulls and rewards (row = context).
    pub fn set_zone_bandit(&mut self, pulls: &[f32], rewards: &[f32]) {
        for c in 0..ZONES {
            for a in 0..ZONES {
                let p = pulls.get(c * ZONES + a).copied().unwrap_or(0.0);
                let r = rewards.get(c * ZONES + a).copied().unwrap_or(0.0);
                self.bandit_pulls[c][a] = if p.is_finite() { 0.0f32.max(p) } else { 0.0 };
                self.bandit_rewards[c][a] =
                    if r.is_finite() { self.bandit_pulls[c][a].min(0.0f32.max(r)) } else { 0.0 };
            }
        }
    }

    pub fn zone_bandit(&self) -> ([f32; ZONES * ZONES], [f32; ZONES * ZONES]) {
        let mut pulls = [0.0; ZONES * ZONES];
        let mut rewards = [0.0; ZONES * ZONES];
        for c in 0..ZONES {
            for a in 0..ZONES {
                pulls[c * ZONES + a] = self.bandit_pulls[c][a];
                rewards[c * ZONES + a] = self.bandit_rewards[c][a];
            }
        }
        (pulls, rewards)
    }

    pub fn set_weights(&mut self, w: &Weights) {
        let bounded =
            |v: f32, lo: f32, hi: f32, fallback: f32| if v.is_finite() { hi.min(lo.max(v)) } else { fallback };
        let defaults = Weights::default();
        self.weights.similarity = bounded(w.similarity, 0.0, 5.0, defaults.similarity);
        self.weights.markov = bounded(w.markov, 0.0, 5.0, defaults.markov);
        self.weights.affinity = bounded(w.affinity, 0.0, 5.0, defaults.affinity);
        self.weights.explore = bounded(w.explore, 0.0, 5.0, defaults.explore);
        self.weights.temperature = bounded(w.temperature, 0.02, 2.0, defaults.temperature);
        self.weights.min_artist_spacing = w.min_artist_spacing.clamp(0, 20);
        self.weights.jerk_threshold = bounded(w.jerk_threshold, 0.1, 2.0, defaults.jerk_threshold);
        self.weights.echo_threshold = bounded(w.echo_threshold, 0.5, 1.0, defaults.echo_threshold);
        // Steering keeps its own weight, set with set_steering.
    }

    // ---- Part 2: the session vibe vector --------------------------------------------------

    pub(crate) fn context_zone(&self) -> i32 {
        if self.has_session {
            zone_of(&self.session)
        } else {
            -1
        }
    }

    fn remember_start(&mut self, id: i64) {
        if self.started.back() == Some(&id) {
            return;
        }
        if let Some(&back) = self.started.back() {
            if self.predecessor.len() > 512 {
                self.predecessor.clear();
            }
            self.predecessor.insert(id, back);
        }
        self.started.push_back(id);
        while self.started.len() > 64 {
            self.started.pop_front();
        }
        self.session_played.insert(id);
        if self.has_session {
            let context = self.context_zone();
            self.picked_context.insert(id, context);
        }
        if self.picked_context.len() > 512 {
            self.picked_context.clear();
        }
    }

    /// A song started playing. Seeds S_t if there is none yet.
    pub fn on_track_started(&mut self, id: i64) -> SessionEvent {
        self.remember_start(id);
        let row = self.row_of(id);
        if self.has_session || row < 0 || self.quality[row as usize] == VectorQuality::None {
            return SessionEvent::None;
        }
        let v: [f32; DIMS] = self.vec(row).try_into().expect("24 floats");
        self.session = v;
        self.has_session = true;
        SessionEvent::Seeded
    }

    /// A song ended or was left after `fraction` of its length. Updates S_t,
    /// the in-memory habits (Markov, bandit, affinity) and arms the skip
    /// audit. `shuffle_pick` says whether Smart Shuffle had queued it (bandit reward).
    pub fn on_playback_outcome(&mut self, id: i64, fraction: f32, now_ms: i64, shuffle_pick: bool) -> SessionEvent {
        self.learned_from = -1;
        self.learned_to = -1;
        self.learned_delta = 0.0;
        self.bandit_context = -1;
        self.bandit_arm = -1;
        let f = if fraction.is_finite() { 1.0f32.min(0.0f32.max(fraction)) } else { 0.0 };
        let played = f > 0.5;
        let fast_skip = f < 0.2;
        let row = self.row_of(id);

        // Affinity, in memory (the history database is the record).
        if row >= 0 {
            let r = row as usize;
            if played {
                self.completed[r] += 1;
            }
            if fast_skip {
                self.skipped[r] += 1;
            }
            self.last_played[r] = self.last_played[r].max(now_ms);
        }

        // Markov habit: a song heard past half after the one before it.
        if played {
            if let Some(&pred) = self.predecessor.get(&id) {
                if pred != id {
                    *self.transitions.entry(pred).or_default().entry(id).or_insert(0.0) += 1.0;
                    *self.transition_totals.entry(pred).or_insert(0.0) += 1.0;
                    self.learned_from = pred;
                    self.learned_to = id;
                    self.learned_delta = 1.0;
                }
            }
        }

        // Contextual bandit: the zone Smart Shuffle reached for, in the zone the session was in.
        if shuffle_pick && row >= 0 {
            let context = self.picked_context.get(&id).copied().unwrap_or_else(|| self.context_zone());
            if context >= 0 {
                let reward = if played {
                    1.0
                } else if fast_skip {
                    0.0
                } else {
                    0.5
                };
                let arm = self.zone[row as usize] as usize;
                self.bandit_pulls[context as usize][arm] += 1.0;
                self.bandit_rewards[context as usize][arm] += reward;
                self.bandit_context = context;
                self.bandit_arm = arm as i32;
                self.bandit_reward = reward;
            }
        }

        // The mix's feedback memory: a near-complete play is the strongest yes,
        // a fast skip the strongest no; the middle says little.
        if row >= 0 && self.quality[row as usize] != VectorQuality::None {
            let signal = if f >= NEAR_COMPLETE_FRACTION {
                1.0
            } else if played {
                0.5
            } else if fast_skip {
                -1.0
            } else {
                -0.25
            };
            self.remember_feedback(row, signal);
        }

        if row < 0 || self.quality[row as usize] == VectorQuality::None {
            return SessionEvent::Neutral;
        }
        let v: [f32; DIMS] = self.vec(row).try_into().expect("24 floats");

        if played {
            for d in 0..DIMS {
                self.liked_sum[d] += v[d];
            }
            if !self.has_session || !normalize(&mut self.session) {
                self.session = v;
                self.has_session = true;
                return SessionEvent::Seeded;
            }
            for d in 0..DIMS {
                self.session[d] = 0.60 * v[d] + 0.40 * self.session[d];
            }
            if !normalize(&mut self.session) {
                self.session = v;
            }
            return SessionEvent::PlayEma;
        }

        if fast_skip {
            if !self.has_session || !normalize(&mut self.session) {
                // Nothing to push away from yet: start opposite the skipped song.
                for d in 0..DIMS {
                    self.session[d] = -v[d];
                }
                self.has_session = true;
            }
            let before = self.session;
            let dot_before = dot(&before, &v);
            let mut next = [0.0f32; DIMS];
            for d in 0..DIMS {
                next[d] = before[d] - 0.35 * v[d];
            }
            let moved = normalize(&mut next) && dot(&next, &v) < dot_before - 1e-4;
            let mut healed = false;
            if !moved {
                // S_t was the skipped song itself (or the update was degenerate):
                // remove its direction outright and lean on what the session liked.
                let mut liked = self.liked_sum;
                let along = dot(&liked, &v);
                for d in 0..DIMS {
                    liked[d] -= along * v[d];
                }
                let have_liked = normalize(&mut liked);
                for d in 0..DIMS {
                    next[d] = before[d] - (dot_before + 0.35) * v[d] + if have_liked { 0.5 * liked[d] } else { 0.0 };
                }
                if !normalize(&mut next) {
                    for d in 0..DIMS {
                        next[d] = -v[d];
                    }
                }
                healed = true;
            }
            self.session = next;
            self.pending_skip =
                PendingSkip { active: true, id, vector: v, dot_before, dot_after: dot(&self.session, &v), healed };
            return SessionEvent::FastSkip;
        }
        SessionEvent::Neutral
    }

    /// The listener picked this song by hand.
    pub fn on_manual_select(&mut self, id: i64) -> SessionEvent {
        let row = self.row_of(id);
        self.pending_skip.active = false;
        if row < 0 || self.quality[row as usize] == VectorQuality::None {
            return SessionEvent::None;
        }
        let v: [f32; DIMS] = self.vec(row).try_into().expect("24 floats");
        self.remember_feedback(row, MANUAL_SIGNAL);
        if self.has_session && normalize(&mut self.session) {
            for d in 0..DIMS {
                self.session[d] = 0.85 * v[d] + 0.15 * self.session[d];
            }
            if !normalize(&mut self.session) {
                self.session = v;
            }
        } else {
            self.session = v;
        }
        self.has_session = true;
        SessionEvent::ManualAnchor
    }

    pub fn reset_session(&mut self) {
        self.session = [0.0; DIMS];
        self.liked_sum = [0.0; DIMS];
        self.has_session = false;
        self.started.clear();
        self.session_played.clear();
        self.predecessor.clear();
        self.picked_context.clear();
        self.pending_skip = PendingSkip::default();
        self.feedback.clear();
        self.explore_boost = 1.0;
    }

    fn remember_feedback(&mut self, row: i32, signal: f32) {
        let mut event = FeedbackEvent {
            item: self.vec(row).try_into().expect("24 floats"),
            context: [0.0; DIMS],
            has_context: false,
            signal,
        };
        // The context: the song this one followed in the session, when known.
        if let Some(&before) = self.predecessor.get(&self.ids[row as usize]) {
            let b = self.row_of(before);
            if b >= 0 && self.quality[b as usize] != VectorQuality::None {
                event.context.copy_from_slice(self.vec(b));
                event.has_context = true;
            }
        }
        self.feedback.push_back(event);
        while self.feedback.len() > FEEDBACK_MEMORY {
            self.feedback.pop_front();
        }
    }

    /// The last Markov transition learned (from, to, delta weight), for
    /// persistence: Some((from, to, delta)) when a transition was recorded at
    /// all; the bool says whether it was learned (delta > 0).
    pub fn last_learned_transition(&self) -> Option<(i64, i64, f32, bool)> {
        if self.learned_from < 0 && self.learned_to < 0 {
            return None;
        }
        Some((self.learned_from, self.learned_to, self.learned_delta, self.learned_delta > 0.0))
    }

    /// The bandit cell the last outcome rewarded (context, arm, reward), for persistence.
    pub fn last_bandit_update(&self) -> Option<(i32, i32, f32)> {
        if self.bandit_context < 0 || self.bandit_arm < 0 {
            return None;
        }
        Some((self.bandit_context, self.bandit_arm, self.bandit_reward))
    }

    // ---- Part 3: scoring and queue building ------------------------------------------------

    fn affinity_of(&self, row: usize) -> f32 {
        let c = self.completed[row] as f32;
        let s = self.skipped[row] as f32;
        let rate = (c + 1.0) / (c + s + 2.0); // Laplace: 0.5 when unknown
        let strength = 1.0f32.min(c.ln_1p() / 11.0f32.ln());
        let mut a = 0.65 * rate + 0.35 * strength;
        if self.favorite[row] != 0 {
            a = 1.0f32.min(a + 0.25);
        }
        clamp01(a)
    }

    fn exploration_of(&self, row: usize, context: i32, now_ms: i64) -> f32 {
        // Track level: UCB1's bonus (few plays -> large) and staleness (a song not
        // heard for a month is worth rediscovering).
        let n = (self.completed[row] + self.skipped[row]) as f32;
        let novelty = 1.0 / (n + 1.0).sqrt();
        let mut stale = 1.0f32;
        if self.last_played[row] > 0 {
            let days = 0i64.max(now_ms - self.last_played[row]) as f64 / 86_400_000.0;
            stale = (1.0 - (-days / 30.0).exp()) as f32;
        }
        // Context level: UCB on the zone this track leans on, given the zone the
        // session is in. Zones the listener keeps finishing earn trust; zones
        // rarely tried keep an exploration bonus.
        let mut zone_ucb = 0.5f32;
        if context >= 0 {
            let (context, arm) = (context as usize, self.zone[row] as usize);
            let pulls = self.bandit_pulls[context][arm];
            let mut context_pulls = 0.0f32;
            for a in 0..ZONES {
                context_pulls += self.bandit_pulls[context][a];
            }
            let mean_reward = (self.bandit_rewards[context][arm] + 1.0) / (pulls + 2.0);
            let bonus = (2.0 * (context_pulls + 2.0).ln() / (pulls + 1.0)).sqrt();
            zone_ucb = clamp01(0.6 * mean_reward + 0.4 * 1.0f32.min(bonus / 2.5));
        }
        clamp01(0.40 * zone_ucb + 0.35 * novelty + 0.25 * stale)
    }

    pub(crate) fn cooldown_of(&self, row: usize, now_ms: i64) -> f32 {
        if self.last_played[row] <= 0 {
            return 1.0;
        }
        let hours = 0i64.max(now_ms - self.last_played[row]) as f64 / 3_600_000.0;
        clamp01((1.0 - 0.9 * (-hours / 4.0).exp()) as f32)
    }

    pub(crate) fn score_row(
        &self,
        row: i32,
        target: Option<&[f32]>,
        context: i32,
        markov_norm: f32,
        now_ms: i64,
    ) -> Candidate {
        let r = row as usize;
        let mut cos = target.map_or(0.0, |t| dot(t, self.vec(row)));
        if !cos.is_finite() {
            cos = 0.0;
        }
        // Less trust in what was guessed than in what was measured.
        match self.quality[r] {
            VectorQuality::None => cos *= 0.3,
            VectorQuality::Fallback => cos *= 0.8,
            VectorQuality::Real => {}
        }
        let sim01 = if target.is_some() { 0.5 * (cos + 1.0) } else { 0.5 };
        let mut c = Candidate { row, cosine: cos, ..Default::default() };
        c.vibe = self.weights.similarity * sim01
            + if self.steering.is_empty() { 0.0 } else { self.weights.steering * self.steering[r] };
        c.markov = self.weights.markov * markov_norm;
        c.affinity = self.weights.affinity * self.affinity_of(r);
        c.explore = self.weights.explore * self.explore_boost * self.exploration_of(r, context, now_ms);
        c.cooldown = self.cooldown_of(r, now_ms);
        c.score = (c.vibe + c.markov + c.affinity + c.explore) * c.cooldown;
        if !c.score.is_finite() {
            c.score = 0.0;
        }
        c
    }

    fn score_library(&self, target: Option<&[f32]>, anchor_id: i64, now_ms: i64, blocked: &[bool]) -> Vec<Candidate> {
        let mut out = Vec::with_capacity(self.size());
        let context = self.context_zone();
        let mut max_weight = 0.0f32;
        let mut evidence = 0.0f32;
        let markov_row = self.transitions.get(&anchor_id);
        if let Some(row) = markov_row {
            max_weight = row.values().fold(0.0f32, |m, &w| m.max(w));
            let t = self.transition_totals.get(&anchor_id).copied().unwrap_or(0.0);
            evidence = t / (t + 3.0);
        }
        for r in 0..self.size() {
            if blocked[r] {
                continue;
            }
            let mut markov_norm = 0.0f32;
            if let Some(row) = markov_row.filter(|_| max_weight > 0.0) {
                if let Some(&cell) = row.get(&self.ids[r]) {
                    markov_norm = clamp01(cell / max_weight * evidence);
                }
            }
            out.push(self.score_row(r as i32, target, context, markov_norm, now_ms));
        }
        out
    }

    /// Efraimidis-Spirakis A-Res: key = u^(1/w), kept as ln(u)/w; the largest
    /// keys win. Weighted sampling without replacement in one pass: strong
    /// candidates usually win, but never always, which is what keeps a queue
    /// organic instead of the same top ten every time. Returns every row of
    /// `pool`, in the drawn order.
    pub(crate) fn reservoir_sample(&self, pool: &[Candidate], seed: u64) -> Vec<i32> {
        if pool.is_empty() {
            return Vec::new();
        }
        let best = pool.iter().fold(pool[0].score, |b, c| b.max(c.score));
        let mut rng = Rng::new(seed);
        let temperature = 0.02f32.max(self.weights.temperature) as f64;
        let mut keyed: Vec<(f64, i32)> = pool
            .iter()
            .map(|c| {
                let w = 1e-30f64.max((((c.score - best) as f64) / temperature).exp());
                (rng.uniform().ln() / w, c.row)
            })
            .collect();
        sort_desc_by(&mut keyed, |k| k.0);
        keyed.into_iter().map(|(_, r)| r).collect()
    }

    pub(crate) fn artist_key(&self, row: i32) -> i64 {
        let a = self.artist[row as usize];
        if a >= 0 {
            a as i64
        } else {
            -(row as i64) - 2
        }
    }

    /// Spotify's anti-clumping, as 1D error diffusion (Floyd-Steinberg on a
    /// line): each artist is owed slots at the rate n_a / L, starting from a
    /// random offset; every slot goes to an artist the error says is due, and
    /// among the due artists' songs the one nearest the previous track wins,
    /// which is the greedy nearest-neighbour chain that keeps transitions smooth.
    fn dither_and_chain(&self, prefix_rows: &[i32], picked: &[i32], seed: u64) -> Vec<i32> {
        let length = picked.len();
        if length == 0 {
            return Vec::new();
        }
        let mut songs: BTreeMap<i64, Vec<i32>> = BTreeMap::new();
        for &r in picked {
            songs.entry(self.artist_key(r)).or_default().push(r);
        }
        let mut error: BTreeMap<i64, f64> = BTreeMap::new();
        let mut density: BTreeMap<i64, f64> = BTreeMap::new();
        let mut rng = Rng::new(seed ^ 0xD1B5_4A32_D192_ED03);
        for (&artist, rows) in &songs {
            let d = rows.len() as f64 / length as f64;
            density.insert(artist, d);
            error.insert(artist, rng.uniform() * d);
        }
        let mut last_position: BTreeMap<i64, i32> = BTreeMap::new();
        for (i, &r) in prefix_rows.iter().enumerate() {
            last_position.insert(self.artist_key(r), i as i32);
        }
        let spacing = 1.max(self.weights.min_artist_spacing);

        let mut order = Vec::with_capacity(length);
        let mut previous = prefix_rows.last().copied().unwrap_or(-1);
        for slot in 0..length {
            let position = (prefix_rows.len() + slot) as i32;
            let mut remaining = Vec::new();
            for (&artist, rows) in &songs {
                if rows.is_empty() {
                    continue;
                }
                *error.get_mut(&artist).expect("every artist has an error") += density[&artist];
                remaining.push(artist);
            }
            let mut eligible: Vec<i64> = remaining
                .iter()
                .copied()
                .filter(|a| last_position.get(a).is_none_or(|&last| position - last >= spacing))
                .collect();
            if eligible.is_empty() {
                eligible = remaining;
            }
            let max_error = eligible.iter().fold(-1e9f64, |m, a| m.max(error[a]));

            let mut best_row = -1;
            let mut best_artist = 0i64;
            let mut best_cost = f32::MAX;
            for &artist in &eligible {
                if error[&artist] < max_error - 0.35 {
                    continue;
                }
                for &r in &songs[&artist] {
                    let cost = if previous >= 0 { distance(self.vec(previous), self.vec(r)) } else { 0.0 };
                    if cost < best_cost {
                        best_cost = cost;
                        best_row = r;
                        best_artist = artist;
                    }
                }
            }
            if best_row < 0 {
                break;
            }
            let rows = songs.get_mut(&best_artist).expect("the chosen artist");
            let at = rows.iter().position(|&r| r == best_row).expect("the chosen row");
            rows.remove(at);
            *error.get_mut(&best_artist).expect("the chosen artist") -= 1.0;
            last_position.insert(best_artist, position);
            order.push(best_row);
            previous = best_row;
        }
        order
    }

    /// What may not be picked: `blocked` (the queue), and also `exclude` and
    /// this session's plays when enough songs remain for `count`.
    pub(crate) fn blocked_rows(&self, mut blocked: Vec<bool>, exclude: &[i64], count: usize) -> Vec<bool> {
        let mut strict = blocked.clone();
        for &id in exclude {
            let r = self.row_of(id);
            if r >= 0 {
                strict[r as usize] = true;
            }
        }
        for &id in &self.session_played {
            let r = self.row_of(id);
            if r >= 0 {
                strict[r as usize] = true;
            }
        }
        let open = strict.iter().filter(|b| !**b).count();
        // A small library runs out of unheard songs: then heard ones come back
        // (still behind the cooldown) rather than the queue running dry.
        if open >= count {
            blocked = strict;
        }
        blocked
    }

    pub(crate) fn begin_generation(&mut self) {
        self.generation += 1;
        self.audit = BrainAuditReport { generation: self.generation, ..Default::default() };
        self.traces.clear();
        self.candidate_by_row.clear();
    }

    /// Builds up to `count` (at most 64) tracks to follow `current_id` and the
    /// already queued `prefix`. `exclude` lists ids that must not be picked
    /// (recently played, already queued). After a fast skip, `replaced_tail`
    /// lists what was queued before the re-rank, for Check 2. Returns the
    /// queued ids; last_traces() and last_audit() describe them.
    #[allow(clippy::too_many_arguments)]
    pub fn generate_queue(
        &mut self,
        current_id: i64,
        prefix: &[i64],
        exclude: &[i64],
        replaced_tail: &[i64],
        count: i32,
        now_ms: i64,
        seed: u64,
    ) -> Vec<i64> {
        let start = Instant::now();
        self.begin_generation();
        let count = count.clamp(0, 64) as usize;

        let mut draft = Draft::new();
        let current_row = self.row_of(current_id);
        if current_row >= 0 {
            draft.prefix_rows.push(current_row);
        }
        draft.prefix_rows.extend(prefix.iter().map(|&id| self.row_of(id)).filter(|&r| r >= 0));
        draft.replaced_tail = replaced_tail.to_vec();

        // Check 4 runs first: the session vector must be sound before anything is scored by it.
        self.preflight(current_row);

        if self.size() == 0 || count == 0 {
            self.run_brain_self_audit(&mut draft, false);
            self.pending_skip.active = false;
            self.audit.elapsed_ms = start.elapsed().as_secs_f64() * 1000.0;
            return Vec::new();
        }

        let mut queue_rows = vec![false; self.size()];
        for &r in &draft.prefix_rows {
            queue_rows[r as usize] = true;
        }
        let blocked = self.blocked_rows(queue_rows, exclude, count);

        let target: Option<[f32; DIMS]> = if self.has_session {
            Some(self.session)
        } else if current_row >= 0 {
            Some(self.vec(current_row).try_into().expect("24 floats"))
        } else {
            None
        };
        let target = target.as_ref().map(|t| &t[..]);
        let anchor_id = draft.prefix_rows.last().map_or(current_id, |&r| self.ids[r as usize]);

        let mut echo_retry = false;
        for attempt in 0..2u64 {
            let mut scored = self.score_library(target, anchor_id, now_ms, &blocked);
            if scored.is_empty() {
                break;
            }
            let pool_size = scored.len().min(24.max(128.min(count * 6)));
            // The pool, then up to 96 more by score: the next best, for repairs
            // that need a different kind of song. (Best first, ties in library
            // order, as a stable sort of `scored` would list them.)
            let extra = (scored.len() - pool_size).min(96);
            let mut best = scored.clone();
            top_k_by(&mut best, pool_size + extra, |a, b| desc(a.score, b.score));
            let pool = &best[..pool_size];
            for c in pool {
                self.candidate_by_row.insert(c.row, *c);
            }

            let attempt_seed = seed.wrapping_add(0x632B_E59B_D9B4_E019u64.wrapping_mul(attempt));
            let sampled = self.reservoir_sample(pool, attempt_seed);
            let picked = &sampled[..count.min(sampled.len())];

            draft.placed_rows = self.dither_and_chain(&draft.prefix_rows, picked, attempt_seed);
            draft.reserve_rows = sampled[picked.len()..].to_vec();
            for c in &best[pool_size..] {
                draft.reserve_rows.push(c.row);
                self.candidate_by_row.insert(c.row, *c);
            }
            // Fresh blood for the echo-chamber repair: the most explorable songs
            // that do not sound like the session (ties: the better scored first,
            // then library order, as stable sorts by score and then by this would).
            scored.retain(|c| target.is_none_or(|t| dot(t, self.vec(c.row)) < 0.9));
            top_k_by(&mut scored, 32, |a, b| {
                desc(a.explore * a.cooldown, b.explore * b.cooldown).then(desc(a.score, b.score))
            });
            draft.fresh_rows.clear();
            for c in &scored {
                draft.fresh_rows.push(c.row);
                self.candidate_by_row.insert(c.row, *c);
            }

            if self.run_brain_self_audit(&mut draft, echo_retry) {
                break;
            }
            echo_retry = true; // the echo chamber check raised w_explore: score again
        }

        self.build_traces(&draft, false, 0.0);
        self.pending_skip.active = false;
        self.audit.explore_boost = self.explore_boost;
        self.audit.elapsed_ms = start.elapsed().as_secs_f64() * 1000.0;
        draft.placed_rows.iter().map(|&r| self.ids[r as usize]).collect()
    }

    /// Sonic Adventure: the next song on a straight line through the vector
    /// space from `from_id` to `to_id`, at position `t` in [0, 1] of the way,
    /// following `previous_id`. Returns the id, or None.
    #[allow(clippy::too_many_arguments)]
    pub fn journey_step(
        &mut self,
        from_id: i64,
        to_id: i64,
        t: f32,
        previous_id: i64,
        exclude: &[i64],
        now_ms: i64,
        seed: u64,
    ) -> Option<i64> {
        // Plexamp-style: the target slides along the straight line from one
        // song to the other; each step plays a real song near the target that
        // also sits close to the one before it.
        let start = Instant::now();
        self.begin_generation();
        let previous_row = self.row_of(previous_id);
        self.preflight(previous_row);
        let from_row = self.row_of(from_id);
        let to_row = self.row_of(to_id);
        if from_row < 0 || to_row < 0 || self.size() == 0 {
            return None;
        }

        let position = if t.is_finite() { 1.0f32.min(0.0f32.max(t)) } else { 1.0 };
        let mut target = [0.0f32; DIMS];
        for d in 0..DIMS {
            target[d] = (1.0 - position) * self.vec(from_row)[d] + position * self.vec(to_row)[d];
        }
        if !normalize(&mut target) {
            target.copy_from_slice(self.vec(to_row));
        }

        let mut blocked = vec![false; self.size()];
        if previous_row >= 0 {
            blocked[previous_row as usize] = true;
        }
        for &id in exclude {
            let r = self.row_of(id);
            if r >= 0 {
                blocked[r as usize] = true;
            }
        }
        for &id in &self.session_played {
            let r = self.row_of(id);
            if r >= 0 && r != to_row {
                blocked[r as usize] = true;
            }
        }

        let saved = self.weights;
        self.weights.similarity = 2.0; // the line is the point: similarity to it dominates
        self.weights.markov = 0.0;
        self.weights.explore = 0.05;
        let mut scored = self.score_library(Some(&target), previous_id, now_ms, &blocked);
        self.weights = saved;
        if scored.is_empty() {
            return None;
        }
        for c in scored.iter_mut() {
            // Smoothness from the previous song, folded into the vibe term.
            let smooth =
                if previous_row >= 0 { 1.0 - 0.5 * distance(self.vec(previous_row), self.vec(c.row)) } else { 1.0 };
            c.vibe += 0.35 * smooth;
            c.score = (c.vibe + c.markov + c.affinity + c.explore) * c.cooldown;
            self.candidate_by_row.insert(c.row, *c);
        }
        top_k_by(&mut scored, 12, |a, b| desc(a.score, b.score));
        self.weights.temperature = 0.05;
        let sampled = self.reservoir_sample(&scored, seed);
        self.weights = saved;

        let mut draft = Draft::new();
        if previous_row >= 0 {
            draft.prefix_rows.push(previous_row);
        }
        draft.placed_rows.push(sampled[0]);
        draft.reserve_rows = sampled[1..].to_vec();
        draft.allow_reorder = false;
        self.run_brain_self_audit(&mut draft, true);
        self.build_traces(&draft, true, position);
        self.audit.explore_boost = self.explore_boost;
        self.audit.elapsed_ms = start.elapsed().as_secs_f64() * 1000.0;
        draft.placed_rows.first().map(|&r| self.ids[r as usize])
    }

    fn build_traces(&mut self, draft: &Draft, journey: bool, journey_t: f32) {
        self.traces.clear();
        let context = self.context_zone();
        for (j, &row) in draft.placed_rows.iter().enumerate() {
            let r = row as usize;
            let c = match self.candidate_by_row.get(&row) {
                Some(c) => *c,
                None => {
                    let session = self.session;
                    self.score_row(row, self.has_session.then_some(&session[..]), context, 0.0, 0)
                }
            };
            let total = EPS.max(c.vibe + c.markov + c.affinity + c.explore);
            let previous =
                if j == 0 { draft.prefix_rows.last().copied().unwrap_or(-1) } else { draft.placed_rows[j - 1] };
            let mut t = TrackDecisionTrace {
                track_id: self.ids[r],
                raw_score: c.score,
                vibe_similarity_pct: 100.0 * c.vibe / total,
                markov_habit_pct: 100.0 * c.markov / total,
                user_affinity_pct: 100.0 * c.affinity / total,
                exploration_bonus_pct: 100.0 * c.explore / total,
                recency_penalty_multiplier: c.cooldown,
                cosine_similarity: c.cosine,
                zone: self.zone[r] as i32,
                quality: self.quality[r],
                transition_smoothness_score: if previous >= 0 {
                    clamp01(1.0 - 0.5 * distance(self.vec(previous), self.vec(row)))
                } else {
                    1.0
                },
                ..Default::default()
            };

            let (reason, text) = if self.quality[r] == VectorQuality::None {
                (ReasonTag::ColdStart, "Cold-Start Wildcard".to_string())
            } else if journey {
                (
                    ReasonTag::SonicAdventure,
                    format!("Sonic Adventure {}% of the way", (journey_t * 100.0).round() as i64),
                )
            } else if self.pending_skip.active && dot(self.vec(row), &self.pending_skip.vector) < DISTINCT_COSINE {
                (
                    ReasonTag::PostSkipPivot,
                    format!("Post-Skip Pivot (away from {})", zone_word(zone_of(&self.pending_skip.vector))),
                )
            } else {
                // std::max_element: the first of the largest.
                let parts = [c.vibe, c.markov, c.affinity, c.explore];
                let top = (1..4).fold(0, |best, i| if parts[best] < parts[i] { i } else { best });
                match top {
                    1 => (ReasonTag::MarkovFavorite, "Markov Favorite Transition".to_string()),
                    2 => (ReasonTag::ProvenFavorite, "Proven Favorite".to_string()),
                    3 => (ReasonTag::BanditGem, "Bandit Rediscovery Gem".to_string()),
                    _ => (
                        ReasonTag::VibeMatch,
                        format!(
                            "{}% {}/Vibe Match",
                            (50.0f32 * (c.cosine + 1.0)).round() as i64,
                            zone_word(self.zone[r] as i32)
                        ),
                    ),
                }
            };
            t.reason = reason;
            t.primary_reason_tag = text;
            self.traces.push(t);
        }
    }
}
