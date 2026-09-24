plugins {
    alias(libs.plugins.harmony.android.library)
    alias(libs.plugins.harmony.hilt)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.harmony.feature.home"
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
    implementation(projects.domain.shuffle)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // HomeViewModelTest builds a real StartSmartMixUseCase, whose engine
    // takes a SimilarityRepository.
    testImplementation(projects.domain.similarity)

    // Home interaction tests: ./gradlew :feature:home:connectedDebugAndroidTest
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation(platform(libs.compose.bom))
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
