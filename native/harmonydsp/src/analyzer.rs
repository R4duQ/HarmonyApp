//! Harmony's streaming audio analysis core (ported from harmony_dsp.cpp).
//!
//! Design contract:
//!  - Streaming API: [`Analyzer::new`] -> [`Analyzer::process`] (chunks of mono
//!    float PCM) -> [`Analyzer::finish`]. The Kotlin decoder feeds PCM as it
//!    comes off MediaCodec; the analyzer never holds the whole song in memory
//!    (we hold one FFT frame and small per-frame accumulators instead).
//!  - Output is a flat float array with a FIXED layout, mirrored exactly by
//!    FeatureLayout.kt in :domain:analysis. Any change here MUST bump
//!    ANALYSIS_VERSION there, which forces re-analysis of the library.
//!  - No allocations in the hot path after warm-up, apart from the per-frame
//!    flux and loudness lists (amortized, pre-reserved).
//!
//! Feature layout (index -> meaning), total [`FEATURE_COUNT`] floats:
//!   0  rms                (linear, 0..1)
//!   1  peak               (linear, 0..1)
//!   2  lufs               (integrated, ITU-R BS.1770 K-weighted approx, dB)
//!   3  dynamic_range      (dB between 95th and 10th percentile frame loudness)
//!   4  bass_energy        (fraction of spectral energy < 250 Hz)
//!   5  mid_energy         (fraction 250 Hz..4 kHz)
//!   6  treble_energy      (fraction > 4 kHz)
//!   7  spectral_centroid  (Hz, mean)
//!   8  spectral_rolloff   (Hz, mean, 85%)
//!   9  spectral_bandwidth (Hz, mean)
//!   10 spectral_flatness  (0..1, mean)
//!   11 spectral_contrast  (dB, mean peak-to-valley across bands)
//!   12 bpm                (60..200, 0 if undetected)
//!   13 beat_confidence    (0..1)
//!   14 zero_crossing_rate (mean, 0..1)
//!   15 key_index          (0=C..11=B, -1 if unknown)
//!   16 key_is_major       (1 major / 0 minor)
//!   17..28  chroma mean (12, L1-normalized)
//!   29..41  mfcc mean   (13)
//!   42..54  mfcc stddev (13)
//!
//! Numeric note: the arithmetic keeps the C++ original's float/double mix
//! step for step (where it computed in float, so does this, and likewise for
//! double), so the features match the C++ engine's to rounding.

// Index loops mirror the C++ original, which keeps the port easy to check.
#![allow(clippy::needless_range_loop)]

pub const FEATURE_COUNT: usize = 55;

const FFT_SIZE: usize = 2048;
const HOP_SIZE: usize = 1024;
const NUM_MELS: usize = 26;
const NUM_MFCC: usize = 13;
const NUM_CHROMA: usize = 12;
const NUM_BINS: usize = FFT_SIZE / 2 + 1;
const PI: f32 = std::f32::consts::PI;

/// Supported source sample rates (Hz); outside this, [`Analyzer::new`] fails.
pub const MIN_SAMPLE_RATE: i32 = 8_000;
pub const MAX_SAMPLE_RATE: i32 = 384_000;

// ------------------------------------------------------------------ FFT
// Iterative radix-2 Cooley-Tukey on split re/im. 2048-point real input; a
// split-radix real FFT would be ~2x faster, but decoding is the bottleneck, so
// we keep the simple, obviously-correct version.
fn fft(re: &mut [f32], im: &mut [f32]) {
    let n = re.len();
    let mut j = 0usize;
    for i in 1..n {
        let mut bit = n >> 1;
        while j & bit != 0 {
            j ^= bit;
            bit >>= 1;
        }
        j ^= bit;
        if i < j {
            re.swap(i, j);
            im.swap(i, j);
        }
    }
    let mut len = 2;
    while len <= n {
        let ang = -2.0f32 * PI / len as f32;
        let (wr, wi) = (ang.cos(), ang.sin());
        let half = len / 2;
        for i in (0..n).step_by(len) {
            let (mut cwr, mut cwi) = (1.0f32, 0.0f32);
            for k in 0..half {
                let (a, b) = (i + k, i + k + half);
                let (ur, ui) = (re[a], im[a]);
                let vr = re[b] * cwr - im[b] * cwi;
                let vi = re[b] * cwi + im[b] * cwr;
                re[a] = ur + vr;
                im[a] = ui + vi;
                re[b] = ur - vr;
                im[b] = ui - vi;
                let nwr = cwr * wr - cwi * wi;
                cwi = cwr * wi + cwi * wr;
                cwr = nwr;
            }
        }
        len <<= 1;
    }
}

// -------------------------------------------------------------- Biquad
// Direct Form II transposed; used for the two K-weighting stages of the
// BS.1770 loudness pre-filter (RBJ designs approximating the ITU curves:
// +4 dB high shelf @ ~1681.97 Hz Q 0.7071, then high-pass @ ~38.13 Hz Q 0.5).
#[derive(Clone, Copy)]
struct Biquad {
    b0: f32,
    b1: f32,
    b2: f32,
    a1: f32,
    a2: f32,
    z1: f32,
    z2: f32,
}

impl Biquad {
    #[inline]
    fn process(&mut self, x: f32) -> f32 {
        let y = self.b0 * x + self.z1;
        self.z1 = self.b1 * x - self.a1 * y + self.z2;
        self.z2 = self.b2 * x - self.a2 * y;
        y
    }

    fn high_shelf(sr: f32, fc: f32, q: f32, gain_db: f32) -> Self {
        let a = 10.0f32.powf(gain_db / 40.0);
        let w = 2.0f32 * PI * fc / sr;
        let (cw, sw) = (w.cos(), w.sin());
        let alpha = sw / (2.0 * q);
        let sa = a.sqrt();
        let a0 = (a + 1.0) - (a - 1.0) * cw + 2.0 * sa * alpha;
        Biquad {
            b0: (a * ((a + 1.0) + (a - 1.0) * cw + 2.0 * sa * alpha)) / a0,
            b1: (-2.0 * a * ((a - 1.0) + (a + 1.0) * cw)) / a0,
            b2: (a * ((a + 1.0) + (a - 1.0) * cw - 2.0 * sa * alpha)) / a0,
            a1: (2.0 * ((a - 1.0) - (a + 1.0) * cw)) / a0,
            a2: ((a + 1.0) - (a - 1.0) * cw - 2.0 * sa * alpha) / a0,
            z1: 0.0,
            z2: 0.0,
        }
    }

    fn high_pass(sr: f32, fc: f32, q: f32) -> Self {
        let w = 2.0f32 * PI * fc / sr;
        let (cw, sw) = (w.cos(), w.sin());
        let alpha = sw / (2.0 * q);
        let a0 = 1.0 + alpha;
        Biquad {
            b0: ((1.0 + cw) / 2.0) / a0,
            b1: (-(1.0 + cw)) / a0,
            b2: ((1.0 + cw) / 2.0) / a0,
            a1: (-2.0 * cw) / a0,
            a2: (1.0 - alpha) / a0,
            z1: 0.0,
            z2: 0.0,
        }
    }
}

// Krumhansl-Schmuckler key profiles (major/minor), the standard reference
// weights for chroma-correlation key estimation.
const MAJOR_PROFILE: [f32; 12] = [6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88];
const MINOR_PROFILE: [f32; 12] = [6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17];

fn correlate12(a: &[f32; 12], b: &[f32; 12]) -> f32 {
    let (mut ma, mut mb) = (0.0f32, 0.0f32);
    for i in 0..12 {
        ma += a[i];
        mb += b[i];
    }
    ma /= 12.0;
    mb /= 12.0;
    let (mut num, mut da, mut db) = (0.0f32, 0.0f32, 0.0f32);
    for i in 0..12 {
        num += (a[i] - ma) * (b[i] - mb);
        da += (a[i] - ma) * (a[i] - ma);
        db += (b[i] - mb) * (b[i] - mb);
    }
    let den = (da * db).sqrt();
    if den > 1e-9 {
        num / den
    } else {
        0.0
    }
}

#[inline]
fn hz_to_mel(hz: f32) -> f32 {
    2595.0 * (1.0 + hz / 700.0).log10()
}

#[inline]
fn mel_to_hz(mel: f32) -> f32 {
    700.0 * (10.0f32.powf(mel / 2595.0) - 1.0)
}

/// The centre frequency of FFT bin `b`, computed exactly as the C++ did
/// (`(float)b * sampleRate / kFftSize`, all in float).
#[inline]
fn bin_hz(b: usize, sample_rate: i32) -> f32 {
    b as f32 * sample_rate as f32 / FFT_SIZE as f32
}

/// One streaming analysis session. Not thread-safe; one per song per worker.
pub struct Analyzer {
    sample_rate: i32,

    // Streaming buffer holding up to one FFT frame of pending samples.
    pending: Vec<f32>,

    // Pre-computed.
    hann: Vec<f32>,
    mel_filters: Vec<Vec<f32>>, // [mel][bin]
    chroma_map: Vec<i32>,       // bin -> pitch class (or -1)
    dct: Vec<f64>,              // [coef][mel] DCT-II basis

    // Scratch (reused every frame: no hot-path allocation).
    re: Vec<f32>,
    im: Vec<f32>,
    magnitude: Vec<f32>,
    prev_magnitude: Vec<f32>,
    mels: [f32; NUM_MELS],

    // Accumulators.
    frame_count: i64,
    sum_squares: f64,
    total_samples: i64,
    peak: f32,
    centroid_sum: f64,
    rolloff_sum: f64,
    bandwidth_sum: f64,
    flatness_sum: f64,
    contrast_sum: f64,
    bass_sum: f64,
    mid_sum: f64,
    treble_sum: f64,
    zcr_sum: f64,
    chroma_sum: [f64; NUM_CHROMA],
    mfcc_sum: [f64; NUM_MFCC],
    mfcc_sq_sum: [f64; NUM_MFCC],
    flux: Vec<f32>,          // per-frame spectral flux for tempo
    frame_loud_db: Vec<f32>, // per-frame loudness for dynamic range

    // Loudness (K-weighted running mean-square with a coarse -70 LUFS gate
    // applied over 400 ms blocks; the absolute-gated value is within ~0.5 LU
    // of a full two-stage relative gate for music).
    k_shelf: Biquad,
    k_highpass: Biquad,
    loud_sum_sq: f64,
    loud_count: i64,
    block_sum_sq: f64,
    block_count: i64,
    block_len: i64,
}

impl Analyzer {
    /// `sample_rate`: source rate in Hz (no resampling; filterbanks adapt).
    /// Returns `None` for rates outside 8 kHz..384 kHz.
    pub fn new(sample_rate: i32) -> Option<Self> {
        if !(MIN_SAMPLE_RATE..=MAX_SAMPLE_RATE).contains(&sample_rate) {
            return None;
        }
        let sr = sample_rate;
        let hann =
            (0..FFT_SIZE).map(|i| 0.5f32 * (1.0 - (2.0f32 * PI * i as f32 / (FFT_SIZE - 1) as f32).cos())).collect();
        // C++: std::cos(kPi * c * (m + 0.5) / kNumMels), with kPi * c in float
        // and the rest in double.
        let mut dct = vec![0.0f64; NUM_MFCC * NUM_MELS];
        for c in 0..NUM_MFCC {
            for m in 0..NUM_MELS {
                dct[c * NUM_MELS + m] = ((PI * c as f32) as f64 * (m as f64 + 0.5) / NUM_MELS as f64).cos();
            }
        }
        let mut analyzer = Analyzer {
            sample_rate: sr,
            pending: Vec::with_capacity(FFT_SIZE + HOP_SIZE),
            hann,
            mel_filters: Vec::new(),
            chroma_map: Vec::new(),
            dct,
            re: vec![0.0; FFT_SIZE],
            im: vec![0.0; FFT_SIZE],
            magnitude: vec![0.0; NUM_BINS],
            prev_magnitude: vec![0.0; NUM_BINS],
            mels: [0.0; NUM_MELS],
            frame_count: 0,
            sum_squares: 0.0,
            total_samples: 0,
            peak: 0.0,
            centroid_sum: 0.0,
            rolloff_sum: 0.0,
            bandwidth_sum: 0.0,
            flatness_sum: 0.0,
            contrast_sum: 0.0,
            bass_sum: 0.0,
            mid_sum: 0.0,
            treble_sum: 0.0,
            zcr_sum: 0.0,
            chroma_sum: [0.0; NUM_CHROMA],
            mfcc_sum: [0.0; NUM_MFCC],
            mfcc_sq_sum: [0.0; NUM_MFCC],
            flux: Vec::with_capacity(1 << 14),
            frame_loud_db: Vec::with_capacity(1 << 14),
            // 0.7071 exactly as the C++ had it (not FRAC_1_SQRT_2): same coefficients.
            #[allow(clippy::approx_constant)]
            k_shelf: Biquad::high_shelf(sr as f32, 1681.97, 0.7071, 4.0),
            k_highpass: Biquad::high_pass(sr as f32, 38.13, 0.5),
            loud_sum_sq: 0.0,
            loud_count: 0,
            block_sum_sq: 0.0,
            block_count: 0,
            block_len: (0.4 * sr as f64) as i64, // 400 ms loudness blocks
        };
        analyzer.build_mel_filters();
        analyzer.build_chroma_map();
        Some(analyzer)
    }

    fn build_mel_filters(&mut self) {
        let sr = self.sample_rate;
        self.mel_filters = vec![vec![0.0f32; NUM_BINS]; NUM_MELS];
        let mel_lo = hz_to_mel(20.0);
        let mel_hi = hz_to_mel(8000.0f32.min(sr as f32 / 2.0));
        let centers: Vec<f32> = (0..NUM_MELS + 2)
            .map(|i| mel_to_hz(mel_lo + (mel_hi - mel_lo) * i as f32 / (NUM_MELS + 1) as f32))
            .collect();
        for m in 0..NUM_MELS {
            let (lo, mid, hi) = (centers[m], centers[m + 1], centers[m + 2]);
            let filter = &mut self.mel_filters[m];
            for (b, weight) in filter.iter_mut().enumerate() {
                let hz = bin_hz(b, sr);
                if hz > lo && hz < mid {
                    *weight = (hz - lo) / (mid - lo);
                } else if hz >= mid && hz < hi {
                    *weight = (hi - hz) / (hi - mid);
                }
            }
        }
    }

    fn build_chroma_map(&mut self) {
        let sr = self.sample_rate;
        self.chroma_map = vec![-1; NUM_BINS];
        for b in 1..NUM_BINS {
            let hz = bin_hz(b, sr);
            if !(55.0..=4000.0).contains(&hz) {
                continue; // A1..~C8: melodic range
            }
            let midi = 69.0f32 + 12.0 * (hz / 440.0).log2();
            // std::lround: round half away from zero, which f32::round also does.
            self.chroma_map[b] = (midi.round() as i64).rem_euclid(12) as i32;
        }
    }

    /// Feeds mono float PCM in [-1, 1]. Any length, including zero.
    pub fn process(&mut self, samples: &[f32]) {
        for &s in samples {
            // Full-signal accumulators.
            self.sum_squares += s as f64 * s as f64;
            self.peak = self.peak.max(s.abs());
            self.total_samples += 1;
            // K-weighted loudness path.
            let kw = self.k_highpass.process(self.k_shelf.process(s));
            self.block_sum_sq += kw as f64 * kw as f64;
            self.block_count += 1;
            if self.block_count >= self.block_len {
                let ms = self.block_sum_sq / self.block_count as f64;
                let lufs = -0.691 + 10.0 * (ms + 1e-12).log10();
                if lufs > -70.0 {
                    // absolute gate
                    self.loud_sum_sq += ms;
                    self.loud_count += 1;
                }
                self.block_sum_sq = 0.0;
                self.block_count = 0;
            }
        }
        // Frame assembly: analyze every full frame, then slide by one hop.
        let mut remaining = samples;
        while !remaining.is_empty() {
            let take = remaining.len().min(FFT_SIZE - self.pending.len());
            self.pending.extend_from_slice(&remaining[..take]);
            remaining = &remaining[take..];
            if self.pending.len() == FFT_SIZE {
                let frame = std::mem::take(&mut self.pending);
                self.analyze_frame(&frame);
                self.pending = frame;
                self.pending.drain(..HOP_SIZE);
            }
        }
    }

    fn analyze_frame(&mut self, frame: &[f32]) {
        debug_assert_eq!(frame.len(), FFT_SIZE);
        let sr = self.sample_rate;

        // Zero-crossing rate (on the raw frame).
        let zc = frame.windows(2).filter(|w| (w[1] >= 0.0) != (w[0] >= 0.0)).count();
        self.zcr_sum += zc as f64 / FFT_SIZE as f64;

        for i in 0..FFT_SIZE {
            self.re[i] = frame[i] * self.hann[i];
            self.im[i] = 0.0;
        }
        fft(&mut self.re, &mut self.im);

        let (mut energy, mut frame_flux) = (0.0f64, 0.0f64);
        let (mut bass, mut mid, mut treble) = (0.0f64, 0.0f64, 0.0f64);
        let mut centroid_num = 0.0f64;
        let (mut geo_log_sum, mut ari_sum) = (0.0f64, 0.0f64);
        for b in 0..NUM_BINS {
            let mag = (self.re[b] * self.re[b] + self.im[b] * self.im[b]).sqrt();
            self.magnitude[b] = mag;
            let hz = bin_hz(b, sr);
            let e = mag as f64 * mag as f64;
            energy += e;
            if hz < 250.0 {
                bass += e;
            } else if hz < 4000.0 {
                mid += e;
            } else {
                treble += e;
            }
            centroid_num += (hz * mag) as f64;
            geo_log_sum += (mag as f64 + 1e-10).ln();
            ari_sum += mag as f64;
            let d = mag - self.prev_magnitude[b];
            if d > 0.0 {
                frame_flux += d as f64;
            }
            self.prev_magnitude[b] = mag;
        }
        self.flux.push(frame_flux as f32);
        self.frame_loud_db.push((10.0 * (energy / NUM_BINS as f64 + 1e-12).log10()) as f32);

        let mag_sum = ari_sum + 1e-10;
        let centroid = centroid_num / mag_sum;
        self.centroid_sum += centroid;
        self.flatness_sum += (geo_log_sum / NUM_BINS as f64).exp() / (ari_sum / NUM_BINS as f64 + 1e-10);

        // Rolloff (85% of energy) and bandwidth around the centroid.
        let mut cum = 0.0f64;
        let target = 0.85 * energy;
        let mut bw_num = 0.0f64;
        let mut rolloff_hz = 0.0f32;
        for b in 0..NUM_BINS {
            let hz = bin_hz(b, sr);
            let m = self.magnitude[b];
            cum += m as f64 * m as f64;
            if rolloff_hz == 0.0 && cum >= target {
                rolloff_hz = hz;
            }
            let dh = hz as f64 - centroid;
            bw_num += m as f64 * dh * dh;
        }
        self.rolloff_sum += rolloff_hz as f64;
        self.bandwidth_sum += (bw_num / mag_sum).sqrt();

        if energy > 1e-9 {
            self.bass_sum += bass / energy;
            self.mid_sum += mid / energy;
            self.treble_sum += treble / energy;
        }

        // Spectral contrast: mean over 6 octave bands of (peak dB - valley dB).
        let mut contrast = 0.0f64;
        let mut bands = 0;
        let nyquist = sr as f32 / 2.0;
        let mut lo = 200.0f32;
        while lo < nyquist && bands < 6 {
            let b0 = (lo * FFT_SIZE as f32 / sr as f32) as i32;
            let b1 = ((lo * 2.0 * FFT_SIZE as f32 / sr as f32) as i32).min(NUM_BINS as i32 - 1);
            if b1 - b0 < 4 {
                break;
            }
            let (mut mx, mut mn) = (0.0f32, 1e9f32);
            for &m in &self.magnitude[b0 as usize..=b1 as usize] {
                mx = mx.max(m);
                mn = mn.min(m);
            }
            contrast += 20.0 * ((mx as f64 + 1e-10) / (mn as f64 + 1e-10)).log10();
            bands += 1;
            lo *= 2.0;
        }
        if bands > 0 {
            self.contrast_sum += contrast / bands as f64;
        }

        // Chroma.
        for (b, &pc) in self.chroma_map.iter().enumerate() {
            if pc >= 0 {
                self.chroma_sum[pc as usize] += self.magnitude[b] as f64;
            }
        }

        // MFCC: mel energies -> log -> DCT-II.
        for m in 0..NUM_MELS {
            let filter = &self.mel_filters[m];
            let mut acc = 0.0f64;
            for b in 0..NUM_BINS {
                acc += (filter[b] * self.magnitude[b]) as f64;
            }
            self.mels[m] = (acc as f32 + 1e-10f32).ln();
        }
        for c in 0..NUM_MFCC {
            let basis = &self.dct[c * NUM_MELS..(c + 1) * NUM_MELS];
            let mut acc = 0.0f64;
            for m in 0..NUM_MELS {
                acc += self.mels[m] as f64 * basis[m];
            }
            self.mfcc_sum[c] += acc;
            self.mfcc_sq_sum[c] += acc * acc;
        }

        self.frame_count += 1;
    }

    /// Tempo from the spectral-flux onset envelope: autocorrelation over the
    /// 60-200 BPM lag range; confidence = best-lag correlation vs. envelope
    /// energy. Simple, robust for most produced music; weakest on rubato
    /// classical, which the perceptual layer treats as "no beat" (bpm=0).
    fn estimate_tempo(&self) -> (f32, f32) {
        let n = self.flux.len();
        if n < 64 {
            return (0.0, 0.0);
        }
        let mean = self.flux.iter().map(|&f| f as f64).sum::<f64>() / n as f64;
        let env: Vec<f32> = self.flux.iter().map(|&f| f - mean as f32).collect();

        let frames_per_sec = self.sample_rate as f32 / HOP_SIZE as f32;
        let min_lag = ((frames_per_sec * 60.0 / 200.0) as i32).max(1) as usize;
        let max_lag = ((n / 2) as i32).min((frames_per_sec * 60.0 / 60.0) as i32).max(0) as usize;
        // Summed in the C++ order: starting from the epsilon.
        let e0 = env.iter().fold(1e-9f64, |acc, &e| acc + e as f64 * e as f64);

        let (mut best, mut best_lag) = (0.0f64, 0usize);
        for lag in min_lag..=max_lag {
            let acc: f64 = env[..n - lag].iter().zip(&env[lag..]).map(|(&a, &b)| a as f64 * b as f64).sum();
            if acc > best {
                best = acc;
                best_lag = lag;
            }
        }
        if best_lag == 0 {
            return (0.0, 0.0);
        }
        (60.0 * frames_per_sec / best_lag as f32, (best / e0).clamp(0.0, 1.0) as f32)
    }

    /// Finalizes the analysis. `None` if the stream was empty or too short for
    /// one frame.
    pub fn finish(&self) -> Option<[f32; FEATURE_COUNT]> {
        if self.frame_count == 0 || self.total_samples == 0 {
            return None;
        }
        let fc = self.frame_count as f64;
        let mut out = [0.0f32; FEATURE_COUNT];

        out[0] = (self.sum_squares / self.total_samples as f64).sqrt() as f32;
        out[1] = self.peak;
        out[2] = if self.loud_count > 0 {
            (-0.691 + 10.0 * (self.loud_sum_sq / self.loud_count as f64 + 1e-12).log10()) as f32
        } else {
            -70.0
        };

        // Dynamic range: 95th vs 10th percentile frame loudness.
        {
            let mut sorted = self.frame_loud_db.clone();
            sorted.sort_by(f32::total_cmp);
            let last = (sorted.len() - 1) as f64;
            let hi = sorted[(0.95 * last) as usize];
            let lo = sorted[(0.10 * last) as usize];
            out[3] = (hi - lo).max(0.0);
        }

        out[4] = (self.bass_sum / fc) as f32;
        out[5] = (self.mid_sum / fc) as f32;
        out[6] = (self.treble_sum / fc) as f32;
        out[7] = (self.centroid_sum / fc) as f32;
        out[8] = (self.rolloff_sum / fc) as f32;
        out[9] = (self.bandwidth_sum / fc) as f32;
        out[10] = (self.flatness_sum / fc) as f32;
        out[11] = (self.contrast_sum / fc) as f32;

        let (bpm, confidence) = self.estimate_tempo();
        out[12] = bpm;
        out[13] = confidence;
        out[14] = (self.zcr_sum / fc) as f32;

        // Key estimation over all 24 rotations of the K-S profiles.
        {
            let total = self.chroma_sum.iter().fold(1e-9f64, |acc, &c| acc + c);
            let chroma: [f32; 12] = std::array::from_fn(|i| (self.chroma_sum[i] / total) as f32);
            let (mut best_score, mut best_key, mut best_major) = (-2.0f32, -1i32, 1i32);
            for root in 0..12 {
                let rotated: [f32; 12] = std::array::from_fn(|i| chroma[(i + root) % 12]);
                let s_maj = correlate12(&rotated, &MAJOR_PROFILE);
                let s_min = correlate12(&rotated, &MINOR_PROFILE);
                if s_maj > best_score {
                    best_score = s_maj;
                    best_key = root as i32;
                    best_major = 1;
                }
                if s_min > best_score {
                    best_score = s_min;
                    best_key = root as i32;
                    best_major = 0;
                }
            }
            out[15] = best_key as f32;
            out[16] = best_major as f32;
            out[17..29].copy_from_slice(&chroma);
        }

        for c in 0..NUM_MFCC {
            let m = self.mfcc_sum[c] / fc;
            let var = (self.mfcc_sq_sum[c] / fc - m * m).max(0.0);
            out[29 + c] = m as f32;
            out[42 + c] = var.sqrt() as f32;
        }
        Some(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sine(freq: f32, sr: i32, secs: f32, amp: f32) -> Vec<f32> {
        (0..(sr as f32 * secs) as usize).map(|i| amp * (2.0 * PI * freq * i as f32 / sr as f32).sin()).collect()
    }

    fn analyze(samples: &[f32], sr: i32, chunk: usize) -> [f32; FEATURE_COUNT] {
        let mut a = Analyzer::new(sr).expect("supported rate");
        samples.chunks(chunk).for_each(|c| a.process(c));
        a.finish().expect("enough audio")
    }

    #[test]
    fn rejects_unsupported_sample_rates() {
        assert!(Analyzer::new(7_999).is_none());
        assert!(Analyzer::new(384_001).is_none());
        assert!(Analyzer::new(8_000).is_some());
        assert!(Analyzer::new(384_000).is_some());
    }

    #[test]
    fn needs_one_full_frame() {
        let mut a = Analyzer::new(44_100).unwrap();
        assert!(a.finish().is_none());
        a.process(&[0.1; FFT_SIZE - 1]);
        assert!(a.finish().is_none());
        a.process(&[0.1]);
        assert!(a.finish().is_some());
    }

    #[test]
    fn chunking_does_not_change_the_result() {
        let s = sine(330.0, 44_100, 3.0, 0.4);
        let whole = analyze(&s, 44_100, s.len());
        for chunk in [1, 7, 1024, 2048, 5000] {
            assert_eq!(whole, analyze(&s, 44_100, chunk), "chunk {chunk}");
        }
    }

    #[test]
    fn sine_levels_and_pitch_class() {
        let f = analyze(&sine(440.0, 48_000, 5.0, 0.5), 48_000, 4096);
        assert!((f[0] - 0.5 / 2f32.sqrt()).abs() < 1e-3, "rms {}", f[0]);
        assert!((f[1] - 0.5).abs() < 1e-3, "peak {}", f[1]);
        // A is pitch class 9 and must dominate the chroma.
        let chroma = &f[17..29];
        let top = (0..12).max_by(|&a, &b| chroma[a].total_cmp(&chroma[b])).unwrap();
        assert_eq!(top, 9);
        assert!((chroma.iter().sum::<f32>() - 1.0).abs() < 1e-3);
        // Energy sits in the mid band; the centroid is near 440 Hz.
        assert!(f[5] > 0.9, "mid {}", f[5]);
        assert!((f[7] - 440.0).abs() < 60.0, "centroid {}", f[7]);
    }

    #[test]
    fn click_track_tempo() {
        // A beat every 24 hops (~117 BPM at 48 kHz): a whole number of
        // analysis frames, so the autocorrelation peak is unambiguous.
        let sr = 48_000;
        let mut s = vec![0.0f32; sr as usize * 20];
        let period = 24 * HOP_SIZE;
        let expected = 60.0 * (sr as f32 / HOP_SIZE as f32) / 24.0;
        let len = s.len();
        for start in (0..len).step_by(period) {
            for (k, v) in s[start..(start + 200).min(len)].iter_mut().enumerate() {
                *v = 0.9 * (1.0 - k as f32 / 200.0);
            }
        }
        let f = analyze(&s, sr, 4096);
        assert!((f[12] - expected).abs() < 0.01, "bpm {} vs {expected}", f[12]);
        assert!(f[13] > 0.0 && f[13] <= 1.0, "confidence {}", f[13]);
    }

    #[test]
    fn silence_is_finite() {
        let f = analyze(&vec![0.0; 44_100 * 2], 44_100, 4096);
        assert!(f.iter().all(|v| v.is_finite()));
        assert_eq!(f[2], -70.0); // no gated loudness block
        assert_eq!(f[12], 0.0);
    }
}
