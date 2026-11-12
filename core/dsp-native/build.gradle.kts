plugins {
    alias(libs.plugins.harmony.android.library)
    alias(libs.plugins.harmony.rust.jni)
}

// The song analyzer (libharmonydsp) and Smart Shuffle (libharmonyshuffle) are
// Rust: native/harmonydsp and native/harmonyshuffle, built by harmony.rust.jni.
harmonyRust {
    crates.addAll("harmonydsp", "harmonyshuffle")
    // FFmpeg (youtubedl-android) loads libc++_shared.so and does not bundle it.
    packageCppShared.set(true)
}

android {
    namespace = "com.harmony.core.dsp"
    // The NDK's clang links the Rust libraries.
    ndkVersion = "29.0.14206865"
}

// NativeShuffleEngineTest runs the real engine on the JVM: CI builds the host
// libharmonyshuffle (`cargo build --release` in native/) and points this
// variable at native/target/release. Without it
// those tests are skipped.
android.testOptions.unitTests.isReturnDefaultValues = true
tasks.withType<Test>().configureEach {
    System.getenv("HARMONY_SHUFFLE_HOST_LIB_DIR")?.let { systemProperty("java.library.path", it) }
}

dependencies {
    api(projects.core.model)
    testImplementation(libs.junit)
}
