# Media3 session classes are referenced reflectively by system controllers.
-keep class androidx.media3.session.** { *; }

# JNI entry points: names must survive obfuscation for the native bridge.
-keepclasseswithmembernames class com.harmony.core.dsp.NativeAnalyzer {
    native <methods>;
}

# Room, Hilt, Compose, Coroutines, DataStore all ship consumer rules via AGP;
# no additional keeps required. Verify with a minified release install before
# every release (see docs/release-checklist.md).

# GoMobile-generated Java wrappers call into libgojni by stable class/method names.
# Keep the bridge intact in minified public release builds.
-keep class gobackend.** { *; }
-keep class go.** { *; }
