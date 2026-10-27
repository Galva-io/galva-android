plugins {
    alias(libs.plugins.galva.android.library)
    alias(libs.plugins.galva.serialization)
    alias(libs.plugins.galva.publishing)
}
android {
    namespace = "io.galva.sdk.iam"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "JS_BRIDGE_VERSION",findProperty("JS_BRIDGE_VERSION").toString())
    }
    testOptions{
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}
dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(project(":identity"))
    implementation(project(":local-storage"))
    implementation(project(":network"))
    implementation(project(":common"))
    implementation(project(":billing"))
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.activity)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
}