# eq_jni.cpp registers its natives on this class by name in JNI_OnLoad.
-keep class com.harmony.core.eq.MobileEqNative {
    native <methods>;
}
