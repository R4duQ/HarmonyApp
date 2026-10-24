// shuffle_jni.cpp: the JNI bridge for SmartShuffleEngine, the
// ShuffleDiagnosticBrain's telemetry, the synthetic self-test and the 24-D
// AcousticExtractor (com.harmony.core.dsp.SmartShuffleNative).
//
// Methods are registered in JNI_OnLoad rather than found by name, so R8 may
// shorten everything around them; only the class and the native method names
// must survive (app/proguard-rules.pro keeps them).
//
// Every engine call takes the engine's lock: the queue manager calls from a
// background coroutine while the indexer or a debug screen may ask too.
// Arrays are read and written with GetPrimitiveArrayCritical (copied in or
// out, never held across engine work), so no call pins the Java heap for long.

#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include "SmartShuffleEngine.h"

using namespace harmony::shuffle;

namespace {

constexpr const char* kBridgeClass = "com/harmony/core/dsp/SmartShuffleNative";

/** Per trace: rawScore, vibe%, markov%, affinity%, exploration%, cooldown, smoothness, cosine. */
constexpr int kTraceFloats = 8;
/** Per trace: zone, quality, reason. */
constexpr int kTraceInts = 3;
/** Per check (metric, threshold), then avgSim, artistH, zoneH, ||S||, real%, boost, ms. */
constexpr int kAuditFloats = kAuditChecks * 2 + 7;
/** Per check (status, corrections), then generation, afterSkip, nonFinite, real, fallback, placeholder. */
constexpr int kAuditInts = kAuditChecks * 2 + 6;

struct Handle {
    SmartShuffleEngine engine;
    std::mutex lock;
};

inline Handle* handleOf(jlong h) { return reinterpret_cast<Handle*>(h); }

void throwIllegalArgument(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("java/lang/IllegalArgumentException");
    if (type != nullptr) env->ThrowNew(type, message);
}

template <typename T, typename JArray>
bool readArray(JNIEnv* env, JArray array, std::vector<T>& out) {
    out.clear();
    if (array == nullptr) return true;
    const jsize length = env->GetArrayLength(array);
    out.resize(static_cast<size_t>(length));
    if (length == 0) return true;
    void* data = env->GetPrimitiveArrayCritical(array, nullptr);
    if (data == nullptr) return false;  // OutOfMemoryError pending
    std::copy_n(static_cast<const T*>(data), length, out.data());
    env->ReleasePrimitiveArrayCritical(array, data, JNI_ABORT);
    return true;
}

template <typename T, typename JArray>
void writeArray(JNIEnv* env, JArray array, const T* values, int count) {
    if (array == nullptr || count <= 0) return;
    const int n = std::min<int>(count, env->GetArrayLength(array));
    void* data = env->GetPrimitiveArrayCritical(array, nullptr);
    if (data == nullptr) return;
    std::copy_n(values, n, static_cast<T*>(data));
    env->ReleasePrimitiveArrayCritical(array, data, 0);
}

jobjectArray toStringArray(JNIEnv* env, const std::vector<std::string>& values) {
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) return nullptr;
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(values.size()), stringClass, nullptr);
    if (out == nullptr) return nullptr;
    for (size_t i = 0; i < values.size(); ++i) {
        jstring s = env->NewStringUTF(values[i].c_str());
        if (s == nullptr) return nullptr;
        env->SetObjectArrayElement(out, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return out;
}

// ---- Lifecycle ------------------------------------------------------------------------

jlong nativeCreate(JNIEnv*, jclass) { return reinterpret_cast<jlong>(new Handle()); }

void nativeDestroy(JNIEnv*, jclass, jlong h) { delete handleOf(h); }

// ---- Library and habits ---------------------------------------------------------------

jint nativeSetLibrary(JNIEnv* env, jclass, jlong h, jlongArray ids, jfloatArray raw, jbyteArray quality,
                      jintArray artists, jintArray albums) {
    std::vector<jlong> idv;
    std::vector<jfloat> rawv;
    std::vector<jbyte> qv;
    std::vector<jint> av;
    std::vector<jint> albv;
    if (!readArray(env, ids, idv) || !readArray(env, raw, rawv) || !readArray(env, quality, qv) ||
        !readArray(env, artists, av) || !readArray(env, albums, albv)) {
        return 0;
    }
    const size_t n = idv.size();
    if (rawv.size() != n * kDims || (!qv.empty() && qv.size() != n) || (!av.empty() && av.size() != n) ||
        (!albv.empty() && albv.size() != n)) {
        throwIllegalArgument(env, "library arrays disagree on the track count (raw needs 24 floats per track)");
        return 0;
    }
    std::vector<std::int64_t> id64(idv.begin(), idv.end());
    std::vector<std::uint8_t> q8(qv.begin(), qv.end());
    std::vector<std::int32_t> a32(av.begin(), av.end());
    std::vector<std::int32_t> al32(albv.begin(), albv.end());
    Handle* handle = handleOf(h);
    std::lock_guard<std::mutex> guard(handle->lock);
    return handle->engine.setLibrary(id64.data(), rawv.data(), q8.empty() ? nullptr : q8.data(),
                                     a32.empty() ? nullptr : a32.data(), al32.empty() ? nullptr : al32.data(),
                                     static_cast<int>(n));
}

void nativeSetTrackStats(JNIEnv* env, jclass, jlong h, jintArray completed, jintArray skipped,
                         jlongArray lastPlayed, jbyteArray favorite) {
    std::vector<jint> c;
    std::vector<jint> s;
    std::vector<jlong> l;
    std::vector<jbyte> f;
    if (!readArray(env, completed, c) || !readArray(env, skipped, s) || !readArray(env, lastPlayed, l) ||
        !readArray(env, favorite, f)) {
        return;
    }
    const size_t n = std::max({c.size(), s.size(), l.size(), f.size()});
    c.resize(n, 0);
    s.resize(n, 0);
    l.resize(n, 0);
    f.resize(n, 0);
    std::vector<std::int32_t> c32(c.begin(), c.end());
    std::vector<std::int32_t> s32(s.begin(), s.end());
    std::vector<std::int64_t> l64(l.begin(), l.end());
    std::vector<std::uint8_t> f8(f.begin(), f.end());
    Handle* handle = handleOf(h);
    std::lock_guard<std::mutex> guard(handle->lock);
    handle->engine.setTrackStats(c32.data(), s32.data(), l64.data(), f8.data(), static_cast<int>(n));
}

void nativeSetSteering(JNIEnv* env, jclass, jlong h, jfloatArray perTrack, jfloat weight) {
    std::vector<jfloat> values;
    if (!readArray(env, perTrack, values)) return;
    Handle* handle = handleOf(h);
    std::lock_guard<std::mutex> guard(handle->lock);
    handle->engine.setSteering(values.empty() ? nullptr : values.data(), static_cast<int>(values.size()), weight);
}

void nativeAddTransitions(JNIEnv* env, jclass, jlong h, jlongArray from, jlongArray to, jfloatArray weight) {
    std::vector<jlong> f;
    std::vector<jlong> t;
    std::vector<jfloat> w;
    if (!readArray(env, from, f) || !readArray(env, to, t) || !readArray(env, weight, w)) return;
    if (f.size() != t.size() || (!w.empty() && w.size() != f.size())) {
        throwIllegalArgument(env, "transition arrays disagree on length");
        return;
    }
    std::vector<std::int64_t> f64(f.begin(), f.end());
    std::vector<std::int64_t> t64(t.begin(), t.end());
    Handle* handle = handleOf(h);
    std::lock_guard<std::mutex> guard(handle->lock);
    handle->engine.addTransitions(f64.data(), t64.data(), w.empty() ? nullptr : w.data(), static_cast<int>(f.size()));
}

void nativeClearTransitions(JNIEnv*, jclass, jlong h) {
    Handle* handle = handleOf(h);
    std::lock_guard<std::mutex> guard(handle->lock);
    handle->engine.clearTransitions();
}

jfloat nativeMarkovProbability(JNIEnv*, jclass, jlong h, jlong from, jlong to) {
    Handle* handle = handleOf(h);
    std::lock_guard<std::mutex> guard(handle->lock);
    return handle->engine.markovProbability(from, to);
}

void nativeSetZoneBandit(JNIEnv* env, jclass, jlong h, jfloatArray pulls, jfloatArray rewards) {
    std::vector<jfloat> p;
    std::vector<jfloat> r;
    if (!readArray(env, pulls, p) || !readArray(env, rewards, r)) return;
    if (p.size() != kZones * kZones || r.size() != kZones * kZones) {
        throwIllegalArgument(env, "the zone bandit holds 10 x 10 pulls and rewards");
        return;
    }
    Handle* handle = handleOf(h);
    std::lock_guard<std::mutex> guard(handle->lock);
    handle->engine.setZoneBandit(p.data(), r.data());
}

void nativeGetZoneBandit(JNIEnv* env, jclass, jlong h, jfloatArray pulls, jfloatArray rewards) {
    float p[kZones * kZones];
    float r[kZones * kZones];
    {
        Handle* handle = handleOf(h);
        std::lock_guard<std::mutex> guard(handle->lock);
        handle->engine.zoneBandit(p, r);
    }
    writeArray(env, pulls, p, kZones * kZones);
    writeArray(env, rewards, r, kZones * kZones);
}

/** [values]: similarity, markov, affinity, explore, temperature, jerkThreshold, echoThreshold. */
void nativeSetWeights(JNIEnv* env, jclass, jlong h, jfloatArray values, jint minArtistSpacing) {
    std::vector<jfloat> v;
    if (!readArray(env, values, v)) return;
    if (v.size() < 7) {
        throwIllegalArgument(env, "expected 7 weights");
        return;
    }
    Handle* handle = handleOf(h);
    std::lock_guard<std::mutex> guard(handle->lock);
    Weights w = handle->engine.weights();
    w.similarity = v[0];
    w.markov = v[1];
    w.affinity = v[2];
    w.explore = v[3];
    w.temperature = v[4];
    w.jerkThreshold = v[5];
    w.echoThreshold = v[6];
    w.minArtistSpacing = minArtistSpacing;
    handle->engine.setWeights(w);
}

jint nativeRepairedInputs(JNIEnv*, jclass, jlong h) {
    Handle* handle = handleOf(h);
    std::lock_guard<std::mutex> guard(handle->lock);
    return handle->engine.repairedInputValues();
}

// ---- Session --------------------------------------------------------------------------

jint nativeOnTrackStarted(JNIEnv*, jclass, jlong h, jlong id) {
    Handle* handle = handleOf(h);
    std::lock_guard<std::mutex> guard(handle->lock);
    return static_cast<jint>(handle->engine.onTrackStarted(id));
}

jint nativeOnPlaybackOutcome(JNIEnv*, jclass, jlong h, jlong id, jfloat fraction, jlong nowMs, jboolean pick) {
    Handle* handle = handleOf(h);
    std::lock_guard<std::mutex> guard(handle->lock);
    return static_cast<jint>(handle->engine.onPlaybackOutcome(id, fraction, nowMs, pick == JNI_TRUE));
}

jint nativeOnManualSelect(JNIEnv*, jclass, jlong h, jlong id) {
    Handle* handle = handleOf(h);
    std::lock_guard<std::mutex> guard(handle->lock);
    return static_cast<jint>(handle->engine.onManualSelect(id));
}

void nativeResetSession(JNIEnv*, jclass, jlong h) {
    Handle* handle = handleOf(h);
    std::lock_guard<std::mutex> guard(handle->lock);
    handle->engine.resetSession();
}

jboolean nativeSessionVector(JNIEnv* env, jclass, jlong h, jfloatArray out) {
    float v[kDims];
    bool has;
    {
        Handle* handle = handleOf(h);
        std::lock_guard<std::mutex> guard(handle->lock);
        std::copy_n(handle->engine.sessionVector(), kDims, v);
        has = handle->engine.hasSession();
    }
    writeArray(env, out, v, kDims);
    return has ? JNI_TRUE : JNI_FALSE;
}

/** Writes (from, to) into [ids]; returns the weight learned, 0 when none. */
jfloat nativeLastLearnedTransition(JNIEnv* env, jclass, jlong h, jlongArray ids) {
    std::int64_t from = -1;
    std::int64_t to = -1;
    float delta = 0.0f;
    bool learned;
    {
        Handle* handle = handleOf(h);
        std::lock_guard<std::mutex> guard(handle->lock);
        learned = handle->engine.lastLearnedTransition(&from, &to, &delta);
    }
    if (!learned) return 0.0f;
    const jlong values[2] = {from, to};
    writeArray(env, ids, values, 2);
    return delta;
}

/** Writes (context, arm, reward) into [out]; false when the last outcome rewarded nothing. */
jboolean nativeLastBanditUpdate(JNIEnv* env, jclass, jlong h, jfloatArray out) {
    int context = -1;
    int arm = -1;
    float reward = 0.0f;
    bool updated;
    {
        Handle* handle = handleOf(h);
        std::lock_guard<std::mutex> guard(handle->lock);
        updated = handle->engine.lastBanditUpdate(&context, &arm, &reward);
    }
    if (!updated) return JNI_FALSE;
    const float values[3] = {static_cast<float>(context), static_cast<float>(arm), reward};
    writeArray(env, out, values, 3);
    return JNI_TRUE;
}

// ---- Queue ----------------------------------------------------------------------------

jint nativeGenerateQueue(JNIEnv* env, jclass, jlong h, jlong currentId, jlongArray prefix, jlongArray exclude,
                         jlongArray replaced, jint count, jlong nowMs, jlong seed, jlongArray out) {
    std::vector<jlong> p;
    std::vector<jlong> e;
    std::vector<jlong> r;
    if (!readArray(env, prefix, p) || !readArray(env, exclude, e) || !readArray(env, replaced, r)) return 0;
    if (out == nullptr || env->GetArrayLength(out) < count) {
        throwIllegalArgument(env, "output array smaller than count");
        return 0;
    }
    std::vector<std::int64_t> p64(p.begin(), p.end());
    std::vector<std::int64_t> e64(e.begin(), e.end());
    std::vector<std::int64_t> r64(r.begin(), r.end());
    std::vector<std::int64_t> result(static_cast<size_t>(std::max(0, count)));
    int written;
    {
        Handle* handle = handleOf(h);
        std::lock_guard<std::mutex> guard(handle->lock);
        written = handle->engine.generateQueue(currentId, p64.data(), static_cast<int>(p64.size()), e64.data(),
                                               static_cast<int>(e64.size()), r64.data(), static_cast<int>(r64.size()),
                                               count, nowMs, static_cast<std::uint64_t>(seed), result.data());
    }
    std::vector<jlong> asJava(result.begin(), result.begin() + written);
    writeArray(env, out, asJava.data(), written);
    return written;
}

jlong nativeJourneyStep(JNIEnv* env, jclass, jlong h, jlong fromId, jlong toId, jfloat t, jlong previousId,
                        jlongArray exclude, jlong nowMs, jlong seed) {
    std::vector<jlong> e;
    if (!readArray(env, exclude, e)) return -1;
    std::vector<std::int64_t> e64(e.begin(), e.end());
    Handle* handle = handleOf(h);
    std::lock_guard<std::mutex> guard(handle->lock);
    return handle->engine.journeyStep(fromId, toId, t, previousId, e64.data(), static_cast<int>(e64.size()), nowMs,
                                      static_cast<std::uint64_t>(seed));
}

// ---- Telemetry ------------------------------------------------------------------------

jint nativeTraceCount(JNIEnv*, jclass, jlong h) {
    Handle* handle = handleOf(h);
    std::lock_guard<std::mutex> guard(handle->lock);
    return static_cast<jint>(handle->engine.lastTraces().size());
}

/**
 * The last queue's TrackDecisionTraces: ids, kTraceFloats floats and
 * kTraceInts ints per trace; returns the reason tags.
 */
jobjectArray nativeTraces(JNIEnv* env, jclass, jlong h, jlongArray ids, jfloatArray floats, jintArray ints) {
    std::vector<jlong> idv;
    std::vector<float> fv;
    std::vector<jint> iv;
    std::vector<std::string> reasons;
    {
        Handle* handle = handleOf(h);
        std::lock_guard<std::mutex> guard(handle->lock);
        for (const auto& t : handle->engine.lastTraces()) {
            idv.push_back(t.trackId);
            const float values[kTraceFloats] = {t.rawScore, t.vibeSimilarityPct, t.markovHabitPct, t.userAffinityPct,
                                                t.explorationBonusPct, t.recencyPenaltyMultiplier,
                                                t.transitionSmoothnessScore, t.cosineSimilarity};
            fv.insert(fv.end(), values, values + kTraceFloats);
            iv.push_back(t.zone);
            iv.push_back(static_cast<jint>(t.quality));
            iv.push_back(static_cast<jint>(t.reason));
            reasons.push_back(t.primaryReasonTag);
        }
    }
    writeArray(env, ids, idv.data(), static_cast<int>(idv.size()));
    writeArray(env, floats, fv.data(), static_cast<int>(fv.size()));
    writeArray(env, ints, iv.data(), static_cast<int>(iv.size()));
    return toStringArray(env, reasons);
}

/** The last BrainAuditReport: kAuditFloats floats, kAuditInts ints; returns the five details. */
jobjectArray nativeAudit(JNIEnv* env, jclass, jlong h, jfloatArray floats, jintArray ints) {
    float fv[kAuditFloats];
    jint iv[kAuditInts];
    std::vector<std::string> details;
    {
        Handle* handle = handleOf(h);
        std::lock_guard<std::mutex> guard(handle->lock);
        const BrainAuditReport& a = handle->engine.lastAudit();
        for (int c = 0; c < kAuditChecks; ++c) {
            fv[c * 2] = a.checks[c].metric;
            fv[c * 2 + 1] = a.checks[c].threshold;
            iv[c * 2] = static_cast<jint>(a.checks[c].status);
            iv[c * 2 + 1] = a.checks[c].corrections;
            details.push_back(a.checks[c].detail);
        }
        int f = kAuditChecks * 2;
        fv[f++] = a.averagePairwiseSimilarity;
        fv[f++] = a.artistEntropyBits;
        fv[f++] = a.zoneEntropyBits;
        fv[f++] = a.sessionNorm;
        fv[f++] = a.realVectorPct;
        fv[f++] = a.exploreBoost;
        fv[f++] = static_cast<float>(a.elapsedMs);
        int i = kAuditChecks * 2;
        iv[i++] = static_cast<jint>(a.generation & 0x7fffffff);
        iv[i++] = a.afterFastSkip ? 1 : 0;
        iv[i++] = a.nonFiniteValues;
        iv[i++] = a.realTracks;
        iv[i++] = a.fallbackTracks;
        iv[i++] = a.placeholderTracks;
    }
    writeArray(env, floats, fv, kAuditFloats);
    writeArray(env, ints, iv, kAuditInts);
    return toStringArray(env, details);
}

jstring nativeRunSyntheticSimulation(JNIEnv* env, jclass) {
    const std::string report = runSyntheticBrainSimulation(nullptr);
    return env->NewStringUTF(report.c_str());
}

// ---- 24-D extraction (the background indexer) -----------------------------------------

jlong nativeExtractorCreate(JNIEnv*, jclass, jint sampleRate) {
    auto* extractor = new AcousticExtractor(sampleRate);
    if (!extractor->valid()) {
        delete extractor;
        return 0;
    }
    return reinterpret_cast<jlong>(extractor);
}

void nativeExtractorProcess(JNIEnv* env, jclass, jlong h, jfloatArray samples, jint count) {
    auto* extractor = reinterpret_cast<AcousticExtractor*>(h);
    if (extractor == nullptr || samples == nullptr || count <= 0) return;
    const jint n = std::min<jint>(count, env->GetArrayLength(samples));
    void* data = env->GetPrimitiveArrayCritical(samples, nullptr);
    if (data == nullptr) return;
    // A copy keeps the critical section to a memcpy, not a filter bank.
    std::vector<float> chunk(static_cast<const float*>(data), static_cast<const float*>(data) + n);
    env->ReleasePrimitiveArrayCritical(samples, data, JNI_ABORT);
    extractor->process(chunk.data(), n);
}

jboolean nativeExtractorFinish(JNIEnv* env, jclass, jlong h, jfloatArray out) {
    auto* extractor = reinterpret_cast<AcousticExtractor*>(h);
    if (extractor == nullptr) return JNI_FALSE;
    float features[kDims];
    if (!extractor->finish(features)) return JNI_FALSE;
    writeArray(env, out, features, kDims);
    return JNI_TRUE;
}

void nativeExtractorDestroy(JNIEnv*, jclass, jlong h) { delete reinterpret_cast<AcousticExtractor*>(h); }

#define HARMONY_JNI_METHOD(name, signature) \
    { const_cast<char*>(#name), const_cast<char*>(signature), reinterpret_cast<void*>(name) }

const JNINativeMethod kMethods[] = {
    HARMONY_JNI_METHOD(nativeCreate, "()J"),
    HARMONY_JNI_METHOD(nativeDestroy, "(J)V"),
    HARMONY_JNI_METHOD(nativeSetLibrary, "(J[J[F[B[I[I)I"),
    HARMONY_JNI_METHOD(nativeSetTrackStats, "(J[I[I[J[B)V"),
    HARMONY_JNI_METHOD(nativeSetSteering, "(J[FF)V"),
    HARMONY_JNI_METHOD(nativeAddTransitions, "(J[J[J[F)V"),
    HARMONY_JNI_METHOD(nativeClearTransitions, "(J)V"),
    HARMONY_JNI_METHOD(nativeMarkovProbability, "(JJJ)F"),
    HARMONY_JNI_METHOD(nativeSetZoneBandit, "(J[F[F)V"),
    HARMONY_JNI_METHOD(nativeGetZoneBandit, "(J[F[F)V"),
    HARMONY_JNI_METHOD(nativeSetWeights, "(J[FI)V"),
    HARMONY_JNI_METHOD(nativeRepairedInputs, "(J)I"),
    HARMONY_JNI_METHOD(nativeOnTrackStarted, "(JJ)I"),
    HARMONY_JNI_METHOD(nativeOnPlaybackOutcome, "(JJFJZ)I"),
    HARMONY_JNI_METHOD(nativeOnManualSelect, "(JJ)I"),
    HARMONY_JNI_METHOD(nativeResetSession, "(J)V"),
    HARMONY_JNI_METHOD(nativeSessionVector, "(J[F)Z"),
    HARMONY_JNI_METHOD(nativeLastLearnedTransition, "(J[J)F"),
    HARMONY_JNI_METHOD(nativeLastBanditUpdate, "(J[F)Z"),
    HARMONY_JNI_METHOD(nativeGenerateQueue, "(JJ[J[J[JIJJ[J)I"),
    HARMONY_JNI_METHOD(nativeJourneyStep, "(JJJFJ[JJJ)J"),
    HARMONY_JNI_METHOD(nativeTraceCount, "(J)I"),
    HARMONY_JNI_METHOD(nativeTraces, "(J[J[F[I)[Ljava/lang/String;"),
    HARMONY_JNI_METHOD(nativeAudit, "(J[F[I)[Ljava/lang/String;"),
    HARMONY_JNI_METHOD(nativeRunSyntheticSimulation, "()Ljava/lang/String;"),
    HARMONY_JNI_METHOD(nativeExtractorCreate, "(I)J"),
    HARMONY_JNI_METHOD(nativeExtractorProcess, "(J[FI)V"),
    HARMONY_JNI_METHOD(nativeExtractorFinish, "(J[F)Z"),
    HARMONY_JNI_METHOD(nativeExtractorDestroy, "(J)V"),
};

#undef HARMONY_JNI_METHOD

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass bridge = env->FindClass(kBridgeClass);
    if (bridge == nullptr) return JNI_ERR;
    const jint count = static_cast<jint>(sizeof(kMethods) / sizeof(kMethods[0]));
    if (env->RegisterNatives(bridge, kMethods, count) != JNI_OK) return JNI_ERR;
    env->DeleteLocalRef(bridge);
    return JNI_VERSION_1_6;
}
