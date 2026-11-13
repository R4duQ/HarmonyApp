//! libharmonydsp: the JNI side of com.harmony.core.dsp.NativeAnalyzer.
//!
//! Thin by design: no logic, just the handle and array marshalling. The input
//! array is read without copy-back (JNI_ABORT) to keep per-chunk overhead low.
//! Every entry point catches panics, so a bug surfaces in Kotlin as a failed
//! call (0 handle / null result) rather than a crashed app.

pub mod analyzer;

use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::objects::{JFloatArray, JObject, ReleaseMode};
use jni::sys::{jfloatArray, jint, jlong};
use jni::JNIEnv;

pub use analyzer::{Analyzer, FEATURE_COUNT};

fn guard<T>(fallback: T, body: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(body)).unwrap_or(fallback)
}

/// # Safety
/// `handle` must be 0 or a live pointer returned by `nativeCreate`.
unsafe fn analyzer<'a>(handle: jlong) -> Option<&'a mut Analyzer> {
    (handle as *mut Analyzer).as_mut()
}

#[no_mangle]
pub extern "system" fn Java_com_harmony_core_dsp_NativeAnalyzer_nativeCreate(
    _env: JNIEnv,
    _this: JObject,
    sample_rate: jint,
) -> jlong {
    guard(0, || match Analyzer::new(sample_rate) {
        Some(a) => Box::into_raw(Box::new(a)) as jlong,
        None => 0,
    })
}

#[no_mangle]
pub extern "system" fn Java_com_harmony_core_dsp_NativeAnalyzer_nativeProcess(
    mut env: JNIEnv,
    _this: JObject,
    handle: jlong,
    samples: JFloatArray,
    count: jint,
) {
    guard((), || {
        // SAFETY: Kotlin owns the handle and only passes live ones.
        let Some(a) = (unsafe { analyzer(handle) }) else { return };
        if samples.is_null() || count <= 0 {
            return;
        }
        // SAFETY: the elements are only read, and released before returning.
        let Ok(data) = (unsafe { env.get_array_elements(&samples, ReleaseMode::NoCopyBack) }) else {
            return;
        };
        let n = (count as usize).min(data.len());
        a.process(&data[..n]);
    })
}

#[no_mangle]
pub extern "system" fn Java_com_harmony_core_dsp_NativeAnalyzer_nativeFinish(
    env: JNIEnv,
    _this: JObject,
    handle: jlong,
) -> jfloatArray {
    guard(std::ptr::null_mut(), || {
        // SAFETY: as above.
        let Some(a) = (unsafe { analyzer(handle) }) else { return std::ptr::null_mut() };
        let Some(features) = a.finish() else { return std::ptr::null_mut() };
        let Ok(array) = env.new_float_array(FEATURE_COUNT as jint) else { return std::ptr::null_mut() };
        if env.set_float_array_region(&array, 0, &features).is_err() {
            return std::ptr::null_mut();
        }
        array.into_raw()
    })
}

#[no_mangle]
pub extern "system" fn Java_com_harmony_core_dsp_NativeAnalyzer_nativeDestroy(
    _env: JNIEnv,
    _this: JObject,
    handle: jlong,
) {
    if handle != 0 {
        // SAFETY: the handle came from Box::into_raw in nativeCreate, and
        // NativeAnalyzer.close() zeroes it so it is destroyed only once.
        guard((), || drop(unsafe { Box::from_raw(handle as *mut Analyzer) }));
    }
}
