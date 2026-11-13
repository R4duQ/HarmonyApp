plugins {
    alias(libs.plugins.harmony.android.library)
    alias(libs.plugins.harmony.rust.jni)
}

// libharmonyeq is Rust: native/harmonyeq, built by harmony.rust.jni.
harmonyRust {
    crates.add("harmonyeq")
}

// Harmony's float EQ engine: a Rust DSP core (native/harmonyeq) behind a Media3
// AudioProcessor, a float-output AudioSink that runs it ahead of
// DefaultAudioSink for hi-res sources, and output-device routing.
android {
    namespace = "com.harmony.core.eq"
    // The NDK's clang links the Rust library.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    testOptions.unitTests.isReturnDefaultValues = true
}

// The JVM tests that run the real engine need a host build of libharmonyeq
// (`cargo build --release` in native/); CI points this variable at native/target/release.
// Without it those tests are skipped and the pure-Kotlin ones still run.
tasks.withType<Test>().configureEach {
    System.getenv("HARMONY_EQ_HOST_LIB_DIR")?.let { systemProperty("java.library.path", it) }
}

dependencies {
    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    testImplementation(libs.junit)
}
