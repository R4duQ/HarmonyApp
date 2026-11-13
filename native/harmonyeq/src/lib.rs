//! libharmonyeq: Harmony's float EQ engine and its JNI bridge
//! (com.harmony.core.eq.MobileEqNative).
//!
//! Zero garbage on every path that runs per buffer or per frame: audio moves
//! through direct ByteBuffers (no copies, no arrays), slider arrays are read
//! with GetPrimitiveArrayCritical into stack memory, and the telemetry is
//! written into float arrays the caller allocated once. Nothing here allocates
//! a Java object unless the caller misuses the API, in which case it throws
//! IllegalArgumentException. Every entry point also catches panics, so a bug
//! here surfaces as a failed call rather than a crashed app.
//!
//! The natives are exported by their JNI names (Java_com_harmony_core_eq_
//! MobileEqNative_*): consumer-rules.pro keeps the class and method names.

// Index loops mirror the C++ original, which keeps the port easy to check;
// `-> ()` comes from the jni_fn! macro's uniform shape.
#![allow(clippy::needless_range_loop, clippy::unused_unit)]

pub mod clarity;
pub mod denormals;
pub mod design;
pub mod engine;
pub mod fft;
pub mod ring;

use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::objects::{JByteBuffer, JClass, JFloatArray, ReleaseMode};
use jni::sys::{jboolean, jfloat, jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

pub use clarity::CLARITY_BANDS;
pub use engine::{auto_offset_target_db, target_curve_db, Engine};

pub const NUM_BANDS: usize = 10;
pub const BAND_FREQUENCIES: [f32; NUM_BANDS] =
    [31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0];
pub const MIN_GAIN_DB: f32 = -12.0;
pub const MAX_GAIN_DB: f32 = 12.0;
pub const MAX_CHANNELS: usize = 8;
pub const MIN_SAMPLE_RATE: i32 = 8000;
pub const MAX_SAMPLE_RATE: i32 = 384000;
pub const FFT_SIZE: usize = 2048;
pub const MIN_BARS: usize = 8;
pub const MAX_BARS: usize = 64;

/// What the sound is playing on. It decides the high-pass, a codec pad and the ceiling.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(i32)]
pub enum OutputDevice {
    PhoneSpeaker = 0,
    WiredHeadphones = 1,
    BluetoothA2dp = 2,
    UsbDacHiRes = 3,
}

impl OutputDevice {
    pub fn try_from_i32(v: i32) -> Option<Self> {
        Some(match v {
            0 => OutputDevice::PhoneSpeaker,
            1 => OutputDevice::WiredHeadphones,
            2 => OutputDevice::BluetoothA2dp,
            3 => OutputDevice::UsbDacHiRes,
            _ => return None,
        })
    }

    pub(crate) fn from_i32(v: i32) -> Self {
        Self::try_from_i32(v).unwrap_or(OutputDevice::PhoneSpeaker)
    }
}

/// Interleaved input sample formats process() converts from. Little-endian, as Android's.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(i32)]
pub enum SampleFormat {
    Pcm16 = 1,
    Pcm24Packed = 2,
    Pcm32 = 3,
    Float = 4,
}

impl SampleFormat {
    pub fn try_from_i32(v: i32) -> Option<Self> {
        Some(match v {
            1 => SampleFormat::Pcm16,
            2 => SampleFormat::Pcm24Packed,
            3 => SampleFormat::Pcm32,
            4 => SampleFormat::Float,
            _ => return None,
        })
    }

    pub fn bytes_per_sample(self) -> usize {
        match self {
            SampleFormat::Pcm16 => 2,
            SampleFormat::Pcm24Packed => 3,
            SampleFormat::Pcm32 | SampleFormat::Float => 4,
        }
    }
}

// ---- JNI helpers ------------------------------------------------------------------------

fn guard<T>(fallback: T, body: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(body)).unwrap_or(fallback)
}

/// # Safety
/// `handle` must be a live pointer returned by nativeCreate (Kotlin owns it).
unsafe fn engine<'a>(handle: jlong) -> Option<&'a Engine> {
    (handle as *const Engine).as_ref()
}

fn throw_illegal_argument(env: &mut JNIEnv, message: &str) {
    let _ = env.throw_new("java/lang/IllegalArgumentException", message);
}

/// The address range [byte_offset, byte_offset + byte_count) of direct
/// `buffer`, or None (with an IllegalArgumentException thrown).
fn direct_range(
    env: &mut JNIEnv,
    buffer: &JByteBuffer,
    byte_offset: jint,
    byte_count: i64,
) -> Option<(*mut u8, usize)> {
    if buffer.is_null() {
        throw_illegal_argument(env, "buffer is null");
        return None;
    }
    let base = env.get_direct_buffer_address(buffer).ok().filter(|p| !p.is_null());
    let capacity = env.get_direct_buffer_capacity(buffer).ok();
    let (Some(base), Some(capacity)) = (base, capacity) else {
        let _ = env.exception_clear();
        throw_illegal_argument(env, "buffer must be a direct ByteBuffer");
        return None;
    };
    if byte_offset < 0 || byte_count < 0 || byte_offset as i64 + byte_count > capacity as i64 {
        throw_illegal_argument(env, "range outside the buffer");
        return None;
    }
    // SAFETY: the offset is within the buffer's capacity.
    Some((unsafe { base.add(byte_offset as usize) }, byte_count as usize))
}

/// Copies `out.len()` floats from `array`; false (with an exception) if it is null or too short.
fn read_floats(env: &mut JNIEnv, array: &JFloatArray, out: &mut [f32], what: &str) -> bool {
    if array.is_null() || env.get_array_length(array).map_or(true, |n| (n as usize) < out.len()) {
        throw_illegal_argument(env, what);
        return false;
    }
    // Read only: nothing to copy back. Nothing in the critical section calls the VM.
    // SAFETY: the elements are only copied out, and released before returning.
    match unsafe { env.get_array_elements_critical(array, ReleaseMode::NoCopyBack) } {
        Ok(data) => {
            out.copy_from_slice(&data[..out.len()]);
            true
        }
        Err(_) => false,
    }
}

/// Copies `values` into `array` (which must hold at least that many).
fn write_floats(env: &mut JNIEnv, array: &JFloatArray, values: &[f32]) {
    if array.is_null() || values.is_empty() {
        return;
    }
    // SAFETY: as above; the critical section only copies.
    if let Ok(mut data) = unsafe { env.get_array_elements_critical(array, ReleaseMode::CopyBack) } {
        let n = values.len().min(data.len());
        data[..n].copy_from_slice(&values[..n]);
    }
}

/// The capacity a nullable array offers, capped at `cap`.
fn capacity_of(env: &JNIEnv, array: &JFloatArray, cap: usize) -> usize {
    if array.is_null() {
        return cap;
    }
    env.get_array_length(array).map_or(0, |n| (n.max(0) as usize).min(cap))
}

macro_rules! jni_fn {
    ($name:ident ( $env:ident, $($arg:ident : $ty:ty),* ) -> $ret:ty = $fallback:expr; $body:block) => {
        #[no_mangle]
        pub extern "system" fn $name(#[allow(unused_mut)] mut $env: JNIEnv, _class: JClass, $($arg: $ty),*) -> $ret {
            #[allow(unused_mut, unused_variables)]
            let env_ref = &mut $env;
            guard($fallback, || {
                let $env = env_ref;
                $body
            })
        }
    };
}

/// The engine behind `handle`, or `$fallback` returned from the enclosing JNI function.
macro_rules! engine_or {
    ($handle:expr, $fallback:expr) => {
        // SAFETY: Kotlin only passes handles from nativeCreate it has not destroyed.
        match unsafe { engine($handle) } {
            Some(e) => e,
            None => return $fallback,
        }
    };
}

// ---- Lifecycle ----------------------------------------------------------------------------

jni_fn!(Java_com_harmony_core_eq_MobileEqNative_nativeCreate(env,) -> jlong = 0; {
    let _ = env;
    Box::into_raw(Box::new(Engine::new())) as jlong
});

jni_fn!(Java_com_harmony_core_eq_MobileEqNative_nativeDestroy(env, handle: jlong) -> () = (); {
    let _ = env;
    if handle != 0 {
        // SAFETY: the handle came from Box::into_raw in nativeCreate and is destroyed once.
        drop(unsafe { Box::from_raw(handle as *mut Engine) });
    }
});

jni_fn!(Java_com_harmony_core_eq_MobileEqNative_nativeConfigure(env, handle: jlong, sample_rate: jint, channels: jint) -> jboolean = JNI_FALSE; {
    let _ = env;
    let e = engine_or!(handle, JNI_FALSE);
    if e.configure(sample_rate, channels) { JNI_TRUE } else { JNI_FALSE }
});

// A new sample rate at the same channel count: every coefficient, time
// constant, lookahead length and FFT bin map is recomputed for it.
jni_fn!(Java_com_harmony_core_eq_MobileEqNative_nativeSetSampleRate(env, handle: jlong, sample_rate: jint) -> jboolean = JNI_FALSE; {
    let _ = env;
    let e = engine_or!(handle, JNI_FALSE);
    if e.configure(sample_rate, e.channels()) { JNI_TRUE } else { JNI_FALSE }
});

jni_fn!(Java_com_harmony_core_eq_MobileEqNative_nativeReset(env, handle: jlong) -> () = (); {
    let _ = env;
    engine_or!(handle, ()).reset();
});

jni_fn!(Java_com_harmony_core_eq_MobileEqNative_nativeLatencyFrames(env, handle: jlong) -> jint = 0; {
    let _ = env;
    engine_or!(handle, 0).latency_frames()
});

// ---- Audio thread ---------------------------------------------------------------------------

jni_fn!(Java_com_harmony_core_eq_MobileEqNative_nativeProcess(
    env, handle: jlong, input: JByteBuffer, input_offset: jint, frames: jint, format: jint,
    output: JByteBuffer, output_offset: jint
) -> jint = 0; {
    let e = engine_or!(handle, 0);
    let Some(format) = SampleFormat::try_from_i32(format) else {
        throw_illegal_argument(env, "unknown sample format");
        return 0;
    };
    if frames <= 0 {
        return 0;
    }
    let samples = frames as i64 * e.channels() as i64;
    let Some((in_ptr, in_len)) = direct_range(env, &input, input_offset, samples * format.bytes_per_sample() as i64) else {
        return 0;
    };
    let Some((out_ptr, out_len)) = direct_range(env, &output, output_offset, samples * 4) else {
        return 0;
    };
    if (out_ptr as usize) % std::mem::align_of::<f32>() != 0 {
        throw_illegal_argument(env, "output must be float-aligned");
        return 0;
    }
    let frames = frames as usize;
    // SAFETY: both ranges were checked against their buffers' capacity above,
    // and the output is float-aligned. They are only used as two slices when
    // they do not overlap; float audio processed in place uses one slice.
    unsafe {
        let output = std::slice::from_raw_parts_mut(out_ptr as *mut f32, out_len / 4);
        if in_ptr == out_ptr && format == SampleFormat::Float {
            return e.process_in_place(output, frames) as jint;
        }
        let (in_start, out_start) = (in_ptr as usize, out_ptr as usize);
        if in_start < out_start + out_len && out_start < in_start + in_len {
            throw_illegal_argument(env, "input and output overlap");
            return 0;
        }
        let input = std::slice::from_raw_parts(in_ptr as *const u8, in_len);
        e.process(input, format, frames, output) as jint
    }
});

jni_fn!(Java_com_harmony_core_eq_MobileEqNative_nativeDrain(env, handle: jlong, output: JByteBuffer, output_offset: jint, max_frames: jint) -> jint = 0; {
    let e = engine_or!(handle, 0);
    if max_frames <= 0 {
        return 0;
    }
    let bytes = max_frames as i64 * e.channels() as i64 * 4;
    let Some((out_ptr, out_len)) = direct_range(env, &output, output_offset, bytes) else {
        return 0;
    };
    if (out_ptr as usize) % std::mem::align_of::<f32>() != 0 {
        throw_illegal_argument(env, "output must be float-aligned");
        return 0;
    }
    // SAFETY: the range was checked against the buffer's capacity, and is aligned.
    let output = unsafe { std::slice::from_raw_parts_mut(out_ptr as *mut f32, out_len / 4) };
    e.drain(output, max_frames as usize) as jint
});

// ---- Controls -------------------------------------------------------------------------------

jni_fn!(Java_com_harmony_core_eq_MobileEqNative_nativeSetBandGain(env, handle: jlong, band: jint, gain_db: jfloat) -> () = (); {
    let _ = env;
    engine_or!(handle, ()).set_band_gain_db(band, gain_db);
});

jni_fn!(Java_com_harmony_core_eq_MobileEqNative_nativeSetBandGains(env, handle: jlong, gains_db: JFloatArray) -> () = (); {
    let e = engine_or!(handle, ());
    let mut gains = [0.0f32; NUM_BANDS];
    if read_floats(env, &gains_db, &mut gains, "expected 10 band gains") {
        e.set_band_gains_db(&gains);
    }
});

jni_fn!(Java_com_harmony_core_eq_MobileEqNative_nativeSetEqEnabled(env, handle: jlong, enabled: jboolean) -> () = (); {
    let _ = env;
    engine_or!(handle, ()).set_eq_enabled(enabled == JNI_TRUE);
});

jni_fn!(Java_com_harmony_core_eq_MobileEqNative_nativeSetAutoMode(env, handle: jlong, enabled: jboolean) -> () = (); {
    let _ = env;
    engine_or!(handle, ()).set_auto_mode(enabled == JNI_TRUE);
});

jni_fn!(Java_com_harmony_core_eq_MobileEqNative_nativeSetUserPreamp(env, handle: jlong, preamp_db: jfloat) -> () = (); {
    let _ = env;
    engine_or!(handle, ()).set_user_preamp_db(preamp_db);
});

jni_fn!(Java_com_harmony_core_eq_MobileEqNative_nativeSetLevelGain(env, handle: jlong, gain_db: jfloat) -> () = (); {
    let _ = env;
    engine_or!(handle, ()).set_level_gain_db(gain_db);
});

jni_fn!(Java_com_harmony_core_eq_MobileEqNative_nativeSetOutputDeviceType(env, handle: jlong, device: jint) -> () = (); {
    let e = engine_or!(handle, ());
    match OutputDevice::try_from_i32(device) {
        Some(device) => e.set_output_device_type(device),
        None => throw_illegal_argument(env, "unknown output device"),
    }
});

jni_fn!(Java_com_harmony_core_eq_MobileEqNative_nativeSetAnalyzerEnabled(env, handle: jlong, enabled: jboolean) -> () = (); {
    let _ = env;
    engine_or!(handle, ()).set_analyzer_enabled(enabled == JNI_TRUE);
});

jni_fn!(Java_com_harmony_core_eq_MobileEqNative_nativeSetSpectrumBarCount(env, handle: jlong, bars: jint) -> () = (); {
    let _ = env;
    engine_or!(handle, ()).set_spectrum_bar_count(bars);
});

jni_fn!(Java_com_harmony_core_eq_MobileEqNative_nativeTargetPreampDb(env, handle: jlong) -> jfloat = 0.0; {
    let _ = env;
    engine_or!(handle, 0.0).target_preamp_db()
});

jni_fn!(Java_com_harmony_core_eq_MobileEqNative_nativeBassDuckDb(env, handle: jlong) -> jfloat = 0.0; {
    let _ = env;
    engine_or!(handle, 0.0).bass_duck_db()
});

jni_fn!(Java_com_harmony_core_eq_MobileEqNative_nativeHarshDuckDb(env, handle: jlong) -> jfloat = 0.0; {
    let _ = env;
    engine_or!(handle, 0.0).harsh_duck_db()
});

jni_fn!(Java_com_harmony_core_eq_MobileEqNative_nativeHealCount(env, handle: jlong) -> jint = 0; {
    let _ = env;
    (engine_or!(handle, 0).heal_count() & 0x7FFF_FFFF) as jint
});

jni_fn!(Java_com_harmony_core_eq_MobileEqNative_nativeSanitizedSamples(env, handle: jlong) -> jint = 0; {
    let _ = env;
    (engine_or!(handle, 0).sanitized_samples() & 0x7FFF_FFFF) as jint
});

jni_fn!(Java_com_harmony_core_eq_MobileEqNative_nativeTonalFrozen(env, handle: jlong) -> jboolean = JNI_FALSE; {
    let _ = env;
    if engine_or!(handle, JNI_FALSE).tonal_frozen() { JNI_TRUE } else { JNI_FALSE }
});

jni_fn!(Java_com_harmony_core_eq_MobileEqNative_nativeLiveAutoOffsets(env, handle: jlong, out: JFloatArray) -> () = (); {
    let e = engine_or!(handle, ());
    if out.is_null() || env.get_array_length(&out).map_or(true, |n| (n as usize) < NUM_BANDS) {
        throw_illegal_argument(env, "expected room for 10 bands");
        return;
    }
    write_floats(env, &out, &e.live_auto_offsets_db());
});

// Clarity Live: on/off (it runs while the EQ is on), intensity 0..1, balance -1..1.
jni_fn!(Java_com_harmony_core_eq_MobileEqNative_nativeSetClarity(env, handle: jlong, enabled: jboolean, intensity: jfloat, balance: jfloat) -> () = (); {
    let _ = env;
    engine_or!(handle, ()).set_clarity(enabled == JNI_TRUE, intensity, balance);
});

jni_fn!(Java_com_harmony_core_eq_MobileEqNative_nativeClarityNewTrack(env, handle: jlong) -> () = (); {
    let _ = env;
    engine_or!(handle, ()).clarity_new_track();
});

/// True when `array` is null or holds at least `n` floats; otherwise throws.
fn room_for(env: &mut JNIEnv, array: &JFloatArray, n: usize, what: &str) -> bool {
    if array.is_null() || env.get_array_length(array).is_ok_and(|len| len as usize >= n) {
        return true;
    }
    throw_illegal_argument(env, what);
    false
}

// Clarity's live view: per band (24) the response it applies, the music's
// level and the masking threshold, dB. Any array may be null. Returns the
// level match in dB, NaN while Clarity is off and flat.
jni_fn!(Java_com_harmony_core_eq_MobileEqNative_nativeClarityTelemetry(
    env, handle: jlong, response: JFloatArray, levels: JFloatArray, masks: JFloatArray
) -> jfloat = f32::NAN; {
    let e = engine_or!(handle, f32::NAN);
    for array in [&response, &levels, &masks] {
        if !room_for(env, array, CLARITY_BANDS, "expected room for 24 Clarity bands") {
            return f32::NAN;
        }
    }
    let mut r = [0.0f32; CLARITY_BANDS];
    let mut l = [0.0f32; CLARITY_BANDS];
    let mut m = [0.0f32; CLARITY_BANDS];
    let output = e.clarity_telemetry(Some(&mut r), Some(&mut l), Some(&mut m));
    write_floats(env, &response, &r);
    write_floats(env, &levels, &l);
    write_floats(env, &masks, &m);
    output
});

// ---- Telemetry (UI thread, 60 fps) -----------------------------------------------------------

// The one call a 60 fps UI makes: the effective gains of the ten bands
// (sliders plus Auto's live offsets), the FFT bars and their peak dots.
// Returns the live automatic preamp in dB. Any array may be null.
jni_fn!(Java_com_harmony_core_eq_MobileEqNative_nativePollDspTelemetry(
    env, handle: jlong, effective_gains10: JFloatArray, fft_magnitudes: JFloatArray, fft_peaks: JFloatArray
) -> jfloat = 0.0; {
    let e = engine_or!(handle, 0.0);
    if !effective_gains10.is_null()
        && env.get_array_length(&effective_gains10).map_or(true, |n| (n as usize) < NUM_BANDS)
    {
        throw_illegal_argument(env, "expected room for 10 band gains");
        return 0.0;
    }
    let mut gains = [0.0f32; NUM_BANDS];
    let mut bars = [0.0f32; MAX_BARS];
    let mut peaks = [0.0f32; MAX_BARS];
    let capacity = capacity_of(env, &fft_magnitudes, MAX_BARS).min(capacity_of(env, &fft_peaks, MAX_BARS));
    let wants_spectrum = !fft_magnitudes.is_null() || !fft_peaks.is_null();
    let wants_gains = !effective_gains10.is_null();
    let preamp_db = e.poll_dsp_telemetry(
        if wants_gains { Some(&mut gains) } else { None },
        if wants_spectrum { Some(&mut bars[..]) } else { None },
        if wants_spectrum { Some(&mut peaks[..]) } else { None },
        capacity,
    );
    let count = if wants_spectrum { capacity.min(e.spectrum_bar_count()) } else { 0 };
    if wants_gains {
        write_floats(env, &effective_gains10, &gains);
    }
    write_floats(env, &fft_magnitudes, &bars[..count]);
    write_floats(env, &fft_peaks, &peaks[..count]);
    preamp_db
});

jni_fn!(Java_com_harmony_core_eq_MobileEqNative_nativePollSpectrum(env, handle: jlong, bars: JFloatArray, peaks: JFloatArray) -> jint = 0; {
    let e = engine_or!(handle, 0);
    let mut bar_values = [0.0f32; MAX_BARS];
    let mut peak_values = [0.0f32; MAX_BARS];
    let capacity = capacity_of(env, &bars, MAX_BARS).min(capacity_of(env, &peaks, MAX_BARS));
    let count = e.poll_spectrum(Some(&mut bar_values), Some(&mut peak_values), capacity);
    write_floats(env, &bars, &bar_values[..count]);
    write_floats(env, &peaks, &peak_values[..count]);
    count as jint
});
