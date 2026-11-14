//! The engine's public vocabulary: qualities, events, audit reports, traces
//! and the scoring knobs. Integer values match the Kotlin enums (by ordinal)
//! and the JNI layout.

pub const DIMS: usize = 24;
pub const BANDS: usize = 10;
/// The bandit's contexts and arms: the octave band a vector leans on most.
pub const ZONES: usize = BANDS;
pub const AUDIT_CHECKS: usize = 5;

/// Band centres, Hz: 31.25 * 2^k.
pub const BAND_CENTERS_HZ: [f32; BANDS] = [31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0];

/// How much the engine knows about a track.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
#[repr(u8)]
pub enum VectorQuality {
    /// Nothing measured: a deterministic placeholder direction.
    #[default]
    None = 0,
    /// Approximated from the older analysis (cold-start fallback).
    Fallback = 1,
    /// A real 24-D measurement.
    Real = 2,
}

impl VectorQuality {
    /// From a stored byte; values above 2 count as Real (the C++ clamped them).
    pub fn from_u8(v: u8) -> Self {
        match v {
            0 => VectorQuality::None,
            1 => VectorQuality::Fallback,
            _ => VectorQuality::Real,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(i32)]
pub enum SessionEvent {
    None = 0,
    /// S_t started from this song.
    Seeded = 1,
    /// Played past half: S_t = normalize(0.60 v + 0.40 S_t-1).
    PlayEma = 2,
    /// Skipped before 20%: S_t = normalize(S_t-1 - 0.35 v). Steers the next picks.
    FastSkip = 3,
    /// Chosen by hand: S_t = normalize(0.85 v + 0.15 S_t-1).
    ManualAnchor = 4,
    /// Between 20% and 50%, or a song without a usable vector: S_t holds.
    Neutral = 5,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
#[repr(i32)]
pub enum AuditStatus {
    #[default]
    Pass = 0,
    /// The check failed and the brain repaired it.
    Healed = 1,
    /// The check failed and could not be fully repaired (the library is too small).
    Failed = 2,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(usize)]
pub enum AuditCheckId {
    AntiClumping = 0,
    SkipRepulsion = 1,
    EchoChamber = 2,
    VectorIntegrity = 3,
    TransitionJerk = 4,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct AuditCheck {
    pub status: AuditStatus,
    /// The measured value (see BrainAuditReport for each check's metric).
    pub metric: f32,
    pub threshold: f32,
    /// Repairs made: swaps, replacements, renormalizations.
    pub corrections: i32,
    pub detail: String,
}

/**
 * The five checks, run on every generated or re-ranked queue.
 *
 *   AntiClumping     metric: smallest same-artist gap; threshold: the spacing enforced
 *   SkipRepulsion    metric: share of the tail replaced with distinct tracks after a
 *                    fast skip; threshold 0.60
 *   EchoChamber      metric: average pairwise cosine of the queue; threshold 0.97
 *   VectorIntegrity  metric: ||S_t||; threshold: the 0.001 tolerance
 *   TransitionJerk   metric: largest ||v_a - v_b|| between neighbours; threshold:
 *                    the harshness limit
 */
#[derive(Clone, Debug, PartialEq)]
pub struct BrainAuditReport {
    pub checks: [AuditCheck; AUDIT_CHECKS],
    pub generation: u64,
    pub after_fast_skip: bool,
    pub average_pairwise_similarity: f32,
    /// Shannon entropy of the queue's artists and of its sonic zones, bits.
    pub artist_entropy_bits: f32,
    pub zone_entropy_bits: f32,
    pub session_norm: f32,
    pub non_finite_values: i32,
    /// Share of the library with a real 24-D vector, 0..100.
    pub real_vector_pct: f32,
    pub real_tracks: i32,
    pub fallback_tracks: i32,
    pub placeholder_tracks: i32,
    /// Current multiplier on the exploration weight (1 = none).
    pub explore_boost: f32,
    pub elapsed_ms: f64,
}

impl Default for BrainAuditReport {
    fn default() -> Self {
        BrainAuditReport {
            checks: Default::default(),
            generation: 0,
            after_fast_skip: false,
            average_pairwise_similarity: 0.0,
            artist_entropy_bits: 0.0,
            zone_entropy_bits: 0.0,
            session_norm: 0.0,
            non_finite_values: 0,
            real_vector_pct: 0.0,
            real_tracks: 0,
            fallback_tracks: 0,
            placeholder_tracks: 0,
            explore_boost: 1.0,
            elapsed_ms: 0.0,
        }
    }
}

impl BrainAuditReport {
    pub fn check(&self, id: AuditCheckId) -> &AuditCheck {
        &self.checks[id as usize]
    }
    pub fn healthy(&self) -> bool {
        self.failed_count() == 0
    }
    pub fn healed_count(&self) -> i32 {
        self.checks.iter().filter(|c| c.status == AuditStatus::Healed).count() as i32
    }
    pub fn failed_count(&self) -> i32 {
        self.checks.iter().filter(|c| c.status == AuditStatus::Failed).count() as i32
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
#[repr(i32)]
pub enum ReasonTag {
    #[default]
    VibeMatch = 0,
    MarkovFavorite = 1,
    BanditGem = 2,
    PostSkipPivot = 3,
    ProvenFavorite = 4,
    SonicAdventure = 5,
    ColdStart = 6,
}

/// Why the brain placed a track where it did. Percentages sum to 100.
#[derive(Clone, Debug, PartialEq)]
pub struct TrackDecisionTrace {
    pub track_id: i64,
    pub raw_score: f32,
    pub vibe_similarity_pct: f32,
    pub markov_habit_pct: f32,
    pub user_affinity_pct: f32,
    pub exploration_bonus_pct: f32,
    /// RecencyCooldown(i): 1 = no cooldown.
    pub recency_penalty_multiplier: f32,
    /// 1 - ||v_prev - v||/2: 1 = seamless, 0 = opposite.
    pub transition_smoothness_score: f32,
    /// Cosine with S_t (or with the journey's target).
    pub cosine_similarity: f32,
    pub zone: i32,
    pub quality: VectorQuality,
    pub reason: ReasonTag,
    pub primary_reason_tag: String,
}

impl Default for TrackDecisionTrace {
    fn default() -> Self {
        TrackDecisionTrace {
            track_id: -1,
            raw_score: 0.0,
            vibe_similarity_pct: 0.0,
            markov_habit_pct: 0.0,
            user_affinity_pct: 0.0,
            exploration_bonus_pct: 0.0,
            recency_penalty_multiplier: 1.0,
            transition_smoothness_score: 1.0,
            cosine_similarity: 0.0,
            zone: -1,
            quality: VectorQuality::None,
            reason: ReasonTag::VibeMatch,
            primary_reason_tag: String::new(),
        }
    }
}

/// The scorer's knobs.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Weights {
    pub similarity: f32,
    pub markov: f32,
    pub affinity: f32,
    pub explore: f32,
    /// Optional per-track steering (energy target, mood); folded into "vibe".
    pub steering: f32,
    /// Efraimidis-Spirakis softness: weight = exp((score - best) / temperature).
    pub temperature: f32,
    pub min_artist_spacing: i32,
    /// ||v_a - v_b|| above this is a harsh jump (unit vectors: cosine below ~0.2).
    pub jerk_threshold: f32,
    /// Above this average pairwise cosine the queue is an echo chamber.
    pub echo_threshold: f32,
}

impl Default for Weights {
    fn default() -> Self {
        Weights {
            similarity: 1.00,
            markov: 0.30,
            affinity: 0.30,
            explore: 0.20,
            steering: 0.0,
            temperature: 0.12,
            min_artist_spacing: 3,
            jerk_threshold: 1.25,
            echo_threshold: 0.97,
        }
    }
}

/**
 * The mix's scoring knobs: Harmony's own starting choices, not values from
 * YouTube Music (which publishes no weights). Picked so that, with no
 * history, the score is mostly sound, and feedback can never outweigh
 * sounding like the seed. See SMART-SHUFFLE.md, "Mix din piesa asta".
 */
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct RadioWeights {
    /// Sounding like the seed (0.7) and the session S_t (0.3). A strong local
    /// habit vouches for a song too: relevance is at least habit_vouch x the
    /// co-listening score (scaled by how much history there is).
    pub relevance: f32,
    pub habit_vouch: f32,
    /// Fitting after the song before: smoothness, and the local habit as history allows (up to 0.6).
    pub transition: f32,
    /// Learned preference: this session's feedback, then the long-term play record.
    pub preference: f32,
    /// Few plays: a small, gated bonus (controlled exploration).
    pub novelty: f32,
    /// Share of the seed in relevance (the rest is S_t).
    pub seed_share: f32,
    /// A song new to the listener needs at least this cosine with the seed and S_t blend.
    pub new_song_gate: f32,
    /// At most this share of a batch may be new to the listener, or the share
    /// of new songs among the seed's closest matches when that is higher.
    pub new_song_share: f32,
    /// Feedback memory: each older event counts this much less.
    pub feedback_decay: f32,
    /// A fast skip weighs this much more than a full play.
    pub skip_weight: f32,
    /// Score multiplier exp(skip_penalty * min(0, feedback)): a lookalike of a skip in a similar context sinks.
    pub skip_penalty: f32,
    /// Efraimidis-Spirakis softness for the batch (lower than Smart Shuffle's: a radio stays on topic).
    pub temperature: f32,
    /// Ordering: the cost of an artist already in the batch (variety), per earlier song of theirs.
    pub artist_repeat_cost: f32,
}

impl Default for RadioWeights {
    fn default() -> Self {
        RadioWeights {
            relevance: 0.50,
            habit_vouch: 0.80,
            transition: 0.20,
            preference: 0.20,
            novelty: 0.10,
            seed_share: 0.70,
            new_song_gate: 0.55,
            new_song_share: 0.25,
            feedback_decay: 0.85,
            skip_weight: 1.5,
            skip_penalty: 1.2,
            temperature: 0.06,
            artist_repeat_cost: 0.25,
        }
    }
}

/// What each stage of the last mix batch did (counts are songs).
#[derive(Clone, Debug, Default, PartialEq)]
pub struct RadioReport {
    pub generation: u64,
    pub from_seed: i32,
    pub from_session: i32,
    pub from_habits: i32,
    pub from_rare: i32,
    /// From the taste memory (what online radios put with the seed or its artist).
    pub from_taste: i32,
    /// After the union, duplicates removed.
    pub merged: i32,
    /// Songs new to the listener turned away by the relevance gate.
    pub gated_out: i32,
    pub new_placed: i32,
    /// The most new songs this batch could take (the exploration cap).
    pub new_cap: i32,
    /// Placed from outside the gate because nothing better was left (tiny libraries).
    pub filled: i32,
    /// 0..1: how much the play record and the session feedback are trusted.
    pub history_confidence: f32,
    pub session_confidence: f32,
    pub seed_measured: bool,
    pub elapsed_ms: f64,
}

/// A float as C's printf("%.Nf") writes it (Rust's `{:.N}` rounds the same
/// way; only NaN and infinity are spelled differently).
pub(crate) fn cf(v: f64, precision: usize) -> String {
    if v.is_nan() {
        if v.is_sign_negative() {
            "-nan".into()
        } else {
            "nan".into()
        }
    } else if v.is_infinite() {
        if v < 0.0 {
            "-inf".into()
        } else {
            "inf".into()
        }
    } else {
        format!("{v:.precision$}")
    }
}
