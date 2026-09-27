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

# youtubedl-android (the YouTube converter). It unpacks Python and yt-dlp with
# commons-compress, whose zip extra-field registry creates classes through
# Class.newInstance(). Without these keeps R8 drops their constructors and the
# downloader can't start ("class X is not a concrete class"). Same rules as the
# youtubedl-android README.
-keep class com.yausername.** { *; }
-keep class org.apache.commons.compress.archivers.zip.** { *; }
