plugins {
    alias(libs.plugins.harmony.android.library)
    alias(libs.plugins.harmony.hilt)
}

android {
    namespace = "com.harmony.sync.analysis"
}

dependencies {
    implementation(projects.domain.library)
    implementation(projects.domain.analysis)
    implementation(projects.core.common)
    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)
}
