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
        buildConfigField("String", "WEBVIEW_BUNDLE_BASE_URL",findProperty("BASE_WEBVIEW_IAM_URL") as String )
        buildConfigField("String", "BASE_WEBVIEW_IAM_URL_PROD",findProperty("BASE_WEBVIEW_IAM_URL_PROD") as String )
    }
    testOptions{
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}
dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(project(":identity"))
    implementation(project(":local-storage"))
    implementation(project(":network"))
    implementation(project(":common"))
    implementation(project(":billing"))
    implementation(libs.core.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.activity)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
}