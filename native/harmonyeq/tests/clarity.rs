//! Clarity Live: the stage on its own (the desktop ClarityLayerTest cases) and
//! inside the engine. Run with `cargo test -p harmonyeq --features testing`.

#![allow(clippy::needless_range_loop)]

use std::f64::consts::PI;
use std::time::Instant;

use harmonyeq::clarity::{Bands, ClarityStage, CLARITY_BANDS};
use harmonyeq::{Engine, OutputDevice};

const RATE: i32 = 48_000;

struct Rng(u64);
impl Rng {
    fn white(&mut self) -> f32 {
        self.0 = self.0.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        ((self.0 >> 40) as f32 / (1u64 << 24) as f32) * 2.0 - 1.0
    }
}

/// Pink noise (Paul Kellet's filter on white noise), about −10 dBFS RMS at scale 1.
struct Pink {
    rng: Rng,
    b: [f32; 7],
}
impl Pink {
    fn new(seed: u64) -> Self {
        Pink { rng: Rng(seed), b: [0.0; 7] }
    }
    fn next(&mut self) -> f32 {
        let w = self.rng.white();
        let b = &mut self.b;
        b[0] = 0.99886 * b[0] + w * 0.0555179;
        b[1] = 0.99332 * b[1] + w * 0.0750759;
        b[2] = 0.96900 * b[2] + w * 0.153852;
        b[3] = 0.86650 * b[3] + w * 0.3104856;
        b[4] = 0.55000 * b[4] + w * 0.5329522;
        b[5] = -0.7616 * b[5] - w * 0.0168980;
        let out = b[0] + b[1] + b[2] + b[3] + b[4] + b[5] + b[6] + w * 0.5362;
        b[6] = w * 0.115926;
        out * 0.11
    }
}

/// Runs `seconds` of mono `signal` through `stage` (the same on two channels),
/// as the engine does: a block's advance, then each frame heard and filtered.
fn run_stage(
    stage: &mut ClarityStage,
    rate: i32,
    seconds: f64,
    mut signal: impl FnMut(usize) -> f32,
) -> (Vec<f32>, Vec<f32>) {
    let frames = (seconds * rate as f64) as usize;
    let mut input = Vec::with_capacity(frames);
    let mut output = Vec::with_capacity(frames);
    let mut i = 0;
    while i < frames {
        let count = 32.min(frames - i);
        stage.advance(count);
        for f in i..i + count {
            let x = signal(f);
            input.push(x);
            stage.listen(x);
            output.push(stage.process(0, x as f64) as f32);
            stage.process(1, x as f64);
        }
        i += count;
    }
    (input, output)
}

fn rms_db(x: &[f32]) -> f64 {
    let from = x.len() / 2;
    let sum: f64 = x[from..].iter().map(|v| (*v as f64) * (*v as f64)).sum();
    10.0 * (sum / (x.len() - from) as f64).log10()
}

/// Level of `hz` in the second half of `x`, dB (Goertzel).
fn tone_db(x: &[f32], hz: f64, rate: i32) -> f64 {
    let from = x.len() / 2;
    let w = 2.0 * PI * hz / rate as f64;
    let c = 2.0 * w.cos();
    let (mut s1, mut s2) = (0.0f64, 0.0f64);
    for v in &x[from..] {
        let s = *v as f64 + c * s1 - s2;
        s2 = s1;
        s1 = s;
    }
    let n = (x.len() - from) as f64;
    20.0 * ((s1 * s1 + s2 * s2 - c * s1 * s2).sqrt() * 2.0 / n).log10()
}

fn band(hz: f32) -> usize {
    let centers = Bands::new().centers_hz;
    (0..CLARITY_BANDS).min_by(|&a, &b| (centers[a] - hz).abs().total_cmp(&(centers[b] - hz).abs())).unwrap()
}

fn stage_at(rate: i32, recover: f32, tame: f32) -> ClarityStage {
    let mut stage = ClarityStage::new();
    stage.configure(rate, 2);
    stage.set_controls(true, recover, tame);
    stage
}

#[test]
fn silence_stays_silent_and_flat() {
    let mut stage = stage_at(RATE, 1.0, 1.0);
    let (_, out) = run_stage(&mut stage, RATE, 1.0, |_| 0.0);
    assert!(out.iter().all(|v| *v == 0.0));
    assert!(stage.applied_db().iter().all(|g| *g == 0.0));
    assert!(stage.model().silent);
    assert!(!stage.engaged());
}

#[test]
fn steady_pink_noise_is_barely_touched_and_keeps_its_level() {
    let mut stage = stage_at(RATE, 1.0, 1.0);
    let mut pink = Pink::new(3);
    let (input, output) = run_stage(&mut stage, RATE, 6.0, |_| pink.next() * 0.5);
    let applied = stage.applied_db();
    let mean = applied.iter().map(|g| g.abs()).sum::<f32>() / CLARITY_BANDS as f32;
    assert!(mean < 1.2, "mean {applied:?}");
    assert!(applied.iter().all(|g| g.abs() < 3.0), "max {applied:?}");
    assert!((rms_db(&input) - rms_db(&output)).abs() < 1.0);
}

#[test]
fn a_softer_tone_just_above_a_loud_one_is_brought_out() {
    let mut stage = stage_at(RATE, 2.0, 0.0);
    let loud = 590.0;
    let quiet = Bands::new().centers_hz[band(740.0)] as f64;
    let (input, output) = run_stage(&mut stage, RATE, 3.0, |i| {
        let t = i as f64 / RATE as f64;
        (0.3 * (2.0 * PI * loud * t).sin() + 0.03 * (2.0 * PI * quiet * t).sin()) as f32
    });
    let k = band(quiet as f32);
    let applied = stage.applied_db();
    assert!(applied[k] > 0.5, "applied {}", applied[k]);
    assert!(applied[k] > applied[band(loud as f32)]);
    let lift = (tone_db(&output, quiet, RATE) - tone_db(&output, loud, RATE))
        - (tone_db(&input, quiet, RATE) - tone_db(&input, loud, RATE));
    assert!(lift > 0.3, "lift {lift}");
}

#[test]
fn a_ringing_resonance_over_the_music_is_held_back() {
    let mut stage = stage_at(RATE, 0.0, 2.0);
    let mut pink = Pink::new(5);
    let ring = Bands::new().centers_hz[band(3000.0)] as f64;
    let (input, output) = run_stage(&mut stage, RATE, 4.0, |i| {
        pink.next() * 0.3 + (0.1 * (2.0 * PI * ring * i as f64 / RATE as f64).sin()) as f32
    });
    let k = band(ring as f32);
    assert!(stage.applied_db()[k] < -1.0, "applied {}", stage.applied_db()[k]);
    let drop = (tone_db(&output, ring, RATE) - rms_db(&output)) - (tone_db(&input, ring, RATE) - rms_db(&input));
    assert!(drop < -1.0, "drop {drop}");
}

#[test]
fn every_band_moves_on_its_own() {
    // Two resonances far apart: their own bands are held back, the bands between them are not.
    let mut stage = stage_at(RATE, 0.0, 2.0);
    let mut pink = Pink::new(11);
    let (low, high) = (Bands::new().centers_hz[band(250.0)] as f64, Bands::new().centers_hz[band(4000.0)] as f64);
    run_stage(&mut stage, RATE, 4.0, |i| {
        let t = i as f64 / RATE as f64;
        pink.next() * 0.3 + (0.08 * (2.0 * PI * low * t).sin() + 0.08 * (2.0 * PI * high * t).sin()) as f32
    });
    let applied = stage.applied_db();
    let between = band(1200.0);
    assert!(applied[band(250.0)] < -1.0, "{applied:?}");
    assert!(applied[band(4000.0)] < -1.0, "{applied:?}");
    assert!(applied[between] > applied[band(250.0)] + 1.0, "{applied:?}");
    assert!(applied[between] > applied[band(4000.0)] + 1.0, "{applied:?}");
}

#[test]
fn turned_off_it_glides_back_to_flat_and_steps_aside() {
    let mut stage = stage_at(RATE, 0.0, 2.0);
    let mut pink = Pink::new(7);
    let ring = Bands::new().centers_hz[band(3000.0)] as f64;
    let mut tone = |i: usize| pink.next() * 0.3 + (0.1 * (2.0 * PI * ring * i as f64 / RATE as f64).sin()) as f32;
    run_stage(&mut stage, RATE, 3.0, &mut tone);
    assert!(stage.engaged());
    stage.set_controls(false, 0.0, 2.0);
    let (input, output) = run_stage(&mut stage, RATE, 3.0, &mut tone);
    assert!(!stage.engaged());
    assert!(stage.applied_db().iter().all(|g| *g == 0.0));
    // Flat and out of the way: the last second comes out exactly as it went in.
    let tail = input.len() - RATE as usize;
    assert_eq!(&input[tail..], &output[tail..]);
}

#[test]
fn it_moves_without_steps() {
    // A sine with a resonance switching on and off: sample to sample, the output
    // never jumps more than the signal itself could.
    let mut stage = stage_at(RATE, 1.0, 2.0);
    let (_, output) = run_stage(&mut stage, RATE, 4.0, |i| {
        let t = i as f64 / RATE as f64;
        let ring = if (i / 24_000) % 2 == 0 { 0.15 } else { 0.0 };
        (0.2 * (2.0 * PI * 440.0 * t).sin() + ring * (2.0 * PI * 3000.0 * t).sin()) as f32
    });
    let mut worst = 0.0f32;
    for i in 1..output.len() {
        worst = worst.max((output[i] - output[i - 1]).abs());
    }
    let max_slope = (0.2 * 2.0 * PI * 440.0 / RATE as f64 + 0.15 * 2.0 * PI * 3000.0 / RATE as f64) as f32;
    assert!(worst < max_slope * 2.0, "step {worst} vs {max_slope}");
}

#[test]
fn hi_res_rates_listen_on_a_decimated_copy_and_still_work() {
    for rate in [96_000, 192_000, 384_000] {
        let mut stage = stage_at(rate, 2.0, 0.0);
        let loud = 590.0;
        let quiet = Bands::new().centers_hz[band(740.0)] as f64;
        run_stage(&mut stage, rate, 3.0, |i| {
            let t = i as f64 / rate as f64;
            (0.3 * (2.0 * PI * loud * t).sin() + 0.03 * (2.0 * PI * quiet * t).sin()) as f32
        });
        assert!(!stage.model().silent, "{rate}");
        assert!(stage.applied_db()[band(quiet as f32)] > 0.5, "{rate}: {:?}", stage.applied_db());
    }
}

#[test]
fn non_finite_input_never_poisons_it() {
    // The engine sanitizes what reaches the filters; the analysis must cope on its own.
    let mut stage = stage_at(RATE, 1.0, 1.0);
    let mut pink = Pink::new(13);
    for block in 0..1500 {
        stage.advance(32);
        for i in 0..32 {
            let x = pink.next() * 0.3;
            stage.listen(if (block * 32 + i) % 1000 == 7 { f32::NAN } else { x });
            stage.process(0, x as f64);
            stage.process(1, x as f64);
        }
    }
    assert!(stage.healthy());
    assert!(stage.applied_db().iter().all(|g| g.is_finite()));
}

// ---- Inside the engine --------------------------------------------------------------------

fn engine(device: OutputDevice) -> Engine {
    let e = Engine::new();
    e.set_output_device_type(device);
    assert!(e.configure(RATE, 2));
    e.set_eq_enabled(true);
    e
}

fn stereo(seconds: f64, mut signal: impl FnMut(usize) -> f32) -> Vec<f32> {
    let frames = (seconds * RATE as f64) as usize;
    let mut out = Vec::with_capacity(frames * 2);
    for i in 0..frames {
        let x = signal(i);
        out.push(x);
        out.push(x);
    }
    out
}

fn run_engine(e: &Engine, input: &[f32]) -> Vec<f32> {
    let mut out = Vec::with_capacity(input.len());
    let mut block = vec![0.0f32; 480 * 2];
    for chunk in input.chunks(480 * 2) {
        let frames = chunk.len() / 2;
        block[..chunk.len()].copy_from_slice(chunk);
        let produced = e.process_in_place(&mut block[..chunk.len()], frames);
        out.extend_from_slice(&block[..produced * 2]);
    }
    out
}

#[test]
fn off_it_changes_nothing() {
    let mut pink = Pink::new(21);
    let input = stereo(3.0, |_| pink.next() * 0.6);
    let plain = engine(OutputDevice::WiredHeadphones);
    plain.set_band_gains_db(&[3.0, 1.0, 0.0, -2.0, 0.0, 1.0, 2.0, -1.0, 3.0, 2.0]);
    plain.set_auto_mode(true);
    let off = engine(OutputDevice::WiredHeadphones);
    off.set_band_gains_db(&[3.0, 1.0, 0.0, -2.0, 0.0, 1.0, 2.0, -1.0, 3.0, 2.0]);
    off.set_auto_mode(true);
    off.set_clarity(false, 0.9, 0.5);
    assert_eq!(run_engine(&plain, &input), run_engine(&off, &input));
    assert!(off.clarity_telemetry(None, None, None).is_nan());

    // With the EQ off, Clarity's switch alone does nothing either.
    let eq_off = engine(OutputDevice::WiredHeadphones);
    eq_off.set_eq_enabled(false);
    let eq_off_clarity = engine(OutputDevice::WiredHeadphones);
    eq_off_clarity.set_eq_enabled(false);
    eq_off_clarity.set_clarity(true, 1.0, 0.0);
    assert_eq!(run_engine(&eq_off, &input), run_engine(&eq_off_clarity, &input));
}

#[test]
fn on_it_shapes_the_music_and_keeps_its_level() {
    let left = |x: &[f32]| x.iter().step_by(2).copied().collect::<Vec<f32>>();
    // Music without anything sticking out keeps its level.
    let e = engine(OutputDevice::WiredHeadphones);
    e.set_clarity(true, 0.5, 0.0);
    let mut pink = Pink::new(19);
    let input = stereo(5.0, |_| pink.next() * 0.4);
    let (li, lo) = (left(&input), left(&run_engine(&e, &input)));
    assert!((rms_db(&li) - rms_db(&lo)).abs() < 1.0, "{} vs {}", rms_db(&li), rms_db(&lo));

    // A resonance over it is held back (full intensity, leaning towards taming).
    let e = engine(OutputDevice::WiredHeadphones);
    e.set_clarity(true, 1.0, -0.5);
    let mut pink = Pink::new(23);
    let ring = Bands::new().centers_hz[band(2800.0)] as f64;
    let input = stereo(5.0, |i| pink.next() * 0.25 + (0.08 * (2.0 * PI * ring * i as f64 / RATE as f64).sin()) as f32);
    let output = run_engine(&e, &input);
    let (li, lo) = (left(&input), left(&output));
    let drop = (tone_db(&lo, ring, RATE) - rms_db(&lo)) - (tone_db(&li, ring, RATE) - rms_db(&li));
    assert!(drop < -1.0, "drop {drop}");
    assert!(output.iter().all(|v| v.abs() <= 1.0));

    let (mut response, mut levels, mut masks) = ([0.0; CLARITY_BANDS], [0.0; CLARITY_BANDS], [0.0; CLARITY_BANDS]);
    let output_db = e.clarity_telemetry(Some(&mut response), Some(&mut levels), Some(&mut masks));
    assert!(output_db.is_finite());
    assert!(response[band(ring as f32)] < -0.5, "{response:?}");
    assert!(levels.iter().all(|v| v.is_finite()) && masks.iter().all(|v| v.is_finite()));
    // Auto rests while Clarity runs.
    e.set_auto_mode(true);
    run_engine(&e, &input[..RATE as usize * 2 * 2]);
    assert!(e.live_auto_offsets_db().iter().all(|o| o.abs() < 0.5), "{:?}", e.live_auto_offsets_db());
}

#[test]
fn loud_music_with_clarity_never_clips() {
    let e = engine(OutputDevice::BluetoothA2dp);
    e.set_clarity(true, 1.0, 1.0);
    e.set_level_gain_db(6.0);
    let mut pink = Pink::new(29);
    let input = stereo(4.0, |_| (pink.next() * 2.5).clamp(-1.0, 1.0));
    let output = run_engine(&e, &input);
    let ceiling = 10f32.powf(-0.4 / 20.0) + 1e-6;
    assert!(output.iter().all(|v| v.abs() <= ceiling));
}

#[test]
fn a_new_song_and_a_seek_keep_it_playing() {
    let e = engine(OutputDevice::WiredHeadphones);
    e.set_clarity(true, 0.5, 0.0);
    let mut pink = Pink::new(31);
    let input = stereo(2.0, |_| pink.next() * 0.3);
    run_engine(&e, &input);
    e.clarity_new_track();
    e.reset();
    let output = run_engine(&e, &input);
    assert!(output.iter().all(|v| v.is_finite()));
    assert_eq!(e.heal_count(), 0);
}

#[test]
fn fast_enough_with_clarity() {
    let e = engine(OutputDevice::WiredHeadphones);
    e.set_clarity(true, 0.5, 0.0);
    let mut pink = Pink::new(37);
    let input = stereo(4.0, |_| pink.next() * 0.3);
    let start = Instant::now();
    let mut seconds = 0.0;
    while seconds < 20.0 {
        run_engine(&e, &input);
        seconds += 4.0;
    }
    let speed = seconds / start.elapsed().as_secs_f64();
    println!("  48 kHz stereo with Clarity Live: {speed:.0}x real time on this host");
    if !cfg!(debug_assertions) {
        assert!(speed > 40.0, "only {speed:.1}x real time");
    }
}
