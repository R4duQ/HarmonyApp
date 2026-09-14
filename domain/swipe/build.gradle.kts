plugins {
    alias(libs.plugins.harmony.jvm.library)
}

dependencies {
    api(projects.core.model)
    api(projects.domain.similarity)   // Neighbor is the catalogue's result type
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
