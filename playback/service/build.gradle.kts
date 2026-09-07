plugins {
    alias(libs.plugins.harmony.android.library)
    alias(libs.plugins.harmony.hilt)
}

android {
    namespace = "com.harmony.playback.service"
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

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.common)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
