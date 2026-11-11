plugins {
    alias(libs.plugins.harmony.jvm.library)
}

dependencies {
    api(projects.core.model)
    implementation(projects.domain.library)
    api(libs.kotlinx.coroutines.core)
    // JSON tree only (no code generation): InnerTube answers are read field by field.
    implementation(libs.kotlinx.serialization.json)
    implementation("javax.inject:javax.inject:1")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
