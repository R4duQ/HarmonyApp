plugins {
    `kotlin-dsl`
}

group = "com.harmony.buildlogic"

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.ksp.gradle.plugin)
    compileOnly(libs.compose.compiler.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "harmony.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "harmony.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("hilt") {
            id = "harmony.hilt"
            implementationClass = "HiltConventionPlugin"
        }
        register("jvmLibrary") {
            id = "harmony.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
    }
}
