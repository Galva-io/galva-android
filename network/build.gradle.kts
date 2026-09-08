plugins {
    alias(libs.plugins.galva.android.library)
    alias(libs.plugins.galva.serialization)
    alias(libs.plugins.galva.publishing)
}
android {
    namespace = "io.galva.sdk.network"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}
dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(project(":common"))
    implementation(libs.core.ktx)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}