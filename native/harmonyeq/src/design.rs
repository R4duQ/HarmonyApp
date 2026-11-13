//! Biquad filter designs (RBJ Audio EQ Cookbook) and the small numeric
//! helpers the engine shares. Ported from MobileEqEngine.cpp.

use crate::{BAND_FREQUENCIES, NUM_BANDS};

pub(crate) const PI: f64 = std::f64::consts::PI;
pub(crate) const BUTTERWORTH_Q: f64 = std::f64::consts::FRAC_1_SQRT_2;

// Stage 3. Band 0 is a low shelf and band 9 a high shelf, both Q = 0.707 with
// their corners on the band frequencies (31 Hz, 16 kHz); bands 1-8 are peaks.
const SHELF_Q: f64 = BUTTERWORTH_Q;
const PEAK_Q_AT_FLAT: f64 = 1.2;
const PEAK_Q_GROWTH: f64 = 0.8; // Q at +-12 dB = 1.2 + 0.8

/**
 * Biquad coefficients, normalised so a0 = 1 (Direct Form II Transposed).
 *
 * Double precision on purpose: at 192-384 kHz the 10 Hz high-pass and the
 * 31 Hz shelf put their poles within 1e-4 of the unit circle, where float
 * coefficients and state shift the corner and add noise. Scalar double costs
 * the same as float on ARMv8. Audio in and out stays 32-bit float.
 */
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Biquad {
    pub b0: f64,
    pub b1: f64,
    pub b2: f64,
    pub a1: f64,
    pub a2: f64,
}

impl Default for Biquad {
    fn default() -> Self {
        Biquad { b0: 1.0, b1: 0.0, b2: 0.0, a1: 0.0, a2: 0.0 }
    }
}

impl Biquad {
    fn normalised(b0: f64, b1: f64, b2: f64, a0: f64, a1: f64, a2: f64) -> Self {
        Biquad { b0: b0 / a0, b1: b1 / a0, b2: b2 / a0, a1: a1 / a0, a2: a2 / a0 }
    }

    /// Direct Form II Transposed: two state variables, the best-behaved direct form.
    #[inline(always)]
    pub(crate) fn run(&self, s: &mut [f64; 2], x: f64) -> f64 {
        let y = self.b0 * x + s[0];
        s[0] = self.b1 * x - self.a1 * y + s[1];
        s[1] = self.b2 * x - self.a2 * y;
        y
    }

    #[inline(always)]
    pub(crate) fn approach(&mut self, target: &Biquad, alpha: f64) {
        self.b0 += (target.b0 - self.b0) * alpha;
        self.b1 += (target.b1 - self.b1) * alpha;
        self.b2 += (target.b2 - self.b2) * alpha;
        self.a1 += (target.a1 - self.a1) * alpha;
        self.a2 += (target.a2 - self.a2) * alpha;
    }

    pub(crate) fn settled(&self, other: &Biquad) -> bool {
        const EPSILON: f64 = 1e-12;
        (self.b0 - other.b0).abs() < EPSILON
            && (self.b1 - other.b1).abs() < EPSILON
            && (self.b2 - other.b2).abs() < EPSILON
            && (self.a1 - other.a1).abs() < EPSILON
            && (self.a2 - other.a2).abs() < EPSILON
    }
}

#[inline]
fn nyquist_safe(sample_rate: f64, frequency: f64) -> f64 {
    frequency.min(sample_rate * 0.45)
}

#[inline]
fn w0(sample_rate: f64, frequency: f64) -> f64 {
    2.0 * PI * nyquist_safe(sample_rate, frequency) / sample_rate
}

pub fn design_peaking(sample_rate: f64, frequency: f64, gain_db: f64, q: f64) -> Biquad {
    let a = 10.0f64.powf(gain_db / 40.0);
    let w0 = w0(sample_rate, frequency);
    let cos_w = w0.cos();
    let alpha = w0.sin() / (2.0 * q);
    Biquad::normalised(1.0 + alpha * a, -2.0 * cos_w, 1.0 - alpha * a, 1.0 + alpha / a, -2.0 * cos_w, 1.0 - alpha / a)
}

pub fn design_low_shelf(sample_rate: f64, frequency: f64, gain_db: f64, q: f64) -> Biquad {
    let a = 10.0f64.powf(gain_db / 40.0);
    let w0 = w0(sample_rate, frequency);
    let cos_w = w0.cos();
    let two_sqrt_a_alpha = 2.0 * a.sqrt() * w0.sin() / (2.0 * q);
    Biquad::normalised(
        a * ((a + 1.0) - (a - 1.0) * cos_w + two_sqrt_a_alpha),
        2.0 * a * ((a - 1.0) - (a + 1.0) * cos_w),
        a * ((a + 1.0) - (a - 1.0) * cos_w - two_sqrt_a_alpha),
        (a + 1.0) + (a - 1.0) * cos_w + two_sqrt_a_alpha,
        -2.0 * ((a - 1.0) + (a + 1.0) * cos_w),
        (a + 1.0) + (a - 1.0) * cos_w - two_sqrt_a_alpha,
    )
}

pub fn design_high_shelf(sample_rate: f64, frequency: f64, gain_db: f64, q: f64) -> Biquad {
    let a = 10.0f64.powf(gain_db / 40.0);
    let w0 = w0(sample_rate, frequency);
    let cos_w = w0.cos();
    let two_sqrt_a_alpha = 2.0 * a.sqrt() * w0.sin() / (2.0 * q);
    Biquad::normalised(
        a * ((a + 1.0) + (a - 1.0) * cos_w + two_sqrt_a_alpha),
        -2.0 * a * ((a - 1.0) + (a + 1.0) * cos_w),
        a * ((a + 1.0) + (a - 1.0) * cos_w - two_sqrt_a_alpha),
        (a + 1.0) - (a - 1.0) * cos_w + two_sqrt_a_alpha,
        2.0 * ((a - 1.0) - (a + 1.0) * cos_w),
        (a + 1.0) - (a - 1.0) * cos_w - two_sqrt_a_alpha,
    )
}

pub fn design_high_pass(sample_rate: f64, frequency: f64, q: f64) -> Biquad {
    let w0 = w0(sample_rate, frequency);
    let cos_w = w0.cos();
    let alpha = w0.sin() / (2.0 * q);
    Biquad::normalised((1.0 + cos_w) / 2.0, -(1.0 + cos_w), (1.0 + cos_w) / 2.0, 1.0 + alpha, -2.0 * cos_w, 1.0 - alpha)
}

/// Band-pass with 0 dB at the centre (RBJ, constant peak gain).
pub fn design_band_pass(sample_rate: f64, frequency: f64, q: f64) -> Biquad {
    let w0 = w0(sample_rate, frequency);
    let alpha = w0.sin() / (2.0 * q);
    Biquad::normalised(alpha, 0.0, -alpha, 1.0 + alpha, -2.0 * w0.cos(), 1.0 - alpha)
}

pub fn design_low_pass(sample_rate: f64, frequency: f64, q: f64) -> Biquad {
    let w0 = w0(sample_rate, frequency);
    let cos_w = w0.cos();
    let alpha = w0.sin() / (2.0 * q);
    Biquad::normalised((1.0 - cos_w) / 2.0, 1.0 - cos_w, (1.0 - cos_w) / 2.0, 1.0 + alpha, -2.0 * cos_w, 1.0 - alpha)
}

/// |H(e^jw)| of one biquad at `frequency`.
pub fn magnitude_at(f: &Biquad, sample_rate: f64, frequency: f64) -> f64 {
    let w = 2.0 * PI * frequency / sample_rate;
    let (c1, s1) = (w.cos(), w.sin());
    let (c2, s2) = ((2.0 * w).cos(), (2.0 * w).sin());
    let num_re = f.b0 + f.b1 * c1 + f.b2 * c2;
    let num_im = -(f.b1 * s1 + f.b2 * s2);
    let den_re = 1.0 + f.a1 * c1 + f.a2 * c2;
    let den_im = -(f.a1 * s1 + f.a2 * s2);
    ((num_re * num_re + num_im * num_im) / (den_re * den_re + den_im * den_im)).sqrt()
}

/// Q of a peaking band at `gain_db`: narrower as the gain grows, so neighbours don't pile up.
pub fn proportional_q(gain_db: f64) -> f64 {
    PEAK_Q_AT_FLAT + PEAK_Q_GROWTH * gain_db.abs().min(12.0) / 12.0
}

/// The design of EQ band `band` at `gain_db` (shelves at the ends, peaks between).
pub fn design_band(band: usize, sample_rate: f64, gain_db: f64) -> Biquad {
    let frequency = BAND_FREQUENCIES[band] as f64;
    if band == 0 {
        design_low_shelf(sample_rate, frequency, gain_db, SHELF_Q)
    } else if band == NUM_BANDS - 1 {
        design_high_shelf(sample_rate, frequency, gain_db, SHELF_Q)
    } else {
        design_peaking(sample_rate, frequency, gain_db, proportional_q(gain_db))
    }
}

/// Padé approximation of tanh, exact at 0 and reaching 1 at |x| = 3, where it is clamped.
#[inline(always)]
pub fn pade_tanh(x: f32) -> f32 {
    if x > 3.0 {
        return 1.0;
    }
    if x < -3.0 {
        return -1.0;
    }
    let x2 = x * x;
    x * (27.0 + x2) / (27.0 + 9.0 * x2)
}

// std::max / std::min / std::clamp, with their exact NaN behaviour (the first
// argument wins when the comparison is false), which f32::max and friends do
// not share. The engine's NaN handling was written against these.
#[inline(always)]
pub(crate) fn smax<T: PartialOrd>(a: T, b: T) -> T {
    if a < b {
        b
    } else {
        a
    }
}

#[inline(always)]
pub(crate) fn smin<T: PartialOrd>(a: T, b: T) -> T {
    if b < a {
        b
    } else {
        a
    }
}

#[inline(always)]
pub(crate) fn sclamp<T: PartialOrd>(v: T, lo: T, hi: T) -> T {
    if v < lo {
        lo
    } else if hi < v {
        hi
    } else {
        v
    }
}
