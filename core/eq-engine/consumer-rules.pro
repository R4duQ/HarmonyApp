# libharmonyeq (Rust) exports its natives by their JNI names: keep the class and method names.
-keep class com.harmony.core.eq.MobileEqNative {
    native <methods>;
}
