plugins {
    alias(libs.plugins.harmony.android.library)
    alias(libs.plugins.harmony.hilt)
    alias(libs.plugins.compose.compiler)
}

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
