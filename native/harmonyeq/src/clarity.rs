//! Clarity Live: a perceptual equalizer that sets each of its 24 bands on its
//! own, live, from what the music sounds like at that moment.
//!
//! A model of hearing listens to the music as it reaches this stage (about
//! 190 short spectra a second) and works out, band by band, what is being
//! covered up by louder sounds nearby in pitch and what pushes forward of the
//! song's usual balance or of its neighbours (a resonance, a harsh note, a
//! boom). Each band then moves on its own: covered-up bands come forward
//! (Recover), pushy ones are held back (Tame), and the level is matched so the
//! song stays as loud as it was. It is the phone port of the desktop app's
//! Clarity (desktop/.../dsp/Clarity.kt), with the same model and constants.
//!
//!  - Bands: 24, evenly spaced on the ERB-number scale (Glasberg & Moore), 40 Hz
//!    to 16 kHz: narrow in the bass and wide in the treble, as hearing resolves pitch.
//!  - Model: outer/middle-ear weighting and threshold in quiet (Terhardt), a
//!    masking spread falling 27 dB/Bark below a sound and level-dependently
//!    above it, 60 ms loudness integration, the song's balance over ~3 s, and
//!    loudness matching with Zwicker's 0.23 exponent.
//!  - Filters: 24 RBJ peaking filters, fitted to the model's curve allowing
//!    for their overlap, linked across channels (the stereo image is untouched).
//!  - Live: feed-forward (the analysis hears the signal before the filters),
//!    so it adds no latency. Bands move towards their target every ~1.3 ms,
//!    quickly when they move away from flat (40 ms) and slowly back (250 ms).
//!  - Real-time safe: everything is allocated in [`ClarityStage::new`];
//!    configure, reset and the per-frame calls never allocate. Above ~50 kHz
//!    the analysis runs on a decimated copy, so its cost doesn't grow with
//!    the sample rate.

use crate::design::{Biquad, PI};
use crate::fft::Fft;
use crate::MAX_CHANNELS;

pub const CLARITY_BANDS: usize = 24;
const N: usize = CLARITY_BANDS;
pub const LOW_HZ: f32 = 40.0;
pub const HIGH_HZ: f32 = 16_000.0;

/// Analysis window (at the analysis rate, 44.1–50 kHz): ~43 ms, ~23 Hz bins,
/// enough to tell the narrow bass bands apart.
pub const ANALYSIS_FFT: usize = 2048;
/// A new spectrum every 256 analysis samples: ~5.3 ms, ~190 model updates a second.
pub const ANALYSIS_HOP: usize = 256;
/// Filter updates a second.
const GLIDE_PER_SECOND: f64 = 750.0;
/// Analysis runs at the sample rate divided by the nearest whole number to this.
const ANALYSIS_TARGET_RATE: f64 = 48_000.0;

// The hearing model (the desktop's ClarityDesign).
const SPL_OFFSET_DB: f32 = 90.0; // full scale taken as a loud but ordinary listening level
const MASK_INDEX_DB: f32 = 6.0;
const LOWER_SLOPE: f32 = 27.0; // dB per Bark, masking towards lower pitches
const BARK_PER_ERB: f32 = 0.62;
const SILENCE_DB: f32 = -62.0;
const LOUDNESS_EXPONENT: f64 = 0.23;
const CLEAR_MARGIN_DB: f32 = 9.0;
const RECOVER_RATIO: f32 = 0.6;
const MAX_RECOVER_DB: f32 = 4.0;
const EXCESS_KNEE_DB: f32 = 3.0;
const EXCESS_RATIO: f32 = 0.5;
const PEAK_KNEE_DB: f32 = 3.0;
const PEAK_RATIO: f32 = 0.6;
const MAX_TAME_DB: f32 = 5.0;
const INTEGRATION_S: f32 = 0.06;
const BALANCE_SECONDS: f32 = 3.0;
const MAX_BOOST_DB: f32 = 8.0;
const MAX_CUT_DB: f32 = 9.0;
const MAX_MATCH_DB: f32 = 4.0;
const SPREAD: f32 = 0.2; // how much of each band's wish its neighbours share

// The filters (the desktop's ClarityFilterBank).
const Q_SCALE: f64 = 0.8;
const MAX_FILTER_DB: f32 = 12.0;
const REFERENCE_DB: f32 = 6.0;
const FIT_ROUNDS: usize = 4;
const FIT_STEP: f32 = 0.8;

// The glide (the desktop's ClarityLayer).
const ATTACK_S: f64 = 0.04;
const RELEASE_S: f64 = 0.25;
const MATCH_S: f64 = 1.5;
const LEVEL_S: f64 = 0.05;
/// A level this close to 0 dB is 0 dB (and the stage can step aside).
const SNAP_DB: f64 = 0.005;

/// Intensity 0..1 and balance −1 (tame more) .. +1 (recover more) as the
/// model's two weights: 1 each at the default 50 %, up to 3.2 at the extremes.
pub fn weights(intensity: f32, balance: f32) -> (f32, f32) {
    let i = if intensity.is_finite() { intensity.clamp(0.0, 1.0) } else { 0.0 };
    let b = if balance.is_finite() { balance.clamp(-1.0, 1.0) } else { 0.0 };
    (2.0 * i * (1.0 + 0.6 * b), 2.0 * i * (1.0 - 0.6 * b))
}

/// ERB number of `hz`.
pub fn erb_number(hz: f32) -> f32 {
    21.4 * (1.0 + 0.00437 * hz).log10()
}

pub fn hz_of_erb(erb: f32) -> f32 {
    (10f32.powf(erb / 21.4) - 1.0) / 0.00437
}

/// The bands' edges (25) and centres (24), and the centres on the ERB scale.
#[derive(Clone, Copy)]
pub struct Bands {
    pub edges_hz: [f32; N + 1],
    pub centers_hz: [f32; N],
    pub center_erb: [f32; N],
    pub erb_step: f32,
}

impl Bands {
    pub fn new() -> Self {
        let low = erb_number(LOW_HZ);
        let step = (erb_number(HIGH_HZ) - low) / N as f32;
        let edges_hz = std::array::from_fn(|i| hz_of_erb(low + step * i as f32));
        let center_erb: [f32; N] = std::array::from_fn(|k| low + step * (k as f32 + 0.5));
        let centers_hz = std::array::from_fn(|k| hz_of_erb(center_erb[k]));
        Bands { edges_hz, centers_hz, center_erb, erb_step: step }
    }

    /// `db` (one per band) at `hz`, interpolated on the ERB scale; the ends hold.
    pub fn value_at(&self, db: &[f32; N], hz: f32) -> f32 {
        let pos = (erb_number(hz) - self.center_erb[0]) / self.erb_step;
        if pos.is_nan() || pos <= 0.0 {
            return db[0];
        }
        if pos >= (N - 1) as f32 {
            return db[N - 1];
        }
        let i = pos as usize;
        let f = pos - i as f32;
        db[i] * (1.0 - f) + db[i + 1] * f
    }
}

impl Default for Bands {
    fn default() -> Self {
        Self::new()
    }
}

/// Terhardt's threshold of hearing in quiet, dB SPL.
fn quiet_db(hz: f32) -> f32 {
    let f = (hz / 1000.0).max(0.02);
    3.64 * f.powf(-0.8) - 6.5 * (-0.6 * (f - 3.3) * (f - 3.3)).exp() + 1e-3 * f.powi(4)
}

/// Outer- and middle-ear weighting, dB, 0 at 1 kHz.
fn ear_db(hz: f32) -> f32 {
    (-0.5 * (quiet_db(hz) - quiet_db(1000.0))).clamp(-20.0, 6.0)
}

/// How fast masking falls above a sound at `hz` that is `spl_db` loud, dB per Bark.
fn upper_slope(hz: f32, spl_db: f32) -> f32 {
    (24.0 + 230.0 / hz.max(20.0) - 0.2 * spl_db).clamp(5.0, 40.0)
}

fn smoothstep(edge0: f32, edge1: f32, x: f32) -> f32 {
    let t = ((x - edge0) / (edge1 - edge0)).clamp(0.0, 1.0);
    t * t * (3.0 - 2.0 * t)
}

#[inline]
fn db_to_power(db: f32) -> f32 {
    10f32.powf(db / 10.0)
}

/// The hearing model: one short-time spectrum in (band powers), the curve it
/// wants out. Pure and allocation-free; the desktop's ClarityModel.
pub struct Model {
    ear: [f32; N],
    quiet_level: [f32; N],
    quiet_power: [f32; N],
    bark: [f32; N],
    centers_hz: [f32; N],
    mask_scale: f32,
    heard: [f32; N],
    linear: [f64; N],
    started: bool,
    excitation: [f64; N],
    slopes: [f32; N],
    balance: [f32; N],
    learned: bool,
    raw: [f32; N],
    /// The ear-weighted level per band, dBFS.
    pub level_db: [f32; N],
    /// The masking threshold per band, dBFS (same weighting).
    pub mask_db: [f32; N],
    pub recover_db: [f32; N],
    pub tame_db: [f32; N],
    /// What the model wants the bands at, dB.
    pub target_db: [f32; N],
    /// The level change that keeps the loudness where it was, dB.
    pub match_db: f32,
    /// The last spectrum was silence (or a fade-out): the target is flat.
    pub silent: bool,
}

impl Model {
    pub fn new(bands: &Bands) -> Self {
        let ear: [f32; N] = std::array::from_fn(|k| ear_db(bands.centers_hz[k]));
        let quiet_level: [f32; N] = std::array::from_fn(|k| quiet_db(bands.centers_hz[k]) - SPL_OFFSET_DB + ear[k]);
        Model {
            ear,
            quiet_level,
            quiet_power: std::array::from_fn(|k| db_to_power(quiet_level[k])),
            bark: std::array::from_fn(|k| bands.center_erb[k] * BARK_PER_ERB),
            centers_hz: bands.centers_hz,
            mask_scale: db_to_power(-MASK_INDEX_DB),
            heard: [0.0; N],
            linear: [0.0; N],
            started: false,
            excitation: [0.0; N],
            slopes: [0.0; N],
            balance: [0.0; N],
            learned: false,
            raw: [0.0; N],
            level_db: [-200.0; N],
            mask_db: quiet_level,
            recover_db: [0.0; N],
            tame_db: [0.0; N],
            target_db: [0.0; N],
            match_db: 0.0,
            silent: true,
        }
    }

    /// A new song: forget the last one's balance.
    pub fn new_song(&mut self) {
        self.learned = false;
        self.balance = [0.0; N];
    }

    /// Back to the state of a new model (non-finite state, a new format).
    pub fn clear(&mut self) {
        self.heard = [0.0; N];
        self.started = false;
        self.new_song();
        self.go_silent();
    }

    fn go_silent(&mut self) {
        self.silent = true;
        self.recover_db = [0.0; N];
        self.tame_db = [0.0; N];
        self.target_db = [0.0; N];
        self.mask_db = self.quiet_level;
        self.match_db = 0.0;
    }

    /// One spectrum: `power` per band (linear; a full-scale sine reads 0.5 in
    /// its band), `seconds` since the last one, and the two weights.
    pub fn analyze(&mut self, power: &[f32; N], seconds: f32, recover_weight: f32, tame_weight: f32) {
        let integrate = 1.0 - (-seconds / INTEGRATION_S).exp();
        for k in 0..N {
            let p = if power[k].is_finite() { power[k].max(0.0) } else { 0.0 };
            self.heard[k] = if !self.started { p } else { self.heard[k] + (p - self.heard[k]) * integrate };
        }
        self.started = true;
        let mut total = 0.0f64;
        for k in 0..N {
            let p = self.heard[k];
            self.level_db[k] = if p > 1e-20 { 10.0 * p.log10() + self.ear[k] } else { -200.0 };
            self.linear[k] = if p > 1e-20 { db_to_power(self.level_db[k]) as f64 } else { 0.0 };
            total += self.linear[k];
        }
        let overall = if total > 0.0 { (10.0 * total.log10()) as f32 } else { -200.0 };
        if overall < SILENCE_DB {
            self.go_silent();
            return;
        }
        self.silent = false;

        // Masking: every other band's level spread across pitch.
        for j in 0..N {
            self.slopes[j] = upper_slope(self.centers_hz[j], self.level_db[j] + SPL_OFFSET_DB);
        }
        for k in 0..N {
            let mut masking = 0.0f64;
            for j in 0..N {
                if j == k || self.linear[j] == 0.0 {
                    continue;
                }
                let dz = (self.bark[k] - self.bark[j]).abs();
                let fall = (if k < j { LOWER_SLOPE } else { self.slopes[j] }) * dz;
                if fall > 70.0 {
                    continue;
                }
                masking += self.linear[j] * db_to_power(-fall) as f64;
            }
            self.excitation[k] = self.linear[k] + masking;
            self.mask_db[k] = (10.0 * (masking * self.mask_scale as f64 + self.quiet_power[k] as f64).log10()) as f32;
        }

        // The song's usual balance: each band against the whole, over a few seconds.
        let follow = 1.0 - (-seconds / BALANCE_SECONDS).exp();
        for k in 0..N {
            let rel = self.level_db[k] - overall;
            self.balance[k] = if !self.learned { rel } else { self.balance[k] + (rel - self.balance[k]) * follow };
        }
        self.learned = true;

        let (rw, tw) = (recover_weight.max(0.0), tame_weight.max(0.0));
        for k in 0..N {
            let margin = self.level_db[k] - self.mask_db[k];
            let audible = self.level_db[k] - self.quiet_level[k];
            // Recover what is there but only just heard (not what is buried, not hiss).
            let present = smoothstep(-20.0, -10.0, margin) * smoothstep(-6.0, 6.0, audible);
            let deficit = (CLEAR_MARGIN_DB - margin).clamp(0.0, 12.0);
            self.recover_db[k] = (deficit * RECOVER_RATIO * present * rw).min(MAX_RECOVER_DB * rw);

            // Tame what pushes forward: of the song's usual balance, and of its neighbours.
            let excess = (self.level_db[k] - overall) - self.balance[k];
            let (mut sum, mut count) = (0.0f64, 0usize);
            for j in k.saturating_sub(2)..=(k + 2).min(N - 1) {
                if j == k {
                    continue;
                }
                sum += self.linear[j];
                count += 1;
            }
            let around = if sum > 0.0 { (10.0 * (sum / count as f64).log10()) as f32 } else { -200.0 };
            let peak = self.level_db[k] - around;
            let push =
                (excess - EXCESS_KNEE_DB).max(0.0) * EXCESS_RATIO + (peak - PEAK_KNEE_DB).clamp(0.0, 20.0) * PEAK_RATIO;
            let clear = smoothstep(-6.0, 6.0, margin);
            self.tame_db[k] = -(push * clear * tw).min(MAX_TAME_DB * tw);

            self.raw[k] = self.recover_db[k] + self.tame_db[k];
        }
        for k in 0..N {
            let l = self.raw[k.saturating_sub(1)];
            let r = self.raw[(k + 1).min(N - 1)];
            self.target_db[k] =
                (SPREAD * l + (1.0 - 2.0 * SPREAD) * self.raw[k] + SPREAD * r).clamp(-MAX_CUT_DB, MAX_BOOST_DB);
        }

        // Loudness before and after the curve; the level change that evens them out.
        let (mut before, mut after) = (0.0f64, 0.0f64);
        for k in 0..N {
            let e = self.excitation[k];
            if e <= 0.0 {
                continue;
            }
            before += e.powf(LOUDNESS_EXPONENT);
            after += (e * db_to_power(self.target_db[k]) as f64).powf(LOUDNESS_EXPONENT);
        }
        self.match_db = if before > 0.0 && after > 0.0 {
            ((10.0 / LOUDNESS_EXPONENT * (before / after).log10()) as f32).clamp(-MAX_MATCH_DB, MAX_MATCH_DB)
        } else {
            0.0
        };
    }
}

/// The 24 peaking filters at one sample rate: one per band, a little wider
/// than the band, and how much each one reaches into its neighbours.
pub struct FilterBank {
    pub active: [bool; N],
    cos_w: [f64; N],
    alpha: [f64; N],
    /// overlap[j][k]: dB at band k's centre per dB of filter j's gain.
    overlap: [[f32; N]; N],
}

impl FilterBank {
    pub fn new(bands: &Bands, sample_rate: f64) -> Self {
        let mut bank = FilterBank { active: [false; N], cos_w: [1.0; N], alpha: [0.0; N], overlap: [[0.0; N]; N] };
        bank.configure(bands, sample_rate);
        bank
    }

    pub fn configure(&mut self, bands: &Bands, sample_rate: f64) {
        for k in 0..N {
            let fc = bands.centers_hz[k] as f64;
            self.active[k] = fc < sample_rate * 0.45;
            let width = (bands.edges_hz[k + 1] - bands.edges_hz[k]) as f64;
            let q = fc / width * Q_SCALE;
            let w = 2.0 * PI * fc / sample_rate;
            self.cos_w[k] = w.cos();
            // RBJ's bandwidth form with its w/sin(w) term: treble filters stay as
            // wide as asked even where the bilinear transform squeezes them.
            let octaves = 2.0 / std::f64::consts::LN_2 * (1.0 / (2.0 * q)).asinh();
            self.alpha[k] = if w >= PI || !self.active[k] {
                0.0
            } else {
                w.sin() * (std::f64::consts::LN_2 / 2.0 * octaves * w / w.sin()).sinh()
            };
        }
        for j in 0..N {
            for k in 0..N {
                self.overlap[j][k] = if !self.active[j] || !self.active[k] {
                    0.0
                } else {
                    self.filter_db(j, REFERENCE_DB, bands.centers_hz[k] as f64, sample_rate) / REFERENCE_DB
                };
            }
        }
    }

    /// RBJ peaking filter for band `k` at `gain_db` (unity when 0 or inactive).
    pub fn coefficients(&self, k: usize, gain_db: f32) -> Biquad {
        if gain_db == 0.0 || !self.active[k] {
            return Biquad::default();
        }
        let a = 10f64.powf(gain_db as f64 / 40.0);
        let al = self.alpha[k];
        let a0 = 1.0 + al / a;
        let b1 = -2.0 * self.cos_w[k] / a0;
        Biquad { b0: (1.0 + al * a) / a0, b1, b2: (1.0 - al * a) / a0, a1: b1, a2: (1.0 - al / a) / a0 }
    }

    fn filter_db(&self, k: usize, gain_db: f32, hz: f64, sample_rate: f64) -> f32 {
        crate::design::magnitude_at(&self.coefficients(k, gain_db), sample_rate, hz).max(1e-12).log10() as f32 * 20.0
    }

    /// The bank's response at each band centre for filter gains `gains_db`.
    pub fn at_centers(&self, gains_db: &[f32; N], out: &mut [f32; N]) {
        for k in 0..N {
            let mut sum = 0.0f32;
            for j in 0..N {
                sum += self.overlap[j][k] * gains_db[j];
            }
            out[k] = sum;
        }
    }

    /// Filter gains in `out` that put the bank's response on `target_db` at each band centre.
    pub fn fit(&self, target_db: &[f32; N], out: &mut [f32; N]) {
        let mut reached = [0.0f32; N];
        for k in 0..N {
            out[k] = if self.active[k] { target_db[k] } else { 0.0 };
        }
        for _ in 0..FIT_ROUNDS {
            self.at_centers(out, &mut reached);
            for k in 0..N {
                if self.active[k] {
                    out[k] = (out[k] + FIT_STEP * (target_db[k] - reached[k])).clamp(-MAX_FILTER_DB, MAX_FILTER_DB);
                }
            }
        }
    }
}

fn alpha_for(seconds: f64, step_seconds: f64) -> f64 {
    1.0 - (-step_seconds / seconds).exp()
}

/// Clarity inside the engine: the audio thread's state.
pub struct ClarityStage {
    bands: Bands,
    model: Model,
    bank: FilterBank,
    fft: Fft,
    window: Box<[f32]>,
    ring: Box<[f32]>,
    re: Box<[f32]>,
    im: Box<[f32]>,
    ring_pos: usize,
    filled: usize,
    since_hop: usize,
    decimation: usize,
    decimation_sum: f32,
    decimation_count: usize,
    analysis_rate: f64,
    bin_from: [usize; N],
    bin_to: [usize; N],
    power_scale: f32,
    band_power: [f32; N],
    fitted: [f32; N],

    sample_rate: f64,
    channels: usize,
    enabled: bool,
    recover_weight: f32,
    tame_weight: f32,
    /// Filter gains playing now, dB.
    applied: [f32; N],
    /// The bank's response at the band centres for `applied`, dB.
    response: [f32; N],
    coefficients: [Biquad; N],
    state: [[[f64; 2]; N]; MAX_CHANNELS],
    glide_frames: usize,
    frames_to_glide: usize,
    attack: f64,
    release: f64,
    match_follow: f64,
    level_follow: f64,
    match_db: f64,
    output_db: f64,
    output_gain: f64,
    engaged: bool,
    /// Something the UI shows changed since it was last published.
    pub dirty: bool,
}

impl ClarityStage {
    pub fn new() -> Self {
        let bands = Bands::new();
        let window: Box<[f32]> =
            (0..ANALYSIS_FFT).map(|n| (0.5 - 0.5 * (2.0 * PI * n as f64 / ANALYSIS_FFT as f64).cos()) as f32).collect();
        let window_power: f64 = window.iter().map(|w| (*w as f64) * (*w as f64)).sum();
        let mut stage = ClarityStage {
            model: Model::new(&bands),
            bank: FilterBank::new(&bands, 48_000.0),
            bands,
            fft: Fft::new(ANALYSIS_FFT),
            window,
            ring: vec![0.0; ANALYSIS_FFT].into_boxed_slice(),
            re: vec![0.0; ANALYSIS_FFT].into_boxed_slice(),
            im: vec![0.0; ANALYSIS_FFT].into_boxed_slice(),
            ring_pos: 0,
            filled: 0,
            since_hop: 0,
            decimation: 1,
            decimation_sum: 0.0,
            decimation_count: 0,
            analysis_rate: 48_000.0,
            bin_from: [1; N],
            bin_to: [2; N],
            // Turns summed |X|² into mean power: a full-scale sine reads 0.5 in its band.
            power_scale: (2.0 / (ANALYSIS_FFT as f64 * window_power)) as f32,
            band_power: [0.0; N],
            fitted: [0.0; N],
            sample_rate: 48_000.0,
            channels: 2,
            enabled: false,
            recover_weight: 1.0,
            tame_weight: 1.0,
            applied: [0.0; N],
            response: [0.0; N],
            coefficients: [Biquad::default(); N],
            state: [[[0.0; 2]; N]; MAX_CHANNELS],
            glide_frames: 64,
            frames_to_glide: 64,
            attack: 0.0,
            release: 0.0,
            match_follow: 0.0,
            level_follow: 0.0,
            match_db: 0.0,
            output_db: 0.0,
            output_gain: 1.0,
            engaged: false,
            dirty: true,
        };
        stage.configure(48_000, 2);
        stage
    }

    /// A new format. Allocation-free. The gains playing are kept (they are in
    /// dB, rate-independent) and their filters redesigned for the new rate.
    pub fn configure(&mut self, sample_rate: i32, channels: usize) {
        let sr = sample_rate as f64;
        self.sample_rate = sr;
        self.channels = channels.clamp(1, MAX_CHANNELS);
        self.bank.configure(&self.bands, sr);
        self.decimation = ((sr / ANALYSIS_TARGET_RATE).round() as usize).max(1);
        self.analysis_rate = sr / self.decimation as f64;
        let bin_hz = self.analysis_rate / ANALYSIS_FFT as f64;
        let last = ANALYSIS_FFT / 2;
        for k in 0..N {
            let lo = ((self.bands.edges_hz[k] as f64 / bin_hz).ceil() as usize).clamp(1, last);
            let hi = ((self.bands.edges_hz[k + 1] as f64 / bin_hz).ceil() as usize).clamp(1, last + 1);
            if hi > lo {
                self.bin_from[k] = lo;
                self.bin_to[k] = hi;
            } else {
                let nearest = ((self.bands.centers_hz[k] as f64 / bin_hz) as usize).clamp(1, last);
                self.bin_from[k] = nearest;
                self.bin_to[k] = nearest + 1;
            }
        }
        self.glide_frames = ((sr / GLIDE_PER_SECOND).round() as usize).max(1);
        let step = self.glide_frames as f64 / sr;
        self.attack = alpha_for(ATTACK_S, step);
        self.release = alpha_for(RELEASE_S, step);
        self.match_follow = alpha_for(MATCH_S, step);
        self.level_follow = alpha_for(LEVEL_S, step);
        for k in 0..N {
            if !self.bank.active[k] {
                self.applied[k] = 0.0;
            }
            self.coefficients[k] = self.bank.coefficients(k, self.applied[k]);
        }
        self.bank.at_centers(&self.applied, &mut self.response);
        self.reset();
        self.dirty = true;
    }

    /// As after a seek: the filters and the analysis window start over; the
    /// gains playing and the song's learned balance stay.
    pub fn reset(&mut self) {
        self.state = [[[0.0; 2]; N]; MAX_CHANNELS];
        self.ring.fill(0.0);
        self.ring_pos = 0;
        self.filled = 0;
        self.since_hop = 0;
        self.decimation_sum = 0.0;
        self.decimation_count = 0;
        self.frames_to_glide = self.glide_frames;
    }

    /// Non-finite state somewhere in the engine: back to flat and a fresh model.
    pub fn heal(&mut self) {
        self.reset();
        self.model.clear();
        self.fitted = [0.0; N];
        self.applied = [0.0; N];
        self.response = [0.0; N];
        self.coefficients = [Biquad::default(); N];
        self.match_db = 0.0;
        self.output_db = 0.0;
        self.output_gain = 1.0;
        self.engaged = false;
        self.dirty = true;
    }

    pub fn healthy(&self) -> bool {
        self.output_gain.is_finite()
            && self.state[..self.channels]
                .iter()
                .all(|bands| bands.iter().all(|s| s[0].is_finite() && s[1].is_finite()))
    }

    /// The listener's settings, read at the start of every block.
    pub fn set_controls(&mut self, enabled: bool, recover_weight: f32, tame_weight: f32) {
        if enabled != self.enabled {
            self.enabled = enabled;
            if !enabled {
                // The bands glide back to flat; the model rests until it is wanted again.
                self.fitted = [0.0; N];
            } else {
                // Start listening from a full window of what plays from now on.
                self.filled = 0;
                self.since_hop = 0;
            }
            self.dirty = true;
        }
        self.recover_weight = recover_weight;
        self.tame_weight = tame_weight;
    }

    /// A new song starts: forget the last one's balance.
    pub fn new_song(&mut self) {
        self.model.new_song();
    }

    pub fn enabled(&self) -> bool {
        self.enabled
    }

    /// The filters or the level are doing something (or gliding back to flat).
    #[inline]
    pub fn engaged(&self) -> bool {
        self.engaged
    }

    /// One frame of what reaches the stage, mixed to mono (feed-forward analysis).
    #[inline]
    pub fn listen(&mut self, mono: f32) {
        if !self.enabled {
            return;
        }
        self.decimation_sum += if mono.is_finite() { mono } else { 0.0 };
        self.decimation_count += 1;
        if self.decimation_count < self.decimation {
            return;
        }
        let x = self.decimation_sum / self.decimation as f32;
        self.decimation_sum = 0.0;
        self.decimation_count = 0;
        self.ring[self.ring_pos] = x;
        self.ring_pos = (self.ring_pos + 1) & (ANALYSIS_FFT - 1);
        if self.filled < ANALYSIS_FFT {
            self.filled += 1;
        }
        self.since_hop += 1;
        if self.since_hop >= ANALYSIS_HOP {
            self.since_hop = 0;
            if self.filled == ANALYSIS_FFT {
                self.analyze();
            }
        }
    }

    fn analyze(&mut self) {
        for i in 0..ANALYSIS_FFT {
            self.re[i] = self.ring[(self.ring_pos + i) & (ANALYSIS_FFT - 1)] * self.window[i];
            self.im[i] = 0.0;
        }
        self.fft.forward(&mut self.re, &mut self.im);
        for k in 0..N {
            let mut sum = 0.0f32;
            for b in self.bin_from[k]..self.bin_to[k] {
                sum += self.re[b] * self.re[b] + self.im[b] * self.im[b];
            }
            self.band_power[k] = sum * self.power_scale;
        }
        let seconds = (ANALYSIS_HOP as f64 / self.analysis_rate) as f32;
        self.model.analyze(&self.band_power, seconds, self.recover_weight, self.tame_weight);
        self.bank.fit(&self.model.target_db, &mut self.fitted);
        self.dirty = true;
    }

    /// Called before every block of `frames`: the bands move towards their
    /// targets every ~1.3 ms (between frames, so the filters keep their state).
    pub fn advance(&mut self, frames: usize) {
        if self.frames_to_glide > frames {
            self.frames_to_glide -= frames;
            return;
        }
        self.frames_to_glide = self.glide_frames;
        if !self.enabled && !self.engaged {
            return;
        }
        self.glide();
    }

    fn glide(&mut self) {
        let mut moving = false;
        for k in 0..N {
            let target = if self.enabled { self.fitted[k] } else { 0.0 };
            let now = self.applied[k];
            if target == now {
                moving |= now != 0.0;
                continue;
            }
            let rate = if target.abs() > now.abs() { self.attack } else { self.release } as f32;
            let mut next = now + (target - now) * rate;
            if next.abs() < 0.01 && target.abs() < 0.01 {
                next = 0.0;
            }
            if next == 0.0 {
                for c in 0..self.channels {
                    self.state[c][k] = [0.0; 2];
                }
            }
            self.applied[k] = next;
            self.coefficients[k] = self.bank.coefficients(k, next);
            moving |= next != 0.0;
        }
        // The level follows the model slowly, but goes back with the bands when Clarity is turned off.
        let (match_target, match_follow) =
            if self.enabled { (self.model.match_db as f64, self.match_follow) } else { (0.0, self.release) };
        self.match_db += (match_target - self.match_db) * match_follow;
        if match_target == 0.0 && self.match_db.abs() < SNAP_DB {
            self.match_db = 0.0;
        }
        self.output_db += (self.match_db - self.output_db) * self.level_follow;
        if self.match_db == 0.0 && self.output_db.abs() < SNAP_DB {
            self.output_db = 0.0;
        }
        self.output_gain = if self.output_db == 0.0 { 1.0 } else { 10f64.powf(self.output_db / 20.0) };
        self.bank.at_centers(&self.applied, &mut self.response);
        self.engaged = moving || self.output_db != 0.0;
        self.dirty = true;
    }

    /// One sample of channel `c` through the bands and the level match.
    #[inline]
    pub fn process(&mut self, c: usize, x: f64) -> f64 {
        let mut y = x;
        let state = &mut self.state[c];
        for k in 0..N {
            if self.applied[k] == 0.0 {
                continue;
            }
            y = self.coefficients[k].run(&mut state[k], y);
        }
        y * self.output_gain
    }

    /// The stage's response (band shape and level), in power, at `hz`: for the
    /// engine's headroom, which must know what Clarity adds where.
    pub fn power_at(&self, hz: f32) -> f64 {
        let db = self.bands.value_at(&self.response, hz) as f64 + self.output_db;
        10f64.powf(db / 10.0)
    }

    pub fn bands(&self) -> &Bands {
        &self.bands
    }

    /// The bank's response at each band centre now, dB.
    pub fn response_db(&self) -> &[f32; N] {
        &self.response
    }

    pub fn applied_db(&self) -> &[f32; N] {
        &self.applied
    }

    pub fn model(&self) -> &Model {
        &self.model
    }

    /// The level match playing now, dB.
    pub fn output_db(&self) -> f64 {
        self.output_db
    }
}

impl Default for ClarityStage {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bands_run_from_40_hz_to_16_khz_rising() {
        let b = Bands::new();
        assert!((b.edges_hz[0] - LOW_HZ).abs() < 0.5);
        assert!((b.edges_hz[N] - HIGH_HZ).abs() < 5.0);
        for k in 1..N {
            assert!(b.centers_hz[k] > b.centers_hz[k - 1]);
        }
    }

    #[test]
    fn weights_are_one_each_at_the_default() {
        assert_eq!(weights(0.5, 0.0), (1.0, 1.0));
        assert_eq!(weights(0.0, 1.0), (0.0, 0.0));
        let (r, t) = weights(1.0, 1.0);
        assert!((r - 3.2).abs() < 1e-6 && (t - 0.8).abs() < 1e-6);
        assert_eq!(weights(f32::NAN, f32::NAN), (0.0, 0.0));
    }

    #[test]
    fn fitted_filters_land_on_an_even_lift() {
        let bands = Bands::new();
        let bank = FilterBank::new(&bands, 48_000.0);
        let target = [3.0f32; N];
        let mut gains = [0.0f32; N];
        bank.fit(&target, &mut gains);
        let mut reached = [0.0f32; N];
        bank.at_centers(&gains, &mut reached);
        for k in 2..N - 2 {
            assert!((reached[k] - 3.0).abs() < 0.5, "band {k}: {}", reached[k]);
        }
    }
}
