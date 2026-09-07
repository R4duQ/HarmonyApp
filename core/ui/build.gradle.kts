plugins {
    alias(libs.plugins.harmony.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.harmony.core.ui"
    buildFeatures { compose = true }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    api(projects.core.model)
    val composeBom = platform(libs.compose.bom)
    api(composeBom)
    api(libs.compose.ui)
    api(libs.compose.material3)
    api(libs.compose.material.icons)
    api(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    api(libs.coil.compose)
    api(libs.androidx.lifecycle.runtime.compose)
    api(libs.androidx.lifecycle.viewmodel.compose)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
