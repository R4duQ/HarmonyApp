plugins {
    alias(libs.plugins.harmony.android.library)
    alias(libs.plugins.harmony.hilt)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.harmony.feature.playlists"
    buildFeatures { compose = true }

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    implementation(projects.core.ui)
    implementation(projects.core.model)
    implementation(projects.domain.library)
    implementation(projects.domain.playback)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Playlist detail interaction tests (swipe-down-to-go-back, reorder,
    // buttons, states): ./gradlew :feature:playlists:connectedDebugAndroidTest
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation(platform(libs.compose.bom))
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
