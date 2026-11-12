//! libharmonyshuffle: Harmony's offline Smart Shuffle and the
//! ShuffleDiagnosticBrain that audits it, the "Mix from this song" radio, the
//! synthetic self-test and the 24-D AcousticExtractor, with their JNI bridge
//! (com.harmony.core.dsp.SmartShuffleNative).
//!
//!   AcousticExtractor   decoded PCM -> 24 raw acoustic features
//!   SmartShuffleEngine  library of unit vectors, the session vibe vector S_t,
//!                       multi-factor scoring, Efraimidis-Spirakis sampling,
//!                       1D error-diffusion dithering (Spotify's anti-clumping)
//!                       and greedy nearest-neighbour ordering; Sonic Adventure
//!                       journeys between two songs
//!   Diagnostic Brain    explains every queued track (TrackDecisionTrace),
//!                       checks five invariants after every generation and
//!                       repairs the queue when one fails
//!
//! Every engine call takes the engine's mutex: the queue manager calls from a
//! background coroutine while the indexer or a debug screen may ask too.
//! Arrays are copied in and out with Get/Set<Type>ArrayRegion, never held
//! across engine work. Every entry point catches panics, so a bug surfaces in
//! Kotlin as an empty result rather than a crashed app. The natives are
//! exported by their JNI names; app/proguard-rules.pro keeps the class and
//! method names.

// Index loops mirror the C++ original, which keeps the port easy to check;
// `-> ()` comes from the jni_fn! macro's uniform shape.
#![allow(clippy::needless_range_loop, clippy::unused_unit)]

pub mod brain;
pub mod engine;
pub mod extractor;
pub mod radio;
pub mod selftest;
pub mod types;

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{Mutex, MutexGuard};

use jni::objects::{JByteArray, JClass, JFloatArray, JIntArray, JLongArray, JObject};
use jni::sys::{jboolean, jfloat, jint, jlong, jobjectArray, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

pub use engine::{zone_of, zone_word, SmartShuffleEngine};
pub use extractor::AcousticExtractor;
pub use selftest::run_synthetic_brain_simulation;
pub use types::*;

/// Per trace: rawScore, vibe%, markov%, affinity%, exploration%, cooldown, smoothness, cosine.
const TRACE_FLOATS: usize = 8;
/// Per trace: zone, quality, reason.
const TRACE_INTS: usize = 3;
/// See nativeRadioReport.
const RADIO_REPORT_FLOATS: usize = 14;
/// Per check (metric, threshold), then avgSim, artistH, zoneH, ||S||, real%, boost, ms.
const AUDIT_FLOATS: usize = AUDIT_CHECKS * 2 + 7;
/// Per check (status, corrections), then generation, afterSkip, nonFinite, real, fallback, placeholder.
const AUDIT_INTS: usize = AUDIT_CHECKS * 2 + 6;

fn guard<T>(fallback: T, body: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(body)).unwrap_or(fallback)
}

fn throw_illegal_argument(env: &mut JNIEnv, message: &str) {
    let _ = env.throw_new("java/lang/IllegalArgumentException", message);
}

/// Locks the engine behind `handle`, recovering it if a panic poisoned the lock.
///
/// # Safety
/// `handle` must be 0 or a live pointer from nativeCreate.
unsafe fn engine<'a>(handle: jlong) -> Option<MutexGuard<'a, SmartShuffleEngine>> {
    let shared = (handle as *const Mutex<SmartShuffleEngine>).as_ref()?;
    Some(shared.lock().unwrap_or_else(|poisoned| poisoned.into_inner()))
}

macro_rules! read_array {
    ($name:ident, $array:ty, $elem:ty, $region:ident) => {
        /// The array's contents (empty for null), or None when the VM failed.
        fn $name(env: &mut JNIEnv, array: &$array) -> Option<Vec<$elem>> {
            if array.is_null() {
                return Some(Vec::new());
            }
            let length = env.get_array_length(array).ok()?.max(0) as usize;
            let mut out = vec![<$elem>::default(); length];
            if length > 0 {
                env.$region(array, 0, &mut out).ok()?;
            }
            Some(out)
        }
    };
}
read_array!(read_longs, JLongArray, i64, get_long_array_region);
read_array!(read_floats, JFloatArray, f32, get_float_array_region);
read_array!(read_ints, JIntArray, i32, get_int_array_region);
read_array!(read_bytes, JByteArray, i8, get_byte_array_region);

macro_rules! write_array {
    ($name:ident, $array:ty, $elem:ty, $region:ident) => {
        /// Copies as many of `values` as `array` holds into it (nothing for null).
        fn $name(env: &mut JNIEnv, array: &$array, values: &[$elem]) {
            if array.is_null() || values.is_empty() {
                return;
            }
            let Ok(length) = env.get_array_length(array) else { return };
            let n = values.len().min(length.max(0) as usize);
            let _ = env.$region(array, 0, &values[..n]);
        }
    };
}
write_array!(write_longs, JLongArray, i64, set_long_array_region);
write_array!(write_floats, JFloatArray, f32, set_float_array_region);
write_array!(write_ints, JIntArray, i32, set_int_array_region);

fn to_string_array(env: &mut JNIEnv, values: &[String]) -> jobjectArray {
    let Ok(out) = env.new_object_array(values.len() as jint, "java/lang/String", JObject::null()) else {
        return std::ptr::null_mut();
    };
    for (i, value) in values.iter().enumerate() {
        let Ok(s) = env.new_string(value) else { return std::ptr::null_mut() };
        if env.set_object_array_element(&out, i as jint, &s).is_err() {
            return std::ptr::null_mut();
        }
        let _ = env.delete_local_ref(s);
    }
    out.into_raw()
}

macro_rules! jni_fn {
    ($name:ident ( $env:ident, $($arg:ident : $ty:ty),* ) -> $ret:ty = $fallback:expr; $body:block) => {
        #[no_mangle]
        pub extern "system" fn $name(#[allow(unused_mut)] mut $env: JNIEnv, _class: JClass, $($arg: $ty),*) -> $ret {
            #[allow(unused_mut, unused_variables)]
            let env_ref = &mut $env;
            guard($fallback, || {
                #[allow(unused_variables)]
                let $env = env_ref;
                $body
            })
        }
    };
}

/// The locked engine behind `handle`, or `$fallback` returned from the enclosing JNI function.
macro_rules! engine_or {
    ($handle:expr, $fallback:expr) => {
        // SAFETY: Kotlin only passes handles from nativeCreate it has not destroyed.
        match unsafe { engine($handle) } {
            Some(e) => e,
            None => return $fallback,
        }
    };
}

/// The value of `$read` or `$fallback` returned from the enclosing JNI function.
macro_rules! read_or {
    ($read:expr, $fallback:expr) => {
        match $read {
            Some(v) => v,
            None => return $fallback,
        }
    };
}

// ---- Lifecycle --------------------------------------------------------------------------

jni_fn!(Java_com_harmony_core_dsp_SmartShuffleNative_nativeCreate(env,) -> jlong = 0; {
    Box::into_raw(Box::new(Mutex::new(SmartShuffleEngine::new()))) as jlong
});

jni_fn!(Java_com_harmony_core_dsp_SmartShuffleNative_nativeDestroy(env, handle: jlong) -> () = (); {
    if handle != 0 {
        // SAFETY: the handle came from Box::into_raw in nativeCreate and is destroyed once.
        drop(unsafe { Box::from_raw(handle as *mut Mutex<SmartShuffleEngine>) });
    }
});

// ---- Library and habits -------------------------------------------------------------------

jni_fn!(Java_com_harmony_core_dsp_SmartShuffleNative_nativeSetLibrary(
    env, handle: jlong, ids: JLongArray, raw: JFloatArray, quality: JByteArray, artists: JIntArray, albums: JIntArray
) -> jint = 0; {
    let idv = read_or!(read_longs(env, &ids), 0);
    let rawv = read_or!(read_floats(env, &raw), 0);
    let qv = read_or!(read_bytes(env, &quality), 0);
    let av = read_or!(read_ints(env, &artists), 0);
    let albv = read_or!(read_ints(env, &albums), 0);
    let n = idv.len();
    if rawv.len() != n * DIMS
        || (!qv.is_empty() && qv.len() != n)
        || (!av.is_empty() && av.len() != n)
        || (!albv.is_empty() && albv.len() != n)
    {
        throw_illegal_argument(env, "library arrays disagree on the track count (raw needs 24 floats per track)");
        return 0;
    }
    let q8: Vec<u8> = qv.iter().map(|&q| q as u8).collect();
    let mut e = engine_or!(handle, 0);
    e.set_library(
        &idv,
        Some(&rawv),
        (!q8.is_empty()).then_some(&q8[..]),
        (!av.is_empty()).then_some(&av[..]),
        (!albv.is_empty()).then_some(&albv[..]),
    ) as jint
});

jni_fn!(Java_com_harmony_core_dsp_SmartShuffleNative_nativeSetTrackStats(
    env, handle: jlong, completed: JIntArray, skipped: JIntArray, last_played: JLongArray, favorite: JByteArray
) -> () = (); {
    let mut c = read_or!(read_ints(env, &completed), ());
    let mut s = read_or!(read_ints(env, &skipped), ());
    let mut l = read_or!(read_longs(env, &last_played), ());
    let f = read_or!(read_bytes(env, &favorite), ());
    let n = c.len().max(s.len()).max(l.len()).max(f.len());
    c.resize(n, 0);
    s.resize(n, 0);
    l.resize(n, 0);
    let mut f8: Vec<u8> = f.iter().map(|&b| b as u8).collect();
    f8.resize(n, 0);
    engine_or!(handle, ()).set_track_stats(Some(&c), Some(&s), Some(&l), Some(&f8), n);
});

jni_fn!(Java_com_harmony_core_dsp_SmartShuffleNative_nativeSetSteering(env, handle: jlong, per_track: JFloatArray, weight: jfloat) -> () = (); {
    let values = read_or!(read_floats(env, &per_track), ());
    engine_or!(handle, ()).set_steering((!values.is_empty()).then_some(&values[..]), weight);
});

jni_fn!(Java_com_harmony_core_dsp_SmartShuffleNative_nativeAddTransitions(
    env, handle: jlong, from: JLongArray, to: JLongArray, weight: JFloatArray
) -> () = (); {
    let f = read_or!(read_longs(env, &from), ());
    let t = read_or!(read_longs(env, &to), ());
    let w = read_or!(read_floats(env, &weight), ());
    if f.len() != t.len() || (!w.is_empty() && w.len() != f.len()) {
        throw_illegal_argument(env, "transition arrays disagree on length");
        return;
    }
    engine_or!(handle, ()).add_transitions(&f, &t, (!w.is_empty()).then_some(&w[..]));
});

jni_fn!(Java_com_harmony_core_dsp_SmartShuffleNative_nativeClearTransitions(env, handle: jlong) -> () = (); {
    engine_or!(handle, ()).clear_transitions();
});

jni_fn!(Java_com_harmony_core_dsp_SmartShuffleNative_nativeMarkovProbability(env, handle: jlong, from: jlong, to: jlong) -> jfloat = 0.0; {
    engine_or!(handle, 0.0).markov_probability(from, to)
});

jni_fn!(Java_com_harmony_core_dsp_SmartShuffleNative_nativeSetZoneBandit(env, handle: jlong, pulls: JFloatArray, rewards: JFloatArray) -> () = (); {
    let p = read_or!(read_floats(env, &pulls), ());
    let r = read_or!(read_floats(env, &rewards), ());
    if p.len() != ZONES * ZONES || r.len() != ZONES * ZONES {
        throw_illegal_argument(env, "the zone bandit holds 10 x 10 pulls and rewards");
        return;
    }
    engine_or!(handle, ()).set_zone_bandit(&p, &r);
});

jni_fn!(Java_com_harmony_core_dsp_SmartShuffleNative_nativeGetZoneBandit(env, handle: jlong, pulls: JFloatArray, rewards: JFloatArray) -> () = (); {
    let (p, r) = engine_or!(handle, ()).zone_bandit();
    write_floats(env, &pulls, &p);
    write_floats(env, &rewards, &r);
});

// [values]: similarity, markov, affinity, explore, temperature, jerkThreshold, echoThreshold.
jni_fn!(Java_com_harmony_core_dsp_SmartShuffleNative_nativeSetWeights(env, handle: jlong, values: JFloatArray, min_artist_spacing: jint) -> () = (); {
    let v = read_or!(read_floats(env, &values), ());
    if v.len() < 7 {
        throw_illegal_argument(env, "expected 7 weights");
        return;
    }
    let mut e = engine_or!(handle, ());
    let mut w = e.weights();
    w.similarity = v[0];
    w.markov = v[1];
    w.affinity = v[2];
    w.explore = v[3];
    w.temperature = v[4];
    w.jerk_threshold = v[5];
    w.echo_threshold = v[6];
    w.min_artist_spacing = min_artist_spacing;
    e.set_weights(&w);
});

jni_fn!(Java_com_harmony_core_dsp_SmartShuffleNative_nativeRepairedInputs(env, handle: jlong) -> jint = 0; {
    engine_or!(handle, 0).repaired_input_values()
});

// ---- Session ------------------------------------------------------------------------------

jni_fn!(Java_com_harmony_core_dsp_SmartShuffleNative_nativeOnTrackStarted(env, handle: jlong, id: jlong) -> jint = 0; {
    engine_or!(handle, 0).on_track_started(id) as jint
});

jni_fn!(Java_com_harmony_core_dsp_SmartShuffleNative_nativeOnPlaybackOutcome(
    env, handle: jlong, id: jlong, fraction: jfloat, now_ms: jlong, pick: jboolean
) -> jint = 0; {
    engine_or!(handle, 0).on_playback_outcome(id, fraction, now_ms, pick == JNI_TRUE) as jint
});

jni_fn!(Java_com_harmony_core_dsp_SmartShuffleNative_nativeOnManualSelect(env, handle: jlong, id: jlong) -> jint = 0; {
    engine_or!(handle, 0).on_manual_select(id) as jint
});

jni_fn!(Java_com_harmony_core_dsp_SmartShuffleNative_nativeResetSession(env, handle: jlong) -> () = (); {
    engine_or!(handle, ()).reset_session();
});

jni_fn!(Java_com_harmony_core_dsp_SmartShuffleNative_nativeSessionVector(env, handle: jlong, out: JFloatArray) -> jboolean = JNI_FALSE; {
    let (v, has) = {
        let e = engine_or!(handle, JNI_FALSE);
        (*e.session_vector(), e.has_session())
    };
    write_floats(env, &out, &v);
    if has { JNI_TRUE } else { JNI_FALSE }
});

// Writes (from, to) into [ids]; returns the weight learned, 0 when none.
jni_fn!(Java_com_harmony_core_dsp_SmartShuffleNative_nativeLastLearnedTransition(env, handle: jlong, ids: JLongArray) -> jfloat = 0.0; {
    let learned = engine_or!(handle, 0.0).last_learned_transition();
    match learned {
        Some((from, to, delta, true)) => {
            write_longs(env, &ids, &[from, to]);
            delta
        }
        _ => 0.0,
    }
});

// Writes (context, arm, reward) into [out]; false when the last outcome rewarded nothing.
jni_fn!(Java_com_harmony_core_dsp_SmartShuffleNative_nativeLastBanditUpdate(env, handle: jlong, out: JFloatArray) -> jboolean = JNI_FALSE; {
    let update = engine_or!(handle, JNI_FALSE).last_bandit_update();
    match update {
        Some((context, arm, reward)) => {
            write_floats(env, &out, &[context as f32, arm as f32, reward]);
            JNI_TRUE
        }
        None => JNI_FALSE,
    }
});

// ---- Queue --------------------------------------------------------------------------------

jni_fn!(Java_com_harmony_core_dsp_SmartShuffleNative_nativeGenerateQueue(
    env, handle: jlong, current_id: jlong, prefix: JLongArray, exclude: JLongArray, replaced: JLongArray,
    count: jint, now_ms: jlong, seed: jlong, out: JLongArray
) -> jint = 0; {
    let p = read_or!(read_longs(env, &prefix), 0);
    let x = read_or!(read_longs(env, &exclude), 0);
    let r = read_or!(read_longs(env, &replaced), 0);
    if out.is_null() || env.get_array_length(&out).map_or(true, |n| n < count) {
        throw_illegal_argument(env, "output array smaller than count");
        return 0;
    }
    let ids = engine_or!(handle, 0).generate_queue(current_id, &p, &x, &r, count, now_ms, seed as u64);
    write_longs(env, &out, &ids);
    ids.len() as jint
});

// "Mix din piesa asta": a batch of the radio started from [seedId]; see SmartShuffleEngine::generate_radio.
jni_fn!(Java_com_harmony_core_dsp_SmartShuffleNative_nativeGenerateRadio(
    env, handle: jlong, seed_id: jlong, current_id: jlong, prefix: JLongArray, exclude: JLongArray,
    count: jint, now_ms: jlong, seed: jlong, out: JLongArray
) -> jint = 0; {
    let p = read_or!(read_longs(env, &prefix), 0);
    let x = read_or!(read_longs(env, &exclude), 0);
    if out.is_null() || env.get_array_length(&out).map_or(true, |n| n < count) {
        throw_illegal_argument(env, "output array smaller than count");
        return 0;
    }
    let ids = engine_or!(handle, 0).generate_radio(seed_id, current_id, &p, &x, count, now_ms, seed as u64);
    write_longs(env, &out, &ids);
    ids.len() as jint
});

// The last mix batch's stages (RADIO_REPORT_FLOATS): fromSeed, fromSession,
// fromHabits, fromRare, merged, gatedOut, newPlaced, newCap, filled,
// historyConfidence, sessionConfidence, seedMeasured (0/1), elapsedMs,
// feedback events.
jni_fn!(Java_com_harmony_core_dsp_SmartShuffleNative_nativeRadioReport(env, handle: jlong, out: JFloatArray) -> () = (); {
    let values: [f32; RADIO_REPORT_FLOATS] = {
        let e = engine_or!(handle, ());
        let r = e.last_radio();
        [
            r.from_seed as f32, r.from_session as f32, r.from_habits as f32,
            r.from_rare as f32, r.merged as f32, r.gated_out as f32,
            r.new_placed as f32, r.new_cap as f32, r.filled as f32,
            r.history_confidence, r.session_confidence, if r.seed_measured { 1.0 } else { 0.0 },
            r.elapsed_ms as f32, e.feedback_count() as f32,
        ]
    };
    write_floats(env, &out, &values);
});

jni_fn!(Java_com_harmony_core_dsp_SmartShuffleNative_nativeJourneyStep(
    env, handle: jlong, from_id: jlong, to_id: jlong, t: jfloat, previous_id: jlong, exclude: JLongArray,
    now_ms: jlong, seed: jlong
) -> jlong = -1; {
    let x = read_or!(read_longs(env, &exclude), -1);
    engine_or!(handle, -1).journey_step(from_id, to_id, t, previous_id, &x, now_ms, seed as u64).unwrap_or(-1)
});

// ---- Telemetry ----------------------------------------------------------------------------

jni_fn!(Java_com_harmony_core_dsp_SmartShuffleNative_nativeTraceCount(env, handle: jlong) -> jint = 0; {
    engine_or!(handle, 0).last_traces().len() as jint
});

// The last queue's TrackDecisionTraces: ids, TRACE_FLOATS floats and
// TRACE_INTS ints per trace; returns the reason tags.
jni_fn!(Java_com_harmony_core_dsp_SmartShuffleNative_nativeTraces(
    env, handle: jlong, ids: JLongArray, floats: JFloatArray, ints: JIntArray
) -> jobjectArray = std::ptr::null_mut(); {
    let mut idv = Vec::new();
    let mut fv = Vec::new();
    let mut iv = Vec::new();
    let mut reasons = Vec::new();
    {
        let e = engine_or!(handle, std::ptr::null_mut());
        for t in e.last_traces() {
            idv.push(t.track_id);
            fv.extend_from_slice(&[
                t.raw_score, t.vibe_similarity_pct, t.markov_habit_pct, t.user_affinity_pct,
                t.exploration_bonus_pct, t.recency_penalty_multiplier, t.transition_smoothness_score,
                t.cosine_similarity,
            ]);
            iv.extend_from_slice(&[t.zone, t.quality as i32, t.reason as i32]);
            reasons.push(t.primary_reason_tag.clone());
        }
    }
    debug_assert_eq!(fv.len(), idv.len() * TRACE_FLOATS);
    debug_assert_eq!(iv.len(), idv.len() * TRACE_INTS);
    write_longs(env, &ids, &idv);
    write_floats(env, &floats, &fv);
    write_ints(env, &ints, &iv);
    to_string_array(env, &reasons)
});

// The last BrainAuditReport: AUDIT_FLOATS floats, AUDIT_INTS ints; returns the five details.
jni_fn!(Java_com_harmony_core_dsp_SmartShuffleNative_nativeAudit(env, handle: jlong, floats: JFloatArray, ints: JIntArray) -> jobjectArray = std::ptr::null_mut(); {
    let mut fv = [0.0f32; AUDIT_FLOATS];
    let mut iv = [0i32; AUDIT_INTS];
    let mut details = Vec::with_capacity(AUDIT_CHECKS);
    {
        let e = engine_or!(handle, std::ptr::null_mut());
        let a = e.last_audit();
        for (c, check) in a.checks.iter().enumerate() {
            fv[c * 2] = check.metric;
            fv[c * 2 + 1] = check.threshold;
            iv[c * 2] = check.status as i32;
            iv[c * 2 + 1] = check.corrections;
            details.push(check.detail.clone());
        }
        let f = AUDIT_CHECKS * 2;
        fv[f..].copy_from_slice(&[
            a.average_pairwise_similarity, a.artist_entropy_bits, a.zone_entropy_bits, a.session_norm,
            a.real_vector_pct, a.explore_boost, a.elapsed_ms as f32,
        ]);
        let i = AUDIT_CHECKS * 2;
        iv[i..].copy_from_slice(&[
            (a.generation & 0x7fff_ffff) as i32, a.after_fast_skip as i32, a.non_finite_values,
            a.real_tracks, a.fallback_tracks, a.placeholder_tracks,
        ]);
    }
    write_floats(env, &floats, &fv);
    write_ints(env, &ints, &iv);
    to_string_array(env, &details)
});

jni_fn!(Java_com_harmony_core_dsp_SmartShuffleNative_nativeRunSyntheticSimulation(env,) -> jstring = std::ptr::null_mut(); {
    let (report, _) = run_synthetic_brain_simulation();
    env.new_string(report).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
});

// ---- 24-D extraction (the background indexer) ---------------------------------------------

jni_fn!(Java_com_harmony_core_dsp_SmartShuffleNative_nativeExtractorCreate(env, sample_rate: jint) -> jlong = 0; {
    let extractor = AcousticExtractor::new(sample_rate);
    if !extractor.valid() {
        return 0;
    }
    Box::into_raw(Box::new(extractor)) as jlong
});

jni_fn!(Java_com_harmony_core_dsp_SmartShuffleNative_nativeExtractorProcess(env, handle: jlong, samples: JFloatArray, count: jint) -> () = (); {
    // SAFETY: the handle came from nativeExtractorCreate; one caller at a time (NativeAcousticExtractor).
    let Some(extractor) = (unsafe { (handle as *mut AcousticExtractor).as_mut() }) else { return };
    if samples.is_null() || count <= 0 {
        return;
    }
    let Ok(length) = env.get_array_length(&samples) else { return };
    let n = (count as usize).min(length.max(0) as usize);
    let mut chunk = vec![0.0f32; n];
    if n > 0 && env.get_float_array_region(&samples, 0, &mut chunk).is_err() {
        return;
    }
    extractor.process(&chunk);
});

jni_fn!(Java_com_harmony_core_dsp_SmartShuffleNative_nativeExtractorFinish(env, handle: jlong, out: JFloatArray) -> jboolean = JNI_FALSE; {
    // SAFETY: as above.
    let Some(extractor) = (unsafe { (handle as *mut AcousticExtractor).as_mut() }) else { return JNI_FALSE };
    match extractor.finish() {
        Some(features) => {
            write_floats(env, &out, &features);
            JNI_TRUE
        }
        None => JNI_FALSE,
    }
});

jni_fn!(Java_com_harmony_core_dsp_SmartShuffleNative_nativeExtractorDestroy(env, handle: jlong) -> () = (); {
    if handle != 0 {
        // SAFETY: the handle came from Box::into_raw in nativeExtractorCreate and is destroyed once.
        drop(unsafe { Box::from_raw(handle as *mut AcousticExtractor) });
    }
});
