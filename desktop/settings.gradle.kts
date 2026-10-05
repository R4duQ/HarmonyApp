// Harmony for Windows: its own Gradle build next to the Android one, sharing
// the pure-Kotlin code (models, Connect protocol, equalizer DSP) by source.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "harmony-desktop"
