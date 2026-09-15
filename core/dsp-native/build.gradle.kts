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
                // FFmpeg's bundled dependencies need libc++_shared.so too.
                // Static STL (the CMake default) does not package that file.
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
        ndk {
            // 64-bit only: Play requires arm64; x86_64 covers emulators.
            // armeabi-v7a can be added if field data shows 32-bit demand.
            // The phone installer passes -PharmonyAbi=arm64-v8a, so do not
            // configure the unused emulator ABI during a phone-only build.
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
