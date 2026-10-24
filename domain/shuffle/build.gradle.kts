plugins {
    alias(libs.plugins.harmony.jvm.library)
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.domain.library)
    api(projects.domain.playback)
    implementation("javax.inject:javax.inject:1")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
