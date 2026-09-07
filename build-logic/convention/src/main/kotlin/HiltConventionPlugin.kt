import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Applies Hilt + KSP and wires the runtime/compiler dependencies so individual
 * modules only need `alias(libs.plugins.harmony.hilt)`.
 */
class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.google.devtools.ksp")
                apply("com.google.dagger.hilt.android")
            }
            dependencies {
                add("implementation", versionCatalog().findLibrary("hilt-android").get())
                add("ksp", versionCatalog().findLibrary("hilt-compiler").get())
            }
        }
    }
}

private fun Project.versionCatalog() =
    extensions.getByType(org.gradle.api.artifacts.VersionCatalogsExtension::class.java).named("libs")
