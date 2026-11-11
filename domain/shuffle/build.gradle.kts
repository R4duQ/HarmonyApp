plugins {
    alias(libs.plugins.harmony.jvm.library)
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.domain.library)
    api(projects.domain.playback)
    // The online side of "Mix din piesa asta" (YouTube Music + Mornye), behind a setting.
    api(projects.domain.online)
    implementation("javax.inject:javax.inject:1")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
