plugins {
    alias(libs.plugins.harmony.android.library)
    alias(libs.plugins.harmony.hilt)
}

android {
    namespace = "com.harmony.data.similarity"
}

dependencies {
    implementation(projects.domain.similarity)
    implementation(projects.domain.analysis)  // EmbeddingCodec + EMBEDDING_DIM
    implementation(projects.core.database)
    implementation(projects.core.common)
    implementation(projects.core.model)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
