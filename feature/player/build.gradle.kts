plugins {
    alias(libs.plugins.harmony.android.library)
    alias(libs.plugins.harmony.hilt)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.harmony.feature.player"
    buildFeatures { compose = true }

    // QueueSheetInteractionTest drags the queue's rows on Robolectric.
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            test.testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}

dependencies {
    implementation(projects.core.ui)
    implementation(projects.core.model)
    implementation(projects.core.datastore)
    implementation(projects.domain.playback)
    implementation(projects.domain.library)
    implementation(projects.domain.shuffle)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    val composeBom = platform(libs.compose.bom)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(composeBom)
    testImplementation(libs.compose.ui.test.junit4)
    // The test activity Robolectric launches, for every variant CI tests (release included).
    testImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation(composeBom)
    debugImplementation(libs.compose.ui.test.manifest)
    // The mini player motion lab (src/debug), launched on a phone with adb.
    debugImplementation(libs.androidx.activity.compose)
}
