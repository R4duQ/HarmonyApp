plugins {
    alias(libs.plugins.harmony.android.library)
    alias(libs.plugins.harmony.hilt)
}

android {
    namespace = "com.harmony.data.analysis"
}

dependencies {
    implementation(projects.domain.analysis)
    implementation(projects.domain.shuffle)
    implementation(projects.domain.library)
    implementation(projects.domain.playback)
    implementation(projects.core.database)
    implementation(projects.core.dspNative)
    implementation(projects.core.common)
    implementation(projects.core.model)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
