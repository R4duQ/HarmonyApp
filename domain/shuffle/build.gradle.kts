plugins {
    alias(libs.plugins.harmony.jvm.library)
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.domain.similarity)
    implementation(projects.domain.library)
    implementation(projects.domain.playback)
    implementation(projects.domain.analysis)   // embedding math for Journey interpolation
    implementation("javax.inject:javax.inject:1")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
