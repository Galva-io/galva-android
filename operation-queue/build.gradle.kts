plugins {
    alias(libs.plugins.galva.android.library)
    alias(libs.plugins.galva.publishing)
    alias(libs.plugins.galva.serialization)
}
android {
    namespace = "io.galva.sdk.operationqueue"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}
dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(project(":common"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation((libs.core.ktx))
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
}