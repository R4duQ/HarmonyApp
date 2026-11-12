import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.File
import javax.inject.Inject

/**
 * Harmony's native code is Rust: the Cargo workspace in native/ (see its
 * Cargo.toml). This plugin builds the crates a module ships for each Android
 * ABI with the NDK's clang as the linker, and hands the libraries to AGP as
 * generated jniLibs, so `assemble` builds them when the Rust changed.
 *
 *     plugins { id("harmony.rust.jni") }
 *     harmonyRust { crates.add("harmonyeq") }
 *
 * Needs cargo (rustup) with the Android targets of native/rust-toolchain.toml;
 * rustup installs them with that toolchain. `-PharmonyAbi=arm64-v8a` builds one
 * ABI only, as for the rest of the app; `-PharmonyCargo=/path/to/cargo` picks
 * a cargo that is not on the PATH.
 */
class RustJniConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            val settings = extensions.create("harmonyRust", HarmonyRustExtension::class.java)
            settings.packageCppShared.convention(false)
            pluginManager.withPlugin("com.android.library") {
                val android = extensions.getByType(LibraryExtension::class.java)
                val components = extensions.getByType(LibraryAndroidComponentsExtension::class.java)
                val workspace = rootProject.layout.projectDirectory.dir("native")
                val build = tasks.register("cargoBuildRustJni", CargoNdkBuildTask::class.java) {
                    group = "build"
                    description = "Builds this module's Rust JNI libraries (native/) for the Android ABIs."
                    crates.set(settings.crates)
                    packageCppShared.set(settings.packageCppShared)
                    abis.set(
                        providers.gradleProperty("harmonyAbi").map { listOf(it) }
                            .orElse(listOf("arm64-v8a", "x86_64")),
                    )
                    minSdk.set(provider { android.defaultConfig.minSdk ?: 29 })
                    cargo.set(providers.gradleProperty("harmonyCargo").orElse(""))
                    // android.ndkPath when set, else the SDK's ndk/<ndkVersion>. (AGP's own
                    // sdkComponents.ndkDirectory only has a value with an externalNativeBuild.)
                    ndkDirectory.set(
                        components.sdkComponents.sdkDirectory.map { sdk ->
                            android.ndkPath?.let { sdk.dir(File(it).absolutePath) }
                                ?: sdk.dir("ndk/${android.ndkVersion}")
                        },
                    )
                    this.workspace.set(workspace)
                    sources.from(
                        fileTree(workspace) {
                            include("**/*.rs", "**/Cargo.toml", "Cargo.lock", ".cargo/config.toml", "rust-toolchain.toml")
                            exclude("target/**")
                        },
                    )
                    outputDir.set(layout.buildDirectory.dir("rustJniLibs"))
                }
                components.onVariants { variant ->
                    variant.sources.jniLibs?.addGeneratedSourceDirectory(build, CargoNdkBuildTask::outputDir)
                }
            }
        }
    }
}

abstract class HarmonyRustExtension {
    /** The Cargo packages in native/ whose cdylib this module ships, e.g. "harmonyeq". */
    abstract val crates: ListProperty<String>

    /**
     * Also ship the NDK's libc++_shared.so. youtubedl-android's FFmpeg loads it
     * and does not bundle it; one module of the app provides it.
     */
    abstract val packageCppShared: Property<Boolean>
}

/** How each Android ABI is named by Rust, by the NDK's clang wrappers and in the NDK sysroot. */
private data class AndroidTarget(val rust: String, val clang: String, val sysroot: String)

private val TARGETS = mapOf(
    "arm64-v8a" to AndroidTarget("aarch64-linux-android", "aarch64-linux-android", "aarch64-linux-android"),
    "x86_64" to AndroidTarget("x86_64-linux-android", "x86_64-linux-android", "x86_64-linux-android"),
    "armeabi-v7a" to AndroidTarget("armv7-linux-androideabi", "armv7a-linux-androideabi", "arm-linux-androideabi"),
    "x86" to AndroidTarget("i686-linux-android", "i686-linux-android", "i686-linux-android"),
)

@DisableCachingByDefault(because = "Cargo keeps its own incremental build in native/target")
abstract class CargoNdkBuildTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Input abstract val crates: ListProperty<String>
    @get:Input abstract val abis: ListProperty<String>
    @get:Input abstract val minSdk: Property<Int>
    @get:Input abstract val packageCppShared: Property<Boolean>

    /** Empty: cargo from the PATH, else ~/.cargo/bin (IDEs often start without the shell's PATH). */
    @get:Input abstract val cargo: Property<String>

    @get:Internal abstract val ndkDirectory: DirectoryProperty

    @get:Input
    val ndkPath: String get() = ndkDirectory.get().asFile.absolutePath

    @get:Internal abstract val workspace: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val windows = System.getProperty("os.name").lowercase().startsWith("windows")
        val host = when {
            windows -> "windows-x86_64"
            System.getProperty("os.name").lowercase().contains("mac") -> "darwin-x86_64"
            else -> "linux-x86_64"
        }
        val toolchain = ndkDirectory.get().asFile.resolve("toolchains/llvm/prebuilt/$host")
        if (!toolchain.isDirectory) throw GradleException("No NDK toolchain at $toolchain (is ndkVersion installed?)")
        val cargo = resolveCargo(windows)
        val root = workspace.get().asFile
        val out = outputDir.get().asFile
        out.deleteRecursively()

        for (abi in abis.get()) {
            val target = TARGETS[abi] ?: throw GradleException("No Rust target for ABI $abi")
            val clang = toolchain.resolve("bin/${target.clang}${minSdk.get()}-clang" + if (windows) ".cmd" else "")
            val ar = toolchain.resolve("bin/llvm-ar" + if (windows) ".exe" else "")
            if (!clang.isFile) throw GradleException("No NDK clang for $abi at $clang")
            val key = target.rust.replace('-', '_')
            exec.exec {
                // In native/, so Cargo reads its .cargo/config.toml (16 KB pages) and rust-toolchain.toml.
                workingDir = root
                commandLine(
                    listOf(cargo, "build", "--release", "--locked", "--target", target.rust) +
                        crates.get().flatMap { listOf("-p", it) },
                )
                environment("CARGO_TARGET_${key.uppercase()}_LINKER", clang.absolutePath)
                environment("CC_$key", clang.absolutePath)
                environment("AR_$key", ar.absolutePath)
            }
            val abiDir = out.resolve(abi).apply { mkdirs() }
            for (crate in crates.get()) {
                val library = root.resolve("target/${target.rust}/release/lib${crate.replace('-', '_')}.so")
                if (!library.isFile) throw GradleException("Cargo did not produce $library")
                library.copyTo(abiDir.resolve(library.name), overwrite = true)
            }
            if (packageCppShared.get()) {
                val stl = toolchain.resolve("sysroot/usr/lib/${target.sysroot}/libc++_shared.so")
                if (!stl.isFile) throw GradleException("No libc++_shared.so for $abi at $stl")
                stl.copyTo(abiDir.resolve(stl.name), overwrite = true)
            }
        }
    }

    private fun resolveCargo(windows: Boolean): String {
        cargo.get().takeIf { it.isNotBlank() }?.let { return it }
        val name = if (windows) "cargo.exe" else "cargo"
        val onPath = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, name) }.firstOrNull { it.canExecute() }
        val inHome = File(System.getProperty("user.home"), ".cargo/bin/$name").takeIf { it.canExecute() }
        return (onPath ?: inHome)?.absolutePath
            ?: throw GradleException("cargo not found: install Rust with rustup (https://rustup.rs) or pass -PharmonyCargo=")
    }
}
