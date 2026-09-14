plugins {
    alias(libs.plugins.harmony.android.library)
    alias(libs.plugins.harmony.hilt)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.harmony.feature.discover"
    buildFeatures { compose = true }
}

dependencies {
    // core:ui exposes Compose, Material3 and the editorial component kit as
    // `api`, so this module needs nothing else for its UI.
    implementation(projects.core.ui)
    implementation(projects.core.model)
    implementation(projects.core.common)          // DispatcherProvider
    implementation(projects.domain.library)
    implementation(projects.domain.playback)
    // Swipe-to-playlist: the deck engine and the acoustic index it queries.
    implementation(projects.domain.swipe)
    implementation(projects.domain.similarity)
    // Custom Tabs. Authentication and cookies stay in the user's own browser
    // rather than in an embedded WebView.
    implementation(libs.androidx.browser)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.media3.exoplayer)

    testImplementation(libs.junit)
}
