import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.harmony.android.library)
    alias(libs.plugins.harmony.hilt)
    alias(libs.plugins.compose.compiler)
}

// Reject incomplete native artifacts before creating an installable APK.
val converterAbis = providers.gradleProperty("harmonyAbi").map { listOf(it) }
    .orElse(listOf("arm64-v8a", "x86_64"))
val backendAar = rootProject.layout.projectDirectory.file(
    "vendor-maven/com/harmony/vendor/gobackend/4.9.5/gobackend-4.9.5.aar",
)
val converterRoot = layout.projectDirectory.dir("src/main/jniLibs")
val verifySpotiFlacNativeArtifacts by tasks.registering {
    inputs.property("abis", converterAbis)
    inputs.file(backendAar)
    inputs.dir(converterRoot)
    doLast {
        check(backendAar.asFile.isFile) {
            "Missing SpotiFLAC backend. Run scripts/ci/build-spotiflac-backend.sh."
        }
        ZipFile(backendAar.asFile).use { aar ->
            for (abi in converterAbis.get()) {
                val machine = when (abi) {
                    "arm64-v8a" -> 183
                    "x86_64" -> 62
                    else -> error("Unsupported Harmony ABI: $abi")
                }
                val backend = checkNotNull(aar.getEntry("jni/$abi/libgojni.so")) {
                    "SpotiFLAC is missing $abi. Rebuild the backend for both supported ABIs."
                }
                val converter = converterRoot.file("$abi/libharmony_flac.so").asFile
                check(converter.isFile) {
                    "Missing $abi audio converter. Run scripts/ci/build-audio-converter.sh."
                }
                val headers = listOf(
                    aar.getInputStream(backend).use { it.readNBytes(20) },
                    converter.inputStream().use { it.readNBytes(20) },
                )
                for (header in headers) {
                    check(header.size == 20 && header[0] == 127.toByte() &&
                        header[1] == 69.toByte() && header[2] == 76.toByte() &&
                        header[3] == 70.toByte() && header[4] == 2.toByte() &&
                        header[5] == 1.toByte() &&
                        (header[18].toInt() and 255) + ((header[19].toInt() and 255) shl 8) == machine
                    ) { "Wrong or damaged native runtime for $abi. Rebuild the native artifacts." }
                }
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(verifySpotiFlacNativeArtifacts) }

android {
    namespace = "com.harmony.feature.downloads"
    buildFeatures { compose = true }
}

dependencies {
    // Local Maven dependency containing the real SpotiFLAC Mobile v4.9.5
    // GoMobile bridge. It is used by both debug and release variants so local
    // phone builds support current extensions that require downloadSegments.
    implementation("com.harmony.vendor:gobackend:4.9.5")
    implementation(projects.core.ui)
    implementation(projects.core.common)
    implementation(projects.core.database)
    // Persists the Soulseek format preference (FLAC / MP3) across restarts.
    implementation(projects.core.datastore)
    implementation(projects.domain.analysis)
    // ScanLibraryUseCase: a finished download triggers an incremental scan so
    // the track appears without a manual rescan.
    implementation(projects.domain.library)
    implementation(projects.domain.playback)
    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(projects.data.analysis)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Free, on-device yt-dlp + FFmpeg conversion.
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
