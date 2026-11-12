//! SmartShuffleEngine, the Diagnostic Brain, the mix and the extractor, ported
//! from the C++ shuffle_test.cpp. Run with
//! `cargo test --release -p harmonyshuffle --features testing`.
//!
//! The synthetic libraries come from std::mt19937 with libstdc++'s
//! uniform_real_distribution and normal_distribution (reimplemented below), so
//! these tests run on the same data the C++ ones did.

#![allow(clippy::needless_range_loop)]

use std::collections::BTreeSet;
use std::time::Instant;

use harmonyshuffle::*;

const PI: f64 = std::f64::consts::PI;
const NOW: i64 = 1_800_000_000_000;
const DAY_MS: i64 = 86_400_000;

// ---- std::mt19937 and libstdc++'s distributions --------------------------------------------

struct Mt19937 {
    state: [u32; 624],
    index: usize,
}

impl Mt19937 {
    fn new(seed: u32) -> Self {
        let mut state = [0u32; 624];
        state[0] = seed;
        for i in 1..624 {
            state[i] = 1_812_433_253u32.wrapping_mul(state[i - 1] ^ (state[i - 1] >> 30)).wrapping_add(i as u32);
        }
        Mt19937 { state, index: 624 }
    }

    fn next_u32(&mut self) -> u32 {
        if self.index >= 624 {
            for i in 0..624 {
                let y = (self.state[i] & 0x8000_0000) | (self.state[(i + 1) % 624] & 0x7fff_ffff);
                let mut v = self.state[(i + 397) % 624] ^ (y >> 1);
                if y & 1 != 0 {
                    v ^= 0x9908_b0df;
                }
                self.state[i] = v;
            }
            self.index = 0;
        }
        let mut y = self.state[self.index];
        self.index += 1;
        y ^= y >> 11;
        y ^= (y << 7) & 0x9d2c_5680;
        y ^= (y << 15) & 0xefc6_0000;
        y ^ (y >> 18)
    }

    /// std::uniform_real_distribution<float>(a, b), as libstdc++ draws it.
    fn uniform_f32(&mut self, a: f32, b: f32) -> f32 {
        let mut canonical = self.next_u32() as f32 / 4_294_967_296.0f32;
        if canonical >= 1.0 {
            canonical = 1.0f32 - f32::EPSILON / 2.0;
        }
        canonical * (b - a) + a
    }

    /// generate_canonical<double, 53>: two draws.
    fn canonical_f64(&mut self) -> f64 {
        let low = self.next_u32() as f64;
        let high = self.next_u32() as f64;
        let mut r = (low + high * 4_294_967_296.0) / 18_446_744_073_709_551_616.0;
        if r >= 1.0 {
            r = 1.0 - f64::EPSILON / 2.0;
        }
        r
    }
}

/// std::normal_distribution<double>, libstdc++'s Marsaglia polar method.
struct Normal {
    mean: f64,
    sigma: f64,
    saved: Option<f64>,
}

impl Normal {
    fn sample(&mut self, rng: &mut Mt19937) -> f64 {
        let z = if let Some(z) = self.saved.take() {
            z
        } else {
            let (x, y, r2) = loop {
                let x = 2.0 * rng.canonical_f64() - 1.0;
                let y = 2.0 * rng.canonical_f64() - 1.0;
                let r2 = x * x + y * y;
                if r2 <= 1.0 && r2 != 0.0 {
                    break (x, y, r2);
                }
            };
            let mult = (-2.0 * r2.ln() / r2).sqrt();
            self.saved = Some(x * mult);
            y * mult
        };
        z * self.sigma + self.mean
    }
}

// ---- Extraction ----------------------------------------------------------------------------

/// Runs `seconds` of `signal(t)` at `rate` through a fresh extractor.
fn extract(rate: i32, seconds: f64, mut signal: impl FnMut(f64) -> f64) -> Option<[f32; DIMS]> {
    let mut extractor = AcousticExtractor::new(rate);
    let total = (seconds * rate as f64) as usize;
    let mut chunk = vec![0.0f32; 4096];
    let mut n = 0;
    while n < total {
        let count = chunk.len().min(total - n);
        for i in 0..count {
            chunk[i] = signal((n + i) as f64 / rate as f64) as f32;
        }
        extractor.process(&chunk[..count]);
        n += count;
    }
    extractor.finish()
}

fn kick(t: f64) -> f64 {
    // A 55 Hz thump twice a second, decaying over 150 ms.
    let phase = t % 0.5;
    0.8 * (-phase / 0.15).exp() * (2.0 * PI * 55.0 * phase).sin()
}

fn argmax(v: &[f32]) -> usize {
    (1..v.len()).fold(0, |best, i| if v[best] < v[i] { i } else { best })
}

#[test]
fn extractor_hears_bass_and_treble() {
    let mut rng = Mt19937::new(1);
    let mut noise = Normal { mean: 0.0, sigma: 0.2, saved: None };
    let mut previous = 0.0;
    // Differentiated noise: energy rising with frequency.
    let hiss = |_: f64| {
        let x = noise.sample(&mut rng);
        let y = x - previous;
        previous = x;
        0.5 * y
    };
    let bass = extract(44100, 12.0, kick).expect("bass track measured");
    let bright = extract(44100, 12.0, hiss).expect("bright track measured");

    assert!(argmax(&bass[..BANDS]) <= 1, "kick peaks in a bass band");
    assert!(argmax(&bright[..BANDS]) >= 7, "hiss peaks in a treble band");
    assert!(bass[21] < 0.25 && bright[21] > 0.6, "centroid dark {} vs bright {}", bass[21], bright[21]);
    assert!(bass[20] > bright[20] + 0.2, "sub-bass punch {} vs {}", bass[20], bright[20]);
    let tone = extract(44100, 6.0, |t| 0.5 * (2.0 * PI * 1000.0 * t).sin()).expect("tone measured");
    assert!(tone[22] < 0.05 && bass[22] > tone[22] + 0.15, "crest: tone {} kicks {}", tone[22], bass[22]);
    // Two kicks a second: the pulse rate should land near 2/(2+3) = 0.4.
    assert!(bass[23] > 0.25 && bass[23] < 0.55, "pulse rate {}", bass[23]);
    assert!(bright[23] < bass[23], "steady hiss pulses less ({} vs {})", bright[23], bass[23]);

    let norm: f64 = bass[..BANDS].iter().map(|&x| (x * x) as f64).sum();
    assert!((norm.sqrt() - 1.0).abs() < 1e-4, "spectral profile is unit length ({})", norm.sqrt());
    for d in 0..DIMS {
        assert!(bass[d].is_finite() && (0.0..=1.0).contains(&bass[d]), "dim {d}: {}", bass[d]);
        assert!(bright[d].is_finite() && (0.0..=1.0).contains(&bright[d]), "dim {d}: {}", bright[d]);
    }
}

#[test]
fn extractor_handles_edges() {
    assert!(extract(44100, 1.0, kick).is_none(), "one second is too short to judge");
    assert!(extract(44100, 10.0, |_| 0.0).is_none(), "silence has no profile");
    assert!(!AcousticExtractor::new(1000).valid(), "absurd sample rates are refused");

    // Corrupt samples count as silence and never poison the result.
    let corrupt = |t: f64| {
        let i = (t * 48000.0) as i64;
        if i % 97 == 0 {
            f64::NAN
        } else if i % 89 == 0 {
            f64::INFINITY
        } else {
            kick(t)
        }
    };
    let out = extract(48000, 8.0, corrupt).expect("corrupt samples are survivable");
    assert!(out.iter().all(|v| v.is_finite()));

    // A 22.05 kHz file cannot hold 16 kHz: that band reads as empty, not as garbage.
    let out = extract(22050, 8.0, kick).expect("22.05 kHz measured");
    assert_eq!(out[9], 0.0, "16 kHz band empty at 22.05 kHz");
    let out = extract(192000, 4.0, kick).expect("192 kHz measured");
    assert!(argmax(&out[..BANDS]) <= 1, "192 kHz kick still peaks in the bass");
}

// ---- A small synthetic library: families of songs with a known sound ----------------------

#[derive(Clone, Default)]
struct Library {
    ids: Vec<i64>,
    raw: Vec<f32>,
    quality: Vec<u8>,
    artists: Vec<i32>,
    family: Vec<i32>,
}

impl Library {
    fn index_of(&self, id: i64) -> usize {
        self.ids.iter().position(|&x| x == id).expect("a library id")
    }
}

/// `per_family` songs in each of the families, a distinct artist every `songs_per_artist` songs.
fn make_library(families: i32, per_family: i32, songs_per_artist: i32, seed: u32) -> Library {
    let mut lib = Library::default();
    let mut rng = Mt19937::new(seed);
    let mut artist = 0;
    for f in 0..families {
        for i in 0..per_family {
            if i % songs_per_artist == 0 {
                artist += 1;
            }
            lib.ids.push(100_000 + f as i64 * 1000 + i as i64);
            lib.family.push(f);
            lib.artists.push(artist);
            lib.quality.push(VectorQuality::Real as u8);
            // Family f leans on bands around 2f, with its own macro signature.
            for k in 0..BANDS {
                let centre = (f * 9) as f32 / (families - 1).max(1) as f32;
                let d = k as f32 - centre;
                lib.raw.push(rng.uniform_f32(0.9, 1.1) * (-0.5f32 * d * d / 2.0).exp());
            }
            for _ in 0..BANDS {
                lib.raw.push(rng.uniform_f32(0.9, 1.1) * (0.2 + 0.1 * f as f32));
            }
            for m in 0..4 {
                lib.raw.push(rng.uniform_f32(0.9, 1.1) * (0.15 + 0.15 * ((f + m) % 4) as f32));
            }
        }
    }
    lib
}

fn lib3(families: i32, per_family: i32, songs_per_artist: i32) -> Library {
    make_library(families, per_family, songs_per_artist, 3)
}

fn load(engine: &mut SmartShuffleEngine, lib: &Library) {
    engine.set_library(&lib.ids, Some(&lib.raw), Some(&lib.quality), Some(&lib.artists), None);
}

fn generate(
    engine: &mut SmartShuffleEngine,
    current: i64,
    count: i32,
    exclude: &[i64],
    replaced: &[i64],
    seed: u64,
) -> Vec<i64> {
    engine.generate_queue(current, &[], exclude, replaced, count, NOW, seed)
}

fn problems(report: &BrainAuditReport) -> String {
    report.checks.iter().filter(|c| c.status == AuditStatus::Failed).map(|c| c.detail.clone() + "; ").collect()
}

fn norm(v: &[f32]) -> f32 {
    v.iter().map(|&x| x as f64 * x as f64).sum::<f64>().sqrt() as f32
}

fn dot_with(s: &[f32], engine: &SmartShuffleEngine, id: i64) -> f32 {
    let v = engine.vector_of(id).expect("a library id");
    (0..DIMS).fold(0.0f32, |d, i| d + s[i] * v[i])
}

// ---- Parts 2 and 3 -------------------------------------------------------------------------

#[test]
fn vectors_are_unit_and_standardized() {
    let lib = lib3(5, 30, 5);
    let mut engine = SmartShuffleEngine::new();
    load(&mut engine, &lib);
    for &id in &lib.ids {
        let v = engine.vector_of(id).expect("vector");
        assert!((norm(&v) - 1.0).abs() < 1e-4, "unit length ({})", norm(&v));
    }
    let same_family: f64 = (1..30).map(|i| engine.cosine(lib.ids[0], lib.ids[i]) as f64).sum::<f64>() / 29.0;
    let other_family: f64 = (60..89).map(|i| engine.cosine(lib.ids[0], lib.ids[i]) as f64).sum::<f64>() / 29.0;
    // The point of standardizing: unrelated songs are not all "90% similar".
    assert!(same_family > 0.7, "same family close: {same_family}");
    assert!(other_family < 0.3, "other family far: {other_family}");
}

#[test]
fn session_vector_follows_the_spec() {
    let lib = lib3(3, 20, 4);
    let mut engine = SmartShuffleEngine::new();
    load(&mut engine, &lib);
    let (a, b) = (lib.ids[0], lib.ids[25]);
    let va = engine.vector_of(a).unwrap();
    let vb = engine.vector_of(b).unwrap();

    assert_eq!(engine.on_track_started(a), SessionEvent::Seeded, "first song seeds S_t");
    assert!((dot_with(engine.session_vector(), &engine, a) - 1.0).abs() < 1e-5, "S_t = v_a");

    // Play past half: S = normalize(0.6 vb + 0.4 S).
    engine.on_track_started(b);
    assert_eq!(engine.on_playback_outcome(b, 0.8, NOW, false), SessionEvent::PlayEma);
    let expected: Vec<f32> = (0..DIMS).map(|d| 0.6 * vb[d] + 0.4 * va[d]).collect();
    let n = norm(&expected);
    for d in 0..DIMS {
        assert!((engine.session_vector()[d] - expected[d] / n).abs() < 1e-5, "EMA dim {d}");
    }

    // Fast skip: S = normalize(S - 0.35 v), and it moves away.
    let before = *engine.session_vector();
    assert_eq!(engine.on_playback_outcome(a, 0.1, NOW, false), SessionEvent::FastSkip);
    let expected: Vec<f32> = (0..DIMS).map(|d| before[d] - 0.35 * va[d]).collect();
    let m = norm(&expected);
    for d in 0..DIMS {
        assert!((engine.session_vector()[d] - expected[d] / m).abs() < 1e-5, "repulsion dim {d}");
    }
    assert!(dot_with(engine.session_vector(), &engine, a) < dot_with(&before, &engine, a), "moved away");

    // Between 20% and 50%: S holds.
    let before = *engine.session_vector();
    assert_eq!(engine.on_playback_outcome(b, 0.35, NOW, false), SessionEvent::Neutral);
    assert_eq!(&before, engine.session_vector(), "S_t unchanged");

    // Manual pick: S = normalize(0.85 v + 0.15 S).
    assert_eq!(engine.on_manual_select(a), SessionEvent::ManualAnchor);
    let expected: Vec<f32> = (0..DIMS).map(|d| 0.85 * va[d] + 0.15 * before[d]).collect();
    let k = norm(&expected);
    for d in 0..DIMS {
        assert!((engine.session_vector()[d] - expected[d] / k).abs() < 1e-5, "anchor dim {d}");
    }
}

#[test]
fn skipping_the_seed_still_repels() {
    // S_t == v_skipped: subtracting 0.35 v leaves the direction unchanged, so
    // the brain removes the direction outright (Check 2, part one).
    let lib = lib3(3, 20, 4);
    let mut engine = SmartShuffleEngine::new();
    load(&mut engine, &lib);
    let a = lib.ids[3];
    engine.on_track_started(a);
    assert_eq!(engine.on_playback_outcome(a, 0.05, NOW, true), SessionEvent::FastSkip);
    assert!(dot_with(engine.session_vector(), &engine, a) < 0.0, "S_t now points away");
    let next = lib.ids[30];
    engine.on_track_started(next);
    generate(&mut engine, next, 6, &[a], &[], 1);
    let check = engine.last_audit().check(AuditCheckId::SkipRepulsion);
    assert!(engine.last_audit().after_fast_skip, "audit knows about the skip");
    assert_eq!(check.status, AuditStatus::Healed, "degenerate repulsion reported as healed");
    assert!((norm(engine.session_vector()) - 1.0).abs() < 1e-3, "still unit");
}

#[test]
fn skip_re_ranks_the_tail() {
    let lib = lib3(5, 30, 3);
    let mut engine = SmartShuffleEngine::new();
    load(&mut engine, &lib);
    let mut played = Vec::new();
    for i in 0..4 {
        engine.on_track_started(lib.ids[i]);
        engine.on_playback_outcome(lib.ids[i], 1.0, NOW, true);
        played.push(lib.ids[i]);
    }
    let queue = generate(&mut engine, *played.last().unwrap(), 8, &played, &[], 1);
    // A song like the queue's is fast-skipped.
    let skipped = queue[0];
    engine.on_track_started(skipped);
    engine.on_playback_outcome(skipped, 0.05, NOW, true);
    played.push(skipped);
    let now_playing = queue[1];
    engine.on_track_started(now_playing);
    played.push(now_playing);
    let old_tail = queue[2..].to_vec();
    let next = generate(&mut engine, now_playing, 8, &played, &old_tail, 9);
    let check = engine.last_audit().check(AuditCheckId::SkipRepulsion);
    assert_ne!(check.status, AuditStatus::Failed, "skip check: {}", check.detail);
    let distinct = next.iter().filter(|id| !old_tail.contains(id) && engine.cosine(**id, skipped) < 0.5).count();
    assert!(distinct * 10 >= next.len() * 6, "{distinct} of {} new and distinct", next.len());
    assert!(
        engine.last_traces().iter().any(|t| t.reason == ReasonTag::PostSkipPivot),
        "some track explains itself as a post-skip pivot"
    );
}

#[test]
fn queue_follows_the_vibe_and_never_repeats() {
    let lib = lib3(5, 40, 4);
    let mut engine = SmartShuffleEngine::new();
    load(&mut engine, &lib);
    for i in 0..3 {
        engine.on_track_started(lib.ids[i]);
        engine.on_playback_outcome(lib.ids[i], 0.9, NOW, false);
    }
    let queue = generate(&mut engine, lib.ids[2], 12, &[lib.ids[0], lib.ids[1]], &[], 1);
    assert_eq!(queue.len(), 12, "full queue");
    let unique: BTreeSet<i64> = queue.iter().copied().collect();
    assert_eq!(unique.len(), queue.len(), "no duplicates");
    for i in 0..3 {
        assert!(!unique.contains(&lib.ids[i]), "played songs are not queued again");
    }
    let same_family = queue.iter().filter(|&&id| (id - 100_000) / 1000 == 0).count();
    assert!(same_family >= 9, "queue stays near the session: {same_family} of 12");
    assert!(engine.last_audit().healthy(), "audit healthy: {}", problems(engine.last_audit()));
    for t in engine.last_traces() {
        let sum = t.vibe_similarity_pct + t.markov_habit_pct + t.user_affinity_pct + t.exploration_bonus_pct;
        assert!((sum - 100.0).abs() < 0.5, "percentages sum to {sum}");
        assert!(t.recency_penalty_multiplier > 0.0 && t.recency_penalty_multiplier <= 1.0, "cooldown in (0,1]");
        assert!((0.0..=1.0).contains(&t.transition_smoothness_score), "smoothness in [0,1]");
        assert!(!t.primary_reason_tag.is_empty(), "reason tagged");
    }
}

#[test]
fn same_seed_same_queue() {
    let lib = lib3(4, 30, 3);
    let mut a = SmartShuffleEngine::new();
    let mut b = SmartShuffleEngine::new();
    load(&mut a, &lib);
    load(&mut b, &lib);
    a.on_track_started(lib.ids[5]);
    b.on_track_started(lib.ids[5]);
    assert_eq!(
        generate(&mut a, lib.ids[5], 10, &[], &[], 77),
        generate(&mut b, lib.ids[5], 10, &[], &[], 77),
        "deterministic per seed"
    );
    let mut c = SmartShuffleEngine::new();
    load(&mut c, &lib);
    c.on_track_started(lib.ids[5]);
    let different: BTreeSet<Vec<i64>> = (0..6).map(|s| generate(&mut c, lib.ids[5], 10, &[], &[], s)).collect();
    assert!(different.len() > 1, "different seeds give different queues");
}

#[test]
fn markov_habits_are_learned() {
    let lib = lib3(3, 30, 3);
    let mut engine = SmartShuffleEngine::new();
    load(&mut engine, &lib);
    let (from, to) = (lib.ids[0], lib.ids[70]); // another family entirely
    for _ in 0..4 {
        engine.on_track_started(from);
        engine.on_playback_outcome(from, 1.0, NOW - DAY_MS * 10, false);
        engine.on_track_started(to);
        engine.on_playback_outcome(to, 1.0, NOW - DAY_MS * 10, false);
        assert_eq!(engine.last_learned_transition(), Some((from, to, 1.0, true)), "reported for persistence");
        engine.reset_session();
    }
    assert!(engine.markov_probability(from, to) > 0.5, "habit learned: {}", engine.markov_probability(from, to));
    // Markov, raised, puts the habitual follower in the queue against the vibe.
    engine.set_weights(&Weights { markov: 2.5, ..Weights::default() });
    engine.on_track_started(from);
    let queue = generate(&mut engine, from, 6, &[], &[], 5);
    assert!(queue.contains(&to), "habitual follower queued");
    assert!(
        engine.last_traces().iter().any(|t| t.track_id == to && t.reason == ReasonTag::MarkovFavorite),
        "and explained as a Markov favorite transition"
    );
}

#[test]
fn bandit_learns_zones() {
    let lib = lib3(3, 30, 3);
    let mut engine = SmartShuffleEngine::new();
    load(&mut engine, &lib);
    engine.on_track_started(lib.ids[0]);
    engine.on_playback_outcome(lib.ids[0], 1.0, NOW, false);
    engine.on_track_started(lib.ids[1]);
    engine.on_playback_outcome(lib.ids[1], 1.0, NOW, true);
    let (context, arm, reward) = engine.last_bandit_update().expect("a finished pick rewards its zone");
    assert_eq!(reward, 1.0);
    let (pulls, rewards) = engine.zone_bandit();
    let cell = context as usize * ZONES + arm as usize;
    assert!(pulls[cell] == 1.0 && rewards[cell] == 1.0, "stored");
    let mut restored = SmartShuffleEngine::new();
    load(&mut restored, &lib);
    restored.set_zone_bandit(&pulls, &rewards);
    assert_eq!(restored.zone_bandit().0, pulls, "round trip");
}

#[test]
fn cooldown_keeps_recent_songs_back() {
    let lib = lib3(2, 30, 30);
    let mut engine = SmartShuffleEngine::new();
    load(&mut engine, &lib);
    let n = lib.ids.len();
    let completed = vec![0; n];
    let skipped = vec![0; n];
    let mut last = vec![0i64; n];
    for i in (1..30).step_by(2) {
        last[i] = NOW - 10 * 60 * 1000; // ten minutes ago
    }
    engine.set_track_stats(Some(&completed), Some(&skipped), Some(&last), None, n);
    engine.on_track_started(lib.ids[0]);
    let queue = generate(&mut engine, lib.ids[0], 8, &[], &[], 1);
    let recent = queue.iter().filter(|&&id| (id - 100_000) % 2 == 1 && id < 101_000).count();
    assert!(recent <= 1, "songs heard ten minutes ago stay back: {recent}");
    let reported = engine.last_traces().iter().any(|t| t.recency_penalty_multiplier < 0.5);
    assert!(!reported || recent > 0, "a cooled-down song only shows a low multiplier if it was picked");
}

// ---- Part 4: the brain ---------------------------------------------------------------------

#[test]
fn anti_clumping_spaces_artists() {
    let mut lib = lib3(2, 40, 40); // one artist per family
    for i in (0..40).step_by(4) {
        lib.artists[i] = 7000 + i as i32; // a few others
    }
    let mut engine = SmartShuffleEngine::new();
    load(&mut engine, &lib);
    engine.on_track_started(lib.ids[1]);
    let queue = generate(&mut engine, lib.ids[1], 10, &[], &[], 1);
    let check = engine.last_audit().check(AuditCheckId::AntiClumping).clone();
    assert_ne!(check.status, AuditStatus::Failed, "clumping: {}", check.detail);
    // Wherever the check says spacing s is achievable, the queue honours it.
    let spacing = check.threshold as usize;
    let artist_of: Vec<i32> = queue.iter().map(|&id| lib.artists[lib.index_of(id)]).collect();
    for a in 0..artist_of.len() {
        for b in a + 1..artist_of.len().min(a + spacing) {
            assert_ne!(artist_of[a], artist_of[b], "artist {} at {a} and {b}", artist_of[a]);
        }
    }

    // A one-artist library cannot be spaced: that is not a failure.
    let mono = lib3(1, 20, 20);
    let mut single = SmartShuffleEngine::new();
    load(&mut single, &mono);
    single.on_track_started(mono.ids[0]);
    generate(&mut single, mono.ids[0], 8, &[], &[], 1);
    let check = single.last_audit().check(AuditCheckId::AntiClumping);
    assert_ne!(check.status, AuditStatus::Failed, "one artist: {}", check.detail);
}

#[test]
fn echo_chamber_gets_fresh_tracks() {
    // Thirty near-identical songs and thirty very different ones.
    let mut lib = Library::default();
    let mut rng = Mt19937::new(5);
    for i in 0..60 {
        lib.ids.push(500 + i as i64);
        lib.artists.push(i);
        lib.quality.push(VectorQuality::Real as u8);
        lib.family.push(if i < 30 { 0 } else { 1 });
        for d in 0..DIMS {
            let base = if i < 30 {
                if d < 3 {
                    1.0
                } else {
                    0.1
                }
            } else if (7..10).contains(&d) {
                1.0
            } else {
                0.3
            };
            lib.raw.push(base * rng.uniform_f32(0.999, 1.001));
        }
    }
    let mut engine = SmartShuffleEngine::new();
    load(&mut engine, &lib);
    engine.on_track_started(500);
    let queue = generate(&mut engine, 500, 10, &[], &[], 1);
    let check = engine.last_audit().check(AuditCheckId::EchoChamber);
    assert_eq!(check.status, AuditStatus::Healed, "echo chamber healed: {}", check.detail);
    assert!(engine.explore_boost() > 1.4, "w_explore boosted x{}", engine.explore_boost());
    assert!(
        engine.last_audit().average_pairwise_similarity <= 0.97,
        "avg {}",
        engine.last_audit().average_pairwise_similarity
    );
    assert!(queue.iter().any(|&id| id >= 530), "fresh tracks injected");
}

#[cfg(feature = "testing")]
#[test]
fn integrity_heals_a_poisoned_session() {
    let lib = lib3(3, 20, 4);
    let mut engine = SmartShuffleEngine::new();
    load(&mut engine, &lib);
    engine.on_track_started(lib.ids[0]);
    engine.poison_session_for_test(f32::NAN);
    let queue = generate(&mut engine, lib.ids[0], 6, &[], &[], 1);
    let check = engine.last_audit().check(AuditCheckId::VectorIntegrity);
    assert_eq!(check.status, AuditStatus::Healed, "NaN session healed: {}", check.detail);
    assert_eq!(queue.len(), 6, "and the queue still filled");
    assert!((norm(engine.session_vector()) - 1.0).abs() < 1e-3, "unit again");
    engine.poison_session_for_test(3.0); // finite, wrong length
    generate(&mut engine, lib.ids[0], 6, &[], &[], 1);
    assert_eq!(engine.last_audit().check(AuditCheckId::VectorIntegrity).status, AuditStatus::Healed, "renormalized");
    assert!((engine.last_audit().session_norm - 1.0).abs() < 1e-3, "norm {}", engine.last_audit().session_norm);
}

#[test]
fn cold_start_is_reported() {
    let mut lib = lib3(2, 20, 4);
    // Ten songs only known from the old analysis (some dims unknown), five not at all.
    for i in 0..10 {
        lib.quality[i] = VectorQuality::Fallback as u8;
        for d in BANDS..2 * BANDS {
            lib.raw[i * DIMS + d] = f32::NAN;
        }
    }
    for i in 10..15 {
        lib.quality[i] = VectorQuality::None as u8;
        for d in 0..DIMS {
            lib.raw[i * DIMS + d] = f32::NAN;
        }
    }
    let mut engine = SmartShuffleEngine::new();
    load(&mut engine, &lib);
    engine.on_track_started(lib.ids[20]);
    generate(&mut engine, lib.ids[20], 8, &[], &[], 1);
    let audit = engine.last_audit();
    assert_eq!((audit.real_tracks, audit.fallback_tracks, audit.placeholder_tracks), (25, 10, 5), "coverage");
    assert!((audit.real_vector_pct - 62.5).abs() < 0.01, "real {}%", audit.real_vector_pct);
    let v = engine.vector_of(lib.ids[12]).unwrap();
    assert!((norm(&v) - 1.0).abs() < 1e-4, "placeholders are still unit vectors");
    assert_eq!(engine.on_track_started(lib.ids[12]), SessionEvent::None, "a placeholder never steers the session");
}

#[test]
fn sonic_adventure_travels() {
    let lib = lib3(5, 30, 3);
    let mut engine = SmartShuffleEngine::new();
    load(&mut engine, &lib);
    let from = lib.ids[0]; // family 0
    let to = lib.ids[4 * 30]; // family 4
    let mut path: Vec<i64> = Vec::new();
    let mut previous = from;
    let mut last_toward = -2.0f32;
    let mut forward = 0;
    for step in 1..=10 {
        let id = engine
            .journey_step(from, to, step as f32 / 10.0, previous, &path, NOW, step as u64)
            .unwrap_or_else(|| panic!("step {step}"));
        assert!(!path.contains(&id), "no repeats");
        let toward = engine.cosine(id, to);
        forward += (toward >= last_toward - 0.15) as i32;
        last_toward = toward;
        path.push(id);
        previous = id;
        assert!(
            engine.last_traces().len() == 1 && engine.last_traces()[0].reason == ReasonTag::SonicAdventure,
            "journey step explained"
        );
    }
    assert!(engine.cosine(path[0], from) > engine.cosine(path[0], to), "starts near the origin");
    assert!(engine.cosine(*path.last().unwrap(), to) > 0.6, "arrives near the destination");
    assert!(forward >= 8, "moves steadily toward it: {forward} of 10 steps");
}

#[test]
fn small_and_empty_libraries() {
    let mut empty = SmartShuffleEngine::new();
    assert!(generate(&mut empty, 1, 5, &[], &[], 1).is_empty(), "empty library: empty queue");
    assert!(empty.last_audit().healthy(), "and a healthy audit");

    let lib = lib3(1, 3, 1);
    let mut tiny = SmartShuffleEngine::new();
    load(&mut tiny, &lib);
    tiny.on_track_started(lib.ids[0]);
    let queue = generate(&mut tiny, lib.ids[0], 10, &[lib.ids[1]], &[], 1);
    assert_eq!(queue.len(), 2, "only what exists; excluded songs return when nothing else is left");
    assert_eq!(tiny.journey_step(lib.ids[0], 424_242, 0.5, lib.ids[0], &[], NOW, 1), None, "unknown destination");
}

#[test]
fn steering_biases_the_queue() {
    let lib = lib3(2, 30, 3);
    let mut engine = SmartShuffleEngine::new();
    load(&mut engine, &lib);
    let mut steer = vec![0.0f32; lib.ids.len()];
    for i in (0..30).step_by(3) {
        steer[i] = 1.0; // an "energy target" only every third song meets
    }
    engine.set_steering(Some(&steer), 1.5);
    engine.on_track_started(lib.ids[1]);
    let queue = generate(&mut engine, lib.ids[1], 6, &[], &[], 1);
    let steered = queue.iter().filter(|&&id| (id - 100_000) % 3 == 0 && id < 101_000).count();
    assert!(steered >= 4, "steering pulls its songs forward: {steered} of 6");
    engine.set_steering(None, 0.0);
    assert_eq!(engine.weights().steering, 0.0, "and clears");
}

#[test]
fn fast_on_a_large_library() {
    let lib = lib3(20, 1000, 8); // 20,000 songs
    let mut engine = SmartShuffleEngine::new();
    load(&mut engine, &lib);
    engine.on_track_started(lib.ids[0]);
    engine.on_playback_outcome(lib.ids[0], 1.0, NOW, false);
    generate(&mut engine, lib.ids[0], 10, &[], &[], 1); // warm up
    let start = Instant::now();
    const RUNS: u64 = 20;
    for i in 0..RUNS {
        generate(&mut engine, lib.ids[0], 10, &[], &[], i);
    }
    let ms = start.elapsed().as_secs_f64() * 1000.0 / RUNS as f64;
    println!("20k-song queue of 10 with full audit: {ms:.2} ms");
    if !cfg!(debug_assertions) {
        assert!(ms < 25.0, "{ms:.2} ms per queue");
    }
}

// ---- Mix din piesa asta (generate_radio) ---------------------------------------------------

/// Six sonic families, each in two styles (A: a slow pulse, B: a busy one, the
/// same bands), an artist every four songs. Ids: 200000 + family * 1000 + style * 100 + i.
struct StyledLibrary {
    lib: Library,
    style: Vec<i32>,
}

fn make_styled_library() -> StyledLibrary {
    let (families, per_style) = (6, 20);
    let mut out = StyledLibrary { lib: Library::default(), style: Vec::new() };
    let mut rng = Mt19937::new(7);
    let mut artist = 0;
    for f in 0..families {
        for s in 0..2 {
            for i in 0..per_style {
                if i % 4 == 0 {
                    artist += 1;
                }
                out.lib.ids.push(200_000 + f as i64 * 1000 + s as i64 * 100 + i as i64);
                out.lib.family.push(f);
                out.style.push(s);
                out.lib.artists.push(artist);
                out.lib.quality.push(VectorQuality::Real as u8);
                for k in 0..BANDS {
                    let centre = (f * 9) as f32 / (families - 1).max(1) as f32;
                    let d = k as f32 - centre;
                    out.lib.raw.push(rng.uniform_f32(0.9, 1.1) * (-0.5f32 * d * d / 2.0).exp());
                }
                for _ in 0..BANDS {
                    out.lib.raw.push(rng.uniform_f32(0.9, 1.1) * 0.3);
                }
                // The styles differ in pulse only: about 0.70 cosine within a style, 0.57 across.
                let macro_ = [0.3 + 0.1 * (f % 3) as f32, 0.5, 0.4, if s == 0 { 0.2 } else { 0.8 }];
                for m in macro_ {
                    out.lib.raw.push(rng.uniform_f32(0.9, 1.1) * m);
                }
            }
        }
    }
    out
}

/// The shared test history: a listener who knows family 0-2 well (style A
/// mostly), has a few favourites, and habitually plays song X (family 4)
/// after songs of family 2.
fn load_history(engine: &mut SmartShuffleEngine, s: &StyledLibrary, habit_target: i64) {
    let lib = &s.lib;
    let n = lib.ids.len();
    let (mut completed, mut skipped) = (vec![0; n], vec![0; n]);
    let mut last = vec![0i64; n];
    let mut favorite = vec![0u8; n];
    for i in 0..n {
        let local = lib.ids[i] % 100;
        if lib.family[i] <= 2 && s.style[i] == 0 && local < 12 {
            completed[i] = 3 + (local % 5) as i32;
            last[i] = NOW - DAY_MS * (5 + local);
        }
        if lib.family[i] <= 2 && s.style[i] == 1 && local < 4 {
            completed[i] = 1;
            skipped[i] = 2;
            last[i] = NOW - DAY_MS * 20;
        }
        if local == 1 && s.style[i] == 0 {
            favorite[i] = 1;
        }
        if lib.ids[i] == habit_target {
            completed[i] = 6;
            last[i] = NOW - DAY_MS * 3;
        }
    }
    engine.set_track_stats(Some(&completed), Some(&skipped), Some(&last), Some(&favorite), n);
    let mut from = Vec::new();
    for i in 0..n {
        if lib.family[i] == 2 && s.style[i] == 0 && lib.ids[i] % 100 < 10 {
            from.push(lib.ids[i]);
        }
    }
    let to = vec![habit_target; from.len()];
    let weight = vec![4.0f32; from.len()];
    engine.add_transitions(&from, &to, Some(&weight));
}

fn radio(
    engine: &mut SmartShuffleEngine,
    seed: i64,
    current: i64,
    count: i32,
    prefix: &[i64],
    exclude: &[i64],
    rng_seed: u64,
) -> Vec<i64> {
    engine.generate_radio(seed, current, prefix, exclude, count, NOW, rng_seed)
}

/// The smallest number of songs between two by the same artist (count + 1 when none repeat).
fn min_artist_gap(lib: &Library, ids: &[i64]) -> usize {
    let mut gap = ids.len() + 1;
    for a in 0..ids.len() {
        for b in a + 1..ids.len() {
            if lib.artists[lib.index_of(ids[a])] == lib.artists[lib.index_of(ids[b])] {
                gap = gap.min(b - a);
            }
        }
    }
    gap
}

#[test]
fn radio_stays_on_the_seed() {
    let s = make_styled_library();
    let mut engine = SmartShuffleEngine::new();
    load(&mut engine, &s.lib);
    let seed = 202_005; // family 2, style A
    engine.on_track_started(seed);
    engine.on_manual_select(seed);
    let mix = radio(&mut engine, seed, seed, 10, &[202_017], &[202_008], 1);
    assert_eq!(mix.len(), 10, "a full batch");
    for &id in &mix {
        assert!(id != seed && id != 202_017 && id != 202_008, "never the seed, the queue or the excluded");
    }
    let family = mix.iter().filter(|&&id| s.lib.family[s.lib.index_of(id)] == 2).count();
    assert!(family >= 8, "sounds like the seed: {family} of 10 from its family");
    assert_eq!(mix.iter().collect::<BTreeSet<_>>().len(), mix.len(), "no duplicates");
    let mut with_queue = vec![seed, 202_017];
    with_queue.extend(&mix);
    assert!(min_artist_gap(&s.lib, &with_queue) >= 3, "artists spaced");
    let report = engine.last_radio();
    assert!(report.from_seed > 0 && report.merged >= report.from_seed, "stage 1 ran");
    assert_eq!(report.history_confidence, 0.0, "no history: a mix by sound");
    assert_eq!(engine.last_traces().len(), mix.len(), "every song explained");
    assert!(engine.last_audit().healthy(), "audit healthy: {}", problems(engine.last_audit()));
}

#[test]
fn radio_works_on_tiny_and_unmeasured_libraries() {
    // Airplane mode is the only mode the engine has: it never touches a network.
    let mut empty = SmartShuffleEngine::new();
    assert!(empty.generate_radio(1, 1, &[], &[], 4, NOW, 1).is_empty(), "empty library: nothing");

    let three = lib3(1, 3, 1);
    let mut tiny = SmartShuffleEngine::new();
    load(&mut tiny, &three);
    tiny.on_track_started(three.ids[0]);
    assert_eq!(radio(&mut tiny, three.ids[0], three.ids[0], 10, &[], &[], 1).len(), 2, "three songs: the other two");

    // Nothing measured at all: every song is NONE.
    let mut unmeasured = lib3(2, 10, 2);
    unmeasured.quality.fill(VectorQuality::None as u8);
    let mut cold = SmartShuffleEngine::new();
    load(&mut cold, &unmeasured);
    cold.on_track_started(unmeasured.ids[0]);
    let cold_mix = radio(&mut cold, unmeasured.ids[0], unmeasured.ids[0], 6, &[], &[], 1);
    assert_eq!(cold_mix.len(), 6, "unmeasured library still mixes");
    assert!(
        !cold.last_traces().is_empty() && cold.last_traces().iter().all(|t| t.reason == ReasonTag::ColdStart),
        "and says the songs are not measured yet"
    );

    // Half measured, half approximated from the older analysis (FALLBACK): both mix.
    let mut mixed = lib3(3, 20, 4);
    for i in (0..mixed.quality.len()).step_by(2) {
        mixed.quality[i] = VectorQuality::Fallback as u8;
    }
    let mut half = SmartShuffleEngine::new();
    load(&mut half, &mixed);
    half.on_track_started(mixed.ids[2]);
    let half_mix = radio(&mut half, mixed.ids[2], mixed.ids[2], 8, &[], &[], 1);
    let fallback =
        half_mix.iter().filter(|&&id| mixed.quality[mixed.index_of(id)] == VectorQuality::Fallback as u8).count();
    let same_family = half_mix.iter().filter(|&&id| mixed.family[mixed.index_of(id)] == 0).count();
    assert!(half_mix.len() == 8 && same_family >= 6, "approximate vectors still find the family: {same_family} of 8");
    assert!(fallback >= 1, "approximated songs take part: {fallback}");

    // A seed with no vector at all: the session and the habits carry the mix.
    let mut seedless = lib3(2, 15, 3);
    seedless.quality[0] = VectorQuality::None as u8;
    let mut no_seed = SmartShuffleEngine::new();
    load(&mut no_seed, &seedless);
    no_seed.on_track_started(seedless.ids[1]);
    let seedless_mix = radio(&mut no_seed, seedless.ids[0], seedless.ids[1], 5, &[], &[], 1);
    assert_eq!(seedless_mix.len(), 5, "an unmeasured seed still mixes");
    assert!(!no_seed.last_radio().seed_measured, "and reports it");
}

/// Plays `batch`: songs of `family`/`style` are fast-skipped, the rest finished.
fn listen(
    engine: &mut SmartShuffleEngine,
    s: &StyledLibrary,
    batch: &[i64],
    family: i32,
    style: i32,
    played: &mut Vec<i64>,
) -> i64 {
    let mut last = -1;
    for &id in batch {
        let i = s.lib.index_of(id);
        engine.on_track_started(id);
        let disliked = s.lib.family[i] == family && s.style[i] == style;
        engine.on_playback_outcome(id, if disliked { 0.05 } else { 0.95 }, NOW, true);
        played.push(id);
        last = id;
    }
    last
}

fn count_style(s: &StyledLibrary, ids: &[i64], family: i32, style: i32) -> usize {
    ids.iter()
        .filter(|&&id| {
            let i = s.lib.index_of(id);
            s.lib.family[i] == family && s.style[i] == style
        })
        .count()
}

#[test]
fn radio_learns_from_fast_skips() {
    let s = make_styled_library();
    let mut engine = SmartShuffleEngine::new();
    load(&mut engine, &s.lib);
    let seed = 203_010; // family 3, style A
    engine.on_track_started(seed);
    engine.on_manual_select(seed);
    let mut played = vec![seed];
    let (mut before, mut after) = (0, 0);
    for round in 0..3u64 {
        let first = radio(&mut engine, seed, *played.last().unwrap(), 10, &[], &played.clone(), 11 + round);
        before += count_style(&s, &first, 3, 1);
        listen(&mut engine, &s, &first, 3, 1, &mut played);
        let next = radio(&mut engine, seed, *played.last().unwrap(), 10, &[], &played.clone(), 21 + round);
        after += count_style(&s, &next, 3, 1);
        listen(&mut engine, &s, &next, 3, 1, &mut played);
    }
    assert!(engine.feedback_count() > 0, "feedback remembered");
    assert!(after < before || before == 0, "fast skips push the skipped style out: {before} before, {after} after");
}

#[test]
fn radio_skip_counts_more_in_a_similar_context() {
    // The same three fast skips (style B of family 1, each right after a song
    // of family 4) keep style B out more when the batch also follows a
    // family 4 song than when it follows a family 5 one.
    let s = make_styled_library();
    let style_b = |anchor: i64| {
        let mut b = 0;
        for r in 1..=8u64 {
            let mut engine = SmartShuffleEngine::new();
            load(&mut engine, &s.lib);
            let seed = 201_002;
            engine.on_track_started(seed);
            engine.on_manual_select(seed);
            for k in 0..3 {
                engine.on_track_started(204_000 + k);
                engine.on_playback_outcome(204_000 + k, 0.95, NOW, false);
                engine.on_track_started(201_100 + k);
                engine.on_playback_outcome(201_100 + k, 0.05, NOW, false);
            }
            b += count_style(&s, &radio(&mut engine, seed, anchor, 30, &[], &[], r), 1, 1);
        }
        b
    };
    let same_context = style_b(204_010); // follows family 4, as the skips did
    let other_context = style_b(205_010); // follows family 5
    assert!(same_context < other_context, "skips weigh more in their own context: {same_context} vs {other_context}");
}

#[test]
fn radio_gates_songs_new_to_the_listener() {
    // A listener who knows family 2 well: all of it played except four songs
    // of each style, plus other families never played.
    let s = make_styled_library();
    let n = s.lib.ids.len();
    let mut completed = vec![0; n];
    let skipped = vec![0; n];
    let mut last = vec![0i64; n];
    let favorite = vec![0u8; n];
    for i in 0..n {
        if s.lib.family[i] == 2 && s.lib.ids[i] % 100 < 16 {
            completed[i] = 4;
            last[i] = NOW - DAY_MS * 10;
        }
    }
    let mut engine = SmartShuffleEngine::new();
    load(&mut engine, &s.lib);
    engine.set_track_stats(Some(&completed), Some(&skipped), Some(&last), Some(&favorite), n);
    let seed = 202_003;
    engine.on_track_started(seed);
    engine.on_manual_select(seed);
    let mut new_songs = 0;
    for r in 1..=5u64 {
        radio(&mut engine, seed, seed, 12, &[], &[], r);
        let report = engine.last_radio().clone();
        assert!(report.history_confidence > 0.5, "a listener with history ({})", report.history_confidence);
        assert!((3..=4).contains(&report.new_cap), "about a quarter of 12 may be new ({})", report.new_cap);
        assert!(report.new_placed <= report.new_cap, "at most the cap");
        new_songs += report.new_placed;
        for t in engine.last_traces() {
            let i = s.lib.index_of(t.track_id);
            if completed[i] == 0 {
                assert_eq!(s.lib.family[i], 2, "a new song placed sounds right ({})", t.cosine_similarity);
            }
        }
        assert_eq!(report.filled, 0, "no fill needed on a library this size");
    }
    assert!(new_songs >= 1, "but new songs do get in: {new_songs} over five batches");

    // A seed in a family never played: its neighbourhood is all new, so the mix may be too.
    let unexplored = 205_004;
    engine.on_manual_select(unexplored);
    let mix = radio(&mut engine, unexplored, unexplored, 12, &[], &[], 9);
    let family = mix.iter().filter(|&&id| s.lib.family[s.lib.index_of(id)] == 5).count();
    assert!(engine.last_radio().new_cap >= 9, "an unexplored seed lifts the cap ({})", engine.last_radio().new_cap);
    assert!(family >= 10, "and the mix stays on it, not on old favourites: {family} of 12");
}

#[test]
fn radio_follows_local_habits() {
    let s = make_styled_library();
    let mut engine = SmartShuffleEngine::new();
    load(&mut engine, &s.lib);
    let habit_target = 204_015; // family 4: sounds nothing like the seed
    load_history(&mut engine, &s, habit_target);
    let seed = 202_011; // family 2, not itself a source of the habit
    engine.on_track_started(seed);
    engine.on_manual_select(seed);
    let mut found = 0;
    for r in 1..=5u64 {
        let mix = radio(&mut engine, seed, seed, 10, &[], &[], r);
        assert!(engine.last_radio().from_habits > 0, "co-listening retrieval found songs");
        found += mix.contains(&habit_target) as i32;
    }
    assert!(found >= 3, "what follows songs like the seed joins the mix: {found} of 5 batches");
}

/// The comparison the feature is judged by: Smart Shuffle (generate_queue)
/// and the mix (generate_radio) on the same library, history, seeds and
/// simulated listener (who fast-skips the busy style of the seed's family and
/// finishes everything else). Checks the mix is at least as relevant.
#[test]
fn radio_compared_with_smart_shuffle() {
    let s = make_styled_library();
    let habit_target = 204_015;
    #[derive(Default)]
    struct Totals {
        relevance5: f64,
        gap: f64,
        skipped_before: f64,
        skipped_after: f64,
        runs: i32,
    }
    let mut totals = [Totals::default(), Totals::default()];
    for family in 0..6 {
        for variant in 0..3 {
            let seed = 200_000 + family as i64 * 1000 + 5 + variant;
            for kind in 0..2 {
                let mut engine = SmartShuffleEngine::new();
                load(&mut engine, &s.lib);
                load_history(&mut engine, &s, habit_target);
                engine.on_track_started(seed);
                engine.on_manual_select(seed);
                let rng = 100 + (family * 10 + variant as i32) as u64;
                let mut played = vec![seed];
                let batch = |engine: &mut SmartShuffleEngine, current: i64, r: u64, played: &[i64]| {
                    if kind == 0 {
                        engine.generate_queue(current, &[], played, &[], 10, NOW, r)
                    } else {
                        engine.generate_radio(seed, current, &[], played, 10, NOW, r)
                    }
                };
                let t = &mut totals[kind];
                let first = batch(&mut engine, seed, rng, &played);
                let seed_vec = engine.vector_of(seed).unwrap();
                for &id in first.iter().take(5) {
                    t.relevance5 += dot_with(&seed_vec, &engine, id) as f64 / 5.0;
                }
                let mut with_seed = vec![seed];
                with_seed.extend(&first);
                t.gap += min_artist_gap(&s.lib, &with_seed).min(11) as f64;
                t.skipped_before += count_style(&s, &first, family, 1) as f64;
                let last = listen(&mut engine, &s, &first, family, 1, &mut played);
                let second = batch(&mut engine, last, rng + 1, &played);
                t.skipped_after += count_style(&s, &second, family, 1) as f64;
                t.runs += 1;
            }
        }
    }
    let [smart, mix] = &totals;
    println!(
        "Smart Shuffle cos@5 {:.2}, gap {:.1}; Mix cos@5 {:.2}, gap {:.1}, skipped {:.1} -> {:.1}",
        smart.relevance5 / smart.runs as f64,
        smart.gap / smart.runs as f64,
        mix.relevance5 / mix.runs as f64,
        mix.gap / mix.runs as f64,
        mix.skipped_before / mix.runs as f64,
        mix.skipped_after / mix.runs as f64
    );
    assert!(mix.relevance5 >= smart.relevance5, "the mix is at least as close to the seed");
    assert!(mix.gap / mix.runs as f64 >= 3.0, "the mix spaces artists");
    assert!(mix.skipped_after < mix.skipped_before || mix.skipped_before == 0.0, "the mix answers the skips");
}

#[test]
fn radio_fast_on_a_large_library() {
    let lib = lib3(20, 1000, 8); // 20,000 songs
    let mut engine = SmartShuffleEngine::new();
    load(&mut engine, &lib);
    engine.on_track_started(lib.ids[0]);
    engine.on_manual_select(lib.ids[0]);
    radio(&mut engine, lib.ids[0], lib.ids[0], 10, &[], &[], 1); // warm up (Check 4's first full pass)
    const RUNS: u64 = 20;
    let start = Instant::now();
    for i in 0..RUNS {
        radio(&mut engine, lib.ids[0], lib.ids[0], 10, &[], &[], i);
    }
    let ms = start.elapsed().as_secs_f64() * 1000.0 / RUNS as f64;
    println!("20k-song mix batch of 10 with full audit: {ms:.2} ms");
    if !cfg!(debug_assertions) {
        assert!(ms < 25.0, "{ms:.2} ms per mix batch");
    }
}
