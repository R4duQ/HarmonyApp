plugins {
    alias(libs.plugins.harmony.android.library)
    alias(libs.plugins.harmony.hilt)
}

// Smart Shuffle's data side: SmartShuffleDatabase (24-D vectors, Markov
// habits, the zone bandit), the ShuffleBrain implementation on the native
// SmartShuffleEngine, and the SHUFFLE_BRAIN Logcat writer.
android {
    namespace = "com.harmony.data.shuffle"

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    testOptions.unitTests.isReturnDefaultValues = true
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.database)
    implementation(projects.core.dspNative)
    implementation(projects.domain.shuffle)
    implementation(projects.domain.analysis)
    implementation(projects.domain.library)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
