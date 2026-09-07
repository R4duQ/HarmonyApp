plugins {
    alias(libs.plugins.harmony.android.library)
    alias(libs.plugins.harmony.hilt)
}

android {
    namespace = "com.harmony.core.datastore"
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
}
