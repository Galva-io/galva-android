import androidx.room.gradle.RoomExtension
import ext.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

abstract class AndroidRoomConventionPlugin: Plugin<Project> {
    override fun apply(target: Project) {
        with(target){
            apply(plugin = "com.google.devtools.ksp")
            apply(plugin = "androidx.room")
            extensions.configure<RoomExtension>{
                schemaDirectory("$projectDir/schemas")
            }
            dependencies{
                "implementation"(libs.findLibrary("room.runtime").get())
                "implementation"(libs.findLibrary("room.ktx").get())
                "ksp"(libs.findLibrary("room.compiler").get())
            }
        }
    }
}