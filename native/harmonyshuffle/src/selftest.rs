//! The built-in self-test: 100 virtual tracks in five sonic families, five
//! bass-heavy plays, then two fast skips on bright treble tracks, plus the
//! echo-chamber, clumping and integrity drills.

use crate::engine::{Rng, SmartShuffleEngine, NORM_TOLERANCE};
use crate::types::*;

/// CPU time this thread has used, in ms: what the self-test's latency budget
/// measures. Wall time would also count the moments the OS ran something else
/// (a busy CI runner, a phone in the middle of a scan), which says nothing
/// about the engine. Falls back to wall time where there is no per-thread clock.
fn thread_cpu_ms() -> f64 {
    #[cfg(unix)]
    {
        let mut ts = libc::timespec { tv_sec: 0, tv_nsec: 0 };
        // SAFETY: clock_gettime only writes the timespec it is given.
        if unsafe { libc::clock_gettime(libc::CLOCK_THREAD_CPUTIME_ID, &mut ts) } == 0 {
            return ts.tv_sec as f64 * 1000.0 + ts.tv_nsec as f64 / 1.0e6;
        }
    }
    use std::sync::OnceLock;
    use std::time::Instant;
    static START: OnceLock<Instant> = OnceLock::new();
    START.get_or_init(Instant::now).elapsed().as_secs_f64() * 1000.0
}

const BASS_HEAVY: usize = 0;
const BRIGHT_TREBLE: usize = 1;
const FAMILIES: usize = 5;

/// One virtual song of `family`, with +-8% variation.
pub(crate) fn synthetic_raw(family: usize, rng: &mut Rng, out: &mut [f32]) {
    const BANDS_BY_FAMILY: [[f32; BANDS]; FAMILIES] = [
        [1.00, 0.95, 0.70, 0.45, 0.30, 0.22, 0.15, 0.10, 0.06, 0.03],
        [0.12, 0.15, 0.20, 0.25, 0.35, 0.50, 0.70, 0.90, 1.00, 0.80],
        [0.20, 0.30, 0.45, 0.70, 0.90, 1.00, 0.80, 0.45, 0.25, 0.10],
        [0.50, 0.60, 0.65, 0.60, 0.55, 0.50, 0.45, 0.40, 0.30, 0.20],
        [0.15, 0.30, 0.55, 0.75, 0.80, 0.65, 0.50, 0.35, 0.20, 0.08],
    ];
    const MACRO: [[f32; 4]; FAMILIES] = [
        [0.75, 0.20, 0.25, 0.60],
        [0.10, 0.80, 0.35, 0.50],
        [0.25, 0.50, 0.40, 0.35],
        [0.40, 0.40, 0.30, 0.45],
        [0.15, 0.45, 0.75, 0.20],
    ];
    let mut jitter = |v: f32| v * (0.92 + 0.16 * rng.uniform()) as f32;
    let mut norm = 0.0f64;
    for k in 0..BANDS {
        out[k] = jitter(BANDS_BY_FAMILY[family][k]);
        norm += (out[k] * out[k]) as f64;
    }
    for k in 0..BANDS {
        out[k] = (out[k] as f64 / norm.sqrt()) as f32;
    }
    for k in 0..BANDS {
        let lively = (family == BASS_HEAVY && k <= 2) || (family == BRIGHT_TREBLE && k >= 7);
        out[BANDS + k] = jitter(if lively { 0.55 } else { 0.30 });
    }
    for m in 0..4 {
        out[20 + m] = jitter(MACRO[family][m]);
    }
}

#[derive(Default)]
struct Reporter {
    text: String,
    passed: i32,
    failed: i32,
}

impl Reporter {
    fn check(&mut self, ok: bool, what: &str, detail: &str) {
        if ok {
            self.passed += 1;
        } else {
            self.failed += 1;
        }
        self.text += if ok { "[PASS] " } else { "[FAIL] " };
        self.text += what;
        if !detail.is_empty() {
            self.text += &format!(" ({detail})");
        }
        self.text += "\n";
    }
}

fn family_of_id(id: i64) -> usize {
    ((id - 1000) / 20) as usize
}

fn family_share(ids: &[i64], family: usize) -> f32 {
    if ids.is_empty() {
        return 0.0;
    }
    let n = ids.iter().filter(|&&id| family_of_id(id) == family).count();
    n as f32 / ids.len() as f32
}

/// The checks that did not pass, with their details.
fn problems(report: &BrainAuditReport) -> String {
    const NAMES: [&str; AUDIT_CHECKS] = ["clumping", "skip", "echo", "integrity", "jerk"];
    let mut out = String::new();
    for (i, c) in report.checks.iter().enumerate() {
        if c.status != AuditStatus::Failed {
            continue;
        }
        if !out.is_empty() {
            out += "; ";
        }
        out += &format!("{}: {}", NAMES[i], c.detail);
    }
    out
}

fn session_sound(e: &SmartShuffleEngine) -> bool {
    let mut norm = 0.0f32;
    for &x in e.session_vector() {
        if !x.is_finite() {
            return false;
        }
        norm += x * x;
    }
    (norm.sqrt() - 1.0).abs() <= NORM_TOLERANCE
}

/// Runs the self-test. Returns the PASS/FAIL report (it ends with a
/// "RESULT: PASS" or "RESULT: FAIL" line) and the verdict.
pub fn run_synthetic_brain_simulation() -> (String, bool) {
    let mut r = Reporter {
        text: "SHUFFLE BRAIN SELF-TEST: 100 virtual tracks, 5 families x 4 artists x 5 songs\n".into(),
        ..Default::default()
    };
    let now: i64 = 1_800_000_000_000;

    // ---- Library -------------------------------------------------------
    const TRACKS: usize = 100;
    let mut ids = vec![0i64; TRACKS];
    let mut raw = vec![0.0f32; TRACKS * DIMS];
    let quality = vec![VectorQuality::Real as u8; TRACKS];
    let mut artists = vec![0i32; TRACKS];
    let mut albums = vec![0i32; TRACKS];
    let mut rng = Rng::new(42);
    for i in 0..TRACKS {
        let family = i / 20;
        ids[i] = 1000 + i as i64;
        artists[i] = (family * 4 + i % 4) as i32;
        albums[i] = artists[i] * 2 + ((i % 20) / 10) as i32;
        synthetic_raw(family, &mut rng, &mut raw[i * DIMS..(i + 1) * DIMS]);
    }
    let mut engine = SmartShuffleEngine::new();
    engine.set_library(&ids, Some(&raw), Some(&quality), Some(&artists), Some(&albums));

    // ---- 5 bass-heavy plays, then 2 fast skips on bright treble ----------
    let t0 = thread_cpu_ms();
    let mut played: Vec<i64> = Vec::new();
    for i in 0..5 {
        let id = 1000 + i;
        engine.on_track_started(id);
        engine.on_playback_outcome(id, 0.95, now, true);
        played.push(id);
    }
    let mut queue = engine.generate_queue(*played.last().expect("five plays"), &[], &played, &[], 10, now, 7);
    let after_plays = engine.last_audit().clone();

    let mut bands: Vec<(usize, f32)> = (0..BANDS).map(|k| (k, engine.session_vector()[k])).collect();
    crate::engine::sort_desc_by(&mut bands, |b| b.1);
    let bass_dominant = bands[0].0 <= 2 && bands[1].0 <= 2;
    let bass_share = family_share(&queue, BASS_HEAVY);

    let mut dot_before = [0.0f32; 2];
    let mut dot_after = [0.0f32; 2];
    let mut skip_events = true;
    let mut treble_share = 0.0f32;
    let mut tail_replaced = [0.0f32; 2];
    let mut skip_check_ok = true;
    for s in 0..2 {
        let skipped = 1020 + s as i64 * 5;
        let v = engine.vector_of(skipped).unwrap_or([0.0; DIMS]);
        let mut dot0 = 0.0f32;
        for d in 0..DIMS {
            dot0 += engine.session_vector()[d] * v[d];
        }
        dot_before[s] = dot0;
        engine.on_track_started(skipped);
        skip_events = skip_events && engine.on_playback_outcome(skipped, 0.08, now, true) == SessionEvent::FastSkip;
        let mut dot1 = 0.0f32;
        for d in 0..DIMS {
            dot1 += engine.session_vector()[d] * v[d];
        }
        dot_after[s] = dot1;
        played.push(skipped);
        // The skip moves playback on to the next queued song; the rest of the
        // tail is dropped and re-ranked behind it.
        let now_playing = queue[0];
        engine.on_track_started(now_playing);
        played.push(now_playing);
        let old_tail = queue[1..].to_vec();
        let next = engine.generate_queue(now_playing, &[], &played, &old_tail, 10, now, 11 + s as u64);
        let check = engine.last_audit().check(AuditCheckId::SkipRepulsion);
        tail_replaced[s] = check.metric;
        skip_check_ok = skip_check_ok && check.status != AuditStatus::Failed && engine.last_audit().after_fast_skip;
        treble_share = family_share(&next, BRIGHT_TREBLE);
        queue = next;
    }
    let adapt_ms = thread_cpu_ms() - t0;
    let after_skips = engine.last_audit().clone();

    r.check(
        bass_dominant,
        "Session vector leans on the bass bands after 5 bass-heavy plays",
        &format!("top bands {} and {}", bands[0].0, bands[1].0),
    );
    r.check(
        bass_share >= 0.70,
        "Queue follows the bass vibe",
        &format!("{}% bass-heavy, need >= 70%", cf(bass_share as f64 * 100.0, 0)),
    );
    let verdict = |report: &BrainAuditReport| {
        format!("{} healed, {} failed", report.healed_count(), report.failed_count())
            + &if report.healthy() { String::new() } else { format!(": {}", problems(report)) }
    };
    r.check(after_plays.healthy(), "All 5 audits pass or heal after the plays", &verdict(&after_plays));
    r.check(skip_events, "Both treble skips register as fast skips", "");
    r.check(
        dot_after[0] < dot_before[0] && dot_after[1] < dot_before[1],
        "Each skip pushes S_t away from the skipped song",
        &format!(
            "{}->{}, then {}",
            cf(dot_before[0] as f64, 2),
            cf(dot_after[0] as f64, 2),
            cf(dot_after[1] as f64, 2)
        ),
    );
    r.check(
        skip_check_ok && tail_replaced[0] >= 0.6 && tail_replaced[1] >= 0.6,
        "At least 60% of the tail replaced with distinct tracks after each skip",
        &format!("{}% and {}%", cf(tail_replaced[0] as f64 * 100.0, 0), cf(tail_replaced[1] as f64 * 100.0, 0)),
    );
    r.check(
        treble_share <= 0.10,
        "No bright treble back in the queue after the skips",
        &format!("{}% treble", cf(treble_share as f64 * 100.0, 0)),
    );
    r.check(session_sound(&engine), "||S_t|| = 1 +- 0.001 and no NaN/Inf", "");
    r.check(after_skips.healthy(), "All 5 audits pass or heal after the skips", &verdict(&after_skips));
    r.check(adapt_ms < 5.0, "Adapts in under 5 ms of CPU (7 events, 3 queues)", &format!("{} ms", cf(adapt_ms, 3)));

    let traces = engine.last_traces();
    let traces_ok = !traces.is_empty()
        && traces.iter().all(|t| {
            let sum = t.vibe_similarity_pct + t.markov_habit_pct + t.user_affinity_pct + t.exploration_bonus_pct;
            (sum - 100.0).abs() < 0.5 && !t.primary_reason_tag.is_empty()
        });
    r.check(traces_ok, "Every queued track explains itself (percentages sum to 100, reason tagged)", "");

    // ---- Echo chamber drill: two tight families, the session in one -------
    {
        let mut echo = SmartShuffleEngine::new();
        const N: usize = 60;
        let echo_ids: Vec<i64> = (0..N as i64).map(|i| 5000 + i).collect();
        let echo_artists: Vec<i32> = (0..N as i32).collect();
        let mut echo_raw = vec![0.0f32; N * DIMS];
        let mut tight = Rng::new(9);
        for i in 0..N {
            let family = if i < 40 { BASS_HEAVY } else { BRIGHT_TREBLE };
            let mut same = Rng::new(if family == BASS_HEAVY { 1 } else { 2 });
            let v = &mut echo_raw[i * DIMS..(i + 1) * DIMS];
            synthetic_raw(family, &mut same, v);
            for x in v.iter_mut() {
                *x *= (1.0 + 0.002 * tight.uniform()) as f32;
            }
        }
        echo.set_library(&echo_ids, Some(&echo_raw), None, Some(&echo_artists), None);
        echo.on_track_started(5000);
        echo.on_playback_outcome(5000, 1.0, now, false);
        echo.generate_queue(5000, &[], &[], &[], 10, now, 3);
        let check = echo.last_audit().check(AuditCheckId::EchoChamber);
        r.check(
            check.status == AuditStatus::Healed && echo.explore_boost() > 1.0,
            "Echo chamber detected and healed (w_explore +50%, fresh tracks)",
            &format!(
                "avg cosine now {}, boost x{}",
                cf(echo.last_audit().average_pairwise_similarity as f64, 2),
                cf(echo.explore_boost() as f64, 2)
            ),
        );
    }

    // ---- Anti-clumping drill: one artist owns half the bass songs ---------
    {
        let mut clumped = artists.clone();
        for i in (0..20).step_by(2) {
            clumped[i] = 99;
        }
        let mut clump = SmartShuffleEngine::new();
        clump.set_library(&ids, Some(&raw), Some(&quality), Some(&clumped), Some(&albums));
        clump.on_track_started(1001);
        clump.on_playback_outcome(1001, 1.0, now, false);
        let q = clump.generate_queue(1001, &[], &[], &[], 12, now, 5);
        let mut tightest = 99usize;
        for a in 0..q.len() {
            for b in a + 1..q.len() {
                let (ra, rb) = ((q[a] - 1000) as usize, (q[b] - 1000) as usize);
                if clumped[ra] == clumped[rb] {
                    tightest = tightest.min(b - a);
                }
            }
        }
        let check = clump.last_audit().check(AuditCheckId::AntiClumping);
        r.check(
            check.status != AuditStatus::Failed && tightest as i32 >= check.threshold as i32,
            "Same-artist songs kept apart",
            &format!("closest pair {tightest} apart, spacing {} {}", cf(check.threshold as f64, 0), check.detail),
        );
    }

    // ---- Integrity drill: corrupt inputs and a corrupt outcome -----------
    {
        let mut corrupt = raw.clone();
        corrupt[3] = f32::INFINITY;
        corrupt[DIMS + 5] = f32::NAN;
        corrupt[2 * DIMS + 7] = 1e30;
        let mut sturdy = SmartShuffleEngine::new();
        sturdy.set_library(&ids, Some(&corrupt), Some(&quality), Some(&artists), Some(&albums));
        sturdy.on_track_started(1000);
        sturdy.on_playback_outcome(1000, f32::NAN, now, true);
        sturdy.on_playback_outcome(1001, f32::INFINITY, now, true);
        let q = sturdy.generate_queue(1000, &[], &[], &[], 8, now, 1);
        r.check(
            session_sound(&sturdy) && sturdy.last_audit().non_finite_values == 0 && q.len() == 8,
            "Corrupt inputs never reach the vectors",
            &format!("{} values repaired at load", sturdy.repaired_input_values()),
        );
    }

    let passed = r.failed == 0;
    r.text += &format!(
        "RESULT: {} ({}/{} checks), adaptation {} ms\n",
        if passed { "PASS" } else { "FAIL" },
        r.passed,
        r.passed + r.failed,
        cf(adapt_ms, 3)
    );
    (r.text, passed)
}
