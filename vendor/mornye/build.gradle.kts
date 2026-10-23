plugins {
    alias(libs.plugins.harmony.android.library)
}

// Mornye's Rust backend and its UniFFI Kotlin bindings, for the Mornye build of
// Harmony. scripts/ci/build-mornye-backend.sh stages them under vendor-mornye/
// before Gradle runs; the stable build never touches this module.
val staged = rootDir.resolve("vendor-mornye")

android {
    namespace = "com.mornye.backend"
    sourceSets.getByName("main") {
        java.srcDir(staged.resolve("kotlin"))
        jniLibs.srcDir(staged.resolve("jniLibs"))
    }
    defaultConfig {
        // UniFFI resolves the generated FFI names through JNA at run time.
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    // UniFFI's Kotlin bindings call the Rust library through JNA.
    api("net.java.dev.jna:jna:5.19.1@aar")
    implementation(libs.kotlinx.coroutines.android)
}
