// JNI bridge for MobileEqEngine.
//
// Zero garbage on every path that runs per buffer or per frame: audio moves
// through direct ByteBuffers (no copies, no arrays), slider arrays are read
// with GetFloatArrayRegion into stack memory, and the spectrum is written
// into float arrays the caller allocated once. Nothing here allocates a Java
// object unless the caller misuses the API, in which case it throws.
//
// Methods are registered explicitly in JNI_OnLoad rather than found by name,
// so R8 may shorten everything around them: only the class name and the
// native method names have to survive (see consumer-rules.pro).

#include <jni.h>

#include <algorithm>
#include <cstdint>

#include "MobileEqEngine.h"

using harmony::eq::MobileEqEngine;
using harmony::eq::OutputDevice;
using harmony::eq::SampleFormat;

namespace {

constexpr const char* kBridgeClass = "com/harmony/core/eq/MobileEqNative";

inline MobileEqEngine* engineOf(jlong handle) { return reinterpret_cast<MobileEqEngine*>(handle); }

void throwIllegalArgument(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("java/lang/IllegalArgumentException");
    if (type != nullptr) env->ThrowNew(type, message);
}

/** The address of [byteOffset] in direct [buffer] if [byteCount] bytes fit there, else null (and throws). */
uint8_t* directRange(JNIEnv* env, jobject buffer, jint byteOffset, jlong byteCount) {
    if (buffer == nullptr) {
        throwIllegalArgument(env, "buffer is null");
        return nullptr;
    }
    auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (base == nullptr || capacity < 0) {
        throwIllegalArgument(env, "buffer must be a direct ByteBuffer");
        return nullptr;
    }
    if (byteOffset < 0 || byteCount < 0 || byteOffset + byteCount > capacity) {
        throwIllegalArgument(env, "range outside the buffer");
        return nullptr;
    }
    return base + byteOffset;
}

int bytesPerSample(jint format) {
    switch (static_cast<SampleFormat>(format)) {
        case SampleFormat::kPcm16: return 2;
        case SampleFormat::kPcm24Packed: return 3;
        case SampleFormat::kPcm32: return 4;
        case SampleFormat::kFloat: return 4;
    }
    return 0;
}

// ---- Lifecycle ---------------------------------------------------------------------

jlong nativeCreate(JNIEnv*, jclass) { return reinterpret_cast<jlong>(new MobileEqEngine()); }

void nativeDestroy(JNIEnv*, jclass, jlong handle) { delete engineOf(handle); }

jboolean nativeConfigure(JNIEnv*, jclass, jlong handle, jint sampleRate, jint channels) {
    return engineOf(handle)->configure(sampleRate, channels) ? JNI_TRUE : JNI_FALSE;
}

// A new sample rate at the same channel count: every coefficient, time
// constant, lookahead length and FFT bin map is recomputed for it.
jboolean nativeSetSampleRate(JNIEnv*, jclass, jlong handle, jint sampleRate) {
    auto* engine = engineOf(handle);
    return engine->configure(sampleRate, engine->channels()) ? JNI_TRUE : JNI_FALSE;
}

void nativeReset(JNIEnv*, jclass, jlong handle) { engineOf(handle)->reset(); }

jint nativeLatencyFrames(JNIEnv*, jclass, jlong handle) { return engineOf(handle)->latencyFrames(); }

// ---- Audio thread --------------------------------------------------------------------

jint nativeProcess(JNIEnv* env, jclass, jlong handle, jobject input, jint inputOffset, jint frames,
                   jint format, jobject output, jint outputOffset) {
    MobileEqEngine* engine = engineOf(handle);
    const int sampleBytes = bytesPerSample(format);
    if (sampleBytes == 0) {
        throwIllegalArgument(env, "unknown sample format");
        return 0;
    }
    if (frames <= 0) return 0;
    const jlong samples = static_cast<jlong>(frames) * engine->channels();
    const uint8_t* in = directRange(env, input, inputOffset, samples * sampleBytes);
    if (in == nullptr) return 0;
    uint8_t* out = directRange(env, output, outputOffset, samples * static_cast<jlong>(sizeof(float)));
    if (out == nullptr) return 0;
    if ((reinterpret_cast<uintptr_t>(out) & (alignof(float) - 1)) != 0) {
        throwIllegalArgument(env, "output must be float-aligned");
        return 0;
    }
    return engine->process(in, static_cast<SampleFormat>(format), frames, reinterpret_cast<float*>(out));
}

jint nativeDrain(JNIEnv* env, jclass, jlong handle, jobject output, jint outputOffset, jint maxFrames) {
    MobileEqEngine* engine = engineOf(handle);
    if (maxFrames <= 0) return 0;
    const jlong bytes = static_cast<jlong>(maxFrames) * engine->channels() * static_cast<jlong>(sizeof(float));
    uint8_t* out = directRange(env, output, outputOffset, bytes);
    if (out == nullptr) return 0;
    return engine->drain(reinterpret_cast<float*>(out), maxFrames);
}

// ---- Controls ------------------------------------------------------------------------

void nativeSetBandGain(JNIEnv*, jclass, jlong handle, jint band, jfloat gainDb) {
    engineOf(handle)->setBandGainDb(band, gainDb);
}

bool readBands(JNIEnv* env, jfloatArray array, float* out) {
    if (array == nullptr || env->GetArrayLength(array) < harmony::eq::kNumBands) {
        throwIllegalArgument(env, "expected 10 band gains");
        return false;
    }
    env->GetFloatArrayRegion(array, 0, harmony::eq::kNumBands, out);
    return !env->ExceptionCheck();
}

void nativeSetBandGains(JNIEnv* env, jclass, jlong handle, jfloatArray gainsDb) {
    float gains[harmony::eq::kNumBands];
    if (readBands(env, gainsDb, gains)) engineOf(handle)->setBandGainsDb(gains);
}

void nativeSetAutoTargetGains(JNIEnv* env, jclass, jlong handle, jfloatArray gainsDb) {
    float gains[harmony::eq::kNumBands];
    if (readBands(env, gainsDb, gains)) engineOf(handle)->setAutoTargetGainsDb(gains);
}

void nativeSetEqEnabled(JNIEnv*, jclass, jlong handle, jboolean enabled) {
    engineOf(handle)->setEqEnabled(enabled == JNI_TRUE);
}

void nativeSetAutoMode(JNIEnv*, jclass, jlong handle, jboolean enabled) {
    engineOf(handle)->setAutoMode(enabled == JNI_TRUE);
}

void nativeSetUserPreamp(JNIEnv*, jclass, jlong handle, jfloat preampDb) {
    engineOf(handle)->setUserPreampDb(preampDb);
}

void nativeSetLevelGain(JNIEnv*, jclass, jlong handle, jfloat gainDb) {
    engineOf(handle)->setLevelGainDb(gainDb);
}

void nativeSetOutputDevice(JNIEnv* env, jclass, jlong handle, jint device) {
    if (device < 0 || device > static_cast<jint>(OutputDevice::kUsbDacHiRes)) {
        throwIllegalArgument(env, "unknown output device");
        return;
    }
    engineOf(handle)->setOutputDevice(static_cast<OutputDevice>(device));
}

void nativeSetAnalyzerEnabled(JNIEnv*, jclass, jlong handle, jboolean enabled) {
    engineOf(handle)->setAnalyzerEnabled(enabled == JNI_TRUE);
}

void nativeSetSpectrumBarCount(JNIEnv*, jclass, jlong handle, jint bars) {
    engineOf(handle)->setSpectrumBarCount(bars);
}

jfloat nativeTargetPreampDb(JNIEnv*, jclass, jlong handle) { return engineOf(handle)->targetPreampDb(); }

jfloat nativeBassDuckDb(JNIEnv*, jclass, jlong handle) { return engineOf(handle)->bassDuckDb(); }

// ---- Spectrum (UI thread, 60 fps) ------------------------------------------------------

jint nativePollSpectrum(JNIEnv* env, jclass, jlong handle, jfloatArray bars, jfloatArray peaks) {
    float barValues[harmony::eq::kMaxBars];
    float peakValues[harmony::eq::kMaxBars];
    int capacity = harmony::eq::kMaxBars;
    if (bars != nullptr) capacity = std::min<int>(capacity, env->GetArrayLength(bars));
    if (peaks != nullptr) capacity = std::min<int>(capacity, env->GetArrayLength(peaks));
    const int count = engineOf(handle)->pollSpectrum(barValues, peakValues, capacity);
    if (bars != nullptr) env->SetFloatArrayRegion(bars, 0, count, barValues);
    if (peaks != nullptr) env->SetFloatArrayRegion(peaks, 0, count, peakValues);
    return count;
}

// The NDK declares JNINativeMethod with const char*, desktop JDKs with char*:
// this table builds with both (the host build runs the Kotlin tests).
#define HARMONY_JNI_METHOD(name, signature) \
    { const_cast<char*>(#name), const_cast<char*>(signature), reinterpret_cast<void*>(name) }

const JNINativeMethod kMethods[] = {
    HARMONY_JNI_METHOD(nativeCreate, "()J"),
    HARMONY_JNI_METHOD(nativeDestroy, "(J)V"),
    HARMONY_JNI_METHOD(nativeConfigure, "(JII)Z"),
    HARMONY_JNI_METHOD(nativeSetSampleRate, "(JI)Z"),
    HARMONY_JNI_METHOD(nativeReset, "(J)V"),
    HARMONY_JNI_METHOD(nativeLatencyFrames, "(J)I"),
    HARMONY_JNI_METHOD(nativeProcess, "(JLjava/nio/ByteBuffer;IIILjava/nio/ByteBuffer;I)I"),
    HARMONY_JNI_METHOD(nativeDrain, "(JLjava/nio/ByteBuffer;II)I"),
    HARMONY_JNI_METHOD(nativeSetBandGain, "(JIF)V"),
    HARMONY_JNI_METHOD(nativeSetBandGains, "(J[F)V"),
    HARMONY_JNI_METHOD(nativeSetAutoTargetGains, "(J[F)V"),
    HARMONY_JNI_METHOD(nativeSetEqEnabled, "(JZ)V"),
    HARMONY_JNI_METHOD(nativeSetAutoMode, "(JZ)V"),
    HARMONY_JNI_METHOD(nativeSetUserPreamp, "(JF)V"),
    HARMONY_JNI_METHOD(nativeSetLevelGain, "(JF)V"),
    HARMONY_JNI_METHOD(nativeSetOutputDevice, "(JI)V"),
    HARMONY_JNI_METHOD(nativeSetAnalyzerEnabled, "(JZ)V"),
    HARMONY_JNI_METHOD(nativeSetSpectrumBarCount, "(JI)V"),
    HARMONY_JNI_METHOD(nativeTargetPreampDb, "(J)F"),
    HARMONY_JNI_METHOD(nativeBassDuckDb, "(J)F"),
    HARMONY_JNI_METHOD(nativePollSpectrum, "(J[F[F)I"),
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
