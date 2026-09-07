plugins {
    alias(libs.plugins.harmony.jvm.library)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation("javax.inject:javax.inject:1")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
