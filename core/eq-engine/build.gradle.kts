plugins {
    alias(libs.plugins.harmony.android.library)
}

// Harmony's float EQ engine: a C++ DSP core (MobileEqEngine) behind a Media3
// AudioProcessor, a float-output AudioSink that runs it ahead of
// DefaultAudioSink for hi-res sources, and output-device routing.
android {
    namespace = "com.harmony.core.eq"
    ndkVersion = "29.0.14206865"

    val requestedAbi = providers.gradleProperty("harmonyAbi").orNull

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                // One shared C++ runtime for the whole app (see core/dsp-native).
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
        ndk {
            abiFilters += requestedAbi?.let { listOf(it) } ?: listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    testOptions.unitTests.isReturnDefaultValues = true
}

// The JVM tests that run the real engine need a host build of libharmonyeq
// (src/test/cpp/CMakeLists.txt); CI builds it and points this variable at it.
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
