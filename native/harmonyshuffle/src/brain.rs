//! The ShuffleDiagnosticBrain: explains every queued track, checks five
//! invariants after every generation and repairs the queue when one fails.
//! It reads the engine's state and repairs the draft queue in place.

use std::collections::{BTreeMap, HashMap, HashSet};

use crate::engine::{
    distance, dot, entropy_bits, normalize, sort_desc_by, Draft, SmartShuffleEngine, DISTINCT_COSINE, NORM_TOLERANCE,
    SKIP_TAIL_SHARE,
};
use crate::types::*;

/// std::next_permutation: the next lexicographic arrangement, false (and
/// back to sorted) after the last.
fn next_permutation(v: &mut [i32]) -> bool {
    if v.len() < 2 {
        return false;
    }
    let mut i = v.len() - 1;
    while i > 0 && v[i - 1] >= v[i] {
        i -= 1;
    }
    if i == 0 {
        v.reverse();
        return false;
    }
    let mut j = v.len() - 1;
    while v[j] <= v[i - 1] {
        j -= 1;
    }
    v.swap(i - 1, j);
    v[i..].reverse();
    true
}

impl SmartShuffleEngine {
    /// Check 4, before anything is scored: S_t finite and of unit length (else
    /// renormalized or reseeded from `current_row`), every library vector
    /// sound, and how much of the library has real vectors.
    pub(crate) fn preflight(&mut self, current_row: i32) {
        let mut check = AuditCheck { threshold: NORM_TOLERANCE, ..Default::default() };

        let mut non_finite = self.session.iter().filter(|x| !x.is_finite()).count() as i32;
        if self.has_session {
            let mut norm = 0.0f32;
            for &x in &self.session {
                norm += if x.is_finite() { x * x } else { 0.0 };
            }
            let norm = norm.sqrt();
            if non_finite > 0 || (norm - 1.0).abs() > NORM_TOLERANCE {
                if non_finite == 0 && normalize(&mut self.session) {
                    check.detail = "S_t drifted off unit length; renormalized".into();
                } else if current_row >= 0 {
                    let v: [f32; DIMS] = self.vec(current_row).try_into().expect("24 floats");
                    self.session = v;
                    check.detail = "S_t held NaN/Inf; reseeded from the playing song".into();
                } else {
                    self.session = [0.0; DIMS];
                    self.has_session = false;
                    check.detail = "S_t held NaN/Inf; reset".into();
                }
                check.corrections += 1;
            }
        }
        // The library itself: every working vector must be finite and unit length.
        for r in 0..self.size() {
            let v = &mut self.vectors[r * DIMS..(r + 1) * DIMS];
            let mut bad = false;
            let mut norm = 0.0f32;
            for &x in v.iter() {
                if !x.is_finite() {
                    bad = true;
                } else {
                    norm += x * x;
                }
            }
            if bad || (norm.sqrt() - 1.0).abs() > NORM_TOLERANCE {
                non_finite += bad as i32;
                if bad || !normalize(v) {
                    v.fill(0.0);
                    v[r % DIMS] = 1.0;
                    self.counts[self.quality[r] as usize] -= 1;
                    self.quality[r] = VectorQuality::None;
                    self.counts[0] += 1;
                }
                check.corrections += 1;
            }
        }

        let mut norm = 0.0f32;
        for &x in &self.session {
            norm += x * x;
        }
        let report = &mut self.audit;
        report.session_norm = if self.has_session { norm.sqrt() } else { 0.0 };
        report.non_finite_values = non_finite;
        report.real_tracks = self.counts[2];
        report.fallback_tracks = self.counts[1];
        report.placeholder_tracks = self.counts[0];
        report.real_vector_pct =
            if !self.ids.is_empty() { 100.0 * self.counts[2] as f32 / self.ids.len() as f32 } else { 0.0 };
        check.metric = if self.has_session { report.session_norm } else { 1.0 };
        check.status = if check.corrections > 0 { AuditStatus::Healed } else { AuditStatus::Pass };
        let prefix = if check.detail.is_empty() { String::new() } else { format!("{}; ", check.detail) };
        check.detail = format!(
            "{prefix}||S_t|| {}, {} NaN/Inf, {}% real vectors, {} cold-start{}",
            cf(check.metric as f64, 4),
            non_finite,
            cf(report.real_vector_pct as f64, 1),
            self.counts[0] + self.counts[1],
            if self.repaired_inputs > 0 { ", corrupt inputs repaired at load" } else { "" }
        );
        report.checks[AuditCheckId::VectorIntegrity as usize] = check;
    }

    /// Runs checks 2, 3, 1 and 5 on `draft` and repairs it in place. Returns
    /// false when the echo-chamber check has raised w_explore by 50% and wants
    /// the queue drawn again; the engine does so once, with `echo_retry` set,
    /// and that audit injects fresh tracks if the boost was not enough.
    pub(crate) fn run_brain_self_audit(&mut self, draft: &mut Draft, echo_retry: bool) -> bool {
        self.check_skip_repulsion(draft);
        if !self.check_echo_chamber(draft, echo_retry) {
            return false;
        }
        self.check_anti_clumping(draft);
        self.check_transition_jerk(draft);

        // The repairs can interact; the final numbers describe the queue as delivered.
        let clumping = &self.audit.checks[AuditCheckId::AntiClumping as usize];
        let spacing = 1.max(clumping.threshold as i32);
        let (violations, _, min_gap) = self.clump_violations(draft, spacing);
        let clumping = &mut self.audit.checks[AuditCheckId::AntiClumping as usize];
        if violations > 0 {
            clumping.status = AuditStatus::Failed;
        }
        clumping.metric = min_gap as f32;
        self.audit.explore_boost = self.explore_boost;
        true
    }

    /// Check 2: after a fast skip, S_t moved away from the skipped song and at
    /// least 60% of the upcoming tail is new and acoustically distinct.
    fn check_skip_repulsion(&mut self, draft: &mut Draft) {
        let mut check = AuditCheck { threshold: SKIP_TAIL_SHARE, ..Default::default() };
        if !self.pending_skip.active {
            check.metric = 1.0;
            check.detail = "no fast skip since the last queue".into();
            self.audit.checks[AuditCheckId::SkipRepulsion as usize] = check;
            return;
        }
        self.audit.after_fast_skip = true;
        let skip = self.pending_skip.clone();
        if skip.healed {
            check.corrections += 1;
        }
        let moved = skip.dot_after < skip.dot_before;

        let old_tail: HashSet<i64> = draft.replaced_tail.iter().copied().collect();
        let distinct = |e: &Self, row: i32| {
            !old_tail.contains(&e.ids[row as usize]) && dot(e.vec(row), &skip.vector) < DISTINCT_COSINE
        };
        let share = |e: &Self, draft: &Draft| {
            if draft.placed_rows.is_empty() {
                return 1.0f32;
            }
            let n = draft.placed_rows.iter().filter(|&&r| distinct(e, r)).count();
            n as f32 / draft.placed_rows.len() as f32
        };

        let mut ratio = share(self, draft);
        if ratio < SKIP_TAIL_SHARE {
            // Replace the tracks most like the skipped one first.
            let mut order: Vec<usize> = (0..draft.placed_rows.len()).collect();
            sort_desc_by(&mut order, |&p| dot(self.vec(draft.placed_rows[p]), &skip.vector));
            let spacing = self.effective_spacing(draft);
            for position in order {
                if ratio >= SKIP_TAIL_SHARE {
                    break;
                }
                if distinct(self, draft.placed_rows[position]) {
                    continue;
                }
                for k in 0..draft.reserve_rows.len() {
                    let candidate = draft.reserve_rows[k];
                    if !distinct(self, candidate) || !self.artist_fits(draft, position as i32, candidate, spacing) {
                        continue;
                    }
                    draft.placed_rows[position] = candidate;
                    draft.reserve_rows.remove(k);
                    check.corrections += 1;
                    break;
                }
                ratio = share(self, draft);
            }
        }
        check.metric = ratio;
        let ok = moved && ratio >= SKIP_TAIL_SHARE;
        check.status = if !ok {
            AuditStatus::Failed
        } else if check.corrections > 0 {
            AuditStatus::Healed
        } else {
            AuditStatus::Pass
        };
        check.detail = format!(
            "S_t.v_skip {} -> {}{}; {}% of the tail new and distinct",
            cf(skip.dot_before as f64, 2),
            cf(skip.dot_after as f64, 2),
            if skip.healed { " (repelled harder)" } else { "" },
            cf((ratio * 100.0) as f64, 0)
        );
        self.audit.checks[AuditCheckId::SkipRepulsion as usize] = check;
    }

    /// Check 3: is the queue monotonous? Average pairwise cosine plus the
    /// Shannon entropy of its artists and sonic zones.
    fn check_echo_chamber(&mut self, draft: &mut Draft, echo_retry: bool) -> bool {
        let mut check = AuditCheck { threshold: self.weights.echo_threshold, ..Default::default() };

        let measure = |e: &Self, rows: &[i32]| {
            let mut sum = 0.0f64;
            let mut pairs = 0;
            for a in 0..rows.len() {
                for b in a + 1..rows.len() {
                    sum += dot(e.vec(rows[a]), e.vec(rows[b])) as f64;
                    pairs += 1;
                }
            }
            if pairs > 0 {
                (sum / pairs as f64) as f32
            } else {
                0.0
            }
        };
        let mut average = measure(self, &draft.placed_rows);

        if average > check.threshold && !echo_retry {
            // First response: +50% exploration and a fresh draw.
            self.explore_boost = 4.0f32.min(self.explore_boost * 1.5);
            self.audit.checks[AuditCheckId::EchoChamber as usize] = check;
            return false;
        }
        if echo_retry {
            check.corrections += 1;
            if average > check.threshold && !draft.fresh_rows.is_empty() {
                // The boost was not enough: swap the most redundant tracks for the
                // most explorable songs that sound different.
                let swaps = 1.max(draft.placed_rows.len().div_ceil(4));
                let mut centroid = [0.0f32; DIMS];
                for &r in &draft.placed_rows {
                    for d in 0..DIMS {
                        centroid[d] += self.vec(r)[d];
                    }
                }
                normalize(&mut centroid);
                let spacing = self.effective_spacing(draft);
                for _ in 0..swaps {
                    let mut worst = -1i32;
                    let mut worst_sim = -2.0f32;
                    for (p, &r) in draft.placed_rows.iter().enumerate() {
                        let sim = dot(self.vec(r), &centroid);
                        if sim > worst_sim {
                            worst_sim = sim;
                            worst = p as i32;
                        }
                    }
                    if worst < 0 {
                        break;
                    }
                    let mut swapped = false;
                    for k in 0..draft.fresh_rows.len() {
                        let candidate = draft.fresh_rows[k];
                        if draft.placed_rows.contains(&candidate) {
                            continue;
                        }
                        if dot(self.vec(candidate), &centroid) > 0.9 {
                            continue;
                        }
                        if !self.artist_fits(draft, worst, candidate, spacing) {
                            continue;
                        }
                        draft.placed_rows[worst as usize] = candidate;
                        draft.fresh_rows.remove(k);
                        check.corrections += 1;
                        swapped = true;
                        break;
                    }
                    if !swapped {
                        break;
                    }
                }
                average = measure(self, &draft.placed_rows);
            }
            check.status = if average > check.threshold { AuditStatus::Failed } else { AuditStatus::Healed };
        } else if self.explore_boost > 1.0 {
            // Healthy again: let the boost fade.
            self.explore_boost = 1.0f32.max(self.explore_boost * 0.9);
        }

        let mut artists: BTreeMap<i64, i32> = BTreeMap::new();
        let mut zones: BTreeMap<i64, i32> = BTreeMap::new();
        for &r in &draft.placed_rows {
            *artists.entry(self.artist_key(r)).or_insert(0) += 1;
            *zones.entry(self.zone[r as usize] as i64).or_insert(0) += 1;
        }
        let total = draft.placed_rows.len() as i32;
        self.audit.average_pairwise_similarity = average;
        self.audit.artist_entropy_bits = entropy_bits(&artists, total);
        self.audit.zone_entropy_bits = entropy_bits(&zones, total);
        check.metric = average;
        let suffix = if echo_retry {
            if check.status == AuditStatus::Healed {
                "; w_explore +50%, fresh tracks injected"
            } else {
                "; library too uniform to diversify"
            }
        } else {
            ""
        };
        check.detail = format!(
            "avg cosine {}, artist entropy {} bits, zone entropy {} bits{suffix}",
            cf(average as f64, 2),
            cf(self.audit.artist_entropy_bits as f64, 2),
            cf(self.audit.zone_entropy_bits as f64, 2)
        );
        self.audit.checks[AuditCheckId::EchoChamber as usize] = check;
        true
    }

    /// The configured spacing, unless the library makes it impossible: keeping
    /// every artist s slots apart needs at least s different artists among the
    /// songs the queue can be built from (placed plus reserve), and a queue of
    /// L slots cannot space anything more than L apart.
    pub(crate) fn effective_spacing(&self, draft: &Draft) -> i32 {
        let wanted = self.weights.min_artist_spacing;
        if wanted <= 1 || draft.placed_rows.is_empty() {
            return 1.max(wanted);
        }
        let mut artists: HashSet<i64> = draft.placed_rows.iter().map(|&r| self.artist_key(r)).collect();
        for &r in &draft.reserve_rows {
            if artists.len() as i32 >= wanted {
                break;
            }
            artists.insert(self.artist_key(r));
        }
        let slots = (draft.placed_rows.len() + draft.prefix_rows.len().min(wanted as usize)) as i32;
        let feasible = (artists.len() as i32).min(slots);
        1.max(wanted.min(feasible))
    }

    /// (violations, the first bad placed index or -1, the smallest same-artist gap).
    pub(crate) fn clump_violations(&self, draft: &Draft, spacing: i32) -> (i32, i32, i32) {
        let prefix_size = draft.prefix_rows.len();
        let total = prefix_size + draft.placed_rows.len();
        let row_at = |i: usize| if i < prefix_size { draft.prefix_rows[i] } else { draft.placed_rows[i - prefix_size] };
        let mut last: HashMap<i64, usize> = HashMap::new();
        let mut violations = 0;
        let mut smallest = total as i32;
        let mut first_bad = -1;
        for i in 0..total {
            let artist = self.artist_key(row_at(i));
            if let Some(&seen) = last.get(&artist) {
                if i >= prefix_size {
                    let gap = (i - seen) as i32;
                    smallest = smallest.min(gap);
                    if gap < spacing {
                        violations += 1;
                        if first_bad < 0 {
                            first_bad = (i - prefix_size) as i32;
                        }
                    }
                }
            }
            last.insert(artist, i);
        }
        (violations, first_bad, smallest)
    }

    pub(crate) fn artist_fits(&self, draft: &Draft, position: i32, row: i32, spacing: i32) -> bool {
        let artist = self.artist[row as usize];
        if artist < 0 {
            return true;
        }
        let prefix_size = draft.prefix_rows.len() as i32;
        let at = prefix_size + position;
        let total = prefix_size + draft.placed_rows.len() as i32;
        for i in 0.max(at - spacing + 1)..total.min(at + spacing) {
            if i == at {
                continue;
            }
            let other = if i < prefix_size {
                draft.prefix_rows[i as usize]
            } else {
                draft.placed_rows[(i - prefix_size) as usize]
            };
            if self.artist[other as usize] == artist {
                return false;
            }
        }
        true
    }

    /// Check 1: same-artist tracks at least min_artist_spacing apart, unless
    /// the queue's make-up makes that impossible.
    fn check_anti_clumping(&mut self, draft: &mut Draft) {
        let spacing = self.effective_spacing(draft);
        let mut check = AuditCheck { threshold: spacing as f32, ..Default::default() };
        let (mut violations, mut bad, mut gap) = self.clump_violations(draft, spacing);
        let limit = 3 * draft.placed_rows.len() as i32 + 3;
        let mut iteration = 0;
        while violations > 0 && bad >= 0 && iteration < limit {
            iteration += 1;
            let bad_at = bad as usize;
            let mut fixed = false;
            if draft.allow_reorder {
                // A dithering swap: the swap partner that leaves the fewest violations.
                let mut best_partner = -1i32;
                let mut best_violations = violations;
                for k in 0..draft.placed_rows.len() {
                    if k == bad_at {
                        continue;
                    }
                    draft.placed_rows.swap(bad_at, k);
                    let (after, _, _) = self.clump_violations(draft, spacing);
                    draft.placed_rows.swap(bad_at, k);
                    if after < best_violations {
                        best_violations = after;
                        best_partner = k as i32;
                    }
                }
                if best_partner >= 0 {
                    draft.placed_rows.swap(bad_at, best_partner as usize);
                    fixed = true;
                }
            }
            if !fixed {
                // No swap helps: replace the track with one by an artist that fits.
                for k in 0..draft.reserve_rows.len() {
                    let candidate = draft.reserve_rows[k];
                    if !self.artist_fits(draft, bad, candidate, spacing) {
                        continue;
                    }
                    draft.placed_rows[bad_at] = candidate;
                    draft.reserve_rows.remove(k);
                    fixed = true;
                    break;
                }
            }
            if !fixed {
                break;
            }
            check.corrections += 1;
            (violations, bad, gap) = self.clump_violations(draft, spacing);
        }
        check.metric = gap as f32;
        check.status = if violations > 0 {
            AuditStatus::Failed
        } else if check.corrections > 0 {
            AuditStatus::Healed
        } else {
            AuditStatus::Pass
        };
        check.detail = format!(
            "min same-artist gap {gap} (>= {spacing} required), {} repair{}",
            check.corrections,
            if check.corrections == 1 { "" } else { "s" }
        );
        self.audit.checks[AuditCheckId::AntiClumping as usize] = check;
    }

    /// ||v_prev - v|| into placed[j]; 0 when leaving a song just fast-skipped.
    fn jump_before(&self, draft: &Draft, j: usize) -> f32 {
        let row = draft.placed_rows[j];
        if j > 0 {
            return distance(self.vec(draft.placed_rows[j - 1]), self.vec(row));
        }
        let Some(&previous) = draft.prefix_rows.last() else { return 0.0 };
        // Leaving a song the listener just fast-skipped is the point: that jump is
        // the pivot, not a rough transition.
        if self.pending_skip.active && self.ids[previous as usize] == self.pending_skip.id {
            return 0.0;
        }
        distance(self.vec(previous), self.vec(row))
    }

    fn max_jump(&self, draft: &Draft) -> f32 {
        let mut worst = 0.0f32;
        for j in 0..draft.placed_rows.len() {
            let jump = self.jump_before(draft, j);
            if jump > worst {
                worst = jump;
            }
        }
        worst
    }

    /// A track the listener's own Markov habit put here: they make that jump
    /// themselves, so it is kept even when it is a big one.
    fn is_habit_pick(&self, row: i32) -> bool {
        let Some(c) = self.candidate_by_row.get(&row) else { return false };
        c.markov > 0.0 && c.markov >= 0.5 * (c.vibe + c.markov + c.affinity + c.explore)
    }

    /// Check 5: no harsh spectral jump between neighbours. Each harsh jump is
    /// first smoothed by re-ordering the four tracks around it, then, if that
    /// is not enough, by swapping the outlier for a bridging track. Jumps into
    /// or out of a learned habit are left alone.
    fn check_transition_jerk(&mut self, draft: &mut Draft) {
        let threshold = self.weights.jerk_threshold;
        let mut check = AuditCheck { threshold, ..Default::default() };
        let spacing = self.effective_spacing(draft);
        let length = draft.placed_rows.len() as i32;
        let harsh_at = |e: &Self, draft: &Draft, j: i32| e.jump_before(draft, j as usize) > threshold;
        let protected_at = |e: &Self, draft: &Draft, j: i32| {
            e.is_habit_pick(draft.placed_rows[j as usize])
                || (j > 0 && e.is_habit_pick(draft.placed_rows[j as usize - 1]))
        };
        // (harsh jumps, largest jump) over transitions into from..to+1.
        let window_cost = |e: &Self, draft: &Draft, from: i32, to: i32| {
            let mut harsh = 0;
            let mut worst = 0.0f32;
            for j in from..=(length - 1).min(to + 1) {
                let jump = e.jump_before(draft, j as usize);
                harsh += (jump > threshold) as i32;
                worst = worst.max(jump);
            }
            (harsh, worst)
        };

        for _pass in 0..2 {
            for j in 0..length {
                if !harsh_at(self, draft, j) || protected_at(self, draft, j) {
                    continue;
                }

                if draft.allow_reorder && length >= 2 {
                    let from = 0.max((j - 1).min(length - 4));
                    let to = (length - 1).min(from + 3);
                    let window = from as usize..to as usize + 1;
                    let original: Vec<i32> = draft.placed_rows[window.clone()].to_vec();
                    let mut best = original.clone();
                    let (base_violations, _, _) = self.clump_violations(draft, spacing);
                    let mut best_cost = window_cost(self, draft, from, to);
                    let mut permutation = original.clone();
                    permutation.sort();
                    loop {
                        draft.placed_rows[window.clone()].copy_from_slice(&permutation);
                        let cost = window_cost(self, draft, from, to);
                        let better = cost.0 < best_cost.0 || (cost.0 == best_cost.0 && cost.1 + 1e-4 < best_cost.1);
                        if better && self.clump_violations(draft, spacing).0 <= base_violations {
                            best_cost = cost;
                            best.copy_from_slice(&permutation);
                        }
                        if !next_permutation(&mut permutation) {
                            break;
                        }
                    }
                    draft.placed_rows[window].copy_from_slice(&best);
                    if best != original {
                        check.corrections += 1;
                    }
                    if !harsh_at(self, draft, j) {
                        continue;
                    }
                }

                // A bridge: a reserve song close to both neighbours, in place of
                // whichever side of the jump is not a habit.
                for side in [j, j - 1] {
                    if side < 0 || self.is_habit_pick(draft.placed_rows[side as usize]) {
                        continue;
                    }
                    let previous = if side == 0 {
                        draft.prefix_rows.last().copied().unwrap_or(-1)
                    } else {
                        draft.placed_rows[side as usize - 1]
                    };
                    let next = if side + 1 < length { draft.placed_rows[side as usize + 1] } else { -1 };
                    // Leaving a fast-skipped song is a pivot; only the next side matters there.
                    let pivot = side == 0
                        && previous >= 0
                        && self.pending_skip.active
                        && self.ids[previous as usize] == self.pending_skip.id;
                    let mut bridged = false;
                    for k in 0..draft.reserve_rows.len() {
                        let candidate = draft.reserve_rows[k];
                        if previous >= 0 && !pivot && distance(self.vec(previous), self.vec(candidate)) > threshold {
                            continue;
                        }
                        if next >= 0 && distance(self.vec(candidate), self.vec(next)) > threshold {
                            continue;
                        }
                        if !self.artist_fits(draft, side, candidate, spacing) {
                            continue;
                        }
                        draft.reserve_rows.remove(k);
                        draft.reserve_rows.push(draft.placed_rows[side as usize]);
                        draft.placed_rows[side as usize] = candidate;
                        check.corrections += 1;
                        bridged = true;
                        break;
                    }
                    if bridged {
                        break;
                    }
                }
            }
        }

        let mut flagged = 0;
        let mut kept = 0;
        for j in 0..length {
            if !harsh_at(self, draft, j) {
                continue;
            }
            if protected_at(self, draft, j) {
                kept += 1;
            } else {
                flagged += 1;
            }
        }
        let worst = self.max_jump(draft);
        check.metric = worst;
        check.status = if flagged > 0 {
            AuditStatus::Failed
        } else if check.corrections > 0 {
            AuditStatus::Healed
        } else {
            AuditStatus::Pass
        };
        let habits = if kept > 0 { format!(", {kept} kept as habits") } else { String::new() };
        check.detail = format!(
            "largest jump {} (limit {}), {flagged} harsh{habits}, {} repair{}",
            cf(worst as f64, 2),
            cf(threshold as f64, 2),
            check.corrections,
            if check.corrections == 1 { "" } else { "s" }
        );
        self.audit.checks[AuditCheckId::TransitionJerk as usize] = check;
    }
}

#[cfg(test)]
mod tests {
    use super::next_permutation;

    #[test]
    fn permutations_match_the_standard_library() {
        let mut v = vec![1, 2, 3];
        let mut seen = vec![v.clone()];
        while next_permutation(&mut v) {
            seen.push(v.clone());
        }
        assert_eq!(seen, [[1, 2, 3], [1, 3, 2], [2, 1, 3], [2, 3, 1], [3, 1, 2], [3, 2, 1]]);
        assert_eq!(v, [1, 2, 3]);
        let mut d = vec![1, 1, 2];
        let mut n = 1;
        while next_permutation(&mut d) {
            n += 1;
        }
        assert_eq!(n, 3);
    }
}
