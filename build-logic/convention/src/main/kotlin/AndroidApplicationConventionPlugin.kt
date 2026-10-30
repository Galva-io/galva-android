import com.android.build.api.dsl.ApplicationExtension
import ext.configAndroidKotlin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure

abstract class AndroidApplicationConventionPlugin : Plugin<Project>{
    override fun apply(target: Project) {
       with(target){
           apply(plugin = "com.android.application")
           extensions.configure<ApplicationExtension>{
               configAndroidKotlin(this)
               buildFeatures.buildConfig = true
           }
       }
    }
}