import java.util.Properties

plugins {
    alias(libs.plugins.harmony.android.application)
    alias(libs.plugins.harmony.hilt)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.harmony.app"

    defaultConfig {
        applicationId = "com.harmony.app"
        versionCode = 85
        versionName = "1.0.2-flac-compat"

        // Optional single-ABI build: -PharmonyAbi=arm64-v8a
        //
        // A debug APK ships every supported ABI's native libraries, which is
        // right for a store upload and pure waste when installing on one
        // known phone — the unused ABI is dead weight over the wire. Opt-in
        // via a property so ordinary builds and CI are unaffected.
        providers.gradleProperty("harmonyAbi").orNull?.let { abi ->
            ndk { abiFilters += abi }
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // Equivalent of android:extractNativeLibs="true", which AGP now
            // wants expressed here rather than in AndroidManifest.xml.
            //
            // This must stay TRUE. youtubedl-android reads its Python and
            // FFmpeg payloads as real files at runtime, so they have to be
            // unpacked into the install directory. Letting this default to
            // false (uncompressed, loaded straight from the APK) is the
            // tempting "modern" choice and it breaks yt-dlp at runtime.
            useLegacyPackaging = true

            // youtubedl-android ships yt-dlp, Python and FFmpeg as ZIP
            // archives renamed to .so, because Android only extracts files
            // with that extension out of jniLibs. They are not ELF objects,
            // so llvm-strip rejects them:
            //
            //   llvm-strip.exe: error: '...libffmpeg.zip.so':
            //   The file was not recognized as a valid object file
            //
            // keepDebugSymbols excludes them from the strip task. The
            // patterns cover every ABI in merged_native_libs, which holds all
            // four ABIs from the AAR regardless of the -PharmonyAbi filter
            // applied later at packaging time.
            keepDebugSymbols += setOf(
                "**/libffmpeg.zip.so",
                "**/libpython.zip.so",
                "**/libaria2c.zip.so",
                "*/*/libffmpeg.zip.so",
                "*/*/libpython.zip.so",
                "*/*/libaria2c.zip.so",
            )
        }
    }

    // Release signing supports either an untracked keystore.properties
    // (local builds) or ephemeral environment variables (GitHub Actions).
    // Secrets never need to be committed and CI does not write passwords to disk.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val releaseStoreFile = keystoreProps.getProperty("storeFile")
        ?: System.getenv("HARMONY_KEYSTORE_FILE")
    val releaseStorePassword = keystoreProps.getProperty("storePassword")
        ?: System.getenv("HARMONY_KEYSTORE_PASSWORD")
    val releaseKeyAlias = keystoreProps.getProperty("keyAlias")
        ?: System.getenv("HARMONY_KEY_ALIAS")
    val releaseKeyPassword = keystoreProps.getProperty("keyPassword")
        ?: System.getenv("HARMONY_KEY_PASSWORD")
    val releaseSigningValues = listOf(
        releaseStoreFile,
        releaseStorePassword,
        releaseKeyAlias,
        releaseKeyPassword,
    )
    val hasAnyReleaseSigning = releaseSigningValues.any { !it.isNullOrBlank() }
    val hasReleaseSigning = releaseSigningValues.all { !it.isNullOrBlank() }
    if (hasAnyReleaseSigning && !hasReleaseSigning) {
        error("Incomplete Harmony release signing configuration")
    }
    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            ndk {
                // Native debug symbols are a large share of a debug APK and
                // are only useful for native crash symbolication, which
                // isn't happening on a phone-only workflow.
                debugSymbolLevel = "none"
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    bundle {
        // Native lib is per-ABI; language/density splits are Play defaults.
        abi { enableSplit = true }
    }
}

dependencies {
    implementation(projects.playback.service)
    implementation(projects.core.ui)
    implementation(projects.core.datastore)
    implementation(projects.feature.library)
    implementation(projects.feature.player)
    implementation(projects.feature.playlists)
    implementation(projects.feature.equalizer)
    implementation(projects.feature.settings)
    implementation(projects.feature.downloads)
    implementation(projects.feature.discover)
    implementation(projects.feature.home)
    implementation(projects.data.library)
    implementation(projects.data.analysis)
    implementation(projects.data.similarity)
    implementation(projects.domain.similarity)
    implementation(projects.domain.shuffle)
    implementation(projects.sync.analysisWorker)
    implementation(projects.core.database)
    implementation(projects.core.media)
    implementation(projects.domain.library)
    implementation(projects.domain.playback)
    implementation(projects.core.model)
    implementation(projects.core.common)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.coil.compose)
    implementation("io.coil-kt:coil:2.7.0")
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
}
