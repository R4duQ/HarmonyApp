// smart_shuffle_jni.cpp — JNI bridge for the Smart Shuffle selector.
//
// Design note: this deliberately mirrors NativeAnalyzer's handle lifecycle
// (nativeCreate -> use -> nativeDestroy) rather than passing the library
// across the boundary on every call.
//
// The reason is marshalling cost, not maths. Scoring 50k tracks is a few
// hundred microseconds; copying 50k * dim floats plus three parallel arrays
// through JNI on every track change is far more expensive, and it happens at
// the worst possible moment — the gap between songs. So the library is
// uploaded once, kept in native memory, and mutated in place as tracks are
// played.

#include <jni.h>

#include <cstring>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

#include "smart_shuffle.h"

using namespace harmony::shuffle;

namespace {

/// Native-side owner of the candidate pool. One per SmartShuffle instance.
struct Session {
    std::mutex lock;  // playback and UI threads both touch this
    std::int32_t dim = 0;

    std::vector<float> features;
    std::vector<std::int64_t> trackIds;
    std::vector<std::int32_t> artistIds;
    std::vector<std::int64_t> lastPlayedMs;
    std::vector<float> dimWeights;  // empty == uniform

    /// trackId -> row, so markPlayed is a hash lookup instead of a scan.
    std::unordered_map<std::int64_t, std::int32_t> indexById;

    Config config;

    Library view() const {
        Library l;
        l.features = features.data();
        l.trackIds = trackIds.data();
        l.artistIds = artistIds.data();
        l.lastPlayedMs = lastPlayedMs.data();
        l.count = static_cast<std::int32_t>(trackIds.size());
        l.dim = dim;
        return l;
    }
};

inline Session* asSession(jlong handle) {
    return reinterpret_cast<Session*>(handle);
}

/// Copies a Java primitive array into a std::vector.
///
/// GetArrayElements rather than GetPrimitiveArrayCritical: the critical
/// variant forbids any other JNI call while held, and this function is called
/// several times in a row for the parallel arrays. Nested critical sections
/// are a portability hazard for a saving that does not matter here, because
/// this runs once per library load rather than once per track.
template <typename JArray, typename JElem, typename Vec>
bool copyArray(JNIEnv* env, JArray array, Vec& out,
               JElem* (JNIEnv::*get)(JArray, jboolean*),
               void (JNIEnv::*release)(JArray, JElem*, jint)) {
    if (array == nullptr) return false;
    const jsize n = env->GetArrayLength(array);
    JElem* p = (env->*get)(array, nullptr);
    if (p == nullptr) return false;
    out.assign(p, p + n);
    (env->*release)(array, p, JNI_ABORT);  // read-only: discard any copy
    return true;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_harmony_core_dsp_NativeSmartShuffle_nativeCreate(JNIEnv*, jobject, jint dim) {
    if (dim <= 0) return 0;
    auto* s = new (std::nothrow) Session();
    if (s == nullptr) return 0;
    s->dim = dim;
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_harmony_core_dsp_NativeSmartShuffle_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete asSession(handle);
}

/// Uploads the candidate pool. Returns the accepted track count, or -1 if the
/// arrays disagree about their length (which would otherwise read out of
/// bounds in the scoring loop).
JNIEXPORT jint JNICALL
Java_com_harmony_core_dsp_NativeSmartShuffle_nativeSetLibrary(
    JNIEnv* env, jobject, jlong handle,
    jfloatArray features, jlongArray trackIds,
    jintArray artistIds, jlongArray lastPlayedMs) {
    Session* s = asSession(handle);
    if (s == nullptr) return -1;
    std::lock_guard<std::mutex> guard(s->lock);

    if (!copyArray<jfloatArray, jfloat>(env, features, s->features,
                                        &JNIEnv::GetFloatArrayElements,
                                        &JNIEnv::ReleaseFloatArrayElements) ||
        !copyArray<jlongArray, jlong>(env, trackIds, s->trackIds,
                                      &JNIEnv::GetLongArrayElements,
                                      &JNIEnv::ReleaseLongArrayElements) ||
        !copyArray<jintArray, jint>(env, artistIds, s->artistIds,
                                    &JNIEnv::GetIntArrayElements,
                                    &JNIEnv::ReleaseIntArrayElements) ||
        !copyArray<jlongArray, jlong>(env, lastPlayedMs, s->lastPlayedMs,
                                      &JNIEnv::GetLongArrayElements,
                                      &JNIEnv::ReleaseLongArrayElements)) {
        return -1;
    }

    const std::size_t n = s->trackIds.size();
    if (s->artistIds.size() != n || s->lastPlayedMs.size() != n ||
        s->features.size() != n * static_cast<std::size_t>(s->dim)) {
        // Refuse a ragged upload outright rather than scoring garbage.
        s->features.clear(); s->trackIds.clear();
        s->artistIds.clear(); s->lastPlayedMs.clear(); s->indexById.clear();
        return -1;
    }

    s->indexById.clear();
    s->indexById.reserve(n);
    for (std::size_t i = 0; i < n; ++i) {
        s->indexById[s->trackIds[i]] = static_cast<std::int32_t>(i);
    }
    return static_cast<jint>(n);
}

JNIEXPORT void JNICALL
Java_com_harmony_core_dsp_NativeSmartShuffle_nativeSetConfig(
    JNIEnv* env, jobject, jlong handle,
    jfloat sameArtistPenalty, jfloat recentPlayPenalty,
    jfloat penaltyFloorFraction, jint topK, jlong recencyWindowMs,
    jfloatArray dimWeights) {
    Session* s = asSession(handle);
    if (s == nullptr) return;
    std::lock_guard<std::mutex> guard(s->lock);

    s->config.sameArtistPenalty = sameArtistPenalty;
    s->config.recentPlayPenalty = recentPlayPenalty;
    s->config.penaltyFloorFraction = penaltyFloorFraction;
    s->config.topK = topK;
    s->config.recencyWindowMs = recencyWindowMs;

    s->dimWeights.clear();
    if (dimWeights != nullptr &&
        env->GetArrayLength(dimWeights) == s->dim) {
        copyArray<jfloatArray, jfloat>(env, dimWeights, s->dimWeights,
                                       &JNIEnv::GetFloatArrayElements,
                                       &JNIEnv::ReleaseFloatArrayElements);
    }
    // Rebound every call: the vector may have reallocated.
    s->config.dimWeights = s->dimWeights.empty() ? nullptr : s->dimWeights.data();
}

/// Updates one track's play time without re-uploading the library.
JNIEXPORT jboolean JNICALL
Java_com_harmony_core_dsp_NativeSmartShuffle_nativeMarkPlayed(
    JNIEnv*, jobject, jlong handle, jlong trackId, jlong atMs) {
    Session* s = asSession(handle);
    if (s == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> guard(s->lock);
    auto it = s->indexById.find(trackId);
    if (it == s->indexById.end()) return JNI_FALSE;
    s->lastPlayedMs[static_cast<std::size_t>(it->second)] = atMs;
    return JNI_TRUE;
}

/// Returns the chosen row index, or -1 if nothing is selectable.
/// `outStats`, if non-null and length >= 3, receives
/// [adjustedDistance, consideredCount, poolSize] for logging.
JNIEXPORT jint JNICALL
Java_com_harmony_core_dsp_NativeSmartShuffle_nativeSelectNext(
    JNIEnv* env, jobject, jlong handle,
    jfloatArray seedFeatures, jlong seedTrackId, jint seedArtistId,
    jlong nowMs, jlong rngSeed, jfloatArray outStats) {
    Session* s = asSession(handle);
    if (s == nullptr || seedFeatures == nullptr) return -1;
    std::lock_guard<std::mutex> guard(s->lock);

    if (env->GetArrayLength(seedFeatures) != s->dim) return -1;

    std::vector<float> seedVec;
    if (!copyArray<jfloatArray, jfloat>(env, seedFeatures, seedVec,
                                        &JNIEnv::GetFloatArrayElements,
                                        &JNIEnv::ReleaseFloatArrayElements)) {
        return -1;
    }

    Seed seed;
    seed.features = seedVec.data();
    seed.trackId = seedTrackId;
    seed.artistId = seedArtistId;

    const Selection r = selectNext(seed, s->view(), s->config,
                                   nowMs, static_cast<std::uint64_t>(rngSeed));

    if (outStats != nullptr && env->GetArrayLength(outStats) >= 3) {
        const jfloat stats[3] = {
            r.adjustedDistance,
            static_cast<jfloat>(r.consideredCount),
            static_cast<jfloat>(r.poolSize),
        };
        env->SetFloatArrayRegion(outStats, 0, 3, stats);
    }
    return r.index;
}

}  // extern "C"
