// JNI bridge for com.harmony.core.dsp.NativeAnalyzer.
// Thin by design: no logic, just handle + array marshalling. The critical
// array is passed with GetFloatArrayElements/JNI_ABORT on the input path
// (read-only, no copy-back) to keep per-chunk overhead minimal.

#include <jni.h>
#include "harmony_dsp.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_harmony_core_dsp_NativeAnalyzer_nativeCreate(JNIEnv*, jobject, jint sampleRate) {
    return reinterpret_cast<jlong>(harmony_create(sampleRate));
}

JNIEXPORT void JNICALL
Java_com_harmony_core_dsp_NativeAnalyzer_nativeProcess(
        JNIEnv* env, jobject, jlong handle, jfloatArray samples, jint count) {
    auto* h = reinterpret_cast<HarmonyAnalyzer*>(handle);
    if (!h || !samples || count <= 0) return;
    jfloat* data = env->GetFloatArrayElements(samples, nullptr);
    if (!data) return;
    const jint len = env->GetArrayLength(samples);
    harmony_process(h, data, count < len ? count : len);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
}

JNIEXPORT jfloatArray JNICALL
Java_com_harmony_core_dsp_NativeAnalyzer_nativeFinish(JNIEnv* env, jobject, jlong handle) {
    auto* h = reinterpret_cast<HarmonyAnalyzer*>(handle);
    if (!h) return nullptr;
    float out[HARMONY_FEATURE_COUNT];
    if (harmony_finish(h, out, HARMONY_FEATURE_COUNT) != HARMONY_FEATURE_COUNT) return nullptr;
    jfloatArray result = env->NewFloatArray(HARMONY_FEATURE_COUNT);
    if (result) env->SetFloatArrayRegion(result, 0, HARMONY_FEATURE_COUNT, out);
    return result;
}

JNIEXPORT void JNICALL
Java_com_harmony_core_dsp_NativeAnalyzer_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    harmony_destroy(reinterpret_cast<HarmonyAnalyzer*>(handle));
}

} // extern "C"
