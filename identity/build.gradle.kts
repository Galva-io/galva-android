plugins {
    alias(libs.plugins.galva.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.galva.publishing)
}
android {
    namespace = "io.galva.sdk.identity"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}
dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(project(":local-storage"))
    implementation(project(":common"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

}