//! The engine's behaviour, ported from the C++ engine_test.cpp.
//! Run with `cargo test -p harmonyeq --features testing`.

#![allow(clippy::needless_range_loop)]

use std::f64::consts::PI;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Instant;

use harmonyeq::design::{design_band, magnitude_at, proportional_q};
use harmonyeq::{
    auto_offset_target_db, target_curve_db, Engine, OutputDevice, SampleFormat, BAND_FREQUENCIES, MAX_BARS, NUM_BANDS,
};

fn dbfs(linear: f32) -> f32 {
    20.0 * linear.max(1e-12).log10()
}

fn engine_at(sample_rate: i32, channels: i32, device: OutputDevice) -> Engine {
    let engine = Engine::new();
    engine.set_output_device_type(device);
    assert!(engine.configure(sample_rate, channels), "configure {sample_rate}/{channels}");
    engine
}

/// Runs `input` (interleaved float) through `engine` in `block`-frame calls plus the drain.
fn run_blocks(engine: &Engine, input: &[f32], block: usize) -> Vec<f32> {
    let channels = engine.channels() as usize;
    let frames = input.len() / channels;
    let mut output = vec![0.0f32; input.len() + (engine.latency_frames() as usize + 1) * channels];
    let mut scratch = vec![0.0f32; block * channels];
    let mut written = 0;
    let mut start = 0;
    while start < frames {
        let count = block.min(frames - start);
        scratch[..count * channels].copy_from_slice(&input[start * channels..(start + count) * channels]);
        let produced = engine.process_in_place(&mut scratch, count);
        output[written * channels..(written + produced) * channels].copy_from_slice(&scratch[..produced * channels]);
        written += produced;
        start += block;
    }
    let latency = engine.latency_frames() as usize + 1;
    written += engine.drain(&mut output[written * channels..], latency);
    output.truncate(written * channels);
    output
}

fn run(engine: &Engine, input: &[f32]) -> Vec<f32> {
    run_blocks(engine, input, 512)
}

fn sine(sample_rate: i32, channels: usize, frequency: f64, amplitude: f32, seconds: f64) -> Vec<f32> {
    let frames = (seconds * sample_rate as f64) as usize;
    let mut out = vec![0.0f32; frames * channels];
    for i in 0..frames {
        let v = amplitude * (2.0 * PI * frequency * i as f64 / sample_rate as f64).sin() as f32;
        out[i * channels..(i + 1) * channels].fill(v);
    }
    out
}

fn peak_of(x: &[f32]) -> f32 {
    x.iter().fold(0.0f32, |p, v| p.max(v.abs()))
}

/// Steady-state gain in dB of the engine for a sine at `frequency`.
fn gain_at(engine: &Engine, frequency: f64) -> f32 {
    let amplitude = 0.05f32;
    let input = sine(engine.sample_rate(), engine.channels() as usize, frequency, amplitude, 1.0);
    let out = run(engine, &input);
    dbfs(peak_of(&out[out.len() / 2..])) - dbfs(amplitude) // after the filters and smoothing settle
}

/// A small deterministic generator (the C++ tests used std::mt19937).
struct Rng(u64);

impl Rng {
    fn next_u32(&mut self) -> u32 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        (self.0 >> 32) as u32
    }
    fn uniform(&mut self) -> f32 {
        (self.next_u32() >> 8) as f32 / (1u32 << 24) as f32 * 2.0 - 1.0
    }
    fn normal(&mut self) -> f32 {
        // Box-Muller.
        let u1 = ((self.next_u32() >> 8) as f64 + 1.0) / (1u32 << 24) as f64;
        let u2 = (self.next_u32() >> 8) as f64 / (1u32 << 24) as f64;
        ((-2.0 * u1.ln()).sqrt() * (2.0 * PI * u2).cos()) as f32
    }
}

fn white_noise(sample_rate: i32, channels: usize, seconds: f64, sigma: f32, seed: u64) -> Vec<f32> {
    let mut rng = Rng(seed.wrapping_mul(0x9E37_79B9_7F4A_7C15) | 1);
    let frames = (seconds * sample_rate as f64) as usize;
    let mut out = vec![0.0f32; frames * channels];
    for i in 0..frames {
        let v = sigma * rng.normal();
        out[i * channels..(i + 1) * channels].fill(v);
    }
    out
}

/// Feeds `audio` in 4800-frame in-place calls, ignoring the output.
fn feed(engine: &Engine, mut audio: Vec<f32>) {
    let channels = engine.channels() as usize;
    for chunk in audio.chunks_mut(4800 * channels) {
        let frames = chunk.len() / channels;
        engine.process_in_place(chunk, frames);
    }
}

#[test]
fn flat_response_is_transparent() {
    for sr in [44100, 48000, 96000, 192000] {
        let engine = engine_at(sr, 2, OutputDevice::UsbDacHiRes);
        assert!(engine.target_preamp_db().abs() < 1e-4, "flat preamp {} at {sr}", engine.target_preamp_db());
        for f in [100.0, 1000.0, 10000.0] {
            let g = gain_at(&engine, f);
            assert!(g.abs() < 0.1, "flat gain {g:.3} dB at {f} Hz, {sr} Hz");
        }
    }
}

#[test]
fn bands_hit_their_targets() {
    for sr in [44100.0, 48000.0, 96000.0, 192000.0] {
        for b in 1..NUM_BANDS - 1 {
            for g in [-12.0, -6.0, 6.0, 12.0] {
                let f = design_band(b, sr, g);
                let measured = 20.0 * magnitude_at(&f, sr, BAND_FREQUENCIES[b] as f64).log10();
                assert!((measured - g).abs() < 0.05, "band {b} at {sr} Hz: {measured:.2} dB for {g}");
            }
        }
        // Shelves (Q 0.707, corners on 31 Hz and 16 kHz): half the gain at the corner, all of it beyond.
        let low = design_band(0, sr, 12.0);
        let low_at_band = 20.0 * magnitude_at(&low, sr, BAND_FREQUENCIES[0] as f64).log10();
        let low_far = 20.0 * magnitude_at(&low, sr, 5.0).log10();
        assert!((low_at_band - 6.0).abs() < 0.3, "low shelf at 31 Hz: {low_at_band:.2}");
        assert!((low_far - 12.0).abs() < 0.5, "low shelf at 5 Hz: {low_far:.2}");
        let high = design_band(NUM_BANDS - 1, sr, 12.0);
        let high_at_band = 20.0 * magnitude_at(&high, sr, 16000.0).log10();
        assert!(high_at_band > 5.0 && high_at_band < 8.0, "high shelf at 16 kHz ({sr}): {high_at_band:.2}");
        let high_mid = 20.0 * magnitude_at(&high, sr, 1000.0).log10();
        assert!(high_mid.abs() < 0.3, "high shelf leaves 1 kHz alone ({sr}): {high_mid:.2}");
    }
    // Proportional Q: narrower at higher gains.
    assert!(proportional_q(12.0) > proportional_q(3.0));
}

#[test]
fn live_preamp_follows_the_energy() {
    let sr = 48000;
    let engine = engine_at(sr, 2, OutputDevice::WiredHeadphones);
    let mut gains = [0.0f32; NUM_BANDS];
    gains[1] = 10.0; // +10 dB at 62 Hz
    engine.set_band_gains_db(&gains);
    // A voice-like 1 kHz line: the bass boost has nothing to lift, so almost no headroom is taken.
    let voice = sine(sr, 2, 1000.0, 0.3, 1.0);
    run(&engine, &voice);
    assert!(engine.target_preamp_db() > -1.5, "preamp on a 1 kHz line: {}", engine.target_preamp_db());
    // A bass line at 62 Hz: now the boost is all there is, and the preamp takes it back fast.
    run(&engine, &sine(sr, 2, 62.0, 0.3, 0.3));
    assert!(engine.target_preamp_db() < -8.0, "preamp on a 62 Hz line: {}", engine.target_preamp_db());
    // Back to the voice: given back, slowly but surely.
    run(&engine, &voice);
    assert!(engine.target_preamp_db() > -1.5, "preamp given back: {}", engine.target_preamp_db());

    // Everything at +12 dB on broadband noise: about 12 dB taken.
    engine.set_band_gains_db(&[12.0; NUM_BANDS]);
    run(&engine, &white_noise(sr, 2, 1.0, 0.1, 3));
    let p = engine.target_preamp_db();
    assert!(p < -10.5 && p > -12.5, "preamp for all +12 dB: {p}");

    // Cuts never raise the level.
    engine.set_band_gains_db(&[-12.0; NUM_BANDS]);
    run(&engine, &white_noise(sr, 2, 1.0, 0.1, 4));
    assert!(engine.target_preamp_db() <= 0.0, "preamp for cuts: {}", engine.target_preamp_db());

    // What the UI reads: the same preamp, without the ReplayGain level.
    engine.set_level_gain_db(-6.0);
    run(&engine, &white_noise(sr, 2, 0.1, 0.1, 5));
    let telemetry = engine.poll_dsp_telemetry(None, None, None, 0);
    assert!(
        (telemetry - (engine.target_preamp_db() + 6.0)).abs() < 0.05,
        "telemetry preamp {telemetry} vs {}",
        engine.target_preamp_db()
    );
}

#[test]
fn level_gain_adds_to_the_preamp() {
    let engine = engine_at(48000, 2, OutputDevice::WiredHeadphones);
    let nothing = vec![0.0f32; 512 * 2];
    engine.set_level_gain_db(-7.5);
    run(&engine, &nothing);
    assert!((engine.target_preamp_db() + 7.5).abs() < 1e-3, "level -7.5: {}", engine.target_preamp_db());
    engine.set_eq_enabled(false); // levelling stays with the EQ off
    run(&engine, &nothing);
    assert!((engine.target_preamp_db() + 7.5).abs() < 1e-3, "level with EQ off: {}", engine.target_preamp_db());
    engine.set_level_gain_db(40.0); // clamped
    run(&engine, &nothing);
    assert!((engine.target_preamp_db() - 15.0).abs() < 1e-3, "level clamp: {}", engine.target_preamp_db());
    engine.set_level_gain_db(f32::NAN);
    run(&engine, &nothing);
    assert!(engine.target_preamp_db().abs() < 1e-3, "NaN level: {}", engine.target_preamp_db());
}

#[test]
fn never_clips() {
    let mut rng = Rng(7);
    for device in [
        OutputDevice::PhoneSpeaker,
        OutputDevice::WiredHeadphones,
        OutputDevice::BluetoothA2dp,
        OutputDevice::UsbDacHiRes,
    ] {
        for sr in [44100, 192000] {
            let engine = engine_at(sr, 2, device);
            engine.set_band_gains_db(&[12.0; NUM_BANDS]);
            // Full-scale noise, then a full-scale bass sine: the worst there is.
            let mut input = vec![0.0f32; sr as usize * 2 * 2];
            let half = input.len() / 2;
            for v in &mut input[..half] {
                *v = rng.uniform();
            }
            for i in 0..input.len() / 4 {
                let v = (2.0 * PI * 60.0 * i as f64 / sr as f64).sin() as f32;
                input[half + i * 2] = v;
                input[half + i * 2 + 1] = v;
            }
            let out = run_blocks(&engine, &input, 333);
            let ceiling = 10.0f32.powf(engine.ceiling_db() / 20.0);
            let peak = peak_of(&out);
            assert!(peak <= ceiling * 1.000001, "{device:?} at {sr} Hz peaks at {:.4} dBFS", dbfs(peak));
            assert!(out.iter().all(|v| v.is_finite()), "non-finite output");
        }
    }
}

#[test]
fn output_stays_aligned_and_complete() {
    for sr in [44100, 48000, 96000, 192000] {
        let engine = engine_at(sr, 2, OutputDevice::UsbDacHiRes);
        let expected_latency = (0.002 * sr as f64).round() as i32 - 1;
        assert_eq!(engine.latency_frames(), expected_latency, "latency at {sr}");
        let input = sine(sr, 2, 1000.0, 0.1, 0.25);
        let out = run_blocks(&engine, &input, 100);
        assert_eq!(out.len(), input.len(), "frames out vs in at {sr}");
        // Same samples at the same positions (a flat EQ is transparent at 1 kHz).
        let worst = (input.len() / 4..input.len() * 3 / 4).fold(0.0f32, |w, i| w.max((out[i] - input[i]).abs()));
        assert!(worst < 2e-3, "misaligned by up to {worst} at {sr}");
    }
}

#[test]
fn sliders_do_not_click() {
    let sr = 48000;
    let engine = engine_at(sr, 1, OutputDevice::WiredHeadphones);
    let input = sine(sr, 1, 1000.0, 0.1, 1.0);
    let mut out = Vec::new();
    let mut gains = [0.0f32; NUM_BANDS];
    let switch_at = input.len() / 2 - (input.len() / 2) % 256;
    let mut start = 0;
    while start + 256 <= input.len() {
        if start == switch_at {
            gains[5] = 12.0; // slam the 1 kHz slider to the top
            engine.set_band_gains_db(&gains);
        }
        let mut block = input[start..start + 256].to_vec();
        let produced = engine.process_in_place(&mut block, 256);
        out.extend_from_slice(&block[..produced]);
        start += 256;
    }
    // Clicks show as spikes in the second difference; a clean sine has a bounded one.
    let mut during = 0.0f32;
    for i in 2..out.len() {
        let d2 = (out[i] - 2.0 * out[i - 1] + out[i - 2]).abs();
        if i > out.len() / 2 - 512 {
            during = during.max(d2);
        }
    }
    // The live preamp takes the new headroom back within milliseconds, so the
    // level moves briefly: bound by the loudest the sine got, not the final one.
    let level = peak_of(&out[out.len() / 2 - 512..]);
    // The second difference of a sine of amplitude A at w is A*w^2: allow for the louder level only.
    let w = (2.0 * PI * 1000.0 / sr as f64) as f32;
    assert!(during <= level * w * w * 1.3, "second difference {during} vs {} expected", level * w * w);
}

#[test]
fn device_high_pass() {
    let speaker40 = gain_at(&engine_at(48000, 2, OutputDevice::PhoneSpeaker), 40.0);
    let wired40 = gain_at(&engine_at(48000, 2, OutputDevice::WiredHeadphones), 40.0);
    let bluetooth1k = gain_at(&engine_at(48000, 2, OutputDevice::BluetoothA2dp), 1000.0);
    assert!(speaker40 < -10.0, "speaker passes 40 Hz at {speaker40} dB");
    assert!(wired40 > -0.6, "headphones cut 40 Hz to {wired40} dB");
    assert!((bluetooth1k + 0.3).abs() < 0.05, "Bluetooth pad: {bluetooth1k} dB");
}

#[test]
fn auto_rules_follow_the_target() {
    for device in [OutputDevice::WiredHeadphones, OutputDevice::PhoneSpeaker] {
        let target = target_curve_db(device);
        let powers = |shape: [f32; NUM_BANDS], sign: f32| -> [f64; NUM_BANDS] {
            std::array::from_fn(|b| 10.0f64.powf((target[b] + sign * shape[b]) as f64 / 10.0))
        };
        // Music that already sits on the target: nothing to do.
        let out = auto_offset_target_db(device, &powers([0.0; NUM_BANDS], 1.0));
        for (b, v) in out.iter().enumerate() {
            assert!(v.abs() < 0.05, "on target, band {b}: {v}");
        }
        // Thin sub-bass and air, muddy and harsh: every rule at once.
        let shape = [-8.0, -8.0, 8.0, 8.0, 0.0, 0.0, 8.0, 8.0, -8.0, -8.0];
        let out = auto_offset_target_db(device, &powers(shape, 1.0));
        let speaker = device == OutputDevice::PhoneSpeaker;
        for b in [0, 1] {
            if speaker {
                assert_eq!(out[b], 0.0, "speaker sub-bass never boosted, band {b}");
            } else {
                assert!(out[b] > 2.0 && out[b] <= 4.5, "thin sub-bass lifted, band {b}: {}", out[b]);
            }
        }
        for b in [8, 9] {
            assert!(out[b] > 2.0 && out[b] <= 4.5, "thin air lifted, band {b}: {}", out[b]);
        }
        for b in [2, 3, 6, 7] {
            assert!(out[b] < -2.0 && out[b] >= -4.5, "mud/harshness cut, band {b}: {}", out[b]);
        }
        for b in [4, 5] {
            assert_eq!(out[b], 0.0, "the middle holds, band {b}");
        }
        // The opposite: never cut sub-bass or air, never lift mud or harshness.
        let out = auto_offset_target_db(device, &powers(shape, -1.0));
        for b in [0, 1, 8, 9] {
            assert!(out[b] >= 0.0, "band {b} only ever lifted: {}", out[b]);
        }
        for b in [2, 3, 6, 7] {
            assert!(out[b] <= 0.0, "band {b} only ever cut: {}", out[b]);
        }
        // A band that isn't there at all (an MP3's cut-off top) is left alone.
        let mut p = powers([0.0; NUM_BANDS], 1.0);
        p[9] = 0.0;
        assert_eq!(auto_offset_target_db(device, &p)[9], 0.0, "absent top not boosted");
    }
}

#[test]
fn auto_calibrates_live() {
    let sr = 48000;
    let engine = engine_at(sr, 2, OutputDevice::WiredHeadphones);
    engine.set_auto_mode(true);
    let mut gains = [0.0f32; NUM_BANDS];
    gains[5] = 3.0; // a slider of the listener's: Auto adds to it, never replaces it
    engine.set_band_gains_db(&gains);
    // White noise, against a -4.5 dB/octave target: thin at the bottom, harsh in the upper mids.
    feed(&engine, white_noise(sr, 2, 6.0, 0.1, 11));
    let mut effective = [0.0f32; NUM_BANDS];
    let (mut bars, mut peaks) = ([0.0f32; 32], [0.0f32; 32]);
    let preamp = engine.poll_dsp_telemetry(Some(&mut effective), Some(&mut bars), Some(&mut peaks), 32);
    let offsets = engine.live_auto_offsets_db();
    assert!(!engine.tonal_frozen(), "playing music is not frozen");
    // 62 Hz is thin and lifted; white noise's 31 Hz octave sits so far under the
    // target that it counts as absent, and absent bands are never boosted.
    assert!(offsets[1] > 2.0 && offsets[0] >= 0.0, "sub-bass lifted: {offsets:?}");
    assert!(offsets[6] < -2.0 && offsets[7] < -2.0, "upper mids tamed: {offsets:?}");
    assert!(offsets[4] == 0.0 && offsets[5] == 0.0, "middle holds: {offsets:?}");
    for b in 0..NUM_BANDS {
        assert!((-4.5..=4.5).contains(&offsets[b]), "band {b} offset {} within ±4.5", offsets[b]);
        let expected = (gains[b] + offsets[b]).clamp(-12.0, 12.0);
        assert!((effective[b] - expected).abs() < 0.3, "band {b} effective {} = {expected}", effective[b]);
    }
    assert!(preamp.is_finite() && preamp <= 0.0, "live preamp {preamp}");

    // Silence: below -42 dBFS the tonal adaptation freezes and the offsets hold.
    feed(&engine, white_noise(sr, 2, 3.0, 0.001, 12)); // about -60 dBFS
    let held = engine.live_auto_offsets_db();
    assert!(engine.tonal_frozen(), "quiet passage freezes the adaptation");
    for b in 0..NUM_BANDS {
        assert!((held[b] - offsets[b]).abs() < 0.05, "band {b} held {} (was {})", held[b], offsets[b]);
    }

    // Auto off: the offsets glide back to nothing.
    engine.set_auto_mode(false);
    feed(&engine, white_noise(sr, 2, 6.0, 0.1, 13));
    for (b, v) in engine.live_auto_offsets_db().iter().enumerate() {
        assert!(v.abs() < 0.05, "Auto off, band {b}: {v}");
    }

    // On the phone speaker the same music gets no sub-bass boost.
    let speaker = engine_at(sr, 2, OutputDevice::PhoneSpeaker);
    speaker.set_auto_mode(true);
    feed(&speaker, white_noise(sr, 2, 6.0, 0.1, 11));
    let offsets = speaker.live_auto_offsets_db();
    assert!(offsets[0] == 0.0 && offsets[1] == 0.0, "speaker sub-bass: {offsets:?}");
}

#[test]
fn tamers_duck_bass_and_harshness() {
    let sr = 48000;
    let engine = engine_at(sr, 2, OutputDevice::WiredHeadphones);
    let mut gains = [0.0f32; NUM_BANDS];
    gains[0] = 8.0;
    gains[1] = 8.0;
    gains[7] = 6.0;
    gains[8] = 6.0;
    engine.set_band_gains_db(&gains);
    // Quiet bass: no ducking.
    feed(&engine, sine(sr, 2, 50.0, 0.1, 1.0));
    assert!(engine.bass_duck_db() < 0.01, "ducking on quiet bass: {}", engine.bass_duck_db());
    assert!((engine.designed_band_gain_db(1) - 8.0).abs() < 0.01, "62 Hz band at {}", engine.designed_band_gain_db(1));
    // A heavy 50 Hz line near -1 dBFS: the bass boost comes back by up to 4 dB.
    feed(&engine, sine(sr, 2, 50.0, 0.9, 0.5));
    let duck = engine.bass_duck_db();
    assert!(duck > 1.5 && duck <= 4.0, "ducking on heavy bass: {duck}");
    assert!((engine.designed_band_gain_db(0) - (8.0 - duck)).abs() < 0.02, "31 Hz band");
    assert!(engine.designed_band_gain_db(2).abs() < 0.01, "125 Hz untouched");
    // Harsh sibilance around 5.6 kHz: the 4/8 kHz boost is tamed; quiet sibilance isn't.
    feed(&engine, sine(sr, 2, 5657.0, 0.005, 0.5));
    assert!(engine.harsh_duck_db() < 0.01, "tamer on quiet sibilance: {}", engine.harsh_duck_db());
    feed(&engine, sine(sr, 2, 5657.0, 0.5, 0.5));
    let harsh = engine.harsh_duck_db();
    assert!(harsh > 1.5 && harsh <= 4.0, "tamer on harsh sibilance: {harsh}");
    assert!((engine.designed_band_gain_db(7) - (6.0 - harsh)).abs() < 0.02, "4 kHz band");
    assert!(engine.designed_band_gain_db(5).abs() < 0.01, "1 kHz untouched");
    // The ceiling follows the output: -0.4 dBFS on Bluetooth.
    let bluetooth = engine_at(sr, 2, OutputDevice::BluetoothA2dp);
    assert!((bluetooth.ceiling_db() + 0.4).abs() < 1e-4, "Bluetooth ceiling {}", bluetooth.ceiling_db());
}

#[test]
fn converts_every_format() {
    let sr = 48000;
    let frames = 4800;
    let expected: Vec<f32> =
        (0..frames).map(|i| 0.25 * (2.0 * PI * 1000.0 * i as f64 / sr as f64).sin() as f32).collect();
    // Reference: the same signal as float, through an identical engine.
    let mut reference = expected.clone();
    engine_at(sr, 1, OutputDevice::UsbDacHiRes).process_in_place(&mut reference, frames);
    let check = |format: SampleFormat, bytes: &[u8], tolerance: f32| {
        let engine = engine_at(sr, 1, OutputDevice::UsbDacHiRes);
        let mut out = vec![0.0f32; frames];
        let produced = engine.process(bytes, format, frames, &mut out);
        let worst = (0..produced).fold(0.0f32, |w, i| w.max((out[i] - reference[i]).abs()));
        assert!(worst < tolerance, "{format:?} off by {worst}");
    };
    let mut pcm16 = Vec::new();
    let mut pcm24 = Vec::new();
    let mut pcm32 = Vec::new();
    for &v in &expected {
        pcm16.extend(((v as f64 * 32767.0).round() as i16).to_le_bytes());
        pcm24.extend(&((v as f64 * 8_388_607.0).round() as i32).to_le_bytes()[..3]);
        pcm32.extend(((v as f64 * 2_147_483_647.0).round() as i32).to_le_bytes());
    }
    // Each within its own quantisation step (and the EQ's tiny gain on it).
    check(SampleFormat::Pcm16, &pcm16, 2.0 / 32768.0);
    check(SampleFormat::Pcm24Packed, &pcm24, 2.0 / 8_388_608.0);
    check(SampleFormat::Pcm32, &pcm32, 1e-6);

    // NaN and infinity in float input must not poison the filters.
    let engine = engine_at(sr, 1, OutputDevice::WiredHeadphones);
    let mut poisoned = sine(sr, 1, 440.0, 0.3, 0.1);
    poisoned[100] = f32::NAN;
    poisoned[200] = f32::INFINITY;
    assert!(run(&engine, &poisoned).iter().all(|v| v.is_finite()), "NaN/Inf leaked into the output");
}

#[test]
fn corrupt_frames_never_silence_playback() {
    // A corrupt stretch in the middle of a song: NaN, infinities and absurd
    // values, as a broken decoder frame might deliver. The output must stay
    // finite and under the ceiling, and the music must come straight back.
    for in_place in [true, false] {
        let sr = 48000;
        let engine = engine_at(sr, 2, OutputDevice::WiredHeadphones);
        engine.set_auto_mode(true);
        engine.set_band_gains_db(&[6.0, 4.0, 2.0, 0.0, -2.0, 0.0, 2.0, 4.0, 6.0, 8.0]);
        let mut song = sine(sr, 2, 1000.0, 0.25, 2.0);
        let corrupt_from = sr as usize; // at 0.5 s
        for i in corrupt_from..corrupt_from + 4800 {
            song[i] = match i % 4 {
                0 => f32::NAN,
                1 => f32::INFINITY,
                2 => -1e30,
                _ => 3e38,
            };
        }
        let mut out = vec![0.0f32; song.len()];
        let frames = song.len() / 2;
        let mut written = 0;
        let mut start = 0;
        while start < frames {
            let count = 480.min(frames - start);
            let chunk = &mut song[start * 2..(start + count) * 2];
            if in_place {
                let produced = engine.process_in_place(chunk, count);
                out[written * 2..(written + produced) * 2].copy_from_slice(&chunk[..produced * 2]);
                written += produced;
            } else {
                let bytes: Vec<u8> = chunk.iter().flat_map(|v| v.to_le_bytes()).collect();
                written += engine.process(&bytes, SampleFormat::Float, count, &mut out[written * 2..]);
            }
            start += 480;
        }
        let out = &out[..written * 2];
        assert!(out.iter().all(|v| v.is_finite()), "corrupt frames leaked non-finite output (in place {in_place})");
        let ceiling = 10.0f32.powf(engine.ceiling_db() / 20.0);
        assert!(peak_of(out) <= ceiling * 1.000001, "corrupt frames broke the ceiling: {}", peak_of(out));
        // The last half second: the sine is back at its level.
        assert!(peak_of(&out[out.len() - sr as usize..]) > 0.1, "playback stayed quiet after the corrupt frames");
        assert!(engine.sanitized_samples() >= 4800, "repaired samples counted: {}", engine.sanitized_samples());
        assert_eq!(engine.heal_count(), 0, "sanitized input never needs a heal");
        assert!(engine.effective_gains_db().iter().all(|g| g.is_finite()), "live gains stay finite");
        assert!(engine.target_preamp_db().is_finite(), "preamp stays finite");
    }
}

#[test]
fn self_heals_poisoned_state() {
    let sr = 48000;
    let engine = engine_at(sr, 2, OutputDevice::WiredHeadphones);
    engine.set_band_gains_db(&[3.0; NUM_BANDS]);
    run(&engine, &sine(sr, 2, 440.0, 0.2, 0.2));
    engine.poison_state_for_test();
    let out = run(&engine, &sine(sr, 2, 440.0, 0.2, 1.0));
    assert!(out.iter().all(|v| v.is_finite()), "poisoned state leaked into the output");
    assert!(engine.heal_count() >= 1, "the heal ran");
    assert!(peak_of(&out[out.len() - sr as usize..]) > 0.1, "sound came back after the heal");
}

#[test]
fn sample_rate_changes_reconfigure() {
    let engine = engine_at(44100, 2, OutputDevice::UsbDacHiRes);
    let mut gains = [0.0f32; NUM_BANDS];
    gains[5] = 6.0;
    engine.set_band_gains_db(&gains);
    for sr in [44100, 96000, 192000, 48000, 384000] {
        assert!(engine.configure(sr, 2), "configure {sr}");
        let g = gain_at(&engine, 1000.0);
        let expected = 6.0 + engine.target_preamp_db();
        assert!((g - expected).abs() < 0.15, "1 kHz at {sr} Hz: {g} dB, expected {expected}");
    }
    assert!(!engine.configure(500000, 2), "accepted 500 kHz");
    assert!(!engine.configure(48000, 9), "accepted 9 channels");
}

#[test]
fn spectrum_finds_the_tone() {
    let sr = 48000;
    let engine = engine_at(sr, 2, OutputDevice::UsbDacHiRes);
    engine.set_spectrum_bar_count(32);
    let mut bars = [0.0f32; MAX_BARS];
    let mut peaks = [0.0f32; MAX_BARS];
    let mut clock: i64 = 1_000_000_000;
    let per_frame = (sr / 60) as usize;
    let mut phase = 0.0f32;
    for _ in 0..60 {
        // one second at 60 fps
        let mut block = vec![0.0f32; per_frame * 2];
        for i in 0..per_frame {
            let v = 0.5 * phase.sin();
            phase += (2.0 * PI * 1000.0 / sr as f64) as f32;
            block[i * 2] = v;
            block[i * 2 + 1] = v;
        }
        engine.process_in_place(&mut block, per_frame);
        clock += 16_666_667;
        let n = engine.poll_spectrum_at(clock, Some(&mut bars), Some(&mut peaks), MAX_BARS);
        assert_eq!(n, 32);
    }
    let loudest = (0..32).fold(0, |best, i| if bars[i] > bars[best] { i } else { best });
    let low = 20.0 * 1000.0f64.powf(loudest as f64 / 32.0);
    let high = 20.0 * 1000.0f64.powf((loudest + 1) as f64 / 32.0);
    assert!(low <= 1000.0 && 1000.0 <= high * 1.05, "loudest bar {loudest} covers {low:.0}-{high:.0} Hz");
    // -6 dBFS on a 72 dB scale.
    assert!(bars[loudest] > 0.85 && bars[loudest] <= 1.0, "tone bar at {}", bars[loudest]);
    assert!(peaks[loudest] >= bars[loudest], "peak below bar");
    assert!(bars[2] < 0.3, "20-30 Hz bar at {} with no bass", bars[2]);

    // Silence (paused): the bars fall to nothing, the peaks after their hold.
    for _ in 0..150 {
        clock += 16_666_667;
        engine.poll_spectrum_at(clock, Some(&mut bars), Some(&mut peaks), MAX_BARS);
    }
    assert!(bars[loudest] < 0.01, "bar still at {} after silence", bars[loudest]);
    assert!(peaks[loudest] < 0.01, "peak still at {} after silence", peaks[loudest]);

    engine.set_spectrum_bar_count(64);
    assert_eq!(engine.poll_spectrum_at(clock + 16_666_667, Some(&mut bars), Some(&mut peaks), MAX_BARS), 64);
}

#[test]
fn analyzer_runs_concurrently() {
    // The audio thread and the UI thread at full speed together.
    let engine = engine_at(96000, 2, OutputDevice::UsbDacHiRes);
    let stop = AtomicBool::new(false);
    std::thread::scope(|s| {
        s.spawn(|| {
            let mut rng = Rng(99);
            let mut bars = [0.0f32; MAX_BARS];
            let mut peaks = [0.0f32; MAX_BARS];
            let mut live = [0.0f32; NUM_BANDS];
            while !stop.load(Ordering::Relaxed) {
                engine.poll_dsp_telemetry(Some(&mut live), Some(&mut bars), Some(&mut peaks), MAX_BARS);
                let gains: [f32; NUM_BANDS] = std::array::from_fn(|_| (rng.next_u32() % 25) as f32 - 12.0);
                engine.set_band_gains_db(&gains);
                engine.set_output_device_type(OutputDevice::try_from_i32((rng.next_u32() % 4) as i32).unwrap());
                engine.set_auto_mode(rng.next_u32() % 2 == 0);
            }
        });
        let mut rng = Rng(1);
        let mut block = vec![0.0f32; 1024 * 2];
        let mut worst = 0.0f32;
        for _ in 0..2000 {
            for v in block.iter_mut() {
                *v = rng.uniform();
            }
            let produced = engine.process_in_place(&mut block, 1024);
            worst = worst.max(peak_of(&block[..produced * 2]));
        }
        stop.store(true, Ordering::Relaxed);
        assert!(worst <= 1.0, "concurrent run peaked at {worst}");
    });
}

#[test]
fn fast_enough_for_192k() {
    let engine = engine_at(192000, 2, OutputDevice::UsbDacHiRes);
    engine.set_band_gains_db(&[6.0, 4.0, 2.0, -2.0, 0.0, 3.0, -4.0, 5.0, 2.0, 6.0]);
    engine.set_auto_mode(true);
    let mut rng = Rng(3);
    let block: Vec<f32> = (0..4096 * 2).map(|_| 0.5 * rng.uniform()).collect();
    let seconds = 10;
    let start = Instant::now();
    let mut frames = 0;
    while frames < 192000 * seconds {
        let mut scratch = block.clone();
        engine.process_in_place(&mut scratch, 4096);
        frames += 4096;
    }
    let speed = seconds as f64 / start.elapsed().as_secs_f64();
    println!("  192 kHz stereo: {speed:.0}x real time on this host");
    // Debug builds are far slower; the real-time bound only means something optimised.
    if !cfg!(debug_assertions) {
        assert!(speed > 20.0, "only {speed:.1}x real time");
    }
}
