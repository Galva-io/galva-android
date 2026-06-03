import com.android.build.api.dsl.LibraryExtension
import ext.configAndroidKotlin
import ext.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

abstract class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "com.android.library")
            apply(plugin = "org.jetbrains.kotlinx.kover")
            extensions.configure<LibraryExtension> {
                configAndroidKotlin(this)
                buildFeatures.buildConfig = true
            }
        }
    }
}