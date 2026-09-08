plugins {
    alias(libs.plugins.galva.android.library)
    alias(libs.plugins.galva.serialization)
    alias(libs.plugins.galva.publishing)
}
android {
    namespace = "io.galva.sdk.localstorage"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testOptions{
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}
dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(libs.core.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
}