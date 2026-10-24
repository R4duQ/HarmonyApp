plugins {
    alias(libs.plugins.harmony.android.library)
    alias(libs.plugins.harmony.hilt)
}

android {
    namespace = "com.harmony.playback.service"
    // The player tests use Media3's plain classes (MediaItem, Timeline, events),
    // which touch a few Android framework calls; on the JVM those return defaults.
    testOptions.unitTests.isReturnDefaultValues = true
}

dependencies {
    api(projects.domain.playback)
    implementation(projects.domain.library)
    // ArtworkProvider: browse items point at it so the Auto host can read
    // artwork across the process boundary.
    implementation(projects.core.media)
    implementation(projects.core.common)
    implementation(projects.core.model)
    implementation(projects.core.datastore)
    // Harmony Connect: playing on a computer running Harmony for Windows.
    implementation(projects.core.remote)
    // The equalizer: the native float engine, its hi-res sink and output routing.
    implementation(projects.core.eqEngine)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.common)
    // DefaultExtractorsFactory flags for real-world files (CBR and index seeking).
    implementation(libs.media3.extractor)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
