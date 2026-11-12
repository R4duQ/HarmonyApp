//! MobileEqEngine: Harmony's 10-band equalizer, live spectral Auto, output
//! protection and spectrum analyzer, in one real-time-safe object. Every stage
//! reacts to what the music sounds like right now; nothing reads tags.
//!
//! Signal path, per frame, in 32-bit float:
//!
//! ```text
//!   input ─┬─▶ [1] device high-pass ─▶ live preamp ─▶ [3] 10 biquads ─▶
//!          │     ─▶ [4] soft-knee saturator ─▶ lookahead limiter ─▶ output ─▶ analyzer ring (FFT)
//!          │                  ▲                       ▲
//!          └─▶ [2] live analysis bank (10 octave band-passes)
//!                ├─ fast band energy (50 ms)   ─▶ the frequency-weighted preamp
//!                ├─ slow tonal envelope (1.2 s) ─▶ Auto's live offsets (±4.5 dB vs the output's target curve)
//!                ├─ broadband RMS (100 ms)      ─▶ freeze below -42 dBFS
//!                └─ sub-bass / sibilance (8 ms / 120 ms) ─▶ bass and harshness tamer
//! ```
//!
//! Threads (the same contract as the C++ engine this replaces):
//!  - One audio thread calls configure(), reset(), process*() and drain().
//!    Those calls never allocate or make system calls. Their state sits
//!    behind a mutex that only the audio thread takes, so it is never
//!    contended (one atomic compare-and-swap) and a caller that broke the
//!    contract would wait rather than corrupt it.
//!  - Any thread may call the set*() controls. They only store atomics; the
//!    audio thread picks them up at the start of its next 32-frame block.
//!  - One UI thread calls poll_dsp_telemetry() at 60 fps. The audio thread
//!    hands it samples through a lock-free single-producer/single-consumer ring
//!    and never waits for it; the FFT and the bar ballistics run on the
//!    polling thread, under their own (equally uncontended) mutex.
//!
//! Numeric note: each operation keeps the C++ original's float/double type, so
//! the output matches the C++ engine's sample for sample.

use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU32, Ordering};
use std::sync::{Mutex, MutexGuard, OnceLock};
use std::time::Instant;

use crate::denormals::FlushDenormals;
use crate::design::{
    design_band, design_band_pass, design_high_pass, design_low_pass, pade_tanh, sclamp, smax, smin, Biquad,
    BUTTERWORTH_Q, PI,
};
use crate::fft::Fft;
use crate::ring::{self, SpscRing};
use crate::{
    OutputDevice, SampleFormat, BAND_FREQUENCIES, FFT_SIZE, MAX_BARS, MAX_CHANNELS, MAX_GAIN_DB, MAX_SAMPLE_RATE,
    MIN_BARS, MIN_GAIN_DB, MIN_SAMPLE_RATE, NUM_BANDS,
};

// Numerical safety. Every power, ratio and logarithm has a floor, input is
// bounded, and anything non-finite that still turns up is healed in place.
const EPSILON: f32 = 1e-12; // floor under every power before a division or log10
const INPUT_LIMIT: f32 = 16.0; // +24 dBFS: no real PCM goes past this; a corrupt frame might

// Stage 1.
const BLUETOOTH_PAD_DB: f32 = -0.3; // room for lossy codecs' inter-sample peaks
const MAX_USER_PREAMP_DB: f32 = 12.0;
const MIN_USER_PREAMP_DB: f32 = -24.0;
const MAX_LEVEL_GAIN_DB: f32 = 15.0; // ReplayGain lifts quiet records up to this
const MIN_LEVEL_GAIN_DB: f32 = -30.0;
const COEFFICIENT_SMOOTHING_SECONDS: f32 = 0.008;
const GAIN_SMOOTHING_SECONDS: f32 = 0.015;
// Live preamp: how much headroom the curve needs for the energy actually present.
const BAND_ENERGY_SECONDS: f32 = 0.05; // per-band energy behind the weighting
const PREAMP_ATTACK_SECONDS: f32 = 0.005; // headroom is taken fast...
const PREAMP_RELEASE_SECONDS: f32 = 0.30; // ...and given back slowly, so nothing pumps

// Stage 2: the live spectral analyser and Auto.
#[allow(clippy::approx_constant)] // the C++ value, not SQRT_2: same coefficients
const OCTAVE_Q: f64 = 1.41421356237; // the analysis bank: one octave per band
const TONAL_SECONDS: f32 = 1.2; // slow tonal envelope (RMS integration)
const GATE_SECONDS: f32 = 0.1; // broadband RMS for the silence gate
const FREEZE_BELOW_DBFS: f32 = -42.0; // quieter than this: tonal adaptation freezes
const OFFSET_GLIDE_SECONDS: f32 = 1.0; // live offsets glide towards their target
const AUTO_STRENGTH: f32 = 0.75; // share of the difference from the target evened out
const MAX_AUTO_BOOST_DB: f32 = 4.5;
const MAX_AUTO_CUT_DB: f32 = 4.5;
const ABSENT_BELOW_TARGET_DB: f32 = 24.0; // a band this far under target is absent, not thin: no boost

// What each band may do: lift what is missing at the extremes (sub-bass, air),
// take out what clutters (mud, harshness), and anchor the middle.
#[derive(Clone, Copy)]
enum AutoRule {
    Boost,
    Cut,
    Hold,
}
const AUTO_RULE: [AutoRule; NUM_BANDS] = [
    AutoRule::Boost, AutoRule::Boost, // 31, 62 Hz: sub-bass
    AutoRule::Cut, AutoRule::Cut,     // 125, 250 Hz: boxy, muddy
    AutoRule::Hold, AutoRule::Hold,   // 500 Hz, 1 kHz: the anchor
    AutoRule::Cut, AutoRule::Cut,     // 2, 4 kHz: harsh, piercing
    AutoRule::Boost, AutoRule::Boost, // 8, 16 kHz: air, brilliance
];

// Fast transient envelopes: dynamic bass and the harshness tamer.
const SUB_BASS_CUTOFF_HZ: f32 = 80.0; // 31-62 Hz and their kicks
const SIBILANCE_HZ: f64 = 5657.0; // 4-8 kHz, one octave around its centre
const TRANSIENT_ATTACK_SECONDS: f32 = 0.008;
const TRANSIENT_RELEASE_SECONDS: f32 = 0.12;
const DUCK_THRESHOLD_DB: f32 = -5.0; // sub-bass peaks above this pull the bass boost back
const HARSH_THRESHOLD_DB: f32 = -18.0; // sibilance above this pulls the 4/8 kHz boost back
const DUCK_RANGE_DB: f32 = 6.0; // full ducking this far above the threshold
const MAX_DUCK_DB: f32 = 4.0;

// Stage 4.
const SATURATOR_KNEE_DB: f32 = -1.8;
const CEILING_DB: f32 = -0.15;
const BLUETOOTH_CEILING_DB: f32 = -0.4;
const USB_CEILING_DB: f32 = -0.05;
const LOOKAHEAD_SECONDS: f32 = 0.002;
const LIMITER_RELEASE_SECONDS: f32 = 0.08;

// Analyzer.
const MIN_BAR_HZ: f32 = 20.0;
const MAX_BAR_HZ: f32 = 20000.0;
const FLOOR_DB: f32 = -72.0;
const ATTACK_SECONDS: f32 = 0.010;
const DECAY_DB_PER_SECOND: f32 = 90.0;
const PEAK_HOLD_SECONDS: f32 = 0.5;
const PEAK_GRAVITY: f32 = 2.5; // bar heights per second squared
const STALE_SECONDS: f32 = 0.25; // no audio for this long: the bars fall
const MAX_LAG_SECONDS: f32 = 0.75; // never trail the audio thread by more

const BLOCK: usize = 32; // frames between control updates
const MAX_LOOKAHEAD: usize = (MAX_SAMPLE_RATE * 2 / 1000 + 1) as usize;
const PENDING_CAPACITY: u32 = 65536; // power of two

#[inline]
fn db_to_linear(db: f32) -> f32 {
    10.0f32.powf(db / 20.0)
}

/// x, or 0 when it is NaN or infinite.
#[inline]
fn sanitize_f32(x: f32) -> f32 {
    if x.is_finite() {
        x
    } else {
        0.0
    }
}

#[inline]
fn sanitize_f64(x: f64) -> f64 {
    if x.is_finite() {
        x
    } else {
        0.0
    }
}

/// 10·log10(p) with p floored at EPSILON: never -inf, never NaN.
#[inline]
fn power_db(p: f64) -> f32 {
    (10.0 * smax(if p.is_finite() { p } else { 0.0 }, EPSILON as f64).log10()) as f32
}

#[inline]
fn clamp_gain_db(db: f32) -> f32 {
    if !db.is_finite() {
        return 0.0;
    }
    sclamp(db, MIN_GAIN_DB, MAX_GAIN_DB)
}

#[inline]
fn smoothing_alpha(seconds: f32, rate: f64) -> f32 {
    (1.0 - (-1.0 / (seconds as f64 * rate)).exp()) as f32
}

/// A steady clock in nanoseconds, never 0 (0 means "never polled").
fn now_nanos() -> i64 {
    static ANCHOR: OnceLock<Instant> = OnceLock::new();
    ANCHOR.get_or_init(Instant::now).elapsed().as_nanos() as i64 + 1_000_000_000
}

#[derive(Default)]
struct AtomicF32(AtomicU32);

impl AtomicF32 {
    fn new(v: f32) -> Self {
        AtomicF32(AtomicU32::new(v.to_bits()))
    }
    #[inline]
    fn load(&self, order: Ordering) -> f32 {
        f32::from_bits(self.0.load(order))
    }
    #[inline]
    fn store(&self, v: f32, order: Ordering) {
        self.0.store(v.to_bits(), order)
    }
}

#[derive(Clone, Copy, Default)]
struct Smoothed {
    current: Biquad,
    target: Biquad,
    settling: bool,
}

/// What only the audio thread touches.
struct AudioState {
    sample_rate: i32,
    channels: usize,
    seen_control_generation: u32,
    active_device: OutputDevice,
    coef_alpha: f64, // per-sample coefficient smoothing
    gain_alpha: f32, // per-sample preamp smoothing

    high_pass: Smoothed,
    bands: [Smoothed; NUM_BANDS],
    hp_state: [[f64; 2]; MAX_CHANNELS],
    band_state: [[[f64; 2]; MAX_CHANNELS]; NUM_BANDS],
    designed_db: [f32; NUM_BANDS],    // what each band is designed at now
    designed_power: [f32; NUM_BANDS], // the same, as power
    preamp_linear: f32,
    preamp_target_linear: f32,
    device_pad_db: f32,

    // Stage 2: the live analysis bank (one octave band-pass per EQ band, on the input).
    analysis_filter: [Biquad; NUM_BANDS],
    analysis_state: [[f64; 2]; NUM_BANDS],
    block_power: [f64; NUM_BANDS], // summed over the current 32-frame block
    block_frames: i32,
    band_energy: [f64; NUM_BANDS], // ~50 ms, weights the live preamp
    tonal_power: [f64; NUM_BANDS], // ~1.2 s, the slow tonal envelope
    tonal_warm: bool,
    broadband_power: f32, // ~100 ms, the silence gate
    frozen: bool,
    live_offset_db: [f32; NUM_BANDS],
    band_energy_alpha: f32,
    gate_alpha: f32,
    tonal_alpha: f64,
    offset_alpha: f32,
    // Stage 1: live headroom.
    auto_headroom_db: f32,
    preamp_attack: f32,
    preamp_release: f32,
    // Stage 2c: fast transient envelopes (8 ms attack, 120 ms release).
    sub_bass_low_pass: Biquad,
    sub_bass_state: [f64; 2],
    sub_bass_envelope: f32,
    sibilance_band_pass: Biquad,
    sibilance_state: [f64; 2],
    sibilance_envelope: f32,
    env_attack: f32,
    env_release: f32,
    duck_release: f32,
    duck_db: f32,
    harsh_duck_db: f32,

    // Saturator and limiter.
    saturator_knee: f32,
    ceiling: f32,
    lookahead: usize,       // window length L
    lookahead_delay: usize, // L - 1
    delay: Box<[f32]>,      // MAX_LOOKAHEAD * MAX_CHANNELS
    delay_pos: usize,
    min_value: Box<[f32]>,
    min_index: Box<[i64]>,
    min_head: usize,
    min_count: usize,
    box_: Box<[f32]>,
    box_sum: f64,
    box_pos: usize,
    release_gain: f32,
    release_alpha: f32,
    frame_index: i64,
    prime_frames: usize, // outputs still to skip after a reset (the lookahead filling up)
}

/// What only the polling (UI) thread touches.
struct PollerState {
    fft: Fft,
    window: Box<[f32]>,
    history: Box<[f32]>,
    history_pos: usize,
    fft_re: Box<[f32]>,
    fft_im: Box<[f32]>,
    pop_scratch: Box<[f32]>,
    // The audio thread runs ahead of what is audible (the sink's buffer) and
    // delivers in bursts. The poller keeps what it took in a FIFO and reads it
    // at the playback rate, so the bars move with the sound, not before it.
    pending: Box<[f32]>,
    pending_head: u32,
    pending_count: u32,
    stale_seconds: f32,
    seen_analyzer_epoch: u32,
    mapped_sample_rate: i32,
    mapped_bars: usize,
    bar_low_bin: [i32; MAX_BARS],
    bar_high_bin: [i32; MAX_BARS],
    bar_center_bin: [f32; MAX_BARS],
    bar_level: [f32; MAX_BARS],
    peak_level: [f32; MAX_BARS],
    peak_hold: [f32; MAX_BARS],
    peak_velocity: [f32; MAX_BARS],
    last_poll_nanos: i64,
}

pub struct Engine {
    // Shared controls.
    user_gain_db: [AtomicF32; NUM_BANDS],
    control_generation: AtomicU32,
    eq_enabled: AtomicBool,
    auto_mode: AtomicBool,
    user_preamp_db: AtomicF32,
    level_gain_db: AtomicF32,
    device: AtomicI32,
    analyzer_enabled: AtomicBool,
    bar_count: AtomicI32,
    analyzer_sample_rate: AtomicI32,
    analyzer_epoch: AtomicU32,
    target_preamp_db: AtomicF32,
    bass_duck_db: AtomicF32,
    // Telemetry out.
    effective_out_db: [AtomicF32; NUM_BANDS],
    offset_out_db: [AtomicF32; NUM_BANDS],
    live_auto_preamp_out_db: AtomicF32,
    harsh_duck_out_db: AtomicF32,
    frozen_out: AtomicBool,
    heal_count: AtomicU32,
    sanitized_samples: AtomicU32,
    // Mirrors of audio-thread values any thread may read without the lock.
    sample_rate_out: AtomicI32,
    channels_out: AtomicI32,
    latency_out: AtomicI32,

    ring: SpscRing,
    audio: Mutex<AudioState>,
    poller: Mutex<PollerState>,
}

/// Locks `m`, recovering the state if a panic poisoned it (every audio and
/// poller path self-heals, so the data is always usable).
fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

impl Default for Engine {
    fn default() -> Self {
        Self::new()
    }
}

impl Engine {
    pub fn new() -> Self {
        // Periodic Hann: the right window for spectral analysis of a stream.
        let window: Box<[f32]> =
            (0..FFT_SIZE).map(|n| (0.5 - 0.5 * (2.0 * PI * n as f64 / FFT_SIZE as f64).cos()) as f32).collect();
        let audio = AudioState {
            sample_rate: 48000,
            channels: 2,
            seen_control_generation: 0,
            active_device: OutputDevice::PhoneSpeaker,
            coef_alpha: 0.0,
            gain_alpha: 0.0,
            high_pass: Smoothed::default(),
            bands: [Smoothed::default(); NUM_BANDS],
            hp_state: [[0.0; 2]; MAX_CHANNELS],
            band_state: [[[0.0; 2]; MAX_CHANNELS]; NUM_BANDS],
            designed_db: [0.0; NUM_BANDS],
            designed_power: [1.0; NUM_BANDS],
            preamp_linear: 1.0,
            preamp_target_linear: 1.0,
            device_pad_db: 0.0,
            analysis_filter: [Biquad::default(); NUM_BANDS],
            analysis_state: [[0.0; 2]; NUM_BANDS],
            block_power: [0.0; NUM_BANDS],
            block_frames: 0,
            band_energy: [0.0; NUM_BANDS],
            tonal_power: [0.0; NUM_BANDS],
            tonal_warm: false,
            broadband_power: 0.0,
            frozen: true,
            live_offset_db: [0.0; NUM_BANDS],
            band_energy_alpha: 0.0,
            gate_alpha: 0.0,
            tonal_alpha: 0.0,
            offset_alpha: 0.0,
            auto_headroom_db: 0.0,
            preamp_attack: 0.0,
            preamp_release: 0.0,
            sub_bass_low_pass: Biquad::default(),
            sub_bass_state: [0.0; 2],
            sub_bass_envelope: 0.0,
            sibilance_band_pass: Biquad::default(),
            sibilance_state: [0.0; 2],
            sibilance_envelope: 0.0,
            env_attack: 0.0,
            env_release: 0.0,
            duck_release: 0.0,
            duck_db: 0.0,
            harsh_duck_db: 0.0,
            saturator_knee: 0.0,
            ceiling: 1.0,
            lookahead: 1,
            lookahead_delay: 0,
            delay: vec![0.0; MAX_LOOKAHEAD * MAX_CHANNELS].into_boxed_slice(),
            delay_pos: 0,
            min_value: vec![0.0; MAX_LOOKAHEAD].into_boxed_slice(),
            min_index: vec![0; MAX_LOOKAHEAD].into_boxed_slice(),
            min_head: 0,
            min_count: 0,
            box_: vec![0.0; MAX_LOOKAHEAD].into_boxed_slice(),
            box_sum: 0.0,
            box_pos: 0,
            release_gain: 1.0,
            release_alpha: 0.0,
            frame_index: 0,
            prime_frames: 0,
        };
        let poller = PollerState {
            fft: Fft::new(FFT_SIZE),
            window,
            history: vec![0.0; FFT_SIZE].into_boxed_slice(),
            history_pos: 0,
            fft_re: vec![0.0; FFT_SIZE].into_boxed_slice(),
            fft_im: vec![0.0; FFT_SIZE].into_boxed_slice(),
            pop_scratch: vec![0.0; ring::CAPACITY as usize].into_boxed_slice(),
            pending: vec![0.0; PENDING_CAPACITY as usize].into_boxed_slice(),
            pending_head: 0,
            pending_count: 0,
            stale_seconds: 0.0,
            seen_analyzer_epoch: 0,
            mapped_sample_rate: 0,
            mapped_bars: 0,
            bar_low_bin: [0; MAX_BARS],
            bar_high_bin: [0; MAX_BARS],
            bar_center_bin: [0.0; MAX_BARS],
            bar_level: [0.0; MAX_BARS],
            peak_level: [0.0; MAX_BARS],
            peak_hold: [0.0; MAX_BARS],
            peak_velocity: [0.0; MAX_BARS],
            last_poll_nanos: 0,
        };
        let engine = Engine {
            user_gain_db: Default::default(),
            control_generation: AtomicU32::new(1),
            eq_enabled: AtomicBool::new(true),
            auto_mode: AtomicBool::new(false),
            user_preamp_db: AtomicF32::new(0.0),
            level_gain_db: AtomicF32::new(0.0),
            device: AtomicI32::new(OutputDevice::PhoneSpeaker as i32),
            analyzer_enabled: AtomicBool::new(true),
            bar_count: AtomicI32::new(32),
            analyzer_sample_rate: AtomicI32::new(48000),
            analyzer_epoch: AtomicU32::new(0),
            target_preamp_db: AtomicF32::new(0.0),
            bass_duck_db: AtomicF32::new(0.0),
            effective_out_db: Default::default(),
            offset_out_db: Default::default(),
            live_auto_preamp_out_db: AtomicF32::new(0.0),
            harsh_duck_out_db: AtomicF32::new(0.0),
            frozen_out: AtomicBool::new(true),
            heal_count: AtomicU32::new(0),
            sanitized_samples: AtomicU32::new(0),
            sample_rate_out: AtomicI32::new(48000),
            channels_out: AtomicI32::new(2),
            latency_out: AtomicI32::new(0),
            ring: SpscRing::default(),
            audio: Mutex::new(audio),
            poller: Mutex::new(poller),
        };
        engine.configure(48000, 2);
        engine
    }

    // ---- Audio thread ------------------------------------------------------------

    /// Prepares for interleaved audio at `sample_rate` with `channels`.
    /// Recomputes every coefficient, time constant and the lookahead length
    /// for the new rate and clears all state. Allocation-free (buffers are
    /// sized for 384 kHz). Returns false for an unsupported format.
    pub fn configure(&self, sample_rate: i32, channels: i32) -> bool {
        if !(MIN_SAMPLE_RATE..=MAX_SAMPLE_RATE).contains(&sample_rate) {
            return false;
        }
        if channels < 1 || channels as usize > MAX_CHANNELS {
            return false;
        }
        let mut a = lock(&self.audio);
        a.sample_rate = sample_rate;
        a.channels = channels as usize;
        let sr = sample_rate as f64;

        // C++: kCoefficientSmoothingSeconds * sampleRate is a float product.
        a.coef_alpha = 1.0 - (-1.0 / (COEFFICIENT_SMOOTHING_SECONDS * sample_rate as f32) as f64).exp();
        a.gain_alpha = smoothing_alpha(GAIN_SMOOTHING_SECONDS, sr);
        a.env_attack = smoothing_alpha(TRANSIENT_ATTACK_SECONDS, sr);
        a.env_release = smoothing_alpha(TRANSIENT_RELEASE_SECONDS, sr);
        a.band_energy_alpha = smoothing_alpha(BAND_ENERGY_SECONDS, sr);
        a.gate_alpha = smoothing_alpha(GATE_SECONDS, sr);
        let block_rate = sr / BLOCK as f64;
        a.tonal_alpha = smoothing_alpha(TONAL_SECONDS, block_rate) as f64;
        a.offset_alpha = smoothing_alpha(OFFSET_GLIDE_SECONDS, block_rate);
        a.preamp_attack = smoothing_alpha(PREAMP_ATTACK_SECONDS, block_rate);
        a.preamp_release = smoothing_alpha(PREAMP_RELEASE_SECONDS, block_rate);
        a.duck_release = smoothing_alpha(TRANSIENT_RELEASE_SECONDS, block_rate);
        a.release_alpha = smoothing_alpha(LIMITER_RELEASE_SECONDS, sr);
        a.lookahead = ((LOOKAHEAD_SECONDS * sample_rate as f32).round() as i64).clamp(1, MAX_LOOKAHEAD as i64) as usize;
        a.lookahead_delay = a.lookahead - 1;
        a.sub_bass_low_pass = design_low_pass(sr, SUB_BASS_CUTOFF_HZ as f64, BUTTERWORTH_Q);
        a.sibilance_band_pass = design_band_pass(sr, SIBILANCE_HZ, OCTAVE_Q);
        a.saturator_knee = db_to_linear(SATURATOR_KNEE_DB);
        // The analysis bank follows the rate; what it has learned (in power, rate-independent) is kept.
        for b in 0..NUM_BANDS {
            a.analysis_filter[b] = design_band_pass(sr, BAND_FREQUENCIES[b] as f64, OCTAVE_Q);
        }
        a.analysis_state = [[0.0; 2]; NUM_BANDS];
        a.block_power = [0.0; NUM_BANDS];

        // Every coefficient depends on the rate: redesign all of them now, at
        // full precision, and start from them directly (the state is cleared anyway).
        a.active_device = OutputDevice::from_i32(self.device.load(Ordering::Acquire));
        a.design_high_pass_for_device();
        a.high_pass.current = a.high_pass.target;
        a.high_pass.settling = false;
        a.seen_control_generation = self.control_generation.load(Ordering::Acquire).wrapping_sub(1); // force a pass
        a.designed_db = [1000.0; NUM_BANDS]; // force every band
        self.update_controls(&mut a);
        for band in a.bands.iter_mut() {
            band.current = band.target;
            band.settling = false;
        }
        a.preamp_linear = a.preamp_target_linear;

        self.analyzer_sample_rate.store(sample_rate, Ordering::Release);
        self.analyzer_epoch.fetch_add(1, Ordering::AcqRel);
        a.reset();
        self.sample_rate_out.store(sample_rate, Ordering::Relaxed);
        self.channels_out.store(channels, Ordering::Relaxed);
        self.latency_out.store(a.lookahead_delay as i32, Ordering::Relaxed);
        drop(a);
        self.bass_duck_db.store(0.0, Ordering::Relaxed);
        true
    }

    /// Clears filter, envelope and limiter state, as after a seek.
    pub fn reset(&self) {
        lock(&self.audio).reset();
        self.bass_duck_db.store(0.0, Ordering::Relaxed);
    }

    /// Frames the lookahead holds back (2 ms at the current rate, minus one).
    pub fn latency_frames(&self) -> i32 {
        self.latency_out.load(Ordering::Relaxed)
    }

    pub fn sample_rate(&self) -> i32 {
        self.sample_rate_out.load(Ordering::Relaxed)
    }

    pub fn channels(&self) -> i32 {
        self.channels_out.load(Ordering::Relaxed)
    }

    /// Converts `frames` interleaved frames of `format` from `input` to float,
    /// processes them and writes the result to `output`. Returns the number of
    /// frames written. Right after configure() or reset() the limiter's
    /// lookahead holds back latency_frames() frames, so the first calls return
    /// fewer frames than they take; drain() returns them at the end of the
    /// stream. Output stays sample-aligned with input: nothing is inserted or lost.
    ///
    /// `input` must hold `frames * channels` samples of `format`, `output`
    /// room for as many floats.
    pub fn process(&self, input: &[u8], format: SampleFormat, frames: usize, output: &mut [f32]) -> usize {
        self.process_with(frames, output, |a, out| a.convert(input, format, out))
    }

    /// process() for float audio already in `io` (the C++ engine's in-place call).
    pub fn process_in_place(&self, io: &mut [f32], frames: usize) -> usize {
        self.process_with(frames, io, |a, out| a.sanitize_in_place(out))
    }

    fn process_with(
        &self,
        frames: usize,
        output: &mut [f32],
        convert: impl FnOnce(&AudioState, &mut [f32]) -> u32,
    ) -> usize {
        if frames == 0 {
            return 0;
        }
        let _flush = FlushDenormals::new();
        let mut a = lock(&self.audio);
        let channels = a.channels;
        let samples = frames * channels;
        let output = &mut output[..samples];

        // Convert to float into the output buffer first.
        let repaired = convert(&a, output);
        if repaired > 0 {
            self.sanitized_samples.fetch_add(repaired, Ordering::Relaxed);
        }

        let mut produced = 0usize;
        let mut start = 0usize;
        while start < frames {
            let count = BLOCK.min(frames - start);
            self.update_controls(&mut a);
            let block = start * channels;
            let written = self.process_block(&mut a, &mut output[block..block + count * channels], count);
            if written > 0 && start != produced {
                output.copy_within(block..block + written * channels, produced * channels);
            }
            self.push_analyzer(&a, &output[produced * channels..(produced + written) * channels], written);
            produced += written;
            start += BLOCK;
        }
        produced
    }

    /// Writes up to `max_frames` of the audio still held by the lookahead;
    /// returns the count. Leaves the engine reset for the next stream.
    pub fn drain(&self, output: &mut [f32], max_frames: usize) -> usize {
        let _flush = FlushDenormals::new();
        let mut a = lock(&self.audio);
        let channels = a.channels;
        let silence = [0.0f32; MAX_CHANNELS];
        let mut out = [0.0f32; MAX_CHANNELS];
        let mut produced = 0usize;
        // Push silence through the limiter until the real audio it still holds is out.
        // The delay line holds lookahead_delay frames; after a short stream some of
        // those are the initial zeros, which the prime counter skips.
        let mut iterations = a.lookahead_delay;
        while iterations > 0 && produced < max_frames {
            iterations -= 1;
            a.limit_frame(&silence, &mut out);
            if a.prime_frames > 0 {
                a.prime_frames -= 1;
                continue;
            }
            output[produced * channels..(produced + 1) * channels].copy_from_slice(&out[..channels]);
            produced += 1;
        }
        let mut start = 0;
        while start < produced {
            let count = BLOCK.min(produced - start);
            self.push_analyzer(&a, &output[start * channels..(start + count) * channels], count);
            start += BLOCK;
        }
        // The stream has ended: start the next one from a clean, primed state.
        a.reset();
        drop(a);
        self.bass_duck_db.store(0.0, Ordering::Relaxed);
        produced
    }

    fn update_controls(&self, a: &mut AudioState) {
        let generation = self.control_generation.load(Ordering::Acquire);
        if generation != a.seen_control_generation {
            a.seen_control_generation = generation;
        }

        let device = OutputDevice::from_i32(self.device.load(Ordering::Acquire));
        if device != a.active_device {
            a.active_device = device;
            a.design_high_pass_for_device();
        }

        let eq_on = self.eq_enabled.load(Ordering::Relaxed);
        let auto_on = eq_on && self.auto_mode.load(Ordering::Relaxed);

        // Stage 2a: the slow tonal envelope (about 1.2 s), frozen below -42 dBFS
        // so silence and quiet intros never teach it anything.
        if a.block_frames > 0 {
            a.broadband_power = sanitize_f32(a.broadband_power);
            let gate_db = power_db(a.broadband_power as f64);
            a.frozen = gate_db < FREEZE_BELOW_DBFS;
            if !a.frozen {
                for b in 0..NUM_BANDS {
                    let mean = sanitize_f64(a.block_power[b] / smax(1, a.block_frames) as f64);
                    a.tonal_power[b] = sanitize_f64(a.tonal_power[b] + (mean - a.tonal_power[b]) * a.tonal_alpha);
                }
                a.tonal_warm = true;
            }
            a.block_power = [0.0; NUM_BANDS];
            a.block_frames = 0;
        }

        // Stage 2b: the live offsets glide towards what the measured balance asks for.
        let mut offset_target = [0.0f32; NUM_BANDS];
        if auto_on && a.tonal_warm {
            offset_target = auto_offset_target_db(a.active_device, &a.tonal_power);
        }
        if !(auto_on && a.frozen) {
            for b in 0..NUM_BANDS {
                a.live_offset_db[b] = sanitize_f32(
                    a.live_offset_db[b] + (sanitize_f32(offset_target[b]) - a.live_offset_db[b]) * a.offset_alpha,
                );
            }
        }

        let mut desired = [0.0f32; NUM_BANDS];
        for b in 0..NUM_BANDS {
            let user = clamp_gain_db(self.user_gain_db[b].load(Ordering::Relaxed));
            desired[b] = if eq_on { clamp_gain_db(user + a.live_offset_db[b]) } else { 0.0 };
        }

        // Stage 2c: the fast transient envelopes. Sub-bass peaks above -5 dBFS
        // pull the 31/62 Hz boost back during the kick; sibilance pulls the 4/8 kHz
        // boost back. Both only ever reduce a boost, by at most 4 dB.
        let duck_for = |envelope: f32, threshold_db: f32| {
            let level_db = 20.0f32 * smax(sanitize_f32(envelope), 1e-9f32).log10();
            sclamp((level_db - threshold_db) / DUCK_RANGE_DB, 0.0, 1.0) * MAX_DUCK_DB
        };
        a.sub_bass_envelope = sanitize_f32(a.sub_bass_envelope);
        a.sibilance_envelope = sanitize_f32(a.sibilance_envelope);
        let bass_target = if eq_on { duck_for(a.sub_bass_envelope, DUCK_THRESHOLD_DB) } else { 0.0 };
        let harsh_target = if eq_on { duck_for(a.sibilance_envelope, HARSH_THRESHOLD_DB) } else { 0.0 };
        // Fast in (the 8 ms coefficient smoothing shapes the attack), 120 ms out.
        a.duck_db =
            if bass_target > a.duck_db { bass_target } else { a.duck_db + (bass_target - a.duck_db) * a.duck_release };
        a.harsh_duck_db = if harsh_target > a.harsh_duck_db {
            harsh_target
        } else {
            a.harsh_duck_db + (harsh_target - a.harsh_duck_db) * a.duck_release
        };
        self.bass_duck_db.store(a.duck_db, Ordering::Relaxed);
        self.harsh_duck_out_db.store(a.harsh_duck_db, Ordering::Relaxed);

        for b in 0..NUM_BANDS {
            let mut gain = desired[b];
            if b < 2 {
                gain -= smin(a.duck_db, smax(0.0, gain));
            }
            if b == 7 || b == 8 {
                gain -= smin(a.harsh_duck_db, smax(0.0, gain));
            }
            if (gain - a.designed_db[b]).abs() > 0.005 {
                a.bands[b].target = design_band(b, a.sample_rate as f64, gain as f64);
                a.bands[b].settling = true;
                a.designed_db[b] = gain;
                a.designed_power[b] = 10.0f32.powf(gain / 10.0);
            }
            self.effective_out_db[b].store(gain, Ordering::Relaxed);
            self.offset_out_db[b].store(a.live_offset_db[b], Ordering::Relaxed);
        }
        self.frozen_out.store(a.frozen, Ordering::Relaxed);

        // Stage 1: the live frequency-weighted preamp. The headroom the curve
        // needs is the boost weighted by the energy each band carries right now:
        // +10 dB at 62 Hz costs 10 dB of preamp on a bass line and almost nothing
        // on a voice. Taken fast, given back slowly.
        let mut need_db = 0.0f32;
        if eq_on {
            let (mut sum_in, mut sum_out) = (0.0f64, 0.0f64);
            for b in 0..NUM_BANDS {
                a.band_energy[b] = sanitize_f64(a.band_energy[b]);
                let energy = a.band_energy[b] + EPSILON as f64;
                sum_in += energy;
                sum_out += energy * a.designed_power[b] as f64;
            }
            need_db = smax(0.0, sanitize_f32(power_db(sum_out) - power_db(sum_in)));
        }
        let rate = if need_db > a.auto_headroom_db { a.preamp_attack } else { a.preamp_release };
        a.auto_headroom_db = sanitize_f32(a.auto_headroom_db + (need_db - a.auto_headroom_db) * rate);
        let user = self.user_preamp_db.load(Ordering::Relaxed);
        let user = if user.is_finite() { sclamp(user, MIN_USER_PREAMP_DB, MAX_USER_PREAMP_DB) } else { 0.0 };
        let level = self.level_gain_db.load(Ordering::Relaxed);
        let level = if level.is_finite() { sclamp(level, MIN_LEVEL_GAIN_DB, MAX_LEVEL_GAIN_DB) } else { 0.0 };
        let total = -a.auto_headroom_db + a.device_pad_db + user + level;
        self.target_preamp_db.store(total, Ordering::Relaxed);
        self.live_auto_preamp_out_db.store(-a.auto_headroom_db + a.device_pad_db + user, Ordering::Relaxed);
        a.preamp_target_linear = db_to_linear(total);
    }

    /// Runs `frames` frames of `io` (interleaved, `channels` wide) through the
    /// chain. Output is compacted in place (it never overtakes the input) while
    /// the lookahead primes. Returns the frames written.
    fn process_block(&self, a: &mut AudioState, io: &mut [f32], frames: usize) -> usize {
        let channels = a.channels;
        let mut written = 0usize;
        let mut frame = [0.0f32; MAX_CHANNELS];
        let mut out = [0.0f32; MAX_CHANNELS];
        let mut input = [0.0f32; MAX_CHANNELS];
        let knee = a.saturator_knee;
        let headroom = 1.0f32 - knee;
        for i in 0..frames {
            // Stage 1: de-zippering. Coefficients and the preamp glide per sample.
            let coef_alpha = a.coef_alpha;
            if a.high_pass.settling {
                let target = a.high_pass.target;
                a.high_pass.current.approach(&target, coef_alpha);
            }
            for band in a.bands.iter_mut() {
                if band.settling {
                    let target = band.target;
                    band.current.approach(&target, coef_alpha);
                }
            }
            a.preamp_linear += (a.preamp_target_linear - a.preamp_linear) * a.gain_alpha;

            input[..channels].copy_from_slice(&io[i * channels..(i + 1) * channels]);
            let input = &input[..channels];
            let mut mono = 0.0f64;
            // Stage 2: the live analysis bank hears the music as it comes in,
            // before the high-pass and the EQ, one octave band-pass per EQ band.
            let mut raw = 0.0f64;
            for &x in input {
                raw += x as f64;
            }
            raw /= channels as f64;
            for b in 0..NUM_BANDS {
                let filter = a.analysis_filter[b];
                let y = filter.run(&mut a.analysis_state[b], raw);
                let power = y * y;
                a.block_power[b] += power;
                a.band_energy[b] += (power - a.band_energy[b]) * a.band_energy_alpha as f64;
            }
            a.broadband_power += ((raw * raw) as f32 - a.broadband_power) * a.gate_alpha;
            a.block_frames += 1;
            let sibilance_filter = a.sibilance_band_pass;
            let sibilance = sibilance_filter.run(&mut a.sibilance_state, raw).abs() as f32;
            let rate = if sibilance > a.sibilance_envelope { a.env_attack } else { a.env_release };
            a.sibilance_envelope += (sibilance - a.sibilance_envelope) * rate;
            for c in 0..channels {
                let hp = a.high_pass.current;
                let mut y = hp.run(&mut a.hp_state[c], input[c] as f64);
                mono += y;
                y *= a.preamp_linear as f64;
                // Stage 2: ten cascaded biquads.
                for b in 0..NUM_BANDS {
                    let filter = a.bands[b].current;
                    y = filter.run(&mut a.band_state[b][c], y);
                }
                let mut x = y as f32;
                // Stage 4a: soft-knee saturation above -1.8 dBFS (Padé tanh on the excess).
                let magnitude = x.abs();
                if magnitude > knee {
                    let shaped = knee + headroom * pade_tanh((magnitude - knee) / headroom);
                    x = if x < 0.0 { -shaped } else { shaped };
                }
                frame[c] = x;
            }

            // Stage 3 detector: sub-bass (below 80 Hz) of what comes in, after the high-pass.
            let low_pass = a.sub_bass_low_pass;
            let sub_bass = low_pass.run(&mut a.sub_bass_state, mono / channels as f64).abs() as f32;
            let rate = if sub_bass > a.sub_bass_envelope { a.env_attack } else { a.env_release };
            a.sub_bass_envelope += (sub_bass - a.sub_bass_envelope) * rate;

            // Stage 4b: lookahead limiter.
            a.limit_frame(&frame, &mut out);
            if a.prime_frames > 0 {
                a.prime_frames -= 1;
                continue;
            }
            io[written * channels..(written + 1) * channels].copy_from_slice(&out[..channels]);
            written += 1;
        }

        for band in a.bands.iter_mut() {
            if band.settling && band.current.settled(&band.target) {
                band.current = band.target;
                band.settling = false;
            }
        }
        if a.high_pass.settling && a.high_pass.current.settled(&a.high_pass.target) {
            a.high_pass.current = a.high_pass.target;
            a.high_pass.settling = false;
        }

        // Self-heal. Input is sanitized, so this should never fire; if anything
        // non-finite reached the filters, the output or the limiter anyway, zero
        // the state now and keep playing rather than stay silent until a seek.
        let mut healthy = a.release_gain.is_finite() && a.box_sum.is_finite() && a.preamp_linear.is_finite();
        let mut state = 0.0f64;
        for c in 0..channels {
            state += a.hp_state[c][0] + a.hp_state[c][1];
        }
        for b in 0..NUM_BANDS {
            for c in 0..channels {
                state += a.band_state[b][c][0] + a.band_state[b][c][1];
            }
            state += a.analysis_state[b][0] + a.analysis_state[b][1];
        }
        healthy = healthy && state.is_finite();
        healthy = healthy && io[..written * channels].iter().all(|v| v.is_finite());
        if !healthy {
            self.heal_count.fetch_add(1, Ordering::Relaxed);
            a.heal();
            io[..written * channels].fill(0.0);
        }
        written
    }

    fn push_analyzer(&self, a: &AudioState, io: &[f32], frames: usize) {
        if !self.analyzer_enabled.load(Ordering::Relaxed) || frames == 0 {
            return;
        }
        let mut mono = [0.0f32; BLOCK];
        let channels = a.channels;
        let scale = 1.0f32 / channels as f32;
        for i in 0..frames {
            let mut sum = 0.0f32;
            for c in 0..channels {
                sum += io[i * channels + c];
            }
            mono[i] = sum * scale; // (L + R) * 0.5 for stereo
        }
        self.ring.push(&mono[..frames]);
    }

    // ---- Controls (any thread, lock-free) ----------------------------------------------

    pub fn set_band_gain_db(&self, band: i32, gain_db: f32) {
        if !(0..NUM_BANDS as i32).contains(&band) {
            return;
        }
        self.user_gain_db[band as usize].store(clamp_gain_db(gain_db), Ordering::Relaxed);
        self.control_generation.fetch_add(1, Ordering::Release);
    }

    pub fn set_band_gains_db(&self, gains_db: &[f32; NUM_BANDS]) {
        for (b, &g) in gains_db.iter().enumerate() {
            self.user_gain_db[b].store(clamp_gain_db(g), Ordering::Relaxed);
        }
        self.control_generation.fetch_add(1, Ordering::Release);
    }

    /// EQ on (the sliders apply) or off (flat); the protection stages stay on either way.
    pub fn set_eq_enabled(&self, enabled: bool) {
        self.eq_enabled.store(enabled, Ordering::Relaxed);
        self.control_generation.fetch_add(1, Ordering::Release);
    }

    /// Auto: live spectral auto-calibration. The analysis bank measures the
    /// music's balance (slow envelope, about 1.2 s) against the output's
    /// target curve and the live offsets lift thin sub-bass and air and take
    /// out mud and harshness, within ±4.5 dB, on top of the sliders.
    pub fn set_auto_mode(&self, enabled: bool) {
        self.auto_mode.store(enabled, Ordering::Relaxed);
        self.control_generation.fetch_add(1, Ordering::Release);
    }

    /// Extra user preamp, on top of the live automatic headroom, -24..+12 dB.
    pub fn set_user_preamp_db(&self, preamp_db: f32) {
        self.user_preamp_db.store(if preamp_db.is_finite() { preamp_db } else { 0.0 }, Ordering::Relaxed);
        self.control_generation.fetch_add(1, Ordering::Release);
    }

    /// Loudness levelling (ReplayGain), -30..+15 dB, on top of the preamps. It
    /// runs even with the EQ off; the limiter catches what a lift pushes over.
    pub fn set_level_gain_db(&self, gain_db: f32) {
        self.level_gain_db.store(if gain_db.is_finite() { gain_db } else { 0.0 }, Ordering::Relaxed);
        self.control_generation.fetch_add(1, Ordering::Release);
    }

    /// The output: switches the high-pass, the limiter ceiling and Auto's target curve.
    pub fn set_output_device_type(&self, device: OutputDevice) {
        self.device.store(device as i32, Ordering::Release);
    }

    /// Stops feeding the analyzer (saves the copy while nothing is on screen).
    pub fn set_analyzer_enabled(&self, enabled: bool) {
        self.analyzer_enabled.store(enabled, Ordering::Relaxed);
    }

    /// 8 to 64 logarithmic bars from 20 Hz to 20 kHz.
    pub fn set_spectrum_bar_count(&self, bars: i32) {
        self.bar_count.store(bars.clamp(MIN_BARS as i32, MAX_BARS as i32), Ordering::Relaxed);
    }

    // ---- Telemetry and spectrum (one polling thread, 60 fps) ------------------------------

    /// One snapshot for a 60 fps UI: the effective band gains (sliders plus the
    /// live Auto offsets, ducking included) into `effective_gains_db`, and the
    /// Hann-windowed 2048-point FFT as 0..1 bars and peak-hold dots into `bars`
    /// and `peaks` (at most `capacity` bars). Returns the live automatic preamp
    /// in dB (headroom, Bluetooth pad and user preamp). Never blocks the audio thread.
    pub fn poll_dsp_telemetry(
        &self,
        effective_gains_db: Option<&mut [f32; NUM_BANDS]>,
        bars: Option<&mut [f32]>,
        peaks: Option<&mut [f32]>,
        capacity: usize,
    ) -> f32 {
        self.poll_dsp_telemetry_at(now_nanos(), effective_gains_db, bars, peaks, capacity)
    }

    pub fn poll_dsp_telemetry_at(
        &self,
        now: i64,
        effective_gains_db: Option<&mut [f32; NUM_BANDS]>,
        bars: Option<&mut [f32]>,
        peaks: Option<&mut [f32]>,
        capacity: usize,
    ) -> f32 {
        if let Some(gains) = effective_gains_db {
            *gains = self.effective_gains_db();
        }
        if bars.is_some() || peaks.is_some() {
            self.poll_spectrum_at(now, bars, peaks, capacity);
        }
        self.live_auto_preamp_out_db.load(Ordering::Relaxed)
    }

    /// The FFT part of poll_dsp_telemetry: 0..1 bar heights to `bars` and
    /// peak-hold dots to `peaks`, ballistics applied. Returns the number of
    /// bars written (at most `capacity`).
    pub fn poll_spectrum(&self, bars: Option<&mut [f32]>, peaks: Option<&mut [f32]>, capacity: usize) -> usize {
        self.poll_spectrum_at(now_nanos(), bars, peaks, capacity)
    }

    /// poll_spectrum() at a given steady-clock time, in nanoseconds (for tests).
    pub fn poll_spectrum_at(
        &self,
        now: i64,
        mut bars: Option<&mut [f32]>,
        mut peaks: Option<&mut [f32]>,
        capacity: usize,
    ) -> usize {
        let mut p = lock(&self.poller);
        let p = &mut *p;
        let bar_count = self.bar_count.load(Ordering::Relaxed) as usize;
        let sample_rate = self.analyzer_sample_rate.load(Ordering::Acquire);
        let epoch = self.analyzer_epoch.load(Ordering::Acquire);
        if epoch != p.seen_analyzer_epoch {
            // The rate changed: whatever is queued was recorded at another one.
            p.seen_analyzer_epoch = epoch;
            self.ring.discard();
            p.history.fill(0.0);
            p.history_pos = 0;
            p.pending_head = 0;
            p.pending_count = 0;
        }
        if sample_rate != p.mapped_sample_rate || bar_count != p.mapped_bars {
            p.rebuild_bar_map(sample_rate, bar_count);
        }

        let dt = if p.last_poll_nanos == 0 { 1.0f32 / 60.0 } else { (now - p.last_poll_nanos) as f32 * 1e-9f32 };
        let dt = sclamp(dt, 0.001, 0.1);
        p.last_poll_nanos = now;

        // Queue everything the audio thread has handed over...
        let got = self.ring.pop(&mut p.pop_scratch);
        const PENDING_MASK: u32 = PENDING_CAPACITY - 1;
        for i in 0..got as usize {
            if p.pending_count == PENDING_CAPACITY {
                // full: drop the oldest
                p.pending_head = (p.pending_head + 1) & PENDING_MASK;
                p.pending_count -= 1;
            }
            p.pending[((p.pending_head + p.pending_count) & PENDING_MASK) as usize] = p.pop_scratch[i];
            p.pending_count += 1;
        }
        // ...and read it at the playback rate, catching up if it has fallen far behind.
        let mut advance = (dt * sample_rate as f32).round() as i64 as u32;
        let max_lag = (MAX_LAG_SECONDS * sample_rate as f32) as u32;
        if p.pending_count > max_lag.wrapping_add(advance) {
            advance = p.pending_count - max_lag;
        }
        advance = advance.min(p.pending_count);
        if advance == 0 {
            p.stale_seconds += dt;
        } else {
            p.stale_seconds = 0.0;
        }
        let skip = advance.saturating_sub(FFT_SIZE as u32);
        p.pending_head = (p.pending_head + skip) & PENDING_MASK;
        for _ in skip..advance {
            p.history[p.history_pos] = p.pending[p.pending_head as usize];
            p.pending_head = (p.pending_head + 1) & PENDING_MASK;
            p.history_pos += 1;
            if p.history_pos == FFT_SIZE {
                p.history_pos = 0;
            }
        }
        p.pending_count -= advance;
        let silent = p.stale_seconds > STALE_SECONDS;

        for n in 0..FFT_SIZE {
            let index = (p.history_pos + n) & (FFT_SIZE - 1); // oldest first
            p.fft_re[n] = p.history[index] * p.window[n];
            p.fft_im[n] = 0.0;
        }
        p.fft.forward(&mut p.fft_re, &mut p.fft_im);

        // A full-scale sine at a bin centre reads |X| = N/4 through a Hann window.
        let normalisation_db = 20.0f32 * (4.0f32 / FFT_SIZE as f32).log10();
        let attack = 1.0f32 - (-dt / ATTACK_SECONDS).exp();
        let decay = DECAY_DB_PER_SECOND / -FLOOR_DB * dt;
        let (re, im) = (&p.fft_re, &p.fft_im);
        let power = |bin: usize| re[bin] * re[bin] + im[bin] * im[bin];

        let count = bar_count.min(capacity);
        for i in 0..bar_count {
            let bin_power = if p.bar_high_bin[i] >= p.bar_low_bin[i] {
                let mut bp = 0.0f32;
                for k in p.bar_low_bin[i]..=p.bar_high_bin[i] {
                    bp = smax(bp, power(k as usize));
                }
                bp
            } else {
                // Narrower than one bin (the lowest bars): read between the two nearest bins.
                let position = sclamp(p.bar_center_bin[i], 1.0, FFT_SIZE as f32 / 2.0 - 1.0);
                let k = position as usize;
                let frac = position - k as f32;
                power(k) * (1.0 - frac) + power(k + 1) * frac
            };
            let db = power_db(bin_power as f64) + normalisation_db;
            // Nothing new for a while (paused): let the bars fall rather than freeze.
            let target = if silent { 0.0 } else { sclamp((db - FLOOR_DB) / -FLOOR_DB, 0.0, 1.0) };

            let level = &mut p.bar_level[i];
            *level = if target > *level { *level + (target - *level) * attack } else { smax(target, *level - decay) };
            let level = *level;

            let peak = &mut p.peak_level[i];
            if level >= *peak {
                *peak = level;
                p.peak_hold[i] = PEAK_HOLD_SECONDS;
                p.peak_velocity[i] = 0.0;
            } else if p.peak_hold[i] > 0.0 {
                p.peak_hold[i] -= dt;
            } else {
                p.peak_velocity[i] += PEAK_GRAVITY * dt;
                *peak = smax(level, *peak - p.peak_velocity[i] * dt);
            }
            if i < count {
                if let Some(bars) = bars.as_deref_mut() {
                    bars[i] = level;
                }
                if let Some(peaks) = peaks.as_deref_mut() {
                    peaks[i] = *peak;
                }
            }
        }
        count
    }

    /// How many bars the spectrum has (set_spectrum_bar_count).
    pub fn spectrum_bar_count(&self) -> usize {
        self.bar_count.load(Ordering::Relaxed) as usize
    }

    /// The effective gain of each band right now, dB.
    pub fn effective_gains_db(&self) -> [f32; NUM_BANDS] {
        std::array::from_fn(|b| self.effective_out_db[b].load(Ordering::Relaxed))
    }

    /// Auto's live offset for each band right now, dB (±4.5).
    pub fn live_auto_offsets_db(&self) -> [f32; NUM_BANDS] {
        std::array::from_fn(|b| self.offset_out_db[b].load(Ordering::Relaxed))
    }

    // ---- Introspection (tests, diagnostics) ----------------------------------------------

    /// The whole preamp the engine is converging on, in dB (live headroom, pad, user, level).
    pub fn target_preamp_db(&self) -> f32 {
        self.target_preamp_db.load(Ordering::Relaxed)
    }

    /// The current sub-bass ducking, in dB (0..4).
    pub fn bass_duck_db(&self) -> f32 {
        self.bass_duck_db.load(Ordering::Relaxed)
    }

    /// The current harshness tamer on 4/8 kHz, in dB (0..4).
    pub fn harsh_duck_db(&self) -> f32 {
        self.harsh_duck_out_db.load(Ordering::Relaxed)
    }

    /// How often the engine has healed itself from non-finite state (should stay 0).
    pub fn heal_count(&self) -> u32 {
        self.heal_count.load(Ordering::Relaxed)
    }

    /// Input samples that were NaN, infinite or past +24 dBFS and were repaired.
    pub fn sanitized_samples(&self) -> u32 {
        self.sanitized_samples.load(Ordering::Relaxed)
    }

    /// Whether the tonal adaptation is frozen (the music is below -42 dBFS).
    pub fn tonal_frozen(&self) -> bool {
        self.frozen_out.load(Ordering::Relaxed)
    }

    /// Ceiling of the limiter for the current device, in dBFS.
    pub fn ceiling_db(&self) -> f32 {
        ceiling_db_for(lock(&self.audio).active_device)
    }

    /// Gain (dB) band `band` is currently designed at, ducking included.
    pub fn designed_band_gain_db(&self, band: usize) -> f32 {
        lock(&self.audio).designed_db[band]
    }

    /// Tests only: puts NaN into the filter state, as a bug elsewhere might, to exercise the self-heal.
    #[cfg(any(test, feature = "testing"))]
    pub fn poison_state_for_test(&self) {
        let mut a = lock(&self.audio);
        a.band_state[3][0][0] = f64::NAN;
        a.release_gain = f32::INFINITY;
    }
}

fn ceiling_db_for(device: OutputDevice) -> f32 {
    match device {
        OutputDevice::UsbDacHiRes => USB_CEILING_DB,
        OutputDevice::BluetoothA2dp => BLUETOOTH_CEILING_DB,
        _ => CEILING_DB,
    }
}

/// Auto's target balance for `device`, dB per band relative to the broadband level.
pub fn target_curve_db(device: OutputDevice) -> [f32; NUM_BANDS] {
    // Headphones, wired, Bluetooth and DACs: a warm Harman/pink -4.5 dB per
    // octave. The phone speaker: the same tilt, centred on the midrange it can
    // actually play, with no sub-bass expected.
    const SPEAKER_MID_BOOST: [f32; NUM_BANDS] = [-12.0, -6.0, 0.0, 1.0, 2.0, 3.0, 3.0, 2.0, 0.0, 0.0];
    let mut out = [0.0f32; NUM_BANDS];
    let mut total = 0.0f64;
    for b in 0..NUM_BANDS {
        out[b] = -4.5f32 * b as f32;
        if device == OutputDevice::PhoneSpeaker {
            out[b] += SPEAKER_MID_BOOST[b];
        }
        total += 10.0f64.powf(out[b] as f64 / 10.0);
    }
    // Relative to the broadband level, as the measurement is.
    let norm = (10.0 * total.log10()) as f32;
    for v in out.iter_mut() {
        *v -= norm;
    }
    out
}

/// The live offsets Auto aims for, for octave-band powers `band_power` heard
/// on `device`: thin sub-bass and air lifted, mud and harshness cut, within
/// ±4.5 dB. Pure.
pub fn auto_offset_target_db(device: OutputDevice, band_power: &[f64; NUM_BANDS]) -> [f32; NUM_BANDS] {
    let target = target_curve_db(device);
    let mut total = 0.0f64;
    for &p in band_power {
        total += sanitize_f64(p);
    }
    let mut out = [0.0f32; NUM_BANDS];
    #[allow(clippy::neg_cmp_op_on_partial_ord)] // also true for NaN, on purpose
    if !(total > EPSILON as f64) || !total.is_finite() {
        return out;
    }
    for b in 0..NUM_BANDS {
        let relative = if band_power[b] > 0.0 { power_db(band_power[b]) - power_db(total) } else { -200.0 };
        let diff = relative - target[b]; // > 0: more than the target, < 0: less
        let mut offset = -AUTO_STRENGTH * diff;
        match AUTO_RULE[b] {
            AutoRule::Boost => {
                // Lift what is thin, never what isn't there at all (an MP3's
                // cut-off top, or noise), and never sub-bass on the phone speaker.
                if diff < -ABSENT_BELOW_TARGET_DB || (device == OutputDevice::PhoneSpeaker && b < 2) {
                    offset = 0.0;
                }
                offset = sclamp(offset, 0.0, MAX_AUTO_BOOST_DB);
            }
            AutoRule::Cut => offset = sclamp(offset, -MAX_AUTO_CUT_DB, 0.0),
            AutoRule::Hold => offset = 0.0,
        }
        out[b] = offset;
    }
    out
}

impl AudioState {
    fn design_high_pass_for_device(&mut self) {
        self.device_pad_db = 0.0;
        let cutoff = match self.active_device {
            OutputDevice::PhoneSpeaker => 90.0,
            OutputDevice::WiredHeadphones => 18.0,
            OutputDevice::BluetoothA2dp => {
                self.device_pad_db = BLUETOOTH_PAD_DB;
                24.0
            }
            OutputDevice::UsbDacHiRes => 10.0,
        };
        self.high_pass.target = design_high_pass(self.sample_rate as f64, cutoff, BUTTERWORTH_Q);
        self.high_pass.settling = true;
        self.ceiling = db_to_linear(ceiling_db_for(self.active_device));
    }

    fn reset(&mut self) {
        self.hp_state = [[0.0; 2]; MAX_CHANNELS];
        self.band_state = [[[0.0; 2]; MAX_CHANNELS]; NUM_BANDS];
        self.sub_bass_state = [0.0; 2];
        self.sub_bass_envelope = 0.0;
        self.sibilance_state = [0.0; 2];
        self.sibilance_envelope = 0.0;
        self.duck_db = 0.0;
        self.harsh_duck_db = 0.0;
        // A seek keeps what Auto has learned of the music; only the filters restart.
        self.analysis_state = [[0.0; 2]; NUM_BANDS];
        self.block_power = [0.0; NUM_BANDS];
        self.block_frames = 0;
        self.high_pass.current = self.high_pass.target;
        self.high_pass.settling = false;
        for band in self.bands.iter_mut() {
            band.current = band.target;
            band.settling = false;
        }
        self.preamp_linear = self.preamp_target_linear;
        self.reset_limiter();
        self.delay_pos = 0;
        self.box_pos = 0;
        self.frame_index = 0;
        self.prime_frames = self.lookahead_delay;
    }

    /// The limiter's delay line and gain (shared by reset and heal).
    fn reset_limiter(&mut self) {
        let lookahead = self.lookahead;
        self.delay[..lookahead * MAX_CHANNELS].fill(0.0);
        self.min_head = 0;
        self.min_count = 0;
        self.box_[..lookahead].fill(1.0);
        self.box_sum = lookahead as f64;
        self.release_gain = 1.0;
    }

    fn heal(&mut self) {
        self.hp_state = [[0.0; 2]; MAX_CHANNELS];
        self.band_state = [[[0.0; 2]; MAX_CHANNELS]; NUM_BANDS];
        self.analysis_state = [[0.0; 2]; NUM_BANDS];
        self.sub_bass_state = [0.0; 2];
        self.sibilance_state = [0.0; 2];
        self.sub_bass_envelope = 0.0;
        self.sibilance_envelope = 0.0;
        for b in 0..NUM_BANDS {
            self.band_energy[b] = sanitize_f64(self.band_energy[b]);
            self.tonal_power[b] = sanitize_f64(self.tonal_power[b]);
            self.live_offset_db[b] = sanitize_f32(self.live_offset_db[b]);
        }
        self.broadband_power = sanitize_f32(self.broadband_power);
        self.auto_headroom_db = sanitize_f32(self.auto_headroom_db);
        self.duck_db = 0.0;
        self.harsh_duck_db = 0.0;
        // The coefficients themselves: back to their targets, settled.
        self.high_pass.current = self.high_pass.target;
        self.high_pass.settling = false;
        for band in self.bands.iter_mut() {
            band.current = band.target;
            band.settling = false;
        }
        if !self.preamp_linear.is_finite() {
            self.preamp_linear = 1.0;
        }
        if !self.preamp_target_linear.is_finite() {
            self.preamp_target_linear = 1.0;
        }
        // The limiter: its delay line and gain, without touching the frame
        // count, so the output stays sample-aligned with the input.
        self.reset_limiter();
    }

    /// Converts interleaved `format` samples from `input` into `output`.
    /// Returns how many float samples had to be repaired.
    fn convert(&self, input: &[u8], format: SampleFormat, output: &mut [f32]) -> u32 {
        let bytes = format.bytes_per_sample();
        let input = &input[..output.len() * bytes];
        let mut repaired = 0u32;
        match format {
            SampleFormat::Pcm16 => {
                for (o, s) in output.iter_mut().zip(input.chunks_exact(2)) {
                    *o = i16::from_le_bytes([s[0], s[1]]) as f32 * (1.0f32 / 32768.0);
                }
            }
            SampleFormat::Pcm24Packed => {
                for (o, s) in output.iter_mut().zip(input.chunks_exact(3)) {
                    // Assemble unsigned, then sign-extend from bit 23.
                    let raw = s[0] as u32 | (s[1] as u32) << 8 | (s[2] as u32) << 16;
                    let v = (raw ^ 0x80_0000) as i32 - 0x80_0000;
                    *o = v as f32 * (1.0f32 / 8_388_608.0);
                }
            }
            SampleFormat::Pcm32 => {
                for (o, s) in output.iter_mut().zip(input.chunks_exact(4)) {
                    let v = i32::from_le_bytes([s[0], s[1], s[2], s[3]]);
                    *o = (v as f64 * (1.0 / 2_147_483_648.0)) as f32;
                }
            }
            SampleFormat::Float => {
                for (o, s) in output.iter_mut().zip(input.chunks_exact(4)) {
                    let (v, fixed) = sanitize_input(f32::from_le_bytes([s[0], s[1], s[2], s[3]]));
                    repaired += fixed as u32;
                    *o = v;
                }
            }
        }
        repaired
    }

    /// The float path of convert() for audio already in `io`.
    fn sanitize_in_place(&self, io: &mut [f32]) -> u32 {
        let mut repaired = 0u32;
        for v in io.iter_mut() {
            let (clean, fixed) = sanitize_input(*v);
            repaired += fixed as u32;
            *v = clean;
        }
        repaired
    }

    fn limit_frame(&mut self, frame: &[f32; MAX_CHANNELS], out: &mut [f32; MAX_CHANNELS]) -> f32 {
        let channels = self.channels;
        // Required gain for this frame to sit under the ceiling.
        let mut peak = 0.0f32;
        for &x in &frame[..channels] {
            peak = smax(peak, x.abs());
        }
        let required = if peak > self.ceiling { self.ceiling / peak } else { 1.0 };

        // Minimum over the lookahead window (monotonic deque).
        let capacity = self.lookahead;
        while self.min_count > 0 {
            let back = (self.min_head + self.min_count - 1) % capacity;
            if self.min_value[back] < required {
                break;
            }
            self.min_count -= 1;
        }
        let slot = (self.min_head + self.min_count) % capacity;
        self.min_value[slot] = required;
        self.min_index[slot] = self.frame_index;
        self.min_count += 1;
        while self.min_index[self.min_head] <= self.frame_index - self.lookahead as i64 {
            self.min_head = (self.min_head + 1) % capacity;
            self.min_count -= 1;
        }
        let window_min = self.min_value[self.min_head];

        // Instant attack, smooth release, then a box average over the lookahead:
        // the gain ramps down across the 2 ms before a peak and is at or below the
        // peak's required gain when the peak comes out of the delay line.
        self.release_gain = if window_min < self.release_gain {
            window_min
        } else {
            self.release_gain + (window_min - self.release_gain) * self.release_alpha
        };
        self.box_sum += self.release_gain as f64 - self.box_[self.box_pos] as f64;
        self.box_[self.box_pos] = self.release_gain;
        self.box_pos += 1;
        if self.box_pos == self.lookahead {
            self.box_pos = 0;
        }
        if self.frame_index & 0xFFFF == 0 {
            // stop float drift from accumulating
            self.box_sum = 0.0;
            for &v in &self.box_[..self.lookahead] {
                self.box_sum += v as f64;
            }
        }
        let gain = smin(1.0f32, (self.box_sum / self.lookahead as f64) as f32);
        self.frame_index += 1;

        let ceiling = self.ceiling;
        if self.lookahead_delay == 0 {
            for c in 0..channels {
                out[c] = sclamp(frame[c] * gain, -ceiling, ceiling);
            }
            return gain;
        }
        let stored = &mut self.delay[self.delay_pos * MAX_CHANNELS..(self.delay_pos + 1) * MAX_CHANNELS];
        for c in 0..channels {
            let delayed = stored[c];
            stored[c] = frame[c];
            // The clamp is a backstop for rounding; the gain already guarantees it.
            out[c] = sclamp(delayed * gain, -ceiling, ceiling);
        }
        self.delay_pos += 1;
        if self.delay_pos == self.lookahead_delay {
            self.delay_pos = 0;
        }
        gain
    }
}

/// A float input sample made safe for the filters: one NaN would poison every
/// filter for good, a corrupt frame's 1e30 would drown them. Returns the value
/// and whether it had to be repaired.
#[inline]
fn sanitize_input(v: f32) -> (f32, bool) {
    if !v.is_finite() || v.abs() > INPUT_LIMIT {
        (if v.is_finite() { sclamp(v, -INPUT_LIMIT, INPUT_LIMIT) } else { 0.0 }, true)
    } else {
        (v, false)
    }
}

impl PollerState {
    fn rebuild_bar_map(&mut self, sample_rate: i32, bars: usize) {
        let bin_hz = sample_rate as f32 / FFT_SIZE as f32;
        let top = smin(MAX_BAR_HZ, 0.5f32 * sample_rate as f32 * 0.995f32);
        for i in 0..bars {
            let low = MIN_BAR_HZ * (top / MIN_BAR_HZ).powf(i as f32 / bars as f32);
            let high = MIN_BAR_HZ * (top / MIN_BAR_HZ).powf((i + 1) as f32 / bars as f32);
            self.bar_low_bin[i] = smax(1, (low / bin_hz).ceil() as i32);
            self.bar_high_bin[i] = smin(FFT_SIZE as i32 / 2, (high / bin_hz).floor() as i32);
            self.bar_center_bin[i] = (low * high).sqrt() / bin_hz;
        }
        if bars != self.mapped_bars {
            self.bar_level = [0.0; MAX_BARS];
            self.peak_level = [0.0; MAX_BARS];
            self.peak_hold = [0.0; MAX_BARS];
            self.peak_velocity = [0.0; MAX_BARS];
        }
        self.mapped_sample_rate = sample_rate;
        self.mapped_bars = bars;
    }
}
