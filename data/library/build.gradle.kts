plugins {
    alias(libs.plugins.harmony.android.library)
    alias(libs.plugins.harmony.hilt)
}

android {
    namespace = "com.harmony.data.library"
}

dependencies {
    implementation(projects.domain.library)
    implementation(projects.core.database)
    implementation(projects.core.media) // MetadataExtractor.songId for artist ids
    implementation(libs.paging.runtime)
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
