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

// NativeShuffleBrainRefreshTest runs the real engine on the JVM against in-memory
// Room databases: CI builds the host libharmonyshuffle (native/) and points this
// variable at it. Without it that test is skipped.
tasks.withType<Test>().configureEach {
    System.getenv("HARMONY_SHUFFLE_HOST_LIB_DIR")?.let { systemProperty("java.library.path", it) }
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
    testImplementation(libs.robolectric)
}
