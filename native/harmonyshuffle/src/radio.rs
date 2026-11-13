//! Part 3b: Mix from this song ("Mix din piesa asta"), an on-device radio.
//!
//! After YouTube Music's three stages (retrieval, ranking, filtering), with the
//! pieces a phone can run on its own library: the 24-D vectors stand in for
//! learned track embeddings, the local Markov habits for co-listening, and a
//! small attention over this session's feedback for the transformer that weighs
//! each action by how relevant its context is to the current one.

use std::collections::HashMap;
use std::time::Instant;

use crate::engine::{clamp01, distance, dot, sort_desc_by, top_k_desc_by, Candidate, Draft, SmartShuffleEngine, EPS};
use crate::types::*;

/// Stage 1 list sizes (before the union).
const RADIO_FROM_SEED: usize = 80;
const RADIO_FROM_SESSION: usize = 40;
const RADIO_FROM_HABITS: usize = 40;
const RADIO_FROM_RARE: usize = 24;
/// Songs like the seed whose habits count, and how alike they must be.
const RADIO_HABIT_NEIGHBOURS: usize = 12;
const RADIO_NEIGHBOUR_COSINE: f32 = 0.5;
/// "Rarely heard": this many plays or fewer (finished or skipped).
const RADIO_RARE_PLAYS: i32 = 2;
/// A rarely heard song must sound at least this right to be a candidate.
const RADIO_RARE_COSINE: f32 = 0.40;
/// Long-term history this large (plays across the library) is fully trusted.
const RADIO_TRUSTED_PLAYS: f32 = 40.0;
/// Session feedback this long is fully trusted.
const RADIO_TRUSTED_EVENTS: f32 = 4.0;
/// Ordering: the cost of each unit of distance from the song before (a tie-break, not a filter).
const STEP_COST: f32 = 0.05;
/// A co-listening score this high lets a song past the new-song gate.
const HABIT_VOUCHES: f32 = 0.3;
/// Width of the feedback likeness kernel exp((cos - 1) / width).
const FEEDBACK_KERNEL: f32 = 0.15;
/// Feedback mass that counts as much as "no opinion" (shrinks thin evidence toward 0).
const FEEDBACK_PRIOR: f32 = 0.25;

const FROM_SEED: u8 = 1;
const FROM_SESSION: u8 = 2;
const FROM_HABITS: u8 = 4;
const FROM_RARE: u8 = 8;

/// Rows of the `k` largest `key` among rows where `keep` holds, best first.
fn top_rows(n: usize, k: usize, key: impl Fn(usize) -> f32, keep: impl Fn(usize) -> bool) -> Vec<i32> {
    let mut rows: Vec<usize> = (0..n).filter(|&r| keep(r)).collect();
    top_k_desc_by(&mut rows, k, |&r| key(r));
    rows.into_iter().map(|r| r as i32).collect()
}

/// Stage 3's bookkeeping while the batch is placed.
struct Placing {
    used: Vec<bool>,
    new_placed: i32,
    last_was_new: bool,
    previous: i32,
    last_position: HashMap<i64, i32>,
    artist_uses: HashMap<i64, i32>,
    exploring: bool,
}

impl Placing {
    fn place(&mut self, e: &SmartShuffleEngine, draft: &mut Draft, by_row: &HashMap<i32, RadioCandidate>, r: i32) {
        let position = (draft.prefix_rows.len() + draft.placed_rows.len()) as i32;
        draft.placed_rows.push(r);
        self.used[r as usize] = true;
        self.last_position.insert(e.artist_key(r), position);
        *self.artist_uses.entry(e.artist_key(r)).or_insert(0) += 1;
        let is_new = self.exploring && by_row[&r].is_new;
        self.new_placed += is_new as i32;
        self.last_was_new = is_new;
        self.previous = r;
    }
}

#[derive(Clone, Copy, Default)]
struct RadioCandidate {
    row: i32,
    relevance: f32,  // 0..1, cosine with the seed/S_t blend (negative counts as 0)
    transition: f32, // 0..1
    habit: f32,      // 0..1, part of transition
    preference: f32, // 0..1
    feedback: f32,   // -1..1, this session's
    novelty: f32,    // 0..1
    score: f32,
    is_new: bool, // never played nor skipped
    gated: bool,  // new, and below the relevance gate
}

impl SmartShuffleEngine {
    /**
     * "Mix din piesa asta": up to `count` (at most 64) songs for a radio
     * started from `seed_id` (the session's landmark for as long as the mix
     * runs), to follow `current_id` and the already queued `prefix`. Local
     * library only, in three stages after YouTube Music's (see SMART-SHUFFLE.md):
     *
     *  1. candidates: close to the seed, close to S_t, what followed songs
     *     like the seed or the anchor in the local history, and rarely heard
     *     songs that sound right; merged, duplicates removed;
     *  2. ranking: RadioWeights over relevance to the seed, fit after the
     *     song before, learned preference (session feedback first, weighted
     *     by how similar its context was) and novelty;
     *  3. filtering and order: queued and excluded songs out, artists spaced,
     *     songs new to the listener only above an acoustic relevance gate and
     *     at most a quarter of a batch.
     *
     * last_traces() and last_audit() describe the batch; last_radio() its stages.
     */
    #[allow(clippy::too_many_arguments)]
    pub fn generate_radio(
        &mut self,
        seed_id: i64,
        current_id: i64,
        prefix: &[i64],
        exclude: &[i64],
        count: i32,
        now_ms: i64,
        seed: u64,
    ) -> Vec<i64> {
        let start = Instant::now();
        self.begin_generation();
        self.radio = RadioReport { generation: self.generation, ..Default::default() };
        let count = count.clamp(0, 64) as usize;
        let w = self.radio_weights;

        let mut draft = Draft::new();
        let current_row = self.row_of(current_id);
        if current_row >= 0 {
            draft.prefix_rows.push(current_row);
        }
        draft.prefix_rows.extend(prefix.iter().map(|&id| self.row_of(id)).filter(|&r| r >= 0));
        self.preflight(current_row);
        let finish = |e: &mut Self| {
            e.audit.explore_boost = e.explore_boost;
            e.radio.elapsed_ms = start.elapsed().as_secs_f64() * 1000.0;
            e.audit.elapsed_ms = e.radio.elapsed_ms;
        };
        let n = self.size();
        if n == 0 || count == 0 {
            self.run_brain_self_audit(&mut draft, true);
            finish(self);
            return Vec::new();
        }

        // What may not be picked: the queue, the excluded, what this session played
        // (unless the library is too small to keep going without them).
        let seed_row = self.row_of(seed_id);
        let mut queue_rows = vec![false; n];
        for &r in &draft.prefix_rows {
            queue_rows[r as usize] = true;
        }
        if seed_row >= 0 {
            queue_rows[seed_row as usize] = true;
        }
        let blocked = self.blocked_rows(queue_rows, exclude, count);

        // ---- Stage 1: candidates ------------------------------------------------
        let seed_measured = seed_row >= 0 && self.quality[seed_row as usize] != VectorQuality::None;
        self.radio.seed_measured = seed_measured;
        let seed_vec: Option<[f32; DIMS]> = seed_measured.then(|| self.vec(seed_row).try_into().expect("24 floats"));
        let session_vec: Option<[f32; DIMS]> = self.has_session.then_some(self.session);
        let anchor_row = draft.prefix_rows.last().copied().unwrap_or(seed_row);
        let mut to_seed = vec![0.0f32; n];
        let mut to_session = vec![0.0f32; n];
        let mut total_plays = 0.0f32;
        for r in 0..n {
            total_plays += (self.completed[r] + self.skipped[r]) as f32;
            if self.quality[r] == VectorQuality::None {
                continue; // no acoustic evidence either way
            }
            let trust = if self.quality[r] == VectorQuality::Fallback { 0.85 } else { 1.0 };
            if let Some(s) = &seed_vec {
                to_seed[r] = trust * dot(s, self.vec(r as i32));
            }
            if let Some(s) = &session_vec {
                to_session[r] = trust * dot(s, self.vec(r as i32));
            }
        }
        // Relevance: the seed stays the landmark; S_t (what the session has become) adds to it.
        let relevance_of = |r: usize| {
            let x = match (&seed_vec, &session_vec) {
                (Some(_), Some(_)) => w.seed_share * to_seed[r] + (1.0 - w.seed_share) * to_session[r],
                (Some(_), None) => to_seed[r],
                _ => to_session[r],
            };
            clamp01(x)
        };
        let measured = |r: usize| !blocked[r] && self.quality[r] != VectorQuality::None;

        let mut sources = vec![0u8; n];
        let mut mark = |rows: &[i32], bit: u8| {
            for &r in rows {
                sources[r as usize] |= bit;
            }
            rows.len() as i32
        };
        if seed_vec.is_some() {
            self.radio.from_seed = mark(&top_rows(n, RADIO_FROM_SEED, |r| to_seed[r], measured), FROM_SEED);
        }
        if session_vec.is_some() {
            self.radio.from_session = mark(&top_rows(n, RADIO_FROM_SESSION, |r| to_session[r], measured), FROM_SESSION);
        }

        // Co-listening: what the listener played after songs like the seed (and
        // after the song the batch follows). Noisy-OR over the sources, each
        // weighted by how alike it is: 1 - prod(1 - w_n P(n -> c)).
        let mut habit_sources: Vec<(i32, f32)> = Vec::new();
        if seed_row >= 0 {
            habit_sources.push((seed_row, 1.0));
        }
        if anchor_row >= 0 && anchor_row != seed_row {
            habit_sources.push((anchor_row, 1.0));
        }
        if let Some(s) = &seed_vec {
            let near = top_rows(
                n,
                RADIO_HABIT_NEIGHBOURS + 1,
                |r| dot(s, self.vec(r as i32)),
                |r| r as i32 != seed_row && self.quality[r] != VectorQuality::None,
            );
            for r in near {
                let c = dot(s, self.vec(r));
                if c >= RADIO_NEIGHBOUR_COSINE && r != anchor_row {
                    habit_sources.push((r, c));
                }
            }
        }
        // row -> prod(1 - w P), in first-seen order (the C++ used a hash map;
        // its iteration order only ever decided ties).
        let mut not_habit: Vec<(i32, f32)> = Vec::new();
        let mut not_habit_at: HashMap<i32, usize> = HashMap::new();
        for &(from, weight) in &habit_sources {
            let Some(out) = self.transitions.get(&self.ids[from as usize]) else { continue };
            let max_weight = out.values().fold(0.0f32, |m, &x| m.max(x));
            if max_weight <= 0.0 {
                continue;
            }
            let t = self.transition_totals.get(&self.ids[from as usize]).copied().unwrap_or(0.0);
            let shrink = t / (t + 3.0);
            // Destinations in id order, so the result never depends on hashing.
            let mut destinations: Vec<(i64, f32)> = out.iter().map(|(&to, &x)| (to, x)).collect();
            destinations.sort_by_key(|&(to, _)| to);
            for (to, x) in destinations {
                let r = self.row_of(to);
                if r < 0 || blocked[r as usize] {
                    continue;
                }
                let p = clamp01(weight * clamp01(x / max_weight * shrink));
                let at = *not_habit_at.entry(r).or_insert_with(|| {
                    not_habit.push((r, 1.0));
                    not_habit.len() - 1
                });
                not_habit[at].1 *= 1.0 - p;
            }
        }
        let mut habit = vec![0.0f32; n];
        for &(r, rest) in &not_habit {
            habit[r as usize] = clamp01(1.0 - rest);
        }
        {
            let mut rows: Vec<i32> = not_habit.iter().map(|&(r, _)| r).collect();
            rows.sort();
            top_k_desc_by(&mut rows, RADIO_FROM_HABITS, |&r| habit[r as usize]);
            self.radio.from_habits = mark(&rows, FROM_HABITS);
        }

        // Rarely heard songs that sound right (Breaking the Loop: what the
        // listener already plays would otherwise take every slot).
        let rare = top_rows(n, RADIO_FROM_RARE, relevance_of, |r| {
            measured(r)
                && self.completed[r] + self.skipped[r] <= RADIO_RARE_PLAYS
                && relevance_of(r) >= RADIO_RARE_COSINE
        });
        self.radio.from_rare = mark(&rare, FROM_RARE);

        // A library with nothing measured and no habits (or a cold seed) still gets a mix.
        let mut merged: Vec<i32> = (0..n).filter(|&r| sources[r] != 0).map(|r| r as i32).collect();
        if merged.len() < count {
            let want = (count * 4).max(16);
            for r in 0..n {
                if merged.len() >= want {
                    break;
                }
                if !blocked[r] && sources[r] == 0 {
                    merged.push(r as i32);
                }
            }
        }
        self.radio.merged = merged.len() as i32;

        // ---- Stage 2: contextual ranking ------------------------------------------
        // How much to trust what was learned: little history means a mix by sound.
        self.radio.history_confidence = clamp01(total_plays / RADIO_TRUSTED_PLAYS);
        self.radio.session_confidence = clamp01(self.feedback.len() as f32 / RADIO_TRUSTED_EVENTS);
        let history_confidence = self.radio.history_confidence;
        let learned = history_confidence.max(self.radio.session_confidence);
        let w_preference = w.preference * learned;
        let w_habit = 0.6 * history_confidence; // of the transition head
        let w_relevance = w.relevance + (w.preference - w_preference);
        let exploring = history_confidence >= 0.25; // "new" means something only once there is history

        // Attention over this session's feedback: newer events count more, and an
        // event counts more when it happened after a song like the one this batch
        // follows (the same context).
        let context_now: Option<[f32; DIMS]> =
            if anchor_row >= 0 && self.quality[anchor_row as usize] != VectorQuality::None {
                Some(self.vec(anchor_row).try_into().expect("24 floats"))
            } else {
                seed_vec
            };
        let mut attention = vec![0.0f32; self.feedback.len()];
        {
            let mut recency = 1.0f32;
            for k in (0..self.feedback.len()).rev() {
                let e = &self.feedback[k];
                let mut alike = 0.5f32;
                if let (true, Some(c)) = (e.has_context, &context_now) {
                    alike = clamp01(dot(c, &e.context));
                }
                attention[k] = recency * (0.35 + 0.65 * alike);
                recency *= w.feedback_decay;
            }
        }

        let mut ranked: Vec<RadioCandidate> = Vec::with_capacity(merged.len());
        for &row in &merged {
            let r = row as usize;
            let mut c = RadioCandidate { row, ..Default::default() };
            let known = self.quality[r] != VectorQuality::None;
            let acoustic = if known { relevance_of(r) } else { 0.0 };
            c.habit = habit[r];
            // Co-listening as evidence of belonging: what the listener plays after
            // songs like the seed belongs in its radio even when it sounds different.
            c.relevance = acoustic.max(w.habit_vouch * history_confidence * c.habit);
            let smooth = match (&context_now, known) {
                (Some(ctx), true) => clamp01(dot(ctx, self.vec(row))),
                _ => acoustic,
            };
            c.transition = clamp01((1.0 - w_habit) * smooth + w_habit * c.habit);

            // Session feedback: sum of attention x likeness x signal, skips heavier.
            let (mut mass, mut sum) = (0.0f32, 0.0f32);
            if known {
                for (k, e) in self.feedback.iter().enumerate() {
                    // How much this event is about this song: a sharp kernel, so a
                    // near twin of a skipped song takes the penalty and a merely
                    // related one mostly does not (cos 1 -> 1, 0.7 -> 0.14, 0.5 -> 0.04).
                    let like = ((dot(self.vec(row), &e.item) - 1.0) / FEEDBACK_KERNEL).exp();
                    let m = attention[k] * like;
                    let s = e.signal;
                    sum += m * s * if s < 0.0 { w.skip_weight } else { 1.0 };
                    mass += m;
                }
            }
            c.feedback = (-1.0f32).max(1.0f32.min(sum / (mass + FEEDBACK_PRIOR)));
            // The long-term record: a Laplace completion rate, favourites a little higher.
            let comp = self.completed[r] as f32;
            let skip = self.skipped[r] as f32;
            let mut long_term = (comp + 1.0) / (comp + 1.5 * skip + 2.0);
            if self.favorite[r] != 0 {
                long_term = 1.0f32.min(long_term + 0.15);
            }
            let session_share = mass / (mass + FEEDBACK_PRIOR);
            c.preference = clamp01(session_share * (0.5 + 0.5 * c.feedback) + (1.0 - session_share) * long_term);

            c.is_new = self.completed[r] + self.skipped[r] == 0;
            c.novelty = 1.0 / (1.0 + comp + skip).sqrt();
            // New to the listener: in only above the gate, unless their own habits vouch for it.
            c.gated = exploring && c.is_new && (!known || acoustic < w.new_song_gate) && c.habit < HABIT_VOUCHES;
            let novelty_head = if exploring && !c.gated { c.novelty } else { 0.0 };

            let trust = if known { 1.0 } else { 0.5 };
            let cooldown = self.cooldown_of(r, now_ms);
            c.score = (w_relevance * c.relevance
                + w.transition * c.transition
                + w_preference * c.preference
                + w.novelty * novelty_head)
                * trust
                * cooldown
                * (w.skip_penalty * 0.0f32.min(c.feedback)).exp();
            if !c.score.is_finite() {
                c.score = 0.0;
            }
            ranked.push(c);

            // What the traces and the brain read. Relevance the listener's habit
            // vouched for counts as habit (so the brain's Check 5 keeps a habitual
            // jump, as it does for Smart Shuffle).
            self.candidate_by_row.insert(
                row,
                Candidate {
                    row,
                    score: c.score,
                    vibe: w_relevance * acoustic,
                    markov: w.transition * c.transition + w_relevance * (c.relevance - acoustic),
                    affinity: w_preference * c.preference,
                    explore: w.novelty * novelty_head,
                    cooldown,
                    cosine: c.relevance,
                },
            );
        }
        sort_desc_by(&mut ranked, |c| c.score);

        // ---- Stage 3: filtering and order ----------------------------------------
        let passing: Vec<i32> = ranked.iter().filter(|c| !c.gated).map(|c| c.row).collect();
        let gated: Vec<i32> = ranked.iter().filter(|c| c.gated).map(|c| c.row).collect();
        self.radio.gated_out = gated.len() as i32;
        let by_row: HashMap<i32, RadioCandidate> = ranked.iter().map(|c| (c.row, *c)).collect();

        // Weighted sampling of the pool, so one seed does not give the same mix every time.
        let pool_size = passing.len().min((count * 4).max(16));
        let pool: Vec<Candidate> = passing[..pool_size].iter().map(|r| self.candidate_by_row[r]).collect();
        let saved = self.weights;
        self.weights.temperature = w.temperature;
        let mut order = self.reservoir_sample(&pool, seed);
        self.weights = saved;
        order.extend_from_slice(&passing[pool_size..]);

        let mut placing = Placing {
            used: vec![false; n],
            new_placed: 0,
            last_was_new: false,
            previous: draft.prefix_rows.last().copied().unwrap_or(-1),
            last_position: HashMap::new(),
            artist_uses: HashMap::new(),
            exploring,
        };
        for (i, &r) in draft.prefix_rows.iter().enumerate() {
            placing.last_position.insert(self.artist_key(r), i as i32);
        }
        let spacing = 1.max(self.weights.min_artist_spacing);
        // The exploration cap: a quarter of the batch, or more when the songs that
        // sound most like the seed are mostly unheard (a seed the listener chose in
        // a part of the library they have not explored is no reason for a mix of
        // their old favourites).
        let near_new = {
            let mut near: Vec<&RadioCandidate> = ranked.iter().collect();
            top_k_desc_by(&mut near, 2 * count, |c| c.relevance);
            let k = near.len();
            let fresh = near.iter().filter(|c| c.is_new).count();
            if k > 0 {
                fresh as f32 / k as f32
            } else {
                0.0
            }
        };
        let new_share = w.new_song_share.max(near_new);
        let new_cap = 1.max((new_share * count as f32).round() as i32);
        self.radio.new_cap = new_cap;
        // Strict first (spacing and the exploration cap), then each is relaxed.
        let mut pass = 0;
        while pass < 3 && draft.placed_rows.len() < count {
            while draft.placed_rows.len() < count {
                let position = (draft.prefix_rows.len() + draft.placed_rows.len()) as i32;
                let mut best = -1i32;
                let mut best_value = -f32::MAX;
                let mut looked = 0;
                for &r in &order {
                    if looked >= 3 {
                        break;
                    }
                    if placing.used[r as usize] {
                        continue;
                    }
                    let key = self.artist_key(r);
                    if pass < 2 && placing.last_position.get(&key).is_some_and(|&last| position - last < spacing) {
                        continue;
                    }
                    let is_new = exploring && by_row[&r].is_new;
                    if pass < 1
                        && is_new
                        && (placing.new_placed >= new_cap || (placing.last_was_new && new_share < 0.5))
                    {
                        continue;
                    }
                    // Of the next three that fit: the score (fit after the batch's
                    // anchor is already in it), a light preference for a smooth
                    // step from the song before, and for an artist new to the batch.
                    let uses = placing.artist_uses.get(&key).copied().unwrap_or(0);
                    let previous = placing.previous;
                    let step = if previous >= 0 { distance(self.vec(previous), self.vec(r)) } else { 0.0 };
                    let value = by_row[&r].score - STEP_COST * step - w.artist_repeat_cost * uses as f32;
                    looked += 1;
                    if value > best_value {
                        best_value = value;
                        best = r;
                    }
                }
                if best < 0 {
                    break;
                }
                placing.place(self, &mut draft, &by_row, best);
            }
            pass += 1;
        }
        // A tiny library: the gated songs, best first, rather than an empty slot.
        for &r in &gated {
            if draft.placed_rows.len() >= count {
                break;
            }
            if placing.used[r as usize] {
                continue;
            }
            placing.place(self, &mut draft, &by_row, r);
            self.radio.filled += 1;
        }
        self.radio.new_placed = placing.new_placed;
        let used = placing.used;

        for &r in &order {
            if !used[r as usize] {
                draft.reserve_rows.push(r);
            }
        }
        for &r in &passing {
            if !used[r as usize] && by_row[&r].is_new && draft.fresh_rows.len() < 16 {
                draft.fresh_rows.push(r);
            }
        }
        // The same safety net as Smart Shuffle (integrity, clumping, jerk), but no
        // exploration boost: the brain repairs only from the mix's own candidates.
        self.run_brain_self_audit(&mut draft, true);

        // Traces.
        for (j, &row) in draft.placed_rows.iter().enumerate() {
            let r = row as usize;
            let c = self.candidate_by_row.get(&row).copied().unwrap_or_default();
            let rc = by_row.get(&row);
            let total = EPS.max(c.vibe + c.markov + c.affinity + c.explore);
            let cosine = if seed_vec.is_some() { to_seed[r] } else { c.cosine };
            let before =
                if j == 0 { draft.prefix_rows.last().copied().unwrap_or(-1) } else { draft.placed_rows[j - 1] };
            let like = (100.0f32 * clamp01(cosine)).round() as i64;
            let (reason, text) = if self.quality[r] == VectorQuality::None {
                (ReasonTag::ColdStart, "Mix: not measured yet".to_string())
            } else if rc.is_some_and(|c| c.habit >= HABIT_VOUCHES) {
                (ReasonTag::MarkovFavorite, "Mix: often played after songs like these".to_string())
            } else if rc.is_some_and(|c| c.gated) {
                (ReasonTag::BanditGem, format!("Mix: filling in, {like}% like the seed"))
            } else if rc.is_some_and(|c| exploring && c.is_new) {
                (ReasonTag::BanditGem, format!("Mix: new to you, {like}% like the seed"))
            } else if rc.is_some_and(|c| c.feedback > 0.2) && self.feedback.len() >= 2 {
                (ReasonTag::ProvenFavorite, "Mix: close to what you liked this session".to_string())
            } else {
                (ReasonTag::VibeMatch, format!("Mix: {like}% like the seed"))
            };
            self.traces.push(TrackDecisionTrace {
                track_id: self.ids[r],
                raw_score: c.score,
                vibe_similarity_pct: 100.0 * c.vibe / total,
                markov_habit_pct: 100.0 * c.markov / total,
                user_affinity_pct: 100.0 * c.affinity / total,
                exploration_bonus_pct: 100.0 * c.explore / total,
                recency_penalty_multiplier: c.cooldown,
                cosine_similarity: cosine,
                zone: self.zone[r] as i32,
                quality: self.quality[r],
                transition_smoothness_score: if before >= 0 {
                    clamp01(1.0 - 0.5 * distance(self.vec(before), self.vec(row)))
                } else {
                    1.0
                },
                reason,
                primary_reason_tag: text,
            });
        }
        self.pending_skip.active = false;
        finish(self);
        draft.placed_rows.iter().map(|&r| self.ids[r as usize]).collect()
    }
}
