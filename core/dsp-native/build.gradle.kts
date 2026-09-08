plugins {
    alias(libs.plugins.harmony.android.library)
}

android {
    namespace = "com.harmony.core.dsp"
    ndkVersion = "29.0.14206865"

    val requestedAbi = providers.gradleProperty("harmonyAbi").orNull

    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
        ndk {
            // Match the app and SpotiFLAC backend: both supported 64-bit ABIs
            // by default; use an explicit property for a single-ABI build.
            abiFilters += requestedAbi?.let { listOf(it) }
                ?: listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
