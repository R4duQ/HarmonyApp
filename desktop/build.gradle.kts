import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
}

group = "com.harmony"

/** The installer's version; CI passes 1.0.<run number> so every build installs over the last. */
val desktopVersion = (findProperty("desktopVersion") as String?) ?: "1.0.0"
version = desktopVersion

// The equalizer's filters are Android-free Kotlin that lives with the phone's
// audio chain; copy them in rather than moving them out of :playback:service.
val sharedDsp = layout.buildDirectory.dir("shared-dsp")
val syncSharedDsp by tasks.registering(Sync::class) {
    from("../playback/service/src/main/kotlin/com/harmony/playback/service/player") {
        include("WinampFilterBank.kt", "ClarityLayer.kt")
    }
    into(sharedDsp.map { it.dir("com/harmony/playback/service/player") })
}

// Built with whichever JDK runs Gradle (17 in CI, which also packages the installer); bytecode for 17.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    sourceSets.named("main") {
        kotlin.srcDirs(
            "src/main/kotlin",
            "../core/model/src/main/kotlin",
            "../core/remote/src/main/kotlin",
            sharedDsp,
        )
    }
}

tasks.named("compileKotlin") { dependsOn(syncSharedDsp) }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation(compose.desktop.uiTestJUnit4)
}

tasks.test {
    systemProperty("java.awt.headless", "true")
    (findProperty("ffmpegDir") as String?)?.let { systemProperty("harmony.ffmpeg.dir", it) }
    systemProperty("screenshots.dir", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
    testLogging { events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

compose.desktop {
    application {
        mainClass = "com.harmony.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Harmony"
            packageVersion = desktopVersion
            description = "Harmony for Windows"
            vendor = "Harmony"
            copyright = "Harmony"
            // ffmpeg.exe and ffprobe.exe are put in resources/windows by CI.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            modules("java.desktop", "java.logging", "java.naming", "jdk.unsupported", "jdk.crypto.ec")
            windows {
                menuGroup = "Harmony"
                shortcut = true
                menu = true
                dirChooser = true
                perUserInstall = true
                // Fixed for good: lets each new installer replace the old one.
                upgradeUuid = "6f3d6a7e-6b1e-4c86-9d5e-6a3f4f0e8c21"
                project.layout.projectDirectory.file("icon.ico").asFile.takeIf { it.exists() }?.let { iconFile.set(it) }
            }
        }
    }
}
