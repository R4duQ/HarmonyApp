//! AcousticExtractor: decoded PCM -> the 24 raw acoustic features.
//!
//! ## The 24 dimensions
//!
//!   0-9    mean spectral profile: the RMS level of each octave band (31 Hz to
//!          16 kHz, the same band-pass bank as the EQ engine's live analyser),
//!          L2-normalized across the ten bands
//!   10-19  groove dynamics: how much each band's level moves from one 50 ms
//!          block to the next (coefficient of variation, mapped to 0..1)
//!   20     sub-bass punch: rises of the sub-bass (below 80 Hz) envelope
//!          against the broadband level
//!   21     spectral centroid on the octave axis (0 = dark, 1 = bright)
//!   22     crest factor (peak to RMS), 3..30 dB mapped to 0..1
//!   23     pulse rate: detected onsets per second, mapped to 0..1

use crate::engine::clamp01;
use crate::types::{BANDS, BAND_CENTERS_HZ, DIMS};

const PI: f64 = std::f64::consts::PI;

#[derive(Clone, Copy, Default)]
struct Biquad {
    b0: f64,
    b1: f64,
    b2: f64,
    a1: f64,
    a2: f64,
    z1: f64,
    z2: f64,
    enabled: bool,
}

impl Biquad {
    #[inline(always)]
    fn run(&mut self, x: f64) -> f64 {
        let y = self.b0 * x + self.z1;
        self.z1 = self.b1 * x - self.a1 * y + self.z2;
        self.z2 = self.b2 * x - self.a2 * y;
        y
    }

    /// Flush denormal-range filter state left by silence.
    fn flush(&mut self) {
        if self.z1.abs() < 1e-30 {
            self.z1 = 0.0;
        }
        if self.z2.abs() < 1e-30 {
            self.z2 = 0.0;
        }
    }
}

/// Streams mono PCM (any sample rate from 8 kHz to 768 kHz) into the 24 raw
/// features. Allocation-free after construction, so it can share the
/// indexer's decode loop with the older analyser.
pub struct AcousticExtractor {
    sample_rate: i32,
    block_length: i32,
    block_fill: i32,
    bands: [Biquad; BANDS],
    sub_low: Biquad,
    block_band_power: [f64; BANDS],
    block_broad_power: f64,
    block_sub_power: f64,
    block_peak: f64,

    // Whole-song accumulators over non-silent blocks.
    active_blocks: i64,
    band_power_sum: [f64; BANDS],
    band_amp_sum: [f64; BANDS],
    band_amp_sq_sum: [f64; BANDS],
    broad_power_sum: f64,
    broad_amp_sum: f64,
    sub_rise_sum: f64,
    previous_sub_amp: f64,
    peak: f64,

    // Onset detection over the band levels.
    previous_log_amp: [f64; BANDS],
    have_previous_block: bool,
    flux_history: [f64; 20],
    flux_history_fill: usize,
    flux_history_pos: usize,
    previous_flux: f64,
    previous_threshold: f64,
    previous_rising: bool,
    onsets: i64,
}

impl AcousticExtractor {
    /// An extractor for `sample_rate`; check [`valid`](Self::valid) (8 kHz..768 kHz).
    pub fn new(sample_rate: i32) -> Self {
        let mut e = AcousticExtractor {
            sample_rate: 0,
            block_length: 0,
            block_fill: 0,
            bands: [Biquad::default(); BANDS],
            sub_low: Biquad::default(),
            block_band_power: [0.0; BANDS],
            block_broad_power: 0.0,
            block_sub_power: 0.0,
            block_peak: 0.0,
            active_blocks: 0,
            band_power_sum: [0.0; BANDS],
            band_amp_sum: [0.0; BANDS],
            band_amp_sq_sum: [0.0; BANDS],
            broad_power_sum: 0.0,
            broad_amp_sum: 0.0,
            sub_rise_sum: 0.0,
            previous_sub_amp: 0.0,
            peak: 0.0,
            previous_log_amp: [0.0; BANDS],
            have_previous_block: false,
            flux_history: [0.0; 20],
            flux_history_fill: 0,
            flux_history_pos: 0,
            previous_flux: 0.0,
            previous_threshold: 0.0,
            previous_rising: false,
            onsets: 0,
        };
        if !(8000..=768000).contains(&sample_rate) {
            return e;
        }
        e.sample_rate = sample_rate;
        e.block_length = (sample_rate / 20).max(1); // 50 ms
        let sr = sample_rate as f64;

        // The EQ engine's analysis bank: RBJ band-passes, one octave wide (Q 1.414).
        const Q: f64 = 1.414;
        for (k, b) in e.bands.iter_mut().enumerate() {
            let fc = BAND_CENTERS_HZ[k] as f64;
            if fc >= 0.45 * sr {
                continue; // above what this file can hold
            }
            let w0 = 2.0 * PI * fc / sr;
            let alpha = w0.sin() / (2.0 * Q);
            let a0 = 1.0 + alpha;
            b.b0 = alpha / a0;
            b.b1 = 0.0;
            b.b2 = -alpha / a0;
            b.a1 = -2.0 * w0.cos() / a0;
            b.a2 = (1.0 - alpha) / a0;
            b.enabled = true;
        }

        // Sub-bass: 80 Hz Butterworth low-pass, for the punch measurement.
        let w0 = 2.0 * PI * 80.0 / sr;
        let alpha = w0.sin() / (2.0 * std::f64::consts::FRAC_1_SQRT_2); // 0.7071067811865476
        let a0 = 1.0 + alpha;
        let c = w0.cos();
        e.sub_low.b0 = (1.0 - c) / 2.0 / a0;
        e.sub_low.b1 = (1.0 - c) / a0;
        e.sub_low.b2 = e.sub_low.b0;
        e.sub_low.a1 = -2.0 * c / a0;
        e.sub_low.a2 = (1.0 - alpha) / a0;
        e.sub_low.enabled = true;
        e
    }

    pub fn valid(&self) -> bool {
        self.sample_rate > 0
    }

    /// [-1, 1] mono samples. Non-finite samples count as silence.
    pub fn process(&mut self, mono: &[f32]) {
        if !self.valid() {
            return;
        }
        for &sample in mono {
            let mut x = sample as f64;
            if !x.is_finite() {
                x = 0.0;
            }
            x = 4.0f64.min((-4.0f64).max(x));
            for k in 0..BANDS {
                let b = &mut self.bands[k];
                if !b.enabled {
                    continue;
                }
                let y = b.run(x);
                self.block_band_power[k] += y * y;
            }
            let s = self.sub_low.run(x);
            self.block_sub_power += s * s;
            self.block_broad_power += x * x;
            self.block_peak = self.block_peak.max(x.abs());
            self.block_fill += 1;
            if self.block_fill >= self.block_length {
                self.close_block();
            }
        }
    }

    fn close_block(&mut self) {
        let n = self.block_fill.max(1) as f64;
        let broad_power = self.block_broad_power / n;

        for b in self.bands.iter_mut() {
            b.flush();
        }
        self.sub_low.flush();

        #[allow(clippy::neg_cmp_op_on_partial_ord)] // also true for NaN, on purpose
        let silent = !(broad_power >= 1e-7); // below -70 dBFS (or NaN)
        if !silent {
            self.active_blocks += 1;
            let mut flux = 0.0f64;
            let mut enabled_bands = 0;
            for k in 0..BANDS {
                if !self.bands[k].enabled {
                    continue;
                }
                enabled_bands += 1;
                let p = self.block_band_power[k] / n;
                let a = p.max(0.0).sqrt();
                self.band_power_sum[k] += p;
                self.band_amp_sum[k] += a;
                self.band_amp_sq_sum[k] += a * a;
                let log_amp = 10.0 * (p + 1e-10).log10();
                if self.have_previous_block {
                    flux += 0.0f64.max(log_amp - self.previous_log_amp[k]);
                }
                self.previous_log_amp[k] = log_amp;
            }
            if enabled_bands > 0 {
                flux /= enabled_bands as f64;
            }
            self.broad_power_sum += broad_power;
            self.broad_amp_sum += broad_power.sqrt();

            let sub_amp = 0.0f64.max(self.block_sub_power / n).sqrt();
            if self.have_previous_block {
                self.sub_rise_sum += 0.0f64.max(sub_amp - self.previous_sub_amp);
            }
            self.previous_sub_amp = sub_amp;
            self.peak = self.peak.max(self.block_peak);

            // Onsets: a local maximum of the band flux above its adaptive threshold
            // (1.5x the last second's average plus 1 dB). The block before this one
            // is judged now that we know it was a peak.
            let mut mean = 0.0f64;
            for &f in &self.flux_history[..self.flux_history_fill] {
                mean += f;
            }
            if self.flux_history_fill > 0 {
                mean /= self.flux_history_fill as f64;
            }
            let threshold = 1.5 * mean + 1.0;
            if self.have_previous_block
                && self.previous_rising
                && self.previous_flux > self.previous_threshold
                && self.previous_flux >= flux
            {
                self.onsets += 1;
            }
            self.previous_rising = flux > self.previous_flux;
            self.previous_flux = flux;
            self.previous_threshold = threshold;
            self.flux_history[self.flux_history_pos] = flux;
            self.flux_history_pos = (self.flux_history_pos + 1) % 20;
            self.flux_history_fill = (self.flux_history_fill + 1).min(20);
            self.have_previous_block = true;
        }

        self.block_band_power = [0.0; BANDS];
        self.block_broad_power = 0.0;
        self.block_sub_power = 0.0;
        self.block_peak = 0.0;
        self.block_fill = 0;
    }

    /// Seconds of non-silent audio measured so far.
    pub fn active_seconds(&self) -> f64 {
        if !self.valid() {
            return 0.0;
        }
        self.active_blocks as f64 * self.block_length as f64 / self.sample_rate as f64
    }

    /// The 24 raw features (each in [0, 1]), or None when there were fewer
    /// than two seconds of non-silent audio to measure.
    pub fn finish(&mut self) -> Option<[f32; DIMS]> {
        if !self.valid() {
            return None;
        }
        if self.block_fill >= self.block_length / 2 {
            self.close_block();
        }
        if self.active_blocks < 40 {
            return None; // under two seconds of sound
        }
        let blocks = self.active_blocks as f64;
        let mut out = [0.0f32; DIMS];

        // 0-9: each band's RMS level, L2-normalized across the bands.
        let mut amp = [0.0f64; BANDS];
        let mut norm = 0.0f64;
        for k in 0..BANDS {
            amp[k] = (self.band_power_sum[k] / blocks).max(0.0).sqrt();
            norm += amp[k] * amp[k];
        }
        let norm = norm.sqrt();
        #[allow(clippy::neg_cmp_op_on_partial_ord)]
        if !(norm > 1e-12) {
            return None;
        }
        for k in 0..BANDS {
            out[k] = clamp01((amp[k] / norm) as f32);
        }

        // 10-19: groove dynamics, the coefficient of variation of each band's level.
        for k in 0..BANDS {
            let mean = self.band_amp_sum[k] / blocks;
            let variance = (self.band_amp_sq_sum[k] / blocks - mean * mean).max(0.0);
            let cv = if mean > 1e-9 { variance.sqrt() / mean } else { 0.0 };
            out[BANDS + k] = clamp01((cv / (1.0 + cv)) as f32);
        }

        // 20: sub-bass punch, rises of the sub-bass envelope against the broadband level.
        let broad_amp = self.broad_amp_sum / blocks;
        let punch = if broad_amp > 1e-9 { (self.sub_rise_sum / blocks) / broad_amp } else { 0.0 };
        out[20] = clamp01((punch / (punch + 0.05)) as f32);

        // 21: spectral centroid on the octave axis.
        let (mut weighted, mut total) = (0.0f64, 0.0f64);
        for k in 0..BANDS {
            let p = self.band_power_sum[k] / blocks;
            weighted += k as f64 * p;
            total += p;
        }
        out[21] = clamp01(if total > 0.0 { (weighted / total / (BANDS - 1) as f64) as f32 } else { 0.5 });

        // 22: crest factor, 3..30 dB.
        let rms = (self.broad_power_sum / blocks).sqrt();
        let crest_db = if rms > 1e-9 && self.peak > 0.0 { 20.0 * (self.peak / rms).log10() } else { 3.0 };
        out[22] = clamp01(((crest_db - 3.0) / 27.0) as f32);

        // 23: pulse rate, onsets per second (3 per second maps to 0.5).
        let seconds = self.active_seconds().max(1e-3);
        let rate = self.onsets as f64 / seconds;
        out[23] = clamp01((rate / (rate + 3.0)) as f32);

        for v in out.iter_mut() {
            if !v.is_finite() {
                *v = 0.0;
            }
        }
        Some(out)
    }
}
